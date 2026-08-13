package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global dispatch pool for auto-queued marketplace ORDER jobs. Instead of pinning each job to one printer at
 * order time (where an uncleared bed would stall it), order jobs sit in this farm-wide pool and a scheduled
 * dispatcher hands each one to whichever eligible printer becomes ready AND bed-clear first - so a job naturally
 * "runs through" every eligible printer until one takes it.
 * <p>
 * Eligibility = the printer has the required filament loaded ({@link AutoQueueService#resolveSlot}) and is opted
 * into auto-queue. Dispatch also requires: the farm-wide auto-start master switch on, the printer idle/ready with
 * an empty local queue (manual Batch Print jobs there take priority - {@link AutoStartService} handles those), and
 * the AI bed-clear check passing (fail-closed, same as auto-start). Manual Batch Print keeps its per-printer queue.
 * <p>
 * <b>Claim model:</b> a job is removed from the pool <i>before</i> its bed check starts and only returned if the
 * dispatch doesn't happen. Two printers can be checked concurrently on a multi-threaded executor, so leaving the
 * job in the pool during the check (and the multi-second {@code queuePart} upload that follows) would let both
 * print the same order copy. The claim is deliberately conservative: a hard crash between claim and dispatch can
 * lose a pooled job (the order then shows N-1/N in the overview) rather than silently printing one twice.
 * <p>
 * <b>Blocked reporting:</b> when the pool has work but the AI gate won't let anything through - AI off, Ollama
 * unreachable/no snapshot, or every eligible bed dirty - a throttled {@code dispatch_blocked} notification fires
 * and {@link #getBlockedStatus()} surfaces it on the Automation overview, so a stalled pipeline is never silent.
 */
@ApplicationScoped
public class DispatchQueueService {

    private static final String STORE_FILENAME = "bambu-dispatch.json";
    /** After a printer's bed-clear check fails, don't re-check it for dispatch again this soon (avoids per-minute spam). */
    private static final Duration DIRTY_BACKOFF = Duration.ofMinutes(3);
    /** After a job fails to queue (missing file, bad plate), wait this long before trying it again. */
    private static final Duration JOB_RETRY_BACKOFF = Duration.ofMinutes(10);
    /** Consecutive queue failures after which a job is parked (stops retrying) and reported. */
    private static final int MAX_JOB_FAILURES = 3;
    /** Minimum gap between two {@code dispatch_blocked} notifications for the same reason. */
    private static final Duration BLOCKED_NOTIFY_INTERVAL = Duration.ofMinutes(30);
    /** How long after a dispatch to confirm the printer actually began printing (not just accepted the command). */
    private static final Duration START_VERIFY_AFTER = Duration.ofSeconds(90);
    /** How long to skip a printer that accepted a job but never started it - routes the retry elsewhere. */
    private static final Duration START_FAIL_BACKOFF = Duration.ofMinutes(20);
    /** Must match the {@code @Scheduled(every=...)} on {@link #tick()} - drives the UI countdown. */
    private static final Duration TICK_INTERVAL = Duration.ofMinutes(1);

    /** One pending order job (one copy). {@code part} carries the gcode + filament requirement; {@code orderRef} the order. */
    public record PendingJob(String id, MappingPart part, OrderRef orderRef) {
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    PrintAiService aiService;
    @Inject
    BedDiffService bedDiff;
    @Inject
    SimulationService simulation;
    // Only to re-derive post-print cooldowns after a restart - see seedCooldownsFromHistory().
    @Inject
    PrintHistoryService historyService;
    @Inject
    AutoStartService autoStartService;
    @Inject
    AutoQueueService autoQueueService;
    @Inject
    OrderTrackingService tracking;
    @Inject
    NotificationService notificationService;

    private final List<PendingJob> pool = Collections.synchronizedList(new ArrayList<>());
    /** printers with an async bed-check/dispatch attempt in flight. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** printer → time until which its bed-clear check is backed off after a recent failure. */
    private final Map<String, Instant> dirtyUntil = new ConcurrentHashMap<>();
    /** Last observed state per printer, to spot the printing→ready edge that means "a part just landed on a bed". */
    private final Map<String, BambuConst.GCodeState> lastStates = new ConcurrentHashMap<>();
    /** printer → dispatched job awaiting confirmation that it actually started printing. */
    private final Map<String, PendingStart> pendingStarts = new ConcurrentHashMap<>();
    /** job id → consecutive queue-time failures (in-memory; a restart forgives them). */
    private final Map<String, Integer> jobFailures = new ConcurrentHashMap<>();
    /** job id → time until which this job is skipped after a queue failure. */
    private final Map<String, Instant> jobBackoff = new ConcurrentHashMap<>();
    /** job id → why the job was parked; parked jobs are never dispatched until retried or removed. */
    private final Map<String, String> jobParked = new ConcurrentHashMap<>();
    /** reason key → when a dispatch_blocked notification was last sent for it. */
    private final Map<String, Instant> blockedNotifiedAt = new ConcurrentHashMap<>();
    /** Human-readable reason the pool isn't moving, or null when it is (or has nothing to do). */
    private volatile String blockedStatus;
    /** The reason key behind {@link #blockedStatus}, so a resolved gate can drop a stale banner. */
    private volatile String blockedKey;
    /** Severity of the current hold. */
    private volatile BlockKind blockedKind = BlockKind.ATTENTION;
    /** When the dispatcher last ran, so the UI can count down to the next pass. */
    private volatile Instant lastTick;

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
            final List<PendingJob> loaded = mapper.readValue(path.toFile(), new TypeReference<List<PendingJob>>() {
            });
            pool.addAll(loaded);
            Log.infof("DispatchQueueService: loaded %d pending order job(s) from %s", pool.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "DispatchQueueService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), new ArrayList<>(pool));
        } catch (IOException ex) {
            Log.errorf(ex, "DispatchQueueService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    // -------------------------------------------------------------------------
    // Pool management
    // -------------------------------------------------------------------------

    /** Adds one order-job copy to the global dispatch pool. */
    public void enqueue(final MappingPart part, final OrderRef orderRef) {
        pool.add(new PendingJob(UUID.randomUUID().toString(), part, orderRef));
        save();
    }

    public List<PendingJob> getPool() {
        return List.copyOf(pool);
    }

    public int size() {
        return pool.size();
    }

    /** Why this job is parked (never dispatched until retried), or empty if it's dispatchable. */
    public Optional<String> getParkedReason(final String id) {
        return Optional.ofNullable(jobParked.get(id));
    }

    /** Clears a parked job's failure state so the dispatcher picks it up again on the next tick. */
    public void retry(final String id) {
        jobParked.remove(id);
        jobFailures.remove(id);
        jobBackoff.remove(id);
    }

    /**
     * Cancels a pooled job: it will never be printed, so the order's expected-job count is reduced to match
     * (otherwise the order stays stuck at "N-1/N printed" and never fires its ready-to-ship notification).
     */
    public void remove(final String id) {
        final Optional<PendingJob> job = take(id);
        if (job.isEmpty()) {
            return;
        }
        forget(id);
        save();
        final OrderRef ref = job.get().orderRef();
        if (ref != null) {
            tracking.removeExpectedJobs(ref.market(), ref.orderId(), 1);
        }
    }

    /**
     * Removes a job that IS going to be printed (handed to a printer's queue elsewhere, e.g. the "Send to…"
     * control on the Print Queue page). The order's expected count stays as-is - the job still counts.
     */
    public void markDispatched(final String id) {
        if (take(id).isPresent()) {
            forget(id);
            save();
        }
    }

    /** Atomically pulls a job out of the pool by id. */
    private Optional<PendingJob> take(final String id) {
        synchronized (pool) {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).id().equals(id)) {
                    return Optional.of(pool.remove(i));
                }
            }
        }
        return Optional.empty();
    }

    private void forget(final String id) {
        jobFailures.remove(id);
        jobBackoff.remove(id);
        jobParked.remove(id);
    }

    // -------------------------------------------------------------------------
    // Dispatcher
    // -------------------------------------------------------------------------

    @Scheduled(every = "1m")
    void tick() {
        lastTick = Instant.now();
        seedCooldownsFromHistory();
        verifyStarts(); // independent of the pool - a dispatched job may still be waiting to actually begin
        // Also pool-independent, and it must stay that way: a print can finish while the pool is empty, and the
        // job that arrives a minute later still needs to know that bed was just used.
        observeFinishes();
        if (pool.isEmpty()) {
            clearBlocked();
            return;
        }
        if (!autoStartService.isGloballyEnabled()) {
            reportBlocked("auto-start-off", "%d order job(s) are waiting in the dispatch pool but the Auto-Start master switch is OFF - nothing will start automatically."
                    .formatted(pool.size()), null, null);
            return;
        }
        if (!aiService.isEnabled()) {
            reportBlocked("ai-off", "%d order job(s) are waiting in the dispatch pool but AI checks are off - the bed-clear gate can't run, so nothing will start (fail-closed)."
                    .formatted(pool.size()), null, null);
            return;
        }
        // Both global gates pass - drop a stale "AI off" / "master switch off" banner right away rather than
        // waiting for a dispatch to succeed.
        if ("auto-start-off".equals(blockedKey) || "ai-off".equals(blockedKey)) {
            clearBlocked();
        }
        boolean claimed = false;
        for (final BambuPrinters.PrinterDetail pd : readyPrinters()) {
            claimed |= tryDispatch(pd);
        }
        // Nothing could even be attempted this pass. That's usually just "every printer is mid-print", which is
        // normal - but it's also how a spool swap or an excluded printer silently stalls an order, so say which.
        if (!claimed) {
            reportNoCandidate();
        }
    }

    /**
     * Explains why a pass dispatched nothing, and reports it. Distinguishes the benign "everything is busy, it'll
     * flow as printers free up" from the ones that need you: no printer has the filament, nothing is opted in,
     * everything is parked.
     */
    private void reportNoCandidate() {
        if (!inFlight.isEmpty()) {
            return; // a bed check is running right now - not blocked, just mid-flight
        }
        final List<PendingJob> waiting = List.copyOf(pool).stream()
                .filter(j -> !jobParked.containsKey(j.id()))
                .toList();
        if (waiting.isEmpty()) {
            reportBlocked("all-parked", "Every job in the dispatch pool (%d) is parked after repeated failures - fix or remove them on the Print Queue page."
                    .formatted(pool.size()), null, null, BlockKind.ATTENTION, false);
            return;
        }
        final List<BambuPrinters.PrinterDetail> optedIn = printers.getPrintersDetail().stream()
                .filter(pd -> autoQueueService.isPrinterEnabled(pd.name()))
                .toList();
        if (optedIn.isEmpty()) {
            reportBlocked("none-opted-in", "%d order job(s) waiting, but no printer is opted into auto-queue - enable at least one on the Print Queue page."
                    .formatted(waiting.size()), null, null, BlockKind.ATTENTION, true);
            return;
        }
        // Ignoring readiness entirely: could ANY opted-in printer take ANY waiting job if it were free?
        final boolean anyFilament = optedIn.stream()
                .anyMatch(pd -> waiting.stream().anyMatch(j -> autoQueueService.resolveSlot(pd, j.part()).isPresent()));
        if (!anyFilament) {
            // Name the COLOUR as well as the material. "no printer has ASA loaded" while three trays hold ASA
            // is a message that sends you looking in the wrong place; "black ASA" is the whole answer.
            final String needed = waiting.stream()
                    .map(j -> j.part())
                    .filter(part -> part.filamentType() != null)
                    .map(part -> part.color().map(c -> c.label() + " ").orElse("") + part.filamentType())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
            reportBlocked("no-filament", "%d order job(s) waiting, but no printer has the required filament loaded%s - load it (or clear the requirement on the mapping) and they'll dispatch."
                    .formatted(waiting.size(), needed.isEmpty() ? "" : " (" + needed + ")"), null, null, BlockKind.ATTENTION, true);
            return;
        }
        final Instant now = Instant.now();
        final long printing = optedIn.stream().filter(pd -> pd.printer().getGCodeState().isPrinting()).count();
        final long ownQueue = optedIn.stream()
                .filter(pd -> pd.printer().getGCodeState().isReady() && queueService.size(pd.name()) > 0).count();
        final long backedOff = optedIn.stream()
                .filter(pd -> dirtyUntil.getOrDefault(pd.name(), Instant.MIN).isAfter(now)).count();
        final List<String> why = new ArrayList<>();
        if (printing > 0) {
            why.add("%d printing".formatted(printing));
        }
        if (ownQueue > 0) {
            why.add("%d working through its own queue".formatted(ownQueue));
        }
        if (backedOff > 0) {
            why.add("%d waiting out a bed-not-clear backoff".formatted(backedOff));
        }
        final long other = optedIn.size() - printing - ownQueue - backedOff;
        if (other > 0) {
            why.add("%d offline or not ready".formatted(other));
        }
        // "when" is the actual question when everything is busy - we know each printer's remaining minutes
        final String eta = optedIn.stream()
                .filter(pd -> pd.printer().getGCodeState().isPrinting())
                .filter(pd -> pd.printer().getRemainingMinutes() >= 0)
                .min(Comparator.comparingInt(pd -> pd.printer().getRemainingMinutes()))
                .map(pd -> " Next free: %s in %s.".formatted(pd.name(),
                        formatMinutes(pd.printer().getRemainingMinutes())))
                .orElse("");
        reportBlocked("waiting-for-printer", "%d order job(s) waiting - all %d printers busy (%s).%s"
                .formatted(waiting.size(), optedIn.size(), String.join(", ", why), eta),
                null, null, BlockKind.WAITING, false);
    }

    /** Set once the first tick has rebuilt cooldowns from history; this only ever needs to run at startup. */
    private boolean cooldownsSeeded;

    /**
     * Rebuilds post-print cooldowns after a restart, from the last finish time recorded per printer.
     * <p>
     * {@code dirtyUntil} and {@code lastStates} are in-memory, so a restart wipes every hold and the printing→ready
     * edge that would have set one has already passed. Without this, deploying a few minutes after a print finished
     * left that printer immediately eligible with the part still on its bed - the exact hazard the cooldown was
     * added for, defeated by the restart that shipped it. Deploys happen far more often than they should be allowed
     * to open a safety hole.
     * <p>
     * Derived from {@code bambu-history.json} rather than persisting {@code dirtyUntil}: the finish time is ground
     * truth and already on disk, so there's no new file and no risk of a stale hold surviving longer than the print
     * it came from.
     * <p>
     * <b>Known narrow gap:</b> a print that ends during the restart itself is discarded by
     * {@link PrintHistoryService} as unknowable, so it leaves no timestamp here and no cooldown is derived. The bed
     * check still runs on that printer; only this layer is missing for it.
     */
    private void seedCooldownsFromHistory() {
        if (cooldownsSeeded) {
            return;
        }
        cooldownsSeeded = true;
        final Duration cooldown = bedDiff.getPostPrintCooldown();
        if (cooldown.isZero()) {
            return;
        }
        final Instant now = Instant.now();
        final Map<String, Instant> lastEnd = new java.util.HashMap<>();
        historyService.getJobs().stream()
                .filter(j -> j.printer() != null && j.ended() != null)
                .forEach(j -> lastEnd.merge(j.printer(), j.ended().toInstant(), (a, b) -> a.isAfter(b) ? a : b));
        lastEnd.forEach((printer, ended) -> {
            final Instant until = ended.plus(cooldown);
            if (until.isAfter(now)) {
                dirtyUntil.merge(printer, until, (a, b) -> a.isAfter(b) ? a : b);
                Log.infof("DispatchQueueService: %s finished a print %d min ago - restoring the remaining %d min of "
                        + "its post-print hold after the restart", printer,
                        Duration.between(ended, now).toMinutes(), Duration.between(now, until).toMinutes());
            }
        });
    }

    /**
     * Holds a printer back for a cooldown the moment it stops printing.
     * <p>
     * <b>Why this is a separate layer from the bed check:</b> in the seconds after a print ends the bed is
     * <i>certainly</i> occupied - the part that just finished is sitting on it. No camera or model is needed to
     * know that, and evaluating the bed in that window is asking a vision check to be right at the exact moment
     * being wrong is most expensive. Observed 2026-08-01: P1S finished at 02:08:45 and a bed check ran at
     * 02:09:46, 61 seconds later. The pixel diff happened to catch that one at 8.54; a mid-range reading with a
     * confident "clear" from the model would have started a print onto the part.
     * <p>
     * {@link AutoStartService} has had exactly this protection since it was written (its 3-minute settle delay,
     * "avoids racing end-of-print telemetry or a person mid-way through clearing the bed"). The dispatcher never
     * got the equivalent, so the pool route into a printer was the unprotected one.
     * <p>
     * Reuses {@code dirtyUntil} rather than adding a parallel timer, because that is honestly what this is: the
     * bed IS dirty, deterministically. Eligibility, the countdown chip on the overview and the "waiting out a
     * bed-not-clear backoff" reporting then all work with no further plumbing. Merged with {@code max} so it can
     * never shorten a longer hold already in place, such as the 20-minute one after a failed start.
     * <p>
     * This removes the guaranteed-occupied window. It is <b>not</b> a substitute for the bed check - if the part
     * is still there when the cooldown expires, only the gate can catch it.
     */
    private void observeFinishes() {
        final Instant now = Instant.now();
        printers.getPrintersDetail().forEach(pd -> {
            final String name = pd.name();
            final BambuConst.GCodeState state = pd.printer().getGCodeState();
            final BambuConst.GCodeState previous = lastStates.put(name, state);
            // First sighting (fresh start, or a printer that just connected): we never saw it printing, so there
            // is no finish to cool down from. Inventing one would stall dispatch for 5 minutes after every restart.
            if (previous == null || previous.isReady() || !state.isReady()) {
                return;
            }
            final Duration cooldown = bedDiff.getPostPrintCooldown();
            if (cooldown.isZero()) {
                return; // switched off in AI Settings
            }
            dirtyUntil.merge(name, now.plus(cooldown), (a, b) -> a.isAfter(b) ? a : b);
            Log.infof("DispatchQueueService: %s just stopped printing - held for %d min before it can take a pooled "
                    + "job, because the finished part is still on the bed", name, cooldown.toMinutes());
        });
    }

    /** Printers that could take a pooled job right now: opted in, idle, empty local queue, not backed off or busy. */
    private List<BambuPrinters.PrinterDetail> readyPrinters() {
        final Instant now = Instant.now();
        return printers.getPrintersDetail().stream()
                .filter(pd -> autoQueueService.isPrinterEnabled(pd.name()))
                .filter(pd -> !inFlight.contains(pd.name()))
                .filter(pd -> pd.printer().getGCodeState().isReady() && !pd.printer().isBlocked())
                .filter(pd -> queueService.size(pd.name()) == 0)
                .filter(pd -> dirtyUntil.getOrDefault(pd.name(), Instant.MIN).isBefore(now))
                .toList();
    }

    /**
     * "Dispatch now" from the Automation overview: you just cleared the beds and don't want to wait out the
     * per-printer dirty backoff or a job's retry backoff. Clears both, then runs a dispatch pass immediately.
     * Parked jobs stay parked - those failed for a reason a cleared bed doesn't fix; retry them individually.
     * <p>
     * The bed checks themselves are asynchronous, so the returned message describes what was kicked off, not
     * what started - prints begin over the following seconds as each printer's check comes back.
     */
    public String dispatchNow() {
        if (pool.isEmpty()) {
            return "No order jobs are waiting in the dispatch pool.";
        }
        if (!autoStartService.isGloballyEnabled()) {
            return "Auto-Start master switch is OFF - turn it on first, nothing can start automatically.";
        }
        if (!aiService.isEnabled()) {
            return "AI checks are off, so the bed-clear gate can't run (fail-closed). Turn AI Checks on first.";
        }
        dirtyUntil.clear();
        jobBackoff.clear();
        clearBlocked();
        boolean claimed = false;
        final List<BambuPrinters.PrinterDetail> ready = readyPrinters();
        for (final BambuPrinters.PrinterDetail pd : ready) {
            claimed |= tryDispatch(pd);
        }
        if (!claimed) {
            reportNoCandidate();
            return getBlockedStatus().orElse("%d job(s) waiting, but no printer can take one right now.".formatted(pool.size()));
        }
        return "Checking %d printer(s) now - each job starts as soon as its bed is confirmed clear.".formatted(ready.size());
    }

    /** A pending job matched to a printer that can print it right now, with the resolved AMS slot for that printer. */
    private record Match(PendingJob job, Integer slot) {
    }

    /**
     * A job whose print command was accepted, awaiting confirmation that the printer actually started.
     * <p>
     * Holds the whole {@link PendingJob}, not a copy of its part and order. The job's <b>id</b> is what
     * {@code jobFailures} and {@code jobParked} are keyed by, and losing it is what let a broken job retry
     * forever - see {@link #verifyStarts}.
     */
    private record PendingStart(String printer, PendingJob job, Instant deadline) {

        MappingPart part() {
            return job.part();
        }

        OrderRef orderRef() {
            return job.orderRef();
        }
    }

    /**
     * Confirms that dispatched jobs actually started printing, and recovers the ones that didn't.
     * <p>
     * {@code startNext} returning success only means the print command was <b>accepted</b> - it removes the entry
     * from the queue and reports success as soon as the MQTT command is sent. If the printer then never starts
     * (the classic cause is the file missing from <i>that</i> printer's SD card), the job has left the pool AND the
     * queue: it vanishes, the order silently under-prints, and nothing says a word. So we check back.
     * <p>
     * On a non-start the job goes back into the pool and the printer gets a long backoff, which naturally routes
     * the retry to a different printer - the same recovery the HA automation did by trying the next printer.
     */
    private void verifyStarts() {
        if (pendingStarts.isEmpty()) {
            return;
        }
        final Instant now = Instant.now();
        for (final PendingStart ps : List.copyOf(pendingStarts.values())) {
            if (now.isBefore(ps.deadline())) {
                continue;
            }
            pendingStarts.remove(ps.printer());
            final boolean printing = printers.getPrinterDetail(ps.printer())
                    .map(d -> d.printer().getGCodeState().isPrinting())
                    .orElse(false);
            if (printing) {
                continue; // started normally
            }
            dirtyUntil.put(ps.printer(), now.plus(START_FAIL_BACKOFF));
            // recordJobFailure, NOT enqueue. enqueue() mints a fresh UUID, so every retry looked like a brand
            // new job to the failure counter and MAX_JOB_FAILURES was never reached: one wrong file retried
            // every 24 minutes for hours, burning an AI check and a chamber-light cycle each time, and the
            // "parked" state that exists precisely for this never engaged. Returning the SAME job makes the
            // count accumulate and the job park on the third attempt like any other permanent failure.
            recordJobFailure(ps.job(), ps.printer(),
                    "the printer accepted the command but never started - the file may be missing from its SD "
                    + "card, or the copy on the card is corrupt and cannot be parsed");
            reportBlocked("start-failed|" + ps.printer(),
                    "%s accepted the print command but never started %s%s - the file may be missing from that printer's SD card. Put back in the dispatch pool; %s is held for %d minutes so it goes to another printer."
                            .formatted(ps.printer(), ps.part().path(),
                                    ps.orderRef() == null ? "" : " (" + ps.orderRef().label() + ")",
                                    ps.printer(), START_FAIL_BACKOFF.toMinutes()),
                    ps.printer(), null, BlockKind.ATTENTION, true);
        }
    }

    /** The bed-check verdict for the dispatch notification, so Discord doubles as an audit trail of every decision. */
    private String bedVerdict(final String printerName) {
        return aiService.getLastCheck(printerName)
                .filter(c -> "bed-clear".equals(c.checkType()))
                // Name the number AND its limit. "[pixel diff 18.62]" was the score (max of mean and worst block)
                // while the gate compares the mean - so an alert could show a figure three times the limit for a
                // check that legitimately passed, which is worse than showing nothing.
                .map(c -> "\n↳ Bed check: %s%s".formatted(truncate(c.description(), 300),
                        c.pixelDiff() == null ? ""
                                : " [pixel diff mean %.2f / limit %.1f]".formatted(c.pixelDiff(), bedDiff.getThreshold())))
                .orElse("");
    }

    private static String truncate(final String s, final int max) {
        if (s == null || s.isBlank()) {
            return "(no detail)";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** @return true if a job was claimed and a bed check started for this printer. */
    private boolean tryDispatch(final BambuPrinters.PrinterDetail pd) {
        // One un-verified dispatch per printer at a time.
        //
        // A printer that accepted a command but hasn't started yet still reports READY, so without this the
        // dispatcher hands it another job on the very next pass. That happened for real: P1P was given three
        // jobs from one order inside two minutes, none of which it could start. Worse, pendingStarts is keyed by
        // PRINTER, so each new dispatch overwrote the previous printer's pending verification - the first two
        // jobs left both the pool and the queue and were never checked on again, which is precisely the silent
        // under-print the verification was written to prevent.
        //
        // Waiting is free here: if the printer really did start, the entry clears on the next verify pass and
        // the job goes out moments later. If it didn't, we find out before spending anything else on it.
        if (pendingStarts.containsKey(pd.name())) {
            return false;
        }
        // Claim the job up front: two printers checked concurrently must never end up printing the same copy.
        final Optional<Match> matchOpt = claimJobFor(pd);
        if (matchOpt.isEmpty()) {
            return false; // no dispatchable job whose filament this printer has loaded
        }
        final Match match = matchOpt.get();
        final PendingJob job = match.job();
        inFlight.add(pd.name());
        boolean handedOff = false;
        try {
            aiService.checkBedClear(pd.name(), "auto-start").whenComplete((result, throwable) -> {
                try {
                    completeDispatch(pd, match, result, throwable);
                } finally {
                    inFlight.remove(pd.name());
                }
            });
            handedOff = true;
        } finally {
            if (!handedOff) {
                // checkBedClear threw before returning a future (e.g. executor shutting down) - don't strand the job
                returnToPool(job);
                inFlight.remove(pd.name());
            }
        }
        return handedOff;
    }

    /**
     * Why the bed-clear check produced no verdict, in the words of whoever actually knows.
     * <p>
     * This alert used to read "(Ollama unreachable, timed out, or no camera snapshot)" - three causes with
     * completely different fixes, and no indication which one had happened. It sent a person to check a
     * perfectly healthy Ollama while the real fault was the mediamtx camera relay not publishing. The
     * information was never missing: {@link PrintAiService} records "No camera snapshot available" or "AI did
     * not answer (Ollama error or timeout)" on the way out, and {@code completeDispatch} already fetches that
     * same record for its snapshot. It just threw the reason away and guessed out loud instead.
     * <p>
     * Falls back to the old wording only when there is genuinely nothing recorded, so the alert degrades to
     * vague rather than to wrong.
     */
    private String whyCheckFailed(final String printerName, final Throwable throwable) {
        if (throwable != null) {
            // CompletableFuture wraps whatever was thrown; the wrapper's message is noise, the cause's is not.
            final Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause() : throwable;
            final String message = cause.getMessage();
            return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
        }
        // Must be this printer's own bed-clear attempt: a concurrent failure check on the same printer would
        // otherwise supply its description here and explain the wrong thing.
        return aiService.getLastCheck(printerName)
                .filter(r -> "bed-clear".equals(r.checkType()) && r.good() == null)
                .map(PrintAiService.CheckRecord::description)
                .filter(d -> d != null && !d.isBlank())
                .orElse("no reason recorded - Ollama unreachable, timed out, or no camera snapshot");
    }

    private void completeDispatch(final BambuPrinters.PrinterDetail pd, final Match match,
            final Optional<OllamaService.AiResult> result, final Throwable throwable) {
        final PendingJob job = match.job();
        // Tracks whether the job has been handed over for printing. Anything else - including an unexpected
        // exception - must put the claimed job back, or it silently vanishes from the order.
        boolean consumed = false;
        try {
            final boolean answered = throwable == null && result != null && result.isPresent();
            final boolean clear = answered && result.get().positive();
            if (!clear) {
                dirtyUntil.put(pd.name(), Instant.now().plus(DIRTY_BACKOFF));
                final byte[] frame = aiService.getLastCheck(pd.name()).map(PrintAiService.CheckRecord::snapshot).orElse(null);
                if (answered) {
                    reportBlocked("bed-dirty|" + pd.name(),
                            "%s: bed is not clear, so a waiting order job wasn't started (%d in the pool). It will go to whichever eligible printer clears first."
                                    .formatted(pd.name(), pool.size() + 1), pd.name(), frame);
                } else {
                    reportBlocked("ai-unavailable|" + pd.name(),
                            "%s: the AI bed-clear check couldn't run (%s), so a waiting order job wasn't started (%d in the pool)."
                                    .formatted(pd.name(), whyCheckFailed(pd.name(), throwable), pool.size() + 1),
                            pd.name(), frame);
                }
                return;
            }
            final Optional<String> error = queuer.queuePart(job.part(), pd.name(), match.slot(), job.orderRef());
            if (error.isPresent()) {
                consumed = true; // recordJobFailure puts it back itself, with backoff
                recordJobFailure(job, pd.name(), error.get());
                return;
            }
            consumed = true;
            forget(job.id());
            save();
            clearBlocked();
            queueService.startNext(pd.name(), "auto-start",
                    () -> {
                        Log.infof("DispatchQueueService: dispatched %s to %s (%d left in pool)", job.part().path(), pd.name(), pool.size());
                        // The command was accepted - but "accepted" isn't "printing". Verify shortly.
                        // Not while rehearsing: nothing was sent, so the printer will still be idle in 90s and
                        // every simulated dispatch would "fail" verification, dumping the job back into the pool
                        // and holding the printer for 20 minutes.
                        if (!simulation.isEnabled()) {
                            pendingStarts.put(pd.name(), new PendingStart(pd.name(), job,
                                    Instant.now().plus(START_VERIFY_AFTER)));
                        }
                        notificationService.notifyEvent("auto_start", pd.name(),
                                "Dispatched order job to %s: %s%s%s".formatted(pd.name(), job.part().path(),
                                        job.orderRef() == null ? "" : " (" + job.orderRef().label() + ")",
                                        bedVerdict(pd.name())),
                                aiService.getLastCheck(pd.name()).map(PrintAiService.CheckRecord::snapshot).orElse(null));
                    },
                    err -> Log.warnf("DispatchQueueService: %s: start failed after dispatch: %s", pd.name(), err));
        } catch (RuntimeException ex) {
            Log.errorf(ex, "DispatchQueueService: %s: dispatch failed unexpectedly: %s", pd.name(), ex.getMessage());
        } finally {
            if (!consumed) {
                returnToPool(job);
            }
        }
    }

    /**
     * A job failed to queue (missing library file, unreadable plate, …). Put it back with a retry backoff so the
     * dispatcher doesn't burn an AI check + chamber-light cycle on it every single minute, and park it entirely
     * after a few attempts so a permanently broken mapping can't keep the pool busy.
     */
    private void recordJobFailure(final PendingJob job, final String printerName, final String error) {
        returnToPool(job);
        final int failures = jobFailures.merge(job.id(), 1, Integer::sum);
        Log.warnf("DispatchQueueService: %s: could not queue %s (attempt %d): %s", printerName, job.part().path(), failures, error);
        if (failures >= MAX_JOB_FAILURES) {
            jobParked.put(job.id(), error);
            jobBackoff.remove(job.id());
            reportBlocked("job-parked|" + job.id(),
                    "Order job %s%s was parked after %d failed attempts: %s. Fix it and hit retry on the Print Queue page, or remove it."
                            .formatted(job.part().path(), job.orderRef() == null ? "" : " (" + job.orderRef().label() + ")",
                                    failures, error), printerName, null);
        } else {
            jobBackoff.put(job.id(), Instant.now().plus(JOB_RETRY_BACKOFF));
        }
    }

    /**
     * Returns a claimed job to the pool (dispatch didn't happen). Kept at the front so it stays first in line.
     * The {@link #save()} deliberately happens outside the {@code pool} monitor: {@code save()} is synchronized on
     * {@code this} and copies the pool (taking the pool monitor), so holding pool→this here would invert the lock
     * order and could deadlock.
     */
    private void returnToPool(final PendingJob job) {
        final boolean added;
        synchronized (pool) {
            added = pool.stream().noneMatch(j -> j.id().equals(job.id()));
            if (added) {
                pool.add(0, job);
            }
        }
        if (added) {
            save();
        }
    }

    /**
     * Atomically claims the oldest dispatchable job whose required filament this printer currently has loaded.
     * Skips jobs that are parked or inside their retry backoff.
     */
    private Optional<Match> claimJobFor(final BambuPrinters.PrinterDetail pd) {
        final Instant now = Instant.now();
        synchronized (pool) {
            for (int i = 0; i < pool.size(); i++) {
                final PendingJob job = pool.get(i);
                if (jobParked.containsKey(job.id()) || jobBackoff.getOrDefault(job.id(), Instant.MIN).isAfter(now)) {
                    continue;
                }
                final Optional<AutoQueueService.Candidate> c = autoQueueService.resolveSlot(pd, job.part());
                if (c.isPresent()) {
                    pool.remove(i);
                    return Optional.of(new Match(job, c.get().resolvedSlot()));
                }
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Blocked reporting (Automation overview + notification)
    // -------------------------------------------------------------------------

    /** How serious a hold is: {@link #WAITING} resolves itself, {@link #ATTENTION} needs you. */
    public enum BlockKind {
        /** Normal congestion - every printer is busy. Shown in amber, notified once per occurrence. */
        WAITING,
        /** Something needs a human: no filament, nothing opted in, AI down, bed dirty, jobs parked. */
        ATTENTION
    }

    /** Why the dispatch pool isn't moving right now, or empty when it is (or has nothing waiting). */
    public Optional<String> getBlockedStatus() {
        return Optional.ofNullable(blockedStatus);
    }

    /** Severity of the current hold - drives amber vs red in the UI. */
    public BlockKind getBlockedKind() {
        return blockedKind;
    }

    // -------------------------------------------------------------------------
    // Live countdowns (Automation overview) - so a waiting pipeline never looks stuck
    // -------------------------------------------------------------------------

    /** Time until the next dispatcher pass. Empty before the first tick. */
    public Optional<Duration> getNextTickIn() {
        final Instant t = lastTick;
        return t == null ? Optional.empty() : Optional.of(nonNegative(Duration.between(Instant.now(), t.plus(TICK_INTERVAL))));
    }

    /** Time until this printer's bed is re-checked after a not-clear result, if it's backed off. */
    public Optional<Duration> getBedBackoffRemaining(final String printerName) {
        final Instant until = dirtyUntil.get(printerName);
        return until == null || !until.isAfter(Instant.now())
                ? Optional.empty() : Optional.of(nonNegative(Duration.between(Instant.now(), until)));
    }

    /** Time until a just-dispatched job is confirmed to have actually started on this printer. */
    public Optional<Duration> getStartVerifyRemaining(final String printerName) {
        final PendingStart ps = pendingStarts.get(printerName);
        return ps == null ? Optional.empty() : Optional.of(nonNegative(Duration.between(Instant.now(), ps.deadline())));
    }

    /** Printers currently running a bed check for a dispatch. */
    public Set<String> getCheckingPrinters() {
        return Set.copyOf(inFlight);
    }

    /** Waiting jobs held in a retry backoff after a queue failure, with the time left on the longest. */
    public Optional<Duration> getJobRetryRemaining() {
        final Instant now = Instant.now();
        return jobBackoff.values().stream()
                .filter(t -> t.isAfter(now))
                .max(Instant::compareTo)
                .map(t -> nonNegative(Duration.between(now, t)));
    }

    /** Soonest a pooled job could start, i.e. when the first busy printer finishes. Empty if none are printing. */
    public Optional<String> getNextFree() {
        return printers.getPrintersDetail().stream()
                .filter(pd -> autoQueueService.isPrinterEnabled(pd.name()))
                .filter(pd -> pd.printer().getGCodeState().isPrinting())
                .filter(pd -> pd.printer().getRemainingMinutes() >= 0)
                .min(Comparator.comparingInt(pd -> pd.printer().getRemainingMinutes()))
                .map(pd -> "%s in %s".formatted(pd.name(), formatMinutes(pd.printer().getRemainingMinutes())));
    }

    static String formatMinutes(final int minutes) {
        return minutes >= 60 ? "%dh %02dm".formatted(minutes / 60, minutes % 60) : "%dm".formatted(minutes);
    }

    private static Duration nonNegative(final Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    /** Number of pooled jobs parked after repeated failures. */
    public int parkedCount() {
        return (int) List.copyOf(pool).stream().filter(j -> jobParked.containsKey(j.id())).count();
    }

    private void reportBlocked(final String reasonKey, final String message, final String printerName, final byte[] frame) {
        reportBlocked(reasonKey, message, printerName, frame, BlockKind.ATTENTION, true);
    }

    /**
     * @param repeat whether to re-notify every {@link #BLOCKED_NOTIFY_INTERVAL} while the condition persists.
     *               True for things needing a human (a dirty bed won't clear itself). False for states that
     *               resolve on their own - "all printers are busy" pings once, not every half hour for a 6h print.
     */
    private void reportBlocked(final String reasonKey, final String message, final String printerName,
            final byte[] frame, final BlockKind kind, final boolean repeat) {
        blockedStatus = message;
        blockedKey = reasonKey;
        blockedKind = kind;
        final Instant last = blockedNotifiedAt.get(reasonKey);
        if (last != null && (!repeat || last.isAfter(Instant.now().minus(BLOCKED_NOTIFY_INTERVAL)))) {
            Log.debugf("DispatchQueueService: still held (%s) - notification throttled", reasonKey);
            return;
        }
        blockedNotifiedAt.put(reasonKey, Instant.now());
        if (kind == BlockKind.WAITING) {
            Log.infof("DispatchQueueService: %s", message);
        } else {
            Log.warnf("DispatchQueueService: %s", message);
        }
        notificationService.notifyEvent("dispatch_blocked", printerName == null ? "dispatch pool" : printerName, message, frame);
    }

    private void clearBlocked() {
        blockedStatus = null;
        blockedKey = null;
        blockedKind = BlockKind.ATTENTION;
        blockedNotifiedAt.clear();
    }
}
