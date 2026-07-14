package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Orchestrates AI-based print checks via Ollama.
 *
 * Three check types:
 *   - Bed clear: is the print bed empty? (used to gate the print queue Start Next flow)
 *   - Failure detection: spaghetti / blob / detached layer check, polled every failureCheckInterval
 *   - First layer quality: fired once per print after firstLayerDelay
 *
 * All checks are no-ops when bambu.ollama.url is not configured.
 */
@ApplicationScoped
public class PrintAiService {

    @Inject
    OllamaService ollama;
    @Inject
    BambuPrinters printers;
    @Inject
    NotificationService notificationService;
    @Inject
    ManagedExecutor executor;
    @Inject
    BambuConfig config;
    @Inject
    RtspSnapshotService rtspSnapshotService;

    /**
     * Snapshot of the last AI check result per printer.
     * <p>
     * {@code good=true} means no issue was found (bed clear, print OK, first layer OK).
     * {@code severity} mirrors the confidence: OK (green), WARN (yellow), FAIL (red).
     * {@code checkType} is one of: "bed-clear", "failure", "first-layer".
     */
    public record AiCheckResult(boolean good, OllamaService.Severity severity, String description,
            String checkType, Instant checkedAt) {}

    /**
     * Full record of a single check attempt, kept in a bounded in-memory history for the AI Settings page.
     *
     * @param at        when the check ran
     * @param printer   printer name
     * @param checkType "bed-clear", "failure" or "first-layer"
     * @param trigger   why it ran: "manual", "scheduled", "start-next" (queue gate) or "auto-start"
     * @param context   the HMS/print-error hint fed to the model, or null when the printer had no active issues
     * @param good      outcome (true = no problem found); null when the check couldn't complete
     * @param severity  display severity; null when the check couldn't complete
     * @param description the model's explanation, or the reason the check couldn't complete
     * @param snapshot  the exact JPEG frame that was analyzed; null when no snapshot could be grabbed
     */
    public record CheckRecord(Instant at, String printer, String checkType, String trigger, String context,
            Boolean good, OllamaService.Severity severity, String description, byte[] snapshot) {}

    private static final int MAX_HISTORY = 50;

    /** Newest-first bounded history of check attempts. In-memory only (images included) - resets on restart. */
    private final Deque<CheckRecord> history = new ArrayDeque<>();

    /** Most recent CheckRecord per printer (kept even for no-snapshot attempts). */
    private final Map<String, CheckRecord> lastChecks = new ConcurrentHashMap<>();

