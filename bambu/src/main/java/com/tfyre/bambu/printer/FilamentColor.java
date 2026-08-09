package com.tfyre.bambu.printer;

import java.util.Arrays;
import java.util.Optional;

/**
 * The colour vocabulary for filament matching: a small set of named colours, and a classifier that snaps the hex
 * the AMS reports onto one of them.
 * <p>
 * <b>Why named rather than exact hex.</b> The printer reports a tray's colour as {@code RRGGBBAA}, read off the
 * spool's RFID tag. Two spools both sold as black are routinely not the same number, and a re-spool can change
 * it. Matching on the literal hex would mean a mapping that quietly stops matching the day you open a new roll -
 * the worst kind of failure here, because nothing errors, the job simply never dispatches.
 * <p>
 * <b>Why hue-and-brightness rather than nearest-colour distance.</b> The obvious implementation - nearest palette
 * entry by RGB distance - is wrong in a way that matters, and it took a test to see it. Blue contributes only
 * 11% of perceived brightness, so Bambu's Basic Blue ({@code 0A2989}) genuinely sits closer to black than to any
 * blue reference under a luminance-weighted metric. It classified as BLACK: a blue tray would have satisfied a
 * "black" filter, which is the exact failure this class exists to prevent, in a new colour. People don't
 * categorise that way - they read hue first and only fall back to lightness when there isn't one - so that is
 * what this does:
 * <ul>
 * <li><b>Barely saturated</b> (a grey, a white, a black): decided on brightness alone.</li>
 * <li><b>Saturated</b>: decided on hue, with brown carved out as dark dull orange and pink as pale red.</li>
 * </ul>
 * <p>
 * <b>Why so few names.</b> This answers "black or grey", not "which of 200 shades". Every extra name is another
 * way for two visually identical spools to land on opposite sides of a boundary and stop being interchangeable.
 * Note the deliberate omission of "clear": translucent filament reports as near-white and cannot be told from
 * white, so offering the name would promise a distinction that can't be made.
 */
public enum FilamentColor {

    BLACK("Black", "#1A1A1A"),
    GREY("Grey", "#808080"),
    WHITE("White", "#F2F2F2"),
    RED("Red", "#C12E1F"),
    ORANGE("Orange", "#FF6A13"),
    YELLOW("Yellow", "#F4EE2A"),
    GREEN("Green", "#00AE42"),
    BLUE("Blue", "#0A2989"),
    PURPLE("Purple", "#5E43B7"),
    PINK("Pink", "#EC008C"),
    BROWN("Brown", "#7D6556");

    /** Below this saturation there is no usable hue, so brightness decides. */
    private static final double ACHROMATIC_SATURATION = 0.18;
    /** Below this brightness a colour reads as black whatever its hue claims to be. */
    private static final double BLACK_VALUE = 0.22;
    /** Above this brightness an unsaturated colour reads as white rather than grey. */
    private static final double WHITE_VALUE = 0.85;
    /** A saturated colour this dark is still black - the hue at that level is sensor noise. */
    private static final double DARK_HUE_FLOOR = 0.16;
    /** Orange this dark is brown. */
    private static final double BROWN_VALUE = 0.60;

    private final String label;
    private final String hex;

    FilamentColor(final String label, final String hex) {
        this.label = label;
        this.hex = hex;
    }

    public String label() {
        return label;
    }

    /** A representative CSS hex, so the mappings page can show a swatch of the colour it is naming. */
    public String hex() {
        return hex;
    }

    /**
     * The named colour a tray's reported hex classifies as, or empty when nothing usable was reported.
     *
     * @param trayColor {@code RRGGBB} or {@code RRGGBBAA} as the AMS reports it, with or without a leading
     *                  {@code #}. The trailing alpha pair is discarded - read as part of the blue channel it
     *                  turns every opaque colour into a blue one.
     */
    public static Optional<FilamentColor> nearest(final String trayColor) {
        if (trayColor == null || trayColor.isBlank()) {
            return Optional.empty();
        }
        final String clean = trayColor.strip().replace("#", "");
        if (clean.length() < 6) {
            return Optional.empty();
        }
        final int r;
        final int g;
        final int b;
        try {
            r = Integer.parseInt(clean.substring(0, 2), 16);
            g = Integer.parseInt(clean.substring(2, 4), 16);
            b = Integer.parseInt(clean.substring(4, 6), 16);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        return Optional.of(classify(r / 255d, g / 255d, b / 255d));
    }

    private static FilamentColor classify(final double r, final double g, final double b) {
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double delta = max - min;
        final double saturation = max == 0 ? 0 : delta / max;

        if (saturation < ACHROMATIC_SATURATION) {
            return max < BLACK_VALUE ? BLACK : max > WHITE_VALUE ? WHITE : GREY;
        }
        if (max < DARK_HUE_FLOOR) {
            return BLACK;
        }
        final double hue = hue(r, g, b, max, delta);
        if (hue >= 10 && hue < 55 && max < BROWN_VALUE) {
            return BROWN;
        }
        if (hue < 10 || hue >= 345) {
            // Pale, less saturated red is pink. Bambu's Pink (F55A74) sits at hue 350 and would otherwise be a
            // red; its Magenta (EC008C) reaches the pink band by hue alone.
            return max > WHITE_VALUE && saturation < 0.70 ? PINK : RED;
        }
        if (hue < 40) {
            return ORANGE;
        }
        if (hue < 70) {
            return YELLOW;
        }
        if (hue < 165) {
            return GREEN;
        }
        if (hue < 250) {
            // Cyan lands here too. A separate name for it would only create a boundary for blue spools to fall
            // across, and no listing has ever needed to distinguish the two.
            return BLUE;
        }
        if (hue < 290) {
            return PURPLE;
        }
        return PINK;
    }

    /** Degrees, 0-360. */
    private static double hue(final double r, final double g, final double b, final double max, final double delta) {
        if (delta == 0) {
            return 0;
        }
        final double h;
        if (max == r) {
            h = 60 * ((g - b) / delta);
        } else if (max == g) {
            h = 60 * ((b - r) / delta) + 120;
        } else {
            h = 60 * ((r - g) / delta) + 240;
        }
        return (h % 360 + 360) % 360;
    }

    /**
     * Whether a tray's reported hex counts as this colour.
     * <p>
     * A tray with no reported colour does <b>not</b> match. The alternative - treating unknown as "probably
     * fine" - means an untagged spool satisfies every filter, which is the situation the filter exists to
     * prevent. It fails closed: the job waits rather than printing in the wrong colour.
     */
    public boolean matches(final String trayColor) {
        return nearest(trayColor).filter(this::equals).isPresent();
    }

    /**
     * Case-insensitive lookup by {@link #label()} or enum name; empty for "no colour requirement".
     * <p>
     * A label this build doesn't recognise also reads as empty, i.e. "any colour". Deliberate: a typo, or a
     * mapping written by a later version, should not silently stop a farm from printing.
     */
    public static Optional<FilamentColor> byLabel(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String want = name.strip();
        return Arrays.stream(values())
                .filter(c -> c.label.equalsIgnoreCase(want) || c.name().equalsIgnoreCase(want))
                .findFirst();
    }
}
