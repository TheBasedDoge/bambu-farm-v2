package com.tfyre.bambu.view;

import com.tfyre.bambu.YesNoCancelDialog;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import io.quarkus.logging.Log;
import java.time.Duration;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public interface ViewHelper {

    default double parseDouble(final String printerName, final String value, final double defaultValue) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            Log.errorf("%s: Cannot parseDouble [%s]", printerName, value);
            return defaultValue;
        }
    }

    default double parseDouble(final String value, final double defaultValue) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            Log.errorf("Cannot parseDouble [%s]", value);
            return defaultValue;
        }
    }

    default int parseInt(final String printerName, final String value, final int defaultValue) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            Log.errorf("s: Cannot parseInt [%s]", printerName, value);
            return defaultValue;
        }
    }

    default int parseInt(final String value, final int defaultValue) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            Log.errorf("Cannot parseInt [%s]", value);
            return defaultValue;
        }
    }

    /** "34d 4h", "4h 55m", "48m" - was "34 day(s) 4 hour(s) 35 minute(s)". */
    default String formatTime(final Duration duration) {
        final long days = duration.toDays();
        final int hours = duration.toHoursPart();
        final int minutes = duration.toMinutesPart();
        if (days > 0) {
            return hours > 0 ? "%dd %dh".formatted(days, hours) : "%dd".formatted(days);
        }
        if (hours > 0) {
            return "%dh %02dm".formatted(hours, minutes);
        }
        return "%dm".formatted(minutes);
    }

    default Div newDiv(final String className, final Component... components) {
        final Div result = new Div(components);
        result.addClassName(className);
        return result;
    }

    default Span newSpan(final String className) {
        final Span result = new Span();
        result.addClassName(className);
        return result;
    }

    default ProgressBar newProgressBar() {
        final ProgressBar result = new ProgressBar(0.0, 100.0);
        result.addClassName("progress");
        return result;
    }

    default void doConfirm(final Runnable runnable) {
        YesNoCancelDialog.show("Are you sure?", ync -> {
            if (!ync.isConfirmed()) {
                return;
            }
            runnable.run();
        });
    }

}
