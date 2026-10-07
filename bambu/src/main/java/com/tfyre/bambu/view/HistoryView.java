package com.tfyre.bambu.view;

import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.PrintHistoryService;
import com.tfyre.bambu.printer.PrintHistoryService.PrintJob;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Print history and statistics.
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "history", layout = MainLayout.class)
@PageTitle("History")
@RolesAllowed({ SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_NORMAL })
public class HistoryView extends VerticalLayout implements GridHelper<PrintJob>, ViewHelper, UpdateHeader {

    private static final DateTimeFormatter DTF = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .toFormatter();

    @Inject
    PrintHistoryService history;
    @Inject
    com.tfyre.bambu.BambuConfig config;

    private final Grid<PrintJob> grid = new Grid<>();
    private final Div stats = new Div();
    private final Div charts = new Div();
    private final Grid<FileStat> fileGrid = new Grid<>();
    private final com.vaadin.flow.component.details.Details fileSection
            = new com.vaadin.flow.component.details.Details("Failure rate by file", fileGrid);

    /**
     * One print file's record across every printer. {@code failed} counts prints the printer itself reported as
     * Failed; Stopped (you cancelled it) and Offline (the app lost sight of the printer) are shown but are not
     * failures of the file and are left out of the rate.
     */
    public record FileStat(String file, int finished, int failed, int other, java.time.OffsetDateTime lastFailed) {

        int total() {
            return finished + failed + other;
        }

        /** Failed as a share of the prints that ran to a verdict, 0-100. */
        int failPct() {
            return finished + failed == 0 ? 0 : Math.round(100f * failed / (finished + failed));
        }
    }

    private String cost(final double grams) {
        if (grams <= 0 || config.costPerKg() <= 0) {
            return "--";
        }
        return "%s%.2f".formatted(config.currencySymbol(), grams / 1000.0 * config.costPerKg());
    }

    @Override
    public Grid<PrintJob> getGrid() {
        return grid;
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("history-view");
        setSizeFull();
        stats.addClassName("history-stats");
        charts.addClassName("history-charts");
        configureGrid();
        configureFileGrid();
        add(stats, charts, fileSection, grid);
        refreshItems();
    }

    private void refreshItems() {
        grid.setItems(history.getJobs());
        fileGrid.setItems(fileStats(history.getJobs()));
        buildStats();
        buildCharts();
    }

    private void buildCharts() {
        charts.removeAll();
        final List<PrintJob> jobs = history.getJobs();
        if (jobs.isEmpty()) {
            return;
        }
        charts.add(buildDailyChart(jobs), buildUtilizationChart(jobs));
    }

    private Component buildDailyChart(final List<PrintJob> jobs) {
        final LocalDate today = LocalDate.now();
        final Map<LocalDate, int[]> days = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) {
            days.put(today.minusDays(i), new int[2]);
        }
        jobs.forEach(j -> {
            final int[] counts = days.get(j.ended().toLocalDate());
            if (counts == null) {
                return;
            }
            counts["Finished".equals(j.result()) ? 0 : 1]++;
        });
        final int max = days.values().stream().mapToInt(c -> c[0] + c[1]).max().orElse(0);

