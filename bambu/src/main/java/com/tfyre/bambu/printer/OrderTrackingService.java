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
        /**
         * Parts that were expected, failed or were stopped, and were never put back on a queue. Their expectation
         * is released so a later re-queue doesn't double-count, but the order still owes them - so while this is
         * above zero the order can NOT read complete or fire ready-to-ship, however many other parts finish.
         * Re-queueing the order clears it. Absent from older JSON, which deserialises to 0 - correct, since
         * nothing was tracking abandonment before.
         */
        public int abandoned;
        /**
         * What was ordered, captured when the order was queued. Held here rather than looked up on demand
         * because an order drops out of the marketplace's open list once it's marked shipped, and its progress
         * can outlive that - without this the UI falls back to showing a meaningless order number.
         */
        public String title;
    }

    /** Immutable snapshot of an order's print progress for the UI. */
    public record ProgressView(String orderId, int expected, int printed, String title, int abandoned) {

        public boolean complete() {
            return expected > 0 && printed >= expected && abandoned == 0;
        }

        /** True when a part was given up on - the order needs a human, not a shipping label. */
        public boolean needsAttention() {
            return abandoned > 0;
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
        addExpectedJobs(market, orderId, jobs, null);
    }

    /** @param title what was ordered, kept so the UI can name it after the order leaves the open list */
    public synchronized void addExpectedJobs(final String market, final String orderId, final int jobs, final String title) {
        if (jobs <= 0) {
            return;
        }
        final OrderProgress p = state(market).progress.computeIfAbsent(orderId, k -> new OrderProgress());
        p.expected += jobs;
        // Queueing work for this order is what un-abandons it: these jobs cover the parts that were given up on.
        p.abandoned = Math.max(0, p.abandoned - jobs);
        p.notified = false;
        if (title != null && !title.isBlank()) {
            p.title = title;
        }
        dirty = true;
        save();
    }

    /**
     * Un-registers {@code jobs} expected print jobs for an order - used when a job is cancelled before it ever
     * printed (e.g. removed from the dispatch pool). Without this the order would sit at "N-1/N printed" forever
     * and never fire its ready-to-ship notification. Clamped so {@code expected} never drops below what has
     * already printed; if that leaves the order complete, the next {@link #recordJobPrinted} can still fire.
     */
    public synchronized void removeExpectedJobs(final String market, final String orderId, final int jobs) {
        removeExpectedJobs(market, orderId, jobs, false);
    }

    /**
     * @param abandoned true when the part is being given up on rather than deliberately cancelled - it failed and
     *                  nothing is going to reprint it unless a human intervenes. Releasing the expectation stops a
     *                  later re-queue from double-counting, but the order must not be able to reach "ready to
     *                  ship" on the strength of a part that was never made, so it's recorded and blocks completion
     *                  until the order is queued again. A deliberate cancellation ("don't print this") passes
     *                  false: the order genuinely needs one part fewer.
     */
    public synchronized void removeExpectedJobs(final String market, final String orderId, final int jobs,
            final boolean abandoned) {
        if (jobs <= 0) {
            return;
        }
        final OrderProgress p = state(market).progress.get(orderId);
        if (p == null) {
            return;
        }
        p.expected = Math.max(p.printed, p.expected - jobs);
        if (abandoned) {
            p.abandoned += jobs;
        }
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
        // p.abandoned == 0: a part that failed and was never reprinted must not be papered over by the others
        // finishing. Telling you an order is ready to ship when it's short a part is the one wrong answer here.
        final boolean justCompleted = p.expected > 0 && p.printed >= p.expected && p.abandoned == 0 && !p.notified;
        if (justCompleted) {
            p.notified = true;
        }
        save();
        return justCompleted;
    }

    /**
     * Drops progress for orders the marketplace no longer lists as open, unless they completed (a completed entry
     * is what stops the ready-to-ship notification firing twice). Without this, an order that was queued but never
     * finished - shipped by hand, or queued before jobs carried an order reference - keeps its 0/N entry forever,
     * inflating "in progress" and growing this file indefinitely.
     *
     * @param openOrderIds order IDs the marketplace currently reports as open
     */
    public synchronized void pruneClosed(final String market, final Set<String> openOrderIds) {
        final Map<String, OrderProgress> progress = state(market).progress;
        final int before = progress.size();
        progress.entrySet().removeIf(e -> !openOrderIds.contains(e.getKey())
                && !(e.getValue().expected > 0 && e.getValue().printed >= e.getValue().expected));
        if (progress.size() != before) {
            Log.infof("OrderTrackingService: %s: dropped %d progress entry(s) for orders no longer open",
                    market, before - progress.size());
            dirty = true;
            save();
        }
    }

    /** Progress for all orders of a marketplace that have print jobs registered. */
    public synchronized List<ProgressView> progress(final String market) {
        return state(market).progress.entrySet().stream()
                .map(e -> new ProgressView(e.getKey(), e.getValue().expected, e.getValue().printed, e.getValue().title,
                        e.getValue().abandoned))
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
