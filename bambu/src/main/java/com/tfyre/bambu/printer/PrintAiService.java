package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
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
        return CompletableFuture.supplyAsync(() -> {
            checksInProgress.add(printerName);
            try {
                final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
                final Optional<OllamaService.AiResult> result = getSnapshot(printerName)
                        .flatMap(bytes -> ollama.checkBedClear(bytes, context));
                // positive = bed IS clear = good
                result.ifPresent(r -> {
                    final boolean good = r.positive();
                    lastResults.put(printerName, new AiCheckResult(good,
                            OllamaService.severityFor(good, r.description()), r.description(), "bed-clear", Instant.now()));
                });
                return result;
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
        return CompletableFuture.supplyAsync(() -> {
            checksInProgress.add(printerName);
            try {
                final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
                final Optional<OllamaService.AiResult> result = getSnapshot(printerName)
                        .flatMap(bytes -> ollama.checkFailure(bytes, context));
                // positive = failure IS detected = bad (good = !positive)
                result.ifPresent(r -> {
                    final boolean good = !r.positive();
                    lastResults.put(printerName, new AiCheckResult(good,
                            OllamaService.severityFor(good, r.description()), r.description(), "failure", Instant.now()));
                });
                return result;
            } finally {
                checksInProgress.remove(printerName);
            }
        }, executor);
    }

    /**
     * Asynchronously checks first-layer quality on the named printer.
     */
    public CompletableFuture<Optional<OllamaService.AiResult>> checkFirstLayer(final String printerName) {
        return CompletableFuture.supplyAsync(() -> {
            final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
            return getSnapshot(printerName).flatMap(bytes -> ollama.checkFirstLayer(bytes, context));
        }, executor);
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
        checksInProgress.add(printer.getName());
        try {
            final Optional<String> context = buildContext(printer);
            getSnapshot(printer.getName()).ifPresent(bytes ->
                    ollama.checkFailure(bytes, context).ifPresent(result -> {
                        // positive = failure detected = bad
                        final boolean good = !result.positive();
                        lastResults.put(printer.getName(),
                                new AiCheckResult(good, OllamaService.severityFor(good, result.description()), result.description(), "failure", Instant.now()));
                        if (result.positive()) {
                            Log.warnf("PrintAiService: %s: failure detected — %s", printer.getName(), result.description());
                            notificationService.notifyEvent("failure_detected", printer.getName(),
                                    "Possible print failure detected: " + truncate(result.description(), 200), bytes);
                        }
                    }));
        } finally {
            checksInProgress.remove(printer.getName());
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
                final Optional<String> context = buildContext(stillPrinting.get());
                getSnapshot(printerName).ifPresent(bytes ->
                        ollama.checkFirstLayer(bytes, context).ifPresent(result -> {
                            // positive = first layer is good
                            final boolean good = result.positive();
                            lastResults.put(printerName,
                                    new AiCheckResult(good, OllamaService.severityFor(good, result.description()), result.description(), "first-layer", Instant.now()));
                            if (!result.positive()) {
                                Log.warnf("PrintAiService: %s: first layer issue — %s", printerName, result.description());
                                notificationService.notifyEvent("first_layer_issue", printerName,
                                        "First layer issue detected: " + truncate(result.description(), 200), bytes);
                            } else {
                                Log.infof("PrintAiService: %s: first layer OK — %s", printerName, result.description());
                            }
                        }));
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

    private Optional<byte[]> getSnapshot(final String printerName) {
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
