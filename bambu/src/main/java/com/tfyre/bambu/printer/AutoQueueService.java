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

    /**
     * One order line item as the marketplace-agnostic input: listing key (Etsy listing id / eBay SKU or item
     * id, used for the hidden-listing check), label for messages, ordered qty, mapped parts (empty = unmapped).
     */
    public record AutoQueueItem(String listingKey, String label, int quantity, List<MappingPart> parts) {
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
                    final Optional<String> error = queuer.queuePart(part, best.detail().name(), best.resolvedSlot());
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
        final String distribution = perPrinter.entrySet().stream()
                .map(e -> "%s×%d".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
        Log.infof("AutoQueueService: %s auto-queued: %d job(s) → %s", orderLabel, total, distribution);
        notificationService.notifyEvent("auto_queue", market,
                "%s auto-queued: %d job(s) → %s".formatted(orderLabel, total, distribution));
    }

    /** A printer that qualifies for a part, with the AMS slot the job should be pinned to (null = printer default). */
    private record Candidate(BambuPrinters.PrinterDetail detail, boolean ready, Integer resolvedSlot) {
    }

    private int pendingCount(final Candidate c, final Map<String, Integer> assignedThisRun) {
        return queueService.size(c.detail().name()) + assignedThisRun.getOrDefault(c.detail().name(), 0);
    }

    private List<Candidate> eligiblePrinters(final MappingPart part) {
        return printers.getPrintersDetail().stream()
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
