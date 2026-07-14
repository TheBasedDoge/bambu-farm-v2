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
    @Inject
    AiPromptService prompts;

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
     * @param context        optional printer status context (e.g. active HMS alerts) to prepend to the
     *                       prompt as a hint - see {@link #withContext(String, Optional)}
     */
    private Optional<AiResult> analyze(final byte[] imageJpeg, final String prompt, final String positiveKeyword, final Optional<String> context) {
        final Optional<String> urlOpt = config.ollama().url();
        if (urlOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            final String base64 = Base64.getEncoder().encodeToString(imageJpeg);
            final Map<String, Object> body = Map.of(
                    "model", config.ollama().model(),
                    "prompt", withContext(prompt, context),
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
     * Prepends printer status context (e.g. active HMS alerts, a printer error code) to a prompt as a hint,
     * when present. Phrased so the model treats it as a clue to correlate with the image rather than an
     * instruction to override what it actually sees - a stale or unrelated HMS code (e.g. an AMS calibration
     * reminder) shouldn't force a false-positive failure/poor-bed-clear result.
     */
    private static String withContext(final String prompt, final Optional<String> context) {
        return context.filter(c -> !c.isBlank())
                .map(c -> "Context: the printer's control board is currently reporting: " + c + ". "
                        + "This may or may not be visible in the image or relevant to this specific question - "
                        + "use it only as a hint, and base your answer primarily on what you actually observe.\n\n" + prompt)
                .orElse(prompt);
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
     *
     * @param context optional printer status context (e.g. active HMS alerts) - see {@link #withContext}
     */
    public Optional<AiResult> checkBedClear(final byte[] imageJpeg, final Optional<String> context) {
        return analyze(imageJpeg, prompts.getPrompt(AiPromptService.PromptType.BED_CLEAR),
                AiPromptService.PromptType.BED_CLEAR.positiveKeyword(), context);
    }

    /**
     * Checks whether a print is failing (spaghetti, detached layers, blobs). positive=true means a failure IS detected.
     *
     * @param context optional printer status context (e.g. active HMS alerts) - see {@link #withContext}
     */
    public Optional<AiResult> checkFailure(final byte[] imageJpeg, final Optional<String> context) {
        return analyze(imageJpeg, prompts.getPrompt(AiPromptService.PromptType.FAILURE),
                AiPromptService.PromptType.FAILURE.positiveKeyword(), context);
    }

    /**
     * Checks first-layer quality. positive=true means the first layer looks GOOD.
     *
     * @param context optional printer status context (e.g. active HMS alerts) - see {@link #withContext}
     */
    public Optional<AiResult> checkFirstLayer(final byte[] imageJpeg, final Optional<String> context) {
        return analyze(imageJpeg, prompts.getPrompt(AiPromptService.PromptType.FIRST_LAYER),
                AiPromptService.PromptType.FIRST_LAYER.positiveKeyword(), context);
    }

}
