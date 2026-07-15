package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Controls Tasmota smart plugs assigned to printers (bambu.printers.XXX.tasmota=http://ip).
 * Also runs the opt-in idle auto-off watcher: a printer that has sat ready with an empty queue for the
 * configured number of minutes gets its plug switched off (per-printer setting on the Tasmota Settings page,
 * persisted to {@code bambu-tasmota-autooff.json}; 0 = disabled).
 */
@ApplicationScoped
public class TasmotaService {

    private static final String STORE_FILENAME = "bambu-tasmota-autooff.json";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    ManagedExecutor executor;
    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    NotificationService notificationService;

    /** printer name → auto-off delay in minutes (absent or <=0 = disabled). Persisted. */
    private final Map<String, Integer> autoOffMinutes = new ConcurrentHashMap<>();
    /** printer name → when it entered its current gcode state (for the idle timer). */
    private final Map<String, BambuConst.GCodeState> lastStates = new ConcurrentHashMap<>();
    private final Map<String, Instant> stateSince = new ConcurrentHashMap<>();
    /** state-epoch markers so a power-off is only sent once per idle period. */
    private final Map<String, Instant> offSentForEpoch = new ConcurrentHashMap<>();

    /**
     * Identifies a Tasmota outlet.
     *
     * @param baseUrl the base URL of the Tasmota device, e.g. {@code http://192.168.1.50}
     * @param channel outlet channel: 0 (or absent) = single-outlet device;
     *                1, 2, 3… = specific outlet on a multi-outlet power strip
     */
    public record TasmotaTarget(String baseUrl, int channel) {

        /** Build a target from config values. */
        public static TasmotaTarget of(final String baseUrl, final Optional<Integer> channel) {
            return new TasmotaTarget(baseUrl, channel.orElse(0));
        }

        /**
         * The Tasmota power command name for this outlet: {@code "Power"} for single-outlet,
         * {@code "Power1"}, {@code "Power2"}, … for multi-outlet strips.
         */
        public String powerCommand() {
            return channel > 0 ? "Power" + channel : "Power";
        }

        /** Human-readable label, e.g. {@code "http://…"} or {@code "http://… ch2"}. */
        public String label() {
            return channel > 0 ? "%s ch%d".formatted(baseUrl, channel) : baseUrl;
        }
    }

    // -------------------------------------------------------------------------
    // Idle auto-off
    // -------------------------------------------------------------------------

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            autoOffMinutes.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Integer>>() {
            }));
            Log.infof("TasmotaService: auto-off settings loaded from %s", path);
        } catch (IOException ex) {
            Log.errorf(ex, "TasmotaService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), autoOffMinutes);
        } catch (IOException ex) {
            Log.errorf(ex, "TasmotaService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    public int getAutoOffMinutes(final String printerName) {
        return autoOffMinutes.getOrDefault(printerName, 0);
    }

    public void setAutoOffMinutes(final String printerName, final int minutes) {
        autoOffMinutes.put(printerName, Math.max(0, minutes));
        save();
        Log.infof("TasmotaService: %s auto-off %s", printerName,
                minutes > 0 ? "after %d idle minute(s)".formatted(minutes) : "disabled");
    }

    /**
     * Switches a printer's plug off once it has sat ready (finished/idle/failed - NOT printing or paused)
     * with an empty queue for the configured time. One attempt per idle period; turning the printer back on
     * (state change) re-arms it. Skipped entirely while jobs are queued, so auto-start always wins.
     */
    @Scheduled(every = "1m")
    void watchAutoOff() {
        final Instant now = Instant.now();
        printers.getPrintersDetail().forEach(detail -> {
            final String name = detail.name();
            final BambuConst.GCodeState current = detail.printer().getGCodeState();
            final BambuConst.GCodeState previous = lastStates.put(name, current);
            if (previous != current) {
                stateSince.put(name, now);
            }
            stateSince.putIfAbsent(name, now);

            final int minutes = getAutoOffMinutes(name);
            if (minutes <= 0 || detail.config().tasmota().isEmpty()) {
                return;
            }
            if (!current.isReady() || queueService.size(name) > 0) {
                return;
            }
            final Instant epoch = stateSince.get(name);
            if (Duration.between(epoch, now).toMinutes() < minutes) {
                return;
            }
            if (epoch.equals(offSentForEpoch.get(name))) {
                return; // already switched off for this idle period
            }
            offSentForEpoch.put(name, epoch);
            final TasmotaTarget target = TasmotaTarget.of(detail.config().tasmota().get(), detail.config().tasmotaChannel());
            Log.infof("TasmotaService: %s idle for %d min with empty queue - switching plug off", name, minutes);
            power(target, false,
                    () -> notificationService.notifyEvent("tasmota_off", name,
                            "Plug switched OFF automatically after %d idle minute(s) with an empty queue".formatted(minutes)),
                    err -> Log.errorf("TasmotaService: %s auto-off failed: %s", name, err));
        });
    }

    private String normalizeUrl(final String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Queries the current power state. Callback receives Optional.of(true/false) for ON/OFF,
     * or Optional.empty() on error. Runs on a worker thread — wrap callback with ui.access().
     */
    public void getStatus(final TasmotaTarget target, final Consumer<Optional<Boolean>> callback) {
        final String url = "%s/cm?cmnd=%s".formatted(normalizeUrl(target.baseUrl()), target.powerCommand());
        executor.submit(() -> {
            try {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    // Single: {"POWER":"ON"}  Multi: {"POWER2":"ON"} — just look for the value
                    final boolean on = response.body().toUpperCase().contains("\"ON\"");
                    callback.accept(Optional.of(on));
                } else {
                    Log.warnf("TasmotaService: getStatus HTTP %d from %s", response.statusCode(), url);
                    callback.accept(Optional.empty());
                }
            } catch (Exception ex) {
                Log.errorf(ex, "TasmotaService: getStatus %s: %s", url, ex.getMessage());
                callback.accept(Optional.empty());
            }
        });
    }

    /**
     * Sends a power command. Callbacks run on a worker thread - wrap with ui.access().
     */
    public void power(final TasmotaTarget target, final boolean on, final Runnable onSuccess, final Consumer<String> onError) {
        final String url = "%s/cm?cmnd=%s".formatted(
                normalizeUrl(target.baseUrl()),
                URLEncoder.encode(target.powerCommand() + " " + (on ? "On" : "Off"), StandardCharsets.UTF_8));
        executor.submit(() -> {
            try {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Log.infof("TasmotaService: %s -> %s", url, response.body());
                    onSuccess.run();
                } else {
                    onError.accept("HTTP %d".formatted(response.statusCode()));
                }
            } catch (Exception ex) {
                Log.errorf(ex, "TasmotaService: %s: %s", url, ex.getMessage());
                onError.accept(ex.getMessage());
            }
        });
    }

}
