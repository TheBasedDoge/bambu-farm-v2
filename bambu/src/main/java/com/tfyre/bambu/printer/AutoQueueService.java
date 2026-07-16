package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Auto-queue: when order polling finds a NEW order whose line items are all mapped, queue the print jobs
 * automatically - picking, per part, a printer that actually has the required filament loaded right now.
 * <p>
 * Filament matching (per part, against {@link BambuPrinter#getAmsTrayTypes()} live telemetry):
 * <ul>
 * <li>{@code filamentType} + {@code amsSlot} both mapped → that exact tray must currently hold that type
 * (catches a swapped spool);</li>
 * <li>{@code filamentType} only → any tray with that type qualifies, and the job is pinned to it (the PETG
 * tray on one printer isn't necessarily the same index on another);</li>
 * <li>{@code amsSlot} only or neither → no material check, behaves like manual queueing.</li>
 * </ul>
 * Printer choice among qualifying printers: ready with an empty queue first, then shortest queue (auto-start
 * or a manual Start Next drains it). <b>All-or-nothing per order</b>: any unmapped line item, missing library
 * file, or part with no qualifying printer skips the whole order with an {@code auto_queue_skipped}
 * notification explaining why - no partial kits. Successfully queued orders are marked in
 * {@link OrderTrackingService} (the "✓ queued" badge), which also guarantees an order is never auto-queued twice.
 * <p>
 * Opt-in via a single persisted toggle ({@code bambu-auto-queue.json}), shown on both Sales Orders pages.
 */
@ApplicationScoped
public class AutoQueueService {

    private static final String STORE_FILENAME = "bambu-auto-queue.json";
    /** Per-printer opt-in keys live in the same settings map under this prefix (default on, so existing farms keep auto-queueing to every printer). */
    private static final String PRINTER_PREFIX = "printer:";

    /**
     * One order line item as the marketplace-agnostic input: listing key (Etsy listing id / eBay SKU or item
     * id, used for the hidden-listing check), label for messages, ordered qty, whether the buyer supplied
     * personalization text (custom items must never be auto-printed from the generic mapping), and the mapped
     * parts (empty = unmapped).
     */
    public record AutoQueueItem(String listingKey, String label, int quantity, boolean personalized, List<MappingPart> parts) {
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    OrderTrackingService tracking;
    @Inject
    NotificationService notificationService;

    private final Map<String, Boolean> settings = new ConcurrentHashMap<>();

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
            settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Boolean>>() {
            }));
            Log.infof("AutoQueueService: settings loaded from %s (enabled=%s)", path, isEnabled());
        } catch (IOException ex) {
            Log.errorf(ex, "AutoQueueService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "AutoQueueService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    public boolean isEnabled() {
        return settings.getOrDefault("enabled", Boolean.FALSE);
    }

    public void setEnabled(final boolean enabled) {
        settings.put("enabled", enabled);
        save();
        Log.infof("AutoQueueService: auto-queue %s", enabled ? "enabled" : "disabled");
    }

    /** Whether failed queue-started prints are automatically requeued once (see PrintQueueService.onJobEnded). */
    public boolean isAutoRequeueEnabled() {
        return settings.getOrDefault("auto-requeue", Boolean.FALSE);
    }

    public void setAutoRequeueEnabled(final boolean enabled) {
        settings.put("auto-requeue", enabled);
        save();
        Log.infof("AutoQueueService: auto-requeue %s", enabled ? "enabled" : "disabled");
    }

    /**
     * Whether a specific printer is allowed to receive auto-queued jobs. Defaults to {@code true}, so turning on
     * the global auto-queue toggle keeps sending to every printer as before; uncheck a printer here (on the Print
     * Queue page) to exclude it - e.g. run lights-out only on the P1S units and leave the H2D for manual jobs.
     */
    public boolean isPrinterEnabled(final String printerName) {
        return settings.getOrDefault(PRINTER_PREFIX + printerName, Boolean.TRUE);
    }

    public void setPrinterEnabled(final String printerName, final boolean enabled) {
        settings.put(PRINTER_PREFIX + printerName, enabled);
        save();
        Log.infof("AutoQueueService: auto-queue to %s %s", printerName, enabled ? "enabled" : "disabled");
    }

    /**
     * Attempts to auto-queue one newly-seen order. Called by the polling services for orders whose IDs were
     * never seen before (so restarts and re-polls can't double-queue; the queued-marker is a second guard).
     */
    public synchronized void processOrder(final String market, final String orderId, final String orderLabel,
            final List<AutoQueueItem> items) {
        if (!isEnabled()) {
            return;
        }
        if (tracking.queuedAt(market, orderId).isPresent() || tracking.isDismissed(market, orderId)) {
            return;
        }

        // Hidden + unmapped listings are products that are never printed (digital items, add-ons) - drop them
        // from consideration entirely. A hidden listing the user DID map is still printed (the mapping wins).
        final List<AutoQueueItem> relevant = items.stream()
                .filter(i -> !(i.parts().isEmpty() && tracking.isListingHidden(market, i.listingKey())))
                .toList();
        if (relevant.isEmpty()) {
            Log.infof("AutoQueueService: %s: all line items are hidden non-printed listings - nothing to queue", orderLabel);
            return;
        }

        // ---- Pre-validate everything before queueing anything (all-or-nothing) ----
        final List<String> problems = new ArrayList<>();
        for (final AutoQueueItem item : relevant) {
            if (item.personalized()) {
                // A buyer-personalized item must never be auto-printed from the generic mapping
                problems.add("'%s' has buyer personalization - needs manual review".formatted(item.label()));
                continue;
            }
            if (item.parts().isEmpty()) {
                problems.add("'%s' is not mapped to a print job yet".formatted(item.label()));
                continue;
            }
            for (final MappingPart part : item.parts()) {
                if (part.source() == GcodeSource.LIBRARY
                        && !Files.isRegularFile(Path.of(config.batchPrint().library()).resolve(part.path()))) {
                    problems.add("'%s': %s is not in the library".formatted(item.label(), part.path()));
                    continue;
                }
                if (eligiblePrinters(part).isEmpty()) {
                    problems.add(part.filamentType() != null
                            ? "'%s': no printer currently has %s loaded%s".formatted(item.label(), part.filamentType(),
                                    part.amsSlot() != null ? " in the mapped AMS slot" : "")
                            : "'%s': no printers configured".formatted(item.label()));
                }
            }
        }
        if (!problems.isEmpty()) {
            final String reason = String.join("; ", problems);
            Log.infof("AutoQueueService: %s not auto-queued: %s", orderLabel, reason);
            notificationService.notifyEvent("auto_queue_skipped", market,
                    "%s NOT auto-queued: %s - queue it manually from the Sales Orders page".formatted(orderLabel, truncate(reason, 300)));
            return;
        }

        // ---- Queue: per copy, pick the best qualifying printer (ready+idle first, then shortest queue) ----
        final OrderRef orderRef = new OrderRef(market, orderId, orderLabel);
        final Map<String, Integer> assignedThisRun = new HashMap<>();
        final Map<String, Integer> perPrinter = new LinkedHashMap<>();
        int total = 0;
        for (final AutoQueueItem item : relevant) {
            for (final MappingPart part : item.parts()) {
                final int copies = Math.max(1, item.quantity()) * part.copiesPerUnit();
                for (int i = 0; i < copies; i++) {
                    final List<Candidate> eligible = eligiblePrinters(part);
                    final Candidate best = eligible.stream()
                            .min(Comparator
                                    .comparingInt((Candidate c) -> pendingCount(c, assignedThisRun) == 0 && c.ready() ? 0 : 1)
                                    .thenComparingInt(c -> pendingCount(c, assignedThisRun))
                                    .thenComparing(c -> c.detail().name(), String.CASE_INSENSITIVE_ORDER))
                            .orElse(null);
                    if (best == null) {
                        // Shouldn't happen after pre-validation; bail without marking queued
                        notificationService.notifyEvent("auto_queue_skipped", market,
                                "%s: auto-queue aborted mid-way - no eligible printer for %s".formatted(orderLabel, part.path()));
                        return;
                    }
                    final Optional<String> error = queuer.queuePart(part, best.detail().name(), best.resolvedSlot(), orderRef);
                    if (error.isPresent()) {
                        notificationService.notifyEvent("auto_queue_skipped", market,
                                "%s: auto-queue aborted - %s".formatted(orderLabel, error.get()));
                        return;
                    }
                    assignedThisRun.merge(best.detail().name(), 1, Integer::sum);
                    perPrinter.merge(best.detail().name(), 1, Integer::sum);
                    total++;
                }
            }
        }

        tracking.markQueued(market, orderId);
        tracking.addExpectedJobs(market, orderId, total);
        final String distribution = perPrinter.entrySet().stream()
                .map(e -> "%s×%d".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
        Log.infof("AutoQueueService: %s auto-queued: %d job(s) → %s", orderLabel, total, distribution);
        notificationService.notifyEvent("auto_queue", market,
                "%s auto-queued: %d job(s) → %s".formatted(orderLabel, total, distribution));
    }

    /** One line of a dry-run result: what would happen to a part if an order arrived right now. */
    public record DryRunLine(String part, boolean ok, String outcome) {
    }

    /**
     * Simulates auto-queueing {@code quantity} units of a mapping WITHOUT queueing anything - shows exactly
     * which printers qualify per part (and the tray each would use) or why none do. Used by the Mappings
     * tab's Test button so a mapping can be verified before a real order arrives.
     */
    public List<DryRunLine> dryRun(final List<MappingPart> parts, final int quantity) {
        final List<DryRunLine> lines = new ArrayList<>();
        if (parts.isEmpty()) {
            lines.add(new DryRunLine("(no parts)", false, "Listing is not mapped yet"));
            return lines;
        }
        final Map<String, Integer> assigned = new HashMap<>();
        for (final MappingPart part : parts) {
            final String label = "%s plate %d%s".formatted(part.path(), part.plateId(),
                    part.filamentType() != null ? " (" + part.filamentType() + ")" : "");
            if (part.source() == GcodeSource.LIBRARY
                    && !Files.isRegularFile(Path.of(config.batchPrint().library()).resolve(part.path()))) {
                lines.add(new DryRunLine(label, false, "File is not in the library"));
                continue;
            }
            final List<Candidate> eligible = eligiblePrinters(part);
            if (eligible.isEmpty()) {
                lines.add(new DryRunLine(label, false, part.filamentType() != null
                        ? "No printer currently has %s loaded%s".formatted(part.filamentType(),
                                part.amsSlot() != null ? " in the mapped AMS slot" : "")
                        : "No printers configured"));
                continue;
            }
            // Simulate the per-copy assignment with current queue depths
            final Map<String, Integer> distribution = new LinkedHashMap<>();
            final Map<String, Integer> slotUsed = new LinkedHashMap<>();
            final int copies = Math.max(1, quantity) * part.copiesPerUnit();
            for (int i = 0; i < copies; i++) {
                final Candidate best = eligible.stream()
                        .min(Comparator
                                .comparingInt((Candidate c) -> pendingCount(c, assigned) == 0 && c.ready() ? 0 : 1)
                                .thenComparingInt(c -> pendingCount(c, assigned))
                                .thenComparing(c -> c.detail().name(), String.CASE_INSENSITIVE_ORDER))
                        .orElseThrow();
                assigned.merge(best.detail().name(), 1, Integer::sum);
                distribution.merge(best.detail().name(), 1, Integer::sum);
                if (best.resolvedSlot() != null) {
                    slotUsed.putIfAbsent(best.detail().name(), best.resolvedSlot());
                }
            }
            final String dist = distribution.entrySet().stream()
                    .map(e -> "%s×%d%s".formatted(e.getKey(), e.getValue(),
                            slotUsed.containsKey(e.getKey()) ? " (tray %d)".formatted(slotUsed.get(e.getKey()) + 1) : ""))
                    .collect(Collectors.joining(", "));
            lines.add(new DryRunLine(label, true, "%d cop%s → %s".formatted(copies, copies == 1 ? "y" : "ies", dist)));
        }
        return lines;
    }

    /** A printer that qualifies for a part, with the AMS slot the job should be pinned to (null = printer default). */
    private record Candidate(BambuPrinters.PrinterDetail detail, boolean ready, Integer resolvedSlot) {
    }

    private int pendingCount(final Candidate c, final Map<String, Integer> assignedThisRun) {
        return queueService.size(c.detail().name()) + assignedThisRun.getOrDefault(c.detail().name(), 0);
    }

    private List<Candidate> eligiblePrinters(final MappingPart part) {
        return printers.getPrintersDetail().stream()
                .filter(detail -> isPrinterEnabled(detail.name()))
                .map(detail -> resolveSlot(detail, part))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Decides whether a printer qualifies for a part and which AMS slot to pin the job to.
     * Empty = printer doesn't qualify (mapped filament type isn't loaded where it needs to be).
     */
    private Optional<Candidate> resolveSlot(final BambuPrinters.PrinterDetail detail, final MappingPart part) {
        final BambuPrinter printer = detail.printer();
        final boolean ready = printer.getGCodeState().isReady() && !printer.isBlocked();
        if (part.filamentType() == null) {
            // No material requirement - any printer, mapped slot (or default) as-is
            return Optional.of(new Candidate(detail, ready, part.amsSlot()));
        }
        final String want = part.filamentType().strip().toUpperCase();
        final Map<Integer, String> trays = printer.getAmsTrayTypes();
        if (part.amsSlot() != null) {
            // Strict: the mapped tray must currently hold the wanted material
            return want.equals(trays.get(part.amsSlot()))
                    ? Optional.of(new Candidate(detail, ready, part.amsSlot()))
                    : Optional.empty();
        }
        // Any tray with the wanted material qualifies - lowest real AMS tray first, external spool last
        return trays.entrySet().stream()
                .filter(e -> want.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingInt(slot -> slot == BambuConst.AMS_TRAY_VIRTUAL ? Integer.MAX_VALUE : slot))
                .findFirst()
                .map(slot -> new Candidate(detail, ready, slot));
    }

    private static String truncate(final String s, final int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

}
