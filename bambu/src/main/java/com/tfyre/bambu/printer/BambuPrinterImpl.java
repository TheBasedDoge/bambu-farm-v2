package com.tfyre.bambu.printer;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.model.BambuMessage;
import com.tfyre.bambu.model.Info;
import com.tfyre.bambu.model.Print;
import com.tfyre.bambu.model.Pushing;
import com.tfyre.bambu.printer.BambuConst.PrinterModel;
import com.tfyre.bambu.security.SecurityUtils;
import com.vaadin.flow.server.VaadinSession;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduler;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Dependent
public class BambuPrinterImpl implements BambuPrinter, Processor {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().preservingProtoFieldNames();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    private static final int MAX_ITEMS = 1_000;
    private static final Duration LASTUPDATED = Duration.ofMinutes(2);

    private String name;
    private BambuConfig.Printer config;
    private Optional<BambuPrinter.Message> status = Optional.empty();
    private Optional<BambuPrinter.Message> fullStatus = Optional.empty();
    private Optional<BambuPrinter.Thumbnail> thumbnail = Optional.empty();
    private Optional<byte[]> snapshotBytes = Optional.empty();
    private Optional<String> iframe = Optional.empty();
    private Optional<String> lastPrintFile = Optional.empty();
    private Optional<CommandPPF> lastPrintCommand = Optional.empty();

    private final BlockingQueue<BambuPrinter.Message> lastMessages = new LinkedBlockingQueue<>(MAX_ITEMS);
    private final AtomicLong counter = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile List<BambuPrinter.ModuleInfo> modules = List.of();
    private OffsetDateTime nextFullStatus = OffsetDateTime.now();

    @Inject
    CamelContext context;
    @Inject
    BambuConfig bambuConfig;
    @Inject
    jakarta.enterprise.inject.Instance<com.tfyre.ftp.BambuFtp> ftpInstance;
    @Inject
    BambuPrinters printersRegistry;

    private Endpoint endpoint;
    private ProducerTemplate producerTemplate;
    private int printerError;
    private List<String> activeHmsErrors = List.of();
    private int totalLayerNum;
    private BambuConst.GCodeState gcodeState = BambuConst.GCodeState.IDLE;
    private PrinterModel model = BambuConst.PrinterModel.UNKNOWN;
    private boolean blocked;

    public BambuPrinterImpl() {
    }

    @Override
    public boolean isBlocked() {
        return blocked;
    }

    @Override
    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    private void setLastPrint(final Print print) {
        if (print.hasPrintError()) {
            printerError = print.getPrintError();
        }
        // Mirrors the dashboard's HMS badge filter: severity 1 (fatal) or 2 (serious) only. Recomputed from
        // this message's hms list (not sticky) - matches the existing, already-working badge behaviour.
        activeHmsErrors = print.getHmsList().stream()
                .filter(h -> {
                    final int severity = (int) ((h.getCode() >> 16) & 0xFFFFL);
                    return severity <= 2 && severity > 0;
                })
                .map(h -> BambuErrors.getPrinterError((int) h.getAttr())
                        .filter(s -> !s.isBlank())
                        .orElse("HMS 0x%08X".formatted(h.getAttr())))
                .distinct()
                .toList();
        if (print.hasTotalLayerNum()) {
            totalLayerNum = print.getTotalLayerNum();
        }
        if (print.hasGcodeState()) {
            gcodeState = BambuConst.GCodeState.fromValue(print.getGcodeState());
        }
        if (print.hasGcodeFile()) {
            trackLastPrintFile(print.getGcodeFile());
        }
    }

    private void trackLastPrintFile(final String fileName) {
        final String _fileName = stripSlash(fileName);
        if (_fileName.isBlank()) {
            return;
        }
        // internal plate gcode of a project print - not directly reprintable
        if (_fileName.startsWith("data/") || _fileName.contains("Metadata/")) {
            return;
        }
        final String lower = _fileName.toLowerCase();
        if (!lower.endsWith(BambuConst.FILE_3MF) && !lower.endsWith(BambuConst.FILE_GCODE)) {
            return;
        }
        lastPrintFile = Optional.of(_fileName);
    }

