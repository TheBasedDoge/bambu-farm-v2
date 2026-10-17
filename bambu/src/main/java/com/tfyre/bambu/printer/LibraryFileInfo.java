package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which printer a library file was sliced for - read from the file, not from its name.
 * <p>
 * Bambu Studio writes the machine into every sliced {@code .3mf} ({@code Metadata/slice_info.config},
 * {@code printer_model_id}). That is the truth about the file, and it is what lets the app label a file "P1" or
 * "H2D" and refuse to send it to the other kind of printer. A file with no slice information (a project that
 * was saved but never sliced) reads as unknown, and falls back to the {@code _H2D} naming convention.
 * <p>
 * Only ids this class recognises produce a family; anything else is shown as its raw id and is never blocked -
 * a guard that guesses would be worse than no guard.
 */
@ApplicationScoped
public class LibraryFileInfo {

    private static final Pattern MODEL_ID = Pattern.compile("printer_model_id\"\\s+value=\"([^\"]*)\"");
    /** Bambu's machine codes, as they appear in slice_info.config, to the gcode family that can run the file. */
    private static final Map<String, String> FAMILIES = Map.of(
            "C11", BambuConst.PrinterModel.FAMILY_P1, // P1P
            "C12", BambuConst.PrinterModel.FAMILY_P1, // P1S
            "C13", BambuConst.PrinterModel.FAMILY_P1, // X1E
            "BL-P001", BambuConst.PrinterModel.FAMILY_P1, // X1 Carbon
            "BL-P002", BambuConst.PrinterModel.FAMILY_P1, // X1
            "O1D", BambuConst.PrinterModel.FAMILY_H2D, // H2D
            "N1", BambuConst.PrinterModel.FAMILY_A1, // A1 mini
            "N2S", BambuConst.PrinterModel.FAMILY_A1); // A1

    /**
     * @param modelId the machine code from the file, or blank when it has none
     * @param family  {@code p1} / {@code h2d} / {@code a1}, or blank when the code is missing or not one this
     *                class knows
     * @param byName  the family came from the file's name, not its contents
     */
    public record Info(String modelId, String family, boolean byName) {

        /** Short label for a picker or a table: "P1", "H2D", the raw code, or "?" */
        public String badge() {
            if (!family.isEmpty()) {
                return family.toUpperCase(Locale.ENGLISH);
            }
            return modelId.isEmpty() ? "?" : modelId;
        }
    }

    @Inject
    BambuConfig config;

    /** path|size|modified → info, so each file is opened once per version of it. */
    private final Map<String, Info> cache = new ConcurrentHashMap<>();

    /** True when {@code libraryPath} follows the naming convention for an H2D slice: {@code Name_H2D.gcode.3mf}. */
    public static boolean namedH2d(final String libraryPath) {
        return stem(libraryPath).toLowerCase(Locale.ENGLISH).endsWith("_h2d");
    }

