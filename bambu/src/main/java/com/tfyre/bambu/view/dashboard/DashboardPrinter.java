package com.tfyre.bambu.view.dashboard;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.model.AmsSingle;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.model.Print;
import com.tfyre.bambu.model.Tray;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuConst.Speed;
import com.tfyre.bambu.printer.BambuErrors;
import com.tfyre.bambu.printer.Filament;
import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.MaintenanceService;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import com.tfyre.bambu.printer.PrintQueueService;
import com.tfyre.bambu.printer.AmsDryService;
import com.tfyre.bambu.printer.TasmotaService;
import com.tfyre.bambu.view.FilamentView;
import com.tfyre.bambu.view.GCodeDialog;
import com.tfyre.bambu.view.LogsView;
import com.tfyre.bambu.view.MaintenanceView;
import com.tfyre.bambu.view.PrinterView;
import com.tfyre.bambu.view.SdCardView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.tfyre.bambu.view.NotificationHelper;
import com.tfyre.bambu.view.ViewHelper;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import io.quarkus.logging.Log;
import java.util.ArrayList;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Dependent
public final class DashboardPrinter implements NotificationHelper, ViewHelper {

    //DateTimeFormatter.ISO_DATE_TIME;
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

    private final ProgressBar progressBar;
    private final Span progressFile = newSpan();
    private final Span progressTime = newSpan();
    private final Span progressLayer = newSpan();
    private final Image monitorLamp = new Image(Images.MONITOR_LAMP_OFF.getImage(), "Monitor Lamp");
    private final Span monitorLampText = newSpan();
    private final Image bedImage = new Image(Images.MONITOR_BED_TEMP.getImage(), "Bed");
    private final Span bed = newSpan();
    private final Span bedTarget = newSpan();
    private final Image nozzleImage = new Image(Images.MONITOR_NOZZLE_TEMP.getImage(), "Nozzle");
    private final Span nozzle = newSpan();
    private final Span nozzleTarget = newSpan();
    // Second extruder (H2D only)
    private final Image nozzle2Image = new Image(Images.MONITOR_NOZZLE_TEMP.getImage(), "Nozzle 2");
    private final Span nozzle2 = newSpan();
    private final Span nozzle2Target = newSpan();
    private double temperatureNozzle2 = 0;
    private final Image frameImage = new Image(Images.MONITOR_FRAME_TEMP.getImage(), "Frame");
    private final Span frame = newSpan();
    private final Image speedImage = new Image(Images.MONITOR_SPEED.getImage(), "Speed");
    private final Span speed = newSpan();
    private final Image thumbnail = new Image();
    private final Span thumbnailUpdated = newSpan();
    private final Span printerStatus = newSpan();
    private final Div printerName = new Div();
    private final Button printAgain = new Button();
    private String printAgainFile = "";
    private final Button maintenanceDue = new Button(new Icon(VaadinIcon.WRENCH));
    private String maintenanceDueText = "";
    private final Button startNext = new Button();
    private String startNextText = "";
    private final Button checkBed = new Button();
    private final Button failureBadge = new Button(new Icon(VaadinIcon.EXCLAMATION_CIRCLE));
    private final Button hmsBadge = new Button(new Icon(VaadinIcon.WARNING));
    private String wifiSignal = "";
    private final Span fanCooling = newSpan();   // part-cooling fan %
    private final Span fanAux = newSpan();        // aux/chamber fan %
    private String ipAddress = "";
    private String buildPlate = "";
    private final Span aiStatusChip = new Span();
    private final Span aiCheckDot = new Span();  // Span avoids the .name div { width:100% } rule
    private String lastAiStatusText = "";
    private boolean failureActive = false;
    private BambuConst.GCodeState lastAiState = null;
    private Div nameDiv;
    private String printerInfo = "";
    private String thumbnailId;
    private boolean built;
    private boolean processFull = true;
    private final boolean isAdmin;
    private int lastError = 0;
    private double temperatureNozzle = 0;
    private double temperatureBed = 0;
    private String nozzleDiameter = "";
    private String nozzleType = "";
    private String nozzle2Diameter = "";
    private String nozzle2Type = "";

    private final Map<String, AmsHeader> amsHeaders = new HashMap<>();
    private final Map<String, AmsFilament> amsFilaments = new HashMap<>();
    private final Map<Integer, Button> amsDryButtons = new HashMap<>();
    private BambuConst.GCodeState gcodeState = BambuConst.GCodeState.IDLE;
    // Sticky last-known active AMS/spool tray id - Bambu's push_status is frequently a partial delta that omits
    // ams.tray_now when it hasn't changed, so treating "missing from this message" as "unknown" would flicker the
    // highlight off on every such tick. Only a fresh tray_now or going idle should update this.
    private int lastKnownActiveTrayId = -1;

    private Component thumbnailOrIframe;
    private BambuPrinter printer;
    private boolean fromDashboard;

    @Inject
    BambuConfig config;
    @Inject
    MaintenanceService maintenanceService;
    @Inject
    PrintQueueService queueService;
    @Inject
    TasmotaService tasmotaService;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintAiService aiService;
    @Inject
    AmsDryService amsDryService;
    @Inject
    org.eclipse.microprofile.context.ManagedExecutor executor;

    public DashboardPrinter() {
        progressBar = newProgressBar();
        isAdmin = SecurityUtils.userHasAccess(SystemRoles.ROLE_ADMIN);
    }

    private Span newSpan() {
        return new Span("---");
    }

    private void setTemperature(final Span span, final double value) {
        span.setText("%.2fºC".formatted(value));
    }

    private Images getHumidityImage(final String id) {
        if ("2".equals(id)) {
            return Images.AMS_HUMIDITY_1;
        }
        if ("3".equals(id)) {
            return Images.AMS_HUMIDITY_2;
        }
        if ("4".equals(id)) {
            return Images.AMS_HUMIDITY_3;
        }
        if ("5".equals(id)) {
            return Images.AMS_HUMIDITY_4;
        }
        return Images.AMS_HUMIDITY_0;
    }

    /**
     * The raw tray id the printer is currently feeding filament from, straight off {@code ams.tray_now} - for a
     * real AMS tray this is already encoded as {@code amsId*4+trayId} by the firmware, and for the external spool
     * it's the literal {@link BambuConst#AMS_TRAY_VIRTUAL}/{@link BambuConst#AMS_TRAY_UNLOAD} sentinel (single vs.
     * H2D left/right nozzle). Returns -1 when idle or unknown, which never matches any real tray id.
     */
    private int activeAmsTrayId(final com.tfyre.bambu.model.Ams ams) {
        if (gcodeState.isIdle()) {
            lastKnownActiveTrayId = -1;
            return -1;
        }
        // Delta push_status messages often carry the ams/tray list (e.g. for a temp/humidity change) without
        // tray_now, since it hasn't changed since the last report - only refresh our sticky value when this
        // particular message actually included it, otherwise keep showing the last one we saw.
        if (ams.hasTrayNow()) {
            lastKnownActiveTrayId = parseInt(printer.getName(), ams.getTrayNow(), -1);
        }
        return lastKnownActiveTrayId;
    }

    private void processAms(final com.tfyre.bambu.model.Ams ams) {
        final int amsTrayId = activeAmsTrayId(ams);
        ams.getAmsList().forEach(single -> {
            final int amsId = getAmsId(single);
            Optional.ofNullable(amsHeaders.get(getAmsHeaderId(amsId))).ifPresent(header -> {
                final double amsTemp = parseDouble(printer.getName(), single.getTemp(), 0);
                setTemperature(header.temperature(), amsTemp);
                header.humidity().setSrc(getHumidityImage(single.getHumidity()).getImage());

                // Drying indicator: check service-tracked session OR heuristic (temp > 40°C while idle)
                final Optional<AmsDryService.DryingSession> session = amsDryService.getActiveDrying(printer.getName(), amsId);
                final boolean isDryingHeuristic = gcodeState.isIdle() && amsTemp > 40.0;
                final boolean isDrying = session.isPresent() || isDryingHeuristic;
                header.dryingBadge().setVisible(isDrying);
                if (isDrying) {
                    session.ifPresentOrElse(
                            s -> header.dryingBadge().setText("🔥 Drying (%d min left)".formatted(s.remainingMinutes())),
                            () -> header.dryingBadge().setText("🔥 Drying"));
                }
            });

            single.getTrayList().forEach(tray -> {
                final int trayId = getTrayId(tray);
                Optional.ofNullable(amsFilaments.get(getFilamentTrayKey(amsId, trayId))).ifPresent(filament -> {
                    if (!tray.hasTrayInfoIdx()) {
                        filament.type().setText("Empty");
                        return;
                    }
                    filament.type().setText(Filament.getFilamentDescription(tray.getTrayInfoIdx(), config.dashboard().filamentFullName()));
                    filament.color().getStyle().setBackgroundColor("#%s".formatted(tray.getTrayColor()));
                    setActive(filament.div(), amsTrayId == filament.amsTrayId());
                });
            });
        });
    }

    /**
     * @param activeTrayId the raw tray id currently being fed to the hotend (see {@link #activeAmsTrayId}), or -1
     *                     if unknown/idle - pass -1 when the caller can't tell whether this particular external
     *                     spool slot is the one actually in use (e.g. H2D with no AMS attached, where both nozzle
     *                     slots are equally plausible and we'd rather show neither than guess wrong).
     */
    private void processVtTray(final Tray tray, final int activeTrayId) {
        final int trayId = getTrayId(tray);
        Optional.ofNullable(amsHeaders.get(getTrayKey(trayId))).ifPresent(header -> {
            setTemperature(header.temperature(), parseDouble(printer.getName(), tray.getTrayTemp(), 0));
        });
        Optional.ofNullable(amsFilaments.get(getTrayKey(trayId))).ifPresent(filament -> {
            if (tray.hasTrayInfoIdx() && !tray.getTrayInfoIdx().isBlank()) {
                filament.type().setText(Filament.getFilamentDescription(tray.getTrayInfoIdx(), config.dashboard().filamentFullName()));
            }
            if (tray.hasTrayColor() && !tray.getTrayColor().isBlank()) {
                filament.color().getStyle().setBackgroundColor("#%s".formatted(tray.getTrayColor()));
            }
            setActive(filament.div(), activeTrayId == trayId);
        });
    }

