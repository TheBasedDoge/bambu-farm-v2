package com.tfyre.bambu.view;

import com.tfyre.ftp.BambuFtp;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.net.ftp.FTP;

/**
 * Extracts embedded slicer preview thumbnails from .gcode and .3mf files on the printer SD card.
 *
 * GCode files embed thumbnails in the header as base64-encoded PNG/JPEG comment blocks:
 *   ; thumbnail begin 640x480 12345
 *   ; <base64 lines>
 *   ; thumbnail end
 *
 * 3MF files are ZIP archives. This service downloads the full file and parses the ZIP Central
 * Directory manually (rather than using ZipInputStream) because Java 21's strict ZipInputStream
 * rejects Bambu's non-standard ZIP format (STORED entries with the EXT descriptor flag set).
 *
 * IMPORTANT: All methods reuse the caller's existing FTP connection. Bambu printers only allow
 * one FTP session at a time, so a second connect while the SD card browser is active would hang.
 *
 * Results are cached in memory (keyed by printer+path+filename).
 */
@ApplicationScoped
public class SdCardThumbnailService {

    /** Max bytes to read from a .gcode file header when hunting for the thumbnail. */
    private static final int GCODE_READ_LIMIT = 256 * 1024; // 256 KB

    /** Candidate 3MF thumbnail entry paths inside the ZIP, in preference order. */
    private static final String[] THUMB_3MF_PATHS = {
        "Metadata/plate_1.png",
        "Metadata/thumbnail.png",
        "Metadata/thumbnail_small.png"
    };

    // ZIP structure magic numbers (little-endian)
    private static final int SIG_EOCD   = 0x06054b50; // End of Central Directory
    private static final int SIG_EOCD64 = 0x06064b50; // ZIP64 End of Central Directory
    private static final int SIG_CD     = 0x02014b50; // Central Directory entry
    private static final int SIG_LOCAL  = 0x04034b50; // Local File Header

    /**
     * Cache key: "{printerName}:{directory}:{filename}" → thumbnail bytes.
     * Only successful results are cached — failures are not stored so the next click retries.
     */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * Returns the thumbnail bytes for the given file using an already-connected FTP client.
     * Reuses the existing session to avoid Bambu's single-connection limit.
     *
     * Runs synchronously — call from a background thread.
     */
    public Optional<byte[]> getThumbnail(final BambuFtp ftp, final String printerName,
            final String directory, final String filename) {
        final String lower = filename.toLowerCase();
        if (!lower.endsWith(".gcode") && !lower.endsWith(".3mf")) {
            return Optional.empty();
        }
        final String key = "%s:%s:%s".formatted(printerName, directory, filename);
        final byte[] cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        final Optional<byte[]> result = fetchWithClient(ftp, directory, filename);
        result.ifPresent(bytes -> cache.put(key, bytes));
        return result;
    }

    /** Remove a specific cached entry (e.g. after the file is deleted or re-uploaded). */
    public void evict(final String printerName, final String directory, final String filename) {
        cache.remove("%s:%s:%s".formatted(printerName, directory, filename));
    }

