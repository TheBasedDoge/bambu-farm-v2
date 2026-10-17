package com.tfyre.bambu.view;

import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public abstract class PushDiv extends Div implements FlexComponent {

    @Inject
    ScheduledExecutorService ses;

    private Optional<ScheduledFuture<?>> future = Optional.empty();

    private void cancelFuture() {
        future.ifPresent(f -> f.cancel(true));
        future = Optional.empty();
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        cancelFuture();
    }

    /**
     * The page's live refresh - see {@link RefreshTicker}: never piles up behind a slow tick, and logs a slow one
     * with the page's name. Replaces any refresh the page already had.
     */
    public ScheduledFuture<?> createRefresh(final com.vaadin.flow.component.UI ui, final Duration interval, final Runnable tick) {
        cancelFuture();
        future = Optional.of(RefreshTicker.start(ses, ui, RefreshTicker.pageName(this), interval, tick));
        return future.get();
    }

    public ScheduledFuture<?> createFuture(final Runnable runnable, final Duration interval) {
        cancelFuture();
        future = Optional.of(ses.scheduleAtFixedRate(runnable, 0, interval.getSeconds(), TimeUnit.SECONDS));
        return future.get();
    }

}
