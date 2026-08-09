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
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.textfield.NumberField;
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
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
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
    com.tfyre.bambu.printer.BedDiffService bedDiff;
    @Inject
    org.eclipse.microprofile.context.ManagedExecutor executor;

    private final Grid<PrinterAiRow> grid = new Grid<>();
    private final Grid<PrintAiService.CheckRecord> historyGrid = new Grid<>();
    /** Per-printer bed-card refreshers, so changing the compared region re-renders every preview immediately. */
    private final List<Runnable> bedCardRefreshers = new ArrayList<>();
    private final Span statusSpan = new Span();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        build();
    }

    /**
     * Four sub-tabs rather than seven stacked sections. Everything here used to be on one page, which meant
     * several screens of scrolling to reach the prompts - and the day-to-day view (is it connected, what did it
     * last decide) was buried above content you only touch while tuning.
     */
    private void build() {
        addClassName("ai-settings-view");
        addClassName("ai-settings-wide");
        setPadding(true);
        setSpacing(true);

        add(new H3("AI Print Monitoring"));
        add(buildControlSection());

        final Tab statusTab = new Tab("Status");
        final Tab bedTab = new Tab("Bed reference");
        final Tab historyTab = new Tab("History");
        final Tab promptsTab = new Tab("Prompts");
        final Tabs subTabs = new Tabs(statusTab, bedTab, historyTab, promptsTab);
        final Div body = new Div();
        body.setWidthFull();
        subTabs.addSelectedChangeListener(e -> {
            body.removeAll();
            final Tab sel = e.getSelectedTab();
            if (sel == bedTab) {
                body.add(buildBedReferenceSection());
            } else if (sel == historyTab) {
                body.add(buildHistorySection());
            } else if (sel == promptsTab) {
                body.add(buildPromptsSection());
            } else {
                body.add(buildResultsSection(), buildConnectionSection());
            }
        });
        add(subTabs);
        add(body);
        body.add(buildResultsSection(), buildConnectionSection());
    }

    // -------------------------------------------------------------------------
    // Experimental: empty-bed reference compare
    // -------------------------------------------------------------------------

    private Div buildBedReferenceSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(buildProtectionTable());
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
        section.add(buildPixelDiffControls());

        final Div cards = new Div();
        cards.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "16px").set("margin-top", "12px");
        bedCardRefreshers.clear();
        printers.getPrinters().forEach(p -> cards.add(buildBedReferenceCard(p.getName())));
        section.add(cards);
        return section;
    }

    /**
     * Answers "can the pixel check currently tell an empty bed from an occupied one, per printer?" - which is the
     * question this page never answered, and the reason an occupied-bed print got through on 2026-08-01.
     * <p>
     * Every number needed to catch that was already on the page: reference age, last reading, limit. But a bare
     * "5.57" next to a limit of 6.0 reads like a pass, when in fact an empty bed on a good reference reads about
     * 0.2 and anything mid-range means the reference has stopped describing an empty bed. The verdict and the
     * plain-English meaning are therefore rendered, not left to be inferred.
     */
    private Div buildProtectionTable() {
        final Div wrap = new Div();
        wrap.addClassName("bed-protect");
        wrap.add(new H4("Bed protection"));
        final Span sub = new Span("Whether the pixel backstop can currently tell an empty bed from an occupied one.");
        sub.addClassName("bed-protect-sub");
        wrap.add(sub);

        final Div head = new Div();
        head.addClassName("bp-head");
        head.add(cell("Printer"), cell("Reference"), cell("Last reading"), cell("State"), cell("Meaning"));
        wrap.add(head);

        printers.getPrinters().forEach(p -> wrap.add(buildProtectionRow(p.getName())));
        return wrap;
    }

    private static Span cell(final String text) {
        return new Span(text);
    }

    private Div buildProtectionRow(final String printerName) {
        final Div row = new Div();
        row.addClassName("bp-row");

        final Span name = new Span(printerName);
        name.addClassName("bp-name");

        // Reference age. Age is the best single predictor of whether the check still discriminates, so it is a
        // column rather than something to go hunting for.
        final Div refCell = new Div();
        final Optional<Instant> capturedAt = bedReference.referenceCapturedAt(printerName);
        if (capturedAt.isEmpty()) {
            final Span none = new Span("none saved");
            none.addClassName("bp-mut");
            refCell.add(none);
        } else {
            final Duration age = Duration.between(capturedAt.get(), Instant.now());
            final Span when = new Span(referenceAge(age));
            when.addClassName(age.toHours() >= 12 ? "bp-warn" : "bp-ok");
            refCell.add(when);
        }

        final Div readingCell = new Div();
        final Div stateCell = new Div();
        final Span meaning = new Span();
        meaning.addClassName("bp-mut");

        final Optional<com.tfyre.bambu.printer.BedDiffService.Measurement> m = bedDiff.getLastMeasurement(printerName);
        if (m.isEmpty()) {
            final Span dash = new Span(capturedAt.isEmpty() ? "no reference" : "not measured yet");
            dash.addClassName("bp-mut");
            readingCell.add(dash);
            final Span chip = new Span("unknown");
            chip.addClassName("bp-chip");
            stateCell.add(chip);
            meaning.setText(capturedAt.isEmpty()
                    ? "Save a frame of the empty bed to switch this printer's backstop on."
                    : "No reading since the last restart - press Measure now on the card below.");
        } else {
            final com.tfyre.bambu.printer.BedDiffService.Measurement meas = m.get();
            final com.tfyre.bambu.printer.BedDiffService.Trust trust = bedDiff.trustOf(meas);
            final Span num = new Span("%.2f".formatted(meas.mean()));
            num.addClassName("bp-num");
            num.addClassName(switch (trust) {
                case PROTECTING ->
                    "bp-ok";
                case CANNOT_TELL ->
                    "bp-warn";
                case OVER_LIMIT ->
                    "bp-err";
            });
            final Span of = new Span(" / %.1f".formatted(bedDiff.getThreshold()));
            of.addClassName("bp-mut");
            readingCell.add(num, of);

            final Span chip = new Span(switch (trust) {
                case PROTECTING ->
                    "protecting";
                case CANNOT_TELL ->
                    "can't tell";
                case OVER_LIMIT ->
                    "over limit";
            });
            chip.addClassName("bp-chip");
            chip.addClassName(switch (trust) {
                case PROTECTING ->
                    "bp-chip-ok";
                case CANNOT_TELL ->
                    "bp-chip-warn";
                case OVER_LIMIT ->
                    "bp-chip-err";
            });
            stateCell.add(chip);
            meaning.setText(bedDiff.meaningOf(meas));
        }

        row.add(name, refCell, readingCell, stateCell, meaning);
        return row;
    }

    /** "4h ago" / "2 days ago" - an exact timestamp is less useful here than how stale it is. */
    private static String referenceAge(final Duration age) {
        final long days = age.toDays();
        if (days >= 1) {
            return days == 1 ? "1 day ago" : "%d days ago".formatted(days);
        }
        final long hours = age.toHours();
        if (hours >= 1) {
            return "%dh ago".formatted(hours);
        }
        final long mins = Math.max(0, age.toMinutes());
        return mins <= 1 ? "just now" : "%d min ago".formatted(mins);
    }

    /**
     * The deterministic pixel-diff backstop. Deliberately sits with the reference controls: it compares against
     * the same saved empty-bed image, but WITHOUT involving the model - which is the entire point, since local
     * vision models cannot reliably see a dark part on the dark plate.
     */
    /**
     * One safety layer: its switch, what it protects against, and its current value.
     *
     * @param toggle the on/off control, or null for a layer that is switched off by setting its value to zero
     * @param flag   an amber note when a layer is deliberately off - an unchecked box cannot distinguish
     *               "disabled on purpose, and here is why" from "nobody ever turned this on"
     */
    private Div layerRow(final Checkbox toggle, final String title, final String description,
            final com.vaadin.flow.component.Component value, final String flag) {
        final Div row = new Div();
        row.addClassName("ai-layer");

        final Div sw = new Div();
        sw.addClassName("ai-layer-sw");
        if (toggle != null) {
            // The checkbox carries its own label, which IS the layer title - nothing to keep in step.
            sw.add(toggle);
        }
        row.add(sw);

        final Div text = new Div();
        text.addClassName("ai-layer-txt");
        if (title != null) {
            final Span titleSpan = new Span(title);
            titleSpan.addClassName("ai-layer-ttl");
            text.add(titleSpan);
        }
        if (flag != null) {
            final Span flagSpan = new Span(flag);
            flagSpan.addClassName("ai-layer-flag");
            text.add(flagSpan);
        }
        final Span desc = new Span(description);
        desc.addClassName("ai-layer-desc");
        text.add(desc);
        row.add(text);

        final Div val = new Div();
        val.addClassName("ai-layer-val");
        val.add(value);
        row.add(val);
        return row;
    }

    private Div buildPixelDiffControls() {
        final Div box = new Div();
        box.getStyle().set("margin-top", "12px").set("padding-top", "10px")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)");

        box.add(new H4("Safety layers"));
        final Span blurb = new Span("What stands between a pooled job and a print starting.");
        blurb.addClassName("bed-protect-sub");
        box.add(blurb);

        final Checkbox pixelToggle = new Checkbox("Enable the pixel-diff backstop");
        pixelToggle.setValue(bedDiff.isEnabled());
        pixelToggle.addValueChangeListener(e -> {
            bedDiff.setEnabled(Boolean.TRUE.equals(e.getValue()));
            showNotification("Pixel-diff backstop " + (bedDiff.isEnabled() ? "enabled" : "disabled"));
        });

        final NumberField limit = new NumberField("Mean limit");
        limit.setValue(bedDiff.getThreshold());
        limit.setStep(0.5);
        limit.setMin(0.5);
        limit.setWidth("130px");
        limit.setHelperText("avg over region");
        limit.setTooltipText("Measure a known-empty bed, then one with a part, and set this between them.");
        limit.addValueChangeListener(e -> {
            if (e.getValue() != null && e.getValue() > 0) {
                bedDiff.setThreshold(e.getValue());
                showNotification("Mean limit set to %.1f".formatted(e.getValue()));
            }
        });

        // Not a gate. On this fleet the worst block read 19.91 with a cupholder on the plate and 21.24 / 23.15
        // on two EMPTY ones - it ranked the occupied bed as the cleanest of the three. It is kept because the
        // heatmap below is shaded against it and it is useful to eyeball, but nothing blocks on it.
        final NumberField blockLimit = new NumberField("Worst-block scale");
        blockLimit.setValue(bedDiff.getBlockThreshold());
        blockLimit.setStep(0.5);
        blockLimit.setMin(0.5);
        blockLimit.setWidth("150px");
        blockLimit.setHelperText("shading only, does not block");
        blockLimit.setTooltipText("Shading only for the heatmap. Gates nothing.");
        blockLimit.addValueChangeListener(e -> {
            if (e.getValue() != null && e.getValue() > 0) {
                bedDiff.setBlockThreshold(e.getValue());
                showNotification("Heatmap scale set to %.1f".formatted(e.getValue()));
            }
        });

        final Checkbox autoRefresh = new Checkbox("Self-refresh the reference after a passing check");
        autoRefresh.setValue(bedDiff.isAutoRefresh());
        autoRefresh.setTooltipText("Keeps a good reference current. Cannot rescue a stale one.");
        autoRefresh.addValueChangeListener(e -> {
            bedDiff.setAutoRefresh(Boolean.TRUE.equals(e.getValue()));
            showNotification("Reference self-refresh " + (bedDiff.isAutoRefresh() ? "on" : "off"));
        });

        final Checkbox strict = new Checkbox("Block when the reading can't be trusted");
        strict.setValue(bedDiff.isStrict());
        strict.setTooltipText("Refuses to start when the reading is mid-range.");
        strict.addValueChangeListener(e -> {
            bedDiff.setStrict(Boolean.TRUE.equals(e.getValue()));
            showNotification("Untrusted-reading blocking " + (bedDiff.isStrict() ? "ON" : "off"));
        });

        final Checkbox twoPass = new Checkbox("Confirm a clear bed with a second look");
        twoPass.setValue(bedDiff.isTwoPass());
        twoPass.setTooltipText("Costs one extra check, and only when the bed looks clear.");
        twoPass.addValueChangeListener(e -> {
            bedDiff.setTwoPass(Boolean.TRUE.equals(e.getValue()));
            showNotification("Second-look confirmation " + (bedDiff.isTwoPass() ? "on" : "off"));
        });

        final com.vaadin.flow.component.textfield.IntegerField cooldown
                = new com.vaadin.flow.component.textfield.IntegerField();
        cooldown.setValue((int) bedDiff.getPostPrintCooldown().toMinutes());
        cooldown.setMin(0);
        cooldown.setStepButtonsVisible(true);
        cooldown.setWidth("170px");
        cooldown.setHelperText("0 = off");
        cooldown.setTooltipText("Minutes to wait after a print before this printer can take a job.");
        cooldown.addValueChangeListener(e -> {
            if (e.getValue() == null) {
                return;
            }
            bedDiff.setPostPrintCooldownMinutes(e.getValue());
            showNotification(e.getValue() == 0
                    ? "Post-print hold off - a printer can take a job as soon as it reports ready"
                    : "Holding printers %d min after a print".formatted(e.getValue()));
        });

        // One row per layer, each stating what it protects against. The previous single wrapping row of seven
        // controls gave no clue which of them mattered, or that one was switched off deliberately.
        box.add(layerRow(pixelToggle, null,
                "Compares the live frame with the saved reference, no model involved. Catches a dark part on a "
                + "dark plate.",
                limit, null));
        box.add(layerRow(strict, null,
                "A mid-range reading means the reference has aged out, so it is treated as \"cannot tell\" "
                + "rather than a pass.",
                new Span("above %.1f".formatted(bedDiff.trustCeiling())),
                bedDiff.isStrict() ? null
                        : "off on purpose - would block every printer until references are re-captured"));
        box.add(layerRow(twoPass, null,
                "A clear verdict must survive a second, freshly captured snapshot.",
                new Span("2 passes"), null));
        box.add(layerRow(null, "Hold after a print finishes",
                "The bed is certainly occupied right after a print, so nothing is dispatched during this window.",
                cooldown, null));
        box.add(layerRow(autoRefresh, null,
                "Adopts a passing frame as the new baseline, but only well under the limit. Capture the first "
                + "one by hand.",
                new Span("≤ %.1f adopts".formatted(bedDiff.trustCeiling())), null));

        final Div cropBox = new Div();
        cropBox.addClassName("bed-crop");
        cropBox.add(new H4("Compared region"));
        final Span cropBlurb = new Span("The fraction of the camera frame that is build plate. This matters more "
                + "than the limit - if the region includes chamber walls or a blown-out highlight, the plate gets "
                + "averaged away and an occupied bed can measure NO higher than an empty one. Check the preview on "
                + "each card: it should be mostly plate.");
        cropBlurb.addClassName("bed-protect-sub");
        cropBox.add(cropBlurb);
        // Which printers the numbers below apply to. Defaults to the MODEL of the first printer rather than
        // the global crop: where the camera sits is a property of the machine, so a per-model crop is the one
        // that should normally be edited. One global crop tuned on a P1 was being applied to the H2D, whose
        // camera looks across the toolhead - it was never going to fit both.
        final ComboBox<String> cropScope = new ComboBox<>("Applies to");
        final List<String> scopes = new ArrayList<>();
        scopes.add("");
        printers.getPrintersDetail().stream()
                .map(d -> d.printer().getModel())
                .filter(m -> m != com.tfyre.bambu.printer.BambuConst.PrinterModel.UNKNOWN)
                .map(m -> "model." + m.getModel())
                .distinct()
                .forEach(scopes::add);
        printers.getPrintersDetail().stream().map(d -> "printer." + d.name()).forEach(scopes::add);
        cropScope.setItems(scopes);
        cropScope.setItemLabelGenerator(v -> v.isEmpty() ? "All printers (default)"
                : v.startsWith("model.") ? "Every " + v.substring(6).toUpperCase()
                : "Only " + v.substring(8));
        cropScope.setAllowCustomValue(false);
        cropScope.setWidth("210px");
        cropScope.setTooltipText("Set the crop for a whole printer model, or override one machine whose camera "
                + "has been knocked. Blank is the fallback used when neither is set.");

        // Says whether the numbers below are this scope's own or inherited, and offers a way back. Editing an
        // inherited value silently creates an override; a settings page that can't tell you which you're looking
        // at is one you have to guess at.
        final Span inherited = new Span();
        inherited.addClassName("bed-protect-sub");
        final Button resetScope = new Button("Reset to inherited", new Icon(VaadinIcon.ARROW_BACKWARD));
        resetScope.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        final List<CropSlider> sliders = new ArrayList<>();
        final CropSlider left = cropSlider(cropScope, "left", "Left edge");
        final CropSlider top = cropSlider(cropScope, "top", "Top edge");
        final CropSlider right = cropSlider(cropScope, "right", "Right edge");
        final CropSlider bottom = cropSlider(cropScope, "bottom", "Bottom edge");
        sliders.add(left);
        sliders.add(top);
        sliders.add(right);
        sliders.add(bottom);

        final Runnable reloadScope = () -> {
            final String scope = cropScope.getValue() == null ? "" : cropScope.getValue();
            final String printer = scopeToPrinter(scope);
            sliders.forEach(sl -> sl.set(bedDiff.getCrop(printer, sl.edge())));
            final boolean own = bedDiff.hasCropOverride(scope);
            inherited.setText(scope.isEmpty() ? "This is the fallback every printer uses when nothing more "
                    + "specific is set."
                    : own ? "Set for this scope."
                    : "Inherited - editing any slider will create an override just for this scope.");
            inherited.getStyle().setColor(own ? "var(--lumo-secondary-text-color)"
                    : "var(--lumo-warning-text-color, #e8a33d)");
            resetScope.setVisible(!scope.isEmpty() && own);
        };
        cropScope.addValueChangeListener(e -> reloadScope.run());
        resetScope.addClickListener(e -> {
            bedDiff.clearCrop(cropScope.getValue() == null ? "" : cropScope.getValue());
            reloadScope.run();
            bedCardRefreshers.forEach(Runnable::run);
        });
        sliders.forEach(sl -> sl.onChange(reloadScope));
        cropScope.setValue(scopes.size() > 1 ? scopes.get(1) : "");
        reloadScope.run();

        final HorizontalLayout crop = new HorizontalLayout(
                left.layout(), top.layout(), right.layout(), bottom.layout(), blockLimit);
        final HorizontalLayout scopeRow = new HorizontalLayout(cropScope, inherited, resetScope);
        scopeRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);
        scopeRow.getStyle().set("flex-wrap", "wrap");
        cropBox.add(scopeRow);
        crop.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.BASELINE);
        crop.getStyle().set("flex-wrap", "wrap");
        cropBox.add(crop);
        box.add(cropBox);

        // Both layers use the same reference image, but only ONE of them should be judging with it.
        if (bedReference.isEnabled() && bedDiff.isEnabled()) {
            final Span clash = new Span("⚠ Both reference modes are on. The AI compare (above) and the pixel-diff "
                    + "backstop use the same reference image, but the model is unreliable at comparing two frames - "
                    + "it reports normal glue marks, lighting and plate shifts as \"an object that wasn't in the "
                    + "reference\" and blocks clear beds. Recommended: turn the AI compare OFF and leave the "
                    + "pixel-diff backstop ON - the single-image bed prompt plus a deterministic diff is the "
                    + "combination that holds up.");
            clash.getStyle().setColor("var(--lumo-warning-text-color, #e8a33d)").set("display", "block")
                    .set("margin-top", "8px");
            box.add(clash);
        }
        return box;
    }

    /**
     * A printer whose crop resolves to {@code scope}, so the fields can show what that scope currently yields.
     * <p>
     * A model scope has no single printer, so any printer of that model answers - they all resolve identically
     * by construction. Empty for the global scope, which {@code getCrop(null, edge)} handles.
     */
    private String scopeToPrinter(final String scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        if (scope.startsWith("printer.")) {
            return scope.substring(8);
        }
        final String model = scope.substring(6);
        return printers.getPrintersDetail().stream()
                .filter(d -> model.equals(d.printer().getModel().getModel()))
                .map(BambuPrinters.PrinterDetail::name)
                .findFirst()
                .orElse(null);
    }

    /** One crop edge: a slider you can drag, with the number beside it so it stays readable. */
    private record CropSlider(String edge, Div layout, com.vaadin.flow.component.html.RangeInput slider, Span readout) {

        void set(final double v) {
            slider.setValue(v);
            readout.setText("%.2f".formatted(v));
        }

        void onChange(final Runnable r) {
            slider.addValueChangeListener(e -> r.run());
        }
    }

    /**
     * One edge of the compared region, written at the selected scope.
     * <p>
     * A slider rather than a number box: this is a spatial setting judged by looking at the preview beside it,
     * and dragging while watching is the whole workflow. The number is still shown, because "0.68" is what you
     * write down and what the docs quote.
     */
    private CropSlider cropSlider(final ComboBox<String> scope, final String edge, final String label) {
        final com.vaadin.flow.component.html.RangeInput slider = new com.vaadin.flow.component.html.RangeInput();
        slider.setMin(0);
        slider.setMax(1);
        slider.setStep(0.01);
        slider.setValue(bedDiff.getCrop(edge));
        slider.setWidth("150px");
        // On release, not on every pixel: each change re-renders five cards and re-encodes their preview
        // images, and nobody reads a preview mid-drag anyway.
        slider.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.ON_CHANGE);

        final Span readout = new Span("%.2f".formatted(bedDiff.getCrop(edge)));
        readout.getStyle().set("font-variant-numeric", "tabular-nums").set("min-width", "36px");

        final Span caption = new Span(label);
        caption.addClassName("bed-protect-sub");
        final Div row = new Div(slider, readout);
        row.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px");
        final Div box = new Div(caption, row);

        slider.addValueChangeListener(e -> {
            if (e.getValue() == null) {
                return;
            }
            readout.setText("%.2f".formatted(e.getValue()));
            bedDiff.setCrop(scope.getValue() == null ? "" : scope.getValue(), edge, e.getValue());
            // Re-render every card so the previews show the new region straight away - without this the
            // control silently appears to do nothing until the tab is reopened.
            bedCardRefreshers.forEach(Runnable::run);
        });
        return new CropSlider(edge, box, slider, readout);
    }

    private static Div newFlexRow() {
        final Div d = new Div();
        d.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "8px");
        return d;
    }

    /** A captioned thumbnail sized to sit two-up inside a bed card. */
    private static Div labelledThumb(final String caption, final String name, final byte[] bytes, final String outline) {
        final Div box = new Div();
        box.getStyle().set("flex", "1 1 132px").set("min-width", "0");
        final Span cap = new Span(caption);
        cap.getStyle().setColor("var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-xs)").set("display", "block");
        final Image img = new Image(new StreamResource(name, () -> new ByteArrayInputStream(bytes)), caption);
        img.setWidthFull();
        img.getStyle().set("border-radius", "4px").set("display", "block");
        if (outline != null) {
            img.getStyle().set("outline", "2px solid " + outline).set("outline-offset", "-2px");
        }
        box.add(cap, img);
        return box;
    }

    private Div buildBedReferenceCard(final String printerName) {
        final Div card = new Div();
        card.addClassName("ai-settings-section");
        // Fixed width + wrapping button row: three buttons overflowed the old 300px card and the last one
        // ended up drawn on top of the image.
        card.getStyle().set("width", "330px").set("flex", "0 0 330px").set("box-sizing", "border-box");

        // Header: name on the left, the trust verdict as a chip on the right.
        final Div head = new Div();
        head.addClassName("bed-card-head");
        final Span title = new Span(printerName);
        title.getStyle().setFontWeight("bold");
        final Span chip = new Span();
        chip.addClassName("bp-chip");
        head.add(title, chip);
        card.add(head);

        // The reading leads, at a size you can read across the room. The old card buried it in a run-on sentence
        // under two thumbnails, which is how a mid-range number went unnoticed for a fortnight.
        final Span big = new Span();
        big.addClassName("bed-card-big");
        card.add(big);
        final Span measured = new Span();
        measured.addClassName("bed-card-sub");
        card.add(measured);

        // Diagnostics: only looked at when a number surprises you, so they fold away. They were also what made
        // the old 300px card overflow its button row onto the image.
        final Div frames = newFlexRow();
        final Div cropHolder = new Div();
        cropHolder.getStyle().set("margin-top", "6px");
        final Div diagBody = new Div(frames, cropHolder);
        final com.vaadin.flow.component.details.Details diag
                = new com.vaadin.flow.component.details.Details("Frames & diagnosis", diagBody);
        diag.addClassName("bed-card-diag");
        card.add(diag);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            frames.removeAll();
            bedReference.getReference(printerName).ifPresentOrElse(
                    bytes -> frames.add(labelledThumb("Reference", "bed-ref-%s.jpg".formatted(printerName), bytes, null)),
                    () -> {
                        final Span none = new Span("No reference saved yet");
                        none.getStyle().setColor("var(--lumo-secondary-text-color)");
                        frames.add(none);
                    });
            bedDiff.getLastFrame(printerName).ifPresent(bytes ->
                    frames.add(labelledThumb("Last measured", "bed-cur-%s.jpg".formatted(printerName), bytes, null)));

            bedDiff.getLastMeasurement(printerName).ifPresentOrElse(m -> {
                final com.tfyre.bambu.printer.BedDiffService.Trust trust = bedDiff.trustOf(m);
                final String tone = switch (trust) {
                    case PROTECTING ->
                        "bp-ok";
                    case CANNOT_TELL ->
                        "bp-warn";
                    case OVER_LIMIT ->
                        "bp-err";
                };
                big.setText("%.2f / %.1f".formatted(m.mean(), bedDiff.getThreshold()));
                big.setClassName("bed-card-big");
                big.addClassName(tone);
                chip.setText(switch (trust) {
                    case PROTECTING ->
                        "protecting";
                    case CANNOT_TELL ->
                        "can't tell";
                    case OVER_LIMIT ->
                        "over limit";
                });
                chip.setClassName("bp-chip");
                chip.addClassName(switch (trust) {
                    case PROTECTING ->
                        "bp-chip-ok";
                    case CANNOT_TELL ->
                        "bp-chip-warn";
                    case OVER_LIMIT ->
                        "bp-chip-err";
                });
                measured.setText("mean · worst block %.2f (display only)%n%s%n%s"
                        .formatted(m.worst(), bedDiff.meaningOf(m), m.detail()));
                measured.getStyle().set("white-space", "pre-line");
            }, () -> {
                big.setText("—");
                big.setClassName("bed-card-big");
                big.addClassName("bp-mut");
                chip.setText(bedReference.hasReference(printerName) ? "not measured" : "no reference");
                chip.setClassName("bp-chip");
                measured.setText(bedReference.hasReference(printerName)
                        ? "No reading since the last restart - press Measure now."
                        : "Save a frame of the empty bed to switch this printer's backstop on.");
                measured.getStyle().set("white-space", "normal");
            });

            // Show exactly what the comparison sees, so a bad crop is obvious rather than invisible.
            // Prefer the last measured frame - that's the one whose number you're trying to explain.
            cropHolder.removeAll();
            final Optional<byte[]> frame = bedDiff.getLastFrame(printerName)
                    .or(() -> bedReference.getReference(printerName));
            frame.flatMap(f -> bedDiff.renderCrop(printerName, f))
                    .ifPresent(bytes -> cropHolder.add(labelledThumb("Pixel diff + bed check see",
                            "bed-crop-%s.jpg".formatted(printerName), bytes, "var(--lumo-primary-color)")));
            // The failure check gets a DIFFERENT region - the same crop plus headroom above the plate, because
            // spaghetti grows upward out of the print. Shown separately because tuning the crop until the diff
            // looks right tells you nothing about what the failure check is looking at, and an invisible
            // mismatch between the two is what let a camera full of hoses produce eight false positives.
            frame.map(f -> bedDiff.cropForAi(printerName, f, PrintAiService.FAILURE_HEADROOM))
                    .ifPresent(bytes -> cropHolder.add(labelledThumb("Failure check sees (+ headroom)",
                            "bed-fail-%s.jpg".formatted(printerName), bytes, "var(--lumo-warning-color)")));
            // What the number is actually reacting to. Red = the blocks driving the reading; if those land on
            // bare plate rather than on an object, the metric is measuring something that isn't a part.
            bedDiff.getLastFrame(printerName).ifPresent(cur -> bedReference.getReference(printerName)
                    .flatMap(ref -> bedDiff.renderDiff(printerName, cur, ref))
                    .ifPresent(bytes -> cropHolder.add(labelledThumb("What differs",
                            "bed-diff-%s.jpg".formatted(printerName), bytes, "var(--lumo-error-color)"))));
        };
        refresh[0].run();
        bedCardRefreshers.add(refresh[0]);

        // Measure without involving the AI, so an empty bed and a bed with a part can be read back to back.
        // Calibrating off real checks alone means waiting for real checks.
        final Button measureBtn = new Button("Measure now", new Icon(VaadinIcon.CROSSHAIRS));
        measureBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        measureBtn.setTooltipText("Compare against the reference now, without asking the model.");
        measureBtn.setEnabled(bedReference.hasReference(printerName));
        measureBtn.addClickListener(e -> {
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            showNotification("%s: measuring…".formatted(printerName));
            executor.submit(() -> {
                final Optional<com.tfyre.bambu.printer.BedDiffService.Measurement> m =
                        aiService.measureBedNow(printerName);
                ui.ifPresent(u -> u.access(() -> {
                    m.ifPresentOrElse(v -> showNotification("%s: mean %.2f, worst block %.2f"
                            .formatted(printerName, v.mean(), v.worst())),
                            () -> showError("%s: could not measure (no snapshot or no reference)".formatted(printerName)));
                    refresh[0].run();
                }));
            });
        });

        // Two live frames against each other - the pipeline's own noise floor, with the bed untouched
        final Button noiseBtn = new Button("Noise floor", new Icon(VaadinIcon.CHART));
        noiseBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        noiseBtn.setTooltipText("Two live frames against each other. Whatever it reads is noise, not an object.");
        noiseBtn.addClickListener(e -> {
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            showNotification("%s: sampling…".formatted(printerName));
            executor.submit(() -> {
                final Optional<com.tfyre.bambu.printer.BedDiffService.Measurement> m =
                        aiService.measureNoiseFloor(printerName);
                ui.ifPresent(u -> u.access(() -> m.ifPresentOrElse(
                        v -> showNotification("%s noise floor: mean %.2f, worst block %.2f".formatted(
                                printerName, v.mean(), v.worst())),
                        () -> showError("%s: could not sample two frames".formatted(printerName)))));
            });
        });

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
        // A plain HorizontalLayout doesn't wrap, so the third button overflowed the card and drew over the image
        final Div buttons = newFlexRow();
        buttons.getStyle().set("margin-top", "8px").set("align-items", "center");
        buttons.add(saveBtn, measureBtn, noiseBtn, clearBtn);
        card.add(buttons);
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

        // One line, because none of this changes without a restart - it's reference material, not a dashboard.
        // Six labelled rows of static config were taking as much vertical space as the results they sat above.
        final Div head = new Div();
        head.addClassName("ai-conn-line");
        final Span summary = new Span(configured ? "%s · %s".formatted(model, urlText) : "no Ollama URL configured");
        summary.addClassName("bp-mut");
        head.add(connectionBadge, summary);

        final Div detail = new Div(
                row("Failure check interval:", new Span(failureInterval)),
                row("First-layer delay:", new Span(firstLayerDelay)),
                row("Request timeout:", new Span(timeout)),
                new Span("To change these, edit bambu.ollama.* in your configuration file and restart."));
        final com.vaadin.flow.component.details.Details more
                = new com.vaadin.flow.component.details.Details("Ollama connection details", detail);
        more.addClassName("bed-card-diag");

        section.add(head, more);
        return section;
    }

    // -------------------------------------------------------------------------
    // Runtime control section
    // -------------------------------------------------------------------------

    private Div buildControlSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Runtime controls"));

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

    /**
     * Every printer's last check, once. This was two sections - a grid of results and, below it, a stack of
     * snapshot cards - which listed the same five printers twice in two different shapes, the same duplication
     * the Automation overview had before its printer table. The snapshot is now a column, so a row carries the
     * frame, the verdict and the reason together instead of making you match them up by name across two lists.
     */
    private Div buildResultsSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Last check per printer"));
        final Span sub = new Span("The exact frame the model looked at, why the check ran, and what it concluded. "
                + "Click a thumbnail to enlarge.");
        sub.addClassName("bed-protect-sub");
        section.add(sub);

        if (grid.getColumns().isEmpty()) {
            // Guard: this view can be re-attached (it lives inside the Automation page's tabs) and the grids
            // are fields - reconfiguring columns on every attach would duplicate them.
            grid.addColumn(PrinterAiRow::printerName).setHeader("Printer").setAutoWidth(true);
            // The analyzed frame, pulled from the same CheckRecord the old snapshot cards used.
            grid.addComponentColumn(row -> aiService.getLastCheck(row.printerName())
                    .filter(rec -> rec.snapshot() != null)
                    .map(rec -> (com.vaadin.flow.component.Component) snapshotImage(rec, "96px"))
                    .orElseGet(() -> {
                        final Span none = new Span("no frame");
                        none.addClassName("bp-mut");
                        return none;
                    })).setHeader("Snapshot").setAutoWidth(true);
            grid.addColumn(PrinterAiRow::checkType).setHeader("Check").setAutoWidth(true);
            grid.addComponentColumn(row -> aiService.getLastCheck(row.printerName())
                    .map(rec -> (com.vaadin.flow.component.Component) triggerChip(rec.trigger()))
                    .orElseGet(Span::new)).setHeader("Trigger").setAutoWidth(true);
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
            GridLayoutMemory.remember(grid, "ai-status");
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

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    private Div buildHistorySection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Check history"));
        section.add(new Span("The last %d check attempts across the farm, newest first (in-memory - resets on restart). Click a row to see the analyzed snapshot.".formatted(50)));

        if (historyGrid.getColumns().isEmpty()) {
            // Same re-attach guard as the results grid above
            historyGrid.addColumn(rec -> TIME_FMT.format(rec.at().atZone(ZoneId.systemDefault()))).setHeader("When").setAutoWidth(true);
            historyGrid.addColumn(PrintAiService.CheckRecord::printer).setHeader("Printer").setAutoWidth(true);
            historyGrid.addColumn(rec -> checkTypeLabel(rec.checkType())).setHeader("Check").setAutoWidth(true);
            historyGrid.addComponentColumn(rec -> triggerChip(rec.trigger())).setHeader("Trigger").setAutoWidth(true);
            historyGrid.addComponentColumn(this::resultSpan).setHeader("Result").setAutoWidth(true);
            // The calibration data: every bed check records its measured diff, so the threshold can be set from
            // real numbers (empty beds vs beds with a part) instead of guessed.
            historyGrid.addColumn(rec -> rec.pixelDiff() == null ? "—" : "%.2f".formatted(rec.pixelDiff()))
                    .setHeader("Pixel diff").setAutoWidth(true);
            historyGrid.addColumn(PrintAiService.CheckRecord::description).setHeader("Description").setFlexGrow(1);
            historyGrid.setWidth("100%");
            historyGrid.setAllRowsVisible(true);
            historyGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
            historyGrid.setColumnReorderingAllowed(true);
            GridLayoutMemory.remember(historyGrid, "ai-history");
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


    // -------------------------------------------------------------------------
    // Prompt editors
    // -------------------------------------------------------------------------

    private Div buildPromptsSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Check prompts"));
        section.add(new Span("The exact prompts sent to the model (%s) for each check, editable at runtime (saved to bambu-ai-prompts.json, applies to the next check immediately). "
                .formatted(config.ollama().model())
                + "Keep the leading answer keyword instructions intact - result parsing looks for that first word."));

        // One shared printer picker for the per-prompt Test buttons: grabs that printer's current camera frame and
        // runs the (possibly unsaved) prompt text against it, so a prompt can be tuned without waiting for a real check.
        final ComboBox<String> testPrinter = new ComboBox<>("Test against printer");
        testPrinter.setItems(printers.getPrinters().stream().map(p -> p.getName()).sorted().toList());
        testPrinter.setClearButtonVisible(true);
        testPrinter.setTooltipText("Printer whose current camera frame the prompt tests below will use.");
        final Button testAll = new Button("Test all three now", new Icon(VaadinIcon.FLASK));
        testAll.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        testAll.setTooltipText("Runs all three saved prompts against one capture.");
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
     