    /**
     * Parses the plate number from a Bambu Studio filename such as
     * "Fog Mount LED_plate_2.gcode.3mf" → 2.
     * Returns 1 if no plate suffix is found.
     */
    private static int parsePlateNumber(final String fileName) {
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("_plate_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(fileName);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    private boolean isSameFile(final String file1, final String file2) {
        final Function<String, String> baseName = f -> f.substring(f.lastIndexOf(BambuConst.PATHSEP) + 1);
        return baseName.apply(file1).equalsIgnoreCase(baseName.apply(file2));
    }

    private void addLast(final BambuPrinter.Message message) {
        while (lastMessages.remainingCapacity() <= 1) {
            lastMessages.remove();
        }
        lastMessages.add(message);

        if (message.message().hasPrint()) {
            setLastPrint(message.message().getPrint());
        }
    }

    private void buildIFrame(final String id) {
        if (!config.stream().liveView()) {
            return;
        }
        iframe = config.stream().url()
                .or(() -> bambuConfig.liveViewUrl().map(url -> "%s%s".formatted(url, id)));
        if (iframe.isEmpty()) {
            Log.errorf("%s: Live View needs [bambu.printers.XXX.stream.url] or [bambu.live-view-url] configured", name);
        }
    }

    public void setup(final Scheduler scheduler, final String name, final BambuConfig.Printer config, final Endpoint endpoint, final String id) {
        this.name = name;
        this.model = config.model();
        this.config = config;
        this.endpoint = endpoint;
        buildIFrame(id);
        scheduler.newJob("%s.requestFullStatus#%s".formatted(getClass().getName(), name))
                .setInterval("1m")
                .setTask(e -> commandFullStatusInternal(false, false))
                .schedule();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PrinterModel getModel() {
        return model;
    }

    @Override
    public Optional<BambuPrinter.Message> getStatus() {
        return status;
    }

    public void setStatus(final BambuPrinter.Message status) {
        addLast(status);
        this.status = Optional.of(status);
    }

    public void setFullStatus(final BambuPrinter.Message fullStatus) {
        setStatus(fullStatus);
        this.fullStatus = Optional.of(fullStatus);
    }

    @Override
    public List<String> getActiveHmsErrors() {
        return activeHmsErrors;
    }

    @Override
    public int getPrintError() {
        return printerError;
    }

    @Override
    public int getTotalLayerNum() {
        return totalLayerNum;
    }

    private boolean printerIsLive(final OffsetDateTime lastUpdated) {
        return lastUpdated.isAfter(OffsetDateTime.now().minus(LASTUPDATED));
    }

    @Override
    public BambuConst.GCodeState getGCodeState() {
        return status
                .filter(m -> printerIsLive(m.lastUpdated()))
                .map(m -> gcodeState)
                .orElse(BambuConst.GCodeState.OFFLINE);
    }

    @Override
    public Optional<BambuPrinter.Message> getFullStatus() {
        return fullStatus;
    }

    @Override
    public Optional<String> getIFrame() {
        return iframe;
    }

    @Override
    public Optional<BambuPrinter.Thumbnail> getThumbnail() {
        return thumbnail;
    }

    @Override
    public Collection<Message> getLastMessages() {
        return Collections.unmodifiableCollection(lastMessages);
    }

    public void setThumbnail(final BambuPrinter.Thumbnail thumbnail) {
        this.thumbnail = Optional.of(thumbnail);
        this.snapshotBytes = Optional.of(thumbnail.bytes());
    }

    @Override
    public Optional<byte[]> getSnapshotBytes() {
        return snapshotBytes;
    }

    private Optional<BambuMessage> fromJson(final String data) {
        final BambuMessage.Builder builder = BambuMessage.newBuilder();
        try {
            PARSER.merge(data, builder);
            return Optional.of(builder.build());
        } catch (InvalidProtocolBufferException ex) {
            Log.errorf(ex, "Cannot build message: %s - %s", ex.getMessage(), data);
            return Optional.empty();
        }
    }

    private Optional<String> toJson(final BambuMessage message) {
        try {
            return Optional.of(PRINTER.print(message));
        } catch (InvalidProtocolBufferException ex) {
            Log.errorf(ex, "Cannot build message: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void process(final Exchange exchange) throws Exception {
        final org.apache.camel.Message message = exchange.getMessage();
        final String body = message.getBody(String.class);
        Log.debugf("%s: Received - [%d]", name, body.length());
        Log.tracef("%s: Received RAW: %s", name, body);
        fromJson(body)
                .map(msg -> new BambuPrinter.Message(OffsetDateTime.now(), msg, body))
                .ifPresent(msg -> {
                    // Info/get_version responses carry module data — don't store as printer status
                    if (msg.message().hasInfo()) {
                        processInfo(msg.message().getInfo());
                        return;
                    }
                    if (body.length() > 2_000) {
                        setFullStatus(msg);
                    } else {
                        setStatus(msg);
                    }
                });
    }

    private void processInfo(final Info info) {
        if (info.getModuleCount() == 0) {
            return;
        }
        final List<BambuPrinter.ModuleInfo> newModules = info.getModuleList().stream()
                .map(m -> new BambuPrinter.ModuleInfo(m.getName(), m.getProjectName(), m.getHwVer(), m.getSwVer(), m.getProductName()))
                .toList();
        modules = newModules;
        Log.infof("%s: modules: %s", name,
                newModules.stream()
                        .map(m -> "[%s project:%s product:%s hw:%s sw:%s]".formatted(m.name(),
                                m.projectName() == null ? "null" : (m.projectName().isBlank() ? "blank" : m.projectName()),
                                m.productName() == null ? "null" : (m.productName().isBlank() ? "blank" : m.productName()),
                                m.hwVer(), m.swVer()))
                        .collect(Collectors.joining(" ")));

        // Auto-detect printer model from the ota module when not explicitly configured.
        // Newer firmware (H2D, P1S v2+): product_name = "Bambu Lab H2D", strip prefix to get model key.
        // Older firmware: project_name = "h2d", "p1s", etc. (direct model key).
        if (model == BambuConst.PrinterModel.UNKNOWN) {
            newModules.stream()
                    .filter(m -> "ota".equalsIgnoreCase(m.name()))
                    .map(BambuPrinter.ModuleInfo::productName)
                    .filter(p -> p != null && !p.isBlank())
                    .map(p -> p.replace("Bambu Lab ", "").trim().toLowerCase())
                    .findFirst()
                    .flatMap(p -> BambuConst.PrinterModel.fromModel(p))
                    .ifPresent(detected -> {
                        model = detected;
                        Log.infof("%s: auto-detected model %s from ota product_name", name, detected);
                    });
        }
        if (model == BambuConst.PrinterModel.UNKNOWN) {
            newModules.stream()
                    .filter(m -> "ota".equalsIgnoreCase(m.name()))
                    .map(BambuPrinter.ModuleInfo::projectName)
                    .filter(p -> p != null && !p.isBlank())
                    .findFirst()
                    .flatMap(p -> BambuConst.PrinterModel.fromModel(p.toLowerCase()))
                    .ifPresent(detected -> {
                        model = detected;
                        Log.infof("%s: auto-detected model %s from ota project_name", name, detected);
                    });
        }
    }

    private void sendData(final String data) {
        if (producerTemplate == null) {
            Log.debugf("%s: producerTemplate is null", name);
            return;
        }
        Log.debugf("%s: Sending - [%d]", name, data.length());
        Log.tracef("%s: Sending RAW: %s", name, data);
        producerTemplate.sendBody(endpoint, data);
    }

    private void logUser(final String data) {
        final String user = SecurityUtils.getPrincipal().map(p -> p.getName()).orElse("null");
        final String ip = Optional.ofNullable(VaadinSession.getCurrent()).map(vs -> vs.getBrowser().getAddress()).orElse("null");
        Log.infof("%s user[%s] ip[%s]", data, user, ip);
    }

    private void commandFullStatusInternal(final boolean fromUser, final boolean force) {
        if (!running.get()) {
            return;
        }
        if (!force && nextFullStatus.isAfter(OffsetDateTime.now())) {
            return;
        }
        nextFullStatus = OffsetDateTime.now().plus(config.mqtt().fullStatus());
        if (fromUser) {
            logUser("%s: Requesting full Status, next: %s".formatted(name, nextFullStatus));
        } else {
            Log.debugf("%s: Requesting full Status, next: %s", name, nextFullStatus);
        }
        final BambuMessage message = BambuMessage.newBuilder()
                .setPushing(
                        Pushing.newBuilder()
                                .setCommand("pushall")
                                .setPushTarget(1)
                                .setVersion(1)
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandFullStatus(final boolean force) {
        commandFullStatusInternal(true, force);
        if (force) {
            commandGetVersion();
        }
    }

    @PostConstruct
    public void postConstruct() {
        Log.debug("postConstruct");
        producerTemplate = context.createProducerTemplate();
    }

    public void start() {
        Log.debug("start");
        running.set(true);
        commandFullStatusInternal(false, false);
        commandGetVersion();
    }

    public void stop() {
        Log.debug("stop");
        running.set(false);
    }

    @Override
    public void commandLight(final BambuConst.LightMode lightMode) {
        logUser("%s: commandLight %s".formatted(name, lightMode));
        final BambuMessage message = BambuMessage.newBuilder()
                .setSystem(
                        com.tfyre.bambu.model.System.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("ledctrl")
                                .setLedNode(BambuConst.CHAMBER_LIGHT)
                                .setLedMode(lightMode.getValue())
                                .setLedOnTime(500)
                                .setLedOffTime(500)
                                .setLoopTimes(1)
                                .setIntervalTime(1000)
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandFilamentLoad(final int amsTrayId) {
        logUser("%s: commandFilamentLoad %d".formatted(name, amsTrayId));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        com.tfyre.bambu.model.Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("ams_change_filament")
                                .setTarget(amsTrayId)
                                .setCurrTemp(BambuConst.AMS_TRAY_TEMP)
                                .setTarTemp(BambuConst.AMS_TRAY_TEMP)
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandFilamentSetting(final int amsId, final int trayId, final Filament filament, final String color, final int minTemp, final int maxTemp) {
        logUser("%s: commandFilamentSetting ams[%d] tray[%d] filament[%s] color[%s] min[%d] max[%d]"
                .formatted(name, amsId, trayId, filament, color, minTemp, maxTemp));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        com.tfyre.bambu.model.Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("ams_filament_setting")
                                .setAmsId(amsId)
                                .setTrayId(trayId)
                                .setTrayInfoIdx(filament.getCode())
                                .setTrayColor(color)
                                .setNozzleTempMin(minTemp)
                                .setNozzleTempMax(maxTemp)
                                .setTrayType(filament.getType().getDescription())
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandSystemReboot() {
        logUser("%s: commandSystemReboot".formatted(name));
        final BambuMessage message = BambuMessage.newBuilder()
                .setSystem(
                        com.tfyre.bambu.model.System.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("reboot")
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandGetVersion() {
        Log.debugf("%s: Requesting version info", name);
        final BambuMessage message = BambuMessage.newBuilder()
                .setInfo(
                        Info.newBuilder()
                                .setCommand("get_version")
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public List<BambuPrinter.ModuleInfo> getModules() {
        return modules;
    }

    @Override
    public Optional<String> getFirmwareVersion() {
        return modules.stream()
                .filter(m -> "ota".equalsIgnoreCase(m.name()))
                .map(BambuPrinter.ModuleInfo::swVer)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    @Override
    public void commandAmsDry(final int amsId, final int targetTemp, final int durationHours) {
        // Clamp temp to firmware minimum (45°C) — below this the command is silently ignored.
        final int clampedTemp = Math.max(45, targetTemp);
        logUser("%s: commandAmsDry ams[%d] temp[%d] duration[%d]h".formatted(name, amsId, clampedTemp, durationHours));
        // Command: ams_filament_drying  (NOT ams_control/dry — that is the old incorrect param)
        // duration is in HOURS (not minutes).
        // cooling_temp, mode, rotate_tray, humidity are all required; omitting any causes silent failure.
        // Requires P1S firmware >= 01.08.00.00; firmware 01.09+ also requires Developer Mode.
        final String json = ("{\"print\":{\"sequence_id\":\"%d\",\"command\":\"ams_filament_drying\","
                + "\"ams_id\":%d,\"temp\":%d,\"cooling_temp\":%d,\"duration\":%d,"
                + "\"humidity\":0,\"mode\":1,\"rotate_tray\":false}}")
                .formatted(counter.incrementAndGet(), amsId, clampedTemp, clampedTemp, durationHours);
        sendData(json);
    }

    @Override
    public void commandAmsStopDry(final int amsId) {
        logUser("%s: commandAmsStopDry ams[%d]".formatted(name, amsId));
        // Stop: mode=0, temp=0, cooling_temp=40, duration=0
        final String json = ("{\"print\":{\"sequence_id\":\"%d\",\"command\":\"ams_filament_drying\","
                + "\"ams_id\":%d,\"temp\":0,\"cooling_temp\":40,\"duration\":0,"
                + "\"humidity\":0,\"mode\":0,\"rotate_tray\":false}}")
                .formatted(counter.incrementAndGet(), amsId);
        sendData(json);
    }

    @Override
    public void commandFilamentUnload() {
        logUser("%s: commandFilamentUnload".formatted(name));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        com.tfyre.bambu.model.Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("unload_filament")
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandControl(final BambuConst.CommandControl control) {
        logUser("%s: commandControl: %s".formatted(name, control));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand(control.getValue())
                                .setParam("")
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandSpeed(final BambuConst.Speed speed) {
        logUser("%s: commandSpeed: %s".formatted(name, speed));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("print_speed")
                                .setParam("%d".formatted(speed.getSpeed()))
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandClearPrinterError() {
        logUser("%s: commandClearPrinterError".formatted(name));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("clean_print_error")
                                .setSubtaskId("0")
                                .setPrintError(printerError)
                )
                .build();
        toJson(message).ifPresent(this::sendData);
    }

    private String stripSlash(final String fileName) {
        if (fileName.startsWith(BambuConst.PATHSEP)) {
            return fileName.substring(1);
        }
        return fileName;
    }

    @Override
    public void commandPrintGCodeLine(final String lines) {
        logUser("%s: commandPrintGCodeLine: [%s]".formatted(name, lines));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("gcode_line")
                                .setParam(lines.concat("\n"))
                )
                .build();

        toJson(message).ifPresent(this::sendData);
    }

    @Override
    public void commandPrintGCodeLine(final List<String> lines) {
        commandPrintGCodeLine(String.join("\n", lines));
    }

    @Override
    public void commandPrintGCodeFile(final String filename) {
        final String _filename = stripSlash(filename);
        logUser("%s: commandPrintGCode: %s".formatted(name, _filename));
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("gcode_file")
                                .setParam("/sdcard/%s".formatted(_filename))
                )
                .build();
        toJson(message).ifPresent(this::sendData);
        lastPrintCommand = Optional.empty();
        trackLastPrintFile(_filename);
    }

    @Override
    public void commandPrintProjectFile(final CommandPPF command) {
        final String _filename = stripSlash(command.filename());
        logUser("%s: commandPrintProject: %s ams[%s] timelapse[%s] bedlevelling[%s] flowCalibration[%s] amsMapping[%s]"
                .formatted(name, _filename, command.useAms(), command.timelapse(), command.bedLevelling(), command.flowCalibration(), command.amsMapping()));
        final int pos = _filename.lastIndexOf(".");
        final String taskName = pos == -1 ? _filename : _filename.substring(0, pos);
        final BambuMessage message = BambuMessage.newBuilder()
                .setPrint(
                        Print.newBuilder()
                                .setSequenceId("%d".formatted(counter.incrementAndGet()))
                                .setCommand("project_file")
                                .setParam("Metadata/plate_%d.gcode".formatted(command.plateId()))
                                .setProjectId("0")
                                .setProfileId("0")
                                .setTaskId("0")
                                .setSubtaskId("0")
                                .setSubtaskName(taskName)
                                .setFile("")
                                .setUrl("file:///sdcard/%s".formatted(_filename))
                                .setMd5("")
                                .setTimelapse(command.timelapse())
                                .setBedType("auto")
                                .setBedLevelling(command.bedLevelling())
                                .setFlowCali(command.flowCalibration())
                                .setVibrationCali(command.vibrationCalibration())
                                .setLayerInspect(true)
                                .addAllAmsMapping(command.amsMapping())
                                .setUseAms(command.useAms())
                )
                .build();
        toJson(message).ifPresent(this::sendData);
        lastPrintCommand = Optional.of(command);
        trackLastPrintFile(_filename);
    }

    @Override
    public Optional<String> getLastPrintFile() {
        return lastPrintFile.or(() -> lastPrintCommand.map(CommandPPF::filename));
    }

    /**
     * Finds the actual SD card location of a file: prints sent from Bambu Studio live in /cache/, app uploads at the root. Falls back to the bare name when
     * FTP is unavailable.
     */
    private String resolveSdFile(final String fileName) {
        if (fileName.contains(BambuConst.PATHSEP)) {
            return fileName;
        }
        final Optional<BambuPrinters.PrinterDetail> detail = printersRegistry.getPrinterDetail(name);
        if (detail.isEmpty()) {
            return fileName;
        }
        final com.tfyre.ftp.BambuFtp client = ftpInstance.get().setup(detail.get(), (total, bytes, stream) -> {
        });
        try {
            client.doConnect();
            if (!client.doLogin()) {
                return fileName;
            }
            for (final String dir : List.of("", "cache/")) {
                final boolean found = java.util.Arrays.stream(client.listFiles(BambuConst.PATHSEP + dir))
                        .anyMatch(f -> f.isFile() && fileName.equals(f.getName()));
                if (found) {
                    return dir + fileName;
                }
            }
        } catch (java.io.IOException ex) {
            Log.errorf(ex, "%s: resolveSdFile failed: %s", name, ex.getMessage());
        } finally {
            try {
                client.doClose();
            } catch (java.io.IOException ex) {
                Log.error(ex.getMessage(), ex);
            }
        }
        return fileName;
    }

    @Override
    public void commandPrintAgain() {
        final Optional<String> file = getLastPrintFile();
        if (file.isEmpty()) {
            logUser("%s: commandPrintAgain: no last print file known".formatted(name));
            return;
        }
        final String fileName = file.get();
        // prefer the original command (keeps plate, AMS mapping & options) if it matches the last printed file
        final Optional<CommandPPF> command = lastPrintCommand.filter(c -> isSameFile(c.filename(), fileName));
        if (command.isPresent()) {
            commandPrintProjectFile(command.get());
            return;
        }
        // file known only from printer reports: locate it on the SD card (root vs /cache/) before resending
        final String resolved = resolveSdFile(fileName);
        if (resolved.toLowerCase().endsWith(BambuConst.FILE_3MF)) {
            // derive the plate number from the filename (e.g. "Fog Mount LED_plate_2.gcode.3mf" → 2)
            final int plate = parsePlateNumber(resolved);
            commandPrintProjectFile(new CommandPPF(resolved, plate, config.useAms(), config.timelapse(),
                    config.bedLevelling(), config.flowCalibration(), config.vibrationCalibration(), List.of()));
            return;
        }
        commandPrintGCodeFile(resolved);
    }

}
