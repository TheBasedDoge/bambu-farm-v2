package com.tfyre.bambu.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which file a mapping part hands to which printer. The wrong answer here sends a P1-sliced file to the H2D,
 * which is why the H2D was kept out of dispatch entirely until variants existed.
 */
class MappingPartVariantTest {

    private static final MappingPart P1_ONLY = new MappingPart(GcodeSource.LIBRARY, "part.gcode.3mf", 1, 1);
    private static final MappingPart WITH_H2D = P1_ONLY.withVariant("h2d", "part_H2D.gcode.3mf");

    @Test
    void p1FamilyAlwaysGetsTheMainFile() {
        for (final BambuConst.PrinterModel m : new BambuConst.PrinterModel[]{BambuConst.PrinterModel.P1S,
            BambuConst.PrinterModel.P1P, BambuConst.PrinterModel.X1C, BambuConst.PrinterModel.UNKNOWN}) {
            assertEquals(Optional.of("part.gcode.3mf"), P1_ONLY.pathFor(m), m.name());
            assertEquals(Optional.of("part.gcode.3mf"), WITH_H2D.pathFor(m), m.name());
        }
        assertEquals(Optional.of("part.gcode.3mf"), P1_ONLY.pathFor(null));
    }

    @Test
    void h2dNeedsItsOwnFile() {
        assertEquals(Optional.empty(), P1_ONLY.pathFor(BambuConst.PrinterModel.H2D));
        assertEquals(Optional.of("part_H2D.gcode.3mf"), WITH_H2D.pathFor(BambuConst.PrinterModel.H2D));
        assertEquals(Optional.empty(), WITH_H2D.pathFor(BambuConst.PrinterModel.A1));
    }

    @Test
    void variantsNormaliseAndClear() {
        final MappingPart messy = new MappingPart(GcodeSource.LIBRARY, "p.3mf", 1, 1, null, null, null,
                Map.of(" H2D ", " x.3mf ", "a1", "  "));
        assertEquals(Optional.of("x.3mf"), messy.h2dPath());
        assertEquals(1, messy.variants().size());
        assertTrue(WITH_H2D.withVariant("h2d", null).variants().isEmpty());
        assertTrue(new MappingPart(GcodeSource.LIBRARY, "p.3mf", 1, 1, null, null, null, null).variants().isEmpty());
    }
}
