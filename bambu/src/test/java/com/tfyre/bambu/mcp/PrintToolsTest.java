package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.LibraryService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** start_print's file search and tray choice - the parts that decide what gets printed where. */
@DisplayName("start_print helpers")
class PrintToolsTest {

    private static LibraryService.Part part(final String title, final String p1, final String h2d) {
        return new LibraryService.Part(title, "", p1, h2d, 1, false, List.of(), 0, 0, null, null);
    }

    private static final List<LibraryService.Part> LIB = List.of(
            part("Mk8 Cupholder Insert", "Mk8_Cupholder_Insert.gcode.3mf", null),
            part("Golf R MK8 Front Plate", "Golf R MK8 Front Plate.gcode.3mf", null),
            part("S2000 Button Replacement", "S2000_Button_Replacement.gcode.3mf", null),
            part("S2000 Console Button Replacement", "S2000_Console_Button_Replacement.3mf", null),
            part("S2000 Rollhoop Rear", null, "S2000_Rollhoop_Rear_H2D.gcode.3mf"));

    @Test
    void everyWordMustMatch() {
        assertEquals(List.of("Mk8 Cupholder Insert"), titles(PrintTools.findParts(LIB, "mk8 cupholder")));
        assertEquals(List.of("Mk8 Cupholder Insert"), titles(PrintTools.findParts(LIB, "Mk8_Cupholder")));
        assertEquals(2, PrintTools.findParts(LIB, "mk8").size());
        assertEquals(List.of("S2000 Rollhoop Rear"), titles(PrintTools.findParts(LIB, "rollhoop")));
        assertTrue(PrintTools.findParts(LIB, "audi").isEmpty());
        assertTrue(PrintTools.findParts(LIB, " ").isEmpty());
    }

    @Test
    void exactTitleWinsOverLongerMatches() {
        // "S2000 Button Replacement" is also contained in "S2000 Console Button Replacement" word-wise
        assertEquals(List.of("S2000 Button Replacement"), titles(PrintTools.findParts(LIB, "s2000 button replacement")));
    }

    @Test
    void trayFollowsTheMaterial() {
        final Map<Integer, String> trays = Map.of(0, "PETG", 1, "ASA", 2, "PETG", 254, "TPU");
        assertArrayEquals(new int[] {0, 0}, PrintTools.pickTray(trays, "PETG", 1).orElseThrow()); // active is ASA
        assertArrayEquals(new int[] {2, 2}, PrintTools.pickTray(trays, "PETG", 2).orElseThrow()); // active is PETG
        assertArrayEquals(new int[] {1, 1}, PrintTools.pickTray(trays, "asa", -1).orElseThrow());
        assertEquals(Optional.empty(), PrintTools.pickTray(trays, "PLA", 0));
        // AMS HT: telemetry key 512 is tray number 128
        assertArrayEquals(new int[] {128, 512}, PrintTools.pickTray(Map.of(512, "HIPS"), "HIPS", -1).orElseThrow());
    }

    @Test
    void trayNames() {
        assertEquals(Optional.of(0), PrintTools.parseTray("a1"));
        assertEquals(Optional.of(15), PrintTools.parseTray("D4"));
        assertEquals(Optional.of(128), PrintTools.parseTray("HT1"));
        assertEquals(Optional.of(254), PrintTools.parseTray("ext"));
        assertEquals(Optional.empty(), PrintTools.parseTray("E1"));
        assertEquals(512, PrintTools.telemetryKey(128));
        assertEquals(3, PrintTools.telemetryKey(3));
    }

    @Test
    void firstMaterialOfAMix() {
        assertEquals("PETG", PrintTools.firstMaterial("PETG + PLA"));
        assertEquals("ASA", PrintTools.firstMaterial("asa"));
        assertEquals("", PrintTools.firstMaterial(null));
    }

    private static List<String> titles(final List<LibraryService.Part> parts) {
        return parts.stream().map(LibraryService.Part::title).toList();
    }
}
