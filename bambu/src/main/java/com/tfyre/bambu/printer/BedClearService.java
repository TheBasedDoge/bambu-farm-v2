package com.tfyre.bambu.printer;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "I have cleared that bed" - a person's word, taken for one print.
 * <p>
 * Two things follow from pressing the button (Overview tile, or the link on an alert):
 * <ul>
 * <li><b>The outline goes.</b> A printer stays FINISH or FAILED until its next job starts, long after the part
 * is off the plate; the printer has no way to know the bed was cleared, so the wall display kept a green or red
 * outline on a machine that needed nothing. The mark lasts until the printer next prints.</li>
 * <li><b>The next automatic start skips the camera check, once.</b> The AI gate exists to answer "is the bed
 * clear" when nobody is looking. Someone who just took the part off has answered it better than the model can,
 * and should not then wait out a post-print hold and a 20-40 second check. Valid for {@link #VALID}; after
 * that, or once it has been used, the gate applies again exactly as before.</li>
 * </ul>
 * In memory only. A restart forgets it, which fails in the safe direction: the gate is back.
 */
@ApplicationScoped
public class BedClearService {

    /** How long a hand-clear lets the next automatic start through without a camera check. */
    public static final Duration VALID = Duration.ofMinutes(30);

    @Inject
    BambuPrinters printers;

    /** Marked clear and not printed since. Drives the display only. */
    private final Map<String, Instant> cleared = new ConcurrentHashMap<>();
    /** One-shot pass for the bed gate. Removed when used. */
    private final Map<String, Instant> pass = new ConcurrentHashMap<>();

    /**
     * Records that a person cleared this printer's bed.
     *
     * @return false when there is no such printer or it is printing (nothing to clear)
     */
    public boolean markCleared(final String printerName, final String by) {
        final boolean ok = printers.getPrinter(printerName)
                .map(p -> !p.getGCodeState().isPrinting() && p.getGCodeState() != BambuConst.GCodeState.PAUSE)
                .orElse(false);
        if (!ok) {
            return false;
        }
        final Instant now = Instant.now();
        cleared.put(printerName, now);
        pass.put(printerName, now);
        Log.infof("BedClearService: %s: bed marked clear by hand (%s) - the next automatic start within %d min "
                + "skips the camera check", printerName, by, VALID.toMinutes());
        return true;
    }

    /** True from the mark until the printer next prints. Forgets the mark as soon as it sees it printing. */
    public boolean isCleared(final String printerName) {
        if (!cleared.containsKey(printerName)) {
            return false;
        }
        final boolean printing = printers.getPrinter(printerName)
                .map(p -> p.getGCodeState().isPrinting()).orElse(false);
        if (printing) {
            cleared.remove(printerName);
            pass.remove(printerName);
            return false;
        }
        return true;
    }

    /** Whether an unused pass exists and is still within {@link #VALID}. Does not use it. */
    public boolean hasPass(final String printerName) {
        final Instant at = pass.get(printerName);
        if (at == null) {
            return false;
        }
        if (Duration.between(at, Instant.now()).compareTo(VALID) > 0) {
            pass.remove(printerName);
            return false;
        }
        return true;
    }

    /** Uses the pass, if there is a valid one. At most one caller gets true. */
    public boolean usePass(final String printerName) {
        return hasPass(printerName) && pass.remove(printerName) != null;
    }
}
