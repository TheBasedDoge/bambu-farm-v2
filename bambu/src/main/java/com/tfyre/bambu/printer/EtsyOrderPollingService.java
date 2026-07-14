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
            notifyNewOrders(receipts);
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EtsyOrderPollingService: poll failed: %s", ex.getMessage());
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
                            .map(t -> "%dx %s".formatted(t.quantity(), t.title()))
                            .collect(Collectors.joining(", "));
                    notificationService.notifyEvent("new_order", "Etsy",
                            "New order #%d from %s: %s".formatted(r.receiptId(), r.buyerName(),
                                    items.length() > 200 ? items.substring(0, 200) + "…" : items));
                    autoQueue.processOrder(MARKET, String.valueOf(r.receiptId()),
                            "Etsy order #%d (%s)".formatted(r.receiptId(), r.buyerName()),
                            r.transactions().stream()
                                    .map(t -> new AutoQueueService.AutoQueueItem(
                                            "%dx %s".formatted(t.quantity(), t.title()),
                                            t.quantity(),
                                            mappingService.find(t.listingId(), t.variations())
                                                    .map(EtsyMappingService.MappingEntry::parts)
                                                    .orElse(java.util.List.of())))
                                    .toList());
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
