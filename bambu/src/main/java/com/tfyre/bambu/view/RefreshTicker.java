package com.tfyre.bambu.view;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import io.quarkus.logging.Log;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The once-a-second live refresh every page runs, done so that one slow tick cannot hold up the app.
 * <p>
 * A page's refresh runs inside {@code ui.access}, i.e. with the browser session locked - and Vaadin runs a queued
 * access task on whichever thread next unlocks the session, which can be the web server's I/O thread (seen in the
 * logs on 2026-10-10: the Automation page's refresh stuck on a slow disk check froze every request for up to 67 s).
 * So:
 * <ul>
 * <li>fixed delay, not fixed rate: a tick that overruns does not leave the next ones queued up behind it;</li>
 * <li>at most one tick waiting for the session: while the last one hasn't run yet (slow browser, busy session),
 * new ones are skipped instead of piling up;</li>
 * <li>every tick is timed, and one slower than {@link #SLOW_MS} is logged with the page's name - at most once a
 * minute per page - so slowness shows up in the log as a page name instead of a feeling.</li>
 * </ul>
 */
public final class RefreshTicker {

    public static final long SLOW_MS = 250;
    private static final long WARN_EVERY_MS = 60_000;
    private static final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    private RefreshTicker() {
    }

    public static ScheduledFuture<?> start(final ScheduledExecutorService ses, final UI ui, final String page,
            final Duration interval, final Runnable tick) {
        final AtomicBoolean waiting = new AtomicBoolean();
        final long period = Math.max(1000, interval.toMillis());
        return ses.scheduleWithFixedDelay(() -> {
            if (!waiting.compareAndSet(false, true)) {
                return;
            }
            try {
                ui.access(() -> {
                    final long started = System.nanoTime();
                    try {
                        tick.run();
                    } finally {
                        waiting.set(false);
                        report(page, "refresh", (System.nanoTime() - started) / 1_000_000);
                    }
                });
            } catch (UIDetachedException ex) {
                waiting.set(false);
            } catch (RuntimeException ex) {
                waiting.set(false);
                Log.warnf("RefreshTicker: %s: could not schedule a refresh: %s", page, ex.getMessage());
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }

    /** Logs a slow page build or refresh; quiet below {@link #SLOW_MS}. */
    public static void report(final String page, final String what, final long millis) {
        if (millis < SLOW_MS) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastWarn.get(page + what);
        if (last != null && now - last < WARN_EVERY_MS) {
            return;
        }
        lastWarn.put(page + what, now);
        Log.warnf("Slow page %s: %s took %d ms (the app waits on this; anything over %d ms is logged, once a minute per page)",
                what, page, millis, SLOW_MS);
    }

    /** "Dashboard" for a CDI subclass such as Dashboard_Subclass. */
    public static String pageName(final Object view) {
        final String name = view.getClass().getSimpleName();
        final int cut = name.indexOf('_');
        return cut > 0 ? name.substring(0, cut) : name;
    }
}