    /**
     * Only touches the "active" class (and by extension restarts its CSS pulse animation) when the state actually
     * changed. Unconditionally removing-then-re-adding the class on every telemetry update - which can arrive
     * several times a second - kept restarting the animation, and after enough restarts some browsers would
     * silently stop animating the element until the page was reloaded.
     */
    private static void setActive(final Div div, final boolean active) {
        if (active == div.hasClassName("active")) {
            return;
        }
        if (active) {
            div.addClassName("active");
        } else {
            div.removeClassName("active");
        }
    }

    /** Clears the active-tray highlight from every AMS/spool slot on this card and resets the sticky tray id. */
    private void clearActiveTrayHighlight() {
        lastKnownActiveTrayId = -1;
        amsFilaments.values().forEach(filament -> setActive(filament.div(), false));
    }

    private void processPrint(final BambuPrinter.Message message, final Print print) {
        if (gcodeState.isIdle()) {
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            progressFile.setText(thumbnailId);
            progressFile.setText("");
            progressTime.setText("--");
            progressLayer.setText("");
        } else {
            //Percetage
            if (print.hasMcPercent()) {
                progressBar.setIndeterminate(false);
                progressBar.setValue(Math.min(print.getMcPercent(), 100));
            }

            //FileName
            if (print.hasSubtaskName()) {
                progressFile.setText(print.getSubtaskName());
            }

            //Time
            if (print.hasMcRemainingTime()) {
                progressTime.setText("%s remaining".formatted(formatTime(Duration.ofMinutes(print.getMcRemainingTime()))));
            }

            //Layers
            if (print.hasLayerNum()) {
                progressLayer.setText("Layer %d / %d".formatted(print.getLayerNum(), printer.getTotalLayerNum()));
            }
        }

        //Bed & Target Temperature
        if (print.hasBedTemper()) {
            setTemperature(bed, print.getBedTemper());
            bedImage.setSrc(print.getBedTemper() > 0.0 ? Images.MONITOR_BED_TEMP_ACTIVE.getImage() : Images.MONITOR_BED_TEMP.getImage());
        }
        if (print.hasBedTargetTemper()) {
            temperatureBed = print.getBedTargetTemper();
            setTemperature(bedTarget, temperatureBed);
        }

        //Nozzle & Target Temperature
        if (print.hasNozzleTemper()) {
            setTemperature(nozzle, print.getNozzleTemper());
            nozzleImage.setSrc(print.getNozzleTemper() > 0.0 ? Images.MONITOR_NOZZLE_TEMP_ACTIVE.getImage() : Images.MONITOR_NOZZLE_TEMP.getImage());
        }
        if (print.hasNozzleTargetTemper()) {
            temperatureNozzle = print.getNozzleTargetTemper();
            setTemperature(nozzleTarget, temperatureNozzle);
        }

        // H2D: device.extruder.info[] overrides scalar nozzle fields.
        // Each entry: temp & 0xFFFF = current °C, (temp >> 16) & 0xFFFF = target °C.
        if (print.hasDevice() && print.getDevice().hasExtruder()) {
            for (final var entry : print.getDevice().getExtruder().getInfoList()) {
                final int current = (int) (entry.getTemp() & 0xFFFFL);
                final int target  = (int) ((entry.getTemp() >> 16) & 0xFFFFL);
                if (entry.getId() == 0) {
                    setTemperature(nozzle, current);
                    nozzleImage.setSrc(current > 0 ? Images.MONITOR_NOZZLE_TEMP_ACTIVE.getImage() : Images.MONITOR_NOZZLE_TEMP.getImage());
                    temperatureNozzle = target;
                    setTemperature(nozzleTarget, temperatureNozzle);
                } else if (entry.getId() == 1) {
                    setTemperature(nozzle2, current);
                    nozzle2Image.setSrc(current > 0 ? Images.MONITOR_NOZZLE_TEMP_ACTIVE.getImage() : Images.MONITOR_NOZZLE_TEMP.getImage());
                    temperatureNozzle2 = target;
                    setTemperature(nozzle2Target, temperatureNozzle2);
                }
            }
        } else {
            // Single-nozzle scalar fallback (all non-H2D printers)
            if (print.hasNozzle2Temper()) {
                setTemperature(nozzle2, print.getNozzle2Temper());
                nozzle2Image.setSrc(print.getNozzle2Temper() > 0.0 ? Images.MONITOR_NOZZLE_TEMP_ACTIVE.getImage() : Images.MONITOR_NOZZLE_TEMP.getImage());
            }
            if (print.hasNozzle2TargetTemper()) {
                temperatureNozzle2 = print.getNozzle2TargetTemper();
                setTemperature(nozzle2Target, temperatureNozzle2);
            }
        }

        // Nozzle diameter / type — scalar fields for single-nozzle printers
        if (print.hasNozzleDiameter() && !print.getNozzleDiameter().isBlank()) {
            nozzleDiameter = print.getNozzleDiameter();
        }
        if (print.hasNozzleType() && !print.getNozzleType().isBlank()) {
            nozzleType = print.getNozzleType();
        }
        if (print.hasNozzle2Diameter() && !print.getNozzle2Diameter().isBlank()) {
            nozzle2Diameter = print.getNozzle2Diameter();
        }
        if (print.hasNozzle2Type() && !print.getNozzle2Type().isBlank()) {
            nozzle2Type = print.getNozzle2Type();
        }

        // H2D: device.nozzle.info[] provides per-nozzle diameter and type
        if (print.hasDevice() && print.getDevice().hasNozzle()) {
            for (final var entry : print.getDevice().getNozzle().getInfoList()) {
                if (entry.getId() == 0) {
                    if (!entry.getDiameter().isBlank()) nozzleDiameter = entry.getDiameter();
                    if (!entry.getType().isBlank()) nozzleType = entry.getType();
                } else if (entry.getId() == 1) {
                    if (!entry.getDiameter().isBlank()) nozzle2Diameter = entry.getDiameter();
                    if (!entry.getType().isBlank()) nozzle2Type = entry.getType();
                }
            }
        }

        //Frame/Chamber Temperature
        if (print.hasDevice() && print.getDevice().hasCtc() && print.getDevice().getCtc().hasInfo()) {
            // H2D: device.ctc.info.temp (packed int: low16 = current, high16 = target)
            setTemperature(frame, (int) (print.getDevice().getCtc().getInfo().getTemp() & 0xFFFFL));
        } else if (print.hasChamberTemper()) {
            setTemperature(frame, print.getChamberTemper());
        }

        //Speed
        if (print.hasSpdLvl()) {
            speed.setText(Speed.fromSpeed(print.getSpdLvl()).getDescription());
        }

        // WiFi signal
        if (print.hasWifiSignal()) {
            wifiSignal = print.getWifiSignal();
        }

        // Fan speeds (raw 0–15 → %)
        if (print.hasCoolingFanSpeed()) {
            final int pct = (int) Math.round(parseDouble(printer.getName(), print.getCoolingFanSpeed(), 0) * 100.0 / 15.0);
            fanCooling.setText("Part %d%%".formatted(pct));
        }
        if (print.hasBigFan1Speed()) {
            final int pct = (int) Math.round(parseDouble(printer.getName(), print.getBigFan1Speed(), 0) * 100.0 / 15.0);
            fanAux.setText("Aux %d%%".formatted(pct));
        }

        // IP address — net.info[0].ip stored little-endian as int64
        if (print.hasNet() && print.getNet().getInfoCount() > 0) {
            final long ip = print.getNet().getInfo(0).getIp();
            if (ip != 0) {
                ipAddress = "%d.%d.%d.%d".formatted(
                        ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
            }
        }

        // Build plate (device.plate.cur_id)
        if (print.hasDevice() && print.getDevice().hasPlate() && print.getDevice().getPlate().hasCurId()) {
            buildPlate = decodePlateId(print.getDevice().getPlate().getCurId());
        }

        // HMS health alerts — only surface severity 1 (fatal) or 2 (serious)
        if (print.getHmsCount() > 0) {
            final List<String> serious = print.getHmsList().stream()
                    .filter(h -> {
                        final int severity = (int) ((h.getCode() >> 16) & 0xFFFFL);
                        return severity <= 2 && severity > 0;
                    })
                    .map(h -> BambuErrors.getPrinterError((int) h.getAttr())
                            .filter(s -> !s.isBlank())
                            .orElse("HMS 0x%08X".formatted(h.getAttr())))
                    .distinct()
                    .toList();
            if (serious.isEmpty()) {
                hmsBadge.setVisible(false);
            } else {
                hmsBadge.setTooltipText(String.join("\n", serious));
                hmsBadge.setVisible(true);
            }
        } else {
            hmsBadge.setVisible(false);
        }

        if (print.hasAms() && print.getAms().getAmsCount() > 0) {
            processAms(print.getAms());
            if (printer.getModel().isDualNozzle()) {
                // H2D sends vir_slot[] instead of vt_tray (id=254 slot0, id=255 slot1). ams.tray_now reports
                // 254/255 directly (same sentinel as a real external-spool selection) when one of these nozzle
                // slots - rather than an AMS tray - is the one actually feeding the hotend right now.
                final int activeTrayId = activeAmsTrayId(print.getAms());
                if (print.getVirSlotCount() > 0) {
                    print.getVirSlotList().forEach(tray -> processVtTray(tray, activeTrayId));
                } else if (print.hasVtTray()) {
                    processVtTray(print.getVtTray(), activeTrayId);
                }
            }
            // Non-dual-nozzle printers with AMS don't display the external spool slot
        } else if (print.getVirSlotCount() > 0) {
            // No AMS unit attached, so there's no ams.tray_now to say which of the two nozzle spools is feeding -
            // leave both unhighlighted rather than guessing.
            print.getVirSlotList().forEach(tray -> processVtTray(tray, -1));
        } else if (print.hasVtTray()) {
            // No AMS unit and a single external spool - it's the only filament source, so it's active whenever
            // the printer isn't idle.
            final int activeTrayId = gcodeState.isIdle() ? -1 : getTrayId(print.getVtTray());
            processVtTray(print.getVtTray(), activeTrayId);
        }

        print.getLightsReportList().stream()
                .filter(lr -> BambuConst.CHAMBER_LIGHT.equals(lr.getNode()))
                .findFirst()
                .ifPresent(lr -> {
                    monitorLampText.setText(lr.getMode());
                    monitorLamp.setSrc(BambuConst.LightMode.ON.getValue().equals(lr.getMode()) ? Images.MONITOR_LAMP_ON.getImage() : Images.MONITOR_LAMP_OFF.getImage());
                });
    }

    private <T> void process(final boolean hasValue, final BambuPrinter.Message message, final T data, final BiConsumer<BambuPrinter.Message, T> consumer) {
        if (!hasValue) {
            return;
        }
        consumer.accept(message, data);
    }

    private void processError(final BambuPrinter.Message message) {
        lastError = printer.getPrintError();
        final String errorString;
        final boolean hasError;
        if (lastError == 0) {
            hasError = false;
            errorString = "";
        } else {
            hasError = true;
            errorString = "\n\nPrint Error [%d / %s]: %s".formatted(
                    lastError, Integer.toHexString(lastError),
                    BambuErrors.getPrinterError(lastError).orElseGet(() -> "No Translation"));
        }

        printerInfo = "Last Updated: %s%s".formatted(DTF.format(message.lastUpdated()), errorString);
        if (hasError) {
            printerName.addClassName(LumoUtility.Background.ERROR_50);
        } else {
            printerName.removeClassName(LumoUtility.Background.ERROR_50);
        }
    }

    private void processMessage(final BambuPrinter.Message message) {
        process(message.message().hasPrint(), message, message.message().getPrint(), this::processPrint);
        processError(message);
    }

    private void updatePrinterStatus() {
        final String value = "Status: %s".formatted(gcodeState.getDescription());
        if (value.equals(printerStatus.getText())) {
            return;
        }
        printerStatus.setText(value);
        printerStatus.removeClassName(LumoUtility.TextColor.ERROR);
        printerStatus.removeClassName(LumoUtility.TextColor.PRIMARY);
        printerStatus.removeClassName(LumoUtility.TextColor.SUCCESS);
        if (gcodeState.isError()) {
            printerStatus.addClassName(LumoUtility.TextColor.ERROR);
        } else if (gcodeState.isReady()) {
            printerStatus.addClassName(LumoUtility.TextColor.SUCCESS);
        } else if (gcodeState.isPrinting()) {
            printerStatus.addClassName(LumoUtility.TextColor.PRIMARY);
        }
    }

    public void update() {
        if (!built) {
            return;
        }
        final BambuConst.GCodeState newState = printer.getGCodeState();
        notifyPrintState(printerName, printer, gcodeState, newState);
        // Auto-dry on finish is handled server-side by AmsDryService.watchAutoDry() - triggering it from
        // this UI tick meant it silently didn't run with no browser open, and ran once per open tab.
        if (newState.isIdle() && !gcodeState.isIdle()) {
            // Belt-and-braces: the message that reports "gone idle" doesn't always carry ams/vt_tray data, so
            // processAms/processVtTray might not run again to clear the highlight themselves.
            clearActiveTrayHighlight();
        }
        gcodeState = newState;
        if (processFull) {
            printer.getFullStatus().ifPresent(message -> {
                processFull = false;
                processMessage(message);
            });
        }
        printer.getStatus().ifPresent(this::processMessage);
        printer.getThumbnail().ifPresent(data -> {
            if (data.thumbnail().getId().equals(thumbnailId)) {
                return;
            }
            thumbnailId = data.thumbnail().getId();
            thumbnail.setSrc(data.thumbnail());
            thumbnailUpdated.setText(DTF.format(data.lastUpdated()));
        });
        updatePrinterStatus();
        if (isAdmin) {
            updatePrintAgain();
            updateStartNext();
        }
        updateMaintenanceDue();
        updateFailureBadge(newState);
        updateAiStatus();
        updateCheckBedLabel();
        updateAmsDryVisibility();
    }

    private void updateFailureBadge(final BambuConst.GCodeState newState) {
        if (!aiService.isEnabled()) {
            return;
        }
        // Auto-clear the failure badge when the print ends
        if (lastAiState != null && lastAiState.isPrinting() && !newState.isPrinting()) {
            failureActive = false;
            failureBadge.setVisible(false);
        }
        lastAiState = newState;
    }

    /**
     * Called by NotificationService (via the scheduled failure watcher) to show the failure badge on this card.
     * Must be called from a UI access() block.
     */
    public void showFailureBadge(final String description) {
        if (failureActive) {
            return; // already showing
        }
        failureActive = true;
        failureBadge.setTooltipText("AI: possible failure — " + truncateAi(description) + " (click to dismiss)");
        failureBadge.setVisible(true);
    }

    private void updateMaintenanceDue() {
        final String due = maintenanceService.getTaskStatus(printer.getName()).stream()
                .filter(MaintenanceService.TaskStatus::overdue)
                .map(ts -> ts.task().name())
                .collect(Collectors.joining(", "));
        if (maintenanceDueText.equals(due)) {
            return;
        }
        maintenanceDueText = due;
        if (due.isEmpty()) {
            maintenanceDue.setVisible(false);
            return;
        }
        maintenanceDue.setTooltipText("Maintenance due: %s".formatted(due));
        maintenanceDue.setVisible(true);
    }

    private void doConfirm(final String description, final Runnable runnable) {
        YesNoCancelDialog.show("%s - %s\n\nAre you sure?".formatted(printer.getName(), description), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            runnable.run();
        });
    }

