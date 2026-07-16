package com.tfyre.bambu.view.batchprint;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.PrintQueueService;
import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.view.GridHelper;
import com.tfyre.bambu.view.NotificationHelper;
import com.tfyre.bambu.view.PushDiv;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.FileBuffer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializablePredicate;
import com.vaadin.flow.function.ValueProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "batchprint", layout = MainLayout.class)
@PageTitle("Batch Print")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public final class BatchPrintView extends PushDiv implements NotificationHelper, FilamentHelper, GridHelper<PrinterMapping> {

    private static final String IMAGE_CLASS = "small";
    private static final SerializablePredicate<PrinterMapping> PREDICATE = pm -> true;

    @Inject
    BambuPrinters printers;
    @Inject
    Instance<PrinterMapping> printerMappingInstance;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    ManagedExecutor executor;
    @Inject
    ScheduledExecutorService ses;
    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize maxBodySize;
    @Inject
    BambuConfig config;
    @Inject
    PrintQueueService queueService;

    private final ComboBox<Plate> plateLookup = new ComboBox<>("Plate Id");
    private final Grid<PrinterMapping> grid = new Grid<>();
    private final HeaderRow headerRow = grid.appendHeaderRow();
    private final Image thumbnail = new Image();
    private final Span printTime = new Span();
    private final Span printWeight = new Span();
    private final Div printFilaments = newDiv("filaments");
    private final Checkbox skipSameSize = new Checkbox("Skip if same size");
    private final Checkbox timelapse = new Checkbox("Timelapse");
    private final Checkbox bedLevelling = new Checkbox("Bed Levelling");
    private final Checkbox flowCalibration = new Checkbox("Flow Calibration");
    private final Checkbox vibrationCalibration = new Checkbox("Vibration Calibration");
    private GridListDataView<PrinterMapping> dataView;
    private final Div actions = newDiv("actions", plateLookup,
            newDiv("detail", printTime, printWeight),
            printFilaments,
            newDiv("options", skipSameSize, timelapse, bedLevelling, flowCalibration, vibrationCalibration),
            newDiv("buttons",
                    new Button("Print", VaadinIcon.PRINT.create(), l -> printAll()),
                    new Button("Queue", VaadinIcon.TIME_FORWARD.create(), l -> queueAll()),
                    new Button("Refresh", VaadinIcon.REFRESH.create(), l -> refresh())
            ));
    private final FileBuffer buffer = new FileBuffer();
    private final Upload upload = new Upload(buffer);
    private final ComboBox<String> librarySelect = new ComboBox<>("Library");
    private final Button libraryDelete = new Button(VaadinIcon.TRASH.create(), l -> deleteLibraryFile());
    private ProjectFile projectFile;
    private List<PrinterMapping> printerMappings = List.of();
    private SerializablePredicate<PrinterMapping> predicate = PREDICATE;

    @Override
    public Grid<PrinterMapping> getGrid() {
        return grid;
    }

    private void configurePlate(final Plate plate) {
        if (plate == null) {
            return;
        }
        thumbnail.setSrc(projectFile.getThumbnail(plate));
        printTime.setText("Time: %s".formatted(formatTime(plate.prediction())));
        printWeight.setText("Weight: %.2fg".formatted(plate.weight()));
        printFilaments.removeAll();
        plate.filaments().forEach(pf -> {
            printFilaments.add(newDiv("filament", newFilament(pf), new Span("%.2fg".formatted(pf.weight()))));
        });
        printerMappings.forEach(pm -> pm.setPlate(plate));
        dataView.refreshAll();
    }

    private void configurePlateLookup() {
        plateLookup.setItemLabelGenerator(Plate::name);
        plateLookup.addValueChangeListener(l -> configurePlate(l.getValue()));
    }

    private Component createFilterHeader(final String labelText, final Consumer<String> filterChangeConsumer) {
        final TextField filterField = new TextField();
        filterField.addValueChangeListener(event -> filterChangeConsumer.accept(event.getValue()));
        filterField.setValueChangeMode(ValueChangeMode.LAZY);
        filterField.setSizeFull();
        filterField.setPlaceholder(labelText);
        filterField.setClearButtonVisible(true);
        return filterField;
    }

    private <T extends String> Grid.Column<PrinterMapping> setupColumnFilter(final String name, final ValueProvider<PrinterMapping, T> valueProvider) {
        final Grid.Column<PrinterMapping> result = setupColumn(name, valueProvider).setComparator(Comparator.comparing(valueProvider));

        final AtomicReference<String> filter = new AtomicReference<>(null);
        final SerializablePredicate<PrinterMapping> _predicate = pm ->
                Optional.ofNullable(filter.get()).map(s -> valueProvider.apply(pm).toLowerCase().contains(s)).orElse(true);

        predicate = predicate == PREDICATE ? _predicate : predicate.and(_predicate);

        headerRow.getCell(result).setComponent(createFilterHeader(name, s -> {
            filter.set(s.toLowerCase());
            dataView.refreshAll();
        }));
        return result;

    }

    private Component newCheckbox(final boolean checked) {
        final Checkbox result = new Checkbox();
        result.setValue(checked);
        result.setReadOnly(true);
        return result;
    }

    private void configureGrid() {
        final Grid.Column<PrinterMapping> colName
                = setupColumnFilter("Name", pm -> pm.getPrinterDetail().printer().getName()).setFlexGrow(2);
        setupColumn("Plate Id", pm -> Optional.ofNullable(plateLookup.getValue()).map(Plate::name).orElse("")).setFlexGrow(1);
        setupColumnFilter("Printer Status", pm -> pm.getPrinterDetail().printer().getGCodeState().getDescription()).setFlexGrow(2);
        grid.addComponentColumn(pm -> newCheckbox(pm.getPrinterDetail().printer().getGCodeState().isReady())).setHeader("Printer Ready").setFlexGrow(1);
        grid.addComponentColumn(PrinterMapping::getBulkStatus).setHeader("Bulk Status").setFlexGrow(2);
        grid.addComponentColumn(PrinterMapping::getFilamentMapping).setHeader("Filament Mapping").setFlexGrow(3);

        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.setColumnReorderingAllowed(true);
        grid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT);

        grid.sort(GridSortOrder.asc(colName).build());
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        final UI ui = getUI().get();
        printerMappings = printers.getPrintersDetail().stream()
                .filter(pd -> pd.isRunning())
                .map(pd -> printerMappingInstance.get().setup(ui, pd))
                .toList();
        dataView = grid.setItems(printerMappings);
        dataView.setIdentifierProvider(PrinterMapping::getId);
        dataView.addFilter(predicate);
    }

    private void printAll(final Set<PrinterMapping> selected) {
        final String user = SecurityUtils.getPrincipal().map(p -> p.getName()).orElse("null");
        final String ip = Optional.ofNullable(VaadinSession.getCurrent()).map(vs -> vs.getBrowser().getAddress()).orElse("null");
        Log.infof("printAll: user[%s] ip[%s] file[%s] printers[%s]", user, ip, projectFile.getFilename(),
                selected.stream().map(pm -> pm.getPrinterDetail().name()).toList());
        final BambuPrinter.CommandPPF command = new BambuPrinter.CommandPPF("", 0, true, timelapse.getValue(), bedLevelling.getValue(), flowCalibration.getValue(), vibrationCalibration.getValue(), List.of());
        selected.forEach(pm -> executor.submit(() -> pm.sendPrint(projectFile, command, skipSameSize.getValue())));
        showNotification("Queued: %d".formatted(selected.size()));
    }

    private void refresh() {
        printerMappings.forEach(PrinterMapping::refresh);
        dataView.refreshAll();
    }

    private void printAll() {
        final Set<PrinterMapping> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            showError("Nothing selected");
            return;
        }
        if (selected.stream().filter(PrinterMapping::canPrint).count() != selected.size()) {
            showError("Please ensure printers are idle and filaments are mapped");
            return;
        }

        doConfirm(() -> printAll(selected));
    }

    private void queueAll() {
        final Set<PrinterMapping> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            showError("Nothing selected");
            return;
        }
        if (!selected.stream().allMatch(PrinterMapping::isMapped)) {
            showError("Please ensure filaments are mapped");
            return;
        }
        final BambuPrinter.CommandPPF base = new BambuPrinter.CommandPPF("", 0, true, timelapse.getValue(),
                bedLevelling.getValue(), flowCalibration.getValue(), vibrationCalibration.getValue(), List.of());
        selected.forEach(pm -> queueService.add(pm.getPrinterDetail().name(),
                new PrintQueueService.QueueEntry(pm.buildCommand(projectFile, base), pm.getPlateWeight())));
        showNotification("Queued [%s] on %d printer(s) - start from the dashboard when the bed is clear"
                .formatted(projectFile.getFilename(), selected.size()));
    }

    private void headerVisible(final boolean isVisible) {
        thumbnail.setVisible(isVisible);
        actions.setVisible(isVisible);
    }

    private void configureUpload() {
        upload.setAcceptedFileTypes(BambuConst.FILE_3MF);
        upload.addSucceededListener(e -> saveToLibrary(e.getFileName()));
        upload.setMaxFileSize((int) maxBodySize.asLongValue());
        upload.setDropLabel(new Span("Drop file here (max size: %dM)".formatted(maxBodySize.asLongValue() / 1_000_000)));
        upload.addFileRejectedListener(l -> {
            showError(l.getErrorMessage());
        });
    }

    private Path getLibraryPath() {
        return Path.of(config.batchPrint().library());
    }

    private List<String> getLibraryFiles() {
        final Path path = getLibraryPath();
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (final java.util.stream.Stream<Path> stream = Files.list(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(BambuConst.FILE_3MF))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    private void refreshLibrary(final String select) {
        librarySelect.setItems(getLibraryFiles());
        librarySelect.setValue(select);
    }

    private void configureLibrary() {
        librarySelect.setPlaceholder("Select saved project");
        librarySelect.setWidth("300px");
        librarySelect.addValueChangeListener(l -> {
            libraryDelete.setEnabled(l.getValue() != null);
            if (!l.isFromClient() || l.getValue() == null) {
                return;
            }
            loadProjectFile(l.getValue(), getLibraryPath().resolve(l.getValue()).toFile());
        });
        libraryDelete.setTooltipText("Delete from library");
        libraryDelete.setEnabled(false);
        refreshLibrary(null);
    }

    private void saveToLibrary(final String filename) {
        try {
            final Path path = getLibraryPath();
            Files.createDirectories(path);
            final Path target = path.resolve(Path.of(filename).getFileName());
            Files.copy(buffer.getFileData().getFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            refreshLibrary(target.getFileName().toString());
            loadProjectFile(target.getFileName().toString(), target.toFile());
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            showError("Cannot save to library: %s".formatted(ex.getMessage()));
            // fall back to printing straight from the upload buffer
            loadProjectFile(filename, buffer.getFileData().getFile());
        }
    }

    private void deleteLibraryFile() {
        final String value = librarySelect.getValue();
        if (value == null) {
            return;
        }
        YesNoCancelDialog.show("Delete [%s] from library?".formatted(value), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            try {
                Files.deleteIfExists(getLibraryPath().resolve(value));
            } catch (IOException ex) {
                Log.error(ex.getMessage(), ex);
                showError("Cannot delete: %s".formatted(ex.getMessage()));
            }
            closeProjectFile();
            headerVisible(false);
            refreshLibrary(null);
        });
    }

    private void configureThumbnail() {
        thumbnail.addClassName(IMAGE_CLASS);
        thumbnail.addClickListener(l -> {
            if (thumbnail.hasClassName(IMAGE_CLASS)) {
                thumbnail.removeClassName(IMAGE_CLASS);
            } else {
                thumbnail.addClassName(IMAGE_CLASS);
            }
        });
    }

    private void updateBulkStatus() {
        printerMappings.forEach(PrinterMapping::updateBulkStatus);
    }

    private void configureActions() {
        skipSameSize.setValue(config.batchPrint().skipSameSize());
        timelapse.setValue(config.batchPrint().timelapse());
        bedLevelling.setValue(config.batchPrint().bedLevelling());
        flowCalibration.setValue(config.batchPrint().flowCalibration());
        vibrationCalibration.setValue(config.batchPrint().vibrationCalibration());
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("batchprint-view");
        configureActions();
        configurePlateLookup();
        configureGrid();
        configureUpload();
        configureLibrary();
        configureThumbnail();
        headerVisible(false);
        add(newDiv("header", thumbnail, actions, newDiv("upload", upload, newDiv("library", librarySelect, libraryDelete))), grid);
        final UI ui = attachEvent.getUI();
        createFuture(() -> ui.access(this::updateBulkStatus), config.refreshInterval());
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        closeProjectFile();
    }

    private void loadProjectFile(final String filename, final File file) {
        closeProjectFile();
        plateLookup.setItems(List.of());
        try {
            projectFile = projectFileInstance.get().setup(filename, file);
        } catch (ProjectException ex) {
            showError(ex);
            return;
        }
        final List<Plate> plates = projectFile.getPlates();
        plateLookup.setItems(plates);
        if (plates.isEmpty()) {
            headerVisible(false);
            showError("No sliced plates found");
        } else {
            headerVisible(true);
            plateLookup.setValue(plates.get(0));
        }
    }

    private void closeProjectFile() {
        if (projectFile == null) {
            return;
        }
        projectFileInstance.destroy(projectFile);
        projectFile = null;
    }

}
