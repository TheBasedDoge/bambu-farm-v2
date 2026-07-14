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
            notifyNewOrders(orders);
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EbayOrderPollingService: poll failed: %s", ex.getMessage());
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
                            .map(li -> "%dx %s".formatted(li.quantity(), li.title()))
                            .collect(Collectors.joining(", "));
                    notificationService.notifyEvent("new_order", "eBay",
                            "New order %s from %s: %s".formatted(o.orderId(), o.buyerUsername(),
                                    items.length() > 200 ? items.substring(0, 200) + "…" : items));
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
