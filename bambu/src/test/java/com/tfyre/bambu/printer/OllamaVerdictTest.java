package com.tfyre.bambu.printer;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a model's reply is turned into a verdict.
 * <p>
 * This method has caused three production incidents, which is why it now has tests:
 * <ol>
 * <li>a leading-keyword-only parse recorded a 95%-confidence spaghetti detection as OK, and the print ran to
 * completion detached;</li>
 * <li>a bare {@code Confidence: 95} with no verdict counted as BED CLEAR;</li>
 * <li>{@code Objects: none,} - with a comma - read as AN OBJECT WAS FOUND, so on a fleet whose model answers
 * with commas, every clear bed was blocked.</li>
 * </ol>
 * Each of those is a case below. The strings are real replies from the farm's logs where marked, because
 * invented test data would not have caught any of the three.
 * <p>
 * Deliberately plain JUnit against the package-private statics - no CDI, no Quarkus boot, so the whole suite
 * runs in about a second and there is no excuse to skip it.
 */
@DisplayName("Ollama verdict parsing")
class OllamaVerdictTest {

    /** The bed-clear gate's positive keyword; also selects the strict "this authorises a print" behaviour. */
    private static final String BED = "YES";
    /** The first-layer check's keyword - a monitoring check, exempt from the strict rules. */
    private static final String FIRST_LAYER = "GOOD";

    private static boolean clear(final String reply) {
        return OllamaService.parseVerdict(reply, BED).map(OllamaService.Verdict::positive).orElse(false);
    }

    private static Optional<OllamaService.Verdict> verdict(final String reply, final String keyword) {
        return OllamaService.parseVerdict(reply, keyword);
    }

    /** For the FAILURE check, where a positive verdict means "a failure was found". */
    private static boolean clearlyFailing(final String reply) {
        return OllamaService.parseVerdict(reply, BED).map(OllamaService.Verdict::positive).orElse(false);
    }

    @Nested
    @DisplayName("the three production bugs")
    class Regressions {

        @Test
        @DisplayName("'Objects: none,' with a comma is still 'nothing found'")
        void commaAfterNone() {
            // The exact shape gemma3 produces on this farm. Before the fix, "none," != "none" so the gate read
            // an object and blocked every clear bed.
            assertTrue(clear("Objects: none, Confidence: 100, Reason: The build plate is empty and clean."));
            assertTrue(clear("Objects: none; Confidence: 100; Reason: nothing on the plate."));
        }

        @Test
        @DisplayName("a spaghetti finding is a POSITIVE failure verdict, not an OK")
        void spaghettiIsDetected() {
            // Real logged reply. The old startsWith() parse made this "no failure", always.
            final String reply = "Problems: Spaghetti  Confidence: 95  Reason: There are loose strands of "
                    + "filament tangled in the air, not attached to the object (spaghetti)";
            assertTrue(verdict(reply, BED).map(OllamaService.Verdict::positive).orElse(false),
                    "a listed problem must read as 'a failure was found'");
        }

        @Test
        @DisplayName("a bare confidence with no verdict is no answer at the bed gate")
        void bareConfidenceIsNotAnAnswer() {
            assertTrue(verdict("Confidence: 95", BED).isEmpty(),
                    "confidence is how sure the model is of its answer, not the answer");
            assertFalse(clear("Confidence: 95"));
        }
    }

    @Nested
    @DisplayName("a failure the model described but did not report")
    class UnreportedFailure {

        @Test
        @DisplayName("REAL: the H2D spaghetti recorded as OK")
        void theH2dNest() {
            // Verbatim from the check dialog, 2026-08-06 23:33:03. A nest of filament had grown out of the top
            // of the part; the model described it and then filed it under Problems: none, because the prompt
            // defined spaghetti as strands "not attached to the object" and offered "still building up layer by
            // layer" as an unconditional out. Both are fixed in the prompt - this is the second line of defence.
            final String reply = "Problems: none Confidence: 0 Reason: The image shows what appears to be a "
                    + "tangled mess of filament, but it doesn't exhibit spaghetti or detached conditions, and "
                    + "seems like it might still be building up layer by layer.";
            assertTrue(verdict(reply, BED).map(OllamaService.Verdict::positive).orElse(false),
                    "a described tangle must read as a failure however the model filed it");
            assertEquals("tangled mess", OllamaService.findUnreportedFailure(reply).orElseThrow());
        }

        @Test
        @DisplayName("other ways a failure gets described but not reported")
        void describedNotReported() {
            assertTrue(clearlyFailing("Problems: none  Reason: A bird's nest of filament sits above the part."));
            assertTrue(clearlyFailing("Problems: none  Reason: The object came off the plate."));
            assertTrue(clearlyFailing("Problems: none  Reason: There is a large blob on the left side."));
        }

