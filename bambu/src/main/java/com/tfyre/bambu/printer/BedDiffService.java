package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Deterministic pixel-diff backstop for the bed-clear check: measures how structurally different the current
 * camera frame is from a saved photo of the EMPTY bed, as a single number. Above a threshold the bed is treated
 * as NOT clear regardless of what the vision model said.
 * <p>
 * <b>Why this exists.</b> Local vision models cannot reliably see a dark part on the dark PEI plate - a dark
 * ring-shaped print was passed as "clear" six consecutive times at 90% confidence, and a print was dispatched
 * onto an occupied bed. The same failure reproduced across Gemma 3 12B, Gemma 4, Qwen2.5-VL and Qwen3-VL: the
 * information is lost in the vision encoder, so no amount of prompt engineering recovers it. This check does not
 * involve the model at all, which is exactly the point - <b>do not remove it because "the prompt is better now"</b>.
 * (Ported from the author's proven Home Assistant {@code bed_diff.py} v2.1 backstop.)
 * <p>
 * <b>The metric</b>, in order - each step removes a way that lighting alone could fake a difference:
 * <ol>
 * <li>Greyscale, downscale to 96x54 (box average), crop to the plate region ({@link BambuConfig.BedDiff}).</li>
 * <li><b>High-pass</b>: subtract a heavily blurred copy (6x3), removing lighting <i>gradients</i> - sun patches,
 * a bright window, day vs night.</li>
 * <li><b>Contrast normalise</b>: divide by RMS, removing lighting <i>gain</i> - a dim mid-exposure frame measured
 * as badly as a real part before this step was added.</li>
 * <li>Mean absolute difference x10.</li>
 * </ol>
 * What survives all that is pure structure: plate texture and part edges. Empty bed scores near zero; a part on
 * the plate scores far above it.
 * <p>
 * <b>Fails open.</b> No reference, an unreadable image, any exception - the measurement is simply skipped and the
 * AI verdict stands alone. A missing reference must never deadlock the farm; only the AI gate fails closed.
 */
@ApplicationScoped
public class BedDiffService {

    private static final String STORE_FILENAME = "bambu-bed-diff.json";
    /** Working resolution of the comparison - small on purpose: we want structure, not detail. */
    private static final int W = 96;
    private static final int H = 54;
    /** Blur resolution for the high-pass step (the "lighting" we subtract off). */
    private static final int BLUR_W = 6;
    private static final int BLUR_H = 3;
    /** Grid used for the worst-block reading - fine enough to isolate a part, coarse enough to stay stable. */
    private static final int BLOCK_COLS = 6;
    private static final int BLOCK_ROWS = 4;
    /**
     * How far (in working-resolution pixels) the two frames are allowed to be out of alignment before comparing.
     * <p>
     * <b>The bed does not sit at the same height every time.</b> Where it parks depends on the Z height of the
     * print that just finished, so the plate appears shifted - and slightly scaled - between the reference and a
     * later check. A strict pixel-to-pixel compare reads that shift as a large structural difference, i.e. a false
     * "not clear" on a perfectly empty bed. We therefore try a small range of offsets and keep the BEST alignment;
     * a genuine part still can't be aligned away, because no offset makes an object match bare plate.
     */
    private static final int ALIGN_SEARCH = 3;
    // The bed parks at (print height + 98mm), so it sits at a different distance from the camera after every
    // differently-sized print. Closer bed = larger in frame. That is a ZOOM, not a shift, which is why a
    // translation-only search reported "aligned by (0,0)" and still scored 6.01 on a genuinely empty plate.
    // Searched over +/-10% in 1% steps. Measured on synthetic scenes: 1% granularity beats 2% (a bed 8%
    // closer scored 3.85 worst-block vs 4.58 and stayed under the limit), and widening past +/-10% bought
    // nothing because the fit saturates. Widening the SHIFT search past +/-3 also bought nothing - the
    // chosen offsets never left +/-4. Beyond roughly +/-8% of apparent zoom this still blocks, which is the
    // right way to fail: ask, rather than wave through a plate we cannot actually vouch for.
    private static final int SCALE_STEPS = 10;
    private static final double SCALE_STEP = 0.01;
    /**
     * Default limits. NOTE: this scale is specific to this implementation - the thresholds from the Home
     * Assistant setup do NOT transfer, and neither do its v1/v2 numbers. Calibrate with the measured values shown
     * on the AI Settings page (see the README).
     * <p>
     * <b>Only the mean gates.</b> The worst block is measured and displayed, but it does not block, because on
     * real fleet data it does not discriminate - it ranked an occupied bed as CLEANER than two empty ones:
     * <pre>
     *   P1S    cupholder on the plate   mean 6.91   worst block 19.91
     *   P1S-2  empty                    mean 4.76   worst block 21.24
     *   P1P    empty                    mean 5.21   worst block 23.15
     *   P1S-3  empty, fresh reference   mean 0.19   worst block  0.30
     * </pre>
     * A maximum over tiles is dominated by whichever tile happens to contain a high-contrast edge, and the
     * slightest misregistration there swamps the signal from an actual part. The mean, which averages that
     * away, does separate the cases. This mirrors {@code bed_diff.py} - the Home Assistant implementation that
     * worked in production for months - which has no block channel at all.
     * <p>
     * <b>The limit is 6.0, NOT the 8.0 that {@code bed_diff.py} ships as its default.</b> Replaying the rows
     * above through a limit of 8 lets the cupholder through at 6.91 - a miss, in the direction that matters.
     * 6.0 sits between the worst empty bed (5.21) and the occupied one (6.91). That is a thin margin, and it is
     * thin only because these references are stale.
     * <p>
     * The last row is the important one: the same scene against a FRESH reference reads 0.19 instead of ~5.
     * Reference freshness is worth far more than any tuning here, which is what {@link #isAutoRefresh} is for -
     * with references kept current the empty-bed side collapses towards 0.2 and the margin becomes enormous.
     */
    private static final double DEFAULT_THRESHOLD = 6.0;
    /**
     * Default hold after a print stops, in minutes. 30 rather than a token few: the bed stays occupied until a
     * human clears it, and a short window mostly guarantees the bed check runs while the part is still there -
     * which is the one situation where a wrong "clear" costs a ruined print.
     */
    private static final int DEFAULT_POST_PRINT_COOLDOWN_MIN = 30;
    private static final double DEFAULT_BLOCK_THRESHOLD = 4.0;

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;

    /** Free-form settings map so new keys don't break old JSON (mirrors BedReferenceService's shape). */
    private final Map<String, Object> settings = new ConcurrentHashMap<>();
    /** printer → most recent measurement, for the calibration readout on the AI Settings page. */
    private final Map<String, Double> lastDiff = new ConcurrentHashMap<>();
    private final Map<String, Measurement> lastMeasurement = new ConcurrentHashMap<>();
    /** The frame each printer was last measured against its reference - shown next to the reference for diagnosis. */
    private final Map<String, byte[]> lastFrame = new ConcurrentHashMap<>();

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Object>>() {
            }));
            Log.infof("BedDiffService: loaded settings from %s (enabled=%s, threshold=%.1f)", path, isEnabled(), getThreshold());
        } catch (IOException ex) {
            Log.errorf(ex, "BedDiffService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "BedDiffService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /** Whether the pixel-diff backstop runs at all. Off by default - it needs a reference image and calibration. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(settings.get("enabled"));
    }

    public void setEnabled(final boolean enabled) {
        settings.put("enabled", enabled);
        save();
        Log.infof("BedDiffService: pixel-diff backstop %s", enabled ? "enabled" : "disabled");
    }

    /** Mean-over-the-region reading above this = bed NOT clear, whatever the model said. */
    public double getThreshold() {
        final Object v = settings.get("threshold");
        return v instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : DEFAULT_THRESHOLD;
    }

    public void setThreshold(final double threshold) {
        settings.put("threshold", threshold);
        save();
    }

    /**
     * How long a printer is held after a print stops before the dispatch pool may give it another job.
     * <p>
     * The bed is known-occupied for this window - the part that just finished is on it - so there is nothing for a
     * bed check to usefully decide. Read by {@link DispatchQueueService}; kept here because it belongs with the
     * other bed-safety settings on the AI Settings page rather than in a store of its own.
     */
    public java.time.Duration getPostPrintCooldown() {
        final Object v = settings.get("postPrintCooldownMinutes");
        final int minutes = v instanceof Number n && n.intValue() >= 0 ? n.intValue() : DEFAULT_POST_PRINT_COOLDOWN_MIN;
        return java.time.Duration.ofMinutes(minutes);
    }

    public void setPostPrintCooldownMinutes(final int minutes) {
        settings.put("postPrintCooldownMinutes", Math.max(0, minutes));
        save();
        Log.infof("BedDiffService: post-print cooldown set to %d min", Math.max(0, minutes));
    }

    /**
     * Whether a "bed is clear" verdict must survive a second, independently captured snapshot.
     * <p>
     * Defaults to {@code bambu.ollama.two-pass-bed-check} so the config property still sets the starting position,
     * and the runtime toggle overrides it once touched - the same arrangement the crop uses.
     */
    public boolean isTwoPass() {
        final Object v = settings.get("twoPassBedCheck");
        return v instanceof Boolean b ? b : config.ollama().twoPassBedCheck();
    }

    public void setTwoPass(final boolean twoPass) {
        settings.put("twoPassBedCheck", twoPass);
        save();
        Log.infof("BedDiffService: two-pass bed verification %s", twoPass ? "ENABLED" : "disabled");
    }

    /**
     * Reference level for the worst-block readout. This is a <b>display and shading scale only</b> - it does not
     * gate anything. See {@link #DEFAULT_BLOCK_THRESHOLD} for the fleet data showing why it cannot.
     */
    public double getBlockThreshold() {
        final Object v = settings.get("blockThreshold");
        return v instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : DEFAULT_BLOCK_THRESHOLD;
    }

    public void setBlockThreshold(final double threshold) {
        settings.put("blockThreshold", threshold);
        save();
    }

    /**
     * Why a reading blocks, for an explainable message - or empty when it doesn't. Only the mean is consulted;
     * the worst block is reported alongside it as context, never as a reason on its own.
     */
    public Optional<String> whyBlocked(final Measurement m) {
        if (m == null || m.mean() <= getThreshold()) {
            return Optional.empty();
        }
        return Optional.of("mean %.2f > limit %.1f (worst block %.2f, not gating)"
                .formatted(m.mean(), getThreshold(), m.worst()));
    }

    /**
     * The reading at or below which a reference is considered to still describe an empty bed. Same figure the
     * self-refresh uses to decide a frame is safe to adopt: if a reading is good enough to become the new baseline,
     * it is good enough to trust the old one against.
     */
    public double trustCeiling() {
        return getThreshold() * REFRESH_MAX_FRACTION;
    }

    /**
     * Why a reading can't be trusted to mean anything, even though it's under the block limit - or empty when it can.
     * <p>
     * <b>This closes the hole that let a print start on an occupied bed.</b> The backstop only discriminates while
     * its clear-bed readings sit near zero: measured on this fleet, a fresh reference reads <b>0.19</b> on an empty
     * bed and <b>5.94-8.28</b> with a part. Against a day-old reference the same empty bed reads <b>4.76-5.64</b>
     * and a large speaker adapter read <b>5.08</b> - the occupied bed scored LOWER than the empty one. No threshold
     * separates those, so a mid-range reading is not a weak pass, it is <i>no measurement at all</i>: the reference
     * has stopped describing an empty bed.
     * <p>
     * Treating that as a pass meant the model was silently the only thing guarding the bed - and a large dark part
     * on a dark plate is exactly the perception failure this backstop exists to cover. So mid-range now fails
     * closed. Either the reference is stale or there is an object the model missed; both mean "don't start a print".
     * <p>
     * Self-correcting by design: capture one reference on a genuinely empty bed and readings drop to ~0.2, far below
     * this ceiling, after which self-refresh keeps them there. It cannot lift itself out of staleness, because the
     * adoption rule is this same ceiling - that asymmetry is deliberate, and this makes its consequence visible
     * instead of silent.
     */
    public Optional<String> whyUntrustworthy(final Measurement m) {
        if (m == null || !isEnabled() || !isStrict() || m.mean() <= trustCeiling() || whyBlocked(m).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(("mean %.2f is under the %.1f limit but above the %.1f this reference can be trusted "
                + "below - either it is stale or there is something on the bed the model missed. If the bed is "
                + "genuinely empty, re-capture the reference (AI Settings → Bed reference); a good one reads ~0.2.")
                .formatted(m.mean(), getThreshold(), trustCeiling()));
    }

    /**
     * Whether a check that passes BOTH the AI and the pixel diff adopts its snapshot as the new reference, to keep
     * the baseline tracking glue drift and plate rotation.
     * <p>
     * <b>On by default</b>, because a stale reference is the single largest error source measured on this fleet:
     * the same empty bed reads 4.76-5.21 against an aged reference and 0.19 against a fresh one. Left off, every
     * reference decays towards the limit until the check is useless.
     * <p>
     * <b>It has poisoned references before, so the guards matter.</b> A cupholder once measured 4.53 against a
     * limit of 6.0 while empty beds measured 4.07-4.92, the model called it "a gridded plate feature", and the
     * occupied bed was adopted on two printers - after which they compared against a bed with a part on it and
     * read "clear" forever. Two things have changed since. First, that happened while
     * {@code OllamaService.parseVerdict} only accepted a verdict that LED with the keyword, which gemma3 never
     * does - so the AI half of "BOTH must agree" had never once fired, and adoption was effectively unguarded.
     * That is fixed. Second, adoption requires the reading to be at or below {@link #REFRESH_MAX_FRACTION} of
     * the limit, which on the current limit of 8 means 4.0 - below every empty-bed reading observed on an aged
     * reference, so a fresh adoption genuinely has to look clean rather than merely acceptable.
     */
    public boolean isAutoRefresh() {
        return !Boolean.FALSE.equals(settings.get("autoRefresh"));
    }

    /**
     * Whether a mid-range reading blocks the bed gate (see {@link #whyUntrustworthy}).
     * <p>
     * <b>Default OFF, deliberately, and it should stay off until bed-height drift is fixed.</b> The metric is
     * currently sensitive to where the plate parks: Bambu's end-gcode leaves the bed at {@code max_layer_z + 98mm},
     * so a print of a different height moves the plate in frame and a reference captured earlier stops matching.
     * Observed on P1P: 0.22 right after capture, then a rock-steady 6.57-6.58 for seven consecutive checks a day
     * and six prints later. With readings routinely landing at 4-6 on empty beds for that reason, turning this on
     * would block nearly every auto-start - trading a silently unsafe farm for a loudly unusable one.
     * <p>
     * Turn it on once an empty bed reliably measures near zero regardless of the previous print's height.
     */
    public boolean isStrict() {
        return Boolean.TRUE.equals(settings.get("strictReference"));
    }

    public void setStrict(final boolean strict) {
        settings.put("strictReference", strict);
        save();
        Log.infof("BedDiffService: strict reference gating %s", strict ? "ENABLED" : "disabled");
    }

    /**
     * A frame may only become the new reference if it measures at or below this fraction of the limit - i.e. it
     * has to be comfortably clear, not merely under the wire. Stops a marginal reading from ratcheting the
     * baseline towards an occupied bed one check at a time.
     * <p>
     * <b>Note the bootstrap:</b> at a limit of 6.0 this ceiling is 3.0, which is below the 4.76-5.21 that stale
     * references currently produce - so auto-refresh cannot lift a printer out of a stale reference on its own,
     * by design. Capture one good reference by hand (the "Save current frame" button on an empty bed) and the
     * mechanism keeps it current from then on. That asymmetry is deliberate: adopting a reference is the one
     * action here that can silently redefine "clear", so it should only ever happen from an already-good state.
     */
    private static final double REFRESH_MAX_FRACTION = 0.5;

    /**
     * Whether a frame is clean enough to be adopted as a new reference. Deliberately mean-only: gating adoption
     * on the worst block would mean it never fires, since an aged reference reads 20+ there on a plainly empty
     * bed - the reference could then only ever get staler, which is the failure this feature exists to prevent.
     */
    public boolean canRefreshFrom(final Measurement m) {
        return isAutoRefresh() && m != null && m.mean() <= getThreshold() * REFRESH_MAX_FRACTION;
    }

    public void setAutoRefresh(final boolean autoRefresh) {
        settings.put("autoRefresh", autoRefresh);
        save();
    }

    // Crop is runtime-editable: it depends entirely on how each camera sees the plate, and getting it wrong is
    // the difference between a metric that separates a part from an empty bed and one that doesn't. Config
    // supplies the starting values; anything saved here wins.
    public double getCrop(final String edge) {
        final Object v = settings.get("crop." + edge);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return switch (edge) {
            case "left" -> config.bedDiff().cropLeft();
            case "top" -> config.bedDiff().cropTop();
            case "right" -> config.bedDiff().cropRight();
            default -> config.bedDiff().cropBottom();
        };
    }

    public void setCrop(final String edge, final double value) {
        settings.put("crop." + edge, Math.max(0, Math.min(1, value)));
        save();
        lastMeasurement.clear();
        lastDiff.clear();
    }

    /**
     * The exact region the comparison sees, as a JPEG - so the crop can be tuned by eye instead of by guesswork.
     * If this preview doesn't show mostly build plate, the metric cannot work: averaging over chamber walls and
     * blown-out highlights dilutes the part you're trying to detect until an occupied bed scores no higher than
     * an empty one.
     */
    public Optional<byte[]> renderCrop(final byte[] jpeg) {
        try {
            final BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (src == null) {
                return Optional.empty();
            }
            final int x0 = clamp((int) Math.round(getCrop("left") * src.getWidth()), 0, src.getWidth() - 1);
            final int x1 = clamp((int) Math.round(getCrop("right") * src.getWidth()), x0 + 1, src.getWidth());
            final int y0 = clamp((int) Math.round(getCrop("top") * src.getHeight()), 0, src.getHeight() - 1);
            final int y1 = clamp((int) Math.round(getCrop("bottom") * src.getHeight()), y0 + 1, src.getHeight());
            final BufferedImage out = src.getSubimage(x0, y0, x1 - x0, y1 - y0);
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return Optional.of(bos.toByteArray());
        } catch (IOException | RuntimeException ex) {
            Log.warnf("BedDiffService: cannot render crop preview: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The comparison as a picture: the crop, with the blocks that differ most washed red and the worst one
     * outlined. Numbers alone can't tell you WHY a reading is high - whether it's an object on the plate or the
     * plate itself having moved - and looking at two near-identical photos can't either. This can.
     */
    public Optional<byte[]> renderDiff(final byte[] current, final byte[] reference) {
        try {
            final Prepared a = prepare(current);
            final Prepared b = prepare(reference);
            if (a.pixels().length == 0 || a.pixels().length != b.pixels().length) {
                return Optional.empty();
            }
            final int bw = Math.max(1, a.width() / BLOCK_COLS);
            final int bh = Math.max(1, a.height() / BLOCK_ROWS);
            // Per-block score at the same alignment the measurement uses
            final Warp warp = bestAlign(a, b).warp();
            final BufferedImage src = ImageIO.read(new ByteArrayInputStream(current));
            if (src == null) {
                return Optional.empty();
            }
            final int x0 = clamp((int) Math.round(getCrop("left") * src.getWidth()), 0, src.getWidth() - 1);
            final int x1 = clamp((int) Math.round(getCrop("right") * src.getWidth()), x0 + 1, src.getWidth());
            final int y0 = clamp((int) Math.round(getCrop("top") * src.getHeight()), 0, src.getHeight() - 1);
            final int y1 = clamp((int) Math.round(getCrop("bottom") * src.getHeight()), y0 + 1, src.getHeight());
            final BufferedImage out = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
            // createGraphics + dispose, not getGraphics: the handle holds native resources and this runs on every
            // card render on the AI page.
            final java.awt.Graphics2D g = out.createGraphics();
            try {
                g.drawImage(src.getSubimage(x0, y0, x1 - x0, y1 - y0), 0, 0, null);
            } finally {
                g.dispose();
            }

            double worst = 0;
            for (int by = 0; by + bh <= a.height(); by += bh) {
                for (int bx = 0; bx + bw <= a.width(); bx += bw) {
                    final double v = blockScore(a, b, bx, by, bw, bh, warp);
                    if (!Double.isNaN(v)) {
                        worst = Math.max(worst, v);
                    }
                }
            }
            if (worst <= 0) {
                return Optional.empty();
            }
            // Shade against the LIMIT, not against the worst block. Scaling by the worst block makes every block
            // look maximally guilty when the frames actually match - a perfect comparison came out solid red,
            // which is the opposite of what it should say.
            final double scale = Math.max(getBlockThreshold(), 0.5);
            for (int by = 0; by + bh <= a.height(); by += bh) {
                for (int bx = 0; bx + bw <= a.width(); bx += bw) {
                    final double raw = blockScore(a, b, bx, by, bw, bh, warp);
                    final double share = Double.isNaN(raw) ? 0 : Math.min(1.0, raw / scale);
                    if (share < 0.25) {
                        continue;
                    }
                    final int px0 = bx * out.getWidth() / a.width();
                    final int px1 = (bx + bw) * out.getWidth() / a.width();
                    final int py0 = by * out.getHeight() / a.height();
                    final int py1 = (by + bh) * out.getHeight() / a.height();
                    for (int y = py0; y < py1; y++) {
                        for (int x = px0; x < px1; x++) {
                            final int rgb = out.getRGB(x, y);
                            final int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) + 170 * share));
                            out.setRGB(x, y, (r << 16) | (((rgb >> 8) & 0xFF) / 2 << 8) | ((rgb & 0xFF) / 2));
                        }
                    }
                }
            }
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return Optional.of(bos.toByteArray());
        } catch (IOException | RuntimeException ex) {
            Log.warnf("BedDiffService: cannot render the difference view: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Most recent measurement for a printer, for calibration display. */
    public Optional<Double> getLastDiff(final String printerName) {
        return Optional.ofNullable(lastDiff.get(printerName));
    }

    // -------------------------------------------------------------------------
    // Measurement
    // -------------------------------------------------------------------------

    /**
     * Structural difference between two JPEG frames. Empty = could not measure (fail open - the caller must treat
     * this as "no opinion", never as "not clear").
     */
    public OptionalDouble diff(final byte[] current, final byte[] reference) {
        return measure(current, reference).map(Measurement::score).map(OptionalDouble::of).orElse(OptionalDouble.empty());
    }

    /**
     * Both readings from one comparison.
     *
     * @param mean  average difference over the whole crop - the original metric
     * @param worst the worst single block ({@link #BLOCK_COLS}x{@link #BLOCK_ROWS} grid). <b>This is what catches a
     *              real part.</b> A cupholder covering a fifth of the crop barely moves the mean but lights up the
     *              blocks it sits in; averaging over the whole frame dilutes exactly the thing we're looking for.
     *              That dilution is worse the looser the crop, so the block reading is also far less sensitive to
     *              the crop being imperfectly tuned for a given camera.
     * @param score the number the threshold is applied to: the larger of the two, so either can trip the block
     */
    public record Measurement(double mean, double worst, double score, String detail) {

        public Measurement(final double mean, final double worst, final double score) {
            this(mean, worst, score, "");
        }
    }

    public Optional<Measurement> measure(final byte[] current, final byte[] reference) {
        try {
            // Source dimensions matter and are invisible otherwise: both frames are squashed to a fixed 96x54
            // grid, so if the reference and the live frame come out of different pipelines at different aspect
            // ratios, the same crop fractions cover different parts of the scene and every reading is wrong.
            final String dims = "%s vs %s".formatted(sizeOf(current), sizeOf(reference));
            if (!sizeOf(current).equals(sizeOf(reference))) {
                Log.warnf("BedDiffService: reference and live frame differ in size (%s) - the compared region "
                        + "covers different areas of the scene, so readings are not meaningful", dims);
            }
            final Prepared a = prepare(current);
            final Prepared b = prepare(reference);
            if (a.pixels().length == 0 || a.pixels().length != b.pixels().length) {
                Log.warnf("BedDiffService: frame size mismatch (%d vs %d) - skipping pixel check",
                        a.pixels().length, b.pixels().length);
                return Optional.empty();
            }
            // Find the offset that best aligns the two frames, so a bed parked at a different height doesn't
            // read as a difference. Chosen on the mean, then both readings are taken at that offset.
            final Align align = bestAlign(a, b);
            final double mean = align.mean();
            final double worst = worstBlockAt(a, b, align.warp());
            if (!align.warp().identity()) {
                Log.debugf("BedDiffService: best fit %s - the bed is parked at a different height than the reference",
                        align.warp().describe());
            }
            return Optional.of(new Measurement(round(mean), round(worst), round(Math.max(mean, worst)),
                    "frames %s, %s".formatted(dims, align.warp().describe())));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("BedDiffService: could not compare frames (%s) - skipping pixel check", ex.getMessage());
            return Optional.empty();
        }
    }

    /** "1920x1080", or "?" when unreadable - used to spot a reference and a live frame from different pipelines. */
    private static String sizeOf(final byte[] jpeg) {
        try {
            final BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            return img == null ? "?" : img.getWidth() + "x" + img.getHeight();
        } catch (IOException | RuntimeException ex) {
            return "?";
        }
    }

    private static double round(final double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Bilinear sample of the reference at a fractional position. Fractional because the zoom search does not
     * land on whole pixels; NaN outside the frame so callers can exclude non-overlapping area from the mean.
     */
    private static double sampleB(final Prepared b, final double fx, final double fy) {
        if (fx < 0 || fy < 0 || fx > b.width() - 1 || fy > b.height() - 1) {
            return Double.NaN;
        }
        final int x0 = (int) Math.floor(fx);
        final int y0 = (int) Math.floor(fy);
        final int x1 = Math.min(x0 + 1, b.width() - 1);
        final int y1 = Math.min(y0 + 1, b.height() - 1);
        final double tx = fx - x0;
        final double ty = fy - y0;
        final double[] px = b.pixels();
        final double top = px[y0 * b.width() + x0] * (1 - tx) + px[y0 * b.width() + x1] * tx;
        final double bot = px[y1 * b.width() + x0] * (1 - tx) + px[y1 * b.width() + x1] * tx;
        return top * (1 - ty) + bot * ty;
    }

    /** Centre of the FULL frame expressed in crop coordinates - the crop is off-centre, the zoom is not. */
    private static double centreX(final Prepared a) {
        return W / 2.0 - a.ox();
    }

    private static double centreY(final Prepared a) {
        return H / 2.0 - a.oy();
    }

    /** Mean absolute difference over the region where the two frames overlap under {@code w}. */
    private static double meanAt(final Prepared a, final Prepared b, final Warp w) {
        final double cx = centreX(a);
        final double cy = centreY(a);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < a.height(); y++) {
            final double fy = cy + (y - cy) / w.scale() + w.dy();
            for (int x = 0; x < a.width(); x++) {
                final double v = sampleB(b, cx + (x - cx) / w.scale() + w.dx(), fy);
                if (Double.isNaN(v)) {
                    continue;
                }
                sum += Math.abs(a.pixels()[y * a.width() + x] - v);
                n++;
            }
        }
        // Demand real overlap. Without this a wild zoom "wins" by comparing a handful of pixels and the
        // search happily reports a perfect match against almost nothing.
        return n < a.pixels().length / 2 ? Double.MAX_VALUE : sum / n * 10;
    }

    /**
     * Searches zoom and shift together for the mapping that best explains the two frames, then everything else
     * is measured at that mapping. A part sitting on the plate cannot be zoomed away, so widening the search
     * this way corrects for bed height without also hiding what we are looking for.
     */
    private static Align bestAlign(final Prepared a, final Prepared b) {
        Warp best = new Warp(1.0, 0, 0);
        double bestMean = meanAt(a, b, best);
        for (int si = -SCALE_STEPS; si <= SCALE_STEPS; si++) {
            final double scale = 1.0 + si * SCALE_STEP;
            for (int dy = -ALIGN_SEARCH; dy <= ALIGN_SEARCH; dy++) {
                for (int dx = -ALIGN_SEARCH; dx <= ALIGN_SEARCH; dx++) {
                    final Warp w = new Warp(scale, dx, dy);
                    final double m = meanAt(a, b, w);
                    if (m < bestMean) {
                        bestMean = m;
                        best = w;
                    }
                }
            }
        }
        return new Align(best, bestMean);
    }

    /** One tile's score under {@code w}. NaN when too little of the tile overlaps to judge it. */
    private static double blockScore(final Prepared a, final Prepared b, final int bx, final int by,
            final int bw, final int bh, final Warp w) {
        final double cx = centreX(a);
        final double cy = centreY(a);
        double sum = 0;
        int n = 0;
        for (int y = by; y < by + bh; y++) {
            final double fy = cy + (y - cy) / w.scale() + w.dy();
            for (int x = bx; x < bx + bw; x++) {
                final double v = sampleB(b, cx + (x - cx) / w.scale() + w.dx(), fy);
                if (Double.isNaN(v)) {
                    continue;
                }
                sum += Math.abs(a.pixels()[y * a.width() + x] - v);
                n++;
            }
        }
        return n < bw * bh / 2 ? Double.NaN : sum / n * 10;
    }

    /** Worst single tile, measured at the alignment chosen above - a localised part can't be averaged away. */
    private static double worstBlockAt(final Prepared a, final Prepared b, final Warp w) {
        final int bw = Math.max(1, a.width() / BLOCK_COLS);
        final int bh = Math.max(1, a.height() / BLOCK_ROWS);
        double worst = 0;
        for (int by = 0; by + bh <= a.height(); by += bh) {
            for (int bx = 0; bx + bw <= a.width(); bx += bw) {
                final double v = blockScore(a, b, bx, by, bw, bh, w);
                if (!Double.isNaN(v)) {
                    worst = Math.max(worst, v);
                }
            }
        }
        return worst;
    }

    /** Measures and remembers the result for {@code printerName}'s calibration readout. */
    public Optional<Measurement> measureFor(final String printerName, final byte[] current, final byte[] reference) {
        final Optional<Measurement> m = measure(current, reference);
        m.ifPresent(v -> {
            lastDiff.put(printerName, v.score());
            lastMeasurement.put(printerName, v);
            lastFrame.put(printerName, current);
            Log.infof("BedDiffService: %s: mean %.2f (limit %.1f), worst block %.2f (limit %.1f)",
                    printerName, v.mean(), getThreshold(), v.worst(), getBlockThreshold());
        });
        return m;
    }

    /** Last full reading for a printer, for the calibration readout. */
    public Optional<Measurement> getLastMeasurement(final String printerName) {
        return Optional.ofNullable(lastMeasurement.get(printerName));
    }

    /** The exact frame behind {@link #getLastMeasurement} - so a surprising number can be looked at, not guessed at. */
    public Optional<byte[]> getLastFrame(final String printerName) {
        return Optional.ofNullable(lastFrame.get(printerName));
    }

    /**
     * How much a reading can be relied on, for the UI. Deliberately derived from the same limits the gate uses, so
     * the page can never describe a reading differently from how the gate treats it.
     */
    public enum Trust {
        /** At or below the trust ceiling: an empty bed against a good reference. The AI verdict decides. */
        PROTECTING,
        /** Between the ceiling and the limit: the reference has stopped describing an empty bed. */
        CANNOT_TELL,
        /** Over the limit: something is on the bed, or the reference is badly wrong. */
        OVER_LIMIT
    }

    public Trust trustOf(final Measurement m) {
        if (m.mean() > getThreshold()) {
            return Trust.OVER_LIMIT;
        }
        return m.mean() > trustCeiling() ? Trust.CANNOT_TELL : Trust.PROTECTING;
    }

    /** One line saying what a reading actually means, which a bare number next to a limit does not convey. */
    public String meaningOf(final Measurement m) {
        return switch (trustOf(m)) {
            case OVER_LIMIT ->
                "Blocks. Either a part is on the bed or the reference has drifted badly.";
            case CANNOT_TELL ->
                "Under the limit but above %.1f - an empty bed on a good reference reads about 0.2, so this "
                        .formatted(trustCeiling()) + "reference has aged out. Re-capture it on an empty bed.";
            case PROTECTING ->
                "Reference is good - the pixel check can tell an empty bed from an occupied one.";
        };
    }

    /** True when either reading exceeds its own limit. */
    public boolean blocks(final Measurement m) {
        return m != null && whyBlocked(m).isPresent();
    }

    /** Convenience for a printer's most recent reading. */
    public boolean blocksLast(final String printerName) {
        return getLastMeasurement(printerName).map(this::blocks).orElse(false);
    }

    /**
     * Greyscale → downscale → crop → high-pass → unit-RMS contrast. Returns the normalised pixels of the crop.
     */
    /** Normalised crop plus its dimensions, so the caller can walk it as a grid. */
    private record Prepared(double[] pixels, int width, int height, int ox, int oy) {
    }

    /** How the reference maps onto the live frame: a zoom about the frame centre, plus a whole-pixel shift. */
    private record Warp(double scale, int dx, int dy) {

        boolean identity() {
            return scale == 1.0 && dx == 0 && dy == 0;
        }

        String describe() {
            return "zoom %.0f%%, offset (%d,%d)".formatted((scale - 1) * 100, dx, dy);
        }
    }

    /** The chosen warp and the mean it achieved, so the search runs once and both readings share it. */
    private record Align(Warp warp, double mean) {
    }

    private Prepared prepare(final byte[] jpeg) throws IOException {
        final BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (src == null) {
            throw new IOException("not a readable image");
        }
        final double[] small = greyBox(src, W, H);

        final int x0 = clamp((int) Math.round(getCrop("left") * W), 0, W - 1);
        final int x1 = clamp((int) Math.round(getCrop("right") * W), x0 + 1, W);
        final int y0 = clamp((int) Math.round(getCrop("top") * H), 0, H - 1);
        final int y1 = clamp((int) Math.round(getCrop("bottom") * H), y0 + 1, H);
        final int cw = x1 - x0;
        final int ch = y1 - y0;

        final double[] crop = new double[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                crop[y * cw + x] = small[(y + y0) * W + (x + x0)];
            }
        }

        // High-pass: subtract a heavily blurred copy of the crop (removes lighting gradients)
        final double[] blur = upscale(box(crop, cw, ch, BLUR_W, BLUR_H), BLUR_W, BLUR_H, cw, ch);
        for (int i = 0; i < crop.length; i++) {
            crop[i] -= blur[i];
        }

        // Contrast normalise to unit RMS (removes lighting gain / exposure state)
        double sq = 0;
        for (final double v : crop) {
            sq += v * v;
        }
        final double rms = Math.max(Math.sqrt(sq / crop.length), 1e-6);
        for (int i = 0; i < crop.length; i++) {
            crop[i] /= rms;
        }
        return new Prepared(crop, cw, ch, x0, y0);
    }

    /** Luminance downscale by box-averaging every source pixel that falls in each destination cell. */
    private static double[] greyBox(final BufferedImage src, final int dw, final int dh) {
        final int sw = src.getWidth();
        final int sh = src.getHeight();
        final double[] sum = new double[dw * dh];
        final int[] count = new int[dw * dh];
        for (int y = 0; y < sh; y++) {
            final int dy = Math.min(y * dh / sh, dh - 1);
            for (int x = 0; x < sw; x++) {
                final int dx = Math.min(x * dw / sw, dw - 1);
                final int rgb = src.getRGB(x, y);
                // ITU-R 601-1 luma, matching PIL's "L" conversion
                final double lum = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                final int i = dy * dw + dx;
                sum[i] += lum;
                count[i]++;
            }
        }
        final double[] out = new double[dw * dh];
        for (int i = 0; i < out.length; i++) {
            out[i] = count[i] == 0 ? 0 : sum[i] / count[i];
        }
        return out;
    }

    /** Box-average an existing grid down to dw x dh. */
    private static double[] box(final double[] src, final int sw, final int sh, final int dw, final int dh) {
        final double[] sum = new double[dw * dh];
        final int[] count = new int[dw * dh];
        for (int y = 0; y < sh; y++) {
            final int dy = Math.min(y * dh / sh, dh - 1);
            for (int x = 0; x < sw; x++) {
                final int i = dy * dw + Math.min(x * dw / sw, dw - 1);
                sum[i] += src[y * sw + x];
                count[i]++;
            }
        }
        final double[] out = new double[dw * dh];
        for (int i = 0; i < out.length; i++) {
            out[i] = count[i] == 0 ? 0 : sum[i] / count[i];
        }
        return out;
    }

    /** Nearest-neighbour upscale back to the crop size. */
    private static double[] upscale(final double[] src, final int sw, final int sh, final int dw, final int dh) {
        final double[] out = new double[dw * dh];
        for (int y = 0; y < dh; y++) {
            final int sy = Math.min(y * sh / dh, sh - 1);
            for (int x = 0; x < dw; x++) {
                out[y * dw + x] = src[sy * sw + Math.min(x * sw / dw, sw - 1)];
            }
        }
        return out;
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
