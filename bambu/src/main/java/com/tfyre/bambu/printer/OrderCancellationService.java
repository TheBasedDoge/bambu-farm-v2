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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens to the print work of an order the buyer (or the marketplace) cancelled before it shipped.
 * <ul>
 * <li>Jobs still waiting - in the dispatch pool or on a printer's queue - are removed. Nothing prints for a sale
 * that no longer exists.</li>
 * <li>A job already printing is left to finish, and the finished piece goes into on-hand stock. A failure is
 * not retried.</li>
 * <li>Pieces the order had taken off the shelf go back on it.</li>
 * <li>Pieces already printed for it go into stock too - but only when the numbers add up exactly; otherwise the
 * alert says to count them by hand.</li>
 * </ul>
 * This is the one place a finished print is added to stock automatically. It is safe here, where it is not in
 * general, because the decision does not rest on a live link between print and order: the cancelled order's
 * parts are written to disk at the moment of cancellation, and a finishing print is matched to them by its
 * order reference (which print history persists) and its file name. A print that cannot be matched adds nothing
 * and raises an alert instead.
 */
@ApplicationScoped
public class OrderCancellationService {

    private static final String STORE_FILENAME = "bambu-cancelled-orders.json";
    /** Long enough to outlive any print that was running at the time; after that the entry is only history. */
    private static final int KEEP_DAYS = 60;

    /** One stock part of a cancelled order, and the print-file names (no folders) that produce it. */
    public record PartRef(String partKey, List<String> files) {
    }

    public record Cancelled(String label, String at, List<PartRef> parts) {
    }

    /** One line of the order as the marketplace reports it, resolved to its mapped parts (empty when unmapped). */
    public record OrderLine(int quantity, boolean personalized, List<MappingPart> parts) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    PrintQueueService printQueue;
    @Inject
    PrintHistoryService history;
    @Inject
    StockService stock;
    @Inject
    OrderTrackingService tracking;
    @Inject
    NotificationService notificationService;

    /** "market|orderId" → what was cancelled. Concurrent: read from the print-history thread without a lock. */
    private final Map<String, Cancelled> orders = new ConcurrentHashMap<>();
    private final Object cancelLock = new Object();
    private final Object saveLock = new Object();

