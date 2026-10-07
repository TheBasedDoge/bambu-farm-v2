package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.view.batchprint.Plate;
import com.tfyre.bambu.view.batchprint.PlateFilament;
import com.tfyre.bambu.view.batchprint.ProjectFile;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Shared by the Etsy and eBay Sales Orders views: turns a listing's mapped {@link MappingPart} list plus an ordered
 * quantity into the right number of {@link PrintQueueService.QueueEntry} entries, spread round-robin across
 * whichever printers the user selected. Kept marketplace-agnostic so both integrations behave identically.
 */
@ApplicationScoped
public class GcodeMappingQueuer {

    public record QueueResult(int totalQueued, List<String> errors) {
    }

    @Inject
    BambuConfig config;
    @Inject
    PrintQueueService queueService;
    @Inject
    OrderTrackingService tracking;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    BambuPrinters printers;

    /** Weight + filament-slot-count of a LIBRARY part's plate, read once and reused for both queueing decisions. */
    private record PlateInfo(double weight, int filamentCount) {
    }

    private static final PlateInfo UNKNOWN_PLATE = new PlateInfo(0.0, 1);

    /**
     * The file {@code part} prints from on {@code printerName} - the P1 file or the family variant - or empty when
     * that printer's family has no file mapped. An unknown printer resolves like a P1, as it always did.
     */
    public Optional<String> pathFor(final MappingPart part, final String printerName) {
        final BambuConst.PrinterModel model = printers.getPrinterDetail(printerName)
                .map(d -> d.config().model()).orElse(BambuConst.PrinterModel.UNKNOWN);
        return part.pathFor(model);
    }

    private static String familyOf(final BambuPrinters.PrinterDetail d) {
        return d.config().model().gcodeFamily().toUpperCase();
    }

    /** Reads weight and filament count for the plate from the given file (the variant actually being sent). */
    private PlateInfo loadPlateInfo(final MappingPart part, final String path) {
        if (part.source() != GcodeSource.LIBRARY) {
            // No local project file to read for SD-card-resident files - weight stays untracked and the AMS
            // mapping (if any) falls back to a single-entry list, since we can't inspect the plate's filament count.
            return UNKNOWN_PLATE;
        }
        final Path file = Path.of(config.batchPrint().library()).resolve(path);
        if (!Files.isRegularFile(file)) {
            return UNKNOWN_PLATE;
        }
        final ProjectFile projectFile = projectFileInstance.get();
        try {
            projectFile.setup(path, file.toFile());
            final Optional<Plate> plate = projectFile.getPlates().stream()
                    .filter(p -> p.plateId() == part.plateId())
                    .findFirst();
            final double weight = plate.map(Plate::weight).orElse(0.0);
            final int filamentCount = plate.map(p -> p.filaments().stream()
                            .mapToInt(PlateFilament::filamentId)
                            .max().orElse(0))
                    .filter(n -> n > 0)
                    .orElse(1);
            return new PlateInfo(weight, filamentCount);
        } catch (Exception ex) {
            Log.error(ex.getMessage(), ex);
            return UNKNOWN_PLATE;
        }
    }

    /**
     * Builds the AMS tray-mapping list to send with a print, forcing every filament slot in the file to the same
     * physical tray - a {@code null} slot means "leave the printer's current/default filament assignment
     * alone", which is signalled to {@link BambuPrinter.CommandPPF} via an empty list (useAms=false).
     */
    private static List<Integer> buildAmsMapping(final Integer amsSlot, final int filamentCount) {
        if (amsSlot == null) {
            return List.of();
        }
        return Collections.nCopies(Math.max(1, filamentCount), amsSlot);
    }

