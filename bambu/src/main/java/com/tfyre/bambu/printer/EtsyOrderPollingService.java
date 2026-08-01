package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Periodically polls Etsy for paid-but-unshipped receipts so the Etsy Sales Orders view can show them without
 * blocking the UI thread on every page load. Also holds a small "dismissed" set so a fulfilled order (shipped
 * outside this app, or one the user just doesn't want to see anymore) can be hidden locally.
 */
@ApplicationScoped
public class EtsyOrderPollingService {

    static final String MARKET = "etsy";

    @Inject
    EtsyApiClient client;
    @Inject
    EtsyOAuthService oauth;
    @Inject
    BambuConfig config;
    @Inject
    OrderTrackingService tracking;
    @Inject
    NotificationService notificationService;
    @Inject
    EtsyMappingService mappingService;
    @Inject
    AutoQueueService autoQueue;
    @Inject
    StockService stockService;
    @Inject
    PollFailureReporter pollFailures;

    private final AtomicReference<List<EtsyApiClient.Receipt>> lastReceipts = new AtomicReference<>(List.of());
    private final AtomicReference<Instant> lastPolled = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    @Scheduled(every = "${bambu.etsy.poll-interval:10m}")
    void poll() {
        refresh();
    }

    /** Fetches receipts now (used both by the scheduler and a manual "Refresh" button). */
    public synchronized void refresh() {
        if (!oauth.isConnected()) {
            return;
        }
        try {
            final List<EtsyApiClient.Receipt> receipts = client.getUnfulfilledReceipts();
            lastReceipts.set(receipts);
            lastPolled.set(Instant.now());
            lastError.set(null);
            Log.infof("EtsyOrderPollingService: %d unfulfilled receipt(s)", receipts.size());
            pollFailures.recordSuccess("Etsy");
            // Orders that have left the open list are done with, however their print progress ended up
            tracking.pruneClosed(MARKET, receipts.stream().map(r -> String.valueOf(r.receiptId())).collect(java.util.stream.Collectors.toSet()));
            notifyNewOrders(receipts);
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EtsyOrderPollingService: poll failed: %s", ex.getMessage());
            // A failing poll means orders silently stop arriving - notify rather than only logging.
            pollFailures.recordFailure("Etsy", ex.getMessage());
        }
    }

    /** Fires a "new_order" notification for receipts never seen before (tracked persistently, so no repeats after restart). */
    private void notifyNewOrders(final List<EtsyApiClient.Receipt> receipts) {
        final List<String> fresh = tracking.recordSeen(MARKET,
                receipts.stream().map(r -> String.valueOf(r.receiptId())).toList());
        if (fresh.isEmpty()) {
            return;
        }
        receipts.stream()
                .filter(r -> fresh.contains(String.valueOf(r.receiptId())))
                .forEach(r -> {
                    final String items = r.transactions().stream()
                            .map(t -> {
                                final String vars = t.variations().stream()
                                        .map(v -> v.propertyName() + ": " + v.value())
                                        .collect(Collectors.joining(", "));
                                return "%dx %s%s".formatted(t.quantity(), t.title(), vars.isBlank() ? "" : " (" + vars + ")");
                            })
                            .collect(Collectors.joining("; "));
                    notificationService.notifyEvent("new_order", "Etsy",
                            "New order #%d from %s: %s".formatted(r.receiptId(), r.buyerName(),
                                    items.length() > 200 ? items.substring(0, 200) + "…" : items));
                    // Work out what on-hand stock would cover and auto-queue only the remainder. The stock is NOT
                    // consumed yet: processOrder can decline the order for five different reasons, and consuming
                    // up front deducted the units anyway - so they were spent on something never printed.
                    final String orderLabel = "Etsy order #%d (%s)".formatted(r.receiptId(), r.buyerName());
                    final java.util.List<AutoQueueService.AutoQueueItem> queueItems = new java.util.ArrayList<>();
                    final java.util.List<StockService.PlannedCoverage> planned = new java.util.ArrayList<>();
                    for (final EtsyApiClient.Transaction t : r.transactions()) {
                        final java.util.Optional<String> key = mappingService.findKey(t.listingId(), t.variations());
                        final int covered = stockService.coverageFor(MARKET, key, t.quantity());
                        if (covered > 0) {
                            planned.add(new StockService.PlannedCoverage(key.get(), covered, t.title()));
                        }
                        final int toPrint = t.quantity() - covered;
                        if (key.isPresent() && toPrint <= 0) {
                            continue; // whole line covered from stock - nothing to print
                        }
                        queueItems.add(new AutoQueueService.AutoQueueItem(
                                String.valueOf(t.listingId()),
                                "%dx %s".formatted(toPrint, t.title()),
                                toPrint,
                                t.personalization().isPresent(),
                                mappingService.find(t.listingId(), t.variations())
                                        .map(EtsyMappingService.MappingEntry::parts)
                                        .orElse(java.util.List.of())));
                    }
                    final String orderId = String.valueOf(r.receiptId());
                    // Nothing left to print means stock covered the whole order - there's no queueing step to
                    // accept it, so accept it here. Previously this fell through entirely: the order was never
                    // marked queued and sat as "not queued" forever despite being fulfillable off the shelf.
                    final boolean accepted = queueItems.isEmpty()
                            ? !planned.isEmpty() && autoQueue.isEnabled()
                            : autoQueue.processOrder(MARKET, orderId, orderLabel, queueItems);
                    if (accepted) {
                        planned.forEach(p -> stockService.commitCoverage(MARKET, p, orderLabel));
                        if (queueItems.isEmpty()) {
                            tracking.markQueued(MARKET, orderId);
                            Log.infof("EtsyOrderPollingService: %s fully fulfilled from on-hand stock - nothing to print",
                                    orderLabel);
                        }
                    }
                });
    }

    /** Unfulfilled receipts, excluding any the user has dismissed. */
    public List<EtsyApiClient.Receipt> getReceipts() {
        return lastReceipts.get().stream()
                .filter(r -> !tracking.isDismissed(MARKET, String.valueOf(r.receiptId())))
                .toList();
    }

    public java.util.Optional<Instant> getLastPolled() {
        return java.util.Optional.ofNullable(lastPolled.get());
    }

    public java.util.Optional<String> getLastError() {
        return java.util.Optional.ofNullable(lastError.get());
    }

    public void dismiss(final long receiptId) {
        tracking.dismiss(MARKET, String.valueOf(receiptId));
    }

    public void undismiss(final long receiptId) {
        tracking.undismiss(MARKET, String.valueOf(receiptId));
    }

}
