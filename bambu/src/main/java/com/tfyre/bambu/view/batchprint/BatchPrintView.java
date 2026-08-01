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
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.component.upload.receivers.MultiFileBuffer;
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
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.GcodeSource;
import com.tfyre.bambu.printer.MappingPart;
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
    @Inject
    com.tfyre.bambu.printer.GcodeMappingQueuer queuer;

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
    private final MultiFileBuffer buffer = new MultiFileBuffer();
    private final Upload upload = new Upload(buffer);
    /** Which project folder uploads land in; blank/null = the library root as before. */
    private final ComboBox<String> uploadTarget = new ComboBox<>("Save into project");
    private final ComboBox<LibraryEntry> librarySelect = new ComboBox<>("Library");
    private final Button libraryDelete = new Button(VaadinIcon.TRASH.create(), l -> deleteLibraryFile());
    private final Button projectPrintAll = new Button("Queue whole project", VaadinIcon.COPY.create(), l -> queueWholeProject());
    /** Per-file rows for the selected library entry (project contents, or move-to-project for a loose file). */
    private final Div entryFiles = newDiv("project-files");
    private ProjectFile projectFile;
    private LibraryEntry selectedEntry;
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
        upload.setDropLabel(new Span("Drop files here (multiple allowed, max %dM each)"
                .formatted(maxBodySize.asLongValue() / 1_000_000)));
        upload.addFileRejectedListener(l -> {
            showError(l.getErrorMessage());
        });
        uploadTarget.setPlaceholder("Library root");
        uploadTarget.setAllowCustomValue(true);
        uploadTarget.setClearButtonVisible(true);
        uploadTarget.setWidth("240px");
        uploadTarget.setHelperText("Type a new name to create a project");
        uploadTarget.setTooltipText("Files uploaded together land in this project folder, so a multi-part product "
                + "stays grouped and can be queued in one go.");
        // Custom value = a new project folder; it only exists on disk once a file is actually saved into it
        uploadTarget.addCustomValueSetListener(e -> {
            final String name = sanitizeProjectName(e.getDetail());
            if (!name.isBlank()) {
                uploadTarget.setItems(withProject(getProjectNames(), name));
                uploadTarget.setValue(name);
            }
        });
        uploadTarget.setItems(getProjectNames());
    }

    /** Folder names are user input and become filesystem paths - keep them to a safe, flat character set. */
    private static String sanitizeProjectName(final String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^a-zA-Z0-9 _-]", "").trim();
    }

    private static List<String> withProject(final List<String> existing, final String name) {
        final List<String> out = new java.util.ArrayList<>(existing);
        if (name != null && !name.isBlank() && !out.contains(name)) {
            out.add(name);
            out.sort(String.CASE_INSENSITIVE_ORDER);
        }
        return out;
    }

    private Path getLibraryPath() {
        return Path.of(config.batchPrint().library());
    }

    /**
     * A library item: either a loose {@code .3mf} sitting at the library root (how the library has always worked)
     * or a <b>project</b> - a subfolder holding several files that belong to one product, so a multi-part item can
     * be uploaded, kept together and queued in a single action.
     */
    public record LibraryEntry(String name, boolean project, List<String> files) {

        /** Library-relative path of one file in this entry - this is what mappings and queue entries store. */
        public String pathOf(final String file) {
            return project ? name + "/" + file : file;
        }

        /** Path of the file used for the plate preview. */
        public String previewPath() {
            return project ? pathOf(files.get(0)) : name;
        }

        public String label() {
            return project ? "%s  (%d files)".formatted(name, files.size()) : name;
        }
    }

    private static List<String> listProjectFiles(final Path dir) {
        try (final java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(BambuConst.FILE_3MF))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    /** Projects first, then loose files; both alphabetical. */
    private List<LibraryEntry> getLibraryEntries() {
        final Path path = getLibraryPath();
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        final List<LibraryEntry> projects = new java.util.ArrayList<>();
        final List<LibraryEntry> loose = new java.util.ArrayList<>();
        try (final java.util.stream.Stream<Path> stream = Files.list(path)) {
            stream.sorted(Comparator.comparing(f -> f.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> {
                        final String name = entry.getFileName().toString();
                        if (Files.isDirectory(entry)) {
                            final List<String> files = listProjectFiles(entry);
                            if (!files.isEmpty()) {
                                projects.add(new LibraryEntry(name, true, files));
                            }
                        } else if (Files.isRegularFile(entry) && name.toLowerCase().endsWith(BambuConst.FILE_3MF)) {
                            loose.add(new LibraryEntry(name, false, List.of(name)));
                        }
                    });
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
        final List<LibraryEntry> all = new java.util.ArrayList<>(projects);
        all.addAll(loose);
        return all;
    }

    private List<String> getProjectNames() {
        return getLibraryEntries().stream().filter(LibraryEntry::project).map(LibraryEntry::name).toList();
    }

    /**
     * Queues every file in the selected project across the selected printers, round-robin. Reuses the same
     * {@link com.tfyre.bambu.printer.GcodeMappingQueuer} path that marketplace orders take, so plate resolution,
     * weight and the library lookup all behave identically to an auto-queued order.
     */
    private void queueWholeProject() {
        final LibraryEntry entry = selectedEntry;
        if (entry == null || !entry.project()) {
            showError("Select a project first");
            return;
        }
        final Set<PrinterMapping> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            showError("Select at least one printer");
            return;
        }
        final List<MappingPart> parts = entry.files().stream()
                .map(f -> new MappingPart(GcodeSource.LIBRARY, entry.pathOf(f), 1, 1, null, null))
                .toList();
        final List<String> printerNames = selected.stream().map(pm -> pm.getPrinterDetail().name()).toList();
        final GcodeMappingQueuer.QueueResult result = queuer.queue(parts, 1, printerNames);
        result.errors().forEach(this::showError);
        if (result.totalQueued() > 0) {
            showNotification("Queued %d file(s) from [%s] across %d printer(s) - start them from the dashboard"
                    .formatted(result.totalQueued(), entry.name(), printerNames.size()));
        }
    }

    /**
     * @param selectPath library-relative path to re-select ("file.3mf" or "Project/file.3mf"), or null for none
     */
    private void refreshLibrary(final String selectPath) {
        final List<LibraryEntry> entries = getLibraryEntries();
        librarySelect.setItems(entries);
        // setItems CLEARS a ComboBox's value. This method runs after EVERY uploaded file, so without restoring it
        // the chosen project would be wiped by file 1 and files 2..N would silently land in the library root.
        final String keepTarget = uploadTarget.getValue();
        uploadTarget.setItems(withProject(getProjectNames(), sanitizeProjectName(keepTarget)));
        uploadTarget.setValue(keepTarget);
        if (selectPath == null) {
            librarySelect.setValue(null);
            return;
        }
        final String projectName = selectPath.contains("/") ? selectPath.substring(0, selectPath.indexOf('/')) : null;
        // Re-selecting a project whose contents changed: the entry instance differs, so match on name only
        entries.stream()
                .filter(e -> projectName == null
                        ? !e.project() && e.name().equals(selectPath)
                        : e.project() && e.name().equals(projectName))
                .findFirst()
                .ifPresent(librarySelect::setValue);
    }

    private void configureLibrary() {
        librarySelect.setPlaceholder("Select saved project");
        librarySelect.setWidth("300px");
        librarySelect.setItemLabelGenerator(LibraryEntry::label);
        librarySelect.addValueChangeListener(l -> {
            final LibraryEntry entry = l.getValue();
            selectedEntry = entry;
            libraryDelete.setEnabled(entry != null);
            projectPrintAll.setVisible(entry != null && entry.project() && entry.files().size() > 1);
            if (entry != null && entry.project()) {
                projectPrintAll.setText("Queue whole project (%d files)".formatted(entry.files().size()));
            }
            refreshEntryFiles();
            if (!l.isFromClient() || entry == null) {
                return;
            }
            // A project previews its first file; "Queue whole project" is what sends all of them
            final String path = entry.previewPath();
            loadProjectFile(path, getLibraryPath().resolve(path).toFile());
        });
        libraryDelete.setTooltipText("Delete from library");
        libraryDelete.setEnabled(false);
        refreshLibrary(null);
    }

    /**
     * Called once per uploaded file, so a multi-file upload arrives one at a time. Each file goes into the chosen
     * project folder (or the library root when none is chosen), and the last one is left previewed.
     */
    private void saveToLibrary(final String filename) {
        final String project = sanitizeProjectName(uploadTarget.getValue());
        try {
            final Path dir = project.isBlank() ? getLibraryPath() : getLibraryPath().resolve(project);
            Files.createDirectories(dir);
            final String bare = Path.of(filename).getFileName().toString();
            final Path target = dir.resolve(bare);
            Files.copy(buffer.getFileData(filename).getFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            final String relative = project.isBlank() ? bare : project + "/" + bare;
            showNotification("Saved %s".formatted(relative));
            refreshLibrary(relative);
            refreshEntryFiles();
            loadProjectFile(relative, target.toFile());
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            showError("Cannot save to library: %s".formatted(ex.getMessage()));
            // fall back to printing straight from the upload buffer
            loadProjectFile(filename, buffer.getFileData(filename).getFile());
        }
    }

    /**
     * The contents of the selected library entry, one row per file. A project is otherwise opaque - you can see
     * that it holds N files but not which, can't preview any but the first, and can't remove one without deleting
     * the whole project. Loose files get a "move into project" control instead, which is also how you tidy up a
     * file that was saved to the root by mistake.
     */
    private void refreshEntryFiles() {
        entryFiles.removeAll();
        final LibraryEntry entry = selectedEntry;
        if (entry == null) {
            entryFiles.setVisible(false);
            return;
        }
        entryFiles.setVisible(true);
        if (!entry.project()) {
            final ComboBox<String> moveTo = new ComboBox<>();
            moveTo.setPlaceholder("Move into project…");
            moveTo.setAllowCustomValue(true);
            moveTo.setWidth("240px");
            moveTo.setItems(getProjectNames());
            moveTo.addCustomValueSetListener(e -> {
                final String name = sanitizeProjectName(e.getDetail());
                if (!name.isBlank()) {
                    moveTo.setItems(withProject(getProjectNames(), name));
                    moveTo.setValue(name);
                }
            });
            moveTo.addValueChangeListener(e -> {
                final String target = sanitizeProjectName(e.getValue());
                if (target.isBlank()) {
                    return;
                }
                moveFileToProject(entry.name(), target);
            });
            entryFiles.add(moveTo);
            return;
        }
        entryFiles.add(new Span("Files in %s:".formatted(entry.name())));
        entry.files().forEach(file -> {
            final Button preview = new Button(file, VaadinIcon.EYE.create(), l -> {
                final String path = entry.pathOf(file);
                loadProjectFile(path, getLibraryPath().resolve(path).toFile());
            });
            preview.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            preview.setTooltipText("Preview this file's plates");
            final Button remove = new Button(VaadinIcon.TRASH.create(), l ->
                    YesNoCancelDialog.show("Remove [%s] from project [%s]?".formatted(file, entry.name()), ync -> {
                        if (!ync.isConfirmed()) {
                            return;
                        }
                        try {
                            Files.deleteIfExists(getLibraryPath().resolve(entry.pathOf(file)));
                            showNotification("Removed %s from %s".formatted(file, entry.name()));
                        } catch (IOException ex) {
                            Log.error(ex.getMessage(), ex);
                            showError("Cannot delete: %s".formatted(ex.getMessage()));
                        }
                        closeProjectFile();
                        headerVisible(false);
                        refreshLibrary(entry.name() + "/");
                        refreshEntryFiles();
                    }));
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            remove.setTooltipText("Remove just this file from the project");
            final Div row = newDiv("project-file", preview, remove);
            entryFiles.add(row);
        });
    }

    /** Moves a loose library file into a project folder, creating the folder if needed. */
    private void moveFileToProject(final String filename, final String project) {
        try {
            final Path dir = getLibraryPath().resolve(project);
            Files.createDirectories(dir);
            final Path target = dir.resolve(filename);
            if (Files.exists(target)) {
                showError("[%s] already exists in project [%s]".formatted(filename, project));
                return;
            }
            Files.move(getLibraryPath().resolve(filename), target);
            showNotification("Moved %s into %s".formatted(filename, project));
            refreshLibrary(project + "/" + filename);
            refreshEntryFiles();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            showError("Cannot move: %s".formatted(ex.getMessage()));
        }
    }

    private void deleteLibraryFile() {
        final LibraryEntry entry = librarySelect.getValue();
        if (entry == null) {
            return;
        }
        final String what = entry.project()
                ? "project [%s] and all %d file(s) in it".formatted(entry.name(), entry.files().size())
                : "[%s]".formatted(entry.name());
        YesNoCancelDialog.show("Delete %s from the library?".formatted(what), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            try {
                final Path target = getLibraryPath().resolve(entry.name());
                if (entry.project()) {
                    // Only the .3mf files we listed, then the folder itself - never a blind recursive delete
                    for (final String file : entry.files()) {
                        Files.deleteIfExists(target.resolve(file));
                    }
                    try (final java.util.stream.Stream<Path> left = Files.list(target)) {
                        if (left.findAny().isEmpty()) {
                            Files.deleteIfExists(target);
                        } else {
                            showNotification("Removed the sliced files; [%s] still holds other files so the folder was kept"
                                    .formatted(entry.name()));
                        }
                    }
                } else {
                    Files.deleteIfExists(target);
                }
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
        projectPrintAll.setVisible(false);
        entryFiles.setVisible(false);
        configureThumbnail();
        headerVisible(false);
        add(newDiv("header", thumbnail, actions,
                newDiv("upload", upload, uploadTarget,
                        newDiv("library", librarySelect, libraryDelete, projectPrintAll), entryFiles)), grid);
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
