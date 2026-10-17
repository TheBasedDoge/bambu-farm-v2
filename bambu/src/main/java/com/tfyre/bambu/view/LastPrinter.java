package com.tfyre.bambu.view;

import com.vaadin.flow.server.VaadinSession;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The printer last picked on the Printer, SD Card or Logs page, so the next of those pages opens on it instead of
 * empty. Kept in the browser session: a new login starts from the first printer by name.
 */
public final class LastPrinter {

    private static final String KEY = "bambufarm.lastPrinter";

    private LastPrinter() {
    }

    public static void set(final String printerName) {
        final VaadinSession session = VaadinSession.getCurrent();
        if (session != null && printerName != null) {
            session.setAttribute(KEY, printerName);
        }
    }

    /** The printer asked for in the address, else the last one picked, else the first in the list. */
    public static <T> Optional<T> pick(final Optional<T> requested, final List<T> printers, final Function<T, String> name) {
        if (requested.isPresent()) {
            return requested;
        }
        final VaadinSession session = VaadinSession.getCurrent();
        final Object last = session == null ? null : session.getAttribute(KEY);
        if (last != null) {
            final Optional<T> match = printers.stream().filter(p -> last.equals(name.apply(p))).findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return printers.stream().findFirst();
    }
}
