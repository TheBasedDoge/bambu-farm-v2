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
    @Inject
    BedReferenceService bedReference;
    @Inject
    BedDiffService bedDiff;

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
     * @param pixelDiff measured pixel-diff vs the empty-bed reference (bed-clear checks only); null when the
     *                  backstop is off, has no reference, or couldn't measure. Recorded on every check so the
     *                  history doubles as the calibration data for the threshold.
     */
    public record CheckRecord(Instant at, String printer, String checkType, String trigger, String context,
            Boolean good, OllamaService.Severity severity, String description, byte[] snapshot, Double pixelDiff) {

        /** Back-compat: records created before the pixel-diff backstop existed. */
        public CheckRecord(final Instant at, final String printer, final String checkType, final String trigger,
                final String context, final Boolean good, final OllamaService.Severity severity,
                final String description, final byte[] snapshot) {
            this(at, printer, checkType, trigger, context, good, severity, description, snapshot, null);
        }
    }

    private static final int MAX_HISTORY = 50;
    /** How often to sample the layer number while waiting for the first layer to be laid down. */
    private static final long FIRST_LAYER_POLL_MS = 10_000;

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
        final Optional<BambuConst.LightMode> priorLight = illuminateForCheck(printerName);
        final Optional<byte[]> snapshot = getSnapshot(printerName);
        restoreLight(printerName, priorLight);
        if (snapshot.isEmpty()) {
            record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                    null, null, "No camera snapshot available", null));
            return Optional.empty();
        }
        final boolean isBedCheck = "bed-clear".equals(checkType);
        // Deterministic backstop, measured BEFORE asking the model so its verdict can override a false "clear".
        // Fails open: no reference / unreadable frame = no opinion, the AI verdict stands alone.
        final Optional<byte[]> pixelRef = isBedCheck && bedDiff.isEnabled()
                ? bedReference.getReference(printerName) : Optional.empty();
        final Optional<BedDiffService.Measurement> pixel = pixelRef.isPresent()
                ? bedDiff.measureFor(printerName, snapshot.get(), pixelRef.get()) : Optional.empty();
        // Over the limit = an object. Mid-range = the reference no longer describes an empty bed, so the reading
        // means nothing - and "means nothing" must not read as "clear". Both fail closed; see whyUntrustworthy.
        final Optional<String> pixelReason = pixel.flatMap(bedDiff::whyBlocked)
                .or(() -> pixel.flatMap(bedDiff::whyUntrustworthy));
        final boolean pixelBlocked = pixelReason.isPresent();
        // The MEAN, because that is the number the gate actually uses. This recorded the score (max of mean and
        // worst block) until 2026-08-01, so a dispatch alert read "[pixel diff 18.62]" for a check the gate had
        // passed at mean 5.08 - the one number you'd reach for to explain the decision was the one that didn't
        // make it. The worst block has not gated anything since its ordering was found to be inverted.
        final Double recordedDiff = pixel.map(BedDiffService.Measurement::mean).orElse(null);

        // Experimental: for the bed-clear check, if reference-compare mode is on and this printer has a saved
        // empty-bed reference, compare current-vs-reference (two images) instead of judging one image alone.
        final Optional<byte[]> reference = isBedCheck && bedReference.isEnabled()
                ? bedReference.getReference(printerName) : Optional.empty();
        final Optional<OllamaService.AiResult> result = reference.isPresent()
                ? ollama.checkBedClearWithReference(reference.get(), snapshot.get(), context)
                : check.apply(snapshot.get(), context);
        if (result.isEmpty()) {
            // The model failed, but a pixel block is still a definite answer - and a definite NO is worth keeping.
            if (pixelBlocked) {
                final String why = "Bed NOT clear: pixel check vs the empty-bed reference (%s) - AI did not answer"
                        .formatted(pixelReason.orElse("over limit"));
                final OllamaService.AiResult blocked = new OllamaService.AiResult(false, OllamaService.Severity.FAIL, why);
                if (updateLastResult) {
                    lastResults.put(printerName, new AiCheckResult(false, OllamaService.Severity.FAIL, why, checkType, Instant.now()));
                }
                record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                        false, OllamaService.Severity.FAIL, why, snapshot.get(), recordedDiff));
                return Optional.of(blocked);
            }
            record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                    null, null, "AI did not answer (Ollama error or timeout)", snapshot.get(), recordedDiff));
            return result;
        }
        OllamaService.AiResult r = result.get();
        boolean good = positiveMeansGood == r.positive();
        final boolean pixelOverrode = pixelBlocked && good;
        if (pixelOverrode) {
            // The whole reason this backstop exists: the model cannot see a dark part on a dark plate, so when
            // the pixels disagree with a "clear" verdict, the pixels win. That now includes the pixels being
            // unable to say anything useful - an untrustworthy reading leaves the model unsupervised, which is
            // precisely the condition under which it dispatched onto an occupied bed.
            final String why = "Bed NOT clear - BLOCKED by pixel check (%s). AI said: %s"
                    .formatted(pixelReason.orElse("over limit"), r.description());
            Log.warnf("PrintAiService: %s: pixel-diff backstop overrode a 'clear' AI verdict (%s)",
                    printerName, pixelReason.orElse("over limit"));
            r = new OllamaService.AiResult(!positiveMeansGood, OllamaService.Severity.FAIL, why);
            good = false;
        }
        // ---- Two-pass verification, bed gate only ----
        // A single verdict is not stable on a marginal bed: the same plate, at the same pixel reading, was judged
        // not-clear at 01:12 and clear at 01:16 on 2026-08-01, and the "clear" one started a print onto an
        // occupied plate. Requiring a second, independently captured frame to agree turns one coin flip into two.
        //
        // Gated on `good && !pixelOverrode` exactly as HA gated theirs: it only spends a second inference on the
        // one decision that can waste filament, and the common bed-dirty path is untouched. A fresh
        // illuminate/snapshot/restore is used rather than reusing the first frame - two reads of the same image
        // would just repeat the same answer and verify nothing.
        boolean secondPassDisagreed = false;
        if (isBedCheck && good && !pixelOverrode && bedDiff.isTwoPass()) {
            final Optional<BambuConst.LightMode> priorLight2 = illuminateForCheck(printerName);
            final Optional<byte[]> snapshot2 = getSnapshot(printerName);
            restoreLight(printerName, priorLight2);
            final Optional<OllamaService.AiResult> second = snapshot2.map(s2 -> reference.isPresent()
                    ? ollama.checkBedClearWithReference(reference.get(), s2, context)
                    : check.apply(s2, context)).orElse(Optional.empty());
            // No second frame or no answer is NOT agreement. This gate authorises a print; silence fails closed.
            secondPassDisagreed = second.map(s -> positiveMeansGood != s.positive()).orElse(true);
            if (secondPassDisagreed) {
                final String why = "Bed NOT clear - the first check said clear but a second look disagreed (%s). First said: %s"
                        .formatted(second.map(OllamaService.AiResult::description)
                                .orElse("no answer on the second look"), r.description());
                Log.warnf("PrintAiService: %s: two-pass verification rejected a 'clear' verdict (%s)", printerName,
                        second.map(s -> "second look: " + s.description()).orElse("no second answer"));
                r = new OllamaService.AiResult(!positiveMeansGood, OllamaService.Severity.FAIL, why);
                good = false;
            }
        }
        final OllamaService.Severity severity = pixelOverrode || secondPassDisagreed
                ? OllamaService.Severity.FAIL : OllamaService.severityFor(good, r.description());
        if (updateLastResult) {
            lastResults.put(printerName, new AiCheckResult(good, severity, r.description(), checkType, Instant.now()));
        }
        record(new CheckRecord(Instant.now(), printerName, checkType, trigger, context.orElse(null),
                good, severity, r.description(), snapshot.get(), recordedDiff));
        // Self-refreshing reference. Adoption needs the reading to be COMFORTABLY clear (see canRefreshFrom), not
        // merely under the limit - a marginal frame walking the baseline towards an occupied bed is exactly how
        // two printers ended up referencing a bed with a cupholder on it.
        if (isBedCheck && good && pixel.isPresent() && !pixelBlocked && bedDiff.canRefreshFrom(pixel.get())) {
            try {
                bedReference.saveReference(printerName, snapshot.get());
                // Logged at INFO on purpose: this silently rewrites what "clear" means for this printer, and the
                // one time it went wrong it did so invisibly. A line per adoption makes drift auditable.
                Log.infof("PrintAiService: %s: adopted a new bed reference (mean %.2f, model agreed it is clear)",
                        printerName, pixel.get().mean());
            } catch (RuntimeException ex) {
                Log.warnf("PrintAiService: %s: could not auto-refresh the bed reference: %s", printerName, ex.getMessage());
            }
        }
        return Optional.of(r);
    }

    public boolean isEnabled() {
        return ollama.isEnabled() && runtimeEnabled;
    }

    // -------------------------------------------------------------------------
    // Prompt testing (AI Settings) - records to history so tuning results are kept
    // -------------------------------------------------------------------------

    /** Records one prompt test against an already-grabbed frame (trigger "test"), returning the raw model result. */
    private Optional<OllamaService.AiResult> recordTest(final String printerName, final AiPromptService.PromptType type,
            final String promptText, final Optional<byte[]> snapshot, final Optional<String> context) {
        final String checkType = type.key();
        if (snapshot.isEmpty()) {
            record(new CheckRecord(Instant.now(), printerName, checkType, "test", context.orElse(null),
                    null, null, "No camera snapshot available", null));
            return Optional.empty();
        }
        final Optional<OllamaService.AiResult> result = ollama.analyzePrompt(snapshot.get(), promptText, type.positiveKeyword(), context);
        if (result.isEmpty()) {
            record(new CheckRecord(Instant.now(), printerName, checkType, "test", context.orElse(null),
                    null, null, "AI did not answer (Ollama error or timeout)", snapshot.get()));
            return result;
        }
        final OllamaService.AiResult r = result.get();
        final boolean good = (type != AiPromptService.PromptType.FAILURE) == r.positive();
        record(new CheckRecord(Instant.now(), printerName, checkType, "test", context.orElse(null),
                good, OllamaService.severityFor(good, r.description()), r.description(), snapshot.get()));
        return result;
    }

    /** Tests one (possibly unsaved) prompt against a printer's live frame and records it. Call on a background thread. */
    public Optional<OllamaService.AiResult> testPrompt(final String printerName, final AiPromptService.PromptType type, final String promptText) {
        final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
        final Optional<BambuConst.LightMode> prior = illuminateForCheck(printerName);
        final Optional<byte[]> snapshot = getSnapshot(printerName);
        restoreLight(printerName, prior);
        return recordTest(printerName, type, promptText, snapshot, context);
    }

    /** Tests several prompts against ONE shared frame (one light cycle) and records each. Call on a background thread. */
    public java.util.Map<AiPromptService.PromptType, Optional<OllamaService.AiResult>> testPrompts(
            final String printerName, final java.util.Map<AiPromptService.PromptType, String> promptTexts) {
        final Optional<String> context = findPrinter(printerName).flatMap(this::buildContext);
        final Optional<BambuConst.LightMode> prior = illuminateForCheck(printerName);
        final Optional<byte[]> snapshot = getSnapshot(printerName);
        restoreLight(printerName, prior);
        final java.util.Map<AiPromptService.PromptType, Optional<OllamaService.AiResult>> out = new java.util.LinkedHashMap<>();
        promptTexts.forEach((type, text) -> out.put(type, recordTest(printerName, type, text, snapshot, context)));
        return out;
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
        final long deadlineMs = config.ollama().firstLayerDelay().toMillis();
        Log.debugf("PrintAiService: %s: waiting for the first layer (up to %dms)", printerName, deadlineMs);

        executor.submit(() -> {
            // Wait for the LAYER, not for a fixed delay. A fixed delay is only "the first layer" on a print whose
            // layers happen to be slow: an 8-minute wait on a fast part landed the check on layer 89, which is
            // neither a first-layer check nor a useful one.
            final int maxLayer = Math.max(1, config.ollama().firstLayerMaxLayer());
            final long started = System.currentTimeMillis();
            int layer;
            while (true) {
                final Optional<BambuPrinter> p = findPrinter(printerName);
                if (p.isEmpty() || !p.get().getGCodeState().isPrinting()) {
                    Log.debugf("PrintAiService: %s: print ended before the first layer, skipping", printerName);
                    firstLayerScheduled.remove(printerName);
                    return;
                }
                layer = p.get().getLayerNum();
                if (layer >= 1) {
                    break;
                }
                if (System.currentTimeMillis() - started > deadlineMs) {
                    Log.infof("PrintAiService: %s: no layer number reported within %dms, skipping the first-layer check",
                            printerName, deadlineMs);
                    firstLayerScheduled.remove(printerName);
                    return;
                }
                try {
                    Thread.sleep(FIRST_LAYER_POLL_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    firstLayerScheduled.remove(printerName);
                    return;
                }
            }
            if (layer > maxLayer) {
                // Layers went by faster than we could sample - checking now would judge a mid-print surface
                // against a first-layer prompt, which is how you get a confident, meaningless verdict.
                Log.infof("PrintAiService: %s: already on layer %d (>%d) when first observed - skipping the "
                        + "first-layer check rather than judging a mid-print surface", printerName, layer, maxLayer);
                firstLayerScheduled.remove(printerName);
                return;
            }
            final int checkedLayer = layer;

            checksInProgress.add(printerName);
            try {
                // positive = first layer is good
                runCheck(printerName, "first-layer", "scheduled",
                        (bytes, context) -> ollama.checkFirstLayer(bytes, context), true, true)
                        .ifPresent(result -> {
                            if (!result.positive()) {
                                Log.warnf("PrintAiService: %s: first layer issue on layer %d — %s",
                                        printerName, checkedLayer, result.description());
                                notificationService.notifyEvent("first_layer_issue", printerName,
                                        "First layer issue detected (layer %d): %s".formatted(
                                                checkedLayer, truncate(result.description(), 900)),
                                        getLastCheck(printerName).map(CheckRecord::snapshot).orElse(null));
                            } else {
                                Log.infof("PrintAiService: %s: first layer (layer %d) OK — %s",
                                        printerName, checkedLayer, result.description());
                            }
                        });
            } finally {
                checksInProgress.remove(printerName);
            }
        });
    }

    /**
     * Runs ONLY the deterministic pixel comparison against this printer's saved reference - no model call. Exists
     * so the limits can be calibrated by taking an empty-bed reading and a part-on-bed reading back to back,
     * instead of waiting for real checks to happen.
     */
    public Optional<BedDiffService.Measurement> measureBedNow(final String printerName) {
        final Optional<byte[]> reference = bedReference.getReference(printerName);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        final Optional<BambuConst.LightMode> prior = illuminateForCheck(printerName);
        final Optional<byte[]> snapshot = getSnapshot(printerName);
        restoreLight(printerName, prior);
        return snapshot.flatMap(s -> bedDiff.measureFor(printerName, s, reference.get()));
    }

    /**
     * Grabs two frames a couple of seconds apart and measures them against EACH OTHER - the bed doesn't move and
     * nothing is placed on it, so whatever this reads is the pipeline's own noise floor (sensor noise, JPEG
     * compression, auto-exposure drift).
     * <p>
     * This is the decisive diagnostic when empty beds score high: if two frames of the same untouched bed read
     * near a real part's score, the metric is dominated by frame-to-frame instability and no reference, crop or
     * threshold can rescue it. If instead this reads near zero, the difference is genuinely between the reference
     * and now - a stale reference, a moved plate, or changed lighting.
     */
    public Optional<BedDiffService.Measurement> measureNoiseFloor(final String printerName) {
        final Optional<BambuConst.LightMode> prior = illuminateForCheck(printerName);
        try {
            final Optional<byte[]> first = getSnapshot(printerName);
            if (first.isEmpty()) {
                return Optional.empty();
            }
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            final Optional<byte[]> second = getSnapshot(printerName);
            if (second.isEmpty()) {
                return Optional.empty();
            }
            if (java.util.Arrays.equals(first.get(), second.get())) {
                Log.infof("PrintAiService: %s: both frames identical - the camera cache didn't refresh, "
                        + "so this says nothing about the noise floor", printerName);
            }
            return bedDiff.measure(second.get(), first.get());
        } finally {
            restoreLight(printerName, prior);
        }
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
     * Turns the printer's chamber light on and waits {@code bambu.ollama.light-settle} (default 10s) for the
     * camera exposure to adjust, so AI checks always analyze a well-lit frame. Called right before every snapshot
     * grab for a check (including the AI Settings "Test" button). Best-effort: light-command or interruption
     * failures don't abort the check.
     * <p>
     * Don't shorten this without testing: P1-series chamber cameras adapt slowly, and a dim mid-adaptation frame
     * both confuses the vision model and inflates the pixel-diff backstop's measurement.
     */
    public Optional<BambuConst.LightMode> illuminateForCheck(final String printerName) {
        final Optional<BambuPrinter> printer = findPrinter(printerName);
        final Optional<BambuConst.LightMode> prior = printer.flatMap(BambuPrinter::getLightMode);
        printer.ifPresent(p -> {
            try {
                p.commandLight(BambuConst.LightMode.ON);
                Thread.sleep(config.ollama().lightSettle().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                Log.warnf("PrintAiService: %s: could not turn light on before check: %s", printerName, ex.getMessage());
            }
        });
        return prior;
    }

    /**
     * Restores the chamber light to its pre-check state. Only acts when the light was known to be OFF before the
     * check (we forced it ON), so lights-off setups stay dark between checks; if the prior state is unknown or was
     * already ON, the light is left on.
     */
    public void restoreLight(final String printerName, final Optional<BambuConst.LightMode> prior) {
        if (prior.map(m -> m == BambuConst.LightMode.OFF).orElse(false)) {
            findPrinter(printerName).ifPresent(p -> {
                try {
                    p.commandLight(BambuConst.LightMode.OFF);
                } catch (RuntimeException ex) {
                    Log.warnf("PrintAiService: %s: could not restore light after check: %s", printerName, ex.getMessage());
                }
            });
        }
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
