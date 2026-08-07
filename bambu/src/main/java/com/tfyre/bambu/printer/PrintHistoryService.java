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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records print jobs (file, start, duration, result) per printer by watching gcode state transitions, persisted to a JSON file.
 */
@ApplicationScoped
public class PrintHistoryService {

    private static final int MAX_JOBS = 1_000;

    /**
     * @param trigger  how the print was started: "auto-start" (AI-gated auto-start), "queue" (manual Start Next
     *                 from the queue), or {@code null} for direct/untracked starts (SD card, Print Again, slicer,
     *                 and history entries recorded before this field existed)
     * @param orderRef the marketplace order this print fulfills, or {@code null}
     */
    public record PrintJob(String printer, String file, OffsetDateTime started, OffsetDateTime ended, long durationSeconds, String result, double grams, String trigger, OrderRef orderRef) {

    }

    public record PrinterStats(String printer, int total, int finished, int failed, long totalSeconds, double totalGrams) {

    }

    /** Prints that ended today, split by outcome, with the filament they consumed. */
    public record TodayStats(int finished, int failed, double grams) {

    }

    /**
     * Today's throughput.
     * <p>
     * Counted by <b>local calendar day</b>, not a rolling 24 hours: the question this answers is "how has today
     * gone", and a rolling window answers a different one - it would still be reporting yesterday evening's
     * prints at breakfast. See the zone logged at startup; a container with no {@code TZ} counts UTC days, which
     * rolls this over mid-evening for most of the world.
     * <p>
     * Grams are counted only for finished prints, matching how the spool decrement works: the figure is the
     * slicer's estimate for a whole plate, so charging it against a print that stopped part-way would overstate
     * it.
     */
    public synchronized TodayStats getTodayStats() {
        final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        final java.time.LocalDate day = java.time.LocalDate.now(zone);
        int finished = 0;
        int failed = 0;
        double grams = 0;
        for (final PrintJob job : jobs) {
            if (job.ended() == null || !job.ended().atZoneSameInstant(zone).toLocalDate().equals(day)) {
                continue;
            }
            if ("Finished".equals(job.result())) {
                finished++;
                grams += Math.max(0, job.grams());
            } else {
                failed++;
            }
        }
        return new TodayStats(finished, failed, grams);
    }

    /** A print currently under way. Persisted - see {@link #saveInFlight()}. */
    public record RunningJob(String file, OffsetDateTime started, double grams, String trigger, OrderRef orderRef) {

    }

    /** A print that has been commanded but hasn't started yet. Persisted - see {@link #saveInFlight()}. */
    public record Pending(String file, double grams, OffsetDateTime expires, String trigger, OrderRef orderRef) {

    }

    /** Persisted shape of the in-flight state. */
    public record InFlight(Map<String, RunningJob> running, Map<String, Pending> pending) {

    }

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    ObjectMapper mapper;
    @Inject
    NotificationService notificationService;
    @Inject
    OrderTrackingService orderTracking;
    @Inject
    PrintAiService aiService;
    @Inject
    SpoolService spoolService;
    /** Lazy to avoid an eager circular reference (PrintQueueService injects this service). */
    @Inject
    jakarta.enterprise.inject.Instance<PrintQueueService> queueServiceInstance;

    private final List<PrintJob> jobs = new ArrayList<>();
    private final Map<String, BambuConst.GCodeState> lastState = new HashMap<>();
    private final Map<String, RunningJob> running = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean dirty;

    /**
     * Registers the expected filament weight for the next print started on a printer (e.g. from batch print / queue, where the plate weight is known).
     */
    public synchronized void registerExpectedWeight(final String printer, final String file, final double grams) {
        registerExpectedWeight(printer, file, grams, null, null);
    }

