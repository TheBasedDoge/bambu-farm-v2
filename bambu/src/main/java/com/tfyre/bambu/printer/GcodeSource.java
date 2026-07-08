package com.tfyre.bambu.printer;

/**
 * Where a mapped gcode file comes from when queuing a print.
 * <p>
 * {@link #LIBRARY} references a {@code .3mf} project file already saved in the batch print library
 * ({@code bambu.batchPrint.library}) - it gets uploaded to the printer's SD card (if not already present) before
 * printing, same as the manual Batch Print flow.
 * <p>
 * {@link #SD_CARD} references a file already resident at the same path on every printer's SD card (e.g. copied
 * there once via the printer's own file browser or an external tool) - no local file is required and no upload is
 * attempted, the print command is sent directly.
 */
public enum GcodeSource {
    LIBRARY,
    SD_CARD
}