    private static String key(final String market, final String orderId) {
        return market + "|" + orderId;
    }

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
            final Map<String, Cancelled> loaded = mapper.readValue(path.toFile(), new TypeReference<Map<String, Cancelled>>() {
            });
            final OffsetDateTime cutoff = OffsetDateTime.now().minusDays(KEEP_DAYS);
            loaded.forEach((k, v) -> {
                if (v != null && !isOlderThan(v.at(), cutoff)) {
                    orders.put(k, v);
                }
            });
            Log.infof("OrderCancellationService: %d cancelled order(s) on record", orders.size());
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "OrderCancellationService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private static boolean isOlderThan(final String at, final OffsetDateTime cutoff) {
        try {
            return OffsetDateTime.parse(at).isBefore(cutoff);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void save() {
        synchronized (saveLock) {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), new LinkedHashMap<>(orders));
            } catch (IOException ex) {
                Log.errorf(ex, "OrderCancellationService: cannot save %s: %s", getPath(), ex.getMessage());
            }
        }
    }

    public boolean isCancelled(final String market, final String orderId) {
        return orders.containsKey(key(market, orderId));
    }

    private static String baseName(final String path) {
        if (path == null) {
            return "";
        }
        final String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** The one part this file prints, or empty when none - or more than one - of the order's parts uses it. */
    private static Optional<String> partFor(final List<PartRef> parts, final String file) {
        final String name = baseName(file);
        if (name.isBlank() || parts == null) {
            return Optional.empty();
        }
        final List<String> hits = parts.stream()
                .filter(p -> p.files().stream().anyMatch(f -> f.equalsIgnoreCase(name)))
                .map(PartRef::partKey)
                .distinct()
                .toList();
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    private static String pieces(final int n) {
        return n == 1 ? "1 piece" : n + " pieces";
    }

    /**
     * Unwinds one cancelled, unshipped order. Safe to call again for the same order: only the first call acts.
     *
     * @param label the order label exactly as it was built when the order was queued, e.g.
     *              {@code "Etsy order #123 (Jane)"} - it is how the stock the order took is found in the ledger
     * @param lines the order's lines; personalized lines are never returned to stock (the shelf holds generic parts)
     */
    public void cancel(final String market, final String orderId, final String label, final List<OrderLine> lines) {
        synchronized (cancelLock) {
            final String id = key(market, orderId);
            if (orders.containsKey(id)) {
                return;
            }
            // What the order was made of, in stock terms.
            final Map<String, Integer> total = new LinkedHashMap<>();
            final Map<String, List<String>> files = new LinkedHashMap<>();
            for (final OrderLine line : lines) {
                if (line.personalized()) {
                    continue;
                }
                for (final MappingPart part : line.parts()) {
                    final String partKey = StockService.partKey(part);
                    total.merge(partKey, Math.max(1, line.quantity()) * part.copiesPerUnit(), Integer::sum);
                    final List<String> names = files.computeIfAbsent(partKey, k -> new ArrayList<>());
                    names.add(baseName(part.path()));
                    // The H2D prints the same part from its own slice, under its own file name.
                    part.variants().values().forEach(v -> names.add(baseName(v)));
                }
            }
            final List<PartRef> refs = new ArrayList<>();
            files.forEach((k, v) -> refs.add(new PartRef(k, v.stream().filter(f -> !f.isBlank()).distinct().toList())));

            // On record BEFORE anything is removed: a print finishing during the next few lines must already
            // see the order as cancelled, or it would be counted as progress and could announce "ready to ship".
            orders.put(id, new Cancelled(label, OffsetDateTime.now().toString(), refs));
            save();

            // 1. Work that has not started.
            final Map<String, Integer> removed = new LinkedHashMap<>();
            int removedJobs = 0;
            for (final DispatchQueueService.PendingJob job : dispatch.getPool()) {
                if (job.orderRef() != null && market.equals(job.orderRef().market()) && orderId.equals(job.orderRef().orderId())) {
                    dispatch.remove(job.id());
                    removedJobs++;
                    if (job.part() != null) {
                        removed.merge(StockService.partKey(job.part()), 1, Integer::sum);
                    }
                }
            }
            for (final PrintQueueService.QueueEntry entry : printQueue.removeOrderEntries(market, orderId)) {
                removedJobs++;
                if (entry.part() != null) {
                    removed.merge(StockService.partKey(entry.part()), 1, Integer::sum);
                }
            }

            // 2. Work under way: left alone, picked up by onJobEnded.
            final List<String> inFlight = history.inFlightFiles(market, orderId);
            final Map<String, Integer> running = new LinkedHashMap<>();
            inFlight.forEach(f -> partFor(refs, f).ifPresent(k -> running.merge(k, 1, Integer::sum)));

            // 3. Pieces the order took off the shelf.
            final Map<String, Integer> taken = stock.takenFor(label);
            int returnedPieces = 0;
            for (final Map.Entry<String, Integer> e : taken.entrySet()) {
                stock.adjust(e.getKey(), e.getValue(), "%s cancelled - returned %d".formatted(label, e.getValue()));
                returnedPieces += e.getValue();
            }

            // 4. Pieces already printed. What is left of each part after the three above is what was printed -
            //    but only believe that when it agrees with the count of prints that actually finished for the
            //    order. A changed mapping, a failed job nobody re-queued or a part queued by hand all break the
            //    arithmetic, and a wrong number on the shelf is worse than asking for a count.
            final int printedJobs = tracking.progress(market).stream()
                    .filter(p -> orderId.equals(p.orderId()))
                    .mapToInt(OrderTrackingService.ProgressView::printed)
                    .findFirst().orElse(0);
            final Map<String, Integer> printed = new LinkedHashMap<>();
            boolean exact = true;
            int printedPieces = 0;
            for (final Map.Entry<String, Integer> e : total.entrySet()) {
                final int left = e.getValue() - removed.getOrDefault(e.getKey(), 0) - running.getOrDefault(e.getKey(), 0)
                        - taken.getOrDefault(e.getKey(), 0);
                if (left < 0) {
                    exact = false;
                } else if (left > 0) {
                    printed.put(e.getKey(), left);
                    printedPieces += left;
                }
            }
            exact = exact && printedPieces == printedJobs;
            if (exact) {
                printed.forEach((k, n) -> stock.adjust(k, n, "%s cancelled - %d already printed".formatted(label, n)));
            }
            tracking.dropProgress(market, orderId);

            final List<String> what = new ArrayList<>();
            if (removedJobs > 0) {
                what.add("%d queued job%s removed".formatted(removedJobs, removedJobs == 1 ? "" : "s"));
            }
            if (!inFlight.isEmpty()) {
                what.add("%d still printing - will finish and go into stock".formatted(inFlight.size()));
            }
            if (returnedPieces > 0) {
                what.add("%s put back on the shelf".formatted(pieces(returnedPieces)));
            }
            if (exact && printedPieces > 0) {
                what.add("%s already printed added to stock".formatted(pieces(printedPieces)));
            } else if (!exact && printedJobs > 0) {
                what.add("%d print%s had already finished - add those to Inventory by hand".formatted(
                        printedJobs, printedJobs == 1 ? "" : "s"));
            }
            final String summary = what.isEmpty() ? "nothing was queued or printed for it" : String.join("; ", what);
            Log.infof("OrderCancellationService: %s was cancelled: %s", label, summary);
            notificationService.notifyEvent("order_cancelled", "etsy".equals(market) ? "Etsy" : "ebay".equals(market) ? "eBay" : market,
                    "%s was cancelled: %s".formatted(label, summary));
        }
    }

    /**
     * A print for a cancelled order has ended. Called from the print-history thread, so it takes no lock that
     * {@link #cancel} holds.
     */
    public void onJobEnded(final PrintHistoryService.PrintJob job) {
        final OrderRef ref = job.orderRef();
        if (ref == null) {
            return;
        }
        final Cancelled cancelled = orders.get(key(ref.market(), ref.orderId()));
        if (cancelled == null) {
            return;
        }
        if (!"Finished".equals(job.result())) {
            Log.infof("OrderCancellationService: %s: %s ended %s - it was for the cancelled %s, so it is not retried",
                    job.printer(), job.file(), job.result(), cancelled.label());
            return;
        }
        final Optional<String> partKey = partFor(cancelled.parts(), job.file());
        if (partKey.isPresent()) {
            final int onHand = stock.adjust(partKey.get(), 1,
                    "%s cancelled - print finished on %s".formatted(cancelled.label(), job.printer()));
            Log.infof("OrderCancellationService: %s: %s finished for the cancelled %s - added to stock (%d on hand)",
                    job.printer(), job.file(), cancelled.label(), onHand);
            notificationService.notifyEvent("order_cancelled", job.printer(),
                    "%s finished on %s. It was for %s, which was cancelled, so the piece was added to on-hand stock (%d on hand)."
                            .formatted(StockService.describe(partKey.get()).name(), job.printer(), cancelled.label(), onHand));
        } else {
            Log.warnf("OrderCancellationService: %s: %s finished for the cancelled %s but matches no part of it - not added to stock",
                    job.printer(), job.file(), cancelled.label());
            notificationService.notifyEvent("order_cancelled", job.printer(),
                    "%s finished on %s. It was for %s, which was cancelled, but the app could not tell which stock part it is - add it on the Inventory page by hand."
                            .formatted(job.file(), job.printer(), cancelled.label()));
        }
    }
}
