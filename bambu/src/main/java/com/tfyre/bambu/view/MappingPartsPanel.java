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
    private final int orderedQuantity;

    public MappingPartsPanel(
            final Supplier<List<String>> libraryFilesSupplier,
            final Function<String, List<Integer>> plateIdsSupplier,
            final List<String> printerNames,
            final List<MappingPart> initialParts,
            final int orderedQuantity,
            final Consumer<List<MappingPart>> onSave,
            final BiConsumer<List<MappingPart>, List<String>> onQueue) {
        this.libraryFilesSupplier = libraryFilesSupplier;
        this.plateIdsSupplier = plateIdsSupplier;
        this.orderedQuantity = orderedQuantity;
        addClassName("mapping-parts-panel");

        rowsContainer.addClassName("mapping-parts-rows");
        add(rowsContainer);

        if (initialParts.isEmpty()) {
            addRow(null);
        } else {
            initialParts.forEach(this::addRow);
        }

        final Button addPart = new Button("+ Add part", new Icon(VaadinIcon.PLUS));
        addPart.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addPart.addClickListener(e -> addRow(null));
        add(addPart);

        final Button saveBtn = new Button("Save Mapping");
        saveBtn.addClickListener(e -> onSave.accept(collectParts()));
        add(saveBtn);

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
            gcodeSelect.setWidth("220px");
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
            amsSlotSelect.setTooltipText("Force this print to load filament from one specific AMS tray (or the "
                    + "external spool) instead of using whatever the printer currently has loaded. Leave blank to "
                    + "use the printer's current/default filament.");

            filamentSelect.setItems("PLA", "PETG", "ASA", "ABS", "TPU", "PC", "PA", "PVA", "PET-CF", "PA-CF", "PLA-CF");
            filamentSelect.setAllowCustomValue(true);
            filamentSelect.addCustomValueSetListener(e -> filamentSelect.setValue(e.getDetail().toUpperCase()));
            filamentSelect.setWidth("120px");
            filamentSelect.setClearButtonVisible(true);
            filamentSelect.setPlaceholder("Any");
            filamentSelect.setTooltipText("Filament this part must print in, matched against each printer's live "
                    + "AMS telemetry. Auto-queue only sends this part to a printer that actually has this material "
                    + "loaded (in the specific AMS slot if one is set, otherwise any tray - the job is then pinned "
                    + "to that tray). Leave blank for no material requirement.");

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
            }
            applyVisibility();

            container.add(sourceSelect, gcodeSelect, plateSelect, sdPathField, sdPlateField, copiesField,
                    amsSlotSelect, filamentSelect, removeBtn);
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
            if (source == GcodeSource.LIBRARY) {
                if (gcodeSelect.getValue() == null || plateSelect.getValue() == null) {
                    return null;
                }
                return new MappingPart(GcodeSource.LIBRARY, gcodeSelect.getValue(), plateSelect.getValue(), copies, amsSlot, filamentType);
            }
            if (sdPathField.getValue() == null || sdPathField.getValue().isBlank()) {
                return null;
            }
            final int plate = sdPlateField.getValue() == null ? 1 : sdPlateField.getValue();
            return new MappingPart(GcodeSource.SD_CARD, sdPathField.getValue().trim(), plate, copies, amsSlot, filamentType);
        }
    }

}
