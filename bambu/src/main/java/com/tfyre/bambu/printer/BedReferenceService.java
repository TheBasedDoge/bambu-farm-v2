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
    /**
     * Only to invalidate cached readings when the reference changes - see {@link #saveReference}. Injecting the
     * diff service here rather than having callers remember to do it: there are already three call sites, and
     * "remember to also call X" is a rule that survives exactly until the fourth one.
     */
    @Inject
    BedDiffService bedDiff;
    /** Only to refuse a reference captured while the printer is mid-print - see {@link #saveReference}. */
    @Inject
    BambuPrinters printers;

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

    /**
     * When this printer's reference was captured, from the file's own timestamp - no extra state to keep in step.
     * <p>
     * Surfaced because age is the single best predictor of whether the pixel check still discriminates: the same
     * empty bed measured 0.19 against a fresh reference and 4.76-5.64 against a day-old one. A reading alone
     * doesn't tell you which situation you're in; the reading next to the age does.
     */
    public Optional<java.time.Instant> referenceCapturedAt(final String printerName) {
        final Path file = refFile(printerName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (IOException ex) {
            return Optional.empty();
        }
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
    /**
     * Captures a frame as this printer's empty-bed reference.
     * <p>
     * <b>Refuses while the printer is printing.</b> The reference IS the definition of "empty" for this machine,
     * so capturing one with a part on the plate teaches the backstop that the part is what an empty bed looks
     * like - it then reads near zero on an occupied bed forever after, and the one check that does not involve
     * the vision model is silently inverted. It has happened here twice, and both times the button was pressed
     * in good faith: a tooltip saying "make sure the bed is EMPTY" is not a guard, and the printer already knows
     * the answer. Also refuses in the post-print window, when the part that just finished is still sitting there.
     *
     * @throws IllegalStateException if the printer is printing, or if the image cannot be written
     */
    public void saveReference(final String printerName, final byte[] jpeg) {
        final Optional<BambuConst.GCodeState> state = printers.getPrinterDetail(printerName)
                .map(d -> d.printer().getGCodeState());
        if (state.filter(BambuConst.GCodeState::isPrinting).isPresent()) {
            throw new IllegalStateException(("%s is printing - a reference captured now would record the part as "
                    + "part of the empty bed. Wait until it finishes and the plate is cleared.")
                    .formatted(printerName));
        }
        try {
            Files.createDirectories(refDir());
            Files.write(refFile(printerName), jpeg);
            Log.infof("BedReferenceService: saved empty-bed reference for %s (%d bytes)", printerName, jpeg.length);
            // Mandatory, not tidy-up. Every cached reading was measured against the picture just replaced, so it
            // now answers a question nobody is asking - and until the next check ran, the UI kept reporting "bed
            // unverified" from it, immediately after the user had photographed a clean bed to fix exactly that.
            bedDiff.forgetMeasurement(printerName);
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot save reference for %s: %s", printerName, ex.getMessage());
            throw new IllegalStateException("Could not save reference image: " + ex.getMessage(), ex);
        }
    }

    public void clearReference(final String printerName) {
        try {
            Files.deleteIfExists(refFile(printerName));
            Log.infof("BedReferenceService: cleared empty-bed reference for %s", printerName);
            bedDiff.forgetMeasurement(printerName);
        } catch (IOException ex) {
            Log.errorf(ex, "BedReferenceService: cannot clear reference for %s: %s", printerName, ex.getMessage());
        }
    }
}
