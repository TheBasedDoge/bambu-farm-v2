package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.AiPromptService;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AI Settings view — configure and monitor Ollama-based print checks at runtime.
 *
 * Accessible from the sidebar. Admin only.
 */
@Route(value = "ai-settings", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("AI Settings")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class AiSettingsView extends VerticalLayout implements NotificationHelper, ViewHelper {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss");

    @Inject
    BambuConfig config;
    @Inject
    PrintAiService aiService;
    @Inject
    OllamaService ollama;
    @Inject
    AiPromptService prompts;
    @Inject
    BambuPrinters printers;
    @Inject
    com.tfyre.bambu.printer.BedReferenceService bedReference;
    @Inject
    org.eclipse.microprofile.context.ManagedExecutor executor;

    private final Grid<PrinterAiRow> grid = new Grid<>();
    private final Grid<PrintAiService.CheckRecord> historyGrid = new Grid<>();
    private final Div lastChecksDiv = new Div();
    private final Span statusSpan = new Span();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        build();
    }

    private void build() {
        addClassName("ai-settings-view");
        addClassName("ai-settings-wide");
        setPadding(true);
        setSpacing(true);

        add(new H3("AI Print Monitoring — Settings"));
        add(buildConnectionSection());
        add(buildControlSection());
        add(buildBedReferenceSection());
        add(buildResultsSection());
        add(buildLastChecksSection());
        add(buildHistorySection());
        add(buildPromptsSection());
    }

    // -------------------------------------------------------------------------
    // Experimental: empty-bed reference compare
    // -------------------------------------------------------------------------

    private Div buildBedReferenceSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Empty-bed reference (experimental)"));
        section.add(new Span("Save a photo of each printer's EMPTY bed. When enabled, the bed-clear check compares "
                + "the live frame against that reference (two images to the model) instead of judging one image "
                + "alone - much more reliable, since the model has a per-printer ground truth for the bed texture, "
                + "glue marks and lighting. Retake the reference whenever you change the plate or move the camera."));

        final Checkbox toggle = new Checkbox("Use empty-bed reference for the bed-clear check");
        toggle.setValue(bedReference.isEnabled());
        toggle.setEnabled(ollama.isEnabled());
        toggle.addValueChangeListener(e -> {
            bedReference.setEnabled(Boolean.TRUE.equals(e.getValue()));
            showNotification("Empty-bed reference compare " + (bedReference.isEnabled() ? "enabled" : "disabled"));
        });
        section.add(toggle);

        final Div cards = new Div();
        cards.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "16px").set("margin-top", "12px");
        printers.getPrinters().forEach(p -> cards.add(buildBedReferenceCard(p.getName())));
        section.add(cards);
        return section;
    }

    private Div buildBedReferenceCard(final String printerName) {
        final Div card = new Div();
        card.addClassName("ai-settings-section");
        card.getStyle().set("max-width", "300px");
        final Span title = new Span(printerName);
        title.getStyle().setFontWeight("bold");
        card.add(new Div(title));

        final Div imgHolder = new Div();
        card.add(imgHolder);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            imgHolder.removeAll();
            bedReference.getReference(printerName).ifPresentOrElse(bytes -> {
                final Image img = new Image(new StreamResource("bed-ref-%s.jpg".formatted(printerName),
                        () -> new ByteArrayInputStream(bytes)), "empty-bed reference for " + printerName);
                img.setWidth("280px");
                img.getStyle().set("border-radius", "6px");
                imgHolder.add(img);
            }, () -> {
                final Span none = new Span("No reference saved yet");
                none.getStyle().setColor("var(--lumo-secondary-text-color)");
                imgHolder.add(none);
            });
        };
        refresh[0].run();

        final Button saveBtn = new Button("Save current frame", new Icon(VaadinIcon.CAMERA));
        saveBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        saveBtn.setTooltipText("Make sure the bed is EMPTY, then capture the current camera frame as this printer's reference");
        saveBtn.setEnabled(ollama.isEnabled());
        saveBtn.addClickListener(e -> {
            showNotification("%s: capturing empty-bed reference…".formatted(printerName));
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            executor.submit(() -> {
                final Optional<com.tfyre.bambu.printer.BambuConst.LightMode> prior = aiService.illuminateForCheck(printerName);
                final Optional<byte[]> snap = aiService.getSnapshot(printerName);
                aiService.restoreLight(printerName, prior);
                ui.ifPresent(u -> u.access(() -> {
                    if (snap.isEmpty()) {
                        showError("%s: no camera snapshot could be grabbed".formatted(printerName));
                        return;
                    }
                    try {
                        bedReference.saveReference(printerName, snap.get());
                        refresh[0].run();
                        showNotification("%s: empty-bed reference saved".formatted(printerName));
                    } catch (RuntimeException ex) {
                        showError(ex.getMessage());
                    }
                }));
            });
        });
        final Button clearBtn = new Button("Clear", new Icon(VaadinIcon.TRASH));
        clearBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        clearBtn.addClickListener(e -> {
            bedReference.clearReference(printerName);
            refresh[0].run();
            showNotification("%s: reference cleared".formatted(printerName));
        });
        card.add(new HorizontalLayout(saveBtn, clearBtn));
        return card;
    }

    // -------------------------------------------------------------------------
    // Connection section
    // -------------------------------------------------------------------------

    private Div buildConnectionSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");

        final boolean configured = ollama.isEnabled();
        final String urlText = config.ollama().url().orElse("(not configured)");
        final String model = config.ollama().model();
        final String failureInterval = config.ollama().failureCheckInterval().toString();
        final String firstLayerDelay = config.ollama().firstLayerDelay().toString();
        final String timeout = config.ollama().timeout().toString();

        final Span connectionBadge = new Span(configured ? "● Connected" : "● Not configured");
        connectionBadge.getStyle()
                .setColor(configured ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)")
                .setFontWeight("bold");

        section.add(new H4("Ollama Connection"));
        section.add(row("Status:", connectionBadge));
        section.add(row("URL:", new Span(urlText)));
        section.add(row("Model:", new Span(model)));
        section.add(row("Failure check interval:", new Span(failureInterval)));
        section.add(row("First-layer delay:", new Span(firstLayerDelay)));
        section.add(row("Request timeout:", new Span(timeout)));
        section.add(new Span("To change these, edit bambu.ollama.* in your configuration file and restart."));

        return section;
    }

    // -------------------------------------------------------------------------
    // Runtime control section
    // -------------------------------------------------------------------------

    private Div buildControlSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Runtime Controls"));

        final Checkbox enableToggle = new Checkbox("AI checks enabled");
        enableToggle.setValue(aiService.isRuntimeEnabled());
        enableToggle.setTooltipText("Disable to suspend all scheduled AI checks without restarting. On-demand checks are also disabled.");
        enableToggle.addValueChangeListener(e -> {
            aiService.setRuntimeEnabled(e.getValue());
            showNotification("AI checks " + (e.getValue() ? "enabled" : "disabled"));
        });

        section.add(enableToggle);

        if (!ollama.isEnabled()) {
            final Span note = new Span("⚠ Ollama URL is not configured — set bambu.ollama.url to enable AI checks.");
            note.getStyle().setColor("var(--lumo-error-text-color)");
            section.add(note);
        }

        return section;
    }

    // -------------------------------------------------------------------------
    // Results section
    // -------------------------------------------------------------------------

    private Div buildResultsSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Last Check Results per Printer"));

        if (grid.getColumns().isEmpty()) {
            // Guard: this view can be re-attached (it lives inside the Automation page's tabs) and the grids
            // are fields - reconfiguring columns on every attach would duplicate them.
            grid.addColumn(PrinterAiRow::printerName).setHeader("Printer").setAutoWidth(true);
            grid.addColumn(PrinterAiRow::checkType).setHeader("Check").setAutoWidth(true);
            grid.addComponentColumn(row -> {
                final Span s = new Span(row.resultText());
                s.getStyle().setColor(switch (row.severity()) {
                    case OK -> "var(--lumo-success-text-color)";
                    case WARN -> "#856404";
                    case FAIL -> "var(--lumo-error-text-color)";
                });
                return s;
            }).setHeader("Result").setFlexGrow(1);
            grid.addColumn(PrinterAiRow::timeAgo).setHeader("When").setAutoWidth(true);
            grid.addComponentColumn(row -> {
                final Button btn = new Button("Check Now", new Icon(VaadinIcon.EYE));
                btn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                btn.setTooltipText("Run a failure detection check on this printer now");
                btn.setEnabled(aiService.isEnabled());
                btn.addClickListener(e -> doManualCheck(row.printerName()));
                return btn;
            }).setHeader("Action").setAutoWidth(true);

            grid.setWidth("100%");
            grid.setAllRowsVisible(true);
            grid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES);
            grid.setColumnReorderingAllowed(true);
            grid.getColumns().forEach(c -> c.setResizable(true));
        }

        section.add(grid);

        final Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH), e -> refreshGrid());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        section.add(refresh);
        section.add(statusSpan);

        refreshGrid();
        return section;
    }

    // -------------------------------------------------------------------------
    // Last checked snapshot per printer
    // -------------------------------------------------------------------------

    private Div buildLastChecksSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Last Checked Snapshot per Printer"));
        section.add(new Span("The exact camera frame the AI analyzed last, why the check ran, and what it concluded. Click an image to enlarge."));
        lastChecksDiv.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "16px");
        section.add(lastChecksDiv);
        populateLastChecks();
        return section;
    }

    private void populateLastChecks() {
        lastChecksDiv.removeAll();
        printers.getPrinters().forEach(printer -> aiService.getLastCheck(printer.getName()).ifPresent(rec -> {
            final Div card = new Div();
            card.getStyle().set("max-width", "340px");
            final Span title = new Span("%s — %s".formatted(rec.printer(), checkTypeLabel(rec.checkType())));
            title.getStyle().setFontWeight("bold");
            final Div details = new Div(
                    new Div(title),
                    new Div(triggerChip(rec.trigger())),
                    new Div(resultSpan(rec)),
                    new Div(timeSpan(rec)));
            if (rec.context() != null && !rec.context().isBlank()) {
                final Span ctx = new Span("HMS/error hint given to the model: " + rec.context());
                ctx.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-style", "italic");
                details.add(new Div(ctx));
            }
            if (rec.snapshot() != null) {
                card.add(snapshotImage(rec, "320px"));
            } else {
                final Span none = new Span("(no snapshot could be grabbed)");
                none.getStyle().setColor("var(--lumo-error-text-color)");
                card.add(new Div(none));
            }
            card.add(details);
            lastChecksDiv.add(card);
        }));
        if (lastChecksDiv.getComponentCount() == 0) {
            lastChecksDiv.add(new Span("No checks have run yet this session."));
        }
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    private Div buildHistorySection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Check History"));
        section.add(new Span("The last %d check attempts across the farm, newest first (in-memory - resets on restart). Click a row to see the analyzed snapshot.".formatted(50)));

        if (historyGrid.getColumns().isEmpty()) {
            // Same re-attach guard as the results grid above
            historyGrid.addColumn(rec -> TIME_FMT.format(rec.at().atZone(ZoneId.systemDefault()))).setHeader("When").setAutoWidth(true);
            historyGrid.addColumn(PrintAiService.CheckRecord::printer).setHeader("Printer").setAutoWidth(true);
            historyGrid.addColumn(rec -> checkTypeLabel(rec.checkType())).setHeader("Check").setAutoWidth(true);
            historyGrid.addComponentColumn(rec -> triggerChip(rec.trigger())).setHeader("Trigger").setAutoWidth(true);
            historyGrid.addComponentColumn(this::resultSpan).setHeader("Result").setAutoWidth(true);
            historyGrid.addColumn(PrintAiService.CheckRecord::description).setHeader("Description").setFlexGrow(1);
            historyGrid.setWidth("100%");
            historyGrid.setAllRowsVisible(true);
            historyGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
            historyGrid.setColumnReorderingAllowed(true);
            historyGrid.getColumns().forEach(c -> c.setResizable(true));
            historyGrid.addItemClickListener(e -> showSnapshotDialog(e.getItem()));
        }
        historyGrid.setItems(aiService.getHistory());

        section.add(historyGrid);
        return section;
    }

    private void showSnapshotDialog(final PrintAiService.CheckRecord rec) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("%s — %s (%s)".formatted(rec.printer(), checkTypeLabel(rec.checkType()),
                TIME_FMT.format(rec.at().atZone(ZoneId.systemDefault()))));
        dialog.setWidth("860px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        if (rec.snapshot() != null) {
            final Image img = new Image(new StreamResource("ai-check.jpg",
                    () -> new ByteArrayInputStream(rec.snapshot())), "AI check snapshot");
            img.setWidth("100%");
            img.getStyle().set("border-radius", "6px");
            layout.add(img);
        } else {
            layout.add(new Span("No snapshot could be grabbed for this check."));
        }
        layout.add(new HorizontalLayout(triggerChip(rec.trigger()), resultSpan(rec)));
        if (rec.context() != null && !rec.context().isBlank()) {
            layout.add(new Span("HMS/error hint given to the model: " + rec.context()));
        }
        final Span desc = new Span(rec.description());
        layout.add(desc);
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    private Image snapshotImage(final PrintAiService.CheckRecord rec, final String width) {
        final Image img = new Image(new StreamResource("ai-check-%s.jpg".formatted(rec.printer()),
                () -> new ByteArrayInputStream(rec.snapshot())), "AI check snapshot for " + rec.printer());
        img.setWidth(width);
        img.getStyle().set("border-radius", "6px").setCursor("pointer");
        img.addClickListener(e -> showSnapshotDialog(rec));
        return img;
    }

    private static String checkTypeLabel(final String checkType) {
        return switch (checkType) {
            case "bed-clear" -> "Bed Clear";
            case "first-layer" -> "First Layer";
            case "failure" -> "Failure Detection";
            default -> checkType;
        };
    }

    private static Span triggerChip(final String trigger) {
        final String label = switch (trigger == null ? "" : trigger) {
            case "manual" -> "Manual check";
            case "scheduled" -> "Scheduled check";
            case "start-next" -> "Start Next gate";
            case "auto-start" -> "Auto-start gate";
            case "test" -> "Prompt test";
            default -> trigger;
        };
        final Span chip = new Span(label);
        chip.getStyle().setColor("var(--lumo-secondary-text-color)")
                .set("border", "1px solid var(--lumo-contrast-30pct)")
                .set("border-radius", "10px")
                .set("padding", "0 8px")
                .set("font-size", "0.85em");
        return chip;
    }

    private Span resultSpan(final PrintAiService.CheckRecord rec) {
        final Span s;
        if (rec.good() == null) {
            s = new Span("— did not complete");
            s.getStyle().setColor("var(--lumo-secondary-text-color)");
            return s;
        }
        final String icon = switch (rec.severity()) {
            case OK -> "✓";
            case WARN -> "⚠";
            case FAIL -> "✗";
        };
        s = new Span("%s %s".formatted(icon, rec.good() ? "OK" : "Problem"));
        s.getStyle().setFontWeight("bold").setColor(switch (rec.severity()) {
            case OK -> "var(--lumo-success-text-color)";
            case WARN -> "#856404";
            case FAIL -> "var(--lumo-error-text-color)";
        });
        return s;
    }

    private static Span timeSpan(final PrintAiService.CheckRecord rec) {
        final Span s = new Span("%s (%s)".formatted(TIME_FMT.format(rec.at().atZone(ZoneId.systemDefault())), formatTimeAgo(rec.at())));
        s.getStyle().setColor("var(--lumo-secondary-text-color)");
        return s;
    }

    // -------------------------------------------------------------------------
    // Prompt editors
    // -------------------------------------------------------------------------

    private Div buildPromptsSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Check Prompts"));
        section.add(new Span("The exact prompts sent to the model (%s) for each check, editable at runtime (saved to bambu-ai-prompts.json, applies to the next check immediately). "
                .formatted(config.ollama().model())
                + "Keep the leading answer keyword instructions intact - result parsing looks for that first word."));

        // One shared printer picker for the per-prompt Test buttons: grabs that printer's current camera frame and
        // runs the (possibly unsaved) prompt text against it, so a prompt can be tuned without waiting for a real check.
        final ComboBox<String> testPrinter = new ComboBox<>("Test against printer");
        testPrinter.setItems(printers.getPrinters().stream().map(p -> p.getName()).sorted().toList());
        testPrinter.setClearButtonVisible(true);
        testPrinter.setTooltipText("The Test button on each prompt below grabs this printer's current camera frame "
                + "and runs the prompt in the editor against it right now (results are recorded in the check history).");
        final Button testAll = new Button("Test all three now", new Icon(VaadinIcon.FLASK));
        testAll.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        testAll.setTooltipText("Run all three SAVED prompts against the selected printer's current frame in one capture, and record them in the history below");
        testAll.addClickListener(e -> runAllPromptsTest(testPrinter.getValue()));
        final HorizontalLayout testBar = new HorizontalLayout(testPrinter, testAll);
        testBar.setDefaultVerticalComponentAlignment(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        section.add(testBar);

        for (final AiPromptService.PromptType type : AiPromptService.PromptType.values()) {
            section.add(buildPromptEditor(type, testPrinter));
        }
        section.add(buildContextEditor());
        section.add(buildBedReferencePromptEditor());
        return section;
    }

    /** Editor for the experimental two-image (reference + current) bed-clear prompt. */
    private Div buildBedReferencePromptEditor() {
        final Div wrap = new Div();
        wrap.getStyle().set("margin-top", "24px").set("border-top", "1px solid var(--lumo-contrast-20pct)").set("padding-top", "16px");

        final Span customized = new Span("customized");
        customized.getStyle().setColor("var(--lumo-primary-text-color)").set("font-size", "0.85em")
                .set("border", "1px solid var(--lumo-primary-color-50pct)").set("border-radius", "10px").set("padding", "0 8px");
        customized.setVisible(prompts.isBedReferenceCustomized());

        final Span title = new Span("Bed Clear — reference compare (experimental) ");
        title.getStyle().setFontWeight("bold");
        final Span keywordHint = new Span(" (model must answer YES-first; used only when a saved empty-bed reference exists and compare mode is on)");
        keywordHint.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        wrap.add(new Div(title, customized, keywordHint));
        wrap.add(new Div(new Span("The model receives TWO images: image 1 the saved empty reference, image 2 the current bed. Keep the wording that refers to \"IMAGE 1\" and \"IMAGE 2\".")));

        final TextArea area = new TextArea();
        area.setWidthFull();
        area.setValue(prompts.getBedReferencePrompt());
        area.getStyle().set("--vaadin-input-field-height", "auto");
        area.setMinHeight("160px");
        wrap.add(area);

        final Button save = new Button("Save", new Icon(VaadinIcon.CHECK), e -> {
            prompts.setBedReferencePrompt(area.getValue());
            customized.setVisible(prompts.isBedReferenceCustomized());
            area.setValue(prompts.getBedReferencePrompt());
            showNotification("Bed-reference prompt %s".formatted(prompts.isBedReferenceCustomized() ? "saved" : "reset to default (matched the default text)"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        final Button reset = new Button("Reset to default", new Icon(VaadinIcon.ROTATE_LEFT), e -> {
            prompts.resetBedReference();
            area.setValue(prompts.getBedReferencePrompt());
            customized.setVisible(false);
            showNotification("Bed-reference prompt reset to default");
        });
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        wrap.add(new HorizontalLayout(save, reset));
        return wrap;
    }

    /** Editor for the HMS/error context wrapper - not a standalone check, so no keyword or Test button. */
    private Div buildContextEditor() {
        final Div wrap = new Div();
        wrap.getStyle().set("margin-top", "24px").set("border-top", "1px solid var(--lumo-contrast-20pct)").set("padding-top", "16px");

        final Span customized = new Span("customized");
        customized.getStyle().setColor("var(--lumo-primary-text-color)").set("font-size", "0.85em")
                .set("border", "1px solid var(--lumo-primary-color-50pct)").set("border-radius", "10px").set("padding", "0 8px");
        customized.setVisible(prompts.isContextCustomized());

        final Span title = new Span("HMS / error context hint ");
        title.getStyle().setFontWeight("bold");
        wrap.add(new Div(title, customized));
        wrap.add(new Div(new Span("Prepended to the three checks above ONLY when the printer is actively reporting an "
                + "HMS alert or print-error code, so the model gets that as a hint. This is what makes the checks "
                + "HMS-aware - there is no separate HMS check. Keep the {context} placeholder; it is replaced with the "
                + "live code/description.")));

        final TextArea area = new TextArea();
        area.setWidthFull();
        area.setValue(prompts.getContextTemplate());
        area.getStyle().set("--vaadin-input-field-height", "auto");
        area.setMinHeight("120px");
        wrap.add(area);

        final Button save = new Button("Save", new Icon(VaadinIcon.CHECK), e -> {
            prompts.setContextTemplate(area.getValue());
            customized.setVisible(prompts.isContextCustomized());
            area.setValue(prompts.getContextTemplate());
            if (!area.getValue().contains("{context}")) {
                showError("Heads up: the {context} placeholder is missing, so the live HMS code won't be inserted.");
            } else {
                showNotification("HMS context hint %s".formatted(prompts.isContextCustomized() ? "saved" : "reset to default (matched the default text)"));
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        final Button reset = new Button("Reset to default", new Icon(VaadinIcon.ROTATE_LEFT), e -> {
            prompts.resetContext();
            area.setValue(prompts.getContextTemplate());
            customized.setVisible(false);
            showNotification("HMS context hint reset to default");
        });
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        wrap.add(new HorizontalLayout(save, reset));
        return wrap;
    }

    private Div buildPromptEditor(final AiPromptService.PromptType type, final ComboBox<String> testPrinter) {
        final Div wrap = new Div();
        wrap.getStyle().set("margin-top", "16px");

        final Span customized = new Span("customized");
        customized.getStyle().setColor("var(--lumo-primary-text-color)").set("font-size", "0.85em")
                .set("border", "1px solid var(--lumo-primary-color-50pct)").set("border-radius", "10px").set("padding", "0 8px");
        customized.setVisible(prompts.isCustomized(type));

        final Span title = new Span(type.label() + " ");
        title.getStyle().setFontWeight("bold");
        final Span keywordHint = new Span(" (model must answer %s-first)".formatted(type.positiveKeyword()));
        keywordHint.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        wrap.add(new Div(title, customized, keywordHint));

        final TextArea area = new TextArea();
        area.setWidthFull();
        area.setValue(prompts.getPrompt(type));
        area.getStyle().set("--vaadin-input-field-height", "auto");
        area.setMinHeight("160px");
        wrap.add(area);

        final Button save = new Button("Save", new Icon(VaadinIcon.CHECK), e -> {
            prompts.setPrompt(type, area.getValue());
            customized.setVisible(prompts.isCustomized(type));
            area.setValue(prompts.getPrompt(type));
            showNotification("%s prompt %s".formatted(type.label(), prompts.isCustomized(type) ? "saved" : "reset to default (matched the default text)"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        final Button reset = new Button("Reset to default", new Icon(VaadinIcon.ROTATE_LEFT), e -> {
            prompts.reset(type);
            area.setValue(prompts.getPrompt(type));
            customized.setVisible(false);
            showNotification("%s prompt reset to default".formatted(type.label()));
        });
        reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        final Button test = new Button("Test", new Icon(VaadinIcon.FLASK), e -> runPromptTest(type, testPrinter.getValue(), area.getValue()));
        test.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        test.setTooltipText("Run this prompt (as edited above, unsaved) against the selected printer's current camera frame");
        wrap.add(new HorizontalLayout(save, reset, test));
        return wrap;
    }

    /**
     * Grabs the selected printer's current frame and runs the (possibly unsaved) prompt text against it, then shows
     * the model's raw verdict + the analyzed frame. Tests the prompt in isolation - no HMS context is injected.
     */
    private void runPromptTest(final AiPromptService.PromptType type, final String printerName, final String promptText) {
        if (printerName == null || printerName.isBlank()) {
            showError("Pick a printer to test against first (the \"Test against printer\" box above).");
            return;
        }
        if (!ollama.isEnabled()) {
            showError("Ollama is not configured - set bambu.ollama.url first.");
            return;
        }
        showNotification("Testing %s prompt on %s…".formatted(type.label(), printerName));
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        executor.submit(() -> {
            final Optional<OllamaService.AiResult> result = aiService.testPrompt(printerName, type, promptText);
            final byte[] snap = aiService.getLastCheck(printerName).map(PrintAiService.CheckRecord::snapshot).orElse(null);
            ui.ifPresent(u -> u.access(() -> {
                showPromptTestResult(type, printerName, snap, result);
                refreshGrid();
            }));
        });
    }

    /** Runs all three SAVED prompts against one frame (one light cycle), records each, and shows a combined verdict. */
    private void runAllPromptsTest(final String printerName) {
        if (printerName == null || printerName.isBlank()) {
            showError("Pick a printer to test against first (the \"Test against printer\" box above).");
            return;
        }
        if (!ollama.isEnabled()) {
            showError("Ollama is not configured - set bambu.ollama.url first.");
            return;
        }
        showNotification("Testing all three prompts on %s…".formatted(printerName));
        final java.util.Map<AiPromptService.PromptType, String> texts = new java.util.LinkedHashMap<>();
        for (final AiPromptService.PromptType type : AiPromptService.PromptType.values()) {
            texts.put(type, prompts.getPrompt(type));
        }
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        executor.submit(() -> {
            final java.util.Map<AiPromptService.PromptType, Optional<OllamaService.AiResult>> results
                    = aiService.testPrompts(printerName, texts);
            ui.ifPresent(u -> u.access(() -> {
                showAllPromptsResult(printerName, results);
                refreshGrid();
            }));
        });
    }

    private void showAllPromptsResult(final String printerName,
            final java.util.Map<AiPromptService.PromptType, Optional<OllamaService.AiResult>> results) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Test all prompts — " + printerName);
        dialog.setWidth("620px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        results.forEach((type, result) -> {
            final Span line;
            if (result.isEmpty()) {
                line = new Span("%s: — did not complete (no snapshot or AI error)".formatted(type.label()));
                line.getStyle().setColor("var(--lumo-secondary-text-color)");
            } else {
                final OllamaService.AiResult r = result.get();
                line = new Span("%s: %s-first %s — %s".formatted(type.label(), type.positiveKeyword(),
                        r.positive() ? "YES" : "no", truncate(r.description(), 160)));
                line.getStyle().setColor(switch (r.severity()) {
                    case OK -> "var(--lumo-success-text-color)";
                    case WARN -> "#856404";
                    case FAIL -> "var(--lumo-error-text-color)";
                });
            }
            layout.add(line);
        });
        layout.add(secondaryNote("All three were recorded in the check history below. Uses the SAVED prompts."));
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    private void showPromptTestResult(final AiPromptService.PromptType type, final String printerName,
            final byte[] snapshot, final Optional<OllamaService.AiResult> result) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Prompt test — %s on %s".formatted(type.label(), printerName));
        dialog.setWidth("760px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        if (snapshot == null) {
            final Span none = new Span("No camera snapshot could be grabbed for this printer, so the prompt could not be tested.");
            none.getStyle().setColor("var(--lumo-error-text-color)");
            layout.add(none);
        } else {
            final Image img = new Image(new StreamResource("prompt-test.jpg",
                    () -> new ByteArrayInputStream(snapshot)), "tested frame");
            img.setWidth("100%");
            img.getStyle().set("border-radius", "6px");
            layout.add(img);
            if (result.isEmpty()) {
                final Span err = new Span("The model did not answer (Ollama error or timeout).");
                err.getStyle().setColor("var(--lumo-error-text-color)");
                layout.add(err);
            } else {
                final OllamaService.AiResult r = result.get();
                final Span verdict = new Span("Model answered %s-first: %s".formatted(type.positiveKeyword(),
                        r.positive() ? "YES (matched \"%s\")".formatted(type.positiveKeyword()) : "no"));
                verdict.getStyle().setFontWeight("bold").setColor(switch (r.severity()) {
                    case OK -> "var(--lumo-success-text-color)";
                    case WARN -> "#856404";
                    case FAIL -> "var(--lumo-error-text-color)";
                });
                layout.add(verdict);
                layout.add(new Span(r.description()));
                layout.add(secondaryNote("This tests the prompt only - no HMS/error context was injected."));
            }
        }
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    private static Span secondaryNote(final String text) {
        final Span s = new Span(text);
        s.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        return s;
    }

    private void refreshGrid() {
        final List<PrinterAiRow> rows = new ArrayList<>();
        printers.getPrinters().forEach(printer -> {
            final Optional<PrintAiService.AiCheckResult> last = aiService.getLastResult(printer.getName());
            if (last.isPresent()) {
                final PrintAiService.AiCheckResult r = last.get();
                final String typeLabel = switch (r.checkType()) {
                    case "bed-clear" -> "Bed Clear";
                    case "first-layer" -> "First Layer";
                    default -> "Print";
                };
                final String icon = switch (r.severity()) {
                    case OK -> "✓";
                    case WARN -> "⚠";
                    case FAIL -> "✗";
                };
                rows.add(new PrinterAiRow(
                        printer.getName(),
                        typeLabel,
                        icon + " " + (r.description() == null ? "" : r.description()),
                        r.severity(),
                        formatTimeAgo(r.checkedAt())
                ));
            } else {
                rows.add(new PrinterAiRow(printer.getName(), "—", "No check run yet", OllamaService.Severity.OK, ""));
            }
        });
        grid.setItems(rows);
        populateLastChecks();
        historyGrid.setItems(aiService.getHistory());
        statusSpan.setText("Last refreshed: " + java.time.LocalTime.now().withNano(0));
    }

    private void doManualCheck(final String printerName) {
        showNotification("%s: running failure check…".formatted(printerName));
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        aiService.checkFailure(printerName, "manual").thenAccept(result ->
                ui.ifPresent(u -> u.access(() -> {
                    if (result.isEmpty()) {
                        showError("%s: no snapshot available yet".formatted(printerName));
                    } else {
                        showNotification("%s: check done — %s".formatted(printerName, truncate(result.get().description(), 200)));
                    }
                    refreshGrid();
                })));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HorizontalLayout row(final String label, final com.vaadin.flow.component.Component value) {
        final HorizontalLayout hl = new HorizontalLayout();
        hl.setSpacing(true);
        hl.setAlignItems(Alignment.BASELINE);
        final Span lbl = new Span(label);
        lbl.getStyle().setFontWeight("bold").setMinWidth("200px");
        hl.add(lbl, value);
        return hl;
    }

    private static String truncate(final String s, final int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static String formatTimeAgo(final Instant t) {
        if (t == null) return "";
        final long secs = Duration.between(t, Instant.now()).getSeconds();
        if (secs < 60) return "just now";
        if (secs < 3600) return "%d min ago".formatted(secs / 60);
        return "%dh %dm ago".formatted(secs / 3600, (secs % 3600) / 60);
    }

    private record PrinterAiRow(String printerName, String checkType, String resultText,
            OllamaService.Severity severity, String timeAgo) {}

}
     