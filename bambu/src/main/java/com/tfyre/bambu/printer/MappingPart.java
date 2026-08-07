package com.tfyre.bambu.printer;

/**
 * One physical print job needed to fulfill a mapped listing (Etsy/eBay), independent of marketplace.
 * <p>
 * A listing can map to more than one of these - either because a single order needs several copies of the same
 * plate (e.g. a part that doesn't fit twice on one bed, so {@code copiesPerUnit=2}), or because it's a kit made of
 * several different gcode files/plates that must each be printed once (or more) per unit ordered.
 *
 * @param source        where the file lives - the batch print library, or already on every printer's SD card
 * @param path           for {@link GcodeSource#LIBRARY}, the filename in the library; for
 *                       {@link GcodeSource#SD_CARD}, the file's path on the printer's SD card (same on every
 *                       printer)
 * @param plateId        which plate/plate index to print
 * @param copiesPerUnit  how many times this part must be printed for every 1 unit ordered
 * @param amsSlot        physical AMS tray to force this print to use for every filament slot in the file (0-based:
 *                       A1=0, A2=1 … D4=15), {@link BambuConst#AMS_TRAY_VIRTUAL} for the external spool, or
 *                       {@code null} to leave the printer's current/default filament assignment untouched. Assumes
 *                       a single-material print - multi-color files aren't individually remapped per color.
 * @param filamentType   filament this part must print in (e.g. "PETG", "ASA"), matched against each printer's
 *                       live AMS tray telemetry, or {@code null} for "don't care". Used by auto-queue to pick a
 *                       printer that actually has the right material loaded: with {@code amsSlot} also set, that
 *                       exact tray must currently hold this type; with {@code amsSlot} unset, any tray with this
 *                       type qualifies and the job is pinned to it. Manual queueing ignores this field.
 * @param filamentColor  colour this part must print in, as a {@link FilamentColor} label ("Black", "Grey", …),
 *                       or {@code null} for "any colour". Checked against each tray's reported colour snapped to
 *                       the nearest named one - see {@link FilamentColor} for why it isn't an exact hex. Only
 *                       meaningful alongside {@code filamentType}: on its own it would let a black PLA tray
 *                       satisfy a part that needs ASA. A tray whose colour the printer hasn't reported never
 *                       matches, so the job waits rather than printing in whatever happens to be loaded - which
 *                       is the point. An order once dispatched onto grey ASA because slot 4 was simply the
 *                       lowest-numbered tray holding the right material.
 */
public record MappingPart(GcodeSource source, String path, int plateId, int copiesPerUnit, Integer amsSlot,
        String filamentType, String filamentColor) {

    public MappingPart {
        if (copiesPerUnit < 1) {
            copiesPerUnit = 1;
        }
        if (filamentType != null && filamentType.isBlank()) {
            filamentType = null;
        }
        if (filamentColor != null && filamentColor.isBlank()) {
            filamentColor = null;
        }
    }

    /** The requested colour, or empty for "any". Unparseable labels read as "any" rather than "never match". */
    public java.util.Optional<FilamentColor> color() {
        return FilamentColor.byLabel(filamentColor);
    }

    /** Convenience constructor for parts with no AMS override (printer's current default is used). */
    public MappingPart(final GcodeSource source, final String path, final int plateId, final int copiesPerUnit) {
        this(source, path, plateId, copiesPerUnit, null, null, null);
    }

    /** Backward-compatible constructor for parts without a filament-type requirement. */
    public MappingPart(final GcodeSource source, final String path, final int plateId, final int copiesPerUnit, final Integer amsSlot) {
        this(source, path, plateId, copiesPerUnit, amsSlot, null, null);
    }

    /**
     * Backward-compatible constructor for parts saved before colour filtering existed. Jackson uses the canonical
     * one; this keeps the several hand-written call sites compiling and reading as "no colour requirement",
     * which is what every mapping written before today meant.
     */
    public MappingPart(final GcodeSource source, final String path, final int plateId, final int copiesPerUnit,
            final Integer amsSlot, final String filamentType) {
        this(source, path, plateId, copiesPerUnit, amsSlot, filamentType, null);
    }

}