    private void doConfirm(final BambuConst.CommandControl command) {
        doConfirm(command.getValue(), () -> printer.commandControl(command));
    }

    private void doConfirm(final String description, final String gcode) {
        doConfirm(description, () -> printer.commandPrintGCodeLine(gcode));
    }

    private void doPrintAgain() {
        printer.getLastPrintFile().ifPresentOrElse(
                file -> doConfirm("Print Again [%s]".formatted(file), () -> {
                    showNotification("%s: sending print job".formatted(printer.getName()));
                    executor.submit(printer::commandPrintAgain);
                }),
                () -> showError("%s: No previous print found".formatted(printer.getName()), Duration.ofSeconds(5)));
    }

    private Button buildPrintAgain() {
        printAgain.addClassName("print-again");
        printAgain.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        printAgain.setIcon(new Icon(VaadinIcon.ROTATE_LEFT));
        printAgain.addClickListener(l -> doPrintAgain());
        printAgain.setVisible(false);
        return printAgain;
    }

    private Button buildStartNext() {
        startNext.addClassName("print-again");
        startNext.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        startNext.setIcon(new Icon(VaadinIcon.TIME_FORWARD));
        startNext.addClickListener(l -> doStartNext());
        startNext.setVisible(false);
        return startNext;
    }

    private void setupCheckBed() {
        checkBed.setIcon(new Icon(VaadinIcon.EYE));
        checkBed.setTooltipText("Ask AI whether the bed is clear");
        checkBed.setVisible(aiService.isEnabled());
        checkBed.addClickListener(l -> doCheckBed());
    }

    private Div buildAiStatus() {
        aiStatusChip.addClassName("ai-status-chip");
        aiStatusChip.setVisible(false);
        final Div result = new Div(aiStatusChip);
        result.addClassName("ai-status");
        return result;
    }

    private void doCheckBed() {
        final boolean printing = gcodeState.isPrinting();
        showNotification("%s: %s…".formatted(printer.getName(),
                printing ? "checking print status with AI" : "checking bed with AI"));
        final Optional<UI> ui = printerName.getUI();
        final var future = printing
                ? aiService.checkFailure(printer.getName())
                : aiService.checkBedClear(printer.getName());
        future.thenAccept(result ->
                ui.ifPresent(u -> u.access(() -> {
                    if (result.isEmpty()) {
                        showError("%s: no camera snapshot available yet".formatted(printer.getName()), Duration.ofSeconds(4));
                        return;
                    }
                    final OllamaService.AiResult r = result.get();
                    if (printing) {
                        // failure check: positive = failure detected (bad)
                        final String icon = r.positive() ? "✗" : "✓";
                        final String label = r.positive() ? "possible failure" : "print looks OK";
                        showNotification("%s: %s %s — %s".formatted(printer.getName(), icon, label, truncateAi(r.description())));
                    } else {
                        // bed clear check: positive = bed is clear (good)
                        final String icon = r.positive() ? "✓" : "✗";
                        final String label = r.positive() ? "clear" : "not clear";
                        showNotification("%s: bed %s %s — %s".formatted(printer.getName(), icon, label, truncateAi(r.description())));
                    }
                })));
    }

    private Button buildFailureBadge() {
        failureBadge.addClassName(LumoUtility.TextColor.ERROR);
        failureBadge.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        failureBadge.setTooltipText("AI detected a possible print failure — click to dismiss");
        failureBadge.setVisible(false);
        failureBadge.addClickListener(l -> {
            failureActive = false;
            failureBadge.setVisible(false);
        });
        return failureBadge;
    }

    private Button buildHmsBadge() {
        hmsBadge.addClassName(LumoUtility.TextColor.WARNING);
        hmsBadge.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        hmsBadge.setVisible(false);
        return hmsBadge;
    }

    private Button buildTasmota(final TasmotaService.TasmotaTarget target) {
        final Button result = new Button(new Icon(VaadinIcon.PLUG));
        result.setTooltipText("Smart Plug: %s".formatted(target.label()));
        final ContextMenu menu = newContextMenu(result);
        menu.addItem("Power On", l -> doTasmota(target, true));
        menu.addItem("Power Off", l -> doTasmota(target, false));
        return result;
    }

