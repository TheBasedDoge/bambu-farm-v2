package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically polls eBay for open (not-started/in-progress) orders, mirroring {@link EtsyOrderPollingService}.
 */
@ApplicationScoped
public class EbayOrderPollingService {

    @Inject
    EbayApiClient client;
    @Inject
    EbayOAuthService oauth;
    @Inject
    BambuConfig config;

    private final AtomicReference<List<EbayApiClient.Order>> lastOrders = new AtomicReference<>(List.of());
    private final AtomicReference<Instant> lastPolled = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final Set<String> dismissed = ConcurrentHashMap.newKeySet();

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
        } catch (Exception ex) {
            lastError.set(ex.getMessage());
            Log.errorf(ex, "EbayOrderPollingService: poll failed: %s", ex.getMessage());
        }
    }

    public List<EbayApiClient.Order> getOrders() {
        return lastOrders.get().stream().filter(o -> !dismissed.contains(o.orderId())).toList();
    }

    public Optional<Instant> getLastPolled() {
        return Optional.ofNullable(lastPolled.get());
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError.get());
    }

    public void dismiss(final String orderId) {
        dismissed.add(orderId);
    }

    public void undismiss(final String orderId) {
        dismissed.remove(orderId);
    }

}
