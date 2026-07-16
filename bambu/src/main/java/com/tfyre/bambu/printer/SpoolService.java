package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filament spool inventory for NON-Bambu spools (which have no AMS RFID, so the printer can't track their
 * remaining weight). Each spool has a remaining-grams counter; a spool is assigned to a specific AMS tray of a
 * specific printer, and when a print finishes on that printer, the grams it used are subtracted from the spool
 * on the tray that was active. A low-stock notification fires when a spool crosses its warning threshold.
 * <p>
 * Persisted to {@code bambu-spools.json}. Consumption is best-effort: it needs the printer to have reported which
 * tray it was feeding ({@link BambuPrinter#getActiveTrayId()}) and a known filament weight for the job.
 */
@ApplicationScoped
public class SpoolService {

    private static final String STORE_FILENAME = "bambu-spools.json";

    /** One spool. {@code color} is a free-form label or hex; grams counters are in grams. */
    public record Spool(String id, String name, String material, String color,
            double totalGrams, double remainingGrams, double lowThresholdGrams) {
    }

    /** Persisted shape. */
    public record Persisted(List<Spool> spools, Map<String, String> assignments) {
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;

    /** spool id → spool. */
    private final Map<String, Spool> spools = new ConcurrentHashMap<>();
    /** "printerName|trayIndex" → spool id. */
    private final Map<String, String> assignments = new ConcurrentHashMap<>();
    private boolean dirty;

    private Path getPath() {
        return Path.of(config.maintenanceFile()).getParent() == null
                ? Path.of(STORE_FILENAME)
                : Path.of(config.maintenanceFile()).getParent().resolve(STORE_FILENAME);
    }

    private static String key(final String printer, final int tray) {
        return printer + "|" + tray;
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Persisted p = mapper.readValue(path.toFile(), Persisted.class);
            if (p.spools() != null) {
                p.spools().forEach(s -> spools.put(s.id(), s));
            }
            if (p.assignments() != null) {
                assignments.putAll(p.assignments());
            }
            Log.infof("SpoolService: loaded %d spool(s), %d assignment(s) from %s", spools.size(), assignments.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "SpoolService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(),
                    new Persisted(new ArrayList<>(spools.values()), new LinkedHashMap<>(assignments)));
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "SpoolService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    // -------------------------------------------------------------------------
    // Spool CRUD
    // -------------------------------------------------------------------------

    public List<Spool> getSpools() {
        return spools.values().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    public Optional<Spool> getSpool(final String id) {
        return Optional.ofNullable(spools.get(id));
    }

    /** Creates a new spool, remaining = total. Returns its generated id. */
    public synchronized String addSpool(final String name, final String material, final String color,
            final double totalGrams, final double lowThresholdGrams) {
        final String id = UUID.randomUUID().toString();
        spools.put(id, new Spool(id, name, material, color, totalGrams, totalGrams, lowThresholdGrams));
        dirty = true;
        save();
        return id;
    }

    /** Updates a spool's editable fields; remaining is clamped to [0, total]. */
    public synchronized void updateSpool(final String id, final String name, final String material, final String color,
            final double totalGrams, final double remainingGrams, final double lowThresholdGrams) {
        final Spool existing = spools.get(id);
        if (existing == null) {
            return;
        }
        final double remaining = Math.max(0, Math.min(remainingGrams, totalGrams));
        spools.put(id, new Spool(id, name, material, color, totalGrams, remaining, lowThresholdGrams));
        dirty = true;
        save();
    }

    /** Resets remaining back to total (a fresh spool of the same kind). */
    public synchronized void refill(final String id) {
        final Spool s = spools.get(id);
        if (s != null) {
            spools.put(id, new Spool(id, s.name(), s.material(), s.color(), s.totalGrams(), s.totalGrams(), s.lowThresholdGrams()));
            dirty = true;
            save();
        }
    }

    public synchronized void deleteSpool(final String id) {
        if (spools.remove(id) != null) {
            assignments.values().removeIf(v -> v.equals(id));
            dirty = true;
            save();
        }
    }

    // -------------------------------------------------------------------------
    // Assignment (which spool is loaded in which printer tray)
    // -------------------------------------------------------------------------

    public Optional<String> getAssignedSpoolId(final String printer, final int tray) {
        return Optional.ofNullable(assignments.get(key(printer, tray)));
    }

    public synchronized void assign(final String printer, final int tray, final String spoolId) {
        if (spoolId == null || spoolId.isBlank()) {
            assignments.remove(key(printer, tray));
        } else {
            assignments.put(key(printer, tray), spoolId);
        }
        dirty = true;
        save();
    }

    // -------------------------------------------------------------------------
    // Consumption
    // -------------------------------------------------------------------------

    /**
     * Subtracts {@code grams} from the spool assigned to the given printer+tray (if any), and fires a
     * {@code spool_low} notification when it crosses its low threshold. No-op when the tray is unknown ({@code <0}),
     * the weight is unknown ({@code <=0}), or no spool is assigned there.
     */
    public synchronized void recordUsage(final String printer, final int tray, final double grams) {
        if (tray < 0 || grams <= 0) {
            return;
        }
        final String spoolId = assignments.get(key(printer, tray));
        if (spoolId == null) {
            return;
        }
        final Spool s = spools.get(spoolId);
        if (s == null) {
            return;
        }
        final double before = s.remainingGrams();
        final double after = Math.max(0, before - grams);
        spools.put(spoolId, new Spool(s.id(), s.name(), s.material(), s.color(), s.totalGrams(), after, s.lowThresholdGrams()));
        dirty = true;
        save();
        Log.infof("SpoolService: %s tray %d used %.1fg from '%s' (%.0fg left)", printer, tray + 1, grams, s.name(), after);
        if (before > s.lowThresholdGrams() && after <= s.lowThresholdGrams()) {
            notificationService.notifyEvent("spool_low", printer,
                    "Spool '%s' (%s) is low: %.0fg left (used on %s tray %d)".formatted(s.name(), s.material(), after, printer, tray + 1));
        }
    }
}