    private void doTasmota(final TasmotaService.TasmotaTarget target, final boolean on) {
        final String description = !on && gcodeState.isPrinting()
                ? "Smart Plug Power Off - PRINTER IS PRINTING!"
                : "Smart Plug Power %s".formatted(on ? "On" : "Off");
        doConfirm(description, () -> {
            final Optional<UI> ui = printerName.getUI();
            tasmotaService.power(target, on,
                    () -> ui.ifPresent(u -> u.access(() -> showNotification("%s: plug switched %s".formatted(printer.getName(), on ? "on" : "off")))),
                    error -> ui.ifPresent(u -> u.access(() -> showError("%s: %s".formatted(printer.getName(), error)))));
        });
    }

    private void doStartNext() {
        queueService.peek(printer.getName()).ifPresentOrElse(entry -> {
            if (aiService.isEnabled()) {
                showNotification("%s: checking bed…".formatted(printer.getName()));
                final Optional<UI> ui = printerName.getUI();
                aiService.checkBedClear(printer.getName()).thenAccept(result ->
                        ui.ifPresent(u -> u.access(() -> {
                            if (result.isEmpty()) {
                                // no snapshot yet or Ollama error - fall through to manual check
                                confirmAndStartNext(entry, "");
                                return;
                            }
                            final OllamaService.AiResult aiResult = result.get();
                            if (aiResult.positive()) {
                                // bed clear - confirm with AI note
                                confirmAndStartNext(entry,
                                        "\n\n✓ AI: bed appears clear — " + truncateAi(aiResult.description()));
                            } else {
                                // bed not clear - block with override option
                                YesNoCancelDialog.show(
                                        "%s — AI detected: bed may not be clear\n\n%s\n\nOverride and start anyway?"
                                                .formatted(printer.getName(), truncateAi(aiResult.description())),
                                        ync -> {
                                            if (ync.isConfirmed()) {
                                                performStartNext(entry);
                                            }
                                        });
                            }
                        })));
            } else {
                confirmAndStartNext(entry, "");
            }
        }, () -> showError("%s: queue is empty".formatted(printer.getName())));
    }

    private void confirmAndStartNext(final PrintQueueService.QueueEntry entry, final String aiNote) {
        doConfirm("Start next queued print [%s] plate %d\n\nIs the bed clear?%s"
                .formatted(entry.command().filename(), entry.command().plateId(), aiNote),
                () -> performStartNext(entry));
    }

    private void performStartNext(final PrintQueueService.QueueEntry entry) {
        final Optional<UI> ui = printerName.getUI();
        queueService.startNext(printer.getName(),
                () -> ui.ifPresent(u -> u.access(() -> showNotification("%s: print started".formatted(printer.getName())))),
                error -> ui.ifPresent(u -> u.access(() -> showError(error))));
    }

    private static String truncateAi(final String s) {
        return s.length() <= 150 ? s : s.substring(0, 150) + "…";
    }

    private static String formatTimeAgo(final Instant t) {
        final long secs = Duration.between(t, Instant.now()).getSeconds();
        if (secs < 60) {
            return "just now";
        }
        if (secs < 3600) {
            return "%d min ago".formatted(secs / 60);
        }
        return "%dh %dm ago".formatted(secs / 3600, (secs % 3600) / 60);
    }

    private void updateAiStatus() {
        // Animated dot: visible while any check is running
        aiCheckDot.setVisible(aiService.isEnabled() && aiService.isCheckInProgress(printer.getName()));

        if (!aiService.isEnabled()) {
            return;
        }

        // Status chip: shows the last check result + relative time
        final Optional<PrintAiService.AiCheckResult> last = aiService.getLastResult(printer.getName());
        if (last.isEmpty()) {
            return;
        }
        final PrintAiService.AiCheckResult r = last.get();
        final String typeLabel = switch (r.checkType()) {
            case "bed-clear" -> "Bed";
            case "first-layer" -> "Layer";
            default -> "Print";
        };
        final String icon = switch (r.severity()) {
            case OK -> "✓";
            case WARN -> "⚠";
            case FAIL -> "✗";
        };
        final String statusText = r.good()
                ? "%s AI: %s OK".formatted(icon, typeLabel)
                : "%s AI: %s".formatted(icon, truncateAi(r.description()));
        final String text = "%s (%s)".formatted(statusText, formatTimeAgo(r.checkedAt()));
        if (text.equals(lastAiStatusText)) {
            return;
        }
        lastAiStatusText = text;
        aiStatusChip.setText(text);
        aiStatusChip.removeClassNames("ai-status-ok", "ai-status-warn", "ai-status-fail");
        final String colorClass = switch (r.severity()) {
            case OK -> "ai-status-ok";
            case WARN -> "ai-status-warn";
            case FAIL -> "ai-status-fail";
        };
        aiStatusChip.addClassName(colorClass);
        aiStatusChip.setVisible(true);
    }

    private void updateCheckBedLabel() {
        if (!aiService.isEnabled()) {
            return;
        }
        if (gcodeState.isPrinting()) {
            checkBed.setTooltipText("Ask AI if the print looks OK");
        } else {
            checkBed.setTooltipText("Ask AI whether the bed is clear");
        }
    }

    private void updateStartNext() {
        final int size = queueService.size(printer.getName());
        final String text;
        if (size == 0 || !gcodeState.isReady() || printer.isBlocked()) {
            text = "";
        } else {
            final String file = queueService.peek(printer.getName())
                    .map(e -> e.command().filename())
                    .orElse("");
            text = "Start Next (%d queued): %s".formatted(size, file.substring(file.lastIndexOf(BambuConst.PATHSEP) + 1));
        }
        if (startNextText.equals(text)) {
            return;
        }
        startNextText = text;
        if (text.isEmpty()) {
            startNext.setVisible(false);
            return;
        }
        startNext.setText(text);
        startNext.setVisible(true);
    }

    private void reloadQueue(final Div list) {
        list.removeAll();
        final List<PrintQueueService.QueueEntry> queue = queueService.getQueue(printer.getName());
        if (queue.isEmpty()) {
            list.add(new Div("Queue is empty - add jobs from Batch Print"));
            return;
        }
        for (int i = 0; i < queue.size(); i++) {
            final PrintQueueService.QueueEntry entry = queue.get(i);
            final Button remove = new Button(new Icon(VaadinIcon.TRASH), l -> {
                queueService.removeEntry(printer.getName(), entry);
                reloadQueue(list);
            });
            remove.setTooltipText("Remove from queue");
            final HorizontalLayout row = new HorizontalLayout(
                    new Span("%d. %s (plate %d)".formatted(i + 1, entry.command().filename(), entry.command().plateId())),
                    remove);
            row.setDefaultVerticalComponentAlignment(FlexLayout.Alignment.CENTER);
            list.add(row);
        }
    }

