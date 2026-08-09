package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simulate mode: rehearse the live pipeline without any printer actually printing.
 * <p>
 * Everything upstream of the print command runs for real - dispatch, printer eligibility, the filament match,
 * the bed-clear gate with its AI and pixel checks, the notifications. Only the irreversible parts are skipped.
 * That is the half of the pipeline the existing dry-run can't reach: {@code AutoQueueService.dryRun()} tests
 * eligibility, but nothing exercised dispatch or the bed gate without committing a real print.
 * <p>
 * <b>What it suppresses, and why each one:</b>
 * <ul>
 * <li><b>The print command</b> - the point.</li>
 * <li><b>SD-card upload</b> - a rehearsal shouldn't leave files on a printer.</li>
 * <li><b>On-hand stock</b> - a rehearsal shouldn't spend inventory.</li>
 * <li><b>Start verification</b> - nothing will start, so the 90s check would "fail" every time and dump jobs
 *     back into the pool with a 20-minute hold on the printer.</li>
 * <li><b>Auto-queueing REAL marketplace orders</b> - this is the important one. Rehearsing a real order would
 *     mark it queued and count its jobs, and it would then never print for real. So while this is on, real
 *     orders are left alone entirely and only what you queue by hand flows through.</li>
 * </ul>
 * <p>
 * <b>It expires by itself.</b> Left on, it silently stops orders reaching the printers, which is the exact
 * failure mode the poll-failure alert exists for - a farm that looks fine and quietly isn't. So it switches
 * itself off after {@link #MAX_DURATION} and says so.
 */
@ApplicationScoped
public class SimulationService {

    private static final String STORE_FILENAME = "bambu-simulate.json";
    /**
     * How long simulate mode stays on before switching itself off. Long enough to watch a dispatch cycle and a
     * bed check; short enough that forgetting about it costs you one poll interval of orders, not a night's.
     */
    private static final Duration MAX_DURATION = Duration.ofMinutes(60);

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;

    private final Map<String, Object> settings = new HashMap<>();

    private Path storePath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return (parent != null ? parent : Path.of(".")).resolve(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = storePath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Object>>() {
            }));
            if (isEnabled()) {
                Log.warnf("SimulationService: SIMULATE MODE IS ON (expires %s) - nothing will actually print",
                        expiresAt().map(Instant::toString).orElse("soon"));
            }
        } catch (IOException ex) {
            Log.errorf(ex, "SimulationService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(storePath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "SimulationService: cannot save %s: %s", storePath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    private Optional<Instant> expiresAt() {
        final Object v = settings.get("expiresAt");
        return v instanceof String s ? Optional.of(Instant.parse(s)) : Optional.empty();
    }

    /**
     * Whether the pipeline is currently rehearsing. Checked on every dispatch and print attempt, so the expiry
     * is evaluated here rather than on a timer - no scheduler needed, and it can never be "on" past its expiry
     * even if nothing ticks.
     */
    public boolean isEnabled() {
        if (!Boolean.TRUE.equals(settings.get("enabled"))) {
            return false;
        }
        final Optional<Instant> until = expiresAt();
        if (until.isPresent() && Instant.now().isAfter(until.get())) {
            setEnabled(false);
            Log.info("SimulationService: simulate mode expired - real printing is live again");
            notificationService.notifyEvent("simulate_mode", "farm",
                    "Simulate mode expired and switched itself off. Orders will print for real again.");
            return false;
        }
        return true;
    }

    public synchronized void setEnabled(final boolean enabled) {
        settings.put("enabled", enabled);
        if (enabled) {
            settings.put("expiresAt", Instant.now().plus(MAX_DURATION).toString());
        } else {
            settings.remove("expiresAt");
        }
        save();
        Log.warnf("SimulationService: simulate mode %s", enabled
                ? "ENABLED - nothing will print, and real marketplace orders will NOT be queued" : "disabled");
    }

    /** Minutes left before it switches itself off, for the UI. */
    public Optional<Long> minutesRemaining() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return expiresAt().map(t -> Math.max(0, Duration.between(Instant.now(), t).toMinutes()));
    }
}