    /**
     * Variant that also records HOW the upcoming print was started ("auto-start" / "queue") and which
     * marketplace order it fulfills, for history filtering and ready-to-ship tracking.
     */
    public synchronized void registerExpectedWeight(final String printer, final String file, final double grams,
            final String trigger, final OrderRef orderRef) {
        pending.put(printer, new Pending(file, grams, OffsetDateTime.now().plusMinutes(15), trigger, orderRef));
        saveInFlight();
    }

    private Pending consumePending(final String printer) {
        final Pending p = pending.remove(printer);
        if (p != null) {
            saveInFlight();
        }
        if (p == null || p.expires().isBefore(OffsetDateTime.now())) {
            return null;
        }
        return p;
    }

    private Path getPath() {
        return Path.of(config.historyFile());
    }

    /**
     * Sidecar holding the jobs that are mid-flight. Kept next to the history file rather than inside it so the
     * history format is untouched.
     * <p>
     * <b>Why this has to be persisted:</b> {@code running} and {@code pending} carry the trigger and the
     * {@link OrderRef}. Held only in memory, a restart during a print severed that link - the tick then saw a
     * printer already printing with no pending entry and rebuilt the job with a null order ref, so when it
     * finished the order was never credited and sat at 0/N forever.
     */
    private Path getInFlightPath() {
        final Path history = getPath();
        final String name = history.getFileName().toString().replaceFirst("\\.json$", "") + "-inflight.json";
        final Path parent = history.getParent();
        return parent != null ? parent.resolve(name) : Path.of(name);
    }

