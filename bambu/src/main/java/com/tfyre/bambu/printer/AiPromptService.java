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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-editable prompts for the three AI vision checks, persisted to {@code bambu-ai-prompts.json}.
 * Only customized prompts are stored - anything not overridden falls back to the built-in default, so
 * default-prompt improvements in future versions still reach users who never customized that prompt.
 * Edited from the AI Settings page; changes apply to the next check immediately, no restart needed.
 */
@ApplicationScoped
public class AiPromptService {

    private static final String STORE_FILENAME = "bambu-ai-prompts.json";

    private static final String DEFAULT_BED_CLEAR =
            "Is this 3D printer bed completely empty and clear?\n"
            + "Look for any printed parts, failed prints, filament blobs, or debris on the bed surface.\n"
            + "Answer YES if the bed is clear and ready for the next print, "
            + "or NO if anything is still on the bed.\n"
            + "After YES or NO, briefly describe what you see.";

    private static final String DEFAULT_FAILURE =
            "Is this 3D print actively failing right now?\n\n"
            + "Only answer YES if you see clear evidence of one of these specific failures:\n"
            + "- SPAGHETTI: loose filament strands floating or tangled in the air that are clearly "
            + "NOT attached to the main print structure\n"
            + "- BLOB: a large unintended mass of melted filament building up in the wrong place\n"
            + "- DETACHED PRINT: the printed object has come off the bed and is being dragged by the nozzle\n\n"
            + "Do NOT answer YES for any of these normal print features:\n"
            + "- Supports, brims, rafts, skirts, or support interfaces (these are intentional structures)\n"
            + "- Dense lattice infill, gyroid, or honeycomb patterns visible through the walls\n"
            + "- Complex overhangs, organic shapes, or intricate geometry\n"
            + "- Rough textures, layer lines, seams, or minor surface variations\n"
            + "- Multiple objects printing together\n\n"
            + "Answer NO if the print looks like it is progressing normally even if it looks complex or unusual.\n"
            + "Answer with YES or NO first, then briefly describe what you observe.";

    private static final String DEFAULT_FIRST_LAYER =
            "Examine the first layer of this 3D print on the printer bed.\n\n"
            + "Answer GOOD if:\n"
            + "- Filament lines are laying flat and sticking down evenly\n"
            + "- The lines look smooth and consistent\n\n"
            + "Answer POOR if:\n"
            + "- Lines are peeling up, curling, or not sticking to the bed\n"
            + "- Visible gaps between lines (under-extrusion)\n"
            + "- No filament being deposited at all\n\n"
            + "Answer GOOD or POOR first, then briefly describe what you see.";

    /**
     * The three check prompts. {@code positiveKeyword} is the word the model is instructed to lead with for a
     * positive outcome - keep custom prompts asking for the same leading keyword, or result parsing breaks
     * (the AI Settings page warns about this next to the editor).
     */
    public enum PromptType {
        BED_CLEAR("bed-clear", "Bed Clear", "YES"),
        FAILURE("failure", "Failure Detection", "YES"),
        FIRST_LAYER("first-layer", "First Layer", "GOOD");

        private final String key;
        private final String label;
        private final String positiveKeyword;

        PromptType(final String key, final String label, final String positiveKeyword) {
            this.key = key;
            this.label = label;
            this.positiveKeyword = positiveKeyword;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String positiveKeyword() {
            return positiveKeyword;
        }

        public String defaultPrompt() {
            return switch (this) {
                case BED_CLEAR -> DEFAULT_BED_CLEAR;
                case FAILURE -> DEFAULT_FAILURE;
                case FIRST_LAYER -> DEFAULT_FIRST_LAYER;
            };
        }
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;

    /** prompt key → custom prompt text. Absent = use the built-in default. */
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            overrides.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, String>>() {
            }));
            Log.infof("AiPromptService: %d custom prompt(s) loaded from %s", overrides.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "AiPromptService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), overrides);
        } catch (IOException ex) {
            Log.errorf(ex, "AiPromptService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    /** The active prompt for a check: the user's custom text if set, otherwise the built-in default. */
    public String getPrompt(final PromptType type) {
        return overrides.getOrDefault(type.key(), type.defaultPrompt());
    }

    public boolean isCustomized(final PromptType type) {
        return overrides.containsKey(type.key());
    }

    /**
     * Sets a custom prompt. Blank text or text identical to the default removes the override instead,
     * so "back to stock" is always representable as "no entry stored".
     */
    public void setPrompt(final PromptType type, final String text) {
        if (text == null || text.isBlank() || text.strip().equals(type.defaultPrompt().strip())) {
            overrides.remove(type.key());
        } else {
            overrides.put(type.key(), text.strip());
        }
        save();
        Log.infof("AiPromptService: %s prompt %s", type.key(), isCustomized(type) ? "customized" : "reset to default");
    }

    public void reset(final PromptType type) {
        overrides.remove(type.key());
        save();
        Log.infof("AiPromptService: %s prompt reset to default", type.key());
    }

}
