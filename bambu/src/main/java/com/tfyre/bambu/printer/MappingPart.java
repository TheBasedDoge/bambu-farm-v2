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
 * @param variants       the same part sliced for other printer families, keyed by
 *                       {@link BambuConst.PrinterModel#gcodeFamily()} ({@code "h2d"}, {@code "a1"}): the file to
 *                       use instead of {@code path} on that family. {@code path} itself is the P1/X1 file. A
 *                       family with no entry here cannot print this part and its printers are skipped - the
 *                       H2D sat out of dispatch for months because every library file was sliced for the P1s,
 *                       and sending one of those to it is not something to find out about from the camera.
 *                       Same source ({@link GcodeSource}) as {@code path}; the plate id applies to every variant.
 */
public record MappingPart(GcodeSource source, String path, int plateId, int copiesPerUnit, Integer amsSlot,
        String filamentType, String filamentColor, java.util.Map<String, String> variants) {

    public MappingPart {
        if (copiesPerUnit < 1) {
            copiesPerUnit = 1;
        }
        // Normalise: missing (every mapping saved before this existed), blank keys/values → empty map.
        final java.util.Map<String, String> clean = new java.util.LinkedHashMap<>();
        if (variants != null) {
            variants.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    clean.put(k.strip().toLowerCase(), v.strip());
                }
            });
        }
        variants = java.util.Collections.unmodifiableMap(clean);
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

    /**
     * The file this part prints from on a printer of the given model: {@code path} for the P1/X1 family, the
     * matching {@link #variants} entry for any other, or empty when that family has no file - in which case the
     * printer is not eligible for this part at all.
     */
    public java.util.Optional<String> pathFor(final BambuConst.PrinterModel model) {
        final String family = (model == null ? BambuConst.PrinterModel.UNKNOWN : model).gcodeFamily();
        if (BambuConst.PrinterModel.FAMILY_P1.equals(family)) {
            return java.util.Optional.of(path);
        }
        return java.util.Optional.ofNullable(variants.get(family));
    }

    /** The H2D's file, if one is mapped. */
    public java.util.Optional<String> h2dPath() {
        return java.util.Optional.ofNullable(variants.get(BambuConst.PrinterModel.FAMILY_H2D));
    }

    /** Same part with a different file for one family ({@code null} path removes that family's variant). */
    public MappingPart withVariant(final String family, final String variantPath) {
        final java.util.Map<String, String> m = new java.util.LinkedHashMap<>(variants);
        if (variantPath == null || variantPath.isBlank()) {
            m.remove(family);
        } else {
            m.put(family, variantPath);
        }
        return new MappingPart(source, path, plateId, copiesPerUnit, amsSlot, filamentType, filamentColor, m);
    }

    /** Canonical constructor minus variants - what every call site written before the H2D file existed uses. */
    public MappingPart(final GcodeSource source, final String path, final int plateId, final int copiesPerUnit,
            final Integer amsSlot, final String filamentType, final String filamentColor) {
        this(source, path, plateId, copiesPerUnit, amsSlot, filamentType, filamentColor, null);
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
