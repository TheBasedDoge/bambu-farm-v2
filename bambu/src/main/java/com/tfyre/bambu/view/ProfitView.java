package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.PrintHistoryService;
import com.tfyre.bambu.printer.SalesService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profit - what each listing has actually earned, set against what it cost to make and how long it held a printer.
 * <p>
 * One row per listing. Income is what buyers paid (items plus the shipping they were charged). Out of it come
 * the marketplace's fees, a label, and the filament; print time is shown beside the result so the last column
 * can answer the real question for a farm with a fixed number of printers - which listings earn the most per
 * hour of printer.
 * <p>
 * Filament and print time are averages over finished prints of the mapped files in print history, so a listing
 * that has never been printed by the app shows income and fees but no cost.
 */
@Route(value = "profit", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Profit")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class ProfitView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");
    private static final List<String> PERIODS = List.of("Last 30 days", "Last 90 days", "Everything on record");

    @Inject
    SalesService salesService;
    @Inject
    PrintHistoryService history;
    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;
    @Inject
    BambuConfig config;

    /** Average of the finished prints of one file. */
    private record FileCost(double grams, double seconds) {
    }

    /** One listing's totals for the chosen period. */
    private static final class Row {

        final String market;
        String title = "";
        int units;
        int orders;
        double income;
        double fees;
        double labels;
        double filament;
        double seconds;
        /** At least one sale had a part with no finished print on record, so the costs are understated. */
        boolean incomplete;
        /** Latest sale seen, so the title shown is the listing's current one. */
        String latest = "";

        Row(final String market) {
            this.market = market;
        }

        double profit() {
            return income - fees - labels - filament;
        }

        double hours() {
            return seconds / 3600.0;
        }
    }

    private final Select<String> period = new Select<>();
    private final Span summary = new Span();
    private final Span status = new Span();
    private final Div notes = new Div();
    private final Grid<Row> grid = new Grid<>();
    private final NumberField etsyPercent = new NumberField("Etsy fees, % of items + shipping");
    private final NumberField etsyPerOrder = new NumberField("Etsy fees, fixed per order");
    private final NumberField labelCost = new NumberField("Shipping label, per order");
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        loadSettings();
        render();
    }

    private void build() {
        setSizeFull();
        period.setItems(PERIODS);
        period.setValue(PERIODS.get(1));
        period.addValueChangeListener(e -> render());
        final Button sync = new Button("Refresh sales", new Icon(VaadinIcon.REFRESH), e -> {
            salesService.sync();
            render();
        });
        sync.setTooltipText("Pull recent orders from Etsy and eBay now (this happens by itself every 6 hours)");
        status.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        final HorizontalLayout top = new HorizontalLayout(new H3("Profit"), period, sync, status);
        top.setAlignItems(FlexComponent.Alignment.BASELINE);
        top.getStyle().set("flex-wrap", "wrap");
        summary.getStyle().set("font-size", "var(--lumo-font-size-l)");
        notes.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.addColumn(r -> ("etsy".equals(r.market) ? "Etsy · " : "eBay · ") + r.title).setHeader("Listing")
                .setFlexGrow(4).setSortable(true).setResizable(true)
                .setComparator(Comparator.comparing((Row r) -> r.title, String.CASE_INSENSITIVE_ORDER))
                .setTooltipGenerator(r -> r.title);
        number("Sold", r -> String.valueOf(r.units), r -> r.units);
        number("Income", r -> money(r.income), r -> r.income);
        number("Fees", r -> money(r.fees), r -> r.fees);
        number("Labels", r -> money(r.labels), r -> r.labels);
        number("Filament", r -> r.filament > 0 ? money(r.filament) + (r.incomplete ? " *" : "") : "--", r -> r.filament);
        number("Print time", r -> r.seconds > 0 ? "%.1f h%s".formatted(r.hours(), r.incomplete ? " *" : "") : "--", r -> r.seconds);
        number("Profit", r -> money(r.profit()), Row::profit);
        number("Per unit", r -> r.units > 0 ? money(r.profit() / r.units) : "--", r -> r.units > 0 ? r.profit() / r.units : 0);
        number("Per printer hour", r -> r.seconds > 0 ? money(r.profit() / r.hours()) : "--",
                r -> r.seconds > 0 ? r.profit() / r.hours() : Double.NEGATIVE_INFINITY);
        grid.setSizeFull();

        for (final NumberField f : List.of(etsyPercent, etsyPerOrder, labelCost)) {
            f.setMin(0);
            f.setStep(0.01);
            f.setWidth("230px");
        }
        labelCost.setHelperText("Labels are bought outside the app, so this is your average");
        final Button save = new Button("Save", e -> {
            salesService.setSettings(new SalesService.Settings(value(etsyPercent), value(etsyPerOrder), value(labelCost)));
            render();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        final HorizontalLayout fields = new HorizontalLayout(etsyPercent, etsyPerOrder, labelCost, save);
        fields.setAlignItems(FlexComponent.Alignment.BASELINE);
        fields.getStyle().set("flex-wrap", "wrap");
        final Details assumptions = new Details("Assumptions", fields);
        assumptions.setWidthFull();

        add(top, summary, grid, notes, assumptions);
        setFlexGrow(1, grid);
    }

    private void number(final String header, final com.vaadin.flow.function.ValueProvider<Row, String> text,
            final java.util.function.ToDoubleFunction<Row> sortBy) {
        grid.addColumn(text).setHeader(header).setAutoWidth(true).setFlexGrow(0).setSortable(true)
                .setTextAlign(ColumnTextAlign.END).setComparator(Comparator.comparingDouble(sortBy));
    }

    private static double value(final NumberField f) {
        return f.getValue() == null ? 0 : Math.max(0, f.getValue());
    }

    private void loadSettings() {
        final SalesService.Settings s = salesService.getSettings();
        etsyPercent.setValue(s.etsyFeePercent());
        etsyPerOrder.setValue(s.etsyFeePerOrder());
        labelCost.setValue(s.labelCost());
    }

    private String money(final double amount) {
        return (amount < 0 ? "-" : "") + config.currencySymbol() + String.format(Locale.ENGLISH, "%,.2f", Math.abs(amount));
    }

    private static String baseName(final String path) {
        if (path == null) {
            return "";
        }
        final String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ENGLISH);
    }

    /** Average filament and duration per file, from the prints that finished. */
    private Map<String, FileCost> fileCosts() {
        final Map<String, double[]> sums = new HashMap<>(); // grams, prints with grams, seconds, prints
        for (final PrintHistoryService.PrintJob j : history.getJobs()) {
            if (!"Finished".equals(j.result()) || j.file() == null || j.file().isBlank()) {
                continue;
            }
            final double[] s = sums.computeIfAbsent(baseName(j.file()), k -> new double[4]);
            if (j.grams() > 0) {
                s[0] += j.grams();
                s[1]++;
            }
            s[2] += j.durationSeconds();
            s[3]++;
        }
        final Map<String, FileCost> out = new HashMap<>();
        sums.forEach((file, s) -> out.put(file, new FileCost(s[1] > 0 ? s[0] / s[1] : 0, s[3] > 0 ? s[2] / s[3] : 0)));
        return out;
    }

    private List<MappingPart> partsOf(final SalesService.Sale sale) {
        if (sale.mappingKey() == null || sale.mappingKey().isBlank()) {
            return List.of();
        }
        if ("etsy".equals(sale.market())) {
            final EtsyMappingService.MappingEntry e = etsyMappings.entries().get(sale.mappingKey());
            return e == null || e.parts() == null ? List.of() : e.parts();
        }
        final EbayMappingService.MappingEntry e = ebayMappings.entries().get(sale.mappingKey());
        return e == null || e.parts() == null ? List.of() : e.parts();
    }

    private void render() {
        final SalesService.Settings settings = salesService.getSettings();
        final int index = Math.max(0, PERIODS.indexOf(period.getValue()));
        final Instant cutoff = index == 0 ? Instant.now().minus(30, ChronoUnit.DAYS)
                : index == 1 ? Instant.now().minus(90, ChronoUnit.DAYS) : Instant.EPOCH;
        final Map<String, FileCost> costs = fileCosts();
        final double perGram = config.costPerKg() / 1000.0;

        final Map<String, Row> rows = new LinkedHashMap<>();
        final java.util.Set<String> orders = new java.util.HashSet<>();
        int cancelled = 0;
        for (final SalesService.Sale sale : salesService.getSales()) {
            final Instant when;
            try {
                when = Instant.parse(sale.date());
            } catch (RuntimeException ex) {
                continue;
            }
            if (when.isBefore(cutoff)) {
                continue;
            }
            if (sale.cancelled()) {
                cancelled++;
                continue;
            }
            final Row row = rows.computeIfAbsent(sale.market() + "|" + sale.listingKey(), k -> new Row(sale.market()));
            if (sale.date().compareTo(row.latest) > 0) {
                row.latest = sale.date();
                row.title = sale.title();
            }
            row.units += sale.quantity();
            row.income += sale.revenue() + sale.shippingShare();
            row.fees += "etsy".equals(sale.market())
                    ? settings.etsyFeePercent() / 100.0 * (sale.revenue() + sale.shippingShare())
                    + settings.etsyFeePerOrder() * sale.orderShare()
                    : sale.marketFee();
            row.labels += settings.labelCost() * sale.orderShare();
            if (orders.add(sale.market() + "|" + sale.orderId())) {
                row.orders++;
            }
            final List<MappingPart> parts = partsOf(sale);
            if (parts.isEmpty()) {
                row.incomplete = true;
            }
            for (final MappingPart part : parts) {
                final FileCost cost = costs.get(baseName(part.path()));
                if (cost == null) {
                    row.incomplete = true;
                    continue;
                }
                final int pieces = Math.max(1, sale.quantity()) * part.copiesPerUnit();
                row.filament += pieces * cost.grams() * perGram;
                row.seconds += pieces * cost.seconds();
            }
        }
        final List<Row> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparingDouble(Row::profit).reversed());
        grid.setItems(list);

        final double income = list.stream().mapToDouble(r -> r.income).sum();
        final double profit = list.stream().mapToDouble(Row::profit).sum();
        final double hours = list.stream().mapToDouble(Row::hours).sum();
        final int units = list.stream().mapToInt(r -> r.units).sum();
        summary.setText(list.isEmpty() ? "No sales on record for this period yet."
                : "%d sold over %d order%s · %s income · %s profit%s".formatted(units, orders.size(),
                        orders.size() == 1 ? "" : "s", money(income), money(profit),
                        hours > 0 ? " · %s per printer hour".formatted(money(profit / hours)) : ""));

        status.setText(salesService.getLastError().map(e -> "Last refresh had a problem: " + e)
                .orElseGet(() -> salesService.getLastSync()
                        .map(t -> "Sales refreshed " + TIME_FMT.format(t.atZone(ZoneId.systemDefault())))
                        .orElse("Sales have not been pulled yet - press Refresh sales")));
        status.getStyle().setColor(salesService.getLastError().isPresent() ? "var(--lumo-error-text-color)"
                : "var(--lumo-secondary-text-color)");

        notes.removeAll();
        final List<String> lines = new ArrayList<>();
        lines.add("Income is what buyers paid for items plus the shipping they were charged; tax is left out. "
                + "eBay fees are the ones eBay reports (blank until it has assessed an order). Etsy does not report "
                + "fees, so they are worked out from the percentages under Assumptions; Offsite Ads fees are not included.");
        lines.add("Filament and print time are averages of the finished prints of each mapped file. "
                + "* = at least one part has no finished print on record, or the listing is not mapped, so its cost is understated. "
                + "Failed prints, electricity and packaging are not counted.");
        if (config.costPerKg() <= 0) {
            lines.add("Filament shows as free because no filament price is set - add bambu.cost-per-kg=<price per kg> to data/.env and restart.");
        }
        if (cancelled > 0) {
            lines.add("%d cancelled or fully refunded order line%s left out.".formatted(cancelled, cancelled == 1 ? "" : "s"));
        }
        lines.forEach(l -> notes.add(new Div(new Span(l))));
    }
}
