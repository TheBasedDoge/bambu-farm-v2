package com.tfyre.bambu.view;

import com.tfyre.bambu.printer.GcodeSource;
import com.tfyre.bambu.printer.MappingPart;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared print-mapping editor used by both the Etsy and eBay Sales Orders views, so the two marketplaces behave
 * identically. Lets a listing map to one or more {@link MappingPart}s - each either a file from the batch print
 * library or a path already on every printer's SD card - with a per-part "copies per unit ordered" count (for
 * items that need more than one physical print per order, or a multi-plate kit of different gcodes).
 */
public class MappingPartsPanel extends Div {

    private final List<PartRow> rows = new ArrayList<>();
    private final Div rowsContainer = new Div();
    private final CheckboxGroup<String> printerSelect = new CheckboxGroup<>();
    private final Span totalJobsLabel = new Span();

    private final Supplier<List<String>> libraryFilesSupplier;
    private final Function<String, List<Integer>> plateIdsSupplier;
    private final Supplier<List<String>> projectsSupplier;
    private final Function<String, List<String>> projectFilesSupplier;
    private final int orderedQuantity;

    public MappingPartsPanel(
            final Supplier<List<String>> libraryFilesSupplier,
            final Function<String, List<Integer>> plateIdsSupplier,
            final Supplier<List<String>> projectsSupplier,
            final Function<String, List<String>> projectFilesSupplier,
            final List<String> printerNames,
            final List<MappingPart> initialParts,
            final int orderedQuantity,
            final Consumer<List<MappingPart>> onSave,
            final BiConsumer<List<MappingPart>, List<String>> onQueue) {
        this.libraryFilesSupplier = libraryFilesSupplier;
        this.plateIdsSupplier = plateIdsSupplier;
        this.projectsSupplier = projectsSupplier;
        this.projectFilesSupplier = projectFilesSupplier;
        this.orderedQuantity = orderedQuantity;
        addClassName("mapping-parts-panel");

        rowsContainer.addClassName("mapping-parts-rows");
        add(rowsContainer);

        if (initialParts.isEmpty()) {
            addRow(null);
        } else {
            initialParts.forEach(this::addRow);
        }

        // "Map to a project": adds one part row per file in the project. Deliberately an expansion rather than a
        // stored project reference - the saved mapping is still a plain list of MappingPart, so existing mappings,
        // auto-queue, dispatch, the dry run and stock all behave exactly as before. Trade-off: adding a file to the
        // project later does NOT update mappings already saved; re-pick the project to refresh them.
        final ComboBox<String> projectSelect = new ComboBox<>();
        projectSelect.setPlaceholder("Add all files from project…");
        projectSelect.setWidth("260px");
        projectSelect.setItems(projectsSupplier.get());
        projectSelect.setTooltipText("Adds one part per file in that project. Each part keeps its own plate, "
                + "copies, AMS slot and filament - edit them individually afterwards.");
        projectSelect.addValueChangeListener(e -> {
            final String project = e.getValue();
            if (project == null || !e.isFromClient()) {
                return;
            }
            final List<String> files = projectFilesSupplier.apply(project);
            if (files.isEmpty()) {
                return;
            }
            // Drop a single empty starter row so picking a project on a fresh mapping doesn't leave a blank part
            if (rows.size() == 1 && rows.get(0).toMappingPart() == null) {
                removeRow(rows.get(0));
            }
            final java.util.Set<String> already = rows.stream()
                    .map(PartRow::toMappingPart)
                    .filter(Objects::nonNull)
                    .filter(part -> part.source() == GcodeSource.LIBRARY)
                    .map(MappingPart::path)
                    .collect(java.util.stream.Collectors.toSet());
            files.stream()
                    .filter(f -> !already.contains(f))
                    .forEach(f -> addRow(new MappingPart(GcodeSource.LIBRARY, f, 1, 1, null, null)));
            projectSelect.clear();
        });
        add(projectSelect);

        final Button addPart = new Button("+ Add part", new Icon(VaadinIcon.PLUS));
        addPart.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addPart.addClickListener(e -> addRow(null));
        add(addPart);

        final Button saveBtn = new Button("Save Mapping");
        saveBtn.addClickListener(e -> onSave.accept(collectParts()));
        add(saveBtn);

        if (onQueue == null) {
            // Mapping-only mode (Mappings tab): no printer selection / queue controls
            return;
        }

        printerSelect.setLabel("Printer(s)");
        printerSelect.addClassName("mapping-printer-select");
        printerSelect.setItems(printerNames);
        printerSelect.addValueChangeListener(e -> updateTotalJobsLabel());
        add(printerSelect);

        totalJobsLabel.getStyle().setColor("var(--lumo-secondary-text-color)");
        add(totalJobsLabel);
        updateTotalJobsLabel();

        final Button queueBtn = new Button("Queue Print", new Icon(VaadinIcon.ARROW_CIRCLE_RIGHT));
        queueBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
        queueBtn.addClickListener(e -> onQueue.accept(collectParts(), new ArrayList<>(printerSelect.getValue())));
        add(queueBtn);
    }

