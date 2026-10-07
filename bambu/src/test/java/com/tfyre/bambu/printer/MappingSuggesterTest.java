package com.tfyre.bambu.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Real listing titles against real library file names from the shop. */
class MappingSuggesterTest {

    private static final List<String> LIBRARY = List.of(
            "Audi_B9_Cupholder.gcode.3mf",
            "Audi_Door_Speaker_Adapter_6_Inch.gcode.3mf",
            "Audi_Door_Speaker_Adapter_8_Inch.gcode.3mf",
            "Audi_Rear_Cupholder.3mf",
            "Infiniti_6MT_Divider.gcode.3mf",
            "Mk8_Cupholder_Insert.gcode.3mf",
            "S2000_Climate_Knob_Knurled.gcode.3mf",
            "S2000_Door_Speaker_Adapter.gcode.3mf",
            "S2000_Folding_Cupholder_Bracket.gcode.3mf",
            "S2000 Roll Hoop/S2000_RollHoop_FrontL.gcode.3mf");

    @Test
    void picksTheSpecificFileOverItsNeighbours() {
        assertEquals(Optional.of("S2000_Door_Speaker_Adapter.gcode.3mf"), MappingSuggester.suggest(
                "6.5\" Door Speaker Adapter for Honda (S2000, Prelude, CRX, Etc) (Set of 2)", LIBRARY));
        assertEquals(Optional.of("Audi_Door_Speaker_Adapter_8_Inch.gcode.3mf"), MappingSuggester.suggest(
                "8\" Door Speaker Adapter for Audi (Set of 2)", LIBRARY));
        assertEquals(Optional.of("Audi_Rear_Cupholder.3mf"), MappingSuggester.suggest(
                "Audi Upgraded Rear Cupholder Insert | Q5 Q7 Q8 Q3 Q2 A3", LIBRARY));
    }

    @Test
    void pluralsAndWordOrderDoNotMatter() {
        assertEquals(Optional.of("S2000_Climate_Knob_Knurled.gcode.3mf"), MappingSuggester.suggest(
                "S2000 Climate Control Knobs - Knurled", LIBRARY));
        assertEquals(Optional.of("S2000_Folding_Cupholder_Bracket.gcode.3mf"), MappingSuggester.suggest(
                "Folding Cupholder Bracket - for Honda S2000", LIBRARY));
        assertEquals(Optional.of("Infiniti_6MT_Divider.gcode.3mf"), MappingSuggester.suggest(
                "Infiniti G35 G37 6MT Center Console Divider", LIBRARY));
    }

    @Test
    void nothingIsBetterThanAWildGuess() {
        assertTrue(MappingSuggester.suggest("Handmade ceramic mug", LIBRARY).isEmpty());
        assertTrue(MappingSuggester.suggest("", LIBRARY).isEmpty());
        assertTrue(MappingSuggester.suggest("Door Speaker Adapter", List.of()).isEmpty());
    }

    @Test
    void setsOfTwoTakeTwoPrints() {
        assertEquals(2, MappingSuggester.copiesFor("8\" Door Speaker Adapter for Audi (Set of 2)"));
        assertEquals(2, MappingSuggester.copiesFor("Speaker pods, pair"));
        assertEquals(1, MappingSuggester.copiesFor("VW Golf Mk8 Cupholder Insert"));
    }
}