    /**
     * Every mappable file in the library: loose {@code .3mf}s at the root as bare names (exactly what existing
     * mappings already store, so they keep matching), plus files inside project folders as {@code Project/file.3mf}.
     * <p>
     * Sub-paths are safe end to end: {@link #queuePart} resolves them against the library root, and
     * {@code PrintQueueService.startNext} flattens them to a bare filename before the SD-card upload.
     */
    public List<String> listLibraryFiles() {
        final Path root = Path.of(config.batchPrint().library());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        final List<String> loose = new ArrayList<>();
        final List<String> inProjects = new ArrayList<>();
        try (final java.util.stream.Stream<Path> stream = Files.list(root)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> {
                        final String name = entry.getFileName().toString();
                        if (Files.isRegularFile(entry) && name.toLowerCase().endsWith(".3mf")) {
                            loose.add(name);
                        } else if (Files.isDirectory(entry)) {
                            inProjects.addAll(listProject(entry).stream().map(f -> name + "/" + f).toList());
                        }
                    });
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
        final List<String> all = new ArrayList<>(loose);
        all.addAll(inProjects);
        return all;
    }

    /** Project folder names that contain at least one {@code .3mf}. */
    public List<String> listProjects() {
        final Path root = Path.of(config.batchPrint().library());
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (final java.util.stream.Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .filter(d -> !listProject(d).isEmpty())
                    .map(d -> d.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    /** Library-relative paths of every file in one project, in name order. */
    public List<String> listProjectFiles(final String project) {
        final Path dir = Path.of(config.batchPrint().library()).resolve(project);
        return listProject(dir).stream().map(f -> project + "/" + f).toList();
    }

    private static List<String> listProject(final Path dir) {
        try (final java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(".3mf"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Queues a single job for {@code part} on {@code printerName}, optionally overriding the part's mapped AMS
     * slot (auto-queue resolves the slot per printer from live filament telemetry - the tray holding PETG on one
     * printer isn't necessarily the same index on another). Returns an error message, or empty on success.
     */
    public Optional<String> queuePart(final MappingPart part, final String printerName, final Integer amsSlotOverride, final OrderRef orderRef) {
        final Optional<String> resolved = pathFor(part, printerName);
        if (resolved.isEmpty()) {
            return Optional.of("%s has no %s file mapped for %s - add one on the Mappings tab".formatted(printerName,
                    printers.getPrinterDetail(printerName).map(GcodeMappingQueuer::familyOf).orElse("?"), part.path()));
        }
        final String path = resolved.get();
        if (part.source() == GcodeSource.LIBRARY) {
            final Path file = Path.of(config.batchPrint().library()).resolve(path);
            if (!Files.isRegularFile(file)) {
                return Optional.of("Not in library: %s".formatted(path));
            }
        }
        final Integer slot = amsSlotOverride != null ? amsSlotOverride : part.amsSlot();
        final PlateInfo plateInfo = loadPlateInfo(part, path);
        final List<Integer> amsMapping = buildAmsMapping(slot, plateInfo.filamentCount());
        final boolean useAms = !amsMapping.isEmpty() && amsMapping.stream().noneMatch(i -> i == BambuConst.AMS_TRAY_VIRTUAL);
        final BambuPrinter.CommandPPF command = new BambuPrinter.CommandPPF(
                path, part.plateId(), useAms,
                config.batchPrint().timelapse(), config.batchPrint().bedLevelling(),
                config.batchPrint().flowCalibration(), config.batchPrint().vibrationCalibration(), amsMapping);
        // Carry the part itself so the job can be returned to the dispatch pool intact if it's later removed
        // from this printer's queue - the command alone loses the filament requirement.
        queueService.add(printerName, new PrintQueueService.QueueEntry(
                command, plateInfo.weight(), part.source(), orderRef, part));
        return Optional.empty();
    }

    /**
     * Queues {@code orderedQuantity * part.copiesPerUnit()} jobs for every part in {@code parts}, distributing them
     * round-robin across {@code printerNames} (a single printer if that's all the caller selected).
     */
    public QueueResult queue(final List<MappingPart> parts, final int orderedQuantity, final List<String> printerNames) {
        return queue(parts, orderedQuantity, printerNames, null);
    }

    /**
     * Variant that links every queued job to a marketplace order: entries carry {@code orderRef}, the order is
     * marked queued, and the jobs count towards its "X/Y printed" progress (ready-to-ship notification fires
     * when the last one finishes).
     */
    public QueueResult queue(final List<MappingPart> parts, final int orderedQuantity, final List<String> printerNames, final OrderRef orderRef) {
        if (parts.isEmpty()) {
            return new QueueResult(0, List.of("No parts are mapped for this listing yet"));
        }
        if (printerNames.isEmpty()) {
            return new QueueResult(0, List.of("Select at least one printer"));
        }
        final List<String> errors = new ArrayList<>();
        int totalQueued = 0;
        int printerIndex = 0;
        for (final MappingPart part : parts) {
            // Only printers whose family has a file for this part take a share of the round-robin. A selected
            // H2D with no H2D file is skipped for this part (and said so), never handed the P1 file.
            final List<String> eligible = printerNames.stream().filter(n -> pathFor(part, n).isPresent()).toList();
            printerNames.stream().filter(n -> !eligible.contains(n)).forEach(n ->
                    errors.add("%s: no %s file mapped for %s - skipped on that printer".formatted(n,
                            printers.getPrinterDetail(n).map(GcodeMappingQueuer::familyOf).orElse("?"), part.path())));
            if (eligible.isEmpty()) {
                continue;
            }
            if (part.source() == GcodeSource.LIBRARY) {
                final Optional<String> missing = eligible.stream().map(n -> pathFor(part, n).get()).distinct()
                        .filter(p -> !Files.isRegularFile(Path.of(config.batchPrint().library()).resolve(p))).findFirst();
                if (missing.isPresent()) {
                    errors.add("Not in library: %s - skipped".formatted(missing.get()));
                    continue;
                }
            }
            // One command per distinct file (the P1 file and, say, the H2D one), built lazily.
            final java.util.Map<String, BambuPrinter.CommandPPF> commands = new java.util.HashMap<>();
            final java.util.Map<String, Double> weights = new java.util.HashMap<>();
            final int copies = Math.max(1, orderedQuantity) * part.copiesPerUnit();
            for (int i = 0; i < copies; i++) {
                final String printerName = eligible.get(printerIndex % eligible.size());
                printerIndex++;
                final String path = pathFor(part, printerName).get();
                final BambuPrinter.CommandPPF command = commands.computeIfAbsent(path, p -> {
                    final PlateInfo plateInfo = loadPlateInfo(part, p);
                    weights.put(p, plateInfo.weight());
                    final List<Integer> amsMapping = buildAmsMapping(part.amsSlot(), plateInfo.filamentCount());
                    // Mirrors PrinterMapping's rule: only turn on AMS routing when every mapped slot is a real AMS
                    // tray - BambuConst.AMS_TRAY_VIRTUAL (external spool) means "feed from the spool holder".
                    final boolean useAms = !amsMapping.isEmpty() && amsMapping.stream().noneMatch(s -> s == BambuConst.AMS_TRAY_VIRTUAL);
                    return new BambuPrinter.CommandPPF(
                            p, part.plateId(), useAms,
                            config.batchPrint().timelapse(), config.batchPrint().bedLevelling(),
                            config.batchPrint().flowCalibration(), config.batchPrint().vibrationCalibration(), amsMapping);
                });
                queueService.add(printerName, new PrintQueueService.QueueEntry(
                        command, weights.get(path), part.source(), orderRef, part));
                totalQueued++;
            }
        }
        if (orderRef != null && totalQueued > 0) {
            tracking.markQueued(orderRef.market(), orderRef.orderId());
            tracking.addExpectedJobs(orderRef.market(), orderRef.orderId(), totalQueued);
        }
        return new QueueResult(totalQueued, errors);
    }

}
