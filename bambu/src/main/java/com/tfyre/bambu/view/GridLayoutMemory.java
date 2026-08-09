package com.tfyre.bambu.view;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.grid.Grid;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Makes a grid's column order and widths survive a page reload.
 * <p>
 * Twelve grids in this app call {@code setColumnReorderingAllowed(true)} and every one of them forgot the
 * result the moment you navigated away - Vaadin's reordering and resizing are client-side only, with nothing
 * persisting them. Dragging a column appeared to work and then silently didn't.
 * <p>
 * Stored per device in {@code localStorage}, following the existing {@code bambufarm-*} convention used for the
 * dashboard card layout, camera sizes and SD-card column visibility. Per device rather than per user on purpose:
 * a column layout is a function of the screen you're looking at, not of who you are.
 */
public final class GridLayoutMemory {

    private static final String PREFIX = "bambufarm-grid-";
    /** Marker so re-attaching a view (these grids are fields, and views rebuild on every attach) can't stack listeners. */
    private static final String WIRED = "grid-layout-memory-wired";

    private GridLayoutMemory() {
    }

    /**
     * Remembers this grid's column order and widths under {@code name}.
     * <p>
     * Call it <b>after</b> the columns are configured - it assigns any column without a key a positional one, and
     * the saved layout is matched back by those keys.
     *
     * @param name stable identifier for this grid, unique across the app (it becomes the localStorage key)
     */
    public static <T> void remember(final Grid<T> grid, final String name) {
        if (Boolean.TRUE.equals(ComponentUtil.getData(grid, WIRED))) {
            return;
        }
        ComponentUtil.setData(grid, WIRED, Boolean.TRUE);

        final List<Grid.Column<T>> columns = grid.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getKey() == null) {
                // Positional keys. Fine because the saved layout is discarded whenever the column COUNT changes,
                // which is the only way these can shift - a release that adds or removes a column resets the
                // layout rather than restoring a scrambled one.
                columns.get(i).setKey("c" + i);
            }
        }

        final String storeKey = PREFIX + name;
        grid.getElement().executeJs("return localStorage.getItem($0) || ''", storeKey)
                .then(String.class, saved -> restore(grid, saved));
        grid.addColumnReorderListener(e -> save(grid, storeKey));
        grid.addColumnResizeListener(e -> save(grid, storeKey));
    }

    /** Format: {@code <count>|key:width,key:width,…} in display order. Width is blank when never resized. */
    private static <T> void save(final Grid<T> grid, final String storeKey) {
        final List<Grid.Column<T>> columns = grid.getColumns();
        final String payload = columns.size() + "|" + columns.stream()
                .map(c -> c.getKey() + ":" + (c.getWidth() == null ? "" : c.getWidth()))
                .collect(Collectors.joining(","));
        grid.getElement().executeJs("localStorage.setItem($0, $1)", storeKey, payload);
    }

    private static <T> void restore(final Grid<T> grid, final String saved) {
        if (saved == null || saved.isBlank()) {
            return;
        }
        try {
            final int bar = saved.indexOf('|');
            if (bar < 0) {
                return;
            }
            final List<Grid.Column<T>> columns = grid.getColumns();
            if (Integer.parseInt(saved.substring(0, bar)) != columns.size()) {
                // The grid gained or lost a column since this was saved. Restoring a partial order would drop
                // columns entirely, so the old layout is discarded instead.
                return;
            }
            final List<Grid.Column<T>> ordered = new ArrayList<>();
            final List<String> widths = new ArrayList<>();
            for (final String part : saved.substring(bar + 1).split(",")) {
                final int colon = part.lastIndexOf(':');
                if (colon < 0) {
                    return;
                }
                final Grid.Column<T> column = grid.getColumnByKey(part.substring(0, colon));
                if (column == null || ordered.contains(column)) {
                    return; // unknown or duplicated key - don't half-apply a layout
                }
                ordered.add(column);
                widths.add(part.substring(colon + 1));
            }
            // setColumnOrder throws unless it's given every column exactly once.
            if (ordered.size() != columns.size()) {
                return;
            }
            grid.setColumnOrder(ordered);
            for (int i = 0; i < ordered.size(); i++) {
                if (!widths.get(i).isBlank()) {
                    ordered.get(i).setWidth(widths.get(i));
                }
            }
        } catch (RuntimeException ex) {
            // A saved layout is a convenience. A malformed one - hand-edited, or written by an older build -
            // must cost you a drag, not a broken page.
            Log.debugf("GridLayoutMemory: ignoring an unreadable saved layout: %s", ex.getMessage());
        }
    }
}
