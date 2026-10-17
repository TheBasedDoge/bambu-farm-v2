package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinterConsumer;
import com.tfyre.bambu.printer.BambuPrinterException;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.MaintenanceService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
 * Settings → Printer connections: whether the app's connection to each printer is running, when it last heard
 * from it, enable / disable / send G-code, and the data backup download. This was the Maintenance page until
 * 2026-10-13, which kept the actual maintenance tasks behind a wrench in its last column; Maintenance is now
 * about the tasks.
 */
@Route(value = "printer-connections", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Printer connections")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public class PrinterConnectionsView extends VerticalLayout implements NotificationHelper, GridHelper<BambuPrinters.PrinterDetail> {

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
    private boolean built;

    @Override
    public Grid<BambuPrinters.PrinterDetail> getGrid() {
        return grid;
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            setSizeFull();
            add(SettingsTabs.create());
            final Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH), l -> refreshItems());
            final StreamResource resource = new StreamResource(
                    "bambufarm-backup-%s.zip".formatted(LocalDate.now()), this::createBackup);
            final Anchor backup = new Anchor(resource, "");
            backup.getElement().setAttribute("download", true);
            backup.add(new Button("Download backup", new Icon(VaadinIcon.DOWNLOAD)));
            final HorizontalLayout top = new HorizontalLayout(new H3("Printer connections"), refresh, backup);
            top.setAlignItems(FlexComponent.Alignment.BASELINE);
            top.getStyle().set("flex-wrap", "wrap");
            final Span hint = new Span("The app's link to each printer. Disable one to stop the app talking to it "
                    + "(for a firmware update, say); enable to reconnect. The nightly backup of the app's data is "
                    + "written by itself - Download backup is for taking a copy elsewhere.");
            hint.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
            configureGrid();
            add(top, hint, grid);
        }
        refreshItems();
    }

    private Button newButton(final BambuPrinters.PrinterDetail pd, final String action, final VaadinIcon icon,
            final BambuPrinterConsumer<String> consumer) {
        final Button result = new Button(action, new Icon(icon), l -> {
            final Optional<UI> ui = getUI();
            executor.submit(() -> {
                try {
                    consumer.accept(pd.name());
                    ui.ifPresent(u -> u.access(this::refreshItems));
                } catch (BambuPrinterException ex) {
                    Log.error(ex.getMessage(), ex);
                    ui.ifPresent(u -> u.access(() -> {
                        showError(ex.getMessage());
                        refreshItems();
                    }));
                }
            });
        });
        return result;
    }

    private void refreshItems() {
        grid.setItems(printers.getPrintersDetail());
    }

    private <T> Comparator<BambuPrinters.PrinterDetail> getODTComparator(
            final Function<BambuPrinter, Optional<T>> function1,
            final Function<T, OffsetDateTime> function2) {
        return Comparator.comparing(pd -> function1.apply(pd.printer())
                .map(function2)
                .map(OffsetDateTime::toEpochSecond)
                .orElse(0L));
    }

    private void configureGrid() {
        final Grid.Column<BambuPrinters.PrinterDetail> colName = setupColumn("Name", pd -> pd.printer().getName());
        setupColumnCheckBox("Running", BambuPrinters.PrinterDetail::isRunning);
        setupColumn("Print hours", pd -> "%.1f".formatted(maintenanceService.getPrintHours(pd.name())))
                .setSortable(true).setComparator(Comparator.<BambuPrinters.PrinterDetail>comparingDouble(pd -> maintenanceService.getPrintHours(pd.name())));
        setupColumn("Last status", pd -> pd.printer().getStatus().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getStatus, BambuPrinter.Message::lastUpdated));
        setupColumn("Last full status", pd -> pd.printer().getFullStatus().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getFullStatus, BambuPrinter.Message::lastUpdated));
        setupColumn("Last camera frame", pd -> pd.printer().getThumbnail().map(m -> DTF.format(m.lastUpdated())).orElse("--"))
                .setSortable(true).setComparator(getODTComparator(BambuPrinter::getThumbnail, BambuPrinter.Thumbnail::lastUpdated));
        grid.addComponentColumn(v -> {
            final Button gcode = new Button("G-code", new Icon(VaadinIcon.COG), l -> GCodeDialog.show(v.printer()));
            final HorizontalLayout actions = new HorizontalLayout(
                    newButton(v, "Enable", VaadinIcon.PLAY, printers::startPrinter),
                    newButton(v, "Disable", VaadinIcon.STOP, printers::stopPrinter),
                    gcode);
            actions.setSpacing(true);
            return actions;
        }).setAutoWidth(true);
        grid.setColumnReorderingAllowed(true);
        GridLayoutMemory.remember(grid, "printer-connections");
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.sort(GridSortOrder.asc(colName).build());
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
}
