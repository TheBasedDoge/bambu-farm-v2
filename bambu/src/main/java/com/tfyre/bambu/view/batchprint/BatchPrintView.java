package com.tfyre.bambu.view.batchprint;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.LibraryService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.PrintQueueService;
import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.view.GridHelper;
import com.tfyre.bambu.view.NotificationHelper;
import com.tfyre.bambu.view.PushDiv;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Batch Print: one file on several printers, in three steps - what to print, on which printers, go.
 * <p>
 * Redesigned 2026-10-13. Uploading, moving and deleting library files used to live here too; they are on the
 * Files page now (which has a "Batch print" button per part that opens this page with the file picked:
 * {@code batchprint?file=<library path>}). Each printer now says whether it can take the job, and one sliced for
 * the other printer type (a P1 file on the H2D, or the reverse) can no longer be sent to it.
 */
@Route(value = "batchprint", layout = MainLayout.class)
@PageTitle("Batch Print")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public final class BatchPrintView extends PushDiv implements NotificationHelper, FilamentHelper, GridHelper<PrinterMapping> {

    private static final String IMAGE_CLASS = "small";

    @Inject
    BambuPrinters printers;
    @Inject
    Instance<PrinterMapping> printerMappingInstance;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    ManagedExecutor executor;
    @Inject
    BambuConfig config;
    @Inject
    PrintQueueService queueService;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    com.tfyre.bambu.printer.LibraryFileInfo libraryFileInfo;
    @Inject
    com.tfyre.bambu.printer.BedClearService bedClear;

    private final ComboBox<String> filePick = new ComboBox<>("File");
    private final ComboBox<String> projectPick = new ComboBox<>("Or a whole project");
    private final ComboBox<Plate> plateLookup = new ComboBox<>("Plate");
    private final Grid<PrinterMapping> grid = new Grid<>();
    private final Image thumbnail = new Image();
    private final Span printTime = new Span();
    private final Span printWeight = new Span();
    private final Span slicedFor = new Span();
    private final Div printFilaments = newDiv("filaments");
    private final Checkbox skipSameSize = new Checkbox("Skip the upload if the same file is already on the SD card");
    private final Checkbox timelapse = new Checkbox("Timelapse");
    private final Checkbox bedLevelling = new Checkbox("Bed levelling");
    private final Checkbox flowCalibration = new Checkbox("Flow calibration");
    private final Checkbox vibrationCalibration = new Checkbox("Vibration calibration");
    private final Button printNow = new Button("Print now", VaadinIcon.PRINT.create(), l -> printAll());
    private final Button addToQueue = new Button("Add to queue", VaadinIcon.TIME_FORWARD.create(), l -> queueAll());
    private final Button queueProject = new Button("Queue whole project", VaadinIcon.COPY.create(), l -> queueWholeProject());
    private final Span picked = new Span();
    private final Div fileFacts = newDiv("bpx-facts");
    private GridListDataView<PrinterMapping> dataView;
    private ProjectFile projectFile;
    /** Library path of the file being previewed, for the wrong-printer check. */
    private String filePath;
    /** Project picked in step 1, if any - "Queue whole project" sends all of it. */
    private String projectName;
    private Map<String, List<String>> projects = Map.of();
    private List<PrinterMapping> printerMappings = List.of();

    @Override
    public Grid<PrinterMapping> getGrid() {
        return grid;
    }

    // -------------------------------------------------------------------------
    // step 1: what to print
    // -------------------------------------------------------------------------

    private static String projectOf(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private void configureFilePick() {
        final List<String> files = queuer.listLibraryFiles();
        final Map<String, List<String>> byProject = new LinkedHashMap<>();
        files.stream().filter(f -> f.contains("/"))
                .forEach(f -> byProject.computeIfAbsent(projectOf(f), k -> new ArrayList<>()).add(f));
        projects = byProject;
        filePick.setItems(files.stream()
                .sorted(Comparator.comparing((String f) -> LibraryService.titleOf(f), String.CASE_INSENSITIVE_ORDER))
                .toList());
        filePick.setItemLabelGenerator(f -> projectOf(f).isEmpty() ? LibraryService.titleOf(f)
                : "%s · %s".formatted(LibraryService.titleOf(f), projectOf(f)));
        filePick.setPlaceholder("Search the library");
        filePick.setWidthFull();
        filePick.addValueChangeListener(e -> {
            if (e.getValue() == null) {
                return;
            }
            if (e.isFromClient()) {
                projectName = null;
                projectPick.clear();
            }
            open(e.getValue());
        });
        projectPick.setItems(byProject.entrySet().stream().filter(en -> en.getValue().size() > 1).map(Map.Entry::getKey).toList());
        projectPick.setItemLabelGenerator(p -> "%s · %d files".formatted(p, projects.getOrDefault(p, List.of()).size()));
        projectPick.setPlaceholder("Queue every file of a project");
        projectPick.setWidthFull();
        projectPick.setClearButtonVisible(true);
        projectPick.addValueChangeListener(e -> {
            projectName = e.getValue();
            if (projectName != null) {
                final List<String> inIt = projects.getOrDefault(projectName, List.of());
                if (!inIt.isEmpty()) {
                    filePick.setValue(inIt.get(0));
                }
            }
            updateButtons();
        });
        plateLookup.setItemLabelGenerator(Plate::name);
        plateLookup.addValueChangeListener(l -> configurePlate(l.getValue()));
    }

    private void open(final String path) {
        filePath = path;
        loadProjectFile(path, Path.of(config.batchPrint().library()).resolve(path).toFile());
        final com.tfyre.bambu.printer.LibraryFileInfo.Info info = libraryFileInfo.of(path);
        slicedFor.setText(info.family().isEmpty() ? "not sliced for a known printer" : "sliced for " + info.badge());
        refreshGrid();
    }

    private void configurePlate(final Plate plate) {
        if (plate == null) {
            return;
        }
        thumbnail.setSrc(projectFile.getThumbnail(plate));
        printTime.setText(formatTime(plate.prediction()));
        printWeight.setText("%.0f g".formatted(plate.weight()));
        printFilaments.removeAll();
        plate.filaments().forEach(pf -> printFilaments.add(newDiv("filament", newFilament(pf), new Span("%.0f g".formatted(pf.weight())))));
        printerMappings.forEach(pm -> pm.setPlate(plate));
        refreshGrid();
    }

    // -------------------------------------------------------------------------
    // step 2: on which printers
    // -------------------------------------------------------------------------

    /** Why this printer can't take the job right now, or null when it can. Shown in the grid, checked on Print. */
    private String blocker(final PrinterMapping pm) {
        final BambuPrinters.PrinterDetail pd = pm.getPrinterDetail();
        if (filePath != null) {
            final String wrong = libraryFileInfo.mismatch(filePath, pd.config().model());
            if (wrong != null) {
                return "wrong printer - this file is " + slicedFor.getText();
            }
        }
        final BambuConst.GCodeState state = pd.printer().getGCodeState();
        if (state.isPrinting() || state == BambuConst.GCodeState.PAUSE) {
            return "busy - printing";
        }
        if ((state == BambuConst.GCodeState.FINISH || state == BambuConst.GCodeState.FAILED) && !bedClear.isCleared(pd.name())) {
            return "bed to clear";
        }
        if (!state.isReady() && state != BambuConst.GCodeState.FINISH && state != BambuConst.GCodeState.FAILED) {
            return state.getDescription().toLowerCase();
        }
        return null;
    }

    private boolean wrongPrinter(final PrinterMapping pm) {
        return filePath != null && libraryFileInfo.mismatch(filePath, pm.getPrinterDetail().config().model()) != null;
    }

    private void configureGrid() {
        final Grid.Column<PrinterMapping> colName = setupColumn("Printer", pm -> pm.getPrinterDetail().printer().getName())
                .setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(pm -> {
            final String why = blocker(pm);
            final Span chip = new Span(why == null ? "ready" : why);
            chip.addClassName("bpx-chip");
            chip.getElement().setAttribute("data-tone", why == null ? "ok" : why.startsWith("wrong") ? "off"
                    : why.startsWith("bed") ? "warn" : "dim");
            return chip;
        }).setHeader("Can take it").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(PrinterMapping::getFilamentMapping).setHeader("Filament").setFlexGrow(3);
        grid.addComponentColumn(PrinterMapping::getBulkStatus).setHeader("Progress").setFlexGrow(1);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_NO_BORDER);
        grid.setAllRowsVisible(true);
        grid.sort(GridSortOrder.asc(colName).build());
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addSelectionListener(e -> updateButtons());
        final UI ui = getUI().get();
        printerMappings = printers.getPrintersDetail().stream()
                .filter(BambuPrinters.PrinterDetail::isRunning)
                .map(pd -> printerMappingInstance.get().setup(ui, pd))
                .toList();
        dataView = grid.setItems(printerMappings);
        dataView.setIdentifierProvider(PrinterMapping::getId);
    }

    private void refreshGrid() {
        if (dataView != null) {
            // A printer of the wrong type is taken out of the selection rather than left to fail on Print
            grid.getSelectedItems().stream().filter(this::wrongPrinter).toList().forEach(grid::deselect);
            dataView.refreshAll();
        }
        updateButtons();
    }

    // -------------------------------------------------------------------------
    // step 3: go
    // -------------------------------------------------------------------------

    private void updateButtons() {
        final Set<PrinterMapping> selected = grid.getSelectedItems();
        final long ready = selected.stream().filter(pm -> blocker(pm) == null).count();
        printNow.setText(ready == 0 ? "Print now" : "Print now on %d".formatted(ready));
        addToQueue.setText(selected.isEmpty() ? "Add to queue" : "Add to queue on %d".formatted(selected.size()));
        printNow.setEnabled(projectFile != null && ready > 0);
        addToQueue.setEnabled(projectFile != null && !selected.isEmpty());
        final boolean project = projectName != null && projects.getOrDefault(projectName, List.of()).size() > 1;
        queueProject.setVisible(project);
        if (project) {
            queueProject.setText("Queue all %d files of %s".formatted(projects.get(projectName).size(), projectName));
            queueProject.setEnabled(!selected.isEmpty());
        }
        picked.setText(selected.isEmpty() ? "Tick the printers to use." : "%d picked · %d can start now".formatted(selected.size(), ready));
    }

    private void printAll(final Set<PrinterMapping> selected) {
        final String user = SecurityUtils.getPrincipal().map(p -> p.getName()).orElse("null");
        final String ip = Optional.ofNullable(VaadinSession.getCurrent()).map(vs -> vs.getBrowser().getAddress()).orElse("null");
        Log.infof("printAll: user[%s] ip[%s] file[%s] printers[%s]", user, ip, projectFile.getFilename(),
                selected.stream().map(pm -> pm.getPrinterDetail().name()).toList());
        final BambuPrinter.CommandPPF command = new BambuPrinter.CommandPPF("", 0, true, timelapse.getValue(), bedLevelling.getValue(),
                flowCalibration.getValue(), vibrationCalibration.getValue(), List.of());
        selected.forEach(pm -> executor.submit(() -> pm.sendPrint(projectFile, command, skipSameSize.getValue())));
        showNotification("Sending to %d printer(s)".formatted(selected.size()));
    }

    private void printAll() {
        final Set<PrinterMapping> ready = grid.getSelectedItems().stream()
                .filter(pm -> blocker(pm) == null).collect(java.util.stream.Collectors.toSet());
        if (ready.isEmpty()) {
            showError("None of the picked printers can start now");
            return;
        }
        final List<String> unmapped = ready.stream().filter(pm -> !pm.canPrint()).map(pm -> pm.getPrinterDetail().name()).toList();
        if (!unmapped.isEmpty()) {
            showError("Pick the filament for %s first".formatted(String.join(", ", unmapped)));
            return;
        }
        doConfirm(() -> printAll(ready));
    }

    private void queueAll() {
        final Set<PrinterMapping> selected = grid.getSelectedItems().stream()
                .filter(pm -> !wrongPrinter(pm)).collect(java.util.stream.Collectors.toSet());
        if (selected.isEmpty()) {
            showError("Nothing picked that can print this file");
            return;
        }
        if (!selected.stream().allMatch(PrinterMapping::isMapped)) {
            showError("Pick the filament for every printer first");
            return;
        }
        final BambuPrinter.CommandPPF base = new BambuPrinter.CommandPPF("", 0, true, timelapse.getValue(),
                bedLevelling.getValue(), flowCalibration.getValue(), vibrationCalibration.getValue(), List.of());
        selected.forEach(pm -> queueService.add(pm.getPrinterDetail().name(),
                new PrintQueueService.QueueEntry(pm.buildCommand(projectFile, base), pm.getPlateWeight())));
        showNotification("Queued %s on %d printer(s) - auto-start runs them as beds clear"
                .formatted(LibraryService.titleOf(projectFile.getFilename()), selected.size()));
    }

    /**
     * Queues every file in the picked project across the picked printers, round-robin, through the same
     * {@link GcodeMappingQueuer} path marketplace orders take.
     */
    private void queueWholeProject() {
        final List<String> files = projectName == null ? List.of() : projects.getOrDefault(projectName, List.of());
        if (files.size() < 2) {
            showError("Pick a project first");
            return;
        }
        final Set<PrinterMapping> selected = grid.getSelectedItems();
        if (selected.isEmpty()) {
            showError("Pick at least one printer");
            return;
        }
        // Paired, not one part per file: an H2D slice in the project is the H2D file of the part it is named
        // after. Queued as a part of its own it would be printed IN ADDITION to the P1 slice of the same thing.
        final List<MappingPart> parts = libraryFileInfo.pair(files);
        final List<String> printerNames = selected.stream().map(pm -> pm.getPrinterDetail().name()).toList();
        final GcodeMappingQueuer.QueueResult result = queuer.queue(parts, 1, printerNames);
        result.errors().forEach(this::showError);
        if (result.totalQueued() > 0) {
            showNotification("Queued %d file(s) from %s across %d printer(s)"
                    .formatted(result.totalQueued(), projectName, printerNames.size()));
        }
    }

    // -------------------------------------------------------------------------
    // page
    // -------------------------------------------------------------------------

    private void configureActions() {
        skipSameSize.setValue(config.batchPrint().skipSameSize());
        timelapse.setValue(config.batchPrint().timelapse());
        bedLevelling.setValue(config.batchPrint().bedLevelling());
        flowCalibration.setValue(config.batchPrint().flowCalibration());
        vibrationCalibration.setValue(config.batchPrint().vibrationCalibration());
    }

    private void updateBulkStatus() {
        printerMappings.forEach(PrinterMapping::updateBulkStatus);
    }

    private static Div step(final String number, final String title, final com.vaadin.flow.component.Component... content) {
        final Span n = new Span(number);
        n.addClassName("bpx-n");
        final H3 h = new H3(title);
        final Div head = new Div(n, h);
        head.addClassName("bpx-step-h");
        final Div step = new Div(head);
        step.add(content);
        step.addClassName("bpx-step");
        return step;
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("batchprint-view");
        addClassName("bp-v2");
        configureActions();
        configureFilePick();
        configureGrid();
        thumbnail.addClassName(IMAGE_CLASS);
        thumbnail.addClassName("bpx-thumb");
        thumbnail.addClickListener(l -> thumbnail.getClassNames().set(IMAGE_CLASS, !thumbnail.hasClassName(IMAGE_CLASS)));
        printNow.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        queueProject.setVisible(false);
        slicedFor.addClassName("bpx-dim");
        picked.addClassName("bpx-dim");

        final Div facts = newDiv("bpx-facts-line", plateLookup, printTime, printWeight, slicedFor);
        fileFacts.add(newDiv("bpx-pick", thumbnail, newDiv("bpx-pick-text", facts, printFilaments)));
        fileFacts.setVisible(false);

        final Span hint = new Span("Print one file on several printers. Uploading and organising files is on the Files page.");
        hint.addClassName("bpx-dim");
        final Div title = newDiv("bpx-title", new H3("Batch Print"), hint);

        final Details options = new Details("Print options",
                newDiv("bpx-options", bedLevelling, flowCalibration, vibrationCalibration, timelapse, skipSameSize));
        final Span goHint = new Span("Print now sends to the picked printers that can start straight away. "
                + "Add to queue puts the job in each picked printer's queue; auto-start runs it when the bed is clear.");
        goHint.addClassName("bpx-dim");

        final Div steps = newDiv("bpx-steps",
                step("1", "What to print", filePick, fileFacts, projectPick),
                step("3", "Go", newDiv("bpx-go", printNow, addToQueue, queueProject), picked, goHint, options),
                step("2", "On which printers", grid));
        add(title, steps);
        updateButtons();

        // batchprint?file=<library path> - the Files page's "Batch print" button
        attachEvent.getUI().getInternals().getActiveViewLocation().getQueryParameters()
                .getSingleParameter("file")
                .filter(f -> !f.isBlank())
                .ifPresent(f -> {
                    if (queuer.listLibraryFiles().contains(f)) {
                        filePick.setValue(f);
                    } else {
                        showError("%s is not in the library any more".formatted(f));
                    }
                });
        createRefresh(attachEvent.getUI(), config.refreshInterval(), () -> {
            updateBulkStatus();
            // Readiness changes as prints end and beds are cleared
            if (dataView != null) {
                dataView.refreshAll();
            }
            updateButtons();
        });
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
            fileFacts.setVisible(false);
            return;
        }
        final List<Plate> plates = projectFile.getPlates();
        plateLookup.setItems(plates);
        if (plates.isEmpty()) {
            fileFacts.setVisible(false);
            showError("No sliced plates found in this file");
        } else {
            fileFacts.setVisible(true);
            plateLookup.setValue(plates.get(0));
        }
        updateButtons();
    }

    private void closeProjectFile() {
        if (projectFile == null) {
            return;
        }
        projectFileInstance.destroy(projectFile);
        projectFile = null;
    }
}
