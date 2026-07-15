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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI-gated auto-start: when enabled for a printer, automatically starts the next queued job once the printer
 * is ready and the AI bed-clear check confirms the bed is actually empty - lights-out operation for the queue.
 * <p>
 * Safety model, in order:
 * <ul>
 * <li><b>Opt-in per printer</b>, persisted to {@code bambu-auto-start.json}. Default off.</li>
 * <li><b>Settle delay</b> ({@code bambu.auto-start-settle}, default 3m): the printer must sit in a ready
 * state that long before the first attempt - avoids racing end-of-print telemetry or a person mid-way
 * through clearing the bed.</li>
 * <li><b>Fail closed</b>: no AI answer means no start. AI disabled (config or runtime kill-switch), Ollama
 * unreachable, or no camera snapshot available all pause auto-start on that printer with a notification -
 * nothing ever prints onto an unverified bed.</li>
 * <li><b>Bed-clear gate</b>: the same check the manual "Start Next" flow uses. Not clear → one
 * {@code auto_start_blocked} notification (with the camera frame attached, so you can judge from your phone),
 * then silent retries every {@link #RETRY_INTERVAL} - clearing the bed changes nothing state-wise, so a
 * periodic recheck is what picks it up. Only ONE notification per state epoch, so a genuinely occupied bed
 * doesn't ping you every retry. A state transition or queue change also lifts the hold immediately.</li>
 * <li>FAILED counts as ready (per {@link BambuConst.GCodeState#isReady()}): if the AI confirms the bed is
 * clear after a failed print, the queue keeps moving.</li>
 * </ul>
 * Runs fully server-side (no browser needed), one attempt in flight per printer, and the actual start goes
 * through {@link PrintQueueService#startNext} - the same validated, blocked-flag-guarded path as the UI.
 */
@ApplicationScoped
public class AutoStartService {

    private static final String STORE_FILENAME = "bambu-auto-start.json";
    /** Reserved key in the per-printer settings map that holds the farm-wide master switch (default on). */
    private static final String GLOBAL_KEY = "*__global__*";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    /** How often a blocked/paused printer is silently re-checked (bed cleared? AI back up?). */
    private static final Duration RETRY_INTERVAL = Duration.ofMinutes(15);

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    PrintAiService aiService;
    @Inject
    NotificationService notificationService;

    /** printer name → auto-start enabled. Persisted. */
    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();

    /** printer name → state we last observed (to detect transitions). */
    private final Map<String, BambuConst.GCodeState> lastStates = new ConcurrentHashMap<>();
    /** printer name → when it entered its current state. */
    private final Map<String, Instant> stateSince = new ConcurrentHashMap<>();
    /** printers with an async bed-check/start attempt currently in flight. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** printer name → when it went on hold after a blocked/paused attempt (retried after RETRY_INTERVAL). */
    private final Map<String, Instant> holdUntilStateChange = new ConcurrentHashMap<>();
    /** printer name → queue size at the time we went on hold - a queue change also lifts the hold. */
    private final Map<String, Integer> holdQueueSize = new ConcurrentHashMap<>();
    /** printer name → the state epoch (stateSince value) a blocked notification was last sent for. */
    private final Map<String, Instant> notifiedEpoch = new ConcurrentHashMap<>();
    /** printer name → human-readable status for the /print-queue page. */
    private final Map<String, String> lastStatus = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Settings (persisted)
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
            enabled.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Boolean>>() {
            }));
            Log.infof("AutoStartService: loaded settings for %d printer(s) from %s", enabled.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "AutoStartService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), enabled);
        } catch (IOException ex) {
            Log.errorf(ex, "AutoStartService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    public boolean isEnabled(final String printerName) {
        return enabled.getOrDefault(printerName, Boolean.FALSE);
    }

    /**
     * Farm-wide master switch (default on). When off, NOTHING auto-starts regardless of each printer's own
     * opt-in - the per-printer selections are preserved and take effect again when this is turned back on.
     * Toggled from the Automation overview's Auto-Start button.
     */
    public boolean isGloballyEnabled() {
        return enabled.getOrDefault(GLOBAL_KEY, Boolean.TRUE);
    }

    public void setGloballyEnabled(final boolean value) {
        enabled.put(GLOBAL_KEY, value);
        save();
        Log.infof("AutoStartService: global auto-start %s", value ? "enabled" : "disabled");
    }

    public void setEnabled(final String printerName, final boolean value) {
        enabled.put(printerName, value);
        save();
        clearHold(printerName);
        lastStatus.put(printerName, value ? "waiting" : "off");
        Log.infof("AutoStartService: %s: auto-start %s", printerName, value ? "enabled" : "disabled");
    }

    /** Human-readable status for the /print-queue page, e.g. "blocked: bed not clear (12:03)". */
    public String getStatus(final String printerName) {
        if (!isGloballyEnabled()) {
            return "off (global)";
        }
        if (!isEnabled(printerName)) {
            return "off";
        }
        return lastStatus.getOrDefault(printerName, "waiting");
    }

    // -------------------------------------------------------------------------
    // The watcher
    // -------------------------------------------------------------------------

    @Scheduled(every = "1m")
    void tick() {
        final Instant now = Instant.now();
        printers.getPrinters().forEach(printer -> {
            final String name = printer.getName();
            final BambuConst.GCodeState current = printer.getGCodeState();
            final BambuConst.GCodeState previous = lastStates.put(name, current);
            if (previous != current) {
                // New state epoch: restart the settle clock and lift any hold
                stateSince.put(name, now);
                clearHold(name);
            }
            stateSince.putIfAbsent(name, now);

            if (!isGloballyEnabled()) {
                return; // master switch off - nothing auto-starts, per-printer opt-ins preserved
            }
            if (!isEnabled(name)) {
                return;
            }
            if (inFlight.contains(name)) {
                return;
            }
            if (!current.isReady() || printer.isBlocked()) {
                lastStatus.put(name, current.isPrinting() ? "waiting: printing" : "waiting: printer not ready");
                return;
            }
            final int queueSize = queueService.size(name);
            if (queueSize == 0) {
                lastStatus.put(name, "waiting: queue empty");
                return;
            }
            // A hold lifts when: the queue changed (job added/removed), or RETRY_INTERVAL passed (silent
            // recheck - a cleared bed produces no state change, this is what picks it up)
            final Instant heldAt = holdUntilStateChange.get(name);
            if (heldAt != null) {
                final boolean queueChanged = holdQueueSize.getOrDefault(name, -1) != queueSize;
                final boolean retryDue = Duration.between(heldAt, now).compareTo(RETRY_INTERVAL) >= 0;
                if (!queueChanged && !retryDue) {
                    return;
                }
                clearHold(name);
            }
            final Duration inState = Duration.between(stateSince.get(name), now);
            final Duration settle = config.autoStartSettle();
            if (inState.compareTo(settle) < 0) {
                lastStatus.put(name, "waiting: settle (%ds left)".formatted(settle.minus(inState).toSeconds()));
                return;
            }
            // Fail closed: AI must be on and answering, otherwise pause with a (one-shot) notification
            if (!aiService.isEnabled()) {
                hold(name, queueSize, "paused: AI checks are disabled/unavailable",
                        "Auto-start paused: AI checks are disabled or unavailable (%d job(s) queued)".formatted(queueSize), null);
                return;
            }
            attempt(printer, name, queueSize);
        });
    }

    private void attempt(final BambuPrinter printer, final String name, final int queueSize) {
        inFlight.add(name);
        lastStatus.put(name, "checking bed…");
        Log.infof("AutoStartService: %s: attempting auto-start (%d queued), running bed-clear check", name, queueSize);
        aiService.checkBedClear(name, "auto-start").whenComplete((result, throwable) -> {
            try {
                if (throwable != null) {
                    hold(name, queueSize, "paused: bed check failed",
                            "Auto-start paused: bed-clear check failed: %s".formatted(throwable.getMessage()), null);
                    return;
                }
                if (result == null || result.isEmpty()) {
                    // No snapshot or Ollama error - fail closed
                    hold(name, queueSize, "paused: no snapshot / AI unreachable",
                            "Auto-start paused: no camera snapshot or AI unreachable (%d job(s) queued)".formatted(queueSize), null);
                    return;
                }
                final OllamaService.AiResult ai = result.get();
                if (!ai.positive()) {
                    hold(name, queueSize, "blocked: bed not clear (%s)".formatted(HHMM.format(LocalTime.now())),
                            "Auto-start blocked: bed may not be clear - %s".formatted(truncate(ai.description(), 200)),
                            aiService.getSnapshot(name).orElse(null));
                    return;
                }
                lastStatus.put(name, "bed clear, starting…");
                queueService.startNext(name, "auto-start",
                        () -> {
                            lastStatus.put(name, "auto-started at %s".formatted(HHMM.format(LocalTime.now())));
                            final int left = queueService.size(name);
                            Log.infof("AutoStartService: %s: auto-started next job (%d left in queue)", name, left);
                            // Attach the frame the bed-clear check analyzed, so the alert shows the bed it approved
                            notificationService.notifyEvent("auto_start", name,
                                    "Auto-started next queued print - AI confirmed bed clear (%d job(s) left in queue)".formatted(left),
                                    aiService.getLastCheck(name).map(PrintAiService.CheckRecord::snapshot).orElse(null));
                        },
                        error -> hold(name, queueSize, "paused: start failed",
                                "Auto-start failed to start the job: %s".formatted(error), null));
            } finally {
                inFlight.remove(name);
            }
        });
    }

    /**
     * Records a blocked/paused status; retried silently every RETRY_INTERVAL (see tick()). The notification
     * fires at most once per state epoch, so a genuinely occupied bed doesn't re-alert on every retry.
     */
    private void hold(final String name, final int queueSize, final String status, final String message, final byte[] imageJpeg) {
        lastStatus.put(name, status);
        holdUntilStateChange.put(name, Instant.now());
        holdQueueSize.put(name, queueSize);
        final Instant epoch = stateSince.get(name);
        if (epoch != null && !epoch.equals(notifiedEpoch.get(name))) {
            notifiedEpoch.put(name, epoch);
            Log.warnf("AutoStartService: %s: %s", name, message);
            notificationService.notifyEvent("auto_start_blocked", name, message, imageJpeg);
        } else {
            Log.infof("AutoStartService: %s: still held after retry - %s", name, status);
        }
    }

    private void clearHold(final String name) {
        holdUntilStateChange.remove(name);
        holdQueueSize.remove(name);
    }

    private static String truncate(final String s, final int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

}
