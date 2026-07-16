package com.tfyre.bambu.view;

import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinterConsumer;
import com.tfyre.bambu.printer.BambuPrinterException;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.MaintenanceService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "maintenance", layout = MainLayout.class)
@PageTitle("Maintenance")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public class MaintenanceView extends VerticalLayout implements NotificationHelper, GridHelper<BambuPrinters.PrinterDetail>, UpdateHeader {

    private static final DateTimeFormatter DTF = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter();

    @Inject
    BambuPrinters printers;

    @Inject
    ManagedExecutor executor;

    @Inject
    MaintenanceService maintenanceService;

    @Inject
    com.tfyre.bambu.BambuConfig config;

    private final Grid<BambuPrinters.PrinterDetail> grid = new Grid<>();

    @Override
    public Grid<BambuPrinters.PrinterDetail> getGrid() {
        return grid;
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("maintenance-view");
        setSizeFull();
        configureGrid();
        add(grid);
        refreshItems();
    }

    private Button newButton(final BambuPrinters.PrinterDetail pd, final String action, final VaadinIcon icon, final BambuPrinterConsumer<String> consumer) {
        final Button result = new Button("", new Icon(icon), l -> {
            final Optional<UI> ui = getUI();
            executor.submit(() -> {
                try {
                    consumer.accept(pd.name());
                } catch (BambuPrinterException ex) {
                    Log.error(ex.getMessage(), ex);
                    ui.get().access(() -> {
                        showError(ex.getMessage());
                        refreshItems();
                    });
                }
            });
        });
        result.setTooltipText(action);
        return result;
    }

    private void refreshItems() {
        grid.setItems(printers.getPrintersDetail());
    }

    private <T> Comparator<BambuPrinters.PrinterDetail> getODTComparator(
            final Function<BambuPrinter, Optional<T>> function1,
            final Function<T, OffsetDateTime> function2) {
        return Comparator.comparing(pd ->
                function1.apply(pd.printer())
                        .map(function2)
                        .map(odt -> odt.toEpochSecond())
                        .orElse(0l)
        );
    }

    private void configureGrid() {
        final Grid.Column<BambuPrinters.PrinterDetail> colName
                = setupColumn("Name", pd -> pd.printer().getName());
        setupColumnCheckBox("Running", pd -> pd.isRunning());
        setupColumn("Print Hours", pd -> "%.1f".formatted(maintenanceService.getPrintHours(pd.name())))
                .setSortable(true).setComparator(Comparator.<BambuPrinters.PrinterDetail>comparingDouble(pd -> maintenanceService.getPrintHours(pd.name())));
        setupColumn("Last Status", pd -> pd.printer().getStatus().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getStatus, BambuPrinter.Message::lastUpdated));
        setupColumn("Last Full Status", pd -> pd.printer().getFullStatus().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getFullStatus, BambuPrinter.Message::lastUpdated));
        setupColumn("Last Thumbnail", pd -> pd.printer().getThumbnail().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getThumbnail, BambuPrinter.Thumbnail::lastUpdated));

        grid.addComponentColumn(v -> {
            final Button gcode = new Button("", new Icon(VaadinIcon.COG), l -> GCodeDialog.show(v.printer()));
            gcode.setTooltipText("Send GCode");
            final Button maintenance = new Button("", new Icon(VaadinIcon.WRENCH), l -> showMaintenanceDialog(v));
            maintenance.setTooltipText("Maintenance Tasks");
            return new HorizontalLayout(
                    newButton(v, "Enable", VaadinIcon.PLAY, printers::startPrinter),
                    newButton(v, "Disable", VaadinIcon.STOP, printers::stopPrinter),
                    gcode,
                    maintenance
            );
        });

        grid.setColumnReorderingAllowed(true);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.sort(GridSortOrder.asc(colName).build());
    }

    private void showMaintenanceDialog(final BambuPrinters.PrinterDetail pd) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("%s: Maintenance".formatted(pd.name()));
        dialog.setWidth("750px");

        final Grid<MaintenanceService.TaskStatus> taskGrid = new Grid<>();
        final Runnable reload = () -> taskGrid.setItems(maintenanceService.getTaskStatus(pd.name()));

        taskGrid.addColumn(ts -> ts.task().name()).setHeader("Task").setFlexGrow(2);
        taskGrid.addColumn(ts -> "%.0f".formatted(ts.task().intervalHours())).setHeader("Interval (h)");
        taskGrid.addComponentColumn(ts -> {
            final Span result = new Span("%.1f".formatted(ts.hoursSince()));
            result.addClassName(ts.overdue() ? LumoUtility.TextColor.ERROR : LumoUtility.TextColor.SUCCESS);
            return result;
        }).setHeader("Hours Since");
        taskGrid.addColumn(ts -> ts.task().lastDoneDate().isEmpty() ? "--" : ts.task().lastDoneDate()).setHeader("Last Done");
        taskGrid.addComponentColumn(ts -> {
            final Button done = new Button("Done", l -> {
                maintenanceService.markDone(pd.name(), ts.task().name());
                reload.run();
            });
            done.setTooltipText("Mark as done now");
            final Button remove = new Button("", new Icon(VaadinIcon.TRASH), l -> {
                maintenanceService.removeTask(pd.name(), ts.task().name());
                reload.run();
            });
            remove.setTooltipText("Remove task");
            return new HorizontalLayout(done, remove);
        }).setFlexGrow(2);
        taskGrid.setAllRowsVisible(true);
        taskGrid.setColumnReorderingAllowed(true);
        taskGrid.getColumns().forEach(c -> c.setResizable(true));
        reload.run();

        final NumberField hoursField = new NumberField("Total Print Hours");
        hoursField.setValue(Math.round(maintenanceService.getPrintHours(pd.name()) * 10) / 10.0);
        hoursField.setStep(0.1);
        hoursField.setMin(0);
        final Button saveHours = new Button("Save Hours", l -> {
            maintenanceService.setPrintHours(pd.name(), Optional.ofNullable(hoursField.getValue()).orElse(0.0));
            reload.run();
            refreshItems();
            showNotification("Print hours updated");
        });
        final HorizontalLayout hoursLayout = new HorizontalLayout(hoursField, saveHours);
        hoursLayout.setDefaultVerticalComponentAlignment(Alignment.END);

        final TextField newName = new TextField("New Task");
        final NumberField newInterval = new NumberField("Interval (h)");
        newInterval.setValue(100.0);
        newInterval.setMin(0);
        final Button add = new Button("Add", l -> {
            final String name = Optional.ofNullable(newName.getValue()).orElse("").trim();
            if (name.isEmpty()) {
                showError("Task name is required");
                return;
            }
            maintenanceService.addTask(pd.name(), name, Optional.ofNullable(newInterval.getValue()).orElse(0.0));
            newName.clear();
            reload.run();
        });
        final HorizontalLayout addLayout = new HorizontalLayout(newName, newInterval, add);
        addLayout.setDefaultVerticalComponentAlignment(Alignment.END);

        dialog.add(hoursLayout, taskGrid, addLayout);
        final Button close = new Button("Close", l -> {
            dialog.close();
            refreshItems();
        });
        dialog.getFooter().add(close);
        dialog.open();
    }

    private void addZipEntry(final ZipOutputStream zos, final String name, final Path path) {
        try {
            zos.putNextEntry(new ZipEntry(name));
            Files.copy(path, zos);
            zos.closeEntry();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void addZipFile(final ZipOutputStream zos, final Path path) {
        if (Files.isRegularFile(path)) {
            addZipEntry(zos, path.getFileName().toString(), path);
        }
    }

    private InputStream createBackup() {
        try {
            final Path temp = Files.createTempFile("bambufarm-backup", ".zip");
            try (final ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
                addZipFile(zos, Path.of(config.maintenanceFile()));
                addZipFile(zos, Path.of(config.historyFile()));
                addZipFile(zos, Path.of(config.queueFile()));
                final Path library = Path.of(config.batchPrint().library());
                if (Files.isDirectory(library)) {
                    try (final java.util.stream.Stream<Path> files = Files.list(library)) {
                        files.filter(Files::isRegularFile)
                                .forEach(p -> addZipEntry(zos, "library/%s".formatted(p.getFileName()), p));
                    }
                }
            }
            return new FileInputStream(temp.toFile()) {
                @Override
                public void close() throws IOException {
                    super.close();
                    Files.deleteIfExists(temp);
                }
            };
        } catch (IOException | UncheckedIOException ex) {
            Log.errorf(ex, "Backup failed: %s", ex.getMessage());
            return InputStream.nullInputStream();
        }
    }

    @Override
    public void updateHeader(final HasComponents component) {
        component.add(new Button("Refresh", new Icon(VaadinIcon.REFRESH), l -> refreshItems()));
        final StreamResource resource = new StreamResource(
                "bambufarm-backup-%s.zip".formatted(LocalDate.now()), this::createBackup);
        final Anchor backup = new Anchor(resource, "");
        backup.getElement().setAttribute("download", true);
        backup.add(new Button("Backup", new Icon(VaadinIcon.DOWNLOAD)));
        component.add(backup);
    }

}
