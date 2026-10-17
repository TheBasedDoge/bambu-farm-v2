package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.AutoQueueService;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOAuthService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOAuthService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.OrderBoardService;
import com.tfyre.bambu.printer.OrderBoardService.Stage;
import com.tfyre.bambu.printer.OrderRef;
import com.tfyre.bambu.view.batchprint.Plate;
import com.tfyre.bambu.view.batchprint.ProjectFile;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orders - every open Etsy and eBay order in one list, in the order they need dealing with: a failed part or
 * nothing queued first, then ready to ship, then the ones still printing.
 * <p>
 * Replaces the two Sales Orders pages as the place to work from. Those pages still exist (linked from here as
 * "connection") because connecting, disconnecting and the Etsy shop-id lookup live there. Everything else -
 * the buyer note, progress, the mapping editor and manual queueing for each line - is here.
 */
@Route(value = "orders", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Orders")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class OrdersView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");
    private static final String OK = "var(--lumo-success-color)";
    private static final String BAD = "var(--lumo-error-color)";
    private static final String WARN = "var(--lumo-warning-color, #e8a33d)";
    private static final String INFO = "var(--lumo-primary-color)";
    private static final String DIM = "var(--lumo-secondary-text-color)";

    @Inject
    BambuConfig config;
    @Inject
    EtsyOAuthService etsyOauth;
    @Inject
    EbayOAuthService ebayOauth;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;
    @Inject
    BambuPrinters printers;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    AutoQueueService autoQueue;
    @Inject
    OrderBoardService board;

    /** Which of the filter chips is on. */
    private enum Filter {
        ALL("All"), NEEDS_YOU("Needs you"), READY("Ready to ship"), PRINTING("Printing"), NOT_QUEUED("Not queued");

        private final String label;

        Filter(final String label) {
            this.label = label;
        }

        boolean accepts(final Stage stage) {
            return switch (this) {
                case ALL -> true;
                case NEEDS_YOU -> stage.needsYou();
                case READY -> stage == Stage.READY;
                case PRINTING -> stage == Stage.PRINTING || stage == Stage.WAITING;
                case NOT_QUEUED -> stage == Stage.NOT_QUEUED;
            };
        }
    }

    /** One order, reduced to what the list needs to sort, filter and draw it. */
    private record Row(String market, Instant created, OrderBoardService.Entry entry, Component card) {

        /** Needs you, ready to ship, in progress - the three headings, in that order. */
        int group() {
            return entry.stage().needsYou() ? 0 : entry.stage() == Stage.READY ? 1 : 2;
        }
    }

    private final HorizontalLayout chips = new HorizontalLayout();
    private final Div status = new Div();
    private final Div list = new Div();
    private Filter filter = Filter.ALL;
    private boolean showEtsy = true;
    private boolean showEbay = true;
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        render();
        // The scheduled polls run every ten minutes; without this the first page load after a restart shows
        // nothing until one happens to fire.
        final boolean etsyStale = etsyOauth.isConnected() && etsyPolling.getLastPolled().isEmpty();
        final boolean ebayStale = ebayOauth.isConnected() && ebayPolling.getLastPolled().isEmpty();
        if (etsyStale || ebayStale) {
            refreshInBackground(null);
        }
    }

    private void build() {
        setPadding(true);
        setSpacing(true);
        setMaxWidth("1100px");

        final Button refresh = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
        refresh.addClickListener(e -> {
            refresh.setEnabled(false);
            refreshInBackground(refresh);
        });
        final Checkbox auto = new Checkbox("Auto-queue new orders");
        auto.setValue(autoQueue.isEnabled());
        auto.setTooltipText("Queue new, fully mapped orders by themselves on a printer with the right filament");
        auto.addValueChangeListener(e -> {
            autoQueue.setEnabled(Boolean.TRUE.equals(e.getValue()));
            showNotification("Auto-queue " + (autoQueue.isEnabled() ? "on" : "off"));
        });
        final RouterLink etsyLink = new RouterLink("Etsy connection", EtsyOrdersView.class);
        final RouterLink ebayLink = new RouterLink("eBay connection", EbayOrdersView.class);
        final HorizontalLayout top = new HorizontalLayout(new H3("Orders"), refresh, auto, etsyLink, ebayLink);
        top.setAlignItems(FlexComponent.Alignment.BASELINE);
        top.getStyle().set("flex-wrap", "wrap");

        chips.getStyle().set("flex-wrap", "wrap");
        chips.setAlignItems(FlexComponent.Alignment.CENTER);
        status.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        list.setWidthFull();
        list.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-s)");
        add(top, chips, legend(), status, list);
    }

    private void refreshInBackground(final Button toReEnable) {
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        new Thread(() -> {
            if (etsyOauth.isConnected()) {
                etsyPolling.refresh();
            }
            if (ebayOauth.isConnected()) {
                ebayPolling.refresh();
            }
            ui.ifPresent(u -> u.access(() -> {
                if (toReEnable != null) {
                    toReEnable.setEnabled(true);
                    showNotification("Orders refreshed");
                }
                render();
            }));
        }).start();
    }

    private static Component legend() {
        final HorizontalLayout key = new HorizontalLayout(swatch(OK, "printed"), swatch(INFO, "from stock"),
                swatch("var(--lumo-success-color-50pct)", "printing now"));
        key.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-xs)").set("flex-wrap", "wrap");
        return key;
    }

    private static Component swatch(final String color, final String text) {
        final Span bar = new Span();
        bar.getStyle().set("display", "inline-block").set("width", "14px").set("height", "4px").set("border-radius", "2px")
                .set("background", color).set("margin-right", "5px").set("vertical-align", "middle");
        return new Span(bar, new Span(text));
    }

    // -------------------------------------------------------------------------
    // the list
    // -------------------------------------------------------------------------
    private void render() {
        final List<Row> rows = new ArrayList<>();
        if (etsyOauth.isConnected()) {
            etsyPolling.getReceipts().forEach(r -> rows.add(etsyRow(r)));
        }
        if (ebayOauth.isConnected()) {
            ebayPolling.getOrders().forEach(o -> rows.add(ebayRow(o)));
        }

        renderChips(rows);
        renderStatus();

        list.removeAll();
        final List<Row> shown = rows.stream()
                .filter(r -> "etsy".equals(r.market()) ? showEtsy : showEbay)
                .filter(r -> filter.accepts(r.entry().stage()))
                .sorted(Comparator.comparingInt(Row::group)
                        .thenComparingInt((Row r) -> r.entry().stage().ordinal())
                        .thenComparing(Row::created))
                .toList();
        if (shown.isEmpty()) {
            final Span none = new Span(rows.isEmpty() ? "No open orders." : "No orders match this filter.");
            none.getStyle().setColor(DIM);
            list.add(none);
            return;
        }
        int group = -1;
        for (final Row row : shown) {
            if (row.group() != group) {
                group = row.group();
                final Span head = new Span(group == 0 ? "Needs you" : group == 1 ? "Ready to ship" : "Printing");
                head.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-xs)").set("text-transform", "uppercase")
                        .set("letter-spacing", "0.09em").set("font-weight", "600").set("margin-top", "var(--lumo-space-s)");
                list.add(head);
            }
            list.add(row.card());
        }
    }

    private void renderChips(final List<Row> rows) {
        chips.removeAll();
        for (final Filter f : Filter.values()) {
            final long n = rows.stream().filter(r -> f.accepts(r.entry().stage())).count();
            final Button chip = new Button("%s  %d".formatted(f.label, n));
            chip.addThemeVariants(ButtonVariant.LUMO_SMALL);
            if (f == filter) {
                chip.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            }
            chip.addClickListener(e -> {
                filter = f;
                render();
            });
            chips.add(chip);
        }
        final long etsy = rows.stream().filter(r -> "etsy".equals(r.market())).count();
        final Checkbox etsyBox = new Checkbox("Etsy  %d".formatted(etsy), showEtsy);
        etsyBox.addValueChangeListener(e -> {
            showEtsy = Boolean.TRUE.equals(e.getValue());
            render();
        });
        final Checkbox ebayBox = new Checkbox("eBay  %d".formatted(rows.size() - etsy), showEbay);
        ebayBox.addValueChangeListener(e -> {
            showEbay = Boolean.TRUE.equals(e.getValue());
            render();
        });
        chips.add(etsyBox, ebayBox);
    }

    private void renderStatus() {
        status.removeAll();
        marketStatus("Etsy", etsyOauth.isConfigured(), etsyOauth.isConnected(), etsyPolling.getLastPolled(), etsyPolling.getLastError());
        marketStatus("eBay", ebayOauth.isConfigured(), ebayOauth.isConnected(), ebayPolling.getLastPolled(), ebayPolling.getLastError());
    }

    private void marketStatus(final String name, final boolean configured, final boolean connected,
            final Optional<Instant> lastPolled, final Optional<String> lastError) {
        final Span line = new Span();
        if (!configured) {
            line.setText("%s is not set up.".formatted(name));
        } else if (!connected) {
            line.setText("%s is not connected - connect it from the %s connection page.".formatted(name, name));
            line.getStyle().setColor("var(--lumo-error-text-color)");
        } else if (lastError.isPresent()) {
            line.setText("%s: last check failed - %s".formatted(name, lastError.get()));
            line.getStyle().setColor("var(--lumo-error-text-color)");
        } else {
            line.setText("%s checked %s".formatted(name, lastPolled
                    .map(t -> DATE_FMT.format(t.atZone(ZoneId.systemDefault()))).orElse("not yet")));
        }
        status.add(new Div(line));
    }

    // -------------------------------------------------------------------------
    // one order
    // -------------------------------------------------------------------------
    private Row etsyRow(final EtsyApiClient.Receipt r) {
        final String id = String.valueOf(r.receiptId());
        final OrderBoardService.Entry entry = board.entry("etsy", id, OrderBoardService.label(r));
        final boolean personalized = r.transactions().stream().anyMatch(t -> t.personalization().isPresent());
        final List<Component> lines = r.transactions().stream().map(t -> etsyLine(t, id, entry)).collect(Collectors.toList());
        final Component card = card("Etsy", "#f1641e", "#" + id, r.buyerName(), r.createTimestamp(), r.buyerNote(), entry,
                personalized, config.etsy().orderUrl().replace("{id}", id), lines, () -> etsyPolling.dismiss(r.receiptId()));
        return new Row("etsy", r.createTimestamp(), entry, card);
    }

    private Row ebayRow(final EbayApiClient.Order o) {
        final OrderBoardService.Entry entry = board.entry("ebay", o.orderId(), OrderBoardService.label(o));
        final boolean personalized = o.lineItems().stream().anyMatch(li -> li.personalization().isPresent());
        final List<Component> lines = o.lineItems().stream().map(li -> ebayLine(li, o.orderId(), entry)).collect(Collectors.toList());
        final Component card = card("eBay", "var(--lumo-primary-text-color)", o.orderId(), o.buyerUsername(), o.creationDate(),
                o.buyerNote(), entry, personalized, config.ebay().orderUrl().replace("{id}", o.orderId()), lines,
                () -> ebayPolling.dismiss(o.orderId()));
        return new Row("ebay", o.creationDate(), entry, card);
    }

    private Component card(final String market, final String marketColor, final String number, final String buyer,
            final Instant created, final String note, final OrderBoardService.Entry e, final boolean personalized,
            final String url, final List<Component> lines, final Runnable dismiss) {
        final Stage stage = e.stage();
        final String tone = stage == Stage.FAILED ? BAD : stage == Stage.NOT_QUEUED ? WARN : stage == Stage.READY ? OK : null;

        final Div card = new Div();
        card.getStyle().set("background", "var(--lumo-contrast-5pct)").set("border-radius", "10px")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)").set("display", "flex")
                .set("flex-direction", "column").set("gap", "var(--lumo-space-xs)").set("min-width", "0");
        if (tone != null) {
            card.getStyle().set("box-shadow", "inset 3px 0 0 " + tone);
        }

        final Span badge = new Span(market);
        badge.getStyle().setColor(marketColor).set("border", "1px solid currentColor").set("border-radius", "4px")
                .set("padding", "0 7px").set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "700");
        final Span who = new Span("%s · %s".formatted(number, buyer));
        who.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        final Span when = new Span(DATE_FMT.format(created.atZone(ZoneId.systemDefault())));
        when.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Span state = new Span(switch (stage) {
            case FAILED -> "%d part%s failed".formatted(e.abandoned(), e.abandoned() == 1 ? "" : "s");
            case NOT_QUEUED -> personalized ? "Not queued · personalized" : "Not queued";
            case READY -> "Ready to ship";
            case PRINTING -> "Printing now";
            case WAITING -> "Waiting for a printer";
        });
        state.getStyle().set("margin-left", "auto").set("font-weight", "600").set("white-space", "nowrap");
        if (tone != null) {
            state.getStyle().setColor(stage == Stage.FAILED ? "var(--lumo-error-text-color)"
                    : stage == Stage.READY ? "var(--lumo-success-text-color)" : WARN);
        }
        final Div head = new Div(badge, who, when, state);
        head.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("align-items", "baseline").set("gap", "4px 12px");
        card.add(head);

        if (note != null && !note.isBlank()) {
            final Div noteBox = new Div(new Span("Buyer note: " + note));
            noteBox.getStyle().set("background", "var(--lumo-warning-color-10pct, rgba(232,163,61,0.12))")
                    .set("border-left", "3px solid " + WARN).set("border-radius", "6px")
                    .set("padding", "var(--lumo-space-xs) var(--lumo-space-s)").set("white-space", "pre-wrap");
            card.add(noteBox);
        }

        card.add(progress(e));
        lines.forEach(card::add);

        final Anchor open = new Anchor(url, "Open on " + market);
        open.setTarget("_blank");
        final Button hide = new Button("Dismiss", new Icon(VaadinIcon.EYE_SLASH), ev -> {
            dismiss.run();
            render();
        });
        hide.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        hide.setTooltipText("Hide this order here. It does not mark it shipped on " + market + ".");
        final HorizontalLayout actions = new HorizontalLayout(open, hide);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);
        card.add(actions);
        return card;
    }

    /** The bar and the sentence under an order's heading: printed, from stock, printing now, out of the total. */
    private static Component progress(final OrderBoardService.Entry e) {
        final int total = Math.max(1, e.expected() + e.fromStock());
        final Div bar = new Div();
        bar.getStyle().set("display", "flex").set("height", "4px").set("border-radius", "2px").set("overflow", "hidden")
                .set("background", "var(--lumo-contrast-10pct)");
        bar.add(segment(OK, e.printed(), total), segment(INFO, e.fromStock(), total),
                segment("var(--lumo-success-color-50pct)", Math.min(e.printing(), Math.max(0, e.expected() - e.printed())), total));

        final List<String> parts = new ArrayList<>();
        if (e.expected() > 0) {
            parts.add("%d of %d printed".formatted(e.printed(), e.expected()));
        }
        if (e.fromStock() > 0) {
            parts.add("%d from stock".formatted(e.fromStock()));
        }
        if (e.printing() > 0) {
            parts.add("%d printing now".formatted(e.printing()));
        }
        if (e.waiting() > 0) {
            parts.add("%d waiting for a printer".formatted(e.waiting()));
        }
        final Span text = new Span(parts.isEmpty() ? "Nothing queued or printed yet" : String.join(" · ", parts));
        text.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Div box = new Div(bar, text);
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "3px");
        return box;
    }

    private static Component segment(final String color, final int count, final int total) {
        final Div d = new Div();
        d.getStyle().set("background", color).set("width", (100.0 * Math.max(0, count) / total) + "%");
        return d;
    }

    // -------------------------------------------------------------------------
    // one line of an order
    // -------------------------------------------------------------------------
    private Component line(final String title, final String detail, final Optional<String> personalization,
            final MappingPartsPanel panel, final boolean open) {
        final Div row = new Div();
        row.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)").set("padding-top", "var(--lumo-space-xs)")
                .set("min-width", "0");
        final Span name = new Span(title);
        name.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        row.add(new Div(name));
        if (detail != null && !detail.isBlank()) {
            final Span d = new Span(detail);
            d.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
            row.add(new Div(d));
        }
        personalization.ifPresent(p -> {
            final Span ps = new Span("Personalization: " + p);
            ps.getStyle().set("font-style", "italic");
            row.add(new Div(ps));
        });
        // The mapping editor and manual queueing, exactly as on the marketplace pages. Folded away unless the
        // order needs you, because for an order that is printing it is just noise.
        final Details details = new Details("Print jobs and mapping", panel);
        details.setOpened(open);
        details.setWidthFull();
        row.add(details);
        return row;
    }

    private List<String> printerNames() {
        return printers.getPrintersDetail().stream().map(BambuPrinters.PrinterDetail::name)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private Component etsyLine(final EtsyApiClient.Transaction t, final String orderId, final OrderBoardService.Entry entry) {
        final List<MappingPart> parts = etsyMappings.find(t.listingId(), t.variations())
                .map(EtsyMappingService.MappingEntry::parts).orElse(List.of());
        final MappingPartsPanel panel = new MappingPartsPanel(queuer::listLibraryFiles, this::loadPlateIds,
                queuer::listProjects, queuer::listProjectFiles, printerNames(), parts, t.quantity(),
                saved -> {
                    etsyMappings.set(t.listingId(), t.variations(), new EtsyMappingService.MappingEntry(saved));
                    showNotification("Mapping saved for listing %d".formatted(t.listingId()));
                },
                (toQueue, selectedPrinters) -> queued(queuer.queue(toQueue, t.quantity(), selectedPrinters,
                        new OrderRef("etsy", orderId, "Etsy order #" + orderId))));
        final String variations = t.variations().stream()
                .map(v -> com.tfyre.bambu.printer.HtmlText.plain(v.propertyName() + ": " + v.value()))
                .collect(Collectors.joining(", "));
        return line("%d× %s".formatted(t.quantity(), t.title()), variations, t.personalization(), panel,
                entry.stage().needsYou());
    }

    private Component ebayLine(final EbayApiClient.LineItem li, final String orderId, final OrderBoardService.Entry entry) {
        final String listingKey = li.listingKey();
        final List<MappingPart> parts = ebayMappings.find(listingKey, li.variationAspects())
                .map(EbayMappingService.MappingEntry::parts).orElse(List.of());
        final MappingPartsPanel panel = new MappingPartsPanel(queuer::listLibraryFiles, this::loadPlateIds,
                queuer::listProjects, queuer::listProjectFiles, printerNames(), parts, li.quantity(),
                saved -> {
                    if (listingKey == null || listingKey.isBlank()) {
                        showError("No SKU or item id to map against for this line");
                        return;
                    }
                    ebayMappings.set(listingKey, li.variationAspects(), new EbayMappingService.MappingEntry(saved));
                    showNotification("Mapping saved for %s".formatted(listingKey));
                },
                (toQueue, selectedPrinters) -> queued(queuer.queue(toQueue, li.quantity(), selectedPrinters,
                        new OrderRef("ebay", orderId, "eBay order " + orderId))));
        final List<String> detail = new ArrayList<>();
        if (li.sku() != null && !li.sku().isBlank()) {
            detail.add("SKU: " + li.sku());
        }
        li.variationAspects().forEach(v -> detail.add(v.propertyName() + ": " + v.value()));
        return line("%d× %s".formatted(li.quantity(), li.title()), String.join(", ", detail), li.personalization(), panel,
                entry.stage().needsYou());
    }

    private void queued(final GcodeMappingQueuer.QueueResult result) {
        if (result.totalQueued() > 0) {
            showNotification("Queued %d job(s)".formatted(result.totalQueued()));
        }
        result.errors().forEach(this::showError);
        render();
    }

    private List<Integer> loadPlateIds(final String filename) {
        final Path file = Path.of(config.batchPrint().library()).resolve(filename);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return projectFileInstance.get().setup(filename, file.toFile()).getPlates().stream().map(Plate::plateId).toList();
        } catch (Exception ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }
}
