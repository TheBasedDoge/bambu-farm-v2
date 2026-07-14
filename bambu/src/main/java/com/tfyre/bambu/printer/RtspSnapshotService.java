package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Grabs a single JPEG snapshot frame via an {@code ffmpeg} subprocess - a fallback for printer models (X1C,
 * X1E, H2D) whose firmware doesn't push raw JPEGs over the port-6000 mechanism that {@link BambuPrinterStream}
 * relies on for every other model (see the warning logged there). Used only as an AI-check snapshot source
 * ({@link PrintAiService}); the live in-app camera view is a separate WHEP/HLS/RTSPS pipeline documented in
 * {@code docker/bambu-liveview}.
 * <p>
 * <b>Source selection matters here.</b> Bambu's camera firmware only tolerates one RTSPS client at a time. For
 * printers using the live-view pipeline ({@code stream.live-view=true}), a persistent bridge (the "liveview"
 * container in {@code docker/bambu-liveview}) already holds that single connection permanently to feed the
 * live view. If this service also connected straight to the printer, it would either fail to connect or -
 * worse - knock the existing bridge connection offline, breaking the live view (confirmed behavior, not just
 * theoretical). So when {@code bambu.mediamtx-rtsp-url} is configured, this service instead pulls the frame
 * from that same already-open relay ({@code <mediamtxRtspUrl>/<printer-id>}), which mediamtx happily serves to
 * any number of readers without touching the upstream printer connection. Only printers without a configured
 * relay (or not using live-view) fall back to a direct RTSPS connection to the printer itself.
 */
@ApplicationScoped
public class RtspSnapshotService {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
    /** Reuse a freshly-grabbed frame for this long instead of spawning a new ffmpeg process. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private record CacheEntry(Instant grabbedAt, Optional<byte[]> bytes) {
        boolean isFresh() {
            return Duration.between(grabbedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    @Inject
    BambuConfig config;

    /**
     * Returns a recently-grabbed (or freshly-grabbed) single JPEG frame for the given printer. Empty when
     * ffmpeg is missing/fails or the grab times out.
     * <p>
     * Deliberately {@code synchronized} - ffmpeg spawns are relatively heavyweight (a few seconds each) and
     * AI checks aren't latency-sensitive, so serializing grabs across the whole farm is a fine trade for
     * not needing per-printer locking, on the assumption most farms are a handful of printers, not hundreds.
     *
     * @param printerId     the printer's config map key (e.g. {@code printer5}) - used both as the cache key
     *                      and, when routing through the mediamtx relay, as the relay's stream path
     * @param printerName   display name, for log messages only
     * @param printerConfig this printer's config (ip/access-code for a direct connection, stream settings for
     *                      relay routing)
     */
    public synchronized Optional<byte[]> grabFrame(final String printerId, final String printerName, final BambuConfig.Printer printerConfig) {
        final CacheEntry cached = cache.get(printerId);
        if (cached != null && cached.isFresh()) {
            return cached.bytes();
        }
        final Optional<byte[]> result = doGrab(printerId, printerName, printerConfig);
        cache.put(printerId, new CacheEntry(Instant.now(), result));
        return result;
    }

    /**
     * Picks the RTSP source to grab a frame from: the internal mediamtx relay (shared, doesn't touch the
     * printer's own RTSPS connection) when {@code stream.live-view=true} and {@code bambu.mediamtx-rtsp-url}
     * is configured, otherwise a direct connection to the printer's own RTSPS port.
     */
    private String sourceUrl(final String printerId, final BambuConfig.Printer printerConfig) {
        if (printerConfig.stream().liveView()) {
            final Optional<String> relay = config.mediamtxRtspUrl();
            if (relay.isPresent()) {
                return relay.get().replaceAll("/+$", "") + "/" + printerId;
            }
        }
        return "rtsps://%s:%s@%s:322/streaming/live/1"
                .formatted(printerConfig.username(), printerConfig.accessCode(), printerConfig.ip());
    }

