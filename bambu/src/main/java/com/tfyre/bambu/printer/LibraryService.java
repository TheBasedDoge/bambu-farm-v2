package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The print-file library as the Files page sees it: one entry per <b>part</b>, with the P1 slice and the H2D
 * slice of it side by side, what maps to it and how it has been printing - plus the few things that change the
 * library (save, restore, delete, attach an H2D file).
 * <p>
 * The library itself is unchanged: a folder of {@code .3mf} files, with sub-folders as projects. A file's path
 * in it is still the identity everything else uses (mappings, stock, queues), which is why nothing here renames
 * a file - replacing one keeps its name, so every mapping and every stock count stays attached.
 * <p>
 * Replacing or deleting keeps the outgoing file in a sibling folder, {@code <library>-previous}, one version per
 * path. Outside the library on purpose: inside it, a folder would be listed as a project.
 */
@ApplicationScoped
public class LibraryService {

    /** A listing (or one variation of it) that maps to a part. */
    public record Use(String market, String title, String variation, int piecesPerUnit) {
    }

    /**
     * One part.
     *
     * @param p1         library path of the P1 slice, or null for a part that only exists as an H2D slice
     * @param h2d        library path of the H2D slice, or null
     * @param h2dMakes   pieces one H2D print makes
     * @param h2dAttached false when the H2D file was only found by its name and no mapping uses it yet
     * @param project    the project folder, or blank for the library root
     */
    public record Part(String title, String project, String p1, String h2d, int h2dMakes, boolean h2dAttached,
            List<Use> uses, int finished, int failed, OffsetDateTime lastPrinted, String lastPrinter) {

        public String id() {
            return p1 != null ? p1 : h2d;
        }

        public boolean mapped() {
            return !uses.isEmpty();
        }

        /** Failed prints as a share of those that ran to a verdict, 0-100. */
        public int failPct() {
            return finished + failed == 0 ? 0 : Math.round(100f * failed / (finished + failed));
        }
    }

    /** A mapping that points at a file the library does not have, or at a path on the printers' SD cards. */
    public record Loose(String path, boolean sdCard, List<Use> uses) {
    }

    public record Result(boolean ok, String message) {
    }

    @Inject
    BambuConfig config;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;
    @Inject
    MarketListingCache listingCache;
    @Inject
    PrintHistoryService history;
    @Inject
    LibraryFileInfo fileInfo;
    @Inject
    StockService stock;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    PrintQueueService printQueue;
    @Inject
    BambuPrinters printers;