        final Div cols = newDiv("chart-cols");
        days.forEach((date, counts) -> {
            final Div bar = newDiv("chart-bar");
            if (max > 0) {
                if (counts[0] > 0) {
                    bar.add(chartSegment("chart-ok", counts[0], max));
                }
                if (counts[1] > 0) {
                    bar.add(chartSegment("chart-fail", counts[1], max));
                }
            }
            final Span label = new Span("%d".formatted(date.getDayOfMonth()));
            label.addClassName("chart-label");
            final Div col = newDiv("chart-col", bar, label);
            col.getElement().setAttribute("title", "%s: %d finished, %d failed/stopped".formatted(date, counts[0], counts[1]));
            cols.add(col);
        });
        final Span title = new Span("Prints per day (14d)");
        title.addClassName("chart-title");
        return newDiv("chart", title, cols);
    }

    private Div chartSegment(final String className, final int count, final int max) {
        final Div result = newDiv(className);
        result.getStyle().setHeight("%dpx".formatted(Math.max(4, count * 80 / max)));
        return result;
    }

    private Component buildUtilizationChart(final List<PrintJob> jobs) {
        final OffsetDateTime cutoff = OffsetDateTime.now().minusDays(7);
        final Map<String, Long> seconds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        jobs.stream()
                .filter(j -> j.ended().isAfter(cutoff))
                .forEach(j -> seconds.merge(j.printer(), j.durationSeconds(), Long::sum));
        final long window = Duration.ofDays(7).toSeconds();

        final Div rows = newDiv("util-rows");
        seconds.forEach((name, sec) -> {
            final int pct = (int) Math.min(100, sec * 100 / window);
            final Div fill = newDiv("util-fill");
            fill.getStyle().setWidth("%d%%".formatted(Math.max(1, pct)));
            final Span label = new Span(name);
            label.addClassName("util-name");
            rows.add(newDiv("util-row", label, newDiv("util-track", fill), new Span("%d%%".formatted(pct))));
        });
        final Span title = new Span("Utilization (7d)");
        title.addClassName("chart-title");
        return newDiv("chart", title, rows);
    }

    /**
     * Groups the history by file. A job started from an SD subfolder is recorded as "_S2000/x.gcode.3mf" and the
     * same file from the root as "x.gcode.3mf", so the folder is dropped - they are one file.
     */
    private static List<FileStat> fileStats(final List<PrintJob> jobs) {
        final java.util.Map<String, int[]> counts = new java.util.HashMap<>();
        final java.util.Map<String, java.time.OffsetDateTime> lastFailed = new java.util.HashMap<>();
        for (final PrintJob j : jobs) {
            final String raw = j.file() == null ? "" : j.file();
            final String file = raw.isBlank() ? "(unnamed)" : raw.substring(raw.lastIndexOf('/') + 1);
            final int[] c = counts.computeIfAbsent(file, k -> new int[3]);
            switch (j.result() == null ? "" : j.result()) {
                case "Finished" ->
                    c[0]++;
                case "Failed" -> {
                    c[1]++;
                    if (j.ended() != null) {
                        lastFailed.merge(file, j.ended(), (a, b) -> a.isAfter(b) ? a : b);
                    }
                }
                default ->
                    c[2]++;
            }
        }
        final List<FileStat> out = new java.util.ArrayList<>();
        counts.forEach((file, c) -> out.add(new FileStat(file, c[0], c[1], c[2], lastFailed.get(file))));
        // Worst first: the files that fail most often, by rate and then by count.
        out.sort(Comparator.comparingInt(FileStat::failPct).reversed()
                .thenComparing(Comparator.comparingInt(FileStat::failed).reversed())
                .thenComparing(FileStat::file, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void configureFileGrid() {
        if (!fileGrid.getColumns().isEmpty()) {
            return;
        }
        fileGrid.addColumn(FileStat::file).setHeader("File").setFlexGrow(3).setSortable(true)
                .setComparator(Comparator.comparing(FileStat::file, String.CASE_INSENSITIVE_ORDER))
                .setTooltipGenerator(FileStat::file);
        fileGrid.addColumn(FileStat::total).setHeader("Prints").setAutoWidth(true).setSortable(true)
                .setComparator(Comparator.comparingInt(FileStat::total));
        fileGrid.addColumn(FileStat::finished).setHeader("Finished").setAutoWidth(true).setSortable(true)
                .setComparator(Comparator.comparingInt(FileStat::finished));
        fileGrid.addColumn(FileStat::failed).setHeader("Failed").setAutoWidth(true).setSortable(true)
                .setComparator(Comparator.comparingInt(FileStat::failed));
        fileGrid.addComponentColumn(s -> {
            final Span rate = new Span(s.finished() + s.failed() == 0 ? "--" : s.failPct() + "%");
            // Colour only where it means something: a file that fails one print in five needs re-slicing.
            if (s.failed() >= 2 && s.failPct() >= 20) {
                rate.addClassName(LumoUtility.TextColor.ERROR);
            } else if (s.failed() > 0) {
                rate.addClassName(LumoUtility.TextColor.WARNING);
            }
            return rate;
        }).setHeader("Failure rate").setAutoWidth(true).setSortable(true)
                .setComparator(Comparator.comparingInt(FileStat::failPct));
        fileGrid.addColumn(s -> s.other() == 0 ? "" : String.valueOf(s.other())).setHeader("Stopped / offline").setAutoWidth(true);
        fileGrid.addColumn(s -> s.lastFailed() == null ? "" : DTF.format(s.lastFailed())).setHeader("Last failed").setAutoWidth(true);
        fileGrid.setHeight("260px");
        fileGrid.setColumnReorderingAllowed(true);
        fileGrid.getColumns().forEach(c -> c.setResizable(true));
        GridLayoutMemory.remember(fileGrid, "history-files");
        fileSection.setOpened(true);
        fileSection.setWidthFull();
    }

    private void buildStats() {
        stats.removeAll();
        history.getStats().forEach(s -> {
            final int pct = s.total() == 0 ? 0 : Math.round(100f * s.finished() / s.total());
            final String costText = config.costPerKg() > 0 && s.totalGrams() > 0
                    ? " • %.0fg / %s".formatted(s.totalGrams(), cost(s.totalGrams()))
                    : "";
            final Span badge = new Span("%s: %d prints • %d%% success • %s total%s".formatted(
                    s.printer(), s.total(), pct, formatTime(Duration.ofSeconds(s.totalSeconds())), costText));
            badge.addClassName("history-badge");
            stats.add(badge);
        });
    }

    private void configureGrid() {
        setupColumn("Printer", PrintJob::printer)
                .setSortable(true).setComparator(Comparator.comparing(PrintJob::printer, String.CASE_INSENSITIVE_ORDER));
        setupColumn("File", PrintJob::file)
                .setSortable(true).setComparator(Comparator.comparing(PrintJob::file, String.CASE_INSENSITIVE_ORDER))
                .setFlexGrow(3)
                .setTooltipGenerator(PrintJob::file);
        final Grid.Column<PrintJob> colStarted = setupColumn("Started", j -> DTF.format(j.started()))
                .setSortable(true).setComparator(Comparator.comparingLong((PrintJob j) -> j.started().toEpochSecond()));
        setupColumn("Duration", j -> formatTime(Duration.ofSeconds(j.durationSeconds())))
                .setSortable(true).setComparator(Comparator.comparingLong(PrintJob::durationSeconds));
        if (config.costPerKg() > 0) {
            setupColumn("Weight", j -> j.grams() > 0 ? "%.1fg".formatted(j.grams()) : "--")
                    .setSortable(true).setComparator(Comparator.comparingDouble(PrintJob::grams));
            setupColumn("Cost", j -> cost(j.grams()))
                    .setSortable(true).setComparator(Comparator.comparingDouble(PrintJob::grams));
        }
        grid.addComponentColumn(j -> {
            final Span result = new Span(j.result());
            result.addClassName(switch (j.result()) {
                case "Finished" ->
                    LumoUtility.TextColor.SUCCESS;
                case "Failed" ->
                    LumoUtility.TextColor.ERROR;
                default ->
                    LumoUtility.TextColor.SECONDARY;
            });
            return result;
        }).setHeader("Result");
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.setColumnReorderingAllowed(true);
        GridLayoutMemory.remember(grid, "history");
        grid.sort(GridSortOrder.desc(colStarted).build());
    }

    @Override
    public void updateHeader(final HasComponents component) {
        component.add(new Button("Refresh", new Icon(VaadinIcon.REFRESH), l -> refreshItems()));
    }

}