    /** Remove all cached entries for a printer (e.g. after disconnect). */
    public void evictPrinter(final String printerName) {
        cache.keySet().removeIf(k -> k.startsWith(printerName + ":"));
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private Optional<byte[]> fetchWithClient(final BambuFtp ftp, final String directory, final String filename) {
        Log.infof("SdCardThumbnailService: fetching thumbnail for %s in [%s]", filename, directory);
        try {
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            final String dir = (directory == null || directory.isBlank()) ? "/" : directory;
            if (!ftp.changeWorkingDirectory(dir)) {
                Log.warnf("SdCardThumbnailService: cannot cd to [%s] (reply: %s)", dir, ftp.getReplyString().trim());
                // continue anyway — relative path from CWD may still work
            }
            return filename.toLowerCase().endsWith(".gcode")
                    ? extractGcodeThumbnail(ftp, filename)
                    : extract3mfThumbnail(ftp, filename);
        } catch (Exception ex) {
            Log.errorf(ex, "SdCardThumbnailService: %s — %s", filename, ex.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // GCode thumbnail extraction
    // -------------------------------------------------------------------------

    private Optional<byte[]> extractGcodeThumbnail(final BambuFtp ftp, final String filename) throws IOException {
        final InputStream stream = ftp.retrieveFileStream(filename);
        if (stream == null) {
            Log.warnf("SdCardThumbnailService: null stream for gcode %s (reply: %s)",
                    filename, ftp.getReplyString().trim());
            return Optional.empty();
        }
        final byte[] headerBytes;
        try {
            headerBytes = stream.readNBytes(GCODE_READ_LIMIT);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
            try { ftp.completePendingCommand(); } catch (IOException ignored) {}
        }
        return parseGcodeThumbnail(new String(headerBytes));
    }

    /**
     * Parses gcode thumbnail blocks from the header text.
     * Supports: "thumbnail begin", "thumbnail_JPG begin", "thumbnail_QOI begin".
     * Prefers PNG over JPEG. Returns the largest found.
     */
    static Optional<byte[]> parseGcodeThumbnail(final String header) {
        byte[] bestPng = null;
        byte[] bestJpg = null;
        int bestPngSize = 0;
        int bestJpgSize = 0;

        String format = null;
        int declaredSize = 0;
        final StringBuilder base64Buf = new StringBuilder();

        for (final String rawLine : header.split("\n")) {
            final String line = rawLine.trim();

            if (line.startsWith("; thumbnail") && line.contains("begin")) {
                final String[] parts = line.split("\\s+");
                if (parts.length >= 5) {
                    format = parts[1].toLowerCase().contains("jpg") ? "jpg" : "png";
                    try { declaredSize = Integer.parseInt(parts[4]); }
                    catch (NumberFormatException ex) { declaredSize = 1; }
                    base64Buf.setLength(0);
                }
                continue;
            }

            if (format != null && line.startsWith("; thumbnail") && line.contains("end")) {
                final String b64 = base64Buf.toString().replaceAll("\\s+", "");
                if (!b64.isEmpty()) {
                    try {
                        final byte[] decoded = Base64.getDecoder().decode(b64);
                        if ("png".equals(format) && declaredSize >= bestPngSize) {
                            bestPng = decoded; bestPngSize = declaredSize;
                        } else if ("jpg".equals(format) && declaredSize >= bestJpgSize) {
                            bestJpg = decoded; bestJpgSize = declaredSize;
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
                format = null;
                base64Buf.setLength(0);
                continue;
            }

            if (format != null && line.startsWith(";")) {
                base64Buf.append(line.substring(1).trim());
            }
        }

        return bestPng != null ? Optional.of(bestPng)
             : bestJpg != null ? Optional.of(bestJpg)
             : Optional.empty();
    }

    // -------------------------------------------------------------------------
    // 3MF thumbnail extraction — manual ZIP Central Directory parsing
    //
    // Java 21's strict ZipInputStream rejects Bambu's 3MF ZIP format because some
    // STORED entries carry the EXT descriptor flag. We parse the Central Directory
    // and inflate entries directly to avoid this.
    // -------------------------------------------------------------------------

    private Optional<byte[]> extract3mfThumbnail(final BambuFtp ftp, final String filename) throws IOException {
        // Download the full file
        final InputStream stream = ftp.retrieveFileStream(filename);
        if (stream == null) {
            Log.warnf("SdCardThumbnailService: null stream for 3mf %s (reply: %s)",
                    filename, ftp.getReplyString().trim());
            return Optional.empty();
        }
        final byte[] zipBytes;
        try {
            zipBytes = stream.readAllBytes();
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
            try { ftp.completePendingCommand(); } catch (IOException ignored) {}
        }
        Log.infof("SdCardThumbnailService: downloaded %s (%d bytes)", filename, zipBytes.length);
        return extractFromZipBytes(zipBytes, filename);
    }

    /**
     * Extracts a thumbnail from raw ZIP bytes using manual Central Directory parsing.
     * Tolerates non-standard entries that ZipInputStream rejects.
     */
    private Optional<byte[]> extractFromZipBytes(final byte[] zip, final String debugName) {
        // Locate End-of-Central-Directory
        final int eocdPos = findLastSignature(zip, SIG_EOCD);
        if (eocdPos < 0) {
            Log.warnf("SdCardThumbnailService: EOCD not found in %s", debugName);
            return Optional.empty();
        }

        long cdOffset = readU32LE(zip, eocdPos + 16);
        long cdSize   = readU32LE(zip, eocdPos + 12);

        // Handle ZIP64 EOCD
        if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
            final int eocd64 = findLastSignature(zip, SIG_EOCD64);
            if (eocd64 >= 0 && eocd64 + 56 <= zip.length) {
                // ZIP64 EOCD: offset 40 = total CD size, offset 48 = CD offset
                cdSize   = readU64LE(zip, eocd64 + 40);
                cdOffset = readU64LE(zip, eocd64 + 48);
            }
        }

        // Try each known thumbnail path
        for (final String thumbPath : THUMB_3MF_PATHS) {
            final Optional<byte[]> result = extractCdEntry(zip, (int) cdOffset, (int) cdSize, thumbPath, debugName);
            if (result.isPresent()) {
                return result;
            }
        }

        // Fallback: first PNG under Metadata/
        return extractFirstMetadataPng(zip, (int) cdOffset, (int) cdSize);
    }

    /**
     * Scans the Central Directory for a named entry and extracts its data.
     */
    private Optional<byte[]> extractCdEntry(final byte[] zip, final int cdStart, final int cdLen,
            final String entryPath, final String debugName) {
        final byte[] nameBytes = entryPath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int pos = cdStart;
        final int cdEnd = Math.min(cdStart + cdLen, zip.length);

        while (pos + 46 <= cdEnd) {
            if (readU32LE(zip, pos) != SIG_CD) {
                break;
            }
            final int compressionMethod = readU16LE(zip, pos + 10);
            final long compressedSize   = readU32LE(zip, pos + 20);
            final long uncompressedSize = readU32LE(zip, pos + 24);
            final int fileNameLen       = readU16LE(zip, pos + 28);
            final int extraLen          = readU16LE(zip, pos + 30);
            final int commentLen        = readU16LE(zip, pos + 32);
            final long localOffset      = readU32LE(zip, pos + 42);

            // Name match
            if (fileNameLen == nameBytes.length && pos + 46 + fileNameLen <= cdEnd) {
                boolean match = true;
                for (int i = 0; i < nameBytes.length; i++) {
                    if (zip[pos + 46 + i] != nameBytes[i]) { match = false; break; }
                }
                if (match) {
                    return readLocalEntry(zip, (int) localOffset, compressionMethod,
                            (int) compressedSize, (int) uncompressedSize, entryPath, debugName);
                }
            }
            pos += 46 + fileNameLen + extraLen + commentLen;
        }
        return Optional.empty();
    }

    /**
     * Scans CD for any entry under Metadata/ ending in .png and returns the first one found.
     */
    private Optional<byte[]> extractFirstMetadataPng(final byte[] zip, final int cdStart, final int cdLen) {
        int pos = cdStart;
        final int cdEnd = Math.min(cdStart + cdLen, zip.length);

        while (pos + 46 <= cdEnd) {
            if (readU32LE(zip, pos) != SIG_CD) break;
            final int compressionMethod = readU16LE(zip, pos + 10);
            final long compressedSize   = readU32LE(zip, pos + 20);
            final long uncompressedSize = readU32LE(zip, pos + 24);
            final int fileNameLen       = readU16LE(zip, pos + 28);
            final int extraLen          = readU16LE(zip, pos + 30);
            final int commentLen        = readU16LE(zip, pos + 32);
            final long localOffset      = readU32LE(zip, pos + 42);

            if (fileNameLen > 0 && pos + 46 + fileNameLen <= cdEnd) {
                final String name = new String(zip, pos + 46, fileNameLen,
                        java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                if (name.startsWith("metadata/") && name.endsWith(".png")) {
                    final Optional<byte[]> result = readLocalEntry(zip, (int) localOffset,
                            compressionMethod, (int) compressedSize, (int) uncompressedSize, name, "zip");
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
            pos += 46 + fileNameLen + extraLen + commentLen;
        }
        return Optional.empty();
    }

    /**
     * Reads and decompresses a single local entry from the ZIP bytes.
     * Supports method 0 (STORED) and method 8 (DEFLATE).
     */
    private Optional<byte[]> readLocalEntry(final byte[] zip, final int localOffset,
            final int compressionMethod, int compressedSize, final int uncompressedSize,
            final String entryName, final String debugName) {
        if (localOffset + 30 > zip.length) {
            Log.warnf("SdCardThumbnailService: local header for %s out of bounds in %s", entryName, debugName);
            return Optional.empty();
        }
        if (readU32LE(zip, localOffset) != SIG_LOCAL) {
            Log.warnf("SdCardThumbnailService: bad local header sig for %s in %s", entryName, debugName);
            return Optional.empty();
        }

        final int fileNameLen = readU16LE(zip, localOffset + 26);
        final int extraLen    = readU16LE(zip, localOffset + 28);
        final int dataStart   = localOffset + 30 + fileNameLen + extraLen;

        // If CD says compressedSize==0 (data descriptor), figure it out from array bounds
        if (compressedSize == 0) {
            // Best effort: use distance to end of file
            compressedSize = zip.length - dataStart;
        }
        if (dataStart + compressedSize > zip.length) {
            compressedSize = zip.length - dataStart;
        }
        if (compressedSize <= 0) {
            return Optional.empty();
        }

        try {
            if (compressionMethod == 0) {
                // STORED — raw bytes
                final byte[] data = new byte[compressedSize];
                System.arraycopy(zip, dataStart, data, 0, compressedSize);
                return Optional.of(data);
            } else if (compressionMethod == 8) {
                // DEFLATE
                final java.util.zip.Inflater inflater = new java.util.zip.Inflater(true); // raw deflate
                inflater.setInput(zip, dataStart, compressedSize);
                final ByteArrayOutputStream out = new ByteArrayOutputStream(
                        uncompressedSize > 0 ? uncompressedSize : 65536);
                final byte[] buf = new byte[8192];
                while (!inflater.finished() && !inflater.needsInput()) {
                    final int n = inflater.inflate(buf);
                    out.write(buf, 0, n);
                }
                inflater.end();
                final byte[] result = out.toByteArray();
                Log.infof("SdCardThumbnailService: extracted %s from %s (%d bytes)", entryName, debugName, result.length);
                return Optional.of(result);
            } else {
                Log.warnf("SdCardThumbnailService: unsupported compression method %d for %s", compressionMethod, entryName);
                return Optional.empty();
            }
        } catch (Exception ex) {
            Log.warnf("SdCardThumbnailService: failed to extract %s from %s: %s", entryName, debugName, ex.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // ZIP byte parsing helpers
    // -------------------------------------------------------------------------

    /** Scan buffer (backwards) for last occurrence of a 4-byte little-endian signature. */
    private static int findLastSignature(final byte[] buf, final int sig) {
        for (int i = buf.length - 4; i >= 0; i--) {
            if (readU32LE(buf, i) == sig) return i;
        }
        return -1;
    }

    /** Read unsigned 16-bit little-endian integer. */
    private static int readU16LE(final byte[] buf, final int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    /** Read unsigned 32-bit little-endian integer (returned as long to avoid sign issues). */
    private static long readU32LE(final byte[] buf, final int off) {
        return (buf[off] & 0xFFL)
             | ((buf[off + 1] & 0xFFL) << 8)
             | ((buf[off + 2] & 0xFFL) << 16)
             | ((buf[off + 3] & 0xFFL) << 24);
    }

    /** Read signed 64-bit little-endian integer. */
    private static long readU64LE(final byte[] buf, final int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (buf[off + i] & 0xFFL);
        return v;
    }

}
