package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
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

    @Inject
    BambuConfig config;
    @Inject
    PrintAiService aiService;
    @Inject
    OllamaService ollama;
    @Inject
    BambuPrinters printers;
    @Inject
    org.eclipse.microprofile.context.ManagedExecutor executor;

    private final Grid<PrinterAiRow> grid = new Grid<>();
    private final Span statusSpan = new Span();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        build();
    }

    private void build() {
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);

        add(new H3("AI Print Monitoring — Settings"));
        add(buildConnectionSection());
        add(buildControlSection());
        add(buildResultsSection());
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

        section.add(grid);

        final Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH), e -> refreshGrid());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        section.add(refresh);
        section.add(statusSpan);

        refreshGrid();
        return section;
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
        statusSpan.setText("Last refreshed: " + java.time.LocalTime.now().withNano(0));
    }

    private void doManualCheck(final String printerName) {
        showNotification("%s: running failure check…".formatted(printerName));
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        aiService.checkFailure(printerName).thenAccept(result ->
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
