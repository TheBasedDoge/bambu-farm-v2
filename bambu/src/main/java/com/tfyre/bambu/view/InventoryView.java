package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.MarketListingCache;
import com.tfyre.bambu.printer.OrderTrackingService;
import com.tfyre.bambu.printer.StockService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inventory - what is on the shelf, one row per printed part, and the only place stock is edited.
 * <p>
 * A part is a print file + plate + material + colour ({@link StockService#partKey}). Each row shows the pieces
 * on hand with - and +, every Etsy and eBay listing or variation that draws on the part and how many each sale
 * takes, what orders that have not been queued yet need, and - opened with the arrow - the history of every
 * change. The grouping across marketplaces is not configured anywhere: two listings share a row because their
 * mappings print the same file.
 */
@Route(value = "inventory", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Inventory")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class InventoryView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");
    private static final String GRID = "150px minmax(0, 1.1fr) minmax(0, 1.6fr) 200px 36px";

    @Inject
    StockService stockService;
    @Inject
    EtsyMappingService etsyMapping;
    @Inject
    EbayMappingService ebayMapping;
    @Inject
    MarketListingCache listingCache;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    OrderTrackingService tracking;

    /** One listing or variation that sells a part. */
    private record Seller(String market, String title, String variation, int takes) {
    }

    /** Everything one row shows. Mutable while it is being assembled, read-only afterwards. */
    private static final class PartRow {

        final String key;
        final StockService.PartId id;
        final List<Seller> sellers = new ArrayList<>();
        int onHand;
        /** Pieces needed by open orders that nothing has been queued for yet. */
        int needed;

        PartRow(final String key) {
            this.key = key;
            this.id = StockService.describe(key);
        }

        int toPrint() {
            return Math.max(0, needed - onHand);
        }
    }

    private final TextField search = new TextField();
    private final Checkbox inStockOnly = new Checkbox("In stock");
    private final Checkbox neededOnly = new Checkbox("Needed now");
    private final Span summary = new Span();
    private final Div list = new Div();
    private final Set<String> open = new HashSet<>();
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        render();
    }

    private void build() {
        addClassName("inventory-view");
        setWidthFull();

        search.setPlaceholder("Search part or listing");
        search.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        search.setClearButtonVisible(true);
        search.setWidth("260px");
        search.setValueChangeMode(ValueChangeMode.TIMEOUT);
        search.addValueChangeListener(e -> render());
        inStockOnly.addValueChangeListener(e -> render());
        neededOnly.addValueChangeListener(e -> render());
        summary.getStyle().setColor("var(--lumo-secondary-text-color)");

        final HorizontalLayout top = new HorizontalLayout(new H3("Inventory"), summary);
        top.setAlignItems(FlexComponent.Alignment.BASELINE);
        top.getStyle().set("flex-wrap", "wrap");
        final HorizontalLayout tools = new HorizontalLayout(search, inStockOnly, neededOnly);
        tools.setAlignItems(FlexComponent.Alignment.CENTER);
        tools.getStyle().set("flex-wrap", "wrap");

        final Span hint = new Span("Counted in printed pieces. Listings share a row when their mappings print the same "
                + "file in the same material. New orders take from here first and print only what is missing.");
        hint.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");

        list.setWidthFull();
        list.getStyle().set("overflow-x", "auto");
        add(top, tools, hint, list);
    }

    // -------------------------------------------------------------------------
    // data
    // -------------------------------------------------------------------------
    private List<PartRow> collect() {
        final Map<String, PartRow> rows = new LinkedHashMap<>();
        final Map<String, String> etsyTitles = new HashMap<>();
        listingCache.getEtsy().forEach(l -> etsyTitles.put(String.valueOf(l.listingId()), l.title()));
        final Map<String, String> ebayTitles = new HashMap<>();
        listingCache.getEbay().forEach(l -> ebayTitles.put(l.listingKey(), l.title()));

        etsyMapping.entries().forEach((storageKey, entry) -> addSellers(rows, "etsy", storageKey, entry.parts(), etsyTitles));
        ebayMapping.entries().forEach((storageKey, entry) -> addSellers(rows, "ebay", storageKey, entry.parts(), ebayTitles));
        // Pieces on hand for a file no mapping uses any more still belong on this page.
        stockService.entries().keySet().forEach(k -> rows.computeIfAbsent(k, PartRow::new));
        rows.values().forEach(r -> r.onHand = stockService.get(r.key));

        // What orders that have NOT been queued still need. Queued orders are excluded on purpose: their
        // pieces were either taken from here or are being printed, and counting them again would ask for
        // every part twice.
        for (final EtsyApiClient.Receipt r : etsyPolling.getReceipts()) {
            if (tracking.queuedAt("etsy", String.valueOf(r.receiptId())).isPresent()) {
                continue;
            }
            for (final EtsyApiClient.Transaction t : r.transactions()) {
                etsyMapping.find(t.listingId(), t.variations()).map(EtsyMappingService.MappingEntry::parts)
                        .ifPresent(parts -> addNeed(rows, parts, t.quantity()));
            }
        }
        for (final EbayApiClient.Order o : ebayPolling.getOrders()) {
            if (tracking.queuedAt("ebay", o.orderId()).isPresent()) {
                continue;
            }
            for (final EbayApiClient.LineItem li : o.lineItems()) {
                ebayMapping.find(li.listingKey(), li.variationAspects()).map(EbayMappingService.MappingEntry::parts)
                        .ifPresent(parts -> addNeed(rows, parts, li.quantity()));
            }
        }

        final List<PartRow> out = new ArrayList<>(rows.values());
        // What you have to act on first: parts that must be printed, then what is on the shelf, then the rest.
        out.sort(Comparator.<PartRow>comparingInt(r -> r.toPrint() > 0 ? 0 : r.onHand > 0 ? 1 : 2)
                .thenComparing(r -> r.id.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void addSellers(final Map<String, PartRow> rows, final String market, final String storageKey,
            final List<MappingPart> parts, final Map<String, String> titles) {
        final int bar = storageKey.indexOf('|');
        final String listing = bar < 0 ? storageKey : storageKey.substring(0, bar);
        final String variation = bar < 0 || bar == storageKey.length() - 1 ? "" : unescape(storageKey.substring(bar + 1));
        final String title = titles.getOrDefault(listing, "");
        for (final MappingPart part : parts) {
            rows.computeIfAbsent(StockService.partKey(part), PartRow::new).sellers
                    .add(new Seller(market, title.isBlank() ? listing : title, variation, part.copiesPerUnit()));
        }
    }

    private static void addNeed(final Map<String, PartRow> rows, final List<MappingPart> parts, final int quantity) {
        for (final MappingPart part : parts) {
            rows.computeIfAbsent(StockService.partKey(part), PartRow::new).needed
                    += Math.max(1, quantity) * part.copiesPerUnit();
        }
    }

    /** Variation names are stored HTML-escaped (they come from the marketplace that way). */
    private static String unescape(final String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    // -------------------------------------------------------------------------
    // rendering
    // -------------------------------------------------------------------------
    private void render() {
        final List<PartRow> all = collect();
        final String q = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        final boolean stockOnly = Boolean.TRUE.equals(inStockOnly.getValue());
        final boolean needOnly = Boolean.TRUE.equals(neededOnly.getValue());
        final List<PartRow> shown = all.stream()
                .filter(r -> !stockOnly || r.onHand > 0)
                .filter(r -> !needOnly || r.needed > 0)
                .filter(r -> q.isEmpty() || matches(r, q))
                .toList();

        summary.setText("%d parts · %d pieces on hand · %d to print for orders not queued yet".formatted(
                all.size(), all.stream().mapToInt(r -> r.onHand).sum(), all.stream().mapToInt(PartRow::toPrint).sum()));

        list.removeAll();
        if (shown.isEmpty()) {
            final Div empty = new Div(new Span(all.isEmpty()
                    ? "Nothing to track yet - map a listing to a print file on the Mappings tab and it appears here."
                    : "Nothing matches."));
            empty.getStyle().set("padding", "var(--lumo-space-l)").setColor("var(--lumo-secondary-text-color)");
            list.add(empty);
            return;
        }
        list.add(header());
        shown.forEach(r -> list.add(row(r)));
    }

    private static boolean matches(final PartRow r, final String q) {
        if (r.id.file().toLowerCase(Locale.ROOT).contains(q) || r.id.material().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return r.sellers.stream().anyMatch(s -> (s.title() + " " + s.variation()).toLowerCase(Locale.ROOT).contains(q));
    }

    private static Div header() {
        final Div h = new Div(new Span("On hand"), new Span("Part"), new Span("Sold as"), new Span("Orders not queued"), new Span());
        h.getStyle().set("display", "grid").set("grid-template-columns", GRID).set("gap", "14px")
                .set("min-width", "820px").set("padding", "0 14px 6px")
                .set("font-size", "var(--lumo-font-size-xs)").set("text-transform", "uppercase")
                .set("letter-spacing", "0.08em").setColor("var(--lumo-secondary-text-color)");
        return h;
    }

    private Div row(final PartRow r) {
        final Div main = new Div(countBox(r), partBox(r), sellersBox(r), needBox(r), toggle(r));
        main.getStyle().set("display", "grid").set("grid-template-columns", GRID).set("gap", "14px")
                .set("align-items", "center").set("padding", "10px 14px");

        final Div box = new Div(main);
        box.getStyle().set("background", "var(--lumo-contrast-5pct)").set("border-radius", "10px")
                .set("margin-bottom", "6px").set("min-width", "820px");
        if (r.toPrint() > 0) {
            // The one coloured edge on the page: this part has to be printed for an order nothing is queued for.
            box.getStyle().set("box-shadow", "inset 3px 0 0 var(--lumo-warning-text-color, #e8a33d)");
        }
        if (open.contains(r.key)) {
            box.add(historyBox(r));
        }
        return box;
    }

    private Div countBox(final PartRow r) {
        final Button minus = new Button(new Icon(VaadinIcon.MINUS), e -> change(r, -1));
        minus.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        minus.setEnabled(r.onHand > 0);
        minus.setTooltipText("Remove one piece");
        final Button plus = new Button(new Icon(VaadinIcon.PLUS), e -> change(r, 1));
        plus.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        plus.setTooltipText("Add one piece");
        final Span n = new Span(String.valueOf(r.onHand));
        n.getStyle().set("min-width", "44px").set("text-align", "center").set("font-size", "22px")
                .set("font-weight", r.onHand > 0 ? "600" : "400").set("font-variant-numeric", "tabular-nums");
        if (r.onHand == 0) {
            n.getStyle().setColor("var(--lumo-tertiary-text-color)");
        }
        final Div box = new Div(minus, n, plus);
        box.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px");
        return box;
    }

    private void change(final PartRow r, final int delta) {
        stockService.adjust(r.key, delta, delta > 0 ? "Added by hand" : "Removed by hand");
        render();
    }

    private static Div partBox(final PartRow r) {
        final Div name = new Div(new Span(r.id.name()));
        name.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        final Div meta = new Div();
        meta.getStyle().set("display", "flex").set("gap", "6px").set("flex-wrap", "wrap").set("margin-top", "3px");
        if (!r.id.material().isBlank()) {
            meta.add(pill(r.id.material()));
        }
        if (!r.id.color().isBlank()) {
            meta.add(pill(r.id.color()));
        }
        final Div box = new Div(name, meta);
        box.getStyle().set("min-width", "0");
        return box;
    }

    private static Span pill(final String text) {
        final Span s = new Span(text);
        s.getStyle().set("border", "1px solid var(--lumo-contrast-30pct)").set("border-radius", "99px")
                .set("padding", "0 8px").set("font-size", "var(--lumo-font-size-xs)").set("line-height", "1.6");
        return s;
    }

    private static Div sellersBox(final PartRow r) {
        final Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px").set("min-width", "0");
        if (r.sellers.isEmpty()) {
            final Span none = new Span("no mapping uses this file any more");
            none.getStyle().setColor("var(--lumo-tertiary-text-color)").set("font-size", "var(--lumo-font-size-s)");
            box.add(none);
            return box;
        }
        r.sellers.stream()
                .sorted(Comparator.comparing(Seller::market).reversed().thenComparing(Seller::title))
                .forEach(s -> {
                    final Span dot = new Span();
                    // The two marketplaces' own colours, used only as a key for which is which.
                    dot.getStyle().set("width", "8px").set("height", "8px").set("border-radius", "50%").set("flex", "none")
                            .set("background", "etsy".equals(s.market()) ? "#f1641e" : "#6cb6ff");
                    final String label = s.variation().isBlank() ? s.title() : s.title() + " · " + s.variation();
                    final Span text = new Span(label);
                    text.getStyle().set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap");
                    final Span takes = new Span("takes " + s.takes());
                    takes.getStyle().setColor("var(--lumo-secondary-text-color)").set("white-space", "nowrap");
                    final Div chip = new Div(dot, text, takes);
                    chip.getElement().setAttribute("title", "%s: %s".formatted("etsy".equals(s.market()) ? "Etsy" : "eBay", label));
                    chip.getStyle().set("display", "inline-flex").set("align-items", "center").set("gap", "6px")
                            .set("background", "var(--lumo-contrast-5pct)").set("border-radius", "6px")
                            .set("padding", "3px 8px").set("font-size", "var(--lumo-font-size-s)").set("max-width", "100%");
                    box.add(chip);
                });
        return box;
    }

    private static Div needBox(final PartRow r) {
        final Div box = new Div();
        box.getStyle().set("font-size", "var(--lumo-font-size-s)");
        if (r.needed == 0) {
            box.add(new Span("none waiting"));
            box.getStyle().setColor("var(--lumo-tertiary-text-color)");
        } else if (r.toPrint() == 0) {
            box.add(new Span("%d needed · covered by stock".formatted(r.needed)));
            box.getStyle().setColor("var(--lumo-success-text-color)");
        } else {
            box.add(new Span("%d needed · print %d".formatted(r.needed, r.toPrint())));
            box.getStyle().setColor("var(--lumo-warning-text-color, #e8a33d)");
        }
        return box;
    }

    private Button toggle(final PartRow r) {
        final boolean isOpen = open.contains(r.key);
        final Button b = new Button(new Icon(isOpen ? VaadinIcon.ANGLE_DOWN : VaadinIcon.ANGLE_RIGHT), e -> {
            if (!open.remove(r.key)) {
                open.add(r.key);
            }
            render();
        });
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        b.setTooltipText(isOpen ? "Hide history" : "Show history");
        return b;
    }

    private Div historyBox(final PartRow r) {
        final Div box = new Div();
        box.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)").set("padding", "10px 14px 12px");
        final List<StockService.LedgerEntry> entries = stockService.history(r.key, 20);
        if (entries.isEmpty()) {
            final Span none = new Span("No changes recorded yet.");
            none.getStyle().setColor("var(--lumo-tertiary-text-color)").set("font-size", "var(--lumo-font-size-s)");
            box.add(none);
            return box;
        }
        entries.forEach(e -> {
            final Span when = new Span(when(e.timestamp()));
            when.getStyle().setColor("var(--lumo-secondary-text-color)");
            final Span delta = new Span((e.delta() > 0 ? "+" : "−") + Math.abs(e.delta()));
            delta.getStyle().setColor(e.delta() > 0 ? "var(--lumo-success-text-color)" : "var(--lumo-warning-text-color, #e8a33d)");
            final Span balance = new Span(e.balance() + " left");
            balance.getStyle().setColor("var(--lumo-secondary-text-color)").set("text-align", "right");
            final Div line = new Div(when, delta, new Span(e.reason()), balance);
            line.getStyle().set("display", "grid").set("grid-template-columns", "110px 48px minmax(0, 1fr) 70px")
                    .set("gap", "10px").set("font-size", "var(--lumo-font-size-s)").set("padding", "2px 0")
                    .set("font-variant-numeric", "tabular-nums");
            box.add(line);
        });
        return box;
    }

    private static String when(final String timestamp) {
        try {
            return TIME_FMT.format(OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()));
        } catch (DateTimeParseException | NullPointerException ex) {
            return String.valueOf(timestamp);
        }
    }
}