        @Test
        @DisplayName("normal prints are still normal - this must not cry wolf")
        void noFalseAlarms() {
            // Every one of these is a phrase the prompt itself calls normal, or a negated mention. A failure
            // check that fires on healthy prints is one that gets switched off, and then it protects nothing.
            assertFalse(clearlyFailing("Problems: none  Reason: The print is building up cleanly, layers even."));
            assertFalse(clearlyFailing("Problems: none  Reason: No spaghetti or tangles are visible."));
            assertFalse(clearlyFailing("Problems: none  Reason: There is no blob or clump on the part."));
            assertFalse(clearlyFailing("Problems: none  Reason: Thin strings between parts, otherwise clean."));
            assertFalse(clearlyFailing("Problems: none  Reason: Support structures and a brim are visible."));
            assertFalse(clearlyFailing("Problems: none  Reason: It doesn't exhibit spaghetti."));
        }

        @Test
        @DisplayName("the override is for the failure check only")
        void notAppliedToTheBedGate() {
            // "Objects:" is the bed check's findings field. A bed described as having a blob on it is already
            // caught by findContradiction; running the failure vocabulary here as well would double-handle it.
            assertTrue(clear("Objects: none  Confidence: 100  Reason: The plate is clean, no blobs anywhere."));
        }
    }

    @Nested
    @DisplayName("the contradiction override")
    class Contradiction {

        @Test
        @DisplayName("REAL: a cupholder explained away as a plate feature is still blocked")
        void circularShapeExcuse() {
            // The reply that dispatched a print onto two occupied beds.
            assertFalse(clear("Objects: none, Confidence: 100, Reason: The circular shape is a gridded plate "
                    + "feature, not a 3D printed object."));
        }

        @Test
        @DisplayName("negated mentions still pass")
        void negationIsHonoured() {
            assertTrue(clear("Objects: none  Confidence: 95  Reason: There are no rings or leftover parts."));
            assertTrue(clear("Clear: YES  Confidence: 96  Reason: I see no circular shapes or objects on the bed."));
            assertTrue(clear("Objects: none  Confidence: 100  Reason: Nothing is sitting on the plate."));
        }

        @Test
        @DisplayName("a finding in one field is not negated by 'none' in another")
        void negationDoesNotCrossFields() {
            // "Objects: none" sits above the Reason; without per-field segmentation it would excuse the ring.
            assertFalse(clear("Clear: YES  Confidence: 95  Objects: none  Reason: A ring is sitting on the bed."));
        }

        @Test
        @DisplayName("the plate called a 'tray' does not block")
        void plateSynonymsAreNotObjects() {
            // tray/disc/disk were removed from the scan: a vision model may call the build plate a tray, and
            // a real object named in Objects: already fails the findings check long before this runs.
            assertTrue(clear("Objects: none  Confidence: 100  Reason: The print tray is clean and empty."));
        }

        @Test
        @DisplayName("monitoring checks are exempt")
        void firstLayerIsNotSubjectToTheOverride() {
            assertTrue(verdict("GOOD  Confidence: 92  Observations: the circular shape is printing cleanly.",
                    FIRST_LAYER).map(OllamaService.Verdict::positive).orElse(false),
                    "the first-layer check starts nothing, so it must not inherit the gate's strictness");
        }
    }

    @Nested
    @DisplayName("the confidence floor")
    class ConfidenceFloor {

        @Test
        @DisplayName("a hedged 'clear' does not start a print")
        void lowConfidenceIsDowngraded() {
            assertFalse(clear("Clear: YES  Confidence: 60  Reason: it looks clear enough."));
        }

        @Test
        @DisplayName("an 85%-confident good first layer is NOT downgraded")
        void monitoringChecksKeepTheirAnswer() {
            // Applying the floor here warned on nearly every print, which is how you learn to ignore warnings.
            assertTrue(verdict("GOOD  Confidence: 85  Observations: lines are flat and even.", FIRST_LAYER)
                    .map(OllamaService.Verdict::positive).orElse(false));
        }
    }

    @Nested
    @DisplayName("explaining itself")
    class Downgrades {

        @Test
        @DisplayName("only OUR override carries a reason")
        void noteOnlyWhenWeOverrode() {
            // A reply that genuinely reported an object explains itself; adding "[blocked: …]" would be wrong.
            assertTrue(verdict("Objects: a cupholder  Confidence: 98  Reason: a part is on the plate.", BED)
                    .orElseThrow().downgradedBecause().isEmpty());
            assertTrue(verdict("Objects: none, Confidence: 100, Reason: The circular shape is a plate feature.", BED)
                    .orElseThrow().downgradedBecause().isPresent());
        }
    }

    @Test
    @DisplayName("garbage is no answer, which fails closed")
    void unparseable() {
        assertTrue(verdict("   ", BED).isEmpty());
        assertTrue(verdict("", BED).isEmpty());
        assertTrue(verdict(null, BED).isEmpty());
    }

    @Test
    @DisplayName("findContradiction reports WHICH phrase tripped it")
    void contradictionNamesThePhrase() {
        assertEquals("circular shape",
                OllamaService.findContradiction("Reason: The circular shape is a plate feature.").orElseThrow());
        assertTrue(OllamaService.findContradiction("Reason: the plate is empty and clean.").isEmpty());
    }
}
