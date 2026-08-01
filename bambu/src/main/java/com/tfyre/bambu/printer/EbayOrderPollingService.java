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

    private final AtomicReference<List<EbayApiClient.Order>> lastOrders = new AtomicReference<>(List.of());
    private final AtomicReference<Instant> lastPolled = new AtomicReference<>();
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
            lastOrders.set(orders);
            lastPolled.set(Instant.now());
            lastError.set(null);
            Log.infof("EbayOrderPollingService: %d open order(s)", orders.size());
            pollFailures.recordSuccess("eBay");
            // Orders that have left the open list are done with, however their print progress ended up
            tracking.pruneClosed(MARKET, orders.stream().map(EbayApiClient.Order::orderId).collect(java.util.stream.Collectors.toSet()));
            notifyNewOrders(orders);
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EbayOrderPollingService: poll failed: %s", ex.getMessage());
            // A failing poll means orders silently stop arriving - notify rather than only logging.
            pollFailures.recordFailure("eBay", ex.getMessage());
        }
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
                    notificationService.notifyEvent("new_order", "eBay",
                            "New order %s from %s: %s".formatted(o.orderId(), o.buyerUsername(),
                                    items.length() > 200 ? items.substring(0, 200) + "…" : items));
                    // Work out what on-hand stock would cover and auto-queue only the remainder. The stock is NOT
                    // consumed yet: processOrder can decline the order for five different reasons, and consuming
                    // up front deducted the units anyway - so they were spent on something never printed.
                    final String orderLabel = "eBay order %s (%s)".formatted(o.orderId(), o.buyerUsername());
                    final java.util.List<AutoQueueService.AutoQueueItem> queueItems = new java.util.ArrayList<>();
                    final java.util.List<StockService.PlannedCoverage> planned = new java.util.ArrayList<>();
                    for (final EbayApiClient.LineItem li : o.lineItems()) {
                        final java.util.Optional<String> key = mappingService.findKey(li.listingKey(), li.variationAspects());
                        final int covered = stockService.coverageFor(MARKET, key, li.quantity());
                        if (covered > 0) {
                            planned.add(new StockService.PlannedCoverage(key.get(), covered, li.title()));
                        }
                        final int toPrint = li.quantity() - covered;
                        if (key.isPresent() && toPrint <= 0) {
                            continue; // whole line covered from stock - nothing to print
                        }
                        queueItems.add(new AutoQueueService.AutoQueueItem(
                                li.listingKey(),
                                "%dx %s".formatted(toPrint, li.title()),
                                toPrint,
                                li.personalization().isPresent(),
                                mappingService.find(li.listingKey(), li.variationAspects())
                                        .map(EbayMappingService.MappingEntry::parts)
                                        .orElse(java.util.List.of())));
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
                });
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
