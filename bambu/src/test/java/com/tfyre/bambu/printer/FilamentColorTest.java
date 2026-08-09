package com.tfyre.bambu.printer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapping a tray's reported hex to a named colour.
 * <p>
 * This exists because an order went out in grey ASA: the dispatcher matched on material alone and took the
 * lowest-numbered tray holding it. The filter that prevents a repeat is only as good as this classification, and
 * the failure it must not have is <b>black and grey landing on the same name</b> - which is precisely the pair a
 * naive RGB distance is worst at, since it has no notion of brightness.
 */
@DisplayName("filament colour matching")
class FilamentColorTest {

    private static FilamentColor of(final String hex) {
        return FilamentColor.nearest(hex).orElseThrow(() -> new AssertionError("no match for " + hex));
    }

    @Nested
    @DisplayName("the distinction that caused the incident")
    class BlackVersusGrey {

        @Test
        @DisplayName("real Bambu greys do not read as black")
        void greysAreGrey() {
            // Bambu Basic Grey, Ash Grey and Silver as the AMS reports them.
            assertEquals(FilamentColor.GREY, of("8E9089FF"));
            assertEquals(FilamentColor.GREY, of("9B9EA0FF"));
            assertEquals(FilamentColor.GREY, of("A6A9AAFF"));
        }

        @Test
        @DisplayName("real Bambu blacks do not read as grey")
        void blacksAreBlack() {
            assertEquals(FilamentColor.BLACK, of("000000FF"));
            assertEquals(FilamentColor.BLACK, of("161616FF"));
            assertEquals(FilamentColor.BLACK, of("2A2A2AFF"));
        }

        @Test
        @DisplayName("a black filter rejects a grey tray, which is the whole point")
        void theFilterActuallyFilters() {
            assertTrue(FilamentColor.BLACK.matches("000000FF"));
            assertFalse(FilamentColor.BLACK.matches("8E9089FF"));
            assertFalse(FilamentColor.GREY.matches("000000FF"));
        }
    }

    @Nested
    @DisplayName("hex parsing")
    class Parsing {

        @Test
        @DisplayName("alpha is ignored, not read as blue")
        void alphaIsStripped() {
            // Without dropping the trailing pair, "0000FFFF" parses as a 32-bit value and the blue channel
            // becomes FF - black would classify as blue.
            assertEquals(FilamentColor.BLACK, of("000000FF"));
            assertEquals(FilamentColor.BLACK, of("000000"));
            assertEquals(FilamentColor.BLUE, of("1976D2FF"));
        }

        @Test
        @DisplayName("a leading # and surrounding space are tolerated")
        void formatsTolerated() {
            assertEquals(FilamentColor.RED, of("#D32F2F"));
            assertEquals(FilamentColor.RED, of("  D32F2FFF  "));
        }

        @Test
        @DisplayName("nothing reported is no match, never a lucky guess")
        void unknownNeverMatches() {
            assertTrue(FilamentColor.nearest(null).isEmpty());
            assertTrue(FilamentColor.nearest("").isEmpty());
            assertTrue(FilamentColor.nearest("   ").isEmpty());
            assertTrue(FilamentColor.nearest("XYZ").isEmpty());
            assertTrue(FilamentColor.nearest("12345").isEmpty(), "too short to be a colour");
            // The important consequence: an untagged spool satisfies NO filter, so the job waits rather than
            // printing in whatever happens to be loaded.
            assertFalse(FilamentColor.BLACK.matches(null));
            assertFalse(FilamentColor.BLACK.matches("garbage"));
        }
    }

    @Nested
    @DisplayName("the rest of the vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("real Bambu spool colours land where a human would put them")
        void realSpools() {
            assertEquals(FilamentColor.WHITE, of("FFFFFFFF"));
            assertEquals(FilamentColor.RED, of("C12E1FFF"));
            assertEquals(FilamentColor.ORANGE, of("FF6A13FF"));
            assertEquals(FilamentColor.YELLOW, of("F4EE2AFF"));
            assertEquals(FilamentColor.YELLOW, of("E4BD68FF"), "Gold sits on the orange/yellow line");
            assertEquals(FilamentColor.GREEN, of("00AE42FF"));
            assertEquals(FilamentColor.BLUE, of("0086D9FF"), "cyan is deliberately blue - see classify()");
            assertEquals(FilamentColor.PURPLE, of("5E43B7FF"));
            assertEquals(FilamentColor.PINK, of("EC008CFF"));
            assertEquals(FilamentColor.PINK, of("F55A74FF"));
            assertEquals(FilamentColor.BROWN, of("7D6556FF"));
            assertEquals(FilamentColor.GREY, of("404040FF"));
        }

        @Test
        @DisplayName("a saturated colour never reads as black just because it is dark")
        void darkIsNotBlack() {
            // The bug this file caught before it ever compiled. Under a luminance-weighted nearest-colour
            // metric, Bambu's Basic Blue is closer to black than to any blue - blue is only 11% of perceived
            // brightness - so a blue tray satisfied a "black" filter. Hue decides first for exactly this reason.
            assertEquals(FilamentColor.BLUE, of("0A2989FF"), "Bambu Basic Blue");
            assertEquals(FilamentColor.BLUE, of("00008BFF"));
            assertEquals(FilamentColor.RED, of("8B0000FF"));
            assertFalse(FilamentColor.BLACK.matches("0A2989FF"), "navy must never satisfy a black filter");
        }
    }

    @Nested
    @DisplayName("labels round-trip")
    class Labels {

        @Test
        @DisplayName("what the mapping stores is what comes back")
        void byLabelRoundTrip() {
            for (final FilamentColor c : FilamentColor.values()) {
                assertEquals(c, FilamentColor.byLabel(c.label()).orElseThrow(), c.label());
                assertEquals(c, FilamentColor.byLabel(c.name()).orElseThrow(), c.name());
            }
            assertEquals(FilamentColor.BLACK, FilamentColor.byLabel("black").orElseThrow());
        }

        @Test
        @DisplayName("no requirement, and unreadable requirements, both mean any colour")
        void blankIsAny() {
            assertTrue(FilamentColor.byLabel(null).isEmpty());
            assertTrue(FilamentColor.byLabel("").isEmpty());
            // Deliberate: a mapping naming a colour this build doesn't know reads as "any" rather than "never
            // match". A typo should not silently stop a farm.
            assertTrue(FilamentColor.byLabel("Chartreuse").isEmpty());
        }

        @Test
        @DisplayName("every colour offers a swatch the UI can render")
        void hexIsCss() {
            for (final FilamentColor c : FilamentColor.values()) {
                assertTrue(c.hex().matches("#[0-9A-F]{6}"), c.label() + " -> " + c.hex());
            }
        }
    }
}
