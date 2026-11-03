package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Periodically polls eBay for open (not-started/in-progress) orders, mirroring {@link EtsyOrderPollingService}.
 */
@ApplicationScoped
public class EbayOrderPollingService {

    static final String MARKET = "ebay";

    @Inject
    EbayApiClient client;
    @Inject
    EbayOAuthService oauth;
    @Inject
    BambuConfig config;
    @Inject
    OrderTrackingService tracking;
    @Inject
    NotificationService notificationService;
    @Inject
    EbayMappingService mappingService;
    @Inject
    AutoQueueService autoQueue;
    @Inject
    StockService stockService;
    @Inject
    PollFailureReporter pollFailures;
    @Inject
    OrderCancellationService cancellations;

    private final AtomicReference<List<EbayApiClient.Order>> lastOrders = new AtomicReference<>(List.of());
    private final AtomicReference<Instant> lastPolled = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean repollScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    @Scheduled(every = "${bambu.ebay.poll-interval:10m}")
    void poll() {
        refresh();
    }

    public synchronized void refresh() {
        if (!oauth.isConnected()) {
            return;
        }
        try {
            final List<EbayApiClient.Order> orders = client.getOpenOrders();
            final java.util.Set<String> open = orders.stream().map(EbayApiClient.Order::orderId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
            // Before the new list replaces the old one: the difference between them is what just closed.
            final java.util.Set<String> undecided = checkCancelled(open);
            lastOrders.set(orders);
            lastPolled.set(Instant.now());
            lastError.set(null);
            Log.infof("EbayOrderPollingService: %d open order(s)", orders.size());
            pollFailures.recordSuccess("eBay");
            // Orders that have left the open list are done with, however their print progress ended up
            // ...except the ones whose reason for closing could not be looked up this time: keep those for the next poll.
            open.addAll(undecided);
            tracking.pruneClosed(MARKET, open);
            // New orders wait until the printers have reported their filament - see AutoQueueService.STARTUP_GRACE.
            // They are not marked seen until then, so a poll shortly after it picks them up.
            final java.time.Duration wait = autoQueue.untilReadyForOrders();
            if (wait.isZero()) {
                notifyNewOrders(orders);
            } else if (repollScheduled.compareAndSet(false, true)) {
                Log.infof("EbayOrderPollingService: new orders wait %ds for the printers to report their filament", wait.toSeconds() + 5);
                java.util.concurrent.CompletableFuture.delayedExecutor(wait.toSeconds() + 5, java.util.concurrent.TimeUnit.SECONDS)
                        .execute(() -> {
                            repollScheduled.set(false);
                            refresh();
                        });
            }
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EbayOrderPollingService: poll failed: %s", ex.getMessage());
            // A failing poll means orders silently stop arriving - notify rather than only logging.
            pollFailures.recordFailure("eBay", ex.getMessage());
        }
    }

    /**
     * Finds out why orders left the open list. Shipped is the usual reason and needs nothing; cancelled (or
     * refunded in full) before shipping means the print work for it has to be unwound.
     * <p>
     * Looks at orders that were open on the previous poll, plus any with unfinished print progress - the second
     * catches a cancellation that happened while the app was down.
     *
     * @return orders whose lookup failed, so the caller can keep their progress and try again next poll
     */
    private java.util.Set<String> checkCancelled(final java.util.Set<String> open) {
        final java.util.Set<String> gone = new java.util.LinkedHashSet<>();
        lastOrders.get().forEach(o -> gone.add(o.orderId()));
        tracking.progress(MARKET).stream().filter(p -> !p.complete()).forEach(p -> gone.add(p.orderId()));
        gone.removeAll(open);
        final java.util.Set<String> undecided = new java.util.HashSet<>();
        for (final String id : gone) {
            if (cancellations.isCancelled(MARKET, id)) {
                continue;
            }
            try {
                final Optional<EbayApiClient.Order> found = client.getOrder(id);
                if (found.isEmpty() || !found.get().cancelled() || found.get().isShipped()) {
                    continue;
                }
                final EbayApiClient.Order o = found.get();
                final List<OrderCancellationService.OrderLine> lines = o.lineItems().stream()
                        .map(li -> new OrderCancellationService.OrderLine(li.quantity(), li.personalization().isPresent(),
                                mappingService.find(li.listingKey(), li.variationAspects())
                                        .map(EbayMappingService.MappingEntry::parts).orElse(List.of())))
                        .toList();
                cancellations.cancel(MARKET, id, "eBay order %s (%s)".formatted(o.orderId(), o.buyerUsername()), lines);
            } catch (Exception ex) {
                Log.warnf("EbayOrderPollingService: could not check why order %s closed: %s", id, ex.getMessage());
                undecided.add(id);
            }
        }
        return undecided;
    }

    /** Fires a "new_order" notification for orders never seen before (tracked persistently, so no repeats after restart). */
    private void notifyNewOrders(final List<EbayApiClient.Order> orders) {
        final List<String> fresh = tracking.recordSeen(MARKET,
                orders.stream().map(EbayApiClient.Order::orderId).toList());
        if (fresh.isEmpty()) {
            return;
        }
        orders.stream()
                .filter(o -> fresh.contains(o.orderId()))
                .forEach(o -> {
                    final String items = o.lineItems().stream()
                            .map(li -> {
                                final String vars = li.variationAspects().stream()
                                        .map(v -> v.propertyName() + ": " + v.value())
                                        .collect(Collectors.joining(", "));
                                return "%dx %s%s".formatted(li.quantity(), li.title(), vars.isBlank() ? "" : " (" + vars + ")");
                            })
                            .collect(Collectors.joining("; "));
                    final String note = o.buyerNote() == null || o.buyerNote().isBlank() ? ""
                            : " - buyer note: " + (o.buyerNote().length() > 200 ? o.buyerNote().substring(0, 200) + "…" : o.buyerNote());
                    notificationService.notifyEvent("new_order", "eBay",
                            "New order %s from %s: %s%s".formatted(o.orderId(), o.buyerUsername(),
                                    items.length() > 200 ? items.substring(0, 200) + "…" : items, note));
                    autoQueueOrder(o);
                });
    }

    /**
     * Auto-queues one order: works out what on-hand stock covers and queues the rest. Called for a newly seen
     * order, and from the Orders page's "Try auto-queue again" for one that was skipped (2026-10-22: a restart
     * checked three orders before any printer had reported its AMS, and nothing ever looked at them again).
     *
     * @return true when the order was accepted (queued, or fully covered from stock)
     */
    private boolean autoQueueOrder(final EbayApiClient.Order o) {
        // Work out what on-hand stock would cover and auto-queue only the remainder. The stock is NOT
        // consumed yet: processOrder can decline the order for five different reasons, and consuming
        // up front deducted the units anyway - so they were spent on something never printed.
        final String orderLabel = "eBay order %s (%s)".formatted(o.orderId(), o.buyerUsername());
        final java.util.List<AutoQueueService.AutoQueueItem> queueItems = new java.util.ArrayList<>();
        final java.util.List<StockService.PlannedCoverage> planned = new java.util.ArrayList<>();
        // Pieces already promised to an earlier line of this same order.
        final java.util.Map<String, Integer> reserved = new java.util.HashMap<>();
        for (final EbayApiClient.LineItem li : o.lineItems()) {
            final java.util.List<MappingPart> parts = mappingService.find(li.listingKey(), li.variationAspects())
                    .map(EbayMappingService.MappingEntry::parts)
                    .orElse(java.util.List.of());
            // Stock is counted per printed part, so cover is decided part by part: a set of two with
            // one piece on the shelf prints one. A personalized line is never covered from stock -
            // what is on the shelf is the generic part.
            final java.util.List<Integer> fromStock = li.personalization().isPresent()
                    ? java.util.List.of()
                    : stockService.planCoverage(parts, li.quantity(), li.title(), reserved, planned);
            if (StockService.fullyCovered(parts, li.quantity(), fromStock)) {
                continue; // whole line covered from stock - nothing to print
            }
            queueItems.add(new AutoQueueService.AutoQueueItem(
                    li.listingKey(),
                    "%dx %s".formatted(li.quantity(), li.title()),
                    li.quantity(),
                    li.personalization().isPresent(),
                    parts,
                    fromStock.isEmpty() ? null : fromStock));
        }
        // Nothing left to print means stock covered the whole order - there's no queueing step to
        // accept it, so accept it here. Previously this fell through entirely: the order was never
        // marked queued and sat as "not queued" forever despite being fulfillable off the shelf.
        final boolean accepted = queueItems.isEmpty()
                ? !planned.isEmpty() && autoQueue.isEnabled()
                : autoQueue.processOrder(MARKET, o.orderId(), orderLabel, queueItems);
        if (accepted) {
            planned.forEach(p -> stockService.commitCoverage(MARKET, p, orderLabel));
            if (queueItems.isEmpty()) {
                tracking.markQueued(MARKET, o.orderId());
                Log.infof("EbayOrderPollingService: %s fully fulfilled from on-hand stock - nothing to print",
                        orderLabel);
            }
        }
        return accepted;
    }

    /**
     * Runs auto-queue again for an open order that it skipped. Nothing happens for an order that is queued or
     * dismissed already - {@link AutoQueueService#processOrder} refuses those itself.
     *
     * @return empty when it was accepted, else why not (the skip reason is also sent as auto_queue_skipped)
     */
    public synchronized java.util.Optional<String> retryAutoQueue(final String orderId) {
        final java.util.Optional<EbayApiClient.Order> order = lastOrders.get().stream().filter(x -> orderId.equals(x.orderId())).findFirst();
        if (order.isEmpty()) {
            return java.util.Optional.of("order %s is not in the open orders any more".formatted(orderId));
        }
        if (tracking.queuedAt(MARKET, orderId).isPresent()) {
            return java.util.Optional.of("order %s is queued already".formatted(orderId));
        }
        if (!autoQueue.isEnabled()) {
            return java.util.Optional.of("auto-queue is switched off");
        }
        Log.infof("EbayOrderPollingService: retrying auto-queue for order %s", orderId);
        return autoQueueOrder(order.get()) ? java.util.Optional.empty()
                : java.util.Optional.of("still not auto-queued - see the auto_queue_skipped alert for why");
    }

    public List<EbayApiClient.Order> getOrders() {
        return lastOrders.get().stream()
                .filter(o -> !tracking.isDismissed(MARKET, o.orderId()))
                .toList();
    }

    public Optional<Instant> getLastPolled() {
        return Optional.ofNullable(lastPolled.get());
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError.get());
    }

    public void dismiss(final String orderId) {
        tracking.dismiss(MARKET, orderId);
    }

    public void undismiss(final String orderId) {
        tracking.undismiss(MARKET, orderId);
    }

}