    private synchronized void saveInFlight() {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(getInFlightPath().toFile(), new InFlight(new HashMap<>(running), new HashMap<>(pending)));
        } catch (IOException ex) {
            Log.errorf(ex, "PrintHistoryService: cannot save %s: %s", getInFlightPath(), ex.getMessage());
        }
    }

    private synchronized void loadInFlight() {
        final Path path = getInFlightPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final InFlight state = mapper.readValue(path.toFile(), InFlight.class);
            if (state.running() != null) {
                running.putAll(state.running());
            }
            if (state.pending() != null) {
                pending.putAll(state.pending());
            }
            Log.infof("PrintHistoryService: restored %d running and %d pending job(s) from %s",
                    running.size(), pending.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "PrintHistoryService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (Files.exists(path)) {
            try {
                final List<PrintJob> loaded = mapper.readValue(path.toFile(), new TypeReference<List<PrintJob>>() {
                });
                jobs.addAll(loaded);
                Log.infof("PrintHistoryService: loaded %d job(s) from %s", loaded.size(), path);
            } catch (IOException ex) {
                Log.errorf(ex, "PrintHistoryService: cannot load %s: %s", path, ex.getMessage());
            }
        }
        // Always - a farm with no history yet can still have been restarted mid-print
        loadInFlight();

        // The zone every calendar-day decision in the app is made in - "prints today", History's day grouping,
        // the daily-summary cron. A container with no TZ set runs in UTC, which silently rolls "today" over in
        // the evening for anyone west of Greenwich: the counter reads 0 while the printers are still warm, and
        // nothing anywhere says why. One line at startup turns that into a ten-second diagnosis.
        final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Log.infof("PrintHistoryService: calendar days are counted in %s (local time now %s)%s", zone,
                java.time.LocalDateTime.now(zone).withNano(0),
                "UTC".equals(zone.getId()) || "Z".equals(zone.getId())
                        ? " - if that isn't your zone, set TZ on the container; 'today' will be wrong otherwise" : "");
    }

    private synchronized void save(final boolean force) {
        if (!dirty && !force) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), jobs);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "PrintHistoryService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    /** Starts tracking a print, taking the trigger and order ref from the pending entry when there is one. */
    private void startRunning(final String name, final BambuPrinter printer) {
        final Pending p = consumePending(name);
        running.put(name, new RunningJob(printer.getLastPrintFile().orElse(""), OffsetDateTime.now(),
                p == null ? 0 : p.grams(), p == null ? null : p.trigger(), p == null ? null : p.orderRef()));
        saveInFlight();
        if (p == null) {
            Log.infof("PrintHistoryService: %s: print started with no pending entry - no trigger or order link", name);
        }
    }

    private boolean isInJob(final BambuConst.GCodeState state) {
        return state.isPrinting() || state == BambuConst.GCodeState.PAUSE;
    }

    @Scheduled(every = "10s")
    synchronized void tick() {
        printers.getPrinters().forEach(printer -> {
            final String name = printer.getName();
            final BambuConst.GCodeState current = printer.getGCodeState();
            final BambuConst.GCodeState previous = lastState.put(name, current);
            if (previous == null || previous == current) {
                // First observation. A restored running job (we restarted mid-print) is kept as-is - that's the
                // whole point of persisting it, since it carries the trigger and the order ref.
                if (previous == null && !isInJob(current) && running.remove(name) != null) {
                    // The print ended while we were down. Its result is unknowable, so it isn't recorded - but the
                    // entry must go, or it would later be closed against an unrelated print.
                    Log.infof("PrintHistoryService: %s: discarding a restored running job - the printer is idle, "
                            + "so the print ended while the app was down", name);
                    saveInFlight();
                } else if (previous == null && isInJob(current) && !running.containsKey(name)) {
                    startRunning(name, printer);
                }
                return;
            }
            if (!isInJob(previous) && isInJob(current)) {
                startRunning(name, printer);
                return;
            }
            if (isInJob(previous) && !isInJob(current)) {
                final RunningJob job = running.remove(name);
                if (job == null) {
                    return;
                }
                saveInFlight();
                final String result = switch (current) {
                    case FINISH ->
                        "Finished";
                    case FAILED ->
                        "Failed";
                    case IDLE ->
                        "Stopped";
                    case OFFLINE ->
                        "Offline";
                    default ->
                        current.getDescription();
                };
                final String file = job.file().isEmpty() ? printer.getLastPrintFile().orElse("") : job.file();
                final OffsetDateTime now = OffsetDateTime.now();
                addJob(new PrintJob(name, file, job.started(), now,
                        Duration.between(job.started(), now).toSeconds(), result, job.grams(), job.trigger(), job.orderRef()));
            }
        });
        save(false);
    }

    /**
     * A human-readable name for a job, for alerts only.
     * <p>
     * {@link PrintJob#file()} is the SD filename, and it is empty for a print started outside this app on a
     * printer that doesn't report {@code gcode_file} - every H2D job here has a blank one. That produced the
     * alert "H2D Print failed: (1h 56m)", which names neither the part nor the plate at the moment you most
     * need both.
     * <p>
     * Falls back to the printer's reported subtask name, which the H2D does send. Deliberately NOT folded into
     * {@code getLastPrintFile()} or into the stored {@code file}: that value is an SD path used by
     * {@code commandPrintAgain} to re-print, and matched exactly by the auto-requeue logic. A friendly label in
     * either place would break both.
     */
    private String jobLabel(final PrintJob job) {
        if (job.file() != null && !job.file().isBlank()) {
            return job.file();
        }
        return printers.getPrinterDetail(job.printer())
                .flatMap(d -> d.printer().getSubtaskName())
                .orElse("(unnamed job)");
    }

    private void addJob(final PrintJob job) {
        jobs.add(job);
        while (jobs.size() > MAX_JOBS) {
            jobs.remove(0);
        }
        dirty = true;
        Log.infof("PrintHistoryService: %s: %s [%s] %ds", job.printer(), job.file(), job.result(), job.durationSeconds());
        final String event = switch (job.result()) {
            case "Finished" ->
                "finish";
            case "Failed" ->
                "fail";
            default ->
                "stopped";
        };
        final long h = job.durationSeconds() / 3600;
        final long m = job.durationSeconds() % 3600 / 60;
        // Attach the current camera frame so Discord/ntfy alerts show the finished (or failed) bed
        notificationService.notifyEvent(event, job.printer(),
                "Print %s: %s (%dh %dm)".formatted(job.result().toLowerCase(), jobLabel(job), h, m),
                aiService.getSnapshot(job.printer()).orElse(null));

        // Ready-to-ship: count this finish towards its order; fires exactly once per completed order
        if (job.orderRef() != null && "Finished".equals(job.result())
                && orderTracking.recordJobPrinted(job.orderRef().market(), job.orderRef().orderId())) {
            Log.infof("PrintHistoryService: %s fully printed", job.orderRef().label());
            notificationService.notifyEvent("order_printed", job.orderRef().market(),
                    "%s is fully printed - ready to ship".formatted(job.orderRef().label()));
        }

        // Decrement the (non-Bambu) spool assigned to the tray this print used, if any.
        // Only on a completed print: job.grams() is the weight the SLICER estimated for the whole plate, so
        // charging it against a print that failed or was cancelled part-way would badly over-count the spool.
        if (job.grams() > 0 && "Finished".equals(job.result())) {
            printers.getPrinters().stream()
                    .filter(p -> p.getName().equals(job.printer()))
                    .findFirst()
                    .ifPresent(p -> spoolService.recordUsage(job.printer(), p.getActiveTrayId(), job.grams()));
        }

        // Give the queue a chance to auto-requeue a failed queue-started job (opt-in, single retry)
        final boolean requeued = queueServiceInstance.get().onJobEnded(job);

        // A print that ended without producing a part must release the expectation it was covering, unless it was
        // auto-requeued (the retry still owes that part). Otherwise the expectation stays outstanding AND any
        // re-queue registers a second one for the same part, so the order over-counts and can never reach
        // "ready to ship": a stopped cupholder left Etsy #4130857746 reading 0/2 for a single-item order.
        if (job.orderRef() != null && !"Finished".equals(job.result()) && !requeued) {
            orderTracking.removeExpectedJobs(job.orderRef().market(), job.orderRef().orderId(), 1, true);
            Log.infof("PrintHistoryService: %s ended %s and was not requeued - released one expected job from %s,"
                    + " which now needs a re-queue before it can be ready to ship",
                    job.file(), job.result(), job.orderRef().label());
            // Its own event, separate from the generic "print stopped" one. A stop that costs you an ORDER is a
            // different thing from a stop you performed deliberately, and it needs an action: nothing will
            // reprint that part on its own. Found the hard way - an eBay order sat abandoned for five hours
            // because the only alert said "print stopped", which is not obviously something to act on.
            notificationService.notifyEvent("order_needs_requeue", job.orderRef().market(),
                    "%s: %s ended %s and nothing will reprint it. Re-queue the order from the Sales Orders page."
                            .formatted(job.orderRef().label(), job.file(), job.result().toLowerCase()),
                    aiService.getSnapshot(job.printer()).orElse(null));
        }
    }

    @Shutdown
    void onShutdown() {
        save(false);
    }

    public synchronized List<PrintJob> getJobs() {
        return List.copyOf(jobs);
    }

    public synchronized List<PrinterStats> getStats() {
        final Map<String, List<PrintJob>> byPrinter = new HashMap<>();
        jobs.forEach(j -> byPrinter.computeIfAbsent(j.printer(), k -> new ArrayList<>()).add(j));
        return byPrinter.entrySet().stream()
                .map(e -> new PrinterStats(
                e.getKey(),
                e.getValue().size(),
                (int) e.getValue().stream().filter(j -> "Finished".equals(j.result())).count(),
                (int) e.getValue().stream().filter(j -> "Failed".equals(j.result())).count(),
                e.getValue().stream().mapToLong(PrintJob::durationSeconds).sum(),
                e.getValue().stream().mapToDouble(PrintJob::grams).sum()))
                .sorted(java.util.Comparator.comparing(PrinterStats::printer))
                .toList();
    }

}
