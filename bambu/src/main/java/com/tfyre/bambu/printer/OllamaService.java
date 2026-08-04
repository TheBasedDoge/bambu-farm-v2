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

    /**
     * What {@link #parseVerdict} decided, and whether that decision overrode the model.
     *
     * @param positive          the verdict the caller acts on
     * @param downgradedBecause set only when a positive answer was turned into a negative one by a safety rule,
     *                          so the check history can say why. Empty when the model's own answer stands - a
     *                          reply that genuinely reported an object needs no explanation from us.
     */
    record Verdict(boolean positive, Optional<String> downgradedBecause) {}

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
        return analyze(List.of(imageJpeg), prompt, positiveKeyword, context);
    }

    /** Multi-image variant: sends every image in order (e.g. [reference, current] for a bed comparison). */
    private Optional<AiResult> analyze(final List<byte[]> imagesJpeg, final String prompt, final String positiveKeyword, final Optional<String> context) {
        final Optional<String> urlOpt = config.ollama().url();
        if (urlOpt.isEmpty()) {
            return Optional.empty();
        }
        final HttpResponse<String> response;
        try {
            final List<String> base64 = imagesJpeg.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList();
            final Map<String, Object> body = Map.of(
                    "model", config.ollama().model(),
                    "prompt", withContext(prompt, context),
                    "images", base64,
                    "stream", false
            );
            final String json = mapper.writeValueAsString(body);
            response = sendWithFallback(json);
        } catch (Exception ex) {
            Log.errorf(ex, "OllamaService: analyze failed: %s", ex.getMessage());
            return Optional.empty();
        }
        if (response == null) {
            return Optional.empty();
        }
        try {
            if (response.statusCode() >= 300) {
                Log.errorf("OllamaService: HTTP %d: %s", response.statusCode(), response.body());
                return Optional.empty();
            }

            final JsonNode root = mapper.readTree(response.body());
            final String text = root.path("response").asText("").trim();
            final Optional<Verdict> verdict = parseVerdict(text, positiveKeyword);
            if (verdict.isEmpty()) {
                // Unparseable answer. Returning empty makes every caller treat it as "no answer" - which is
                // fail-closed at the gates - instead of silently inventing a verdict.
                Log.warnf("OllamaService: [%s] could not be parsed, treating as no answer: %s",
                        positiveKeyword, text.length() > 200 ? text.substring(0, 200) + "…" : text);
                return Optional.empty();
            }
            final boolean positive = verdict.get().positive();
            String description = stripKeyword(text);
            // Severity is derived from what the model actually wrote, before any note of ours is appended.
            final Severity severity = deriveSeverity(positive, description);
            // Only set when WE overrode the model. A reply that genuinely reported an object doesn't get a note -
            // it already explains itself. This is the case where the reply reads as an approval and the history
            // would otherwise show a bare "not clear" beside it.
            if (verdict.get().downgradedBecause().isPresent()) {
                description = description + " [blocked: " + verdict.get().downgradedBecause().get() + "]";
            }

            Log.debugf("OllamaService: [%s] → positive=%b severity=%s — %s", positiveKeyword, positive, severity, description);
            return Optional.of(new AiResult(positive, severity, description));

        } catch (Exception ex) {
            Log.errorf(ex, "OllamaService: analyze failed: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Prepends printer status context (e.g. active HMS alerts, a printer error code) to a prompt as a hint,
     * when present, using the editable template in {@link AiPromptService#renderContext(String)}. Phrased so the
     * model treats it as a clue to correlate with the image rather than an instruction to override what it
     * actually sees - a stale or unrelated HMS code shouldn't force a false-positive result.
     */
    private String withContext(final String prompt, final Optional<String> context) {
        return context.filter(c -> !c.isBlank())
                .map(c -> prompts.renderContext(c) + "\n\n" + prompt)
                .orElse(prompt);
    }

    /**
     * Runs an arbitrary prompt against an image, exposing {@link #analyze} for the AI Settings "Test prompt"
     * button so an edited (not-yet-saved) prompt can be tried against a live camera frame before saving.
     */
    public Optional<AiResult> analyzePrompt(final byte[] imageJpeg, final String prompt, final String positiveKeyword, final Optional<String> context) {
        return analyze(imageJpeg, prompt, positiveKeyword, context);
    }

    /**
     * POSTs to the primary Ollama server, falling back to {@code bambu.ollama.fallback-url} when it cannot be
     * reached at all. Returns null when every endpoint fails, which callers treat as "no answer" - fail-closed.
     * <p>
     * <b>Only connection-level failures fail over</b> - unreachable host, timeout. An HTTP error status is
     * returned as-is and an unreadable reply is handled upstream, because those mean the model DID answer, and
     * asking a second model until you get a usable answer is not a safety check, it is shopping for one.
     */
    private HttpResponse<String> sendWithFallback(final String json) throws Exception {
        final List<String> endpoints = new java.util.ArrayList<>();
        config.ollama().url().ifPresent(endpoints::add);
        config.ollama().fallbackUrl().filter(u -> !u.isBlank()).ifPresent(endpoints::add);

        Exception last = null;
        for (int i = 0; i < endpoints.size(); i++) {
            final String base = endpoints.get(i);
            try {
                final HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                                .timeout(config.ollama().timeout())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (i > 0) {
                    Log.warnf("OllamaService: primary Ollama was unreachable - answered by the fallback at %s", base);
                }
                return response;
            } catch (java.io.IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                last = ex;
                Log.warnf("OllamaService: %s unreachable (%s)%s", base, ex.getMessage(),
                        i + 1 < endpoints.size() ? " - trying the fallback" : "");
            }
        }
        if (last != null) {
            Log.errorf("OllamaService: no Ollama endpoint answered - checks will fail closed");
        }
        return null;
    }

    /** Findings line: everything after "Problems:" / "Objects:" up to the next labelled line or the end. */
    private static final java.util.regex.Pattern FINDINGS =
            java.util.regex.Pattern.compile("(?is)\\b(?:problems|objects)\\s*:\\s*\\[?\\s*(.*?)(?=\\n|confidence\\s*:|reason\\s*:|clear\\s*:|$)");
    private static final java.util.regex.Pattern CONFIDENCE =
            java.util.regex.Pattern.compile("(?i)\\bconfidence\\s*:\\s*\\[?\\s*(\\d{1,3})");
    private static final java.util.regex.Pattern CLEAR_FIELD =
            java.util.regex.Pattern.compile("(?i)\\bclear\\s*:\\s*\\[?\\s*(yes|no)\\b");
    /** Values in a findings field that mean "nothing found". */
    private static final Set<String> NO_FINDINGS =
            Set.of("none", "no", "nothing", "n/a", "na", "-", "--", "none.", "none visible", "none detected");
    /**
     * Confidence floor for a POSITIVE verdict on the checks where positive means "safe to proceed" (bed is clear,
     * first layer is good). Below this we treat it as not-safe: these gates protect a print, so hedging fails closed.
     */
    private static final int SAFE_CONFIDENCE_MIN = 90;

    /**
     * Phrases that describe something being ON the plate. A "the bed is clear" verdict whose own text contains one
     * of these is contradicting itself.
     */
    private static final java.util.regex.Pattern OBJECT_PHRASE = java.util.regex.Pattern.compile(
            "(?i)\\b(objects?\\s+(?:on|upon)\\s+the\\s+(?:bed|plate)"
            + "|sitting\\s+on\\s+the\\s+(?:bed|plate)"
            + "|resting\\s+on\\s+the\\s+(?:bed|plate)"
            + "|(?:left|leftover|remaining|still)\\s+on\\s+the\\s+(?:bed|plate)"
            + "|not\\s+empty|not\\s+clear|is\\s+occupied)\\b");
    /**
     * Round shapes. The bed is a flat rectangle, so a circle on it is a printed part - the prompt says exactly that
     * and the model still talks itself out of it ("the circular shape is a gridded plate feature"). HA scanned its
     * {@code Objects:} line for {@code rings?|donut|trays?} for the same reason; we scan the whole reply because the
     * excuse arrives in the Reason field, with {@code Objects: none} sitting innocently above it.
     * <p>
     * Deliberately EXCLUDES tray/disc/disk, which HA could afford because it only ever looked at the
     * {@code Objects:} line. Over the whole reply they are unsafe: "tray" especially is a plausible way for a vision
     * model to refer to the build plate itself ("the print tray is clean"), which would block every clear bed. They
     * also add no detection: a real object named in {@code Objects:} already fails the findings check above, long
     * before this runs. All that was left was the false positives.
     */
    private static final java.util.regex.Pattern ROUND_SHAPE = java.util.regex.Pattern.compile(
            "(?i)\\b(rings?|donuts?|doughnuts?"
            + "|circular\\s+(?:shape|object|part|item)s?"
            + "|round\\s+(?:shape|object|part|item)s?"
            + "|cylinders?|cylindrical)\\b");
    /**
     * Negation cues. Deliberately excludes "clear" and "empty": a reply containing "Clear: YES" or "the plate is
     * empty" would otherwise read as a negation and excuse every contradiction after it - and "not empty" is itself
     * one of the phrases we are looking for.
     */
    private static final java.util.regex.Pattern NEGATION = java.util.regex.Pattern.compile(
            "(?i)\\b(no|not|none|nothing|never|without|absent|absence|free|lack|lacks|lacking"
            + "|cannot|can't|don't|doesn't|isn't|aren't|didn't|nor)\\b|n't\\b");
    /** Field labels, treated as segment boundaries so one field's wording can't negate another's. */
    private static final java.util.regex.Pattern FIELD_LABEL = java.util.regex.Pattern.compile(
            "(?i)\\b(?:objects?|problems?|reason|confidence|clear|observations?|findings)\\s*:");
    private static final java.util.regex.Pattern SEGMENT_SPLIT = java.util.regex.Pattern.compile("[\\n.;!?]");

    /**
     * Finds a phrase in the model's own text that contradicts a "bed is clear" verdict, if there is one.
     * <p>
     * <b>Why this exists:</b> a cupholder was dispatched onto two occupied beds after the model answered
     * <i>"Objects: none, Confidence: 100, Reason: The circular shape is a gridded plate feature, not a 3D printed
     * object."</i> Every field said clear; the sentence said otherwise. The prompt already tells the model that a
     * round shape on the plate IS a part, and it overrode that - so this distrusts the verdict rather than the
     * prompt, which is what HA did and why its equivalent gate held.
     * <p>
     * <b>Negation is looked for only BEHIND the match, within its own segment.</b> Behind, because the documented
     * failure explains the object away <i>afterwards</i> ("...is a gridded plate feature, <b>not</b> a printed
     * object") and that trailing "not" must not excuse it. Within its own segment, because "Objects: none" earlier
     * in the reply would otherwise negate a real finding in the Reason field below it.
     *
     * @return the offending phrase, for the log and the check history, or empty when the reply is consistent
     */
    static Optional<String> findContradiction(final String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (final String segment : SEGMENT_SPLIT.split(FIELD_LABEL.matcher(text).replaceAll("\n"))) {
            for (final java.util.regex.Pattern pattern : List.of(OBJECT_PHRASE, ROUND_SHAPE)) {
                final java.util.regex.Matcher m = pattern.matcher(segment);
                while (m.find()) {
                    if (!NEGATION.matcher(segment.substring(0, m.start())).find()) {
                        return Optional.of(m.group(1));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * True for the bed-clear check - the only one of the three that can START a print, and so the only one where a
     * doubtful positive must fail closed. A {@code Problems:} field marks the failure check; a {@code GOOD} positive
     * keyword marks the first-layer one; anything else is the gate.
     */
    private static boolean isBedGate(final String text, final String positiveKeyword) {
        return !text.toLowerCase().contains("problems:") && !"GOOD".equalsIgnoreCase(positiveKeyword.trim());
    }

    /**
     * Works out what the model actually decided.
     * <p>
     * <b>Why this is not just {@code startsWith(keyword)}:</b> the prompts demand a leading YES/NO/GOOD, but
     * gemma3:12b routinely ignores that and answers straight into the structured fields
     * ({@code Problems: Spaghetti  Confidence: 95  Reason: …}). With a first-word-only parse every such answer read
     * as "keyword absent" → negative → for failure detection that means "no failure", <b>always</b>. A real
     * spaghetti failure was detected by the model at 95% confidence and recorded as OK; the print ran to
     * completion detached. So the structured fields are now the primary signal and the leading word is a fallback.
     * <p>
     * Precedence: findings field → explicit Clear: → leading keyword → confidence number. Empty when the answer
     * can't be read at all, which callers treat as "no answer" (fail-closed at the gates).
     *
     * @param positiveKeyword the word meaning a positive outcome, which also tells us the direction: for
     *                        {@code failure} a positive outcome IS a problem, for the others it's the absence of one
     */
    static Optional<Verdict> parseVerdict(final String text, final String positiveKeyword) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        final Integer confidence = firstGroup(CONFIDENCE, text).map(Integer::parseInt).orElse(null);
        // "Problems:" is the failure check's findings field; a finding there means the POSITIVE outcome
        // (failure detected). "Objects:" is the bed check's; a finding there means the NEGATIVE outcome.
        final boolean findingsMeanPositive = text.toLowerCase().contains("problems:");
        // Trailing punctuation must come off before the NO_FINDINGS lookup, commas included. gemma3 writes
        // "Objects: none, Confidence: 100, Reason: …" on this fleet, and "none," is not "none" - so a clear bed
        // read as "an object was found" and the gate blocked it. Every comma-separated reply was affected.
        final Optional<String> findings = firstGroup(FINDINGS, text)
                .map(s -> s.replaceAll("[\\s.,;:*_\\]\\[]+$", "").trim());

        Boolean positive = null;
        if (findings.isPresent()) {
            final boolean found = !NO_FINDINGS.contains(findings.get().toLowerCase().trim()) && !findings.get().isBlank();
            positive = findingsMeanPositive == found;
        }
        if (positive == null) {
            final Optional<String> clear = firstGroup(CLEAR_FIELD, text);
            if (clear.isPresent()) {
                positive = "yes".equalsIgnoreCase(clear.get());
            }
        }
        if (positive == null) {
            final String firstWord = text.trim().split("\\s+", 2)[0].replaceAll("[^A-Za-z]", "").toUpperCase();
            if (RESPONSE_KEYWORDS.contains(firstWord)) {
                positive = firstWord.equalsIgnoreCase(positiveKeyword);
            }
        }
        // A "Problems:" field marks the failure check; "GOOD" as the positive keyword marks the first-layer one.
        // Anything else is the bed-clear gate, which is the only check that can START a print.
        final boolean bedGate = isBedGate(text, positiveKeyword);
        if (positive == null && confidence != null && !bedGate) {
            // Confidence is how sure the model is of its answer, NOT the answer. Reading it as one is a guess,
            // and it is only tolerable on the monitoring checks, where being wrong means a spurious alert.
            positive = confidence >= 50;
        }
        if (positive == null) {
            // No parseable verdict. Fail closed at the gate: the callers treat empty as "no answer". Letting a
            // bare "Confidence: 95" with no actual verdict count as "bed clear" is exactly the silent
            // permissiveness that let the old startsWith() parser green-light every check it could not read.
            return Optional.empty();
        }
        if (!positive) {
            // The model said no by itself - nothing for us to override, and no note to add.
            return Optional.of(new Verdict(false, Optional.empty()));
        }
        // The bed-clear GATE only: a hedged "clear" isn't good enough to start a print on, so a low-confidence
        // yes becomes no. Deliberately NOT applied to the monitoring checks:
        //  - failure detection, because suppressing an alarm over low confidence is the wrong way to be wrong;
        //  - the first-layer check, because it starts nothing. Downgrading an 85%-confident "looks fine" into an
        //    alert produces a warning on every print, which is how you train someone to ignore the one that matters.
        if (bedGate && confidence != null && confidence < SAFE_CONFIDENCE_MIN) {
            Log.infof("OllamaService: positive verdict downgraded - confidence %d is below the %d needed to proceed",
                    confidence, SAFE_CONFIDENCE_MIN);
            return Optional.of(new Verdict(false,
                    Optional.of("confidence %d is below the %d needed to start a print".formatted(confidence, SAFE_CONFIDENCE_MIN))));
        }
        // Same gate, second reason to distrust a yes: the reply describes an object while claiming the bed is clear.
        if (bedGate) {
            final Optional<String> contradiction = findContradiction(text);
            if (contradiction.isPresent()) {
                Log.infof("OllamaService: bed-clear verdict downgraded - the reply says the bed is clear but "
                        + "mentions \"%s\": %s", contradiction.get(), text);
                return Optional.of(new Verdict(false,
                        Optional.of("says clear but mentions \"%s\"".formatted(contradiction.get()))));
            }
        }
        return Optional.of(new Verdict(true, Optional.empty()));
    }

    private static Optional<String> firstGroup(final java.util.regex.Pattern pattern, final String text) {
        final java.util.regex.Matcher m = pattern.matcher(text);
        return m.find() ? Optional.ofNullable(m.group(1)) : Optional.empty();
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
     * EXPERIMENTAL bed-clear check that compares the current frame against a saved empty-bed reference for the same
     * printer. Sends the reference as image 1 and the current frame as image 2. positive=true means the bed IS clear.
     */
    public Optional<AiResult> checkBedClearWithReference(final byte[] referenceJpeg, final byte[] currentJpeg, final Optional<String> context) {
        return analyze(List.of(referenceJpeg, currentJpeg), prompts.getBedReferencePrompt(),
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
