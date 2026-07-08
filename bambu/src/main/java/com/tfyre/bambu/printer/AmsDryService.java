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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime AMS drying settings — configured via the dashboard UI, persisted to JSON.
 *
 * Settings survive server restarts. The file is written alongside {@code bambu-maintenance.json}.
 */
@ApplicationScoped
public class AmsDryService {

    private static final String DEFAULT_FILENAME = "bambu-ams-dry.json";

    public static final int DEFAULT_TEMP = 55;
    public static final int DEFAULT_DURATION_HOURS = 4;

    /**
     * Per-printer drying configuration.
     *
     * @param autoOnFinish  trigger drying automatically when a print completes
     * @param temp          drying temperature in °C
     * @param durationHours drying duration in HOURS (firmware expects hours)
     */
    public record DrySetting(boolean autoOnFinish, int temp, int durationHours) {

        public static final DrySetting DEFAULT = new DrySetting(false, DEFAULT_TEMP, DEFAULT_DURATION_HOURS);

        public DrySetting withAutoOnFinish(final boolean enabled) {
            return new DrySetting(enabled, temp, durationHours);
        }

        public DrySetting withTempAndDuration(final int newTemp, final int newDuration) {
            return new DrySetting(autoOnFinish, newTemp, newDuration);
        }
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    BambuConfig config;

    /**
     * Tracks a single active drying session started from the app.
     * Used to show live status (remaining time) in the dashboard.
     */
    public record DryingSession(int amsId, Instant startedAt, int durationHours, int temp) {

        /** True if the drying cycle hasn't expired yet. */
        public boolean isActive() {
            return Instant.now().isBefore(startedAt.plusSeconds(durationHours * 3600L));
        }

        /** Minutes remaining in the drying cycle (0 when expired). */
        public long remainingMinutes() {
            return Math.max(0, Duration.between(Instant.now(),
                    startedAt.plusSeconds(durationHours * 3600L)).toMinutes());
        }
    }

    private final ConcurrentHashMap<String, DrySetting> settings = new ConcurrentHashMap<>();

    /** Active drying sessions keyed by printer name. */
    private final ConcurrentHashMap<String, List<DryingSession>> activeSessions = new ConcurrentHashMap<>();

    /**
     * Records the start of a drying session (called when commandAmsDry is issued from the app).
     */
    public void recordDrying(final String printerName, final int amsId, final int temp, final int durationHours) {
        final List<DryingSession> sessions = activeSessions.computeIfAbsent(printerName, k -> new CopyOnWriteArrayList<>());
        sessions.removeIf(s -> s.amsId() == amsId); // replace any existing session for this unit
        sessions.add(new DryingSession(amsId, Instant.now(), durationHours, temp));
    }

    /**
     * Clears a tracked drying session (called when stop dry is issued from the app).
     */
    public void clearDrying(final String printerName, final int amsId) {
        final List<DryingSession> sessions = activeSessions.get(printerName);
        if (sessions != null) {
            sessions.removeIf(s -> s.amsId() == amsId);
        }
    }

    /**
     * Returns the active drying session for the given AMS unit, if any.
     * Empty if no session was recorded or the session has expired.
     */
    public Optional<DryingSession> getActiveDrying(final String printerName, final int amsId) {
        final List<DryingSession> sessions = activeSessions.get(printerName);
        if (sessions == null) {
            return Optional.empty();
        }
        return sessions.stream()
                .filter(s -> s.amsId() == amsId && s.isActive())
                .findFirst();
    }

    private Path getPath() {
        // Store in same directory as maintenance file, or current dir if unconfigured
        final Path maintenance = Path.of(config.maintenanceFile());
        final Path parent = maintenance.getParent();
        return parent != null ? parent.resolve(DEFAULT_FILENAME) : Path.of(DEFAULT_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Map<String, DrySetting> loaded = mapper.readValue(path.toFile(),
                    new TypeReference<Map<String, DrySetting>>() {});
            settings.putAll(loaded);
            Log.infof("AmsDryService: loaded %d printer setting(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "AmsDryService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        final Path path = getPath();
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "AmsDryService: cannot save %s: %s", path, ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public DrySetting getSetting(final String printerName) {
        return settings.getOrDefault(printerName, DrySetting.DEFAULT);
    }

    public void setSetting(final String printerName, final DrySetting setting) {
        settings.put(printerName, setting);
        save(); // write immediately so a crash doesn't lose the setting
    }

}