    /** The file name without folders and without {@code .gcode.3mf} / {@code .3mf}. */
    public static String stem(final String libraryPath) {
        if (libraryPath == null) {
            return "";
        }
        String name = libraryPath.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        final String lower = name.toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".gcode.3mf")) {
            return name.substring(0, name.length() - ".gcode.3mf".length());
        }
        return lower.endsWith(".3mf") ? name.substring(0, name.length() - ".3mf".length()) : name;
    }

    /**
     * Size and modified time of a library file as last seen on disk; bytes -1 = not a regular file.
     * The library lives in a Windows folder shared into the container, where every look at a file crosses into
     * Windows and now and then stalls for seconds - and pages asked about the same files every second (the
     * Automation page's freeze on 2026-10-10). So a look is reused for {@link #STAT_TTL_MS}, and the app's own
     * changes to the library clear it at once ({@link #invalidate()}, called by LibraryService). A file changed
     * by hand in the folder is noticed within a minute.
     */
    private record Stat(long bytes, long modified, long checkedAt) {
    }

    private static final long STAT_TTL_MS = 60_000;
    private final Map<String, Stat> statCache = new ConcurrentHashMap<>();

    private Stat stat(final String libraryPath, final Path file) throws IOException {
        final long now = System.currentTimeMillis();
        final Stat cached = statCache.get(libraryPath);
        if (cached != null && now - cached.checkedAt() < STAT_TTL_MS) {
            return cached;
        }
        final Stat fresh = Files.isRegularFile(file)
                ? new Stat(Files.size(file), Files.getLastModifiedTime(file).toMillis(), now)
                : new Stat(-1, 0, now);
        statCache.put(libraryPath, fresh);
        return fresh;
    }

    /** Forget what was seen on disk - call after the app itself adds, replaces, moves or deletes a library file. */
    public void invalidate() {
        statCache.clear();
    }

    public Info of(final String libraryPath) {
        if (libraryPath == null || libraryPath.isBlank()) {
            return new Info("", "", false);
        }
        final Path file = Path.of(config.batchPrint().library()).resolve(libraryPath);
        final String key;
        try {
            final Stat st = stat(libraryPath, file);
            if (st.bytes() < 0) {
                return byName(libraryPath, "");
            }
            key = libraryPath + "|" + st.bytes() + "|" + st.modified();
        } catch (IOException | RuntimeException ex) {
            return byName(libraryPath, "");
        }
        return cache.computeIfAbsent(key, k -> read(libraryPath, file));
    }

    /**
     * What a sliced file says about the print: read from the same slice information as the machine id.
     *
     * @param material the filament types the slice uses, e.g. "ASA" or "PETG + PLA"; blank when not sliced
     * @param grams    filament for every plate in the file
     * @param seconds  the slicer's time estimate for every plate
     */
    public record Details(String material, double grams, long seconds, int objects, int plates, long bytes, long modifiedMillis) {

        public boolean sliced() {
            return plates > 0 && (grams > 0 || seconds > 0);
        }
    }

    private static final Pattern FILAMENT_TYPE = Pattern.compile("<filament\\s[^>]*\\stype=\"([^\"]*)\"");
    private static final Pattern WEIGHT = Pattern.compile("key=\"weight\"\\s+value=\"([0-9.]+)\"");
    private static final Pattern PREDICTION = Pattern.compile("key=\"prediction\"\\s+value=\"([0-9]+)\"");
    private static final Pattern OBJECT = Pattern.compile("<object\\s");
    private static final Pattern PLATE = Pattern.compile("<plate>");
    private final Map<String, Details> detailCache = new ConcurrentHashMap<>();

    public Details details(final String libraryPath) {
        final Details none = new Details("", 0, 0, 0, 0, 0, 0);
        if (libraryPath == null || libraryPath.isBlank()) {
            return none;
        }
        final Path file = Path.of(config.batchPrint().library()).resolve(libraryPath);
        try {
            final Stat st = stat(libraryPath, file);
            if (st.bytes() < 0) {
                return none;
            }
            final long bytes = st.bytes();
            final long modified = st.modified();
            return detailCache.computeIfAbsent(libraryPath + "|" + bytes + "|" + modified, k -> readDetails(file, bytes, modified));
        } catch (IOException | RuntimeException ex) {
            return none;
        }
    }

    private static Details readDetails(final Path file, final long bytes, final long modified) {
        String xml = "";
        try (ZipFile zip = new ZipFile(file.toFile())) {
            final ZipEntry entry = zip.getEntry("Metadata/slice_info.config");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    xml = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // unreadable = not sliced, as far as this page is concerned
        }
        final java.util.Set<String> types = new java.util.LinkedHashSet<>();
        final Matcher t = FILAMENT_TYPE.matcher(xml);
        while (t.find()) {
            if (!t.group(1).isBlank()) {
                types.add(t.group(1).strip());
            }
        }
        double grams = 0;
        final Matcher w = WEIGHT.matcher(xml);
        while (w.find()) {
            try {
                grams += Double.parseDouble(w.group(1));
            } catch (NumberFormatException ex) {
                // ignore one bad figure
            }
        }
        long seconds = 0;
        final Matcher s = PREDICTION.matcher(xml);
        while (s.find()) {
            seconds += Long.parseLong(s.group(1));
        }
        return new Details(String.join(" + ", types), grams, seconds, (int) OBJECT.matcher(xml).results().count(),
                (int) PLATE.matcher(xml).results().count(), bytes, modified);
    }

    /** The first plate's preview picture from the file, or null when it has none. */
    /** Plate pictures by path|size|modified - small PNGs, read from the zip once instead of on every Files visit. */
    private final Map<String, byte[]> thumbnailCache = new ConcurrentHashMap<>();

    public byte[] thumbnail(final String libraryPath) {
        final Path file = Path.of(config.batchPrint().library()).resolve(libraryPath);
        try {
            final Stat st = stat(libraryPath, file);
            if (st.bytes() < 0) {
                return null;
            }
            final String key = libraryPath + "|" + st.bytes() + "|" + st.modified();
            final byte[] cached = thumbnailCache.get(key);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }
            final byte[] read = readThumbnail(file);
            thumbnailCache.put(key, read == null ? new byte[0] : read);
            return read;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static byte[] readThumbnail(final Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("Metadata/plate_1_small.png");
            if (entry == null) {
                entry = zip.getEntry("Metadata/plate_1.png");
            }
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readNBytes(4 << 20);
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Turns a list of library files into parts: one per file that is not an H2D slice, with the H2D slice named
     * like it plus {@code _H2D} attached as its variant. For anything that takes "all the files in a project" -
     * without this, an H2D slice in the project would be queued or mapped as if it were a part of its own.
     */
    public java.util.List<MappingPart> pair(final java.util.List<String> files) {
        final java.util.List<MappingPart> out = new java.util.ArrayList<>();
        final java.util.Set<String> used = new java.util.HashSet<>();
        for (final String f : files) {
            if (BambuConst.PrinterModel.FAMILY_H2D.equals(family(f))) {
                continue;
            }
            MappingPart part = new MappingPart(GcodeSource.LIBRARY, f, 1, 1);
            final String wanted = stem(f) + "_h2d";
            final java.util.Optional<String> twin = files.stream()
                    .filter(c -> !c.equals(f) && stem(c).equalsIgnoreCase(wanted)).findFirst();
            if (twin.isPresent()) {
                part = part.withVariant(BambuConst.PrinterModel.FAMILY_H2D, twin.get());
                used.add(twin.get());
            }
            out.add(part);
        }
        // An H2D slice with no P1 twin in the list still has to be printed by something: keep it as its own part.
        for (final String f : files) {
            if (BambuConst.PrinterModel.FAMILY_H2D.equals(family(f)) && !used.contains(f)) {
                out.add(new MappingPart(GcodeSource.LIBRARY, f, 1, 1));
            }
        }
        return out;
    }

    private static final Pattern PLATE_INDEX = Pattern.compile("key=\"index\"\\s+value=\"([0-9]+)\"");
    private static final Pattern FILAMENT_TAG = Pattern.compile("<filament\\s");

    private String sliceInfo(final String libraryPath) {
        final Path file = Path.of(config.batchPrint().library()).resolve(libraryPath);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            final ZipEntry entry = zip.getEntry("Metadata/slice_info.config");
            if (entry == null) {
                return "";
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private record Facts(java.util.List<Integer> plates, int filaments) {
    }

    /** path|size|modified → facts. The dispatcher asks about every waiting job on every pass; the zip is opened once. */
    private final Map<String, Facts> factCache = new ConcurrentHashMap<>();

    private Facts facts(final String libraryPath) {
        final Facts none = new Facts(java.util.List.of(), 0);
        if (libraryPath == null || libraryPath.isBlank()) {
            return none;
        }
        final Path file = Path.of(config.batchPrint().library()).resolve(libraryPath);
        try {
            final Stat st = stat(libraryPath, file);
            if (st.bytes() < 0) {
                return none;
            }
            return factCache.computeIfAbsent(libraryPath + "|" + st.bytes() + "|" + st.modified(), k -> {
                final String xml = sliceInfo(libraryPath);
                final java.util.List<Integer> plates = new java.util.ArrayList<>();
                final Matcher m = PLATE_INDEX.matcher(xml);
                while (m.find()) {
                    plates.add(Integer.parseInt(m.group(1)));
                }
                return new Facts(java.util.List.copyOf(plates), (int) FILAMENT_TAG.matcher(xml).results().count());
            });
        } catch (IOException | RuntimeException ex) {
            return none;
        }
    }

    /** The plate numbers a sliced file actually contains. An H2D slice exported from plate 4 of a project holds only "4". */
    public java.util.List<Integer> plateIds(final String libraryPath) {
        return facts(libraryPath).plates();
    }

    /** How many filaments the slice feeds from - 2 for a part with supports in another material. */
    public int filamentCount(final String libraryPath) {
        return facts(libraryPath).filaments();
    }

    /**
     * True when this printer cannot be handed the file by the app: a slice that feeds from two filaments on a
     * two-nozzle printer. The app assigns one tray to a whole print, which would point both filaments at the
     * same spool - on the wrong nozzle for one of them.
     */
    public boolean needsTwoTrays(final String libraryPath, final BambuConst.PrinterModel model) {
        return model != null && model.isDualNozzle() && filamentCount(libraryPath) > 1;
    }

    /** True when {@code part} does not say where every filament of this slice feeds from on such a printer. */
    public boolean missingTrays(final MappingPart part, final String libraryPath, final BambuConst.PrinterModel model) {
        return needsTwoTrays(libraryPath, model) && part.extraSlots().size() < filamentCount(libraryPath) - 1;
    }

    /**
     * The plate to print from {@code path} for a part mapped to {@code mappedPlate}. A variant (the H2D file of a
     * P1 part) shares the mapping's plate number, but is its own file with its own numbering: when it does not
     * contain that plate and holds exactly one, that one is meant.
     */
    public int plateFor(final String path, final int mappedPlate) {
        final java.util.List<Integer> plates = plateIds(path);
        return plates.contains(mappedPlate) || plates.size() != 1 ? mappedPlate : plates.get(0);
    }

    /**
     * The file a part prints from on a printer of {@code model}, or empty when that printer cannot print it:
     * the P1 file or the mapped variant as {@link MappingPart#pathFor} says, minus any file that was sliced for
     * the other kind of printer - plus one case the mapping alone cannot express, a part whose only file IS an
     * H2D slice (nothing to print it on a P1), which the H2D prints from the main file.
     */
    public java.util.Optional<String> fileFor(final MappingPart part, final BambuConst.PrinterModel model) {
        final java.util.Optional<String> mapped = part.pathFor(model);
        if (part.source() != GcodeSource.LIBRARY) {
            return mapped;
        }
        if (mapped.isPresent()) {
            return mismatch(mapped.get(), model) == null && !missingTrays(part, mapped.get(), model) ? mapped : java.util.Optional.empty();
        }
        final BambuConst.PrinterModel m = model == null ? BambuConst.PrinterModel.UNKNOWN : model;
        final Info main = of(part.path());
        return !main.family().isEmpty() && !main.byName() && main.family().equals(m.gcodeFamily())
                && !missingTrays(part, part.path(), m)
                ? java.util.Optional.of(part.path()) : java.util.Optional.empty();
    }

    public String family(final String libraryPath) {
        return of(libraryPath).family();
    }

    private Info read(final String libraryPath, final Path file) {
        String modelId = "";
        try (ZipFile zip = new ZipFile(file.toFile())) {
            final ZipEntry entry = zip.getEntry("Metadata/slice_info.config");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    // The id is in the first plate's metadata, a few hundred bytes in; 64 KB is generous.
                    final Matcher m = MODEL_ID.matcher(new String(in.readNBytes(65536), StandardCharsets.UTF_8));
                    if (m.find()) {
                        modelId = m.group(1).strip();
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            Log.debugf("LibraryFileInfo: cannot read %s: %s", libraryPath, ex.getMessage());
        }
        final String family = FAMILIES.getOrDefault(modelId, "");
        return family.isEmpty() ? byName(libraryPath, modelId) : new Info(modelId, family, false);
    }

    private static Info byName(final String libraryPath, final String modelId) {
        return namedH2d(libraryPath) && modelId.isEmpty()
                ? new Info("", BambuConst.PrinterModel.FAMILY_H2D, true) : new Info(modelId, "", false);
    }

    /**
     * Why {@code libraryPath} must not be sent to a printer of {@code model}, or null when it may. Only speaks
     * when the file states a machine this class recognises and it is the wrong kind.
     */
    public String mismatch(final String libraryPath, final BambuConst.PrinterModel model) {
        final Info info = of(libraryPath);
        if (info.family().isEmpty() || info.byName() || model == null || model == BambuConst.PrinterModel.UNKNOWN) {
            return null;
        }
        return info.family().equals(model.gcodeFamily()) ? null
                : "%s was sliced for a %s printer, not for a %s".formatted(libraryPath, info.badge(),
                        model.gcodeFamily().toUpperCase(Locale.ENGLISH));
    }
}