    private Optional<byte[]> doGrab(final String printerId, final String printerName, final BambuConfig.Printer printerConfig) {
        final String url = sourceUrl(printerId, printerConfig);
        final boolean viaRelay = url.startsWith("rtsp://") && printerConfig.stream().liveView() && config.mediamtxRtspUrl().isPresent();
        final ProcessBuilder pb = new ProcessBuilder(
                config.ffmpegPath(),
                "-hide_banner", "-loglevel", "error",
                "-rtsp_transport", "tcp",
                "-i", url,
                "-frames:v", "1",
                "-q:v", "2",
                "-f", "image2",
                "-y", "pipe:1");

        Process process = null;
        final ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        try {
            process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // ffmpeg doesn't need stdin for this command - closing it just avoids any chance of a hang.
            }

            // Drain BOTH pipes on their own threads, so this thread can enforce the timeout with a plain
            // waitFor below. Reading either pipe directly here would block past the timeout if ffmpeg hangs
            // without closing its pipes (e.g. a TCP connection that's accepted but never sends data - since
            // dropping -rw_timeout, there's no ffmpeg-side I/O timeout, so this Java-side one is the only
            // guard). Also avoids the classic ProcessBuilder deadlock of reading two pipes sequentially.
            final Thread stdoutPump = pump(process.getInputStream(), stdoutBuf, "rtsp-snapshot-stdout-" + printerId);
            final Thread stderrPump = pump(process.getErrorStream(), stderrBuf, "rtsp-snapshot-stderr-" + printerId);

            final boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                // Kill first so the pipes close and the pumps can exit, then reap them.
                process.destroyForcibly();
            }
            stdoutPump.join(Duration.ofSeconds(2).toMillis());
            stderrPump.join(Duration.ofSeconds(2).toMillis());
            final byte[] bytes = stdoutBuf.toByteArray();
            final String stderrText = stderrBuf.toString(StandardCharsets.UTF_8).trim();

            if (!finished) {
                Log.warnf("RtspSnapshotService: %s: ffmpeg timed out after %ds grabbing a snapshot from %s%s", printerName,
                        PROCESS_TIMEOUT.toSeconds(), url, stderrText.isEmpty() ? "" : ": " + stderrText);
                return Optional.empty();
            }
            if (process.exitValue() != 0 || bytes.length == 0) {
                logOnce(printerId, viaRelay
                        ? "RtspSnapshotService: %s: ffmpeg exited %d, %d bytes captured from the mediamtx relay (%s): %s - check mediamtx is up and the printer's live-view bridge is actually publishing that stream path"
                                .formatted(printerName, process.exitValue(), bytes.length, url, stderrText)
                        : "RtspSnapshotService: %s: ffmpeg exited %d, %d bytes captured: %s - check the printer's RTSPS stream is reachable at %s:322 and the access code is correct"
                                .formatted(printerName, process.exitValue(), bytes.length, stderrText, printerConfig.ip()));
                return Optional.empty();
            }
            warned.remove(printerId);
            return Optional.of(bytes);
        } catch (IOException ex) {
            logOnce(printerId, "RtspSnapshotService: %s: could not launch ffmpeg (%s) - install ffmpeg and ensure it's on PATH, or set bambu.ffmpeg-path to its full path"
                    .formatted(printerName, ex.getMessage()));
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Starts a daemon thread that drains {@code in} into {@code buf} until the stream closes. */
    private static Thread pump(final InputStream in, final ByteArrayOutputStream buf, final String threadName) {
        final Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(buf);
            } catch (IOException ignored) {
                // process died / stream closed - nothing more to capture
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void logOnce(final String printerId, final String message) {
        if (warned.putIfAbsent(printerId, Boolean.TRUE) == null) {
            Log.warn(message);
        }
    }

}