    private void showQueue() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("%s: Print Queue".formatted(printer.getName()));
        dialog.setWidth("600px");
        final Div list = new Div();
        reloadQueue(list);
        dialog.add(list);
        dialog.getFooter().add(new Button("Close", l -> dialog.close()));
        dialog.open();
    }

    private void updatePrintAgain() {
        final String file = printer.getLastPrintFile().orElse("");
        if (printAgainFile.equals(file)) {
            return;
        }
        printAgainFile = file;
        if (file.isEmpty()) {
            printAgain.setVisible(false);
            return;
        }
        printAgain.setText("Print Again: %s".formatted(file.substring(file.lastIndexOf(BambuConst.PATHSEP) + 1)));
        printAgain.setVisible(true);
    }

    private Button newButton(final String toolTip, final VaadinIcon icon, final ComponentEventListener<ClickEvent<Button>> clickListener) {
        final Button result = new Button(new Icon(icon), clickListener);
        result.setTooltipText(toolTip);
        return result;
    }

    private Button fanControl(final String toolTip, final VaadinIcon icon) {
        final Button result = new Button(new Icon(icon));
        result.setTooltipText(toolTip);
        final ContextMenu menu = newContextMenu(result);
        EnumSet.allOf(BambuConst.Fan.class).forEach(fan -> {
            final SubMenu fanMenu = menu.addItem(fan.getName()).getSubMenu();
            EnumSet.allOf(BambuConst.FanSpeed.class).forEach(fanSpeed -> {
                fanMenu.addItem(fanSpeed.getName(), l -> doConfirm("Fan [%s] Speed[%s]".formatted(fan.getName(), fanSpeed.getName()),
                        BambuConst.gcodeFanSpeed(fan, fanSpeed)));
            });
        });
        return result;
    }

    private void showStatus() {
        final Dialog d = new Dialog();
        d.setHeaderTitle("%s: Status".formatted(printer.getName()));

        final StringBuilder sb = new StringBuilder(printerInfo);
        printer.getFirmwareVersion().ifPresent(v -> sb.append("\n\nFirmware: ").append(v));
        if (!nozzleDiameter.isBlank() || !nozzleType.isBlank()) {
            sb.append("\nNozzle 1: ").append(nozzleDiameter.isBlank() ? "?" : nozzleDiameter + "mm");
            if (!nozzleType.isBlank()) sb.append(" (").append(nozzleType).append(")");
        }
        if (!nozzle2Diameter.isBlank() || !nozzle2Type.isBlank()) {
            sb.append("\nNozzle 2: ").append(nozzle2Diameter.isBlank() ? "?" : nozzle2Diameter + "mm");
            if (!nozzle2Type.isBlank()) sb.append(" (").append(nozzle2Type).append(")");
        }
        if (!wifiSignal.isBlank()) {
            sb.append("\nWiFi: ").append(wifiSignal);
        }
        if (!ipAddress.isBlank()) {
            sb.append("\nIP: ").append(ipAddress);
        }
        if (!buildPlate.isBlank()) {
            sb.append("\nBuild Plate: ").append(buildPlate);
        }
        final List<BambuPrinter.ModuleInfo> mods = printer.getModules();
        if (!mods.isEmpty()) {
            sb.append("\n\nModules:");
            mods.forEach(m -> sb.append("\n  [").append(m.name()).append("]")
                    .append(m.projectName() != null && !m.projectName().isBlank() ? " " + m.projectName() : "")
                    .append("  hw:").append(m.hwVer())
                    .append("  fw:").append(m.swVer()));
        }

        final Div div = new Div(sb.toString());
        div.getStyle()
                .setPadding("10px")
                .setWhiteSpace(Style.WhiteSpace.PRE_WRAP);
        if (lastError != 0) {
            div.addClassName(LumoUtility.Background.ERROR_50);
        }
        d.add(div);
        final Button ok = new Button("OK", e -> d.close());
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(ok);
        d.open();
    }

    public Component getDragHandle() {
        return nameDiv;
    }

    private static String decodePlateId(final String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return switch (id) {
            case "P0101" -> "Cool Plate";
            case "P0102" -> "Smooth PEI";
            case "P0201" -> "Engineering Plate";
            case "P0300" -> "High Temp Plate";
            case "P0400" -> "Textured PEI";
            case "P1101" -> "Textured PEI (2)";
            default -> id;
        };
    }

    private Div buildWhepVideo(final String streamUrl) {
        final Div container = new Div();
        container.addClassName("live-view");
        // Primary: WHEP/WebRTC — low latency, works on LAN immediately.
        // Fallback: HLS after 10 s if ICE never connects (no UDP path = external access).
        //   HLS is pure HTTP, routed through nginx /_hlsstream/ → mediamtx:8888.
        //   hls.js is served from /hls.min.js (bundled in META-INF/resources).
        container.getElement().executeJs("""
                const whepUrl = new URL($0 + '/whep', window.location.href).toString();
                const hlsUrl  = new URL($0.replace('/_camerastream/', '/_hlsstream/') + '/index.m3u8', window.location.href).toString();
                const hlsJsUrl = new URL('/hls.min.js', window.location.href).toString();

                const video = document.createElement('video');
                video.autoplay = true; video.muted = true; video.playsInline = true; video.controls = true;
                video.style.cssText = 'width:100%;height:100%;object-fit:contain;display:block;background:#000;';
                this.appendChild(video);

                // Tiny status label — disappears once playing, visible for diagnosis
                const status = document.createElement('div');
                status.style.cssText = 'position:absolute;bottom:4px;left:4px;background:rgba(0,0,0,0.6);color:#ccc;font:10px monospace;padding:1px 5px;border-radius:3px;z-index:10;pointer-events:none;';
                status.textContent = 'WebRTC…';
                this.style.position = 'relative';
                this.appendChild(status);

                let mode = 'whep'; // 'whep' | 'hls'
                let pc = null, retryTimeout = null, fallbackTimer = null;
                let sessionUrl = '', offerIceUfrag = '', offerIcePwd = '', offerMedias = [], queuedCandidates = [];

                // ── HLS fallback ──────────────────────────────────────────────────────────
                const setupHlsJs = () => {
                    status.textContent = 'HLS loading…';
                    const hls = new Hls({ backBufferLength: 4, enableWorker: false });
                    hls.loadSource(hlsUrl);
                    hls.attachMedia(video);
                    hls.on(Hls.Events.MANIFEST_PARSED, () => { status.style.display = 'none'; video.play().catch(() => {}); });
                    // Use hls.js recommended recovery — avoids destroy() which triggers
                    // Firefox's native "no video" error overlay via video.removeAttribute('src')
                    let mediaRecovered = false;
                    hls.on(Hls.Events.ERROR, (e, d) => {
                        if (!d.fatal) return;
                        status.textContent = 'HLS: ' + d.details;
                        if (d.type === Hls.ErrorTypes.NETWORK_ERROR) {
                            // Network hiccup — just retry loading
                            setTimeout(() => hls.startLoad(), 3000);
                        } else if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecovered) {
                            mediaRecovered = true;
                            hls.recoverMediaError();
                        } else {
                            // Unrecoverable — blank the src BEFORE destroy so Firefox
                            // does not flash its native error overlay
                            video.src = '';
                            hls.destroy();
                            setTimeout(setupHlsJs, 4000);
                        }
                    });
                };
                const tryNativeHls = () => {
                    // Old iOS without MSE — native <video> HLS only option
                    status.textContent = 'HLS (native)…';
                    video.src = hlsUrl;
                    video.load();
                    video.addEventListener('canplay', () => { status.style.display = 'none'; video.play().catch(() => {}); }, { once: true });
                    video.addEventListener('error', () => { status.textContent = 'HLS err: ' + (video.error ? video.error.code : '?'); }, { once: true });
                };
                const startHls = () => {
                    mode = 'hls';
                    video.srcObject = null;
                    status.textContent = 'Switching to HLS…';
                    // Always prefer hls.js (Chrome, Firefox, iOS 17+ with MSE).
                    // Only fall back to native <video> HLS for old iOS without MediaSource.
                    const afterLoad = () => {
                        if (typeof Hls !== 'undefined' && Hls.isSupported()) {
                            setupHlsJs();
                        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                            tryNativeHls(); // old iOS only
                        } else {
                            status.textContent = 'HLS not supported';
                        }
                    };
                    if (typeof Hls !== 'undefined') {
                        afterLoad();
                    } else {
                        status.textContent = 'Loading hls.js…';
                        const s = document.createElement('script');
                        s.src = hlsJsUrl; // served from bambuweb, no CDN
                        s.onload = afterLoad;
                        s.onerror = () => {
                            if (video.canPlayType('application/vnd.apple.mpegurl')) { tryNativeHls(); }
                            else { status.textContent = 'hls.js load failed'; }
                        };
                        document.head.appendChild(s);
                    }
                };

                // ── WHEP ──────────────────────────────────────────────────────────────────
                const unquoteCredential = (v) => JSON.parse('"' + v + '"');
                const linkToIceServers = (links) => (!links ? [] : links.split(', ').map((link) => {
                    const m = link.match(/^<(.+?)>; rel="ice-server"(; username="(.*?)"; credential="(.*?)"; credential-type="password")?/i);
                    const ret = {urls: [m[1]]};
                    if (m[3]) { ret.username = unquoteCredential(m[3]); ret.credential = unquoteCredential(m[4]); ret.credentialType = 'password'; }
                    return ret;
                }));
                const parseOffer = (sdp) => {
                    const ret = {iceUfrag: '', icePwd: '', medias: []};
                    for (const line of sdp.split('\\r\\n')) {
                        if (line.startsWith('m=')) ret.medias.push(line.slice(2));
                        else if (!ret.iceUfrag && line.startsWith('a=ice-ufrag:')) ret.iceUfrag = line.slice(12);
                        else if (!ret.icePwd && line.startsWith('a=ice-pwd:')) ret.icePwd = line.slice(10);
                    }
                    return ret;
                };
                const sendLocalCandidates = (candidates) => {
                    if (!sessionUrl || !candidates.length) return;
                    let frag = 'a=ice-ufrag:' + offerIceUfrag + '\\r\\na=ice-pwd:' + offerIcePwd + '\\r\\n';
                    const byMedia = {};
                    candidates.forEach(c => { const m = c.sdpMLineIndex; (byMedia[m] = byMedia[m] || []).push(c); });
                    let mid = 0;
                    for (const media of offerMedias) {
                        if (byMedia[mid]) { frag += 'm=' + media + '\\r\\na=mid:' + mid + '\\r\\n'; byMedia[mid].forEach(c => { frag += 'a=' + c.candidate + '\\r\\n'; }); }
                        mid++;
                    }
                    fetch(sessionUrl, {method: 'PATCH', headers: {'Content-Type': 'application/trickle-ice-sdpfrag', 'If-Match': '*'}, body: frag}).catch(() => {});
                };
                // Cleans up the current WHEP session only — does NOT touch fallbackTimer
                // (fallbackTimer counts down independently across retries)
                const cleanupWhepSession = () => {
                    clearTimeout(retryTimeout); retryTimeout = null;
                    if (pc) { try { pc.close(); } catch(e) {} pc = null; }
                    if (sessionUrl) { fetch(sessionUrl, {method: 'DELETE'}).catch(() => {}); sessionUrl = ''; }
                    queuedCandidates = [];
                };
                const onWhepError = () => {
                    if (mode === 'hls') return;
                    cleanupWhepSession();
                    retryTimeout = setTimeout(() => { retryTimeout = null; if (mode === 'whep') loadStream(); }, 2000);
                };
                const loadStream = () => {
                    if (mode === 'hls') return;
                    // Set the fallback timer only once — it survives WHEP retries.
                    // If ICE never connects within 10 s (external access), we switch to HLS.
                    if (!fallbackTimer) {
                        fallbackTimer = setTimeout(() => {
                            fallbackTimer = null;
                            status.textContent = 'WebRTC timeout → HLS';
                            cleanupWhepSession();
                            clearTimeout(retryTimeout); retryTimeout = null;
                            startHls();
                        }, 3000);
                    }
                    fetch(whepUrl, {method: 'OPTIONS'})
                        .then(res => {
                            pc = new RTCPeerConnection({iceServers: linkToIceServers(res.headers.get('Link')), sdpSemantics: 'unified-plan'});
                            pc.addTransceiver('video', {direction: 'sendrecv'});
                            pc.addTransceiver('audio', {direction: 'sendrecv'});
                            pc.onicecandidate = (e) => { if (!e.candidate) return; if (!sessionUrl) queuedCandidates.push(e.candidate); else sendLocalCandidates([e.candidate]); };
                            pc.oniceconnectionstatechange = () => {
                                if (!pc) return;
                                // WHEP connected — cancel HLS fallback permanently
                                if (pc.iceConnectionState === 'connected') { clearTimeout(fallbackTimer); fallbackTimer = null; }
                                if (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed') onWhepError();
                            };
                            pc.ontrack = (e) => { video.srcObject = e.streams[0]; status.style.display = 'none'; };
                            pc.createOffer().then(offer => {
                                const parsed = parseOffer(offer.sdp);
                                offerIceUfrag = parsed.iceUfrag; offerIcePwd = parsed.icePwd; offerMedias = parsed.medias;
                                pc.setLocalDescription(offer);
                                fetch(whepUrl, {method: 'POST', headers: {'Content-Type': 'application/sdp'}, body: offer.sdp})
                                    .then(res => { if (res.status !== 201) throw new Error('status ' + res.status); sessionUrl = new URL(res.headers.get('location'), window.location.href).toString(); return res.text(); })
                                    .then(sdp => { pc.setRemoteDescription({type: 'answer', sdp}); if (queuedCandidates.length) { sendLocalCandidates(queuedCandidates); queuedCandidates = []; } })
                                    .catch(onWhepError);
                            }).catch(onWhepError);
                        }).catch(onWhepError);
                };
                loadStream();
                """, streamUrl);
        return container;
    }

    private Component getThumbnailOrIframe() {
        return printer.getIFrame()
                .<Component>map(this::buildWhepVideo)
                .orElse(thumbnail);
    }

    public Component build(final BambuPrinter printer, final boolean fromDashboard) {
        this.printer = printer;
        this.fromDashboard = fromDashboard;
        thumbnailOrIframe = getThumbnailOrIframe();
        try {
            progressBar.setIndeterminate(true);
            printerStatus.getStyle().setFontWeight(Style.FontWeight.BOLD);
            final List<Component> list = new ArrayList<>();
            list.add(buildName());
            list.add(buildAiStatus());
            if (!config.remoteView()) {
                // Don't buildImage
            } else if (fromDashboard && !config.dashboard().remoteView()) {
                // Don't buildImage
            } else {
                list.add(buildImage());
            }
            list.add(buildStatus());
            list.add(buildAms());
            list.add(buildProgressBar());
            list.add(progressBar);
            if (isAdmin) {
                // Wide action buttons pinned to the bottom of the card (see .print-again in bambu.css) -
                // built and kept up to date by buildPrintAgain()/updatePrintAgain() and
                // buildStartNext()/updateStartNext(), but were never actually attached to the card here.
                list.add(buildPrintAgain());
                list.add(buildStartNext());
            }
            return createContent(list);
        } finally {
            built = true;
        }
    }

    private Component createContent(final List<Component> list) {
        final VerticalLayout content = new VerticalLayout();
        content.addClassName("dashboard-printer");
        content.setPadding(false);
        content.setSpacing(false);
        list.forEach(content::add);
        content.setSizeUndefined();
        return content;
    }

    private Div buildName() {
        final Div result = newDiv("name", printerName);
        nameDiv = result;
        maintenanceDue.setVisible(false);
        maintenanceDue.addClassName(LumoUtility.TextColor.ERROR);
        if (isAdmin) {
            maintenanceDue.addClickListener(l -> UI.getCurrent().navigate(MaintenanceView.class));
        }
        aiCheckDot.addClassName("ai-check-dot");
        aiCheckDot.setVisible(false);
        // Put the dot inside the name div so it doesn't disrupt the flex centering of printerName
        printerName.add(new Div(new Span(printer.getName()), aiCheckDot), newButton("", VaadinIcon.INFO, l -> showStatus()), maintenanceDue, buildHmsBadge(), buildFailureBadge());
        setupCheckBed();
        if (isAdmin) {
            result.add(
                    newButton("Show Logs", VaadinIcon.CLIPBOARD_TEXT, l -> UI.getCurrent().navigate(LogsView.class, printer.getName())),
                    newButton("Show SD Card", VaadinIcon.ARCHIVE, l -> UI.getCurrent().navigate(SdCardView.class, printer.getName())),
                    newButton("Request full status", VaadinIcon.REFRESH, l -> printer.commandFullStatus(true)),
                    newButton("Clear Print Error", VaadinIcon.WARNING, l -> printer.commandClearPrinterError()),
                    newButton("Resume Print", VaadinIcon.PLAY, l -> doConfirm(BambuConst.CommandControl.RESUME)),
                    newButton("Pause Print", VaadinIcon.PAUSE, l -> doConfirm(BambuConst.CommandControl.PAUSE)),
                    newButton("Stop Print", VaadinIcon.STOP, l -> doConfirm(BambuConst.CommandControl.STOP)),
                    newButton("Print Queue", VaadinIcon.TIME_FORWARD, l -> showQueue()),
                    checkBed
            );
            printers.getPrinterDetail(printer.getName())
                    .filter(pd -> pd.config().tasmota().isPresent())
                    .ifPresent(pd -> result.add(buildTasmota(
                            TasmotaService.TasmotaTarget.of(pd.config().tasmota().get(), pd.config().tasmotaChannel()))));
            if (fromDashboard) {
                result.add(newButton("Show Detail Printer", VaadinIcon.SEARCH_PLUS, l -> UI.getCurrent().navigate(PrinterView.class, printer.getName())));
            } else {
                result.add(
                        newButton("Disable Stepper Motors", VaadinIcon.COGS, l -> doConfirm("Disable Stepper Motors", BambuConst.gcodeDisableSteppers())),
                        fanControl("Fan Control", VaadinIcon.ASTERISK),
                        newButton("Send GCode", VaadinIcon.COG, l -> GCodeDialog.show(printer)),
                        newButton("Reboot", VaadinIcon.POWER_OFF, l -> doConfirm("Reboot", printer::commandSystemReboot)),
                        newButton("Back to Dashboard", VaadinIcon.ARROW_BACKWARD, l -> UI.getCurrent().navigate(Dashboard.class))
                );
            }
        }
        return result;
    }

    private Component buildControls() {
        final BiConsumer<BambuConst.Move, Integer> movexy = (m, value) ->
                printer.commandPrintGCodeLine(BambuConst.gcodeMoveXYZ(m, value, config.moveXy()));
        final BiConsumer<BambuConst.Move, Integer> movez = (m, value) ->
                printer.commandPrintGCodeLine(BambuConst.gcodeMoveXYZ(m, value, config.moveZ()));
        final Consumer<Boolean> movee = (up) ->
                printer.commandPrintGCodeLine(BambuConst.gcodeMoveExtruder(up));
        final Consumer<Boolean> home = b ->
                printer.commandPrintGCodeLine(b ? BambuConst.gcodeHomeXY() : BambuConst.gcodeHomeZ());

        final Div xyControl = newDiv("controlxy",
                newDiv("updown",
                        new Span("X/Y Control"),
                        newButton("Y+10", VaadinIcon.ANGLE_DOUBLE_UP, l -> movexy.accept(BambuConst.Move.Y, 10)),
                        newButton("Y+1", VaadinIcon.ANGLE_UP, l -> movexy.accept(BambuConst.Move.Y, 1))
                ),
                newDiv("leftright",
                        newButton("X-10", VaadinIcon.ANGLE_DOUBLE_LEFT, l -> movexy.accept(BambuConst.Move.X, -10)),
                        newButton("X-1", VaadinIcon.ANGLE_LEFT, l -> movexy.accept(BambuConst.Move.X, -1)),
                        newButton("XY Home", VaadinIcon.HOME, l -> home.accept(true)),
                        newButton("X+1", VaadinIcon.ANGLE_RIGHT, l -> movexy.accept(BambuConst.Move.X, 1)),
                        newButton("X+10", VaadinIcon.ANGLE_DOUBLE_RIGHT, l -> movexy.accept(BambuConst.Move.X, 10))
                ),
                newDiv("updown",
                        newButton("Y-1", VaadinIcon.ANGLE_DOWN, l -> movexy.accept(BambuConst.Move.Y, -1)),
                        newButton("Y-10", VaadinIcon.ANGLE_DOUBLE_DOWN, l -> movexy.accept(BambuConst.Move.Y, -10))
                )
        );

        final Div zControl = newDiv("controlz",
                new Span("Bed Control"),
                newButton("Bed+10", VaadinIcon.ANGLE_DOUBLE_UP, l -> movez.accept(BambuConst.Move.Z, -10)),
                newButton("Bed+1", VaadinIcon.ANGLE_UP, l -> movez.accept(BambuConst.Move.Z, -1)),
                newButton("Bed Home", VaadinIcon.HOME, l -> home.accept(false)),
                newButton("Bed-1", VaadinIcon.ANGLE_DOWN, l -> movez.accept(BambuConst.Move.Z, 1)),
                newButton("Bed-10", VaadinIcon.ANGLE_DOUBLE_DOWN, l -> movez.accept(BambuConst.Move.Z, 10))
        );

        final Div extruder = newDiv("extruder",
                new Span("Extruder"),
                newSpan("spacer"),
                newButton("Extruder-10", VaadinIcon.ANGLE_UP, l -> movee.accept(true)),
                newSpan("spacer"),
                newButton("Extruder+10", VaadinIcon.ANGLE_DOWN, l -> movee.accept(false)),
                newSpan("spacer")
        );

        final Button homeAll = newButton("All Home", VaadinIcon.HOME, l -> printer.commandPrintGCodeLine(BambuConst.gcodeHomeAll()));

        final Consumer<Boolean> setEnabled = enabled -> {
            xyControl.setEnabled(enabled);
            zControl.setEnabled(enabled);
            extruder.setEnabled(enabled);
            homeAll.setEnabled(enabled);
        };
        final Checkbox enableControl = new Checkbox("Enable Controls", l -> setEnabled.accept(l.getValue()));
        setEnabled.accept(false);

        return newDiv("controlsbox", thumbnailOrIframe,
                newDiv("controls",
                        newDiv("controlheader", enableControl, homeAll),
                        newDiv("controlbody", xyControl, zControl, extruder)));
    }

    private Component buildImage() {
        return newDiv("image",
                fromDashboard ? thumbnailOrIframe : buildControls(),
                newDiv("imagestatus", thumbnailUpdated, printerStatus)
        );
    }

    private Div getBadge(final String toolTip, final Component... components) {
        final Div result = newDiv("badge", components);
        result.addClassName(toolTip.toLowerCase().replaceAll("\\s+", "-"));
        result.getElement().setProperty("title", toolTip);
        return result;
    }

    private ContextMenu newContextMenu(final Component component) {
        final ContextMenu result = new ContextMenu(component);
        if (config.menuLeftClick()) {
            result.setOpenOnClick(true);
        }
        return result;
    }

    private <T extends Component> T wrapMonitorMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = newContextMenu(result);
        Arrays.asList(BambuConst.LightMode.values())
                .forEach(lm -> {
                    menu.addItem("Set %s".formatted(lm.getValue()), l -> printer.commandLight(lm));
                });
        return result;
    }

    private <T extends Component> T wrapSpeedMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = newContextMenu(result);
        Arrays.asList(BambuConst.Speed.values())
                .forEach(s -> {
                    if (s == BambuConst.Speed.UNKNOWN) {
                        return;
                    }
                    menu.addItem(s.getDescription(), l -> doConfirm(s.getDescription(), () -> printer.commandSpeed(s)));
                });
        return result;
    }

    private <T extends Component> T wrapFanMenu(final T result) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = newContextMenu(result);
        EnumSet.allOf(BambuConst.Fan.class).forEach(fan -> {
            final SubMenu fanMenu = menu.addItem(fan.getName()).getSubMenu();
            EnumSet.allOf(BambuConst.FanSpeed.class).forEach(fanSpeed ->
                    fanMenu.addItem(fanSpeed.getName(), l -> doConfirm(
                            "Fan [%s] Speed [%s]".formatted(fan.getName(), fanSpeed.getName()),
                            BambuConst.gcodeFanSpeed(fan, fanSpeed))));
        });
        return result;
    }

    private void confirmTemperature(final String description, final List<String> list) {
        doConfirm(description, () -> printer.commandPrintGCodeLine(list));
    }

    private void confirmTemperature(final Supplier<Integer> current, final int maxTemp, final Function<Integer, String> function) {
        final IntegerField temp = new IntegerField("Target Temperature");
        temp.setMin(0);
        temp.setMax(maxTemp);
        temp.setStepButtonsVisible(true);
        temp.setValue(current.get());
        YesNoCancelDialog.show(List.of(temp), "%s\n\nAre you sure?".formatted(printer.getName()), ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            if (temp.getValue() < 0 || temp.getValue() > maxTemp) {
                showError("Invalid Temperature");
                return;
            }
            printer.commandPrintGCodeLine(function.apply(temp.getValue()));
        });
    }

    private <T extends Component> T wrapTemperature(final T result, final Supplier<Integer> current, final int maxTemp, final Function<Integer, String> function) {
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = newContextMenu(result);
        menu.addItem("Set Target", l -> confirmTemperature(current, maxTemp, function));

        final List<BambuConfig.Temperature> list = config.preheat().orElse(BambuConst.PREHEAT);

        if (list.isEmpty()) {
            return result;
        }

        final SubMenu preheat = menu.addItem("Preheat").getSubMenu();
        list.forEach(t -> {
            if (t.bed() < 0 || t.bed() > BambuConst.TEMPERATURE_MAX_BED) {
                Log.errorf("Skipping invalid bed preheat: %d", t.bed());
                return;
            }
            if (t.nozzle() < 0 || t.nozzle() > BambuConst.TEMPERATURE_MAX_NOZZLE) {
                Log.errorf("Skipping invalid nozzle preheat: %d", t.nozzle());
                return;
            }

            preheat.addItem(t.name(), l -> confirmTemperature(t.name(), List.of(
                    BambuConst.gcodeTargetTemperatureBed(t.bed()),
                    BambuConst.gcodeTargetTemperatureNozzle(t.nozzle()))));

        });
        return result;
    }

    private FlexLayout buildStatus() {
        final FlexLayout result = new FlexLayout();
        result.addClassName("status");
        result.add(
                wrapTemperature(getBadge("Nozzle", nozzleImage, nozzle, nozzleTarget), () -> (int) temperatureNozzle, BambuConst.TEMPERATURE_MAX_NOZZLE, BambuConst::gcodeTargetTemperatureNozzle),
                wrapTemperature(getBadge("Bed", bedImage, bed, bedTarget), () -> (int) temperatureBed, BambuConst.TEMPERATURE_MAX_BED, BambuConst::gcodeTargetTemperatureBed)
        );

        if (printer.getModel().isDualNozzle()) {
            result.add(wrapTemperature(getBadge("Nozzle 2", nozzle2Image, nozzle2, nozzle2Target), () -> (int) temperatureNozzle2, BambuConst.TEMPERATURE_MAX_NOZZLE, BambuConst::gcodeTargetTemperatureNozzle));
        }

        if (printer.getModel().isTemperature()) {
            result.add(getBadge("Frame", frameImage, frame));
        }

        result.add(
                wrapSpeedMenu(getBadge("Speed", speedImage, speed)),
                wrapFanMenu(getBadge("Fan", fanCooling, fanAux)),
                wrapMonitorMenu(getBadge("Lamp", monitorLamp, monitorLampText))
        );
        return result;
    }

    private HorizontalLayout buildProgressBar() {
        final HorizontalLayout result = new HorizontalLayout(progressFile, progressTime, progressLayer);
        result.addClassName("progress");
        return result;
    }

    private void showAmsDryDialog(final int amsId) {
        final AmsDryService.DrySetting current = amsDryService.getSetting(printer.getName());

        final Dialog d = new Dialog();
        d.setHeaderTitle("%s — AMS #%d Drying".formatted(printer.getName(), amsId));
        d.setWidth("340px");

        // ── Dry Now ──────────────────────────────────────────────
        final IntegerField nowTemp = new IntegerField("Temperature (°C)");
        nowTemp.setValue(current.temp());
        nowTemp.setMin(40);
        nowTemp.setMax(65);
        nowTemp.setStepButtonsVisible(true);
        nowTemp.setWidth("100%");

        final IntegerField nowDur = new IntegerField("Duration (hours)");
        nowDur.setValue(current.durationHours());
        nowDur.setMin(1);
        nowDur.setMax(24);
        nowDur.setStepButtonsVisible(true);
        nowDur.setWidth("100%");

        final Button stopBtn = new Button("Stop Drying", new Icon(VaadinIcon.CLOSE_SMALL), e -> {
            printer.commandAmsStopDry(amsId);
            amsDryService.clearDrying(printer.getName(), amsId);
            showNotification("%s: AMS #%d drying stopped".formatted(printer.getName(), amsId));
            d.close();
        });
        stopBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        // Only show stop if there's an active session or we detect drying from AMS temp
        stopBtn.setVisible(amsDryService.getActiveDrying(printer.getName(), amsId).isPresent());

        final Button startBtn = new Button("Start Drying", new Icon(VaadinIcon.FIRE), e -> {
            printer.commandAmsDry(amsId, nowTemp.getValue(), nowDur.getValue());
            amsDryService.recordDrying(printer.getName(), amsId, nowTemp.getValue(), nowDur.getValue());
            showNotification("%s: AMS #%d drying started (%d°C / %dh)"
                    .formatted(printer.getName(), amsId, nowTemp.getValue(), nowDur.getValue()));
            d.close();
        });
        startBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final com.vaadin.flow.component.html.H4 nowHeader = new com.vaadin.flow.component.html.H4("Dry Now");
        nowHeader.getStyle().set("margin", "0 0 var(--lumo-space-s) 0");
        final Div nowSection = new Div(nowHeader, nowTemp, nowDur);
        nowSection.getStyle()
                .setBorder("1px solid var(--lumo-contrast-20pct)")
                .setBorderRadius("var(--lumo-border-radius-m)")
                .setPadding("var(--lumo-space-s)")
                .setMarginBottom("var(--lumo-space-m)");

        // ── Auto-dry on finish ────────────────────────────────────
        final com.vaadin.flow.component.checkbox.Checkbox autoToggle =
                new com.vaadin.flow.component.checkbox.Checkbox("Enabled — trigger automatically when print finishes");
        autoToggle.setValue(current.autoOnFinish());

        final IntegerField autoTemp = new IntegerField("Temperature (°C)");
        autoTemp.setValue(current.temp());
        autoTemp.setMin(40);
        autoTemp.setMax(65);
        autoTemp.setStepButtonsVisible(true);
        autoTemp.setWidth("100%");

        final IntegerField autoDur = new IntegerField("Duration (hours)");
        autoDur.setValue(current.durationHours());
        autoDur.setMin(1);
        autoDur.setMax(24);
        autoDur.setStepButtonsVisible(true);
        autoDur.setWidth("100%");

        final Button saveBtn = new Button("Save", e -> {
            amsDryService.setSetting(printer.getName(),
                    new AmsDryService.DrySetting(autoToggle.getValue(), autoTemp.getValue(), autoDur.getValue()));
            showNotification("%s: auto-dry %s".formatted(printer.getName(), autoToggle.getValue() ? "enabled" : "disabled"));
            updateAmsDryVisibility();
            d.close();
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);

        final com.vaadin.flow.component.html.H4 autoHeader = new com.vaadin.flow.component.html.H4("Auto-dry on Print Finish");
        autoHeader.getStyle().set("margin", "0 0 var(--lumo-space-s) 0");
        final Div autoSection = new Div(autoHeader, autoToggle, autoTemp, autoDur);
        autoSection.getStyle()
                .setBorder("1px solid var(--lumo-contrast-20pct)")
                .setBorderRadius("var(--lumo-border-radius-m)")
                .setPadding("var(--lumo-space-s)");

        d.add(nowSection, autoSection);
        d.getFooter().add(new Button("Cancel", e -> d.close()), stopBtn, saveBtn, startBtn);
        d.open();
    }

    private Button buildAmsDryButton(final int amsId) {
        final Button btn = new Button(new Icon(VaadinIcon.FIRE));
        btn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        btn.setVisible(false); // shown only once AMS 2 Pro is confirmed via module info
        btn.addClickListener(l -> showAmsDryDialog(amsId));
        amsDryButtons.put(amsId, btn);
        return btn;
    }

    /** Fire auto-dry when a print completes, if enabled via UI. Called with old/new state before gcodeState is updated. */
    private void updateAmsDryVisibility() {
        if (amsDryButtons.isEmpty()) {
            return;
        }
        // Build unitIndex → AmsType from all AMS-related modules (name "ams/N" or "n3f/N" etc.)
        final Map<Integer, BambuConst.AmsType> amsTypeByIndex = printer.getModules().stream()
                .filter(m -> m.getAmsType() != BambuConst.AmsType.UNKNOWN)
                .collect(Collectors.toMap(
                        BambuPrinter.ModuleInfo::unitIndex,
                        BambuPrinter.ModuleInfo::getAmsType,
                        (a, b) -> a));
        final AmsDryService.DrySetting dryConf = amsDryService.getSetting(printer.getName());
        amsDryButtons.forEach((amsId, btn) -> {
            final BambuConst.AmsType type = amsTypeByIndex.getOrDefault(amsId, BambuConst.AmsType.UNKNOWN);
            final boolean visible = type.isSupportsDrying();
            btn.setVisible(visible);
            if (visible) {
                btn.setTooltipText(dryConf.autoOnFinish()
                        ? "Dry filament — auto-dry ON (%d°C / %dh)".formatted(dryConf.temp(), dryConf.durationHours())
                        : "Dry filament (AMS 2 Pro)");
            }
        });
    }

    private Div buildAmsHeader(final AmsHeader header, final String label, final int amsId) {
        amsHeaders.put(header.id(), header);
        final Div result = newDiv("amsheader",
                new Span(label),
                newSpan("filler")
        );
        if (printer.getModel().isTemperature()) {
            result.add(header.temperature());
        }
        result.add(header.humidity());
        result.add(header.dryingBadge());
        if (isAdmin && amsId >= 0) {
            result.add(buildAmsDryButton(amsId));
        }
        return result;
    }

    private void doFilamentConfigure(final AmsFilament filament) {
        FilamentView.show(printer, filament.amsId(), filament.trayId());
    }

    private Div wrapAmsFilament(final AmsFilament filament) {
        final Div result = filament.div();
        if (!isAdmin) {
            return result;
        }
        final ContextMenu menu = newContextMenu(result);
        menu.addItem("Configure", l -> doFilamentConfigure(filament));
        menu.addItem("Load", l -> doConfirm(() -> printer.commandFilamentLoad(filament.trayId())));
        menu.addItem("Unload", l -> doConfirm(printer::commandFilamentUnload));
        return result;
    }

    private Div buildAmsFilament(final String key, final int amsId, final int trayId) {
        final Div result = newDiv("filament");
        final AmsFilament filament = new AmsFilament(amsId, trayId, result, new Span(), newSpan("color"));
        amsFilaments.put(key, filament);
        result.add(filament.type(), filament.color());
        return wrapAmsFilament(filament);
    }

    private int getAmsId(final AmsSingle single) {
        return parseInt(printer.getName(), single.getId(), BambuConst.AMS_TRAY_UNLOAD);
    }

    private int getTrayId(final Tray tray) {
        return parseInt(printer.getName(), tray.getId(), BambuConst.AMS_TRAY_UNLOAD);
    }

    private Div buildAmsFilament(final AmsSingle single, final Tray tray) {
        final int amsId = getAmsId(single);
        final int trayId = getTrayId(tray);
        return buildAmsFilament(getFilamentTrayKey(amsId, trayId), amsId, trayId);
    }

    private String getFilamentTrayKey(final int amsId, final int trayId) {
        return "single[%d]tray[%d]".formatted(amsId, trayId);
    }

    private String getAmsHeaderId(final int id) {
        return "AMS#%d".formatted(id);
    }

    private String getTrayKey(final int id) {
        return "Tray#%d".formatted(id);
    }

    private Div buildTray(final String amsHeaderId, final int amsId, final boolean hasHumidity, final List<Div> filaments) {
        final Image image = new Image(Images.AMS_HUMIDITY_0.getImage(), "Humidity");
        image.setTitle("Humidity");
        final Span dryBadge = new Span();
        dryBadge.addClassName("drying-badge");
        dryBadge.setVisible(false);
        final AmsHeader amsHeader = new AmsHeader(amsHeaderId, newSpan(), image, dryBadge);
        if (!hasHumidity) {
            amsHeader.humidity().getStyle().setDisplay(Style.Display.NONE);
        }
        final Div trayL = newDiv("amstray");
        filaments.forEach(trayL::add);
        return newDiv("ams", buildAmsHeader(amsHeader, amsHeaderId, amsId), trayL);
    }

    private void buildAms(final Div parent, final com.tfyre.bambu.model.Ams ams) {
        ams.getAmsList().forEach(single -> {
            final int amsId = getAmsId(single);
            parent.add(buildTray(
                    getAmsHeaderId(amsId),
                    amsId,
                    true,
                    single.getTrayList().stream()
                            .map(tray -> buildAmsFilament(single, tray))
                            .toList()
            ));
        });
    }

    private void buildVtTray(final Div parent, final com.tfyre.bambu.model.Tray tray) {
        parent.add(buildVtTrayById(getTrayId(tray), getTrayKey(getTrayId(tray))));
    }

    /** Build a single external-spool tray widget. Key (for processVtTray lookup) is always
     *  getTrayKey(id); label is the string shown in the header. */
    private Div buildVtTrayById(final int id, final String label) {
        final String key = getTrayKey(id);
        final Image humidityImage = new Image(Images.AMS_HUMIDITY_0.getImage(), "Humidity");
        humidityImage.getStyle().setDisplay(Style.Display.NONE);
        final Span dryBadge = new Span();
        dryBadge.addClassName("drying-badge");
        dryBadge.setVisible(false);
        final AmsHeader amsHeader = new AmsHeader(key, newSpan(), humidityImage, dryBadge);
        // Temperature not meaningful for nozzle slots — hide but keep span so processVtTray can find it
        amsHeader.temperature().setVisible(false);
        final Div trayL = newDiv("amstray");
        trayL.add(buildAmsFilament(key, BambuConst.AMS_TRAY_UNLOAD, id));
        return newDiv("ams", buildAmsHeader(amsHeader, label, -1), trayL);
    }

    private void buildVirSlots(final Div parent) {
        final java.util.Optional<com.tfyre.bambu.model.Print> printOpt = printer.getFullStatus()
                .filter(m -> m.message().hasPrint())
                .map(m -> m.message().getPrint());
        final Div row = newDiv("vir-slots-row");
        if (printOpt.isPresent()) {
            final com.tfyre.bambu.model.Print print = printOpt.get();
            if (print.getVirSlotCount() > 0) {
                final java.util.List<? extends com.tfyre.bambu.model.Tray> slots = print.getVirSlotList();
                for (int i = 0; i < slots.size(); i++) {
                    final String label = i == 0 ? "Left Nozzle" : "Right Nozzle";
                    row.add(buildVtTrayById(getTrayId(slots.get(i)), label));
                }
                parent.add(row);
                return;
            } else if (print.hasVtTray()) {
                row.add(buildVtTrayById(getTrayId(print.getVtTray()), "Left Nozzle"));
                parent.add(row);
                return;
            }
        }
        // fullStatus has no vir_slot / vt_tray yet — pre-build with known H2D IDs
        // proto: vir_slot id=254 = left nozzle, id=255 = right nozzle
        Log.infof("%s: no vir_slot/vt_tray in full status, pre-building both nozzle slots", printer.getName());
        row.add(buildVtTrayById(BambuConst.AMS_TRAY_VIRTUAL, "Left Nozzle"));
        row.add(buildVtTrayById(BambuConst.AMS_TRAY_UNLOAD, "Right Nozzle"));
        parent.add(row);
    }

    private Div buildAms() {
        final Div result = newDiv("filaments");
        printer.getFullStatus().ifPresent(m -> {
            if (!m.message().hasPrint()) {
                return;
            }
            final com.tfyre.bambu.model.Print print = m.message().getPrint();
            final boolean hasAms = print.hasAms() && print.getAms().getAmsCount() > 0;
            if (hasAms) {
                buildAms(result, print.getAms());
            }
            // Only show vt_tray for printers with no AMS — printers with AMS don't use the external slot
            // H2D (dual-nozzle) uses vir_slots instead, handled by buildVirSlots below
            if (!printer.getModel().isDualNozzle() && !hasAms && print.hasVtTray()) {
                buildVtTray(result, print.getVtTray());
            }
        });
        if (printer.getModel().isDualNozzle()) {
            buildVirSlots(result);
        }
        return result;
    }

    private record AmsHeader(String id, Span temperature, Image humidity, Span dryingBadge) {

    }

    private record AmsFilament(int amsId, int trayId, Div div, Span type, Span color) {

        public int amsTrayId() {
            return amsId * 4 + trayId;
        }

    }

    private enum Images {
        AMS_HUMIDITY_0("ams_humidity_0.svg"),
        AMS_HUMIDITY_1("ams_humidity_1.svg"),
        AMS_HUMIDITY_2("ams_humidity_2.svg"),
        AMS_HUMIDITY_3("ams_humidity_3.svg"),
        AMS_HUMIDITY_4("ams_humidity_4.svg"),
        MONITOR_BED_TEMP("monitor_bed_temp.svg"),
        MONITOR_BED_TEMP_ACTIVE("monitor_bed_temp_active.svg"),
        MONITOR_NOZZLE_TEMP("monitor_nozzle_temp.svg"),
        MONITOR_NOZZLE_TEMP_ACTIVE("monitor_nozzle_temp_active.svg"),
        MONITOR_SPEED("monitor_speed.svg"),
        MONITOR_SPEED_ACTIVE("monitor_speed_active.svg"),
        MONITOR_FRAME_TEMP("monitor_frame_temp.svg"),
        MONITOR_LAMP_ON("monitor_lamp_on.svg"),
        MONITOR_LAMP_OFF("monitor_lamp_off.svg");

        private final String image;

        private Images(final String image) {
            this.image = "bambu/%s".formatted(image);
        }

        public String getImage() {
            return image;
        }

    }

}
