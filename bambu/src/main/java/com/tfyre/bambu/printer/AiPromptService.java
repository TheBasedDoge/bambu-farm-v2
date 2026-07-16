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

    // Prompts are tuned for gemma3:12b vision: one clear question, an explicit and short list of what counts,
    // "ignore this / treat as normal" guardrails to cut false positives, and a strict "one keyword first, then one
    // short sentence" output contract the result parser depends on.
    private static final String DEFAULT_BED_CLEAR =
            "Look at this 3D printer build plate image. Is there a 3D printed object on the bed?\n\n"
            + "A NORMAL EMPTY BED looks like this - treat EVERY one of these as EMPTY, never as an object:\n"
            + "- Dark textured PEI surface with a speckled/mottled pattern (this is normal)\n"
            + "- White or silver dashed grid lines on the bed surface (this is normal)\n"
            + "- White or cloudy glue residue smears or patches (this is normal)\n"
            + "- Faint outlines or marks left by previous prints (this is normal)\n"
            + "- Thin filament wisps laying flat (this is normal)\n"
            + "- Reflections and light glare on the surface (this is normal)\n\n"
            + "ABOUT THE BED: the bed is a FLAT RECTANGLE. It has no circular or ring-shaped features of its own. "
            + "Any round or circular shape sitting on the plate is a printed object, not part of the bed or the machine.\n\n"
            + "THE BED IS NOT CLEAR ONLY IF a solid 3D printed plastic object is sitting on top of the bed surface - "
            + "a distinct shape (cup, ring, bracket, box, cylinder, etc.) that rises above the flat bed and has clear "
            + "edges and walls. If you see ONLY the normal bed features listed above, the bed IS clear.\n\n"
            + "The VERY FIRST word of your reply MUST be YES or NO: YES if the bed is clear and empty, NO if any "
            + "printed object is on it. Then add, each on its own line:\n"
            + "Objects: <list everything you can see ON the bed surface, or \"none\">\n"
            + "Confidence: <0-100, how likely the bed is EMPTY; any object on the bed = 0>\n"
            + "Reason: <what you see, in one short sentence>";

    private static final String DEFAULT_FAILURE =
            "Look at this image of a 3D print in progress. Is the print clearly failing right now?\n\n"
            + "NORMAL, HEALTHY PRINTING looks like this - treat EVERY one of these as NORMAL, never as a failure:\n"
            + "- Support structures, brims, rafts, skirts, and support interfaces (these are intentional)\n"
            + "- Infill patterns showing through the walls - gyroid, honeycomb, grid, lattice (this is normal)\n"
            + "- Overhangs, bridges, and organic or complex geometry (this is normal)\n"
            + "- Thin strings or wisps of filament between parts (this is normal, not a failure)\n"
            + "- Layer lines, seams, rough texture, or minor surface blemishes (this is normal)\n"
            + "- Several separate objects printing on the plate at once (this is normal)\n"
            + "- The nozzle, hotend, or toolhead passing over or resting near the print (this is normal)\n\n"
            + "THE PRINT IS FAILING ONLY IF you clearly see one of these:\n"
            + "- SPAGHETTI: loose strands of filament tangled in the air, not attached to the object\n"
            + "- BLOB: a large unintended glob or mass of extruded filament building up in the wrong place\n"
            + "- DETACHED: the object has come off the plate and is being dragged around by the nozzle\n"
            + "If the print looks like it is still building up layer by layer - even if it is complex, busy, or "
            + "slightly messy - it is NOT failing.\n\n"
            + "The VERY FIRST word of your reply MUST be YES or NO: YES if the print is clearly failing, NO if it "
            + "looks fine. Then add, each on its own line:\n"
            + "Problems: <list any failure you can see, or \"none\">\n"
            + "Confidence: <0-100, how likely the print is FAILING; a normal-looking print = 0>\n"
            + "Reason: <what you see, in one short sentence>";

    private static final String DEFAULT_FIRST_LAYER =
            "Look at this image of the FIRST layer of a 3D print on the bed. Is the first layer sticking down well?\n\n"
            + "A GOOD FIRST LAYER looks like this:\n"
            + "- Filament lines lie flat against the plate and are stuck down\n"
            + "- Lines are evenly spaced and touching, with a smooth, consistent surface\n"
            + "- Solid, uniform coverage with no gaps in the filled areas\n\n"
            + "THE FIRST LAYER IS POOR ONLY IF you clearly see one of these:\n"
            + "- Lines curling, lifting, or peeling up off the plate\n"
            + "- Filament not sticking - being dragged around or balled up on the nozzle\n"
            + "- Visible gaps or spaces between lines that should be touching (under-extrusion)\n"
            + "- Little or no filament being laid down at all\n"
            + "The plate's texture or pattern, grid lines, glue marks, reflections, and seams are NOT problems.\n\n"
            + "The VERY FIRST word of your reply MUST be GOOD or POOR: GOOD if the first layer is adhering well, "
            + "POOR if it shows any of the problems above. Then add, each on its own line:\n"
            + "Observations: <what the first-layer lines look like>\n"
            + "Confidence: <0-100, how likely the first layer is GOOD; a clear adhesion problem = 0>\n"
            + "Reason: <what you see, in one short sentence>";

    /**
     * Prepended to any check prompt when the printer is actively reporting an HMS alert or print-error code, so the
     * model gets that as a hint. {@code {context}} is replaced with the live code/description; keep the placeholder.
     * This is not a standalone check - it's the wrapper that makes the three checks above HMS-aware.
     */
    private static final String DEFAULT_HMS_CONTEXT =
            "Note: the printer's control board is currently reporting: {context}. "
            + "This may or may not be visible in the image or relevant to this question - use it only as a hint, "
            + "and base your answer mainly on what you actually see.";

    /** Persisted key for the HMS/error context wrapper (kept separate from the {@link PromptType} checks). */
    private static final String CONTEXT_KEY = "hms-context";

    /** Persisted key for the experimental empty-bed reference-compare prompt (two images: reference + current). */
    private static final String BED_REFERENCE_KEY = "bed-clear-reference";

    /**
     * EXPERIMENTAL bed-clear prompt used when a saved empty-bed reference exists for the printer. The model is sent
     * TWO images - image 1 the empty reference, image 2 the current bed - and asked to compare, which is far more
     * reliable than judging one image alone. Must still answer YES/NO first (YES = current bed is clear).
     */
    private static final String DEFAULT_BED_REFERENCE =
            "You are given TWO images of the SAME 3D printer bed from the same fixed camera.\n"
            + "IMAGE 1 is the REFERENCE: this exact bed when it is EMPTY and clear.\n"
            + "IMAGE 2 is the bed RIGHT NOW.\n\n"
            + "Compare IMAGE 2 against IMAGE 1. IGNORE any differences in lighting, brightness, glare, reflections, "
            + "shadows, glue residue, the bed's texture/pattern, grid lines, and faint marks left by previous prints "
            + "- none of those are objects. A printed object is a solid 3D shape that is present in IMAGE 2 but NOT "
            + "in IMAGE 1, rising above the bed surface with clear edges and walls.\n\n"
            + "The VERY FIRST word of your reply MUST be YES or NO: YES if IMAGE 2 is clear and empty (it matches "
            + "the reference), NO if there is a printed object on the bed in IMAGE 2 that is not in the reference. "
            + "Then add, each on its own line:\n"
            + "Objects: <what is on the current bed that is not in the reference, or \"none\">\n"
            + "Confidence: <0-100, how likely the current bed is EMPTY>\n"
            + "Reason: <what you see, in one short sentence>";

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

    // -------------------------------------------------------------------------
    // HMS / error context wrapper (makes the three checks above HMS-aware)
    // -------------------------------------------------------------------------

    /** The active HMS-context template (custom if set, else the built-in default). Contains a {@code {context}} placeholder. */
    public String getContextTemplate() {
        return overrides.getOrDefault(CONTEXT_KEY, DEFAULT_HMS_CONTEXT);
    }

    public String defaultContextTemplate() {
        return DEFAULT_HMS_CONTEXT;
    }

    public boolean isContextCustomized() {
        return overrides.containsKey(CONTEXT_KEY);
    }

    public void setContextTemplate(final String text) {
        if (text == null || text.isBlank() || text.strip().equals(DEFAULT_HMS_CONTEXT.strip())) {
            overrides.remove(CONTEXT_KEY);
        } else {
            overrides.put(CONTEXT_KEY, text.strip());
        }
        save();
        Log.infof("AiPromptService: HMS-context template %s", isContextCustomized() ? "customized" : "reset to default");
    }

    public void resetContext() {
        overrides.remove(CONTEXT_KEY);
        save();
    }

    /** Renders the context hint for a concrete HMS/error string, substituting the {@code {context}} placeholder. */
    public String renderContext(final String context) {
        final String template = getContextTemplate();
        return template.contains("{context}") ? template.replace("{context}", context) : template + " " + context;
    }

    // -------------------------------------------------------------------------
    // Experimental empty-bed reference-compare prompt (two images)
    // -------------------------------------------------------------------------

    public String getBedReferencePrompt() {
        return overrides.getOrDefault(BED_REFERENCE_KEY, DEFAULT_BED_REFERENCE);
    }

    public String defaultBedReferencePrompt() {
        return DEFAULT_BED_REFERENCE;
    }

    public boolean isBedReferenceCustomized() {
        return overrides.containsKey(BED_REFERENCE_KEY);
    }

    public void setBedReferencePrompt(final String text) {
        if (text == null || text.isBlank() || text.strip().equals(DEFAULT_BED_REFERENCE.strip())) {
            overrides.remove(BED_REFERENCE_KEY);
        } else {
            overrides.put(BED_REFERENCE_KEY, text.strip());
        }
        save();
        Log.infof("AiPromptService: bed-reference prompt %s", isBedReferenceCustomized() ? "customized" : "reset to default");
    }

    public void resetBedReference() {
        overrides.remove(BED_REFERENCE_KEY);
        save();
    }

}
