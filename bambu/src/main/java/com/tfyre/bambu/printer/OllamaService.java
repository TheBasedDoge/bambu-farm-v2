package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thin HTTP wrapper around a local Ollama server for vision-based AI checks.
 * All public methods return empty when Ollama is not configured or the call fails.
 *
 * Configure via:
 *   bambu.ollama.url=http://192.168.1.x:11434
 *   bambu.ollama.model=gemma3:12b
 */
@ApplicationScoped
public class OllamaService {

    /**
     * Severity of a check result.
     * OK   = positive outcome (bed clear, print OK, good first layer)
     * WARN = outcome is uncertain or a possible/minor issue detected
     * FAIL = definitive failure or problem detected
     */
    public enum Severity { OK, WARN, FAIL }

    /** First word of model responses that act as keywords. Stripped from the displayed description. */
    private static final Set<String> RESPONSE_KEYWORDS = Set.of("YES", "NO", "GOOD", "POOR");

    /** Words that indicate hedging/uncertainty in a negative response → WARN instead of FAIL. */
    private static final List<String> HEDGING_WORDS = List.of(
            "might", "possibly", "possible", "could", "may ", "perhaps",
            "unclear", "unsure", "uncertain", "hard to tell", "difficult to",
            "cannot confirm", "not certain", "it appears", "it seems",
            "seems like", "looks like it might", "suspect"
    );

    private static final String BED_CLEAR_PROMPT =
            "Is this 3D printer bed completely empty and clear?\n"
            + "Look for any printed parts, failed prints, filament blobs, or debris on the bed surface.\n"
            + "Answer YES if the bed is clear and ready for the next print, "
            + "or NO if anything is still on the bed.\n"
            + "After YES or NO, briefly describe what you see.";

    private static final String FAILURE_PROMPT =
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

    private static final String FIRST_LAYER_PROMPT =
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
     * Result of an AI image analysis.
     *
     * @param positive  true when the check passed (bed IS clear, print is NOT failing, first layer IS good)
     * @param severity  confidence level: OK (positive result), WARN (uncertain negative), FAIL (definite negative)
     * @param description the model's explanation with the leading keyword word stripped
     */
    public record AiResult(boolean positive, Severity severity, String description) {}

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isEnabled() {
        return config.ollama().url().isPresent();
    }

    /**
     * Sends imageJpeg to Ollama with the given prompt. Returns empty on any error.
     *
     * @param imageJpeg      raw JPEG bytes
     * @param prompt         the question to ask the model
     * @param positiveKeyword the word that, when found at the start of the response, means positive=true
     */
    private Optional<AiResult> analyze(final byte[] imageJpeg, final String prompt, final String positiveKeyword) {
        final Optional<String> urlOpt = config.ollama().url();
        if (urlOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            final String base64 = Base64.getEncoder().encodeToString(imageJpeg);
            final Map<String, Object> body = Map.of(
                    "model", config.ollama().model(),
                    "prompt", prompt,
                    "images", List.of(base64),
                    "stream", false
            );
            final HttpRequest request = HttpRequest.newBuilder(URI.create(urlOpt.get() + "/api/generate"))
                    .timeout(config.ollama().timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("OllamaService: HTTP %d: %s", response.statusCode(), response.body());
                return Optional.empty();
            }

            final JsonNode root = mapper.readTree(response.body());
            final String text = root.path("response").asText("").trim();
            final boolean positive = text.toUpperCase().startsWith(positiveKeyword.toUpperCase());
            final String description = stripKeyword(text);
            final Severity severity = deriveSeverity(positive, description);

            Log.debugf("OllamaService: [%s] → positive=%b severity=%s — %s", positiveKeyword, positive, severity, description);
            return Optional.of(new AiResult(positive, severity, description));

        } catch (Exception ex) {
            Log.errorf(ex, "OllamaService: analyze failed: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Removes the leading YES/NO/GOOD/POOR keyword (and any following punctuation/space)
     * from the model's raw response so the displayed description is clean.
     */
    private static String stripKeyword(final String text) {
        if (text.isBlank()) {
            return text;
        }
        final String[] parts = text.split("\\s+", 2);
        final String firstWord = parts[0].replaceAll("[.,;:!?*#]+$", "").toUpperCase();
        if (RESPONSE_KEYWORDS.contains(firstWord) && parts.length > 1) {
            final String rest = parts[1].replaceFirst("^[.,;:\\s]+", "").trim();
            return rest.isEmpty() ? text : rest;
        }
        return text;
    }

    /**
     * Derives severity from whether the result is positive and whether the description
     * uses hedging/uncertain language.
     */
    private static Severity deriveSeverity(final boolean positive, final String description) {
        if (positive) {
            return Severity.OK;
        }
        final String lower = description.toLowerCase();
        final boolean hedging = HEDGING_WORDS.stream().anyMatch(lower::contains);
        return hedging ? Severity.WARN : Severity.FAIL;
    }

    /**
     * Derives display severity from the application-level {@code good} flag.
     * Unlike {@link #deriveSeverity(boolean, String)}, this takes the already-inverted
     * "is this outcome good?" boolean, so callers don't need to know which direction
     * each check type uses for its positive keyword.
     * <p>
     * Use this in {@code PrintAiService} when storing {@code AiCheckResult} to ensure
     * {@code severity} always agrees with {@code good}.
     */
    public static Severity severityFor(final boolean good, final String description) {
        if (good) {
            return Severity.OK;
        }
        final String lower = description.toLowerCase();
        final boolean hedging = HEDGING_WORDS.stream().anyMatch(lower::contains);
        return hedging ? Severity.WARN : Severity.FAIL;
    }

    /**
     * Checks whether the print bed is clear. positive=true means the bed IS clear.
     */
    public Optional<AiResult> checkBedClear(final byte[] imageJpeg) {
        return analyze(imageJpeg, BED_CLEAR_PROMPT, "YES");
    }

    /**
     * Checks whether a print is failing (spaghetti, detached layers, blobs). positive=true means a failure IS detected.
     */
    public Optional<AiResult> checkFailure(final byte[] imageJpeg) {
        return analyze(imageJpeg, FAILURE_PROMPT, "YES");
    }

    /**
     * Checks first-layer quality. positive=true means the first layer looks GOOD.
     */
    public Optional<AiResult> checkFirstLayer(final byte[] imageJpeg) {
        return analyze(imageJpeg, FIRST_LAYER_PROMPT, "GOOD");
    }

}
