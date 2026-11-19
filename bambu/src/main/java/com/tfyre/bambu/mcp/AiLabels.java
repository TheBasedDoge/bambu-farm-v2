package com.tfyre.bambu.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** A labelled frame's check, kept beside its JPEG in {@code data/bambu-ai-labelled/}. */
    record Kept(long at, String printer, String checkType, String trigger, String context, Boolean good,
            String severity, String description, Double pixelDiff) {
    }

    /**
     * Copies a labelled frame out of the AI history, which rotates its failure frames out within about a day:
     * the P1S corner-lift frame labelled on 11-10 was gone by 11-12, when it was needed to test a prompt that
     * looks for lifting. A label without its frame cannot score anything.
     */
    public synchronized void keep(final PrintAiService.CheckRecord r, final byte[] jpeg) {
        if (r == null || r.at() == null || jpeg == null || jpeg.length == 0) {
            return;
        }
        final String id = r.printer() + "@" + r.at().toEpochMilli();
        try {
            Files.createDirectories(keptDir());
            Files.write(keptDir().resolve(safe(id) + ".jpg"), jpeg);
            mapper.writeValue(keptDir().resolve(safe(id) + ".json").toFile(), new Kept(r.at().toEpochMilli(), r.printer(),
                    r.checkType(), r.trigger(), r.context(), r.good(), r.severity() == null ? null : r.severity().name(),
                    r.description(), r.pixelDiff()));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("AiLabels: could not keep frame %s: %s", id, ex.getMessage());
        }
    }

    public synchronized void drop(final String frameId) {
        try {
            Files.deleteIfExists(keptDir().resolve(safe(frameId) + ".jpg"));
            Files.deleteIfExists(keptDir().resolve(safe(frameId) + ".json"));
        } catch (IOException ex) {
            Log.warnf("AiLabels: could not drop kept frame %s: %s", frameId, ex.getMessage());
        }
    }

    /** A kept labelled frame as a check record (with its JPEG), for frames the history no longer has. */
    public synchronized Optional<PrintAiService.CheckRecord> kept(final String frameId) {
        final Path meta = keptDir().resolve(safe(frameId) + ".json");
        final Path jpg = keptDir().resolve(safe(frameId) + ".jpg");
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(jpg)) {
            return Optional.empty();
        }
        try {
            final Kept k = mapper.readValue(meta.toFile(), Kept.class);
            OllamaService.Severity sev = null;
            if (k.severity() != null) {
                try {
                    sev = OllamaService.Severity.valueOf(k.severity());
                } catch (IllegalArgumentException ignored) {
                    // an older enum value - leave it blank
                }
            }
            return Optional.of(new PrintAiService.CheckRecord(Instant.ofEpochMilli(k.at()), k.printer(), k.checkType(),
                    k.trigger(), k.context(), k.good(), sev, k.description(), Files.readAllBytes(jpg), k.pixelDiff()));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("AiLabels: could not read kept frame %s: %s", frameId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Every labelled frame that has a kept copy, newest first. */
    public synchronized List<PrintAiService.CheckRecord> allKept() {
        final List<PrintAiService.CheckRecord> out = new ArrayList<>();
        labels.keySet().forEach(id -> kept(id).ifPresent(out::add));
        out.sort(Comparator.comparing(PrintAiService.CheckRecord::at).reversed());
        return out;
    }

    static String safe(final String frameId) {
        return frameId.replaceAll("[^A-Za-z0-9@_-]", "_");
    }

    private Path keptDir() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve("bambu-ai-labelled");
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
