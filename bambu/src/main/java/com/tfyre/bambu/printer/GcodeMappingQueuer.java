package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.view.batchprint.Plate;
import com.tfyre.bambu.view.batchprint.PlateFilament;
import com.tfyre.bambu.view.batchprint.ProjectFile;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    Instance<ProjectFile> projectFileInstance;

    /** Weight + filament-slot-count of a LIBRARY part's plate, read once and reused for both queueing decisions. */
    private record PlateInfo(double weight, int filamentCount) {
    }

    private static final PlateInfo UNKNOWN_PLATE = new PlateInfo(0.0, 1);

    private PlateInfo loadPlateInfo(final MappingPart part) {
        if (part.source() != GcodeSource.LIBRARY) {
            // No local project file to read for SD-card-resident files - weight stays untracked and the AMS
            // mapping (if any) falls back to a single-entry list, since we can't inspect the plate's filament count.
            return UNKNOWN_PLATE;
        }
        final Path file = Path.of(config.batchPrint().library()).resolve(part.path());
        if (!Files.isRegularFile(file)) {
            return UNKNOWN_PLATE;
        }
        final ProjectFile projectFile = projectFileInstance.get();
        try {
            projectFile.setup(part.path(), file.toFile());
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
     * physical tray - {@code amsSlot() == null} means "leave the printer's current/default filament assignment
     * alone", which is signalled to {@link BambuPrinter.CommandPPF} via an empty list (useAms=false).
     */
    private static List<Integer> buildAmsMapping(final MappingPart part, final int filamentCount) {
        if (part.amsSlot() == null) {
            return List.of();
        }
        return Collections.nCopies(Math.max(1, filamentCount), part.amsSlot());
    }

    /**
     * Queues {@code orderedQuantity * part.copiesPerUnit()} jobs for every part in {@code parts}, distributing them
     * round-robin across {@code printerNames} (a single printer if that's all the caller selected).
     */
    public QueueResult queue(final List<MappingPart> parts, final int orderedQuantity, final List<String> printerNames) {
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
            if (part.source() == GcodeSource.LIBRARY) {
                final Path file = Path.of(config.batchPrint().library()).resolve(part.path());
                if (!Files.isRegularFile(file)) {
                    errors.add("Not in library: %s - skipped".formatted(part.path()));
                    continue;
                }
            }
            final PlateInfo plateInfo = loadPlateInfo(part);
            final List<Integer> amsMapping = buildAmsMapping(part, plateInfo.filamentCount());
            // Mirrors PrinterMapping's rule: only turn on AMS routing when every mapped slot is a real AMS tray -
            // BambuConst.AMS_TRAY_VIRTUAL (external spool) means "feed from the spool holder", not the AMS unit.
            final boolean useAms = !amsMapping.isEmpty() && amsMapping.stream().noneMatch(i -> i == BambuConst.AMS_TRAY_VIRTUAL);
            final BambuPrinter.CommandPPF command = new BambuPrinter.CommandPPF(
                    part.path(), part.plateId(), useAms,
                    config.batchPrint().timelapse(), config.batchPrint().bedLevelling(),
                    config.batchPrint().flowCalibration(), config.batchPrint().vibrationCalibration(), amsMapping);
            final int copies = Math.max(1, orderedQuantity) * part.copiesPerUnit();
            for (int i = 0; i < copies; i++) {
                final String printerName = printerNames.get(printerIndex % printerNames.size());
                printerIndex++;
                queueService.add(printerName, new PrintQueueService.QueueEntry(command, plateInfo.weight(), part.source()));
                totalQueued++;
            }
        }
        return new QueueResult(totalQueued, errors);
    }

}