    /** Mapping-only variant for the Mappings tab: edit and save parts, no printer selection or queueing. */
    public MappingPartsPanel(
            final Supplier<List<String>> libraryFilesSupplier,
            final Function<String, List<Integer>> plateIdsSupplier,
            final Supplier<List<String>> projectsSupplier,
            final Function<String, List<String>> projectFilesSupplier,
            final List<MappingPart> initialParts,
            final Consumer<List<MappingPart>> onSave) {
        this(libraryFilesSupplier, plateIdsSupplier, projectsSupplier, projectFilesSupplier,
                List.of(), initialParts, 1, onSave, null);
    }

    private void addRow(final MappingPart initial) {
        final PartRow row = new PartRow(initial);
        rows.add(row);
        rowsContainer.add(row.container);
        updateTotalJobsLabel();
    }

    private void removeRow(final PartRow row) {
        rows.remove(row);
        rowsContainer.remove(row.container);
        updateTotalJobsLabel();
    }

    private List<MappingPart> collectParts() {
        return rows.stream().map(PartRow::toMappingPart).filter(Objects::nonNull).toList();
    }

    private void updateTotalJobsLabel() {
        final int perUnit = collectParts().stream().mapToInt(MappingPart::copiesPerUnit).sum();
        final int totalJobs = perUnit * Math.max(1, orderedQuantity);
        final int printerCount = printerSelect.getValue().size();
        totalJobsLabel.setText(printerCount > 0
                ? "Will queue %d job(s) across %d printer(s)".formatted(totalJobs, printerCount)
                : "Will queue %d job(s) - select a printer".formatted(totalJobs));
    }

    private final class PartRow {

        final HorizontalLayout container = new HorizontalLayout();
        final ComboBox<GcodeSource> sourceSelect = new ComboBox<>("Source");
        final ComboBox<String> gcodeSelect = new ComboBox<>("Gcode file");
        final ComboBox<Integer> plateSelect = new ComboBox<>("Plate");
        final TextField sdPathField = new TextField("SD card path");
        final IntegerField sdPlateField = new IntegerField("Plate");
        final IntegerField copiesField = new IntegerField("Copies/unit");
        final ComboBox<Integer> amsSlotSelect = new ComboBox<>("AMS slot");
        final ComboBox<String> filamentSelect = new ComboBox<>("Filament");
        final ComboBox<com.tfyre.bambu.printer.FilamentColor> colorSelect = new ComboBox<>("Colour");
        final Button removeBtn = new Button(new Icon(VaadinIcon.TRASH));

