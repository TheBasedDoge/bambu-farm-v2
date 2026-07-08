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
 */
public record MappingPart(GcodeSource source, String path, int plateId, int copiesPerUnit, Integer amsSlot) {

    public MappingPart {
        if (copiesPerUnit < 1) {
            copiesPerUnit = 1;
        }
    }

    /** Convenience constructor for parts with no AMS override (printer's current default is used). */
    public MappingPart(final GcodeSource source, final String path, final int plateId, final int copiesPerUnit) {
        this(source, path, plateId, copiesPerUnit, null);
    }

}
