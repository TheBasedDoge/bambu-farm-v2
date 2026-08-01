package com.tfyre.bambu.printer;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Surfaces marketplace order-polling failures as notifications.
 * <p>
 * Without this, a failing poll is the worst kind of failure this app can have: <b>silent</b>. Expired OAuth, a
 * refresh that 4xx'd, a marketplace outage - the poller catches it, records {@code lastError} for the Sales Orders
 * page, logs it, and the farm simply stops receiving orders. You'd only find out by happening to open that page.
 * Orders keep arriving at the marketplace the whole time.
 * <p>
 * Deliberately not chatty: a single failed poll is usually a transient 5xx and self-heals on the next tick, so
 * nothing fires until {@link #FAILURES_BEFORE_ALERT} <b>consecutive</b> failures. After that it repeats only every
 * {@link #REPEAT_INTERVAL}, and a recovery notification fires once orders start flowing again.
 */
@ApplicationScoped
public class PollFailureReporter {

    /** Consecutive failures before the first alert - one blip shouldn't page you, a real outage should. */
    private static final int FAILURES_BEFORE_ALERT = 2;
    /** How often to re-alert while a failure persists (expired credentials won't fix themselves). */
    private static final Duration REPEAT_INTERVAL = Duration.ofHours(6);

    @Inject
    NotificationService notificationService;

    private record State(int consecutiveFailures, Instant lastNotified, String lastMessage) {
    }

    private final Map<String, State> byMarket = new ConcurrentHashMap<>();

    /** Records a failed poll for a marketplace ("Etsy"/"eBay") and alerts once it looks like a real outage. */
    public void recordFailure(final String market, final String message) {
        final State prev = byMarket.getOrDefault(market, new State(0, null, null));
        final int failures = prev.consecutiveFailures() + 1;
        final Instant now = Instant.now();
        final boolean due = prev.lastNotified() == null || prev.lastNotified().isBefore(now.minus(REPEAT_INTERVAL));
        if (failures < FAILURES_BEFORE_ALERT || !due) {
            byMarket.put(market, new State(failures, prev.lastNotified(), message));
            Log.warnf("PollFailureReporter: %s poll failed (%d consecutive): %s", market, failures, message);
            return;
        }
        byMarket.put(market, new State(failures, now, message));
        notificationService.notifyEvent("poll_failed", market,
                "%s order polling has failed %d times in a row - NEW ORDERS ARE NOT BEING PICKED UP. Last error: %s"
                        .formatted(market, failures, message == null ? "(no message)" : message));
    }

    /** Records a successful poll; fires a recovery notification if the failure had been reported. */
    public void recordSuccess(final String market) {
        final State prev = byMarket.remove(market);
        if (prev != null && prev.lastNotified() != null) {
            notificationService.notifyEvent("poll_failed", market,
                    "%s order polling is working again after %d failed attempt(s) - any orders that arrived meanwhile will be picked up now."
                            .formatted(market, prev.consecutiveFailures()));
        }
    }

    /** Consecutive failures for a market, 0 when healthy (for status display). */
    public int getConsecutiveFailures(final String market) {
        final State s = byMarket.get(market);
        return s == null ? 0 : s.consecutiveFailures();
    }
}
