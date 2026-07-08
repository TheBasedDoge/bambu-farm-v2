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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks per printer print hours and maintenance tasks, persisted to a JSON file.
 *
 * Print hours accumulate while the application is running and a printer is in a printing state. Hours can be adjusted manually in the Maintenance view (for
 * printers that already had hours on them before tracking started).
 */
@ApplicationScoped
public class MaintenanceService {

    private static final double TICK_HOURS = 1.0 / 60;

    public record MaintenanceTask(String name, double intervalHours, double lastDoneHours, String lastDoneDate) {

    }

    public record PrinterMaintenance(double printHours, List<MaintenanceTask> tasks) {

    }

    public record TaskStatus(MaintenanceTask task, double hoursSince, boolean overdue) {

    }

    private static final List<MaintenanceTask> DEFAULT_TASKS = List.of(
            new MaintenanceTask("Clean carbon rods", 200, 0, ""),
            new MaintenanceTask("Lubricate lead screws", 300, 0, ""),
            new MaintenanceTask("Clean nozzle / hotend", 100, 0, ""),
            new MaintenanceTask("Check belt tension", 500, 0, "")
    );

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    ObjectMapper mapper;

    private final Map<String, PrinterMaintenance> data = new HashMap<>();
    private boolean dirty;

    private Path getPath() {
        return Path.of(config.maintenanceFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Map<String, PrinterMaintenance> loaded = mapper.readValue(path.toFile(), new TypeReference<Map<String, PrinterMaintenance>>() {
            });
            data.putAll(loaded);
            Log.infof("MaintenanceService: loaded %d printer(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "MaintenanceService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save(final boolean force) {
        if (!dirty && !force) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), data);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "MaintenanceService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Scheduled(every = "60s")
    synchronized void tick() {
        printers.getPrinters().forEach(p -> {
            if (!p.getGCodeState().isPrinting()) {
                return;
            }
            final PrinterMaintenance pm = get(p.getName());
            data.put(p.getName(), new PrinterMaintenance(pm.printHours() + TICK_HOURS, pm.tasks()));
            dirty = true;
        });
    }

    @Scheduled(every = "5m")
    void persist() {
        save(false);
    }

    @Shutdown
    void onShutdown() {
        save(false);
    }

    private PrinterMaintenance get(final String name) {
        return data.computeIfAbsent(name, n -> new PrinterMaintenance(0, new ArrayList<>(DEFAULT_TASKS)));
    }

    public synchronized double getPrintHours(final String name) {
        return get(name).printHours();
    }

    public synchronized void setPrintHours(final String name, final double hours) {
        data.put(name, new PrinterMaintenance(Math.max(0, hours), get(name).tasks()));
        dirty = true;
        save(false);
    }

    public synchronized List<TaskStatus> getTaskStatus(final String name) {
        final PrinterMaintenance pm = get(name);
        return pm.tasks().stream().map(t -> {
            final double since = Math.max(0, pm.printHours() - t.lastDoneHours());
            return new TaskStatus(t, since, t.intervalHours() > 0 && since >= t.intervalHours());
        }).toList();
    }

    public synchronized void markDone(final String name, final String taskName) {
        final PrinterMaintenance pm = get(name);
        final List<MaintenanceTask> tasks = pm.tasks().stream()
                .map(t -> t.name().equals(taskName)
                        ? new MaintenanceTask(t.name(), t.intervalHours(), pm.printHours(), LocalDate.now().toString())
                        : t)
                .toList();
        data.put(name, new PrinterMaintenance(pm.printHours(), tasks));
        dirty = true;
        save(false);
    }

    public synchronized void addTask(final String name, final String taskName, final double intervalHours) {
        final PrinterMaintenance pm = get(name);
        if (taskName.isBlank() || pm.tasks().stream().anyMatch(t -> t.name().equalsIgnoreCase(taskName))) {
            return;
        }
        final List<MaintenanceTask> tasks = new ArrayList<>(pm.tasks());
        tasks.add(new MaintenanceTask(taskName, Math.max(0, intervalHours), pm.printHours(), LocalDate.now().toString()));
        data.put(name, new PrinterMaintenance(pm.printHours(), tasks));
        dirty = true;
        save(false);
    }

    public synchronized void removeTask(final String name, final String taskName) {
        final PrinterMaintenance pm = get(name);
        final List<MaintenanceTask> tasks = pm.tasks().stream()
                .filter(t -> !t.name().equals(taskName))
                .toList();
        data.put(name, new PrinterMaintenance(pm.printHours(), tasks));
        dirty = true;
        save(false);
    }

}