    private static final String GROUPS_FILENAME = "bambu-library-groups.json";
    /** Where parts with no group of their own, and no siblings to form one with, are listed. */
    public static final String OTHER_GROUP = "Other";

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** Chosen groups: {@code project:<name>} or {@code file:<path>} → group. Anything absent is automatic. */
    private final Map<String, String> groupChoices = new java.util.concurrent.ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void loadGroups() {
        final Path path = groupsPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            groupChoices.putAll(mapper.readValue(path.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
            }));
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "LibraryService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private Path groupsPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(GROUPS_FILENAME) : Path.of(GROUPS_FILENAME);
    }

    private synchronized void saveGroups() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(groupsPath().toFile(), new java.util.TreeMap<>(groupChoices));
        } catch (IOException ex) {
            Log.errorf(ex, "LibraryService: cannot save %s: %s", groupsPath(), ex.getMessage());
        }
    }

    /** What a part's group is filed under: its project when it is in one (a project is in one group), else the file. */
    private static String groupKey(final Part p) {
        return p.project().isBlank() ? "file:" + p.id() : "project:" + p.project();
    }

    /** The first word of a project or part name - "S2000", "Audi", "Infiniti" - which is how this library is named. */
    private static String autoGroup(final Part p) {
        final String name = (p.project().isBlank() ? p.title() : p.project()).strip();
        final int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /**
     * The group each part is shown under, by {@link Part#id()}. A group you chose wins. Otherwise parts are
     * grouped by the first word of their name, and a first word nothing else shares goes under "Other" rather
     * than becoming a group of one.
     */
    public Map<String, String> groups(final List<Part> parts) {
        final Map<String, Integer> sizes = new HashMap<>();
        // Count by project-or-file, so a project of five files is one member, not five.
        final java.util.Set<String> counted = new java.util.HashSet<>();
        for (final Part p : parts) {
            if (!groupChoices.containsKey(groupKey(p)) && counted.add(groupKey(p))) {
                sizes.merge(autoGroup(p).toLowerCase(Locale.ENGLISH), 1, Integer::sum);
            }
        }
        // A chosen group with the same name as an automatic one is the same group.
        groupChoices.values().forEach(g -> sizes.merge(g.toLowerCase(Locale.ENGLISH), 2, Integer::sum));
        final Map<String, String> out = new HashMap<>();
        for (final Part p : parts) {
            final String chosen = groupChoices.get(groupKey(p));
            if (chosen != null) {
                out.put(p.id(), chosen);
            } else {
                final String auto = autoGroup(p);
                out.put(p.id(), sizes.getOrDefault(auto.toLowerCase(Locale.ENGLISH), 0) > 1 ? auto : OTHER_GROUP);
            }
        }
        return out;
    }

    /** Puts a part - or the whole project it is in - under {@code group}; blank goes back to automatic. */
    public void setGroup(final Part p, final String group) {
        if (group == null || group.isBlank()) {
            groupChoices.remove(groupKey(p));
        } else {
            groupChoices.put(groupKey(p), group.strip());
        }
        saveGroups();
    }

    /** Puts a loose file (one that is not in a project) under {@code group}; blank goes back to automatic. */
    public void setFileGroup(final String path, final String group) {
        if (group == null || group.isBlank()) {
            groupChoices.remove("file:" + path);
        } else {
            groupChoices.put("file:" + path, group.strip());
        }
        saveGroups();
    }

    private Path root() {
        return Path.of(config.batchPrint().library());
    }

    private Path previousRoot() {
        final Path lib = root().toAbsolutePath();
        return lib.resolveSibling(lib.getFileName() + "-previous");
    }

    private static String baseName(final String path) {
        final String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String folderOf(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** "S2000_AP1_FogLights_H2D.gcode.3mf" → "S2000 AP1 FogLights". */
    public static String titleOf(final String path) {
        String stem = LibraryFileInfo.stem(path);
        if (stem.toLowerCase(Locale.ENGLISH).endsWith("_h2d")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        return stem.replace('_', ' ').replaceAll("\\s+", " ").strip();
    }

    // -------------------------------------------------------------------------
    // reading
    // -------------------------------------------------------------------------
    /** Every mapped part with the listing that maps it, both marketplaces. */
    private List<Map.Entry<MappingPart, Use>> mappedParts() {
        final Map<String, String> etsyTitles = new HashMap<>();
        listingCache.getEtsy().forEach(l -> etsyTitles.put(String.valueOf(l.listingId()), l.title()));
        final Map<String, String> ebayTitles = new HashMap<>();
        listingCache.getEbay().forEach(l -> ebayTitles.put(l.listingKey(), l.title()));
        final List<Map.Entry<MappingPart, Use>> out = new ArrayList<>();
        etsyMappings.entries().forEach((key, entry) -> entry.parts().forEach(p
                -> out.add(Map.entry(p, use("Etsy", key, etsyTitles, p)))));
        ebayMappings.entries().forEach((key, entry) -> entry.parts().forEach(p
                -> out.add(Map.entry(p, use("eBay", key, ebayTitles, p)))));
        return out;
    }

    private static Use use(final String market, final String storageKey, final Map<String, String> titles, final MappingPart part) {
        final int bar = storageKey.indexOf('|');
        final String listing = bar < 0 ? storageKey : storageKey.substring(0, bar);
        final String variation = bar < 0 || bar == storageKey.length() - 1 ? "" : storageKey.substring(bar + 1);
        final String title = titles.getOrDefault(listing, "");
        return new Use(market, unescape(title.isBlank() ? listing : title), unescape(variation), part.copiesPerUnit());
    }

    /** Listing titles and variation names arrive HTML-escaped from the marketplaces (6&quot; for 6"). */
    private static String unescape(final String s) {
        return s == null ? "" : s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    public List<Part> parts() {
        final List<String> files = queuer.listLibraryFiles();
        final List<Map.Entry<MappingPart, Use>> mapped = mappedParts();

        // What the mappings say: P1 path → its H2D file, and who uses each P1 path.
        final Map<String, String> h2dByP1 = new HashMap<>();
        final Map<String, Integer> makesByP1 = new HashMap<>();
        final Map<String, List<Use>> usesByPath = new HashMap<>();
        for (final Map.Entry<MappingPart, Use> e : mapped) {
            final MappingPart p = e.getKey();
            if (p.source() != GcodeSource.LIBRARY) {
                continue;
            }
            usesByPath.computeIfAbsent(p.path(), k -> new ArrayList<>()).add(e.getValue());
            p.h2dPath().ifPresent(h -> {
                h2dByP1.putIfAbsent(p.path(), h);
                makesByP1.merge(p.path(), p.h2dYield(), Math::max);
            });
        }
        final java.util.Set<String> attachedH2d = new java.util.HashSet<>(h2dByP1.values());

        // What the names say: Name_H2D beside Name, for H2D files no mapping uses yet.
        final Map<String, String> namedH2dByP1 = new HashMap<>();
        for (final String f : files) {
            if (attachedH2d.contains(f) || !LibraryFileInfo.namedH2d(f)) {
                continue;
            }
            final String stem = LibraryFileInfo.stem(f);
            final String wanted = stem.substring(0, stem.length() - 4);
            files.stream()
                    .filter(c -> !c.equals(f) && LibraryFileInfo.stem(c).equalsIgnoreCase(wanted))
                    // Same folder first, then anywhere: an H2D slice dropped in the root still finds its part.
                    .min(Comparator.comparingInt((String c) -> folderOf(c).equals(folderOf(f)) ? 0 : 1))
                    .ifPresent(p1 -> namedH2dByP1.putIfAbsent(p1, f));
        }

        final Map<String, int[]> counts = new HashMap<>(); // finished, failed
        final Map<String, PrintHistoryService.PrintJob> last = new HashMap<>();
        for (final PrintHistoryService.PrintJob j : history.getJobs()) {
            if (j.file() == null || j.file().isBlank()) {
                continue;
            }
            final String name = baseName(j.file()).toLowerCase(Locale.ENGLISH);
            final int[] c = counts.computeIfAbsent(name, k -> new int[2]);
            if ("Finished".equals(j.result())) {
                c[0]++;
            } else if ("Failed".equals(j.result())) {
                c[1]++;
            }
            if (j.ended() != null) {
                last.merge(name, j, (a, b) -> a.ended().isAfter(b.ended()) ? a : b);
            }
        }

        final java.util.Set<String> shownAsH2d = new java.util.HashSet<>(attachedH2d);
        shownAsH2d.addAll(namedH2dByP1.values());
        final List<Part> out = new ArrayList<>();
        for (final String f : files) {
            if (shownAsH2d.contains(f)) {
                continue; // drawn on its part's row
            }
            final boolean h2dOnly = BambuConst.PrinterModel.FAMILY_H2D.equals(fileInfo.family(f));
            final String h2d = h2dOnly ? f : h2dByP1.getOrDefault(f, namedH2dByP1.get(f));
            final List<String> names = new ArrayList<>(List.of(baseName(f).toLowerCase(Locale.ENGLISH)));
            if (h2d != null && !h2d.equals(f)) {
                names.add(baseName(h2d).toLowerCase(Locale.ENGLISH));
            }
            int finished = 0;
            int failed = 0;
            PrintHistoryService.PrintJob latest = null;
            for (final String n : names) {
                final int[] c = counts.get(n);
                if (c != null) {
                    finished += c[0];
                    failed += c[1];
                }
                final PrintHistoryService.PrintJob l = last.get(n);
                if (l != null && (latest == null || l.ended().isAfter(latest.ended()))) {
                    latest = l;
                }
            }
            out.add(new Part(titleOf(f), folderOf(f), h2dOnly ? null : f, h2d,
                    h2dOnly ? 1 : makesByP1.getOrDefault(f, 1), h2dOnly || h2dByP1.containsKey(f),
                    List.copyOf(usesByPath.getOrDefault(f, List.of())), finished, failed,
                    latest == null ? null : latest.ended(), latest == null ? null : latest.printer()));
        }
        out.sort(Comparator.comparing(Part::project, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Part::title, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Mappings that point somewhere the library is not: a missing file, or a path on the SD cards. */
    public List<Loose> loose() {
        final java.util.Set<String> files = new java.util.HashSet<>(queuer.listLibraryFiles());
        final Map<String, Loose> out = new LinkedHashMap<>();
        for (final Map.Entry<MappingPart, Use> e : mappedParts()) {
            final MappingPart p = e.getKey();
            final boolean sd = p.source() != GcodeSource.LIBRARY;
            final List<String> paths = new ArrayList<>(List.of(p.path()));
            if (!sd) {
                paths.addAll(p.variants().values());
            }
            for (final String path : paths) {
                if (sd || !files.contains(path)) {
                    final Loose before = out.get(path);
                    final List<Use> uses = new ArrayList<>(before == null ? List.of() : before.uses());
                    uses.add(e.getValue());
                    out.put(path, new Loose(path, sd, uses));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    public List<String> projects() {
        return queuer.listProjects();
    }

    public boolean hasPrevious(final String path) {
        return path != null && Files.isRegularFile(previousRoot().resolve(path));
    }

    // -------------------------------------------------------------------------
    // changing
    // -------------------------------------------------------------------------
    private static boolean safe(final String path) {
        return path != null && !path.isBlank() && !path.contains("..") && !path.startsWith("/") && !path.contains("\\")
                && path.toLowerCase(Locale.ENGLISH).endsWith(".3mf");
    }

    /** Moves the current file at {@code path}, if any, to the previous-versions folder, replacing what was there. */
    private void keepPrevious(final String path) throws IOException {
        final Path current = root().resolve(path);
        if (!Files.isRegularFile(current)) {
            return;
        }
        final Path keep = previousRoot().resolve(path);
        Files.createDirectories(keep.getParent());
        Files.move(current, keep, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Saves an uploaded file at {@code path} in the library. An existing file there is kept as the previous
     * version. A new file named like a part plus {@code _H2D} is attached to that part's mappings.
     */
    public Result save(final String path, final Path uploaded) {
        if (!safe(path)) {
            return new Result(false, "Not a library file name: " + path);
        }
        try {
            final Path target = root().resolve(path);
            final boolean replaced = Files.isRegularFile(target);
            if (!replaced) {
                // The same file name in two folders is two different files to the library and ONE file to a
                // printer's SD card, where project folders are flattened - each would overwrite the other.
                final Optional<String> twin = queuer.listLibraryFiles().stream()
                        .filter(f -> !f.equals(path) && baseName(f).equalsIgnoreCase(baseName(path))).findFirst();
                if (twin.isPresent()) {
                    return new Result(false, "%s is already in the library as %s - replace that one, or move it with the Project box"
                            .formatted(baseName(path), twin.get()));
                }
            }
            Files.createDirectories(target.getParent());
            // Landed beside the target first, so a failed upload cannot cost the file that was there.
            final Path incoming = target.resolveSibling(target.getFileName() + ".incoming");
            Files.copy(uploaded, incoming, StandardCopyOption.REPLACE_EXISTING);
            keepPrevious(path);
            Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
            String note = replaced ? "Replaced %s - the previous version is kept".formatted(path) : "Added %s".formatted(path);
            if (!replaced && LibraryFileInfo.namedH2d(path)) {
                final Optional<Part> owner = parts().stream()
                        .filter(p -> path.equals(p.h2d()) && p.p1() != null && !p.h2dAttached() && p.mapped()).findFirst();
                if (owner.isPresent()) {
                    final Result attached = attachH2d(owner.get().p1(), path);
                    note += ". " + attached.message();
                }
            }
            Log.infof("LibraryService: %s", note);
            fileInfo.invalidate();
            queuer.invalidateLibraryListing();
            return new Result(true, note);
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "LibraryService: cannot save %s: %s", path, ex.getMessage());
            return new Result(false, "Cannot save %s: %s".formatted(path, ex.getMessage()));
        }
    }

    /** Swaps the file at {@code path} with its kept previous version, so restoring can itself be undone. */
    public Result restorePrevious(final String path) {
        if (!safe(path) || !hasPrevious(path)) {
            return new Result(false, "No previous version of %s is kept".formatted(path));
        }
        try {
            final Path current = root().resolve(path);
            final Path kept = previousRoot().resolve(path);
            final Path swap = kept.resolveSibling(kept.getFileName() + ".swap");
            Files.move(kept, swap, StandardCopyOption.REPLACE_EXISTING);
            keepPrevious(path);
            Files.move(swap, current, StandardCopyOption.REPLACE_EXISTING);
            fileInfo.invalidate();
            queuer.invalidateLibraryListing();
            return new Result(true, "Restored the previous version of %s".formatted(path));
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "LibraryService: cannot restore %s: %s", path, ex.getMessage());
            return new Result(false, "Cannot restore %s: %s".formatted(path, ex.getMessage()));
        }
    }

    /** Jobs not yet started that would print from {@code path}: in the dispatch pool, or on a printer's queue. */
    private int waitingJobs(final String path) {
        int n = 0;
        for (final DispatchQueueService.PendingJob j : dispatch.getPool()) {
            if (j.part() != null && (path.equals(j.part().path()) || j.part().variants().containsValue(path))) {
                n++;
            }
        }
        for (final BambuPrinters.PrinterDetail d : printers.getPrintersDetail()) {
            for (final PrintQueueService.QueueEntry e : printQueue.getQueue(d.name())) {
                if (path.equals(e.command().filename())
                        || (e.part() != null && (path.equals(e.part().path()) || e.part().variants().containsValue(path)))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Moves a file into a project folder (blank = the library root) and re-points everything that refers to it
     * by path: every mapping that prints it, as the P1 file or as a variant, and the stock filed under it. The
     * file keeps its name, so print history and the copies already on the printers' SD cards still match.
     * <p>
     * Refused while a job that has not started yet would print from the old path - that job carries the path
     * with it and would fail to find the file.
     */
    public Result move(final String path, final String project) {
        final String target = (project == null || project.isBlank() ? "" : project.strip() + "/") + baseName(path);
        if (!safe(path) || !safe(target)) {
            return new Result(false, "Not a library file name: " + path);
        }
        if (target.equals(path)) {
            fileInfo.invalidate();
            queuer.invalidateLibraryListing();
            return new Result(true, "%s is already there".formatted(baseName(path)));
        }
        if (Files.exists(root().resolve(target))) {
            return new Result(false, "%s already exists".formatted(target));
        }
        final int waiting = waitingJobs(path);
        if (waiting > 0) {
            return new Result(false, "%d waiting job%s would print from %s - move it once they have started"
                    .formatted(waiting, waiting == 1 ? "" : "s", path));
        }
        try {
            final Path to = root().resolve(target);
            Files.createDirectories(to.getParent());
            Files.move(root().resolve(path), to);
            final Path kept = previousRoot().resolve(path);
            if (Files.isRegularFile(kept)) {
                final Path keptTo = previousRoot().resolve(target);
                Files.createDirectories(keptTo.getParent());
                Files.move(kept, keptTo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "LibraryService: cannot move %s: %s", path, ex.getMessage());
            return new Result(false, "Cannot move %s: %s".formatted(path, ex.getMessage()));
        }
        int changed = 0;
        for (final Map.Entry<String, EtsyMappingService.MappingEntry> e : etsyMappings.entries().entrySet()) {
            final List<MappingPart> updated = repointed(e.getValue().parts(), path, target);
            if (updated != null) {
                etsyMappings.putByKey(e.getKey(), new EtsyMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        for (final Map.Entry<String, EbayMappingService.MappingEntry> e : ebayMappings.entries().entrySet()) {
            final List<MappingPart> updated = repointed(e.getValue().parts(), path, target);
            if (updated != null) {
                ebayMappings.putByKey(e.getKey(), new EbayMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        stock.renamePath(path, target);
        // A group chosen for the loose file does not follow it into a project: the project's group applies there.
        if (groupChoices.remove("file:" + path) != null) {
            saveGroups();
        }
        Log.infof("LibraryService: moved %s to %s, %d mapping(s) re-pointed", path, target, changed);
        fileInfo.invalidate();
            queuer.invalidateLibraryListing();
        return new Result(true, "Moved to %s%s".formatted(project == null || project.isBlank() ? "the library root" : project.strip(),
                changed == 0 ? "" : " · %d mapping%s updated".formatted(changed, changed == 1 ? "" : "s")));
    }

    /** {@code parts} with every reference to {@code from} replaced by {@code to}, or null when there is none. */
    private static List<MappingPart> repointed(final List<MappingPart> parts, final String from, final String to) {
        boolean any = false;
        final List<MappingPart> out = new ArrayList<>();
        for (final MappingPart p : parts) {
            if (p.source() != GcodeSource.LIBRARY || !(from.equals(p.path()) || p.variants().containsValue(from))) {
                out.add(p);
                continue;
            }
            final Map<String, String> variants = new LinkedHashMap<>();
            p.variants().forEach((family, file) -> variants.put(family, from.equals(file) ? to : file));
            out.add(new MappingPart(p.source(), from.equals(p.path()) ? to : p.path(), p.plateId(), p.copiesPerUnit(),
                    p.amsSlot(), p.filamentType(), p.filamentColor(), variants, p.variantYields(), p.extraSlots()));
            any = true;
        }
        return any ? out : null;
    }

    /** Listings that would break if {@code path} went away: those using it as the P1 file or as a variant. */
    public List<Use> usesOf(final String path) {
        final List<Use> out = new ArrayList<>();
        for (final Map.Entry<MappingPart, Use> e : mappedParts()) {
            final MappingPart p = e.getKey();
            if (p.source() == GcodeSource.LIBRARY && (path.equals(p.path()) || p.variants().containsValue(path))) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** Removes a file nothing maps to. It is moved to the previous-versions folder, not destroyed. */
    public Result delete(final String path) {
        if (!safe(path)) {
            return new Result(false, "Not a library file name: " + path);
        }
        final List<Use> uses = usesOf(path);
        if (!uses.isEmpty()) {
            return new Result(false, "%s is still mapped by %s%s - change that mapping first".formatted(path,
                    uses.get(0).market() + " · " + uses.get(0).title(),
                    uses.size() > 1 ? " and %d more".formatted(uses.size() - 1) : ""));
        }
        final int waiting = waitingJobs(path);
        if (waiting > 0) {
            return new Result(false, "%d waiting job%s would print from %s - delete it once they have started"
                    .formatted(waiting, waiting == 1 ? "" : "s", path));
        }
        try {
            keepPrevious(path);
            fileInfo.invalidate();
            queuer.invalidateLibraryListing();
            return new Result(true, "Deleted %s".formatted(path));
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "LibraryService: cannot delete %s: %s", path, ex.getMessage());
            return new Result(false, "Cannot delete %s: %s".formatted(path, ex.getMessage()));
        }
    }

    /**
     * Sets {@code h2dPath} as the H2D file of every mapped part, in both marketplaces, whose P1 file is
     * {@code p1Path} and that has no H2D file yet. Pieces per print is left at 1.
     */
    public Result attachH2d(final String p1Path, final String h2dPath) {
        final String problem = fileInfo.mismatch(h2dPath, BambuConst.PrinterModel.H2D);
        if (problem != null) {
            return new Result(false, problem);
        }
        int changed = 0;
        for (final Map.Entry<String, EtsyMappingService.MappingEntry> e : etsyMappings.entries().entrySet()) {
            final List<MappingPart> updated = withH2d(e.getValue().parts(), p1Path, h2dPath);
            if (updated != null) {
                etsyMappings.putByKey(e.getKey(), new EtsyMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        for (final Map.Entry<String, EbayMappingService.MappingEntry> e : ebayMappings.entries().entrySet()) {
            final List<MappingPart> updated = withH2d(e.getValue().parts(), p1Path, h2dPath);
            if (updated != null) {
                ebayMappings.putByKey(e.getKey(), new EbayMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        return changed == 0
                ? new Result(false, "No mapping prints %s without an H2D file already".formatted(p1Path))
                : new Result(true, "Attached as the H2D file on %d mapping%s (makes 1 per print - change it in the mapping if the plate holds more)"
                        .formatted(changed, changed == 1 ? "" : "s"));
    }

    /**
     * Sets how many pieces one H2D print makes, on every mapped part, in both marketplaces, whose P1 file is
     * {@code p1Path} and that has an H2D file. One place to say it, because it is a fact about the H2D slice,
     * not about any one listing.
     */
    public Result setH2dMakes(final String p1Path, final int pieces) {
        final int makes = Math.max(1, pieces);
        int changed = 0;
        for (final Map.Entry<String, EtsyMappingService.MappingEntry> e : etsyMappings.entries().entrySet()) {
            final List<MappingPart> updated = withMakes(e.getValue().parts(), p1Path, makes);
            if (updated != null) {
                etsyMappings.putByKey(e.getKey(), new EtsyMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        for (final Map.Entry<String, EbayMappingService.MappingEntry> e : ebayMappings.entries().entrySet()) {
            final List<MappingPart> updated = withMakes(e.getValue().parts(), p1Path, makes);
            if (updated != null) {
                ebayMappings.putByKey(e.getKey(), new EbayMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        return changed == 0
                ? new Result(false, "No mapping prints %s with an H2D file".formatted(p1Path))
                : new Result(true, "One H2D print now makes %d on %d mapping%s".formatted(makes, changed, changed == 1 ? "" : "s"));
    }

    private static List<MappingPart> withMakes(final List<MappingPart> parts, final String p1Path, final int makes) {
        boolean any = false;
        final List<MappingPart> out = new ArrayList<>();
        for (final MappingPart p : parts) {
            if (p.source() == GcodeSource.LIBRARY && p1Path.equals(p.path()) && p.h2dPath().isPresent() && p.h2dYield() != makes) {
                out.add(p.withVariantYield(BambuConst.PrinterModel.FAMILY_H2D, makes));
                any = true;
            } else {
                out.add(p);
            }
        }
        return any ? out : null;
    }

    private static List<MappingPart> withH2d(final List<MappingPart> parts, final String p1Path, final String h2dPath) {
        boolean any = false;
        final List<MappingPart> out = new ArrayList<>();
        for (final MappingPart p : parts) {
            if (p.source() == GcodeSource.LIBRARY && p1Path.equals(p.path()) && p.h2dPath().isEmpty()) {
                out.add(p.withVariant(BambuConst.PrinterModel.FAMILY_H2D, h2dPath));
                any = true;
            } else {
                out.add(p);
            }
        }
        return any ? out : null;
    }
}