    private void record(final CheckRecord rec) {
        lastChecks.put(rec.printer(), rec);
        // Keep scheduled "couldn't run" noise (no snapshot every 5 minutes) out of the bounded history,
        // but always keep real results and anything a human or the queue explicitly asked for.
        if (rec.good() == null && "scheduled".equals(rec.trigger())) {
            return;
        }
        synchronized (history) {
            history.addFirst(rec);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
    }

    /** Newest-first copy of the recent check history. */
    public List<CheckRecord> getHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    /** The most recent check attempt for a printer, including the analyzed snapshot. */
    public Optional<CheckRecord> getLastCheck(final String printerName) {
        return Optional.ofNullable(lastChecks.get(printerName));
    }

    /** Last known GCodeState per printer, used to detect IDLE → RUNNING transitions. */
    private final Map<String, BambuConst.GCodeState> lastStates = new ConcurrentHashMap<>();

    /**
     * Printers for which a first-layer check has already been scheduled this print cycle.
     * Cleared when the printer goes back to a non-printing state.
     */
    private final Set<String> firstLayerScheduled = ConcurrentHashMap.newKeySet();

    /** Most recent AI check result per printer name. Updated after every check (scheduled or on-demand). */
    private final Map<String, AiCheckResult> lastResults = new ConcurrentHashMap<>();

    /** Printers currently undergoing an AI check. Used to show the animated indicator in the UI. */
    private final Set<String> checksInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Runtime kill-switch. When false, all scheduled checks are suspended and {@link #isEnabled()} returns false.
     * Toggled from the AI Settings view without requiring a restart.
     */
    private volatile boolean runtimeEnabled = true;

    /** Returns the most recent AI check result for the given printer, if any check has run. */
    public Optional<AiCheckResult> getLastResult(final String printerName) {
        return Optional.ofNullable(lastResults.get(printerName));
    }

    /** Returns {@code true} while an AI check is actively running for the given printer. */
    public boolean isCheckInProgress(final String printerName) {
        return checksInProgress.contains(printerName);
    }

    /** Enable or disable all AI checks at runtime without restarting. */
    public void setRuntimeEnabled(final boolean enabled) {
        this.runtimeEnabled = enabled;
        Log.infof("PrintAiService: runtime AI checks %s", enabled ? "enabled" : "disabled");
    }

    public boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    // -------------------------------------------------------------------------
    // On-demand checks (async, called from the UI)
    // -------------------------------------------------------------------------

    /**
     * Asynchronously checks whether the named printer's bed is clear.
     * Returns a CompletableFuture with the result, or empty if Ollama is not configured or
     * no snapshot is available yet.
     * <p>
     * Stores the result in {@link #lastResults} and tracks in-progress state.
     */
    public CompletableFuture<Optional<OllamaService.AiResult>> checkBedClear(final String printerName) {
        return checkBedClear(printerName, "manual");
    }

    public CompletableFuture<Optional<OllamaService.AiResult>> checkBedClear(final String printerName, final String trigger) {
        return CompletableFuture.supplyAsync(() -> {
            checksInProgress.add(printerName);
            try {
                // positive = bed IS clear = good
                return runCheck(printerName, "bed-clear", trigger,
                        (bytes, context) -> ollama.checkBedClear(bytes, context), true, true);
            } finally {
                checksInProgress.remove(printerName);
            }
        }, executor);
    }

    /**
     * Asynchronously checks for a print failure on the named printer.
     * <p>
     * Stores the result in {@link #lastResults} and tracks in-progress state.
     */
    public CompletableFuture<Optional<OllamaService.AiResult>> checkFailure(final String printerName) {
        return checkFailure(printerName, "manual");
    }

    public CompletableFuture<Optional<OllamaService.AiResult>> checkFailure(final String printerName, final String trigger) {
        return CompletableFuture.supplyAsync(() -> {
            checksInProgress.add(printerName);
            try {
                // positive = failure IS detected = bad (good = !positive)
                return runCheck(printerName, "failure", trigger,
                        (bytes, context) -> ollama.checkFailure(bytes, context), false, true);
            } finally {
                checksInProgress.remove(printerName);
            }
        }, executor);
    }

    /**
     * Asynchronously checks first-layer quality on the named printer.
     */
    public CompletableFuture<Optional<OllamaService.AiResult>> checkFirstLayer(final String printerName) {
        return CompletableFuture.supplyAsync(() ->
                runCheck(printerName, "first-layer", "manual",
                        (bytes, context) -> ollama.checkFirstLayer(bytes, context), true, false), executor);
    }

    /**
     * Shared body of every check: grab snapshot → ask the model → record the attempt (including the exact
     * frame analyzed, the trigger, and the HMS context hint) for the AI Settings page's last-check/history views.
     *
     * @param positiveMeansGood whether a positive model answer is a good outcome (bed-clear/first-layer: yes;
     *                          failure detection: no - positive means a failure WAS seen)
     * @param updateLastResult  whether to also update the dashboard status-chip result map
     */
    private Optional<OllamaService.AiResult> runCheck(final String printerName, final String checkType, final String trigger,
            final java.util.function.BiFunction<byte[], Optional<String>, Optional<OllamaService.AiResult>> check,
            final boolean positiveMeansGood, final boolean updateLastResult) {
        final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
        final Optional<byte[]> snapshot = getSnapshot(printerName);
        if (snapshot.isEmpty()) {
            record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                    null, null, "No camera snapshot available", null));
            return Optional.empty();
        }
        final Optional<OllamaService.AiResult> result = check.apply(snapshot.get(), context);
        if (result.isEmpty()) {
            record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                    null, null, "AI did not answer (Ollama error or timeout)", snapshot.get()));
            return result;
        }
        final OllamaService.AiResult r = result.get();
        final boolean good = positiveMeansGood == r.positive();
        final OllamaService.Severity severity = OllamaService.severityFor(good, r.description());
        if (updateLastResult) {
            lastResults.put(printerName, new AiCheckResult(good, severity, r.description(), checkType, Instant.now()));
        }
        record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                good, severity, r.description(), snapshot.get()));
        return result;
    }

    public boolean isEnabled() {
        return ollama.isEnabled() && runtimeEnabled;
    }

    // -------------------------------------------------------------------------
    // Scheduled: failure detection poll
    // -------------------------------------------------------------------------

    @Scheduled(every = "${bambu.ollama.failure-check-interval:5m}")
    void watchForFailures() {
        if (!isEnabled()) {
            // isEnabled() (not just ollama.isEnabled()) so the /ai-settings runtime kill-switch
            // actually suspends scheduled checks too, as its Javadoc promises.
            return;
        }
        printers.getPrinters().stream()
                .filter(p -> p.getGCodeState().isPrinting())
                .forEach(p -> executor.submit(() -> runFailureCheck(p)));
    }

    private void runFailureCheck(final BambuPrinter printer) {
        final String name = printer.getName();
        checksInProgress.add(name);
        try {
            // positive = failure detected = bad
            runCheck(name, "failure", "scheduled", (bytes, context) -> ollama.checkFailure(bytes, context), false, true)
                    .filter(OllamaService.AiResult::positive)
                    .ifPresent(result -> {
                        Log.warnf("PrintAiService: %s: failure detected — %s", name, result.description());
                        notificationService.notifyEvent("failure_detected", name,
                                "Possible print failure detected: " + truncate(result.description(), 200),
                                getLastCheck(name).map(CheckRecord::snapshot).orElse(null));
                    });
        } finally {
            checksInProgress.remove(name);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled: state transition watcher for first-layer check
    // -------------------------------------------------------------------------

    @Scheduled(every = "30s")
    void watchStateTransitions() {
        if (!isEnabled()) {
            return;
        }
        printers.getPrinters().forEach(printer -> {
            final BambuConst.GCodeState current = printer.getGCodeState();
            final BambuConst.GCodeState previous = lastStates.put(printer.getName(), current);

            if (previous == null) {
                return;
            }

            // IDLE / FINISH / FAILED → RUNNING: new print started, schedule first-layer check
            if (!previous.isPrinting() && current == BambuConst.GCodeState.RUNNING) {
                scheduleFirstLayerCheck(printer.getName());
            }

            // Print ended: clear the first-layer guard so the next print gets checked too
            if (previous.isPrinting() && !current.isPrinting()) {
                firstLayerScheduled.remove(printer.getName());
            }
        });
    }

    private void scheduleFirstLayerCheck(final String printerName) {
        if (!firstLayerScheduled.add(printerName)) {
            return; // already scheduled for this print cycle
        }
        final long delayMs = config.ollama().firstLayerDelay().toMillis();
        Log.debugf("PrintAiService: %s: first-layer check scheduled in %dms", printerName, delayMs);

        executor.submit(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                firstLayerScheduled.remove(printerName);
                return;
            }

            // Only run if still printing
            final Optional<BambuPrinter> stillPrinting = printers.getPrinters().stream()
                    .filter(p -> p.getName().equals(printerName) && p.getGCodeState().isPrinting())
                    .findFirst();

            if (stillPrinting.isEmpty()) {
                Log.debugf("PrintAiService: %s: print ended before first-layer check, skipping", printerName);
                return;
            }

            checksInProgress.add(printerName);
            try {
                // positive = first layer is good
                runCheck(printerName, "first-layer", "scheduled",
                        (bytes, context) -> ollama.checkFirstLayer(bytes, context), true, true)
                        .ifPresent(result -> {
                            if (!result.positive()) {
                                Log.warnf("PrintAiService: %s: first layer issue — %s", printerName, result.description());
                                notificationService.notifyEvent("first_layer_issue", printerName,
                                        "First layer issue detected: " + truncate(result.description(), 200),
                                        getLastCheck(printerName).map(CheckRecord::snapshot).orElse(null));
                            } else {
                                Log.infof("PrintAiService: %s: first layer OK — %s", printerName, result.description());
                            }
                        });
            } finally {
                checksInProgress.remove(printerName);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Optional<BambuPrinter> findPrinter(final String printerName) {
        return printers.getPrinters().stream()
                .filter(p -> p.getName().equals(printerName))
                .findFirst();
    }

    /**
     * Current camera frame for a printer (port-6000 cache, or an ffmpeg RTSPS grab on X1C/X1E/H2D).
     * Public so AutoStartService can attach the frame to its "blocked: bed not clear" notification.
     */
    public Optional<byte[]> getSnapshot(final String printerName) {
        final Optional<BambuPrinters.PrinterDetail> detail = printers.getPrintersDetail().stream()
                .filter(pd -> pd.name().equals(printerName))
                .findFirst();
        final Optional<byte[]> cached = detail.flatMap(pd -> pd.printer().getSnapshotBytes());
        if (cached.isPresent()) {
            return cached;
        }
        // X1C/X1E/H2D don't push raw JPEGs over the port-6000 mechanism that populates the above (see
        // BambuPrinterStream's warning) - fall back to grabbing a frame via ffmpeg instead (RtspSnapshotService
        // picks the internal mediamtx relay vs. a direct printer connection - see its class Javadoc for why
        // that routing matters), unless remote view is disabled for this printer entirely.
        return detail.filter(pd -> config.remoteView() && pd.config().stream().enabled())
                .flatMap(pd -> rtspSnapshotService.grabFrame(pd.id(), pd.name(), pd.config()));
    }

    /**
     * Builds a short status-context string (active HMS alerts + any legacy printer error code) to feed the
     * AI prompt as a hint, e.g. so a nozzle-clog HMS alert nudges the failure check to weight what it sees
     * accordingly. Empty when the printer has no active issues.
     */
    private Optional<String> buildContext(final BambuPrinter printer) {
        final List<String> issues = new ArrayList<>(printer.getActiveHmsErrors());
        if (printer.getPrintError() != 0) {
            BambuErrors.getPrinterError(printer.getPrintError())
                    .filter(s -> !s.isBlank())
                    .ifPresent(issues::add);
        }
        return issues.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", issues));
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

}
