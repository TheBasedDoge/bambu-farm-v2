package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.ftp.BambuFtp;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.SucceededEvent;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.OptionalParameter;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.StreamResource;
import io.quarkus.logging.Log;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "sdcard", layout = MainLayout.class)
@PageTitle("SD Card")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public final class SdCardView extends PushDiv implements HasUrlParameter<String>, GridHelper<FTPFile>, ViewHelper {

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
    Instance<BambuFtp> clientInstance;
    @Inject
    BambuConfig config;
    @Inject
    SdCardThumbnailService thumbnailService;

    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize maxBodySize;

    private Optional<BambuPrinters.PrinterDetail> _printer = Optional.empty();

    private final ComboBox<BambuPrinters.PrinterDetail> comboBox = new ComboBox<>();
    private final Grid<FTPFile> grid = new MyGrid<>();
    private final TextField path = new TextField("", BambuConst.PATHSEP, l -> runCallable(this::doPath));
    private final Button connect = new Button("Connect", new Icon(VaadinIcon.CONNECT), l -> doConnect());
    private final Button disconnect = new Button("Disconnect", new Icon(VaadinIcon.CLOSE), l -> doDisconnect());
    private final Button cdup = new Button("", new Icon(VaadinIcon.ARROW_BACKWARD), l -> doCDUP());
    private final Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH), l -> runCallable(this::doRefresh));
    private final Button deleteSelected = new Button("Delete Selected", new Icon(VaadinIcon.TRASH), l -> doRemoveSelected());
    private final Button broadcastUploadButton = new Button("Broadcast Upload", new Icon(VaadinIcon.UPLOAD), l -> showBroadcastDialog());
    private final Button columnsButton = new Button("Columns", new Icon(VaadinIcon.GRID_H));
    private static final String JS_COLUMNS_KEY = "bambufarm-sdcard-columns";
    private final Map<String, ColumnToggle> columnToggles = new LinkedHashMap<>();
    private final ProgressBar progressBar = newProgressBar();
    private final MemoryBuffer buffer = new MemoryBuffer();
    private final Upload upload = new Upload(buffer);
    private BambuFtp client;
    private double percentageComplete;
    private long fileSize;
    private UI ui;

    private final NotificationHelper nh = new NotificationHelper() {
    };

    private void showProgressBar(final boolean visible) {
        percentageComplete = 0;
        progressBar.setValue(percentageComplete);
        progressBar.setVisible(visible);
    }

    @Override
    public Grid<FTPFile> getGrid() {
        return grid;
    }

    @Override
    public void setParameter(final BeforeEvent event, @OptionalParameter final String printerName) {
        _printer = printers.getPrinterDetail(printerName);
    }

    private void runInUI(final Command command) {
        ui.access(command);
    }

    private void runCallable(final Callable callable) {
        executor.submit(() -> {
            try {
                callable.run();
            } catch (Exception ex) {
                Log.error(ex.getMessage(), ex);
                if (ex.getCause() != null) {
                    Log.error(ex.getCause().getMessage(), ex.getCause());
                }
                runInUI(() -> nh.showError(ex.getMessage()));
            }
        });
    }

    private void disconnect() {
        if (client == null) {
            return;
        }
        final BambuFtp _client = client;
        client = null;
        if (!_client.isConnected()) {
            return;
        }
        grid.setItems(List.of());
        runCallable(_client::doClose);
    }

    private void setConnectDisconnect(final boolean canConnect) {
        connect.setEnabled(canConnect);
        disconnect.setEnabled(!canConnect);
        path.setEnabled(!canConnect);
        cdup.setEnabled(!canConnect);
        refresh.setEnabled(!canConnect);
        upload.setVisible(!canConnect);
        deleteSelected.setEnabled(false);
    }

    private void buildList(final BambuPrinters.PrinterDetail printer) {
        disconnect();
        client = clientInstance.get().setup(printer, this::bytesTransferred);
        setConnectDisconnect(true);
    }

    private void doConnect() {
        connect.setEnabled(false);
        runCallable(() -> {
            client.doConnect();
            if (!client.doLogin()) {
                runInUI(() -> nh.showError("Login Failed"));
            }
            runInUI(() -> setConnectDisconnect(false));
            doPath();
        });
    }

    private void doDisconnect() {
        disconnect.setEnabled(false);
        grid.setItems(List.of());
        setConnectDisconnect(true);
        runCallable(client::doClose);
    }

    private void doPath() throws IOException {
        final String value = path.getValue();
        if (value == null || value.isEmpty()) {
            runInUI(() -> path.setValue(BambuConst.PATHSEP));
            return;
        }
        if (!client.isConnected()) {
            return;
        }
        if (!client.changeWorkingDirectory(value)) {
            runInUI(() -> nh.showError("Change Directory Failed"));
            return;
        }
        final List<FTPFile> files = Arrays.asList(client.listFiles());
        runInUI(() -> {
            grid.setItems(files);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                nh.showError(client.getReplyString());
            }
        });
    }

    private void doCDUP() {
        final int pos = path.getValue().lastIndexOf(BambuConst.PATHSEP);
        if (pos == -1) {
            path.setValue(BambuConst.PATHSEP);
            return;
        }
        final String value = path.getValue().substring(0, pos).trim();
        path.setValue(value.isEmpty() ? BambuConst.PATHSEP : value);
    }

    private Component buildToolbar() {
        comboBox.setItemLabelGenerator(BambuPrinters.PrinterDetail::name);
        comboBox.setItems(printers.getPrintersDetail().stream().sorted(Comparator.comparing(BambuPrinters.PrinterDetail::name)).toList());
        comboBox.addValueChangeListener(l -> buildList(l.getValue()));
        setConnectDisconnect(true);
        connect.setEnabled(false);
        upload.setAcceptedFileTypes(BambuConst.EXT.toArray(String[]::new));
        upload.addSucceededListener(this::doUpload);
        upload.setMaxFileSize((int) maxBodySize.asLongValue());
        upload.setDropLabel(new Span("Drop file here (max size: %dM)".formatted(maxBodySize.asLongValue() / 1_000_000)));
        upload.addFileRejectedListener(l -> {
            nh.showError(l.getErrorMessage());
        });
        final HorizontalLayout result = new HorizontalLayout(new Span("Printers"), comboBox, connect, disconnect, new Span("Path"),
                path, cdup, refresh, deleteSelected, broadcastUploadButton, columnsButton, upload
        );
        result.setWidthFull();
        result.setAlignItems(Alignment.CENTER);
        result.setMinHeight(80, Unit.PIXELS);
        result.addClassName("toolbar");
        return result;
    }

    private void updateProgressBar() {
        if (!progressBar.isVisible()) {
            return;
        }

        runInUI(() -> progressBar.setValue(percentageComplete));
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        ui = attachEvent.getUI();
        addClassName("sdcard-view");
        configureGrid();
        showProgressBar(false);
        add(buildToolbar(), progressBar, grid);
        _printer.ifPresent(comboBox::setValue);
        createFuture(this::updateProgressBar, config.refreshInterval());
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        disconnect();
    }

    private ComponentRenderer<Icon, FTPFile> getTypeRender() {
        return new ComponentRenderer<>(file -> {
            final VaadinIcon icon;
            if (file.isDirectory()) {
                icon = VaadinIcon.FOLDER;
            } else if (file.isFile()) {
                icon = VaadinIcon.FILE;
            } else if (file.isSymbolicLink()) {
                icon = VaadinIcon.LINK;
            } else {
                icon = VaadinIcon.QUESTION;
            }
            return new Icon(icon);
        });
    }

    private Anchor getDownloadLink(final FTPFile file) {
        final String fileName = file.getName();
        final StreamResource stream = new StreamResource(fileName, () -> {
            try {
                fileSize = file.getSize();
                runInUI(() -> showProgressBar(true));

                client.setFileType(FTP.BINARY_FILE_TYPE);
                try (final InputStream s = client.retrieveFileStream(file.getName())) {
                    return new ByteArrayInputStream(s.readAllBytes());
                    //return new BufferedInputStream(s);
                } finally {
                    if (!client.completePendingCommand()) {
                        Log.error("could not complete pending command");
                    }
                    runInUI(() -> showProgressBar(false));
                }
            } catch (IOException ex) {
                Log.errorf(ex, "Cannot find file: %s - %s", file.getName(), ex.getMessage());
            }
            return null;
        });
        final Anchor anchor = new Anchor();
        anchor.setHref(stream);
        anchor.getElement().setAttribute("download", true);
        anchor.add(new Button(new Icon(VaadinIcon.DOWNLOAD)));
        return anchor;
    }

    private static String formatSize(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024.0);
        } else {
            return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        }
    }

    private void showPreview(final FTPFile file) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(file.getName());
        dialog.setWidth("480px");

        final Span sizeLabel = new Span("Size: " + formatSize(file.getSize()));
        final Span dateLabel = new Span("Date: " + DTF.format(file.getTimestampInstant().atOffset(java.time.ZoneOffset.UTC)));
        final Span loadingLabel = new Span("Loading thumbnail…");
        loadingLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final Image thumbImage = new Image();
        thumbImage.setMaxWidth("440px");
        thumbImage.setMaxHeight("440px");
        thumbImage.setVisible(false);

        final VerticalLayout content = new VerticalLayout(sizeLabel, dateLabel, loadingLabel, thumbImage);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);

        final Button closeBtn = new Button("Close", new Icon(VaadinIcon.CLOSE), l -> dialog.close());
        dialog.getFooter().add(closeBtn);
        dialog.open();

        // Fetch thumbnail on background thread — use the currently selected printer in the combobox
        final BambuPrinters.PrinterDetail printerDetail = comboBox.getValue();
        if (printerDetail == null) {
            loadingLabel.setText("No printer selected.");
            return;
        }
        final String directory = path.getValue();

        final BambuFtp activeClient = client;
        executor.submit(() -> {
            if (activeClient == null || !activeClient.isConnected()) {
                ui.access(() -> {
                    loadingLabel.setText("Not connected — connect to SD card first.");
                    loadingLabel.setVisible(true);
                });
                return;
            }
            final Optional<byte[]> thumb = thumbnailService.getThumbnail(
                    activeClient, printerDetail.name(), directory, file.getName());
            ui.access(() -> {
                loadingLabel.setVisible(false);
                if (thumb.isEmpty()) {
                    loadingLabel.setText("No thumbnail embedded in this file.");
                    loadingLabel.setVisible(true);
                    return;
                }
                final byte[] thumbBytes = thumb.get();
                // Detect format from magic bytes: JPEG starts 0xFF 0xD8, PNG starts 0x89 'P'
                final String mime = thumbBytes.length >= 2 && (thumbBytes[0] & 0xFF) == 0xFF && (thumbBytes[1] & 0xFF) == 0xD8
                        ? "image/jpeg" : "image/png";
                final String b64 = java.util.Base64.getEncoder().encodeToString(thumbBytes);
                thumbImage.setSrc("data:%s;base64,%s".formatted(mime, b64));
                thumbImage.setVisible(true);
            });
        });
    }

    private Component getComponentColumn(final FTPFile file) {
        final HorizontalLayout result = new HorizontalLayout();
        if (file.isDirectory()) {
            result.add(new Button(new Icon(VaadinIcon.FOLDER_OPEN), l -> doDoubleClick(file)));
            result.add(new Button(new Icon(VaadinIcon.FOLDER_REMOVE), l -> doRemoveFile(file)));
        }
        if (file.isFile()) {
            if (BambuConst.EXT.stream().anyMatch(ext -> file.getName().endsWith(ext))) {
                result.add(new Button(new Icon(VaadinIcon.PRINT), l -> doPrintFile(file)));
            }
            final String lowerName = file.getName().toLowerCase();
            if (lowerName.endsWith(".gcode") || lowerName.endsWith(".3mf")) {
                final Button preview = new Button(new Icon(VaadinIcon.EYE));
                preview.setTooltipText("Preview thumbnail");
                preview.addClickListener(l -> showPreview(file));
                result.add(preview);
            }
            result.add(getDownloadLink(file));
            result.add(new Button(new Icon(VaadinIcon.FILE_REMOVE), l -> doRemoveFile(file)));
        }
        return result;
    }

    private void configureGrid() {
        final Grid.Column<FTPFile> colType = setupColumn("Type", getTypeRender())
                .setFlexGrow(0).setWidth("90px");
        final Grid.Column<FTPFile> colName = setupColumn("Name", f -> f.getName())
                .setSortable(true).setComparator(Comparator.comparing(FTPFile::getName, String.CASE_INSENSITIVE_ORDER))
                .setFlexGrow(3)
                .setTooltipGenerator(FTPFile::getName);
        final Grid.Column<FTPFile> colSize = setupColumn("Size", f -> f.getSize())
                .setSortable(true).setComparator(FTPFile::getSize);
        final Grid.Column<FTPFile> coldDate
                = setupColumn("Date", f -> DTF.format(f.getTimestampInstant().atOffset(ZoneOffset.UTC)))
                        .setSortable(true).setComparator(FTPFile::getTimestampInstant);

        final Grid.Column<FTPFile> colActions = grid.addComponentColumn(this::getComponentColumn)
                .setHeader("Actions").setFlexGrow(2);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.addItemDoubleClickListener(l -> doDoubleClick(l.getItem()));
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addSelectionListener(l -> deleteSelected.setEnabled(!l.getAllSelectedItems().isEmpty()));
        grid.sort(GridSortOrder.desc(coldDate).build());

        final ContextMenu columnsMenu = new ContextMenu(columnsButton);
        columnsMenu.setOpenOnClick(true);
        addColumnToggle(columnsMenu, "Type", colType);
        addColumnToggle(columnsMenu, "Name", colName);
        addColumnToggle(columnsMenu, "Size", colSize);
        addColumnToggle(columnsMenu, "Date", coldDate);
        addColumnToggle(columnsMenu, "Actions", colActions);
        restoreHiddenColumns();
    }

    private void addColumnToggle(final ContextMenu menu, final String name, final Grid.Column<FTPFile> column) {
        final MenuItem item = menu.addItem(name, l -> {
            column.setVisible(l.getSource().isChecked());
            saveHiddenColumns();
        });
        item.setCheckable(true);
        item.setChecked(true);
        item.setKeepOpen(true);
        columnToggles.put(name, new ColumnToggle(column, item));
    }

    private void saveHiddenColumns() {
        final String hidden = columnToggles.entrySet().stream()
                .filter(e -> !e.getValue().column().isVisible())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(","));
        getElement().executeJs("localStorage.setItem($0, $1)", JS_COLUMNS_KEY, hidden);
    }

    private void restoreHiddenColumns() {
        getElement().executeJs("return localStorage.getItem($0) || ''", JS_COLUMNS_KEY)
                .then(String.class, saved -> {
                    if (saved == null || saved.isBlank()) {
                        return;
                    }
                    for (final String name : saved.split(",")) {
                        Optional.ofNullable(columnToggles.get(name)).ifPresent(toggle -> {
                            toggle.column().setVisible(false);
                            toggle.item().setChecked(false);
                        });
                    }
                });
    }

    private record ColumnToggle(Grid.Column<FTPFile> column, MenuItem item) {

    }

    private String buildFileName(final String fileName) {
        final StringBuilder sb = new StringBuilder(path.getValue());
        if (!path.getValue().endsWith(BambuConst.PATHSEP)) {
            sb.append(BambuConst.PATHSEP);
        }
        sb.append(fileName.startsWith(BambuConst.PATHSEP) ? fileName.substring(1) : fileName);
        return sb.toString();
    }

    private void doDoubleClick(final FTPFile item) {
        if (!item.isDirectory()) {
            return;
        }
        path.setValue(buildFileName(item.getName()));
    }

    private void doRefresh() throws IOException {
        doPath();
    }

    private void doUpload(final SucceededEvent event) {
        fileSize = event.getContentLength();
        showProgressBar(true);

        final InputStream inputStream = buffer.getInputStream();
        nh.showNotification("Uploading to Printer");
        runCallable(() -> {
            client.doUpload(event.getFileName(), inputStream);
            runInUI(() -> {
                showProgressBar(false);
                nh.showNotification("Uploaded: %s".formatted(event.getFileName()));
            });
            doRefresh();
        });
    }

    private void doRemoveFile(final FTPFile file) {
        YesNoCancelDialog.show("Confirm to delete: %s".formatted(file.getName()), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            runCallable(() -> {
                final boolean ok;
                if (file.isDirectory()) {
                    ok = client.removeDirectory(file.getName());
                } else if (file.isFile()) {
                    ok = client.deleteFile(file.getName());
                } else {
                    ok = true;
                }

                if (!ok) {
                    runInUI(() -> nh.showError("Delete Failed"));
                } else {
                    final String printerName = comboBox.getValue() != null ? comboBox.getValue().name() : "";
                    thumbnailService.evict(printerName, path.getValue(), file.getName());
                }
                doRefresh();
            });
        });
    }

    private void doRemoveSelected() {
        final List<FTPFile> files = grid.getSelectedItems().stream()
                .filter(f -> f.isFile() || f.isDirectory())
                .toList();
        if (files.isEmpty()) {
            return;
        }
        final String names = files.stream().map(FTPFile::getName).collect(java.util.stream.Collectors.joining("\n"));
        YesNoCancelDialog.show("Confirm to delete %d item(s):\n\n%s".formatted(files.size(), names), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            runCallable(() -> {
                final List<String> failed = new java.util.ArrayList<>();
                for (final FTPFile file : files) {
                    final boolean ok;
                    if (file.isDirectory()) {
                        ok = client.removeDirectory(file.getName());
                    } else {
                        ok = client.deleteFile(file.getName());
                    }
                    if (!ok) {
                        failed.add(file.getName());
                    }
                }
                if (!failed.isEmpty()) {
                    runInUI(() -> nh.showError("Delete Failed: %s".formatted(String.join(", ", failed))));
                }
                doRefresh();
            });
        });
    }

    private void doPrintFile(final FTPFile file) {
        final IntegerField plateId = new IntegerField("Plate Id");
        plateId.setMin(1);
        plateId.setMax(20);
        plateId.setStepButtonsVisible(true);
        plateId.setValue(1);
        final Checkbox useAMS = new Checkbox("Use AMS", comboBox.getValue().config().useAms());
        final Checkbox timelapse = new Checkbox("Timelapse", comboBox.getValue().config().timelapse());
        final Checkbox bedLevelling = new Checkbox("Bed Levelling", comboBox.getValue().config().bedLevelling());
        final Checkbox flowCalibration = new Checkbox("Flow Calibration", comboBox.getValue().config().flowCalibration());
        final Checkbox vibrationCalibration = new Checkbox("Vibration Calibration", comboBox.getValue().config().vibrationCalibration());
        final ComboBox<Integer> amsSlot = new ComboBox<>("AMS Slot Override");
        amsSlot.setItems(AmsSlotSupport.ITEMS);
        amsSlot.setItemLabelGenerator(AmsSlotSupport::label);
        amsSlot.setClearButtonVisible(true);
        amsSlot.setPlaceholder("Use AMS checkbox above");
        amsSlot.setHelperText(
                "Optional - forces every filament slot in this file onto one physical tray (or the external "
                        + "spool), overriding the Use AMS checkbox");

        final String fileName = buildFileName(file.getName());
        final boolean is3mf = fileName.endsWith(BambuConst.FILE_3MF);

        final List<Component> list;
        if (is3mf) {
            list = List.of(plateId, useAMS, amsSlot, timelapse, bedLevelling, flowCalibration, vibrationCalibration);
        } else {
            list = List.of(useAMS, timelapse, bedLevelling, flowCalibration, vibrationCalibration);
        }

        YesNoCancelDialog.show(list, "Confirm to print: %s".formatted(file.getName()), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            if (fileName.endsWith(BambuConst.FILE_GCODE)) {
                comboBox.getValue().printer().commandPrintGCodeFile(fileName);
            } else if (is3mf) {
                final Integer slot = amsSlot.getValue();
                final List<Integer> amsMapping = slot == null ? List.of() : List.of(slot);
                final boolean effectiveUseAms = slot != null ? slot != BambuConst.AMS_TRAY_VIRTUAL : useAMS.getValue();
                comboBox.getValue().printer().commandPrintProjectFile(
                        new BambuPrinter.CommandPPF(
                                fileName, plateId.getValue(),
                                effectiveUseAms, timelapse.getValue(), bedLevelling.getValue(),
                                flowCalibration.getValue(), vibrationCalibration.getValue(),
                                amsMapping));
            } else {
                nh.showError("Unknown File: %s".formatted(fileName));
            }
        });
    }

    private void showBroadcastDialog() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Broadcast Upload to All Printers");
        dialog.setWidth("560px");

        // Destination path on each SD card
        final TextField destPath = new TextField("Destination path on SD card");
        destPath.setWidthFull();
        // Pre-fill from current connection if available
        destPath.setValue(client != null && client.isConnected() && !path.getValue().isEmpty()
                ? path.getValue() : BambuConst.PATHSEP);
        destPath.setHelperText("e.g. /_S2000 — directory is created automatically if it doesn't exist");
        destPath.setPrefixComponent(new Icon(VaadinIcon.FOLDER));

        // File picker
        final MemoryBuffer broadcastBuffer = new MemoryBuffer();
        final Upload broadcastUpload = new Upload(broadcastBuffer);
        broadcastUpload.setAcceptedFileTypes(BambuConst.EXT.toArray(String[]::new));
        broadcastUpload.setMaxFileSize((int) maxBodySize.asLongValue());
        broadcastUpload.setDropLabel(new Span("Drop .3mf or .gcode file here"));
        broadcastUpload.setWidthFull();
        broadcastUpload.addFileRejectedListener(e -> nh.showError(e.getErrorMessage()));

        // Printer selection — all printers, all checked by default
        final List<BambuPrinters.PrinterDetail> allPrinters = printers.getPrintersDetail().stream()
                .sorted(Comparator.comparing(BambuPrinters.PrinterDetail::name)).toList();
        final Map<BambuPrinters.PrinterDetail, Checkbox> printerChecks = new LinkedHashMap<>();
        final VerticalLayout printerList = new VerticalLayout();
        printerList.setPadding(false);
        printerList.setSpacing(false);
        printerList.add(new Span("Upload to:"));
        for (final BambuPrinters.PrinterDetail pd : allPrinters) {
            final Checkbox cb = new Checkbox(pd.name(), true);
            printerChecks.put(pd, cb);
            printerList.add(cb);
        }

        // Per-printer status area (hidden until upload starts)
        final Map<String, Span> statusSpans = new LinkedHashMap<>();
        final VerticalLayout statusArea = new VerticalLayout();
        statusArea.setPadding(false);
        statusArea.setSpacing(false);
        statusArea.setVisible(false);

        // Start button — enabled only after a file is buffered
        final Button startBtn = new Button("Upload to Selected Printers", new Icon(VaadinIcon.UPLOAD));
        startBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        startBtn.setEnabled(false);
        startBtn.setWidthFull();

        // Hold the file in memory so each printer gets its own fresh ByteArrayInputStream
        final String[] pendingFileName = {null};
        final byte[][] pendingBytes = {null};

        broadcastUpload.addSucceededListener(e -> {
            try {
                pendingBytes[0] = broadcastBuffer.getInputStream().readAllBytes();
                pendingFileName[0] = e.getFileName();
                startBtn.setEnabled(true);
            } catch (IOException ex) {
                nh.showError("Failed to buffer file: " + ex.getMessage());
            }
        });

        startBtn.addClickListener(e -> {
            final String fileName = pendingFileName[0];
            final byte[] bytes = pendingBytes[0];
            if (fileName == null || bytes == null) {
                return;
            }

            final List<BambuPrinters.PrinterDetail> selected = printerChecks.entrySet().stream()
                    .filter(en -> en.getValue().getValue())
                    .map(Map.Entry::getKey)
                    .toList();
            if (selected.isEmpty()) {
                nh.showError("Select at least one printer");
                return;
            }

            // Normalise destination path
            String rawDest = destPath.getValue().trim();
            if (rawDest.isEmpty()) {
                rawDest = BambuConst.PATHSEP;
            }
            if (!rawDest.startsWith(BambuConst.PATHSEP)) {
                rawDest = BambuConst.PATHSEP + rawDest;
            }
            final String finalDest = rawDest;

            // Build status rows
            statusSpans.clear();
            statusArea.removeAll();
            statusArea.add(new Span("Upload progress:"));
            for (final BambuPrinters.PrinterDetail pd : selected) {
                final Span s = new Span("⏳ " + pd.name() + ": Queued");
                statusSpans.put(pd.name(), s);
                statusArea.add(s);
            }
            statusArea.setVisible(true);
            startBtn.setEnabled(false);

            // Fire off one FTP connection per printer in parallel
            for (final BambuPrinters.PrinterDetail pd : selected) {
                final Span statusSpan = statusSpans.get(pd.name());
                executor.submit(() -> {
                    final BambuFtp ftpClient = clientInstance.get().setup(pd, (total, b, stream) -> {
                    });
                    try {
                        ui.access(() -> statusSpan.setText("🔄 " + pd.name() + ": Connecting…"));
                        ftpClient.doConnect();
                        if (!ftpClient.doLogin()) {
                            ui.access(() -> statusSpan.setText("✗ " + pd.name() + ": Login failed"));
                            return;
                        }
                        // Navigate to destination, creating it if necessary
                        if (!ftpClient.changeWorkingDirectory(finalDest)) {
                            ftpClient.makeDirectory(finalDest);
                            if (!ftpClient.changeWorkingDirectory(finalDest)) {
                                ui.access(() -> statusSpan.setText("✗ " + pd.name() + ": Cannot access " + finalDest));
                                return;
                            }
                        }
                        ui.access(() -> statusSpan.setText("⬆ " + pd.name() + ": Uploading " + fileName + "…"));
                        final boolean ok = ftpClient.doUpload(fileName, new ByteArrayInputStream(bytes));
                        ui.access(() -> statusSpan.setText(ok
                                ? "✓ " + pd.name() + ": Done"
                                : "✗ " + pd.name() + ": Upload returned failure"));
                    } catch (Exception ex) {
                        Log.errorf(ex, "Broadcast upload to %s failed: %s", pd.name(), ex.getMessage());
                        final String msg = ex.getMessage();
                        ui.access(() -> statusSpan.setText("✗ " + pd.name() + ": " + (msg != null ? msg : "Unknown error")));
                    } finally {
                        try {
                            ftpClient.doClose();
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });

        final Button closeBtn = new Button("Close", new Icon(VaadinIcon.CLOSE), e -> dialog.close());

        final VerticalLayout content = new VerticalLayout(destPath, broadcastUpload, printerList, startBtn, statusArea);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidth("100%");
        dialog.add(content);
        dialog.getFooter().add(closeBtn);
        dialog.open();
    }

    private void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
        percentageComplete = 100.0 * totalBytesTransferred / fileSize;
    }

    @FunctionalInterface
    private interface Callable {

        /**
         * Runs this operation.
         */
        void run() throws Exception;
    }

    private class MyGrid<T> extends Grid<T> {

        private Optional<UI> ui = Optional.empty();

        @Override
        public Optional<UI> getUI() {
            return ui.or(super::getUI);
        }

        @Override
        protected void onAttach(final AttachEvent attachEvent) {
            super.onAttach(attachEvent);
            ui = Optional.of(attachEvent.getUI());
        }

    }

}
