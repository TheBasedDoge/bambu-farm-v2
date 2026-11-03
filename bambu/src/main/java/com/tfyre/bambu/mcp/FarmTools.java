package com.tfyre.bambu.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.model.Print;
import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.FarmStatusService;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Phase 1 farm tools (docs/MCP-PLAN.md): {@code farm_status} and {@code health_check}. Read-only; both answer from
 * the same in-process services the UI uses.
 */
@Singleton
public class FarmTools {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** A printer with no telemetry for this long counts as offline. */
    private static final Duration STALE = Duration.ofMinutes(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    FarmStatusService farmStatus;
    @Inject
    BedClearService bedClear;
    @Inject
    AutoStartService autoStart;
    @Inject
    PrintAiService ai;
    @Inject
    OllamaService ollama;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    EtsyOrderPollingService etsy;
    @Inject
    EbayOrderPollingService ebay;
    @Inject
    McpAudit audit;
    @Inject
    ObjectMapper mapper;

    // ---------------------------------------------------------------------------------------------------------
    // farm_status
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "farm_status", description = """
            Live state of every printer in the farm plus the one-line farm headline the app shows (why nothing is \
            starting, next free printer, jobs waiting). Per printer: connection, gcode state, job, percent, layer, \
            minutes left and finish clock time, temperatures, AMS trays (type and colour per slot), active HMS / \
            print errors, bed-cleared flag, own queue size, auto-start opt-in and the last AI check verdict.""",
            annotations = @Tool.Annotations(title = "Farm status", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse farmStatus() {
        return audit.run("farm_status", Map.of(), () -> ToolResponse.success(json(buildFarmStatus())));
    }

    Map<String, Object> buildFarmStatus() {
        final FarmStatusService.Status status = farmStatus.status();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", LocalDateTime.now().format(LOCAL));
        out.put("tone", status.tone().name());
        out.put("headline", status.headline());
        putIf(out, "detail", status.detail());
        out.put("poolJobs", dispatch.size());
        out.put("autoStartGlobal", autoStart.isGloballyEnabled());
        out.put("aiChecks", ai.isEnabled() ? "on" : ollama.isEnabled() ? "paused" : "off (no Ollama URL)");

        final List<Map<String, Object>> list = new ArrayList<>();
        printers.getPrintersDetail().stream()
                .sorted(Comparator.comparing(BambuPrinters.PrinterDetail::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(d -> list.add(printer(d)));
        out.put("printers", list);
        return out;
    }

    private Map<String, Object> printer(final BambuPrinters.PrinterDetail d) {
        final BambuPrinter p = d.printer();
        final String name = d.name();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("model", String.valueOf(p.getModel()));
        final Optional<BambuPrinter.Message> last = p.getStatus();
        final boolean stale = last.map(s -> s.lastUpdated().toInstant().isBefore(Instant.now().minus(STALE))).orElse(true);
        m.put("connected", d.isRunning() && !stale);
        last.ifPresent(s -> m.put("lastTelemetry", ago(s.lastUpdated().toInstant())));

        final FarmStatusService.PrinterLine line = farmStatus.line(d);
        m.put("status", line.tone().name() + ": " + line.text());
        final BambuConst.GCodeState state = p.getGCodeState();
        m.put("state", state.name());
        p.getSubtaskName().filter(s -> !s.isBlank()).ifPresent(s -> m.put("job", s));
        if (state.isPrinting() || state == BambuConst.GCodeState.PAUSE) {
            m.put("percent", Math.max(0, p.getProgressPercent()));
            m.put("layer", "%d/%d".formatted(p.getLayerNum(), p.getTotalLayerNum()));
            final int left = p.getRemainingMinutes();
            if (left > 0) {
                m.put("minutesLeft", left);
                m.put("finishAt", LocalTime.now().plusMinutes(left).format(HM));
            }
        }
        temps(p).ifPresent(t -> m.put("temps", t));

        final int err = p.getPrintError();
        if (err != 0) {
            m.put("printError", Integer.toHexString(err));
        }
        final List<String> hms = p.getActiveHmsErrors();
        if (!hms.isEmpty()) {
            m.put("hms", hms);
        }
        m.put("bedCleared", bedClear.isCleared(name));
        m.put("queued", line.queued());
        m.put("autoStart", autoStart.isEnabled(name));
        m.put("ams", trays(p));
        if (p.getActiveTrayId() >= 0 && p.getActiveTrayId() != 255) {
            m.put("activeTray", trayLabel(p.getActiveTrayId()));
        }
        ai.getLastResult(name).ifPresent(r -> {
            final Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", r.checkType());
            a.put("good", r.good());
            a.put("severity", String.valueOf(r.severity()));
            a.put("description", truncate(r.description(), 240));
            a.put("at", ago(r.checkedAt()));
            m.put("lastAiCheck", a);
        });
        return m;
    }

    private static Optional<Map<String, Object>> temps(final BambuPrinter p) {
        final Optional<Print> print = p.getFullStatus().filter(s -> s.message().hasPrint()).map(s -> s.message().getPrint());
        if (print.isEmpty()) {
            return Optional.empty();
        }
        final Print pr = print.get();
        final Map<String, Object> t = new LinkedHashMap<>();
        if (pr.hasNozzleTemper()) {
            t.put("nozzle", Math.round(pr.getNozzleTemper()));
        }
        if (pr.hasBedTemper()) {
            t.put("bed", Math.round(pr.getBedTemper()));
        }
        if (pr.hasChamberTemper()) {
            t.put("chamber", Math.round(pr.getChamberTemper()));
        }
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }

    private static Map<String, String> trays(final BambuPrinter p) {
        final Map<Integer, String> types = p.getAmsTrayTypes();
        final Map<Integer, String> colors = p.getAmsTrayColors();
        final Map<String, String> out = new LinkedHashMap<>();
        new TreeMap<>(types).forEach((idx, type) -> {
            final String color = colors.getOrDefault(idx, "");
            out.put(trayLabel(idx), color.isBlank() ? type : type + " #" + color);
        });
        return out;
    }

    /**
     * A1..D4 for the four-slot AMS units, "Ext" for the external spool, HT1.. for AMS HT units. An AMS HT shows up
     * two ways: as the tray number the printer and mappings use (128 = the first HT) and as the telemetry key
     * amsId*4 + tray (512 = the first HT); the ranges don't overlap, so both are read.
     */
    static String trayLabel(final int idx) {
        if (idx == BambuConst.AMS_TRAY_VIRTUAL) {
            return "Ext";
        }
        if (idx >= 0 && idx < 16) {
            return "%c%d".formatted((char) ('A' + idx / 4), idx % 4 + 1);
        }
        if (idx >= 128 && idx < 136) {
            return "HT" + (idx - 127);
        }
        if (idx >= 512 && idx < 544 && idx % 4 == 0) {
            return "HT" + ((idx - 512) / 4 + 1);
        }
        return "tray " + idx;
    }

    // ---------------------------------------------------------------------------------------------------------
    // health_check
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "health_check", description = """
            Health of the BambuFarm app itself: uptime, WARN/ERROR counts from the app log grouped by message \
            (with the last few errors), Vert.x blocked-thread warnings, clock offset against an internet time \
            source, TLS certificate expiry, free disk on the data folder, the newest nightly backup, the last \
            Etsy/eBay poll and error, whether Ollama answers, and printers with no recent telemetry.""",
            annotations = @Tool.Annotations(title = "Health check", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse healthCheck(
            @ToolArg(description = "How many hours of log to scan. 0 (default) = since the app last started.",
                    required = false, defaultValue = "0") final int hours) {
        return audit.run("health_check", Map.of("hours", hours), () -> ToolResponse.success(json(buildHealth(hours))));
    }

    Map<String, Object> buildHealth(final int hours) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        out.put("now", LocalDateTime.now().format(LOCAL));
        out.put("started", LocalDateTime.ofInstant(started, ZoneId.systemDefault()).format(LOCAL));
        out.put("uptime", human(Duration.between(started, Instant.now())));

        final LocalDateTime since = hours > 0 ? LocalDateTime.now().minusHours(hours)
                : LocalDateTime.ofInstant(started, ZoneId.systemDefault());
        out.put("log", logs(since));
        out.put("printersOffline", offline());
        out.put("dispatch", dispatchInfo());
        out.put("markets", Map.of("etsy", market(etsy.getLastPolled(), etsy.getLastError()),
                "ebay", market(ebay.getLastPolled(), ebay.getLastError())));
        out.put("ollama", ollamaInfo());
        out.put("clock", clock());
        out.put("tls", tls());
        out.put("disk", disk());
        out.put("backup", backup());
        return out;
    }

    private Map<String, Object> logs(final LocalDateTime since) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final Path log = logFile();
        m.put("file", log.toString());
        m.put("since", since.format(LOCAL));
        try {
            final LogScan.Result r = LogScan.scan(LogScan.read(LogScan.files(log, since)), since, 15, 5);
            m.put("lines", r.lines());
            m.put("errors", r.errors());
            m.put("warnings", r.warnings());
            m.put("blockedThreadWarnings", r.blockedThread());
            final List<String> groups = new ArrayList<>();
            r.groups().forEach(g -> groups.add("%s x%d [%s] %s (last %s)".formatted(g.level(), g.count(), g.logger(),
                    g.shape(), g.last().format(LOCAL))));
            m.put("groups", groups);
            final List<String> lastErrors = new ArrayList<>();
            r.lastErrors().forEach(e -> lastErrors.add("%s [%s] %s".formatted(e.at().format(LOCAL), e.logger(),
                    truncate(e.message(), 300))));
            m.put("lastErrors", lastErrors);
        } catch (IOException | RuntimeException ex) {
            m.put("error", "could not read the log: " + ex.getMessage());
        }
        return m;
    }

    private static Path logFile() {
        final String path = ConfigProvider.getConfig().getOptionalValue("quarkus.log.file.path", String.class)
                .orElse("application.log");
        return Path.of(path).toAbsolutePath();
    }

    private List<String> offline() {
        final List<String> out = new ArrayList<>();
        for (final BambuPrinters.PrinterDetail d : printers.getPrintersDetail()) {
            final Optional<BambuPrinter.Message> last = d.printer().getStatus();
            if (!d.isRunning()) {
                out.add(d.name() + ": not connected");
            } else if (last.isEmpty()) {
                out.add(d.name() + ": no telemetry yet");
            } else if (last.get().lastUpdated().toInstant().isBefore(Instant.now().minus(STALE))) {
                out.add(d.name() + ": last telemetry " + ago(last.get().lastUpdated().toInstant()));
            }
        }
        return out;
    }

    private Map<String, Object> dispatchInfo() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolJobs", dispatch.size());
        dispatch.getBlockedStatus().ifPresent(s -> m.put("blocked", s));
        dispatch.getNextTickIn().ifPresent(t -> m.put("nextTickIn", human(t)));
        m.put("autoStartGlobal", autoStart.isGloballyEnabled());
        return m;
    }

    private static Map<String, Object> market(final Optional<Instant> lastPolled, final Optional<String> lastError) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastPolled", lastPolled.map(FarmTools::ago).orElse("never since start"));
        lastError.filter(e -> !e.isBlank()).ifPresent(e -> m.put("lastError", truncate(e, 300)));
        return m;
    }

    private Map<String, Object> ollamaInfo() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiChecks", ai.isEnabled() ? "on" : ollama.isEnabled() ? "paused" : "off");
        final Optional<String> url = config.ollama().url().filter(u -> !u.isBlank());
        if (url.isEmpty()) {
            m.put("reachable", "no URL configured");
            return m;
        }
        final long start = System.nanoTime();
        try {
            final HttpResponse<Void> r = http().send(HttpRequest.newBuilder(URI.create(stripSlash(url.get()) + "/api/tags"))
                    .timeout(PROBE_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.discarding());
            m.put("reachable", r.statusCode() == 200 ? "yes" : "HTTP " + r.statusCode());
            m.put("ms", (System.nanoTime() - start) / 1_000_000);
        } catch (IOException ex) {
            m.put("reachable", "no: " + ex.getClass().getSimpleName() + " " + String.valueOf(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            m.put("reachable", "interrupted");
        }
        m.put("model", config.ollama().model());
        return m;
    }

    /** Offset of this clock from the HTTP Date header of a well-known site (1 s resolution). Report only. */
    private Map<String, Object> clock() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", ZoneId.systemDefault().getId());
        try {
            final long before = System.currentTimeMillis();
            final HttpResponse<Void> r = http().send(HttpRequest.newBuilder(URI.create(config.mcp().clockCheckUrl()))
                    .timeout(PROBE_TIMEOUT).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            final long after = System.currentTimeMillis();
            final Optional<String> date = r.headers().firstValue("Date");
            if (date.isEmpty()) {
                m.put("offset", "unknown: no Date header");
                return m;
            }
            final long server = ZonedDateTime.parse(date.get(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            final long offsetSec = Math.round(((before + after) / 2.0 - server) / 1000.0);
            m.put("offsetSeconds", offsetSec);
            m.put("verdict", Math.abs(offsetSec) <= 5 ? "ok" : offsetSec > 0 ? "app clock is AHEAD" : "app clock is BEHIND");
        } catch (IOException | RuntimeException ex) {
            m.put("offset", "unknown: " + ex.getClass().getSimpleName() + " " + String.valueOf(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            m.put("offset", "interrupted");
        }
        return m;
    }

    /**
     * The certificate nginx serves, read from the compose network with the public host name as SNI. The trust
     * manager accepts anything because this only reads the certificate - no request is sent over the connection.
     */
    private Map<String, Object> tls() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final String target = config.mcp().tlsCheck();
        final int colon = target.lastIndexOf(':');
        final String host = colon > 0 ? target.substring(0, colon) : target;
        final int port = colon > 0 ? Integer.parseInt(target.substring(colon + 1)) : 443;
        final String sni = config.notifications().baseUrl().filter(u -> !u.isBlank())
                .map(u -> URI.create(u).getHost()).filter(h -> h != null && !h.isBlank()).orElse(host);
        m.put("checked", target + " as " + sni);
        try {
            final SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new ReadOnlyTrust() }, null);
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                s.connect(new InetSocketAddress(host, port), (int) PROBE_TIMEOUT.toMillis());
                s.setSoTimeout((int) PROBE_TIMEOUT.toMillis());
                final SSLParameters params = s.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(sni)));
                s.setSSLParameters(params);
                s.startHandshake();
                final X509Certificate cert = (X509Certificate) s.getSession().getPeerCertificates()[0];
                final Instant notAfter = cert.getNotAfter().toInstant();
                m.put("subject", cert.getSubjectX500Principal().getName());
                m.put("notAfter", LocalDateTime.ofInstant(notAfter, ZoneId.systemDefault()).format(LOCAL));
                final long days = Duration.between(Instant.now(), notAfter).toDays();
                m.put("daysLeft", days);
                m.put("verdict", days < 0 ? "EXPIRED" : days < 14 ? "renew soon" : "ok");
            }
        } catch (Exception ex) {
            m.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return m;
    }

    private Map<String, Object> disk() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final Path data = dataDir();
        try {
            final FileStore store = Files.getFileStore(data);
            final long free = store.getUsableSpace();
            final long total = store.getTotalSpace();
            m.put("dataDir", data.toString());
            m.put("freeGb", Math.round(free / 1e8) / 10.0);
            m.put("totalGb", Math.round(total / 1e8) / 10.0);
            m.put("freePercent", total > 0 ? Math.round(free * 100.0 / total) : null);
        } catch (IOException ex) {
            m.put("error", ex.getMessage());
        }
        return m;
    }

    private Map<String, Object> backup() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final Path dir = config.backup().dir().filter(d -> !d.isBlank()).map(Path::of).orElseGet(() -> dataDir().resolve("backups"));
        m.put("dir", dir.toString());
        if (!Files.isDirectory(dir)) {
            m.put("newest", "none (folder missing)");
            return m;
        }
        try (Stream<Path> files = Files.list(dir)) {
            final Optional<Path> newest = files.filter(f -> f.getFileName().toString().endsWith(".zip"))
                    .max(Comparator.comparing(f -> f.getFileName().toString()));
            if (newest.isEmpty()) {
                m.put("newest", "none");
            } else {
                final Instant modified = Files.getLastModifiedTime(newest.get()).toInstant();
                m.put("newest", newest.get().getFileName().toString());
                m.put("written", ago(modified));
                m.put("verdict", Duration.between(modified, Instant.now()).toHours() <= 30 ? "ok" : "STALE - no backup in the last 30 h");
            }
        } catch (IOException ex) {
            m.put("error", ex.getMessage());
        }
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------

    private Path dataDir() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent();
    }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private String json(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialise the result: " + ex.getMessage(), ex);
        }
    }

    private static void putIf(final Map<String, Object> m, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }

    static String ago(final Instant at) {
        final Duration d = Duration.between(at, Instant.now());
        final String local = LocalDateTime.ofInstant(at, ZoneId.systemDefault()).format(LOCAL);
        return d.isNegative() ? local + " (in the future: " + human(d.negated()) + ")" : local + " (" + human(d) + " ago)";
    }

    static String human(final Duration d) {
        final long s = Math.max(0, d.toSeconds());
        if (s < 120) {
            return s + "s";
        }
        if (s < 7200) {
            return (s / 60) + "m";
        }
        if (s < 172800) {
            return "%dh %dm".formatted(s / 3600, s % 3600 / 60);
        }
        return "%dd %dh".formatted(s / 86400, s % 86400 / 3600);
    }

    static String truncate(final String s, final int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String stripSlash(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Accepts any server certificate - used only to read the certificate's dates, never to send data. */
    private static final class ReadOnlyTrust implements X509TrustManager {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