        PartRow(final MappingPart initial) {
            container.addClassName("mapping-part-row");
            container.setAlignItems(FlexComponent.Alignment.BASELINE);

            sourceSelect.setItems(GcodeSource.values());
            sourceSelect.setItemLabelGenerator(s -> s == GcodeSource.LIBRARY ? "Library" : "SD Card");
            sourceSelect.setWidth("110px");
            sourceSelect.setValue(GcodeSource.LIBRARY);
            sourceSelect.addValueChangeListener(e -> applyVisibility());

            gcodeSelect.setItems(libraryFilesSupplier.get());
            gcodeSelect.setWidth("300px");
            // Let the dropdown overlay be wider than the input so long library filenames aren't truncated.
            gcodeSelect.getStyle().set("--vaadin-combo-box-overlay-width", "460px");
            plateSelect.setWidth("90px");
            gcodeSelect.addValueChangeListener(e -> {
                if (e.getValue() == null) {
                    plateSelect.setItems(List.of());
                    return;
                }
                final List<Integer> plates = plateIdsSupplier.apply(e.getValue());
                plateSelect.setItems(plates);
                plateSelect.setValue(plates.isEmpty() ? null : plates.get(0));
            });

            sdPathField.setWidth("220px");
            sdPathField.setPlaceholder("e.g. gcode/adapter.gcode.3mf");
            sdPlateField.setWidth("90px");
            sdPlateField.setValue(1);
            sdPlateField.setMin(1);
            sdPlateField.setStepButtonsVisible(true);

            copiesField.setWidth("110px");
            copiesField.setValue(1);
            copiesField.setMin(1);
            copiesField.setStepButtonsVisible(true);
            copiesField.setTooltipText("How many prints of this part are needed per 1 unit ordered");
            copiesField.addValueChangeListener(e -> updateTotalJobsLabel());

            amsSlotSelect.setItems(AmsSlotSupport.ITEMS);
            amsSlotSelect.setItemLabelGenerator(AmsSlotSupport::label);
            amsSlotSelect.setWidth("140px");
            amsSlotSelect.setClearButtonVisible(true);
            amsSlotSelect.setPlaceholder("Printer default");
            amsSlotSelect.setTooltipText("Force one specific AMS tray. Blank uses whatever is loaded.");

            filamentSelect.setItems("PLA", "PETG", "ASA", "ABS", "TPU", "PC", "PA", "PVA", "PET-CF", "PA-CF", "PLA-CF");
            filamentSelect.setAllowCustomValue(true);
            filamentSelect.addCustomValueSetListener(e -> filamentSelect.setValue(e.getDetail().toUpperCase()));
            filamentSelect.setWidth("120px");
            filamentSelect.setClearButtonVisible(true);
            filamentSelect.setPlaceholder("Any");
            filamentSelect.setTooltipText("Only send this part to a printer with this material loaded. Blank means no requirement.");

            colorSelect.setItems(com.tfyre.bambu.printer.FilamentColor.values());
            colorSelect.setItemLabelGenerator(com.tfyre.bambu.printer.FilamentColor::label);
            colorSelect.setWidth("120px");
            colorSelect.setClearButtonVisible(true);
            colorSelect.setPlaceholder("Any");
            colorSelect.setTooltipText("Only use a tray whose colour is closest to this. Blank means any colour - "
                    + "which is how an order once printed in grey ASA because that tray happened to be first. "
                    + "A tray whose colour the printer hasn't reported never matches.");
            // The swatch is the point: "Black" and "Grey" are two words that look alike in a dropdown and very
            // much do not look alike on a customer's part.
            colorSelect.setRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<com.vaadin.flow.component.html.Div, com.tfyre.bambu.printer.FilamentColor>(c -> {
                final com.vaadin.flow.component.html.Span dot = new com.vaadin.flow.component.html.Span();
                dot.addClassName("filament-swatch");
                dot.getStyle().setBackgroundColor(c.hex());
                final com.vaadin.flow.component.html.Span label = new com.vaadin.flow.component.html.Span(c.label());
                final com.vaadin.flow.component.html.Div row = new com.vaadin.flow.component.html.Div(dot, label);
                row.addClassName("filament-swatch-row");
                return row;
            }));

            removeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            removeBtn.setTooltipText("Remove this part");
            removeBtn.addClickListener(e -> removeRow(this));

            if (initial != null) {
                sourceSelect.setValue(initial.source());
                if (initial.source() == GcodeSource.LIBRARY) {
                    gcodeSelect.setValue(initial.path());
                    plateSelect.setItems(plateIdsSupplier.apply(initial.path()));
                    plateSelect.setValue(initial.plateId());
                } else {
                    sdPathField.setValue(initial.path());
                    sdPlateField.setValue(initial.plateId());
                }
                copiesField.setValue(initial.copiesPerUnit());
                amsSlotSelect.setValue(initial.amsSlot());
                filamentSelect.setValue(initial.filamentType());
                colorSelect.setValue(initial.color().orElse(null));
            }
            applyVisibility();

            container.add(sourceSelect, gcodeSelect, plateSelect, sdPathField, sdPlateField, copiesField,
                    amsSlotSelect, filamentSelect, colorSelect, removeBtn);
        }

        private void applyVisibility() {
            final boolean isLibrary = sourceSelect.getValue() == GcodeSource.LIBRARY;
            gcodeSelect.setVisible(isLibrary);
            plateSelect.setVisible(isLibrary);
            sdPathField.setVisible(!isLibrary);
            sdPlateField.setVisible(!isLibrary);
        }

        MappingPart toMappingPart() {
            final GcodeSource source = sourceSelect.getValue();
            final int copies = copiesField.getValue() == null ? 1 : copiesField.getValue();
            final Integer amsSlot = amsSlotSelect.getValue();
            final String filamentType = filamentSelect.getValue();
            final String filamentColor = colorSelect.getValue() == null ? null : colorSelect.getValue().label();
            if (source == GcodeSource.LIBRARY) {
                if (gcodeSelect.getValue() == null || plateSelect.getValue() == null) {
                    return null;
                }
                return new MappingPart(GcodeSource.LIBRARY, gcodeSelect.getValue(), plateSelect.getValue(), copies,
                        amsSlot, filamentType, filamentColor);
            }
            if (sdPathField.getValue() == null || sdPathField.getValue().isBlank()) {
                return null;
            }
            final int plate = sdPlateField.getValue() == null ? 1 : sdPlateField.getValue();
            return new MappingPart(GcodeSource.SD_CARD, sdPathField.getValue().trim(), plate, copies, amsSlot,
                    filamentType, filamentColor);
        }
    }

}
