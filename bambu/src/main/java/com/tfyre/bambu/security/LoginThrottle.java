package com.tfyre.bambu.security;

import com.tfyre.bambu.printer.NotificationService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Slows password guessing: after {@link #FREE_FAILURES} wrong passwords for one username inside {@link #WINDOW}, that
 * username is locked for a minute, doubling with each further failure up to {@link #MAX_LOCK}.
 * <p>
 * 2026-11-18 security audit: nothing limited login attempts, and every visitor reaches the app as 172.18.0.1 (Docker
 * Desktop on Windows hides the real address), so it can't be per IP - it is per username. Unknown usernames are
 * counted the same way, so the answer never says which names exist.
 * <p>
 * A locked username is refused even with the right password - that's what makes guessing slow. A remembered
 * device (256-bit token) still logs in, so a lockout someone else triggers doesn't lock the owner out of the
 * wall display or their phone. A correct login clears the count. In memory only: a restart forgets it, which is
 * fine for a lock measured in minutes.
 */
@ApplicationScoped
public class LoginThrottle {

    static final int FREE_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration FIRST_LOCK = Duration.ofMinutes(1);
    static final Duration MAX_LOCK = Duration.ofMinutes(30);
    private static final int MAX_TRACKED = 1000;

    @Inject
    Instance<NotificationService> notifications;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();
    Supplier<Instant> clock = Instant::now;

    /** What a typed username may show in logs and alerts: no newlines, no "@everyone", nothing long. */
    public static String shown(final String username) {
        final String s = username == null ? "" : username.replaceAll("[^A-Za-z0-9._-]", "?");
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static String key(final String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** How much longer this username is locked, or empty when it may try. */
    public Optional<Duration> lockedFor(final String username) {
        final Instant until = lockedUntil.get(key(username));
        if (until == null) {
            return Optional.empty();
        }
        final Duration left = Duration.between(clock.get(), until);
        if (left.isNegative() || left.isZero()) {
            lockedUntil.remove(key(username));
            return Optional.empty();
        }
        return Optional.of(left);
    }

    /** A wrong password. Returns the lock it started, if this failure tipped it over. */
    public synchronized Optional<Duration> failed(final String username) {
        final String k = key(username);
        final Instant now = clock.get();
        if (failures.size() >= MAX_TRACKED && !failures.containsKey(k)) {
            prune(now);
        }
        final Deque<Instant> q = failures.computeIfAbsent(k, x -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) {
            q.removeFirst();
        }
        final int over = q.size() - FREE_FAILURES;
        if (over < 0) {
            return Optional.empty();
        }
        final Duration lock = lockFor(over);
        lockedUntil.put(k, now.plus(lock));
        Log.warnf("LoginThrottle: %d failed logins for [%s] in %d min - locked for %s", q.size(), shown(username),
                WINDOW.toMinutes(), human(lock));
        if (over == 0) {
            notify(username, q.size(), lock);
        }
        return Optional.of(lock);
    }

    public synchronized void succeeded(final String username) {
        failures.remove(key(username));
        lockedUntil.remove(key(username));
    }

    /** 1 min for the 5th failure, then 2, 4, 8, 16, 30 (cap). */
    static Duration lockFor(final int over) {
        final long minutes = FIRST_LOCK.toMinutes() << Math.min(over, 10);
        return Duration.ofMinutes(Math.min(minutes, MAX_LOCK.toMinutes()));
    }

    public static String human(final Duration d) {
        final long s = Math.max(1, d.toSeconds());
        return s < 60 ? s + " s" : ((s + 59) / 60) + " min";
    }

    private void prune(final Instant now) {
        failures.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(WINDOW)));
        lockedUntil.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private void notify(final String username, final int count, final Duration lock) {
        try {
            if (notifications != null && notifications.isResolvable()) {
                notifications.get().notifyEvent("login_locked", "Login",
                        "%d wrong passwords for user \"%s\" within %d minutes - that username is locked for %s (longer if it continues). If this wasn't you, someone is guessing."
                                .formatted(count, shown(username), WINDOW.toMinutes(), human(lock)));
            }
        } catch (RuntimeException ex) {
            Log.debugf("LoginThrottle: notification failed: %s", ex.getMessage());
        }
    }
}
