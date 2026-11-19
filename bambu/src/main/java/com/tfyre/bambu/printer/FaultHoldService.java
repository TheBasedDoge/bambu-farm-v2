package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Printers taken out of automatic work after a hardware fault, until a person says they are fixed.
 * <p>
 * 2026-11-12: the H2D failed Auto Bed Leveling (0300-4002, eddy-current sensor alert 0300-2600) and auto-requeue
 * started the same job on it again six minutes later - the sensor was still dirty, so it failed the same way, and
 * that spent the job's one retry. A retry only helps when the cause might have gone away by itself (a tangle, a
 * blob, a flaky feed); a sensor, a motor or a homing failure needs hands.
 * <p>
 * A held printer gets nothing from the dispatch pool or its own queue. The job that failed goes back to the pool
 * for its order, so another printer that can print it takes it - or this one, once released. Released by a person
 * pressing "Bed cleared" on it (any of the buttons, the alert link, or an agent told to), which is the action that
 * already means "I have dealt with this printer". Kept in {@code data/bambu-fault-holds.json} so a restart does
 * not quietly put a broken printer back to work.
 */
@ApplicationScoped
public class FaultHoldService {

    public record Hold(String reason, String since) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        final Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            holds.putAll(mapper.readValue(f.toFile(), new TypeReference<Map<String, Hold>>() {
            }));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("FaultHoldService: could not read %s: %s", f, ex.getMessage());
        }
    }

    public void hold(final String printer, final String reason) {
        holds.put(printer, new Hold(reason, Instant.now().toString()));
        save();
        Log.warnf("FaultHoldService: %s held - %s", printer, reason);
    }

    public boolean isHeld(final String printer) {
        return holds.containsKey(printer);
    }

    public Optional<Hold> get(final String printer) {
        return Optional.ofNullable(holds.get(printer));
    }

    /** @return true when the printer was held */
    public boolean release(final String printer, final String by) {
        if (holds.remove(printer) == null) {
            return false;
        }
        save();
        Log.infof("FaultHoldService: %s released (%s)", printer, by);
        return true;
    }

    private Path file() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve("bambu-fault-holds.json");
    }

    private synchronized void save() {
        final Path f = file();
        final Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), Map.copyOf(holds));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            Log.errorf("FaultHoldService: could not write %s: %s", f, ex.getMessage());
        }
    }
}
