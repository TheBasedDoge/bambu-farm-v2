package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persisted per-marketplace order bookkeeping, shared by the Etsy and eBay integrations:
 * <ul>
 * <li><b>seen</b> - every order ID ever observed by a poll. Used to detect genuinely NEW orders for the
 * new-order notification (an ID not in this set), without re-alerting after restarts or on every poll.</li>
 * <li><b>dismissed</b> - orders the user hid from the Sales Orders page. Previously in-memory, so dismissed
 * orders resurrected on every restart.</li>
 * <li><b>queued</b> - order IDs the user has queued print jobs for (with timestamp), so the orders pages can
 * show a "queued" badge and prevent accidentally printing an order twice.</li>
 * </ul>
 * Marketplace keys are lowercase ("etsy", "ebay"); order IDs are strings (Etsy receipt IDs stringified).
 */
@ApplicationScoped
public class OrderTrackingService {

    private static final String STORE_FILENAME = "bambu-order-tracking.json";

    /** Jackson-friendly mutable holder - one per marketplace. */
    public static class MarketState {

        public Set<String> seen = new HashSet<>();
        public Set<String> dismissed = new HashSet<>();
        public Map<String, Instant> queued = new HashMap<>();
        /** Listing IDs/keys the user hid on the Mappings tab (products that are never printed). */
        public Set<String> hiddenListings = new HashSet<>();
        /** Per-order print progress (jobs queued vs finished), for the ready-to-ship notification. */
        public Map<String, OrderProgress> progress = new HashMap<>();
    }

    /** Jackson-friendly per-order progress counters. */
    public static class OrderProgress {

        public int expected;
        public int printed;
        public boolean notified;
    }

    /** Immutable snapshot of an order's print progress for the UI. */
    public record ProgressView(String orderId, int expected, int printed) {

        public boolean complete() {
            return expected > 0 && printed >= expected;
        }
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;

    private final Map<String, MarketState> data = new HashMap<>();
    private boolean dirty;

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            data.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, MarketState>>() {
            }));
            Log.infof("OrderTrackingService: loaded state for %d marketplace(s) from %s", data.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "OrderTrackingService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), data);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "OrderTrackingService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    private MarketState state(final String market) {
        return data.computeIfAbsent(market, k -> new MarketState());
    }

    /**
     * Records the given order IDs as seen and returns the ones that were NEW (never seen before).
     * <p>
     * When the marketplace has never been polled before (empty seen set - fresh install or first connect),
     * everything is recorded but nothing is reported as new, so connecting a shop with 30 open orders doesn't
     * fire 30 notifications.
     */
    public synchronized List<String> recordSeen(final String market, final List<String> orderIds) {
        final MarketState s = state(market);
        final boolean firstPoll = s.seen.isEmpty();
        final List<String> fresh = new ArrayList<>();
        for (final String id : orderIds) {
            if (s.seen.add(id)) {
                dirty = true;
                if (!firstPoll) {
                    fresh.add(id);
                }
            }
        }
        save();
        return fresh;
    }

    public synchronized boolean isDismissed(final String market, final String orderId) {
        return state(market).dismissed.contains(orderId);
    }

    public synchronized void dismiss(final String market, final String orderId) {
        if (state(market).dismissed.add(orderId)) {
            dirty = true;
            save();
        }
    }

    public synchronized void undismiss(final String market, final String orderId) {
        if (state(market).dismissed.remove(orderId)) {
            dirty = true;
            save();
        }
    }

    public synchronized void markQueued(final String market, final String orderId) {
        state(market).queued.put(orderId, Instant.now());
        dirty = true;
        save();
    }

    /**
     * Registers {@code jobs} more print jobs as queued for an order (accumulates across repeated queueing).
     * Drives the "X/Y printed" progress display and the ready-to-ship notification.
     */
    public synchronized void addExpectedJobs(final String market, final String orderId, final int jobs) {
        if (jobs <= 0) {
            return;
        }
        final OrderProgress p = state(market).progress.computeIfAbsent(orderId, k -> new OrderProgress());
        p.expected += jobs;
        p.notified = false;
        dirty = true;
        save();
    }

    /**
     * Records one successfully finished print for an order. Returns {@code true} exactly once, when this
     * finish completes the order (all expected jobs printed) - the caller fires the ready-to-ship
     * notification on that.
     */
    public synchronized boolean recordJobPrinted(final String market, final String orderId) {
        final OrderProgress p = state(market).progress.get(orderId);
        if (p == null) {
            return false;
        }
        p.printed++;
        dirty = true;
        final boolean justCompleted = p.expected > 0 && p.printed >= p.expected && !p.notified;
        if (justCompleted) {
            p.notified = true;
        }
        save();
        return justCompleted;
    }

    /** Progress for all orders of a marketplace that have print jobs registered. */
    public synchronized List<ProgressView> progress(final String market) {
        return state(market).progress.entrySet().stream()
                .map(e -> new ProgressView(e.getKey(), e.getValue().expected, e.getValue().printed))
                .toList();
    }

    public synchronized Optional<Instant> queuedAt(final String market, final String orderId) {
        return Optional.ofNullable(state(market).queued.get(orderId));
    }

    /** All queued-order markers for a marketplace (orderId → when), for the Automation overview. */
    public synchronized Map<String, Instant> queuedOrders(final String market) {
        return Map.copyOf(state(market).queued);
    }

    // -------------------------------------------------------------------------
    // Hidden listings - products that are never printed (digital items, add-ons, ...).
    // Hidden + unmapped listings are silently ignored by auto-queue instead of raising
    // an "auto_queue_skipped: not mapped" alert on every order containing one.
    // -------------------------------------------------------------------------

    public synchronized boolean isListingHidden(final String market, final String listingKey) {
        return state(market).hiddenListings.contains(listingKey);
    }

    public synchronized void hideListing(final String market, final String listingKey) {
        if (state(market).hiddenListings.add(listingKey)) {
            dirty = true;
            save();
        }
    }

    public synchronized void unhideListing(final String market, final String listingKey) {
        if (state(market).hiddenListings.remove(listingKey)) {
            dirty = true;
            save();
        }
    }

    public synchronized Set<String> hiddenListings(final String market) {
        return Set.copyOf(state(market).hiddenListings);
    }

}
