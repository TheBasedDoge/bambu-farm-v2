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

    public record PrintJob(String printer, String file, OffsetDateTime started, OffsetDateTime ended, long durationSeconds, String result, double grams) {

    }

    public record PrinterStats(String printer, int total, int finished, int failed, long totalSeconds, double totalGrams) {

    }

    private record RunningJob(String file, OffsetDateTime started, double grams) {

    }

    private record Pending(String file, double grams, OffsetDateTime expires) {

    }

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    ObjectMapper mapper;
    @Inject
    NotificationService notificationService;

    private final List<PrintJob> jobs = new ArrayList<>();
    private final Map<String, BambuConst.GCodeState> lastState = new HashMap<>();
    private final Map<String, RunningJob> running = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean dirty;

    /**
     * Registers the expected filament weight for the next print started on a printer (e.g. from batch print / queue, where the plate weight is known).
     */
    public synchronized void registerExpectedWeight(final String printer, final String file, final double grams) {
        pending.put(printer, new Pending(file, grams, OffsetDateTime.now().plusMinutes(15)));
    }

    private double consumePendingGrams(final String printer) {
        final Pending p = pending.remove(printer);
        if (p == null || p.expires().isBefore(OffsetDateTime.now())) {
            return 0;
        }
        return p.grams();
    }

    private Path getPath() {
        return Path.of(config.historyFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final List<PrintJob> loaded = mapper.readValue(path.toFile(), new TypeReference<List<PrintJob>>() {
            });
            jobs.addAll(loaded);
            Log.infof("PrintHistoryService: loaded %d job(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "PrintHistoryService: cannot load %s: %s", path, ex.getMessage());
        }
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
                // first observation: pick up a print already in progress
                if (previous == null && isInJob(current) && !running.containsKey(name)) {
                    running.put(name, new RunningJob(printer.getLastPrintFile().orElse(""), OffsetDateTime.now(), consumePendingGrams(name)));
                }
                return;
            }
            if (!isInJob(previous) && isInJob(current)) {
                running.put(name, new RunningJob(printer.getLastPrintFile().orElse(""), OffsetDateTime.now(), consumePendingGrams(name)));
                return;
            }
            if (isInJob(previous) && !isInJob(current)) {
                final RunningJob job = running.remove(name);
                if (job == null) {
                    return;
                }
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
                        Duration.between(job.started(), now).toSeconds(), result, job.grams()));
            }
        });
        save(false);
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
        notificationService.notifyEvent(event, job.printer(),
                "Print %s: %s (%dh %dm)".formatted(job.result().toLowerCase(), job.file(), h, m));
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
