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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EXPERIMENTAL: stores a per-printer "empty bed" reference photo so the bed-clear AI check can compare the current
 * frame against a known-empty ground truth for that specific printer - which handles per-bed texture, glue marks
 * and lighting far more reliably than judging a single image in isolation.
 * <p>
 * Reference images are JPEG files under a {@code bambu-bed-refs/} folder (keyed by a sanitized printer name); a
 * single global enable flag is persisted to {@code bambu-bed-reference.json}. When enabled AND a reference exists
 * for a printer, {@link PrintAiService} sends reference+current to the model instead of the normal single-image
 * bed-clear prompt.
 */
@ApplicationScoped
public class BedReferenceService {

    private static final String STORE_FILENAME = "bambu-bed-reference.json";
    private static final String REF_DIR = "bambu-bed-refs";

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;

    private final Map<String, Boolean> settings = new ConcurrentHashMap<>();

    private Path baseDir() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent : Path.of(".");
    }

    private Path settingsPath() {
        return baseDir().resolve(STORE_FILENAME);
    }

    private Path refDir() {
        return baseDir().resolve(REF_DIR);
    }

    private static String sanitize(final String printerName) {
        return printerName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private Path refFile(final String printerName) {
        return refDir().resolve(sanitize(printerName) + ".jpg");
    }

    @PostConstruct
    void load() {
        final Path path = settingsPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Boolean>>() {
            }));
            Log.infof("BedReferenceService: loaded settings from %s (enabled=%s)", path, isEnabled());
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsPath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot save %s: %s", settingsPath(), ex.getMessage());
        }
    }

    /** Whether the experimental "compare current bed against the saved empty reference" mode is on. */
    public boolean isEnabled() {
        return settings.getOrDefault("enabled", Boolean.FALSE);
    }

    public void setEnabled(final boolean enabled) {
        settings.put("enabled", enabled);
        save();
        Log.infof("BedReferenceService: reference-compare mode %s", enabled ? "enabled" : "disabled");
    }

    public boolean hasReference(final String printerName) {
        return Files.isRegularFile(refFile(printerName));
    }

    public Optional<byte[]> getReference(final String printerName) {
        final Path f = refFile(printerName);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(f));
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot read reference for %s: %s", printerName, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Persists a JPEG as the empty-bed reference for a printer. Throws (with a user-friendly message) on I/O error. */
    public void saveReference(final String printerName, final byte[] jpeg) {
        try {
            Files.createDirectories(refDir());
            Files.write(refFile(printerName), jpeg);
            Log.infof("BedReferenceService: saved empty-bed reference for %s (%d bytes)", printerName, jpeg.length);
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot save reference for %s: %s", printerName, ex.getMessage());
            throw new IllegalStateException("Could not save reference image: " + ex.getMessage(), ex);
        }
    }

    public void clearReference(final String printerName) {
        try {
            Files.deleteIfExists(refFile(printerName));
            Log.infof("BedReferenceService: cleared empty-bed reference for %s", printerName);
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot clear reference for %s: %s", printerName, ex.getMessage());
        }
    }
}
