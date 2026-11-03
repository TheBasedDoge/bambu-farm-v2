package com.tfyre.bambu.mcp;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ground truth for AI check frames, set by a person (through an agent): was there really a problem in this frame?
 * Kept in {@code data/bambu-ai-labels.json} (the bambu- prefix puts it in the nightly backup) by frame id ({@code printer@epochMillis}) so eval_prompt can score a prompt
 * against it. Labels outlive the frames' rotation out of the AI history; a label for a pruned frame is harmless.
 */
@ApplicationScoped
public class AiLabels {

    /**
     * @param truth "problem" (something really was wrong) or "ok" (nothing was)
     * @param label the word the person used (failure, ok, false_alarm, missed, ...)
     */
    public record Label(String truth, String label, String note, String at) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private final Map<String, Label> labels = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        final Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            labels.putAll(mapper.readValue(f.toFile(), new TypeReference<Map<String, Label>>() {
            }));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("AiLabels: could not read %s: %s", f, ex.getMessage());
        }
    }

    /** Maps the words people use to the two truths; empty for anything else. */
    static Optional<String> truthOf(final String label) {
        if (label == null) {
            return Optional.empty();
        }
        return switch (label.trim().toLowerCase()) {
            case "problem", "failure", "fail", "missed", "spaghetti", "not_clear", "not-clear", "dirty", "bad" -> Optional.of("problem");
            case "ok", "good", "fine", "false_alarm", "false-alarm", "clear" -> Optional.of("ok");
            default -> Optional.empty();
        };
    }

    public synchronized Optional<Label> get(final String frameId) {
        return Optional.ofNullable(labels.get(frameId));
    }

    public synchronized Map<String, Label> all() {
        return Map.copyOf(labels);
    }

    /** Sets (or with a blank label, removes) a frame's label and saves. */
    public synchronized void set(final String frameId, final String truth, final String label, final String note) {
        if (truth == null) {
            labels.remove(frameId);
        } else {
            labels.put(frameId, new Label(truth, label, note == null ? "" : note, Instant.now().toString()));
        }
        save();
    }

    private void save() {
        final Path f = file();
        final Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), labels);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            Log.warnf("AiLabels: could not save %s: %s", f, ex.getMessage());
        }
    }

    private Path file() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve("bambu-ai-labels.json");
    }
}
