package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOAuthService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOAuthService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.OrderTrackingService;
import com.tfyre.bambu.view.batchprint.Plate;
import com.tfyre.bambu.view.batchprint.ProjectFile;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Mappings — every listing → gcode assignment in one place (a tab on the Automation page, also
 * {@code /mappings}).
 * <ul>
 * <li><b>Etsy</b>: pulls ALL active shop listings (the {@code listings_r} scope the connect flow already
 * requests) into a table, so products can be mapped before an order ever arrives - exactly what auto-queue
 * needs to handle a first-time order hands-free. Editing here writes the listing-wide (variation-less) base
 * mapping, which order lookups fall back to for any variation.</li>
 * <li><b>eBay</b>: the fulfillment-only OAuth scope can't list inventory, so rows come from saved mappings
 * plus listing keys seen in currently-open orders.</li>
 * <li><b>All saved mappings</b>: the raw stored entries (including per-variation ones created from the order
 * pages), editable and deletable.</li>
 * </ul>
 */
@Route(value = "mappings", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Mappings")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class MappingsView extends VerticalLayout implements NotificationHelper {

    @Inject
    BambuConfig config;
    @Inject
    com.tfyre.bambu.printer.GcodeMappingQueuer queuer;
    @Inject
    EtsyOAuthService etsyOauth;
    @Inject
    EtsyApiClient etsyClient;
    @Inject
    EtsyMappingService etsyMapping;
    @Inject
    EbayMappingService ebayMapping;
    @Inject
    EbayOAuthService ebayOauth;
    @Inject
    EbayApiClient ebayClient;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    OrderTrackingService tracking;
    @Inject
    com.tfyre.bambu.printer.AutoQueueService autoQueueService;

    /** One Etsy listing - what the variations dialog works from. */
    public record EtsyRow(long listingId, String title, int quantity, String mappedState, boolean hidden, String imageUrl,
            boolean hasVariations) {
    }

    /** One eBay listing key - what the variations dialog works from. */
    public record EbayRow(String listingKey, String title, String mappedState, boolean hidden, String imageUrl,
            String itemId, List<EbayApiClient.EbayVariation> variations) {
    }

    /**
     * One row of the page: a listing on one marketplace.
     *
     * @param active false for a listing that only exists as a saved mapping (or an open order) - not among the
     *               fetched active listings
     */
    public record ListingRow(String market, String key, String title, String imageUrl, String state, boolean hidden,
            boolean active, EtsyRow etsy, EbayRow ebay) {

        /** needs / variations / mapped / hidden - which filter chip the row counts under. */
        String category() {
            if (hidden) {
                return "hidden";
            }
            if ("—".equals(state)) {
                return "needs";
            }
            return state.endsWith(" only") ? "variations" : "mapped";
        }
    }

    @Inject
    com.tfyre.bambu.printer.MarketListingCache listingCache;
    @Inject
    com.tfyre.bambu.printer.StockService stockService;

    /** Filter chip in use: all / needs / variations / mapped / hidden. */
    private String filter = "all";
    private final Map<String, Button> chips = new LinkedHashMap<>();
    private final com.vaadin.flow.component.textfield.TextField search = new com.vaadin.flow.component.textfield.TextField();
    private final Div list = new Div();
    private final Span fetchedStatus = new Span();

    private static final String SORT_ATTENTION = "Needs attention first";
    private static final String SORT_NAME = "Name";
    private static final String SORT_MARKET = "Marketplace, then name";
    private static final String SORT_FILE = "Part file (groups the same product)";
    private static final String MARKET_ALL = "Etsy + eBay";
    /** Session attributes, so the choice survives leaving the page and coming back. */
    private static final String SORT_ATTR = "mappings.sort";
    private static final String MARKET_ATTR = "mappings.market";

    private final com.vaadin.flow.component.select.Select<String> sortBy = new com.vaadin.flow.component.select.Select<>();
    private final com.vaadin.flow.component.select.Select<String> marketPick = new com.vaadin.flow.component.select.Select<>();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        chips.clear();
        addClassName("mappings-view");
        setPadding(true);
        setSpacing(true);

        final Button refresh = new Button("Refresh listings", new Icon(VaadinIcon.REFRESH));
        refresh.addThemeVariants(ButtonVariant.LUMO_SMALL);
        refresh.setEnabled(etsyOauth.isConnected() || ebayOauth.isConnected());
        refresh.addClickListener(e -> refreshListings(refresh));
        fetchedStatus.addClassName("mp-dim");
        final Div title = new Div(new H3("Mappings"), fetchedStatus, refresh);
        title.addClassName("mp-title");
        final Span intro = new Span("Which print jobs each listing makes, so auto-queue can handle its orders. "
                + "Hide listings you never print - they're skipped instead of raising a 'not mapped' alert. "
                + "Which parts have an H2D file is on the Files page.");
        intro.addClassName("mp-dim");

        final Div filters = new Div();
        filters.addClassName("mp-filters");
        for (final String[] c : new String[][]{{"all", "All"}, {"needs", "Needs a mapping"},
            {"variations", "Variations only"}, {"mapped", "Mapped"}, {"hidden", "Hidden"}}) {
            final Button chip = new Button(c[1], e -> {
                filter = c[0];
                render();
            });
            chip.addClassName("mp-filter");
            chip.getElement().setAttribute("data-f", c[0]);
            chips.put(c[0], chip);
            filters.add(chip);
        }
        search.setPlaceholder("Search listings or files");
        search.setClearButtonVisible(true);
        search.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);
        search.addValueChangeListener(e -> render());
        search.addClassName("mp-search");
        final com.vaadin.flow.server.VaadinSession session = com.vaadin.flow.server.VaadinSession.getCurrent();
        marketPick.setItems(MARKET_ALL, "Etsy", "eBay");
        marketPick.setValue(Optional.ofNullable((String) session.getAttribute(MARKET_ATTR)).orElse(MARKET_ALL));
        marketPick.setAriaLabel("Marketplace");
        marketPick.addClassName("mp-select");
        marketPick.addValueChangeListener(e -> {
            session.setAttribute(MARKET_ATTR, e.getValue());
            render();
        });
        sortBy.setItems(SORT_ATTENTION, SORT_NAME, SORT_MARKET, SORT_FILE);
        sortBy.setValue(Optional.ofNullable((String) session.getAttribute(SORT_ATTR)).orElse(SORT_ATTENTION));
        sortBy.setPrefixComponent(new Icon(VaadinIcon.SORT));
        sortBy.setAriaLabel("Sort");
        sortBy.addClassName("mp-select");
        sortBy.addValueChangeListener(e -> {
            session.setAttribute(SORT_ATTR, e.getValue());
            render();
        });
        filters.add(search, marketPick, sortBy);

        list.addClassName("mp-list");
        add(title, intro, filters, list);
        if (!etsyOauth.isConnected() || !ebayOauth.isConnected()) {
            final Span connect = new Span("%s is not connected - connect it on its Sales Orders page to refresh its listings."
                    .formatted(!etsyOauth.isConnected() && !ebayOauth.isConnected() ? "Etsy and eBay"
                    : !etsyOauth.isConnected() ? "Etsy" : "eBay"));
            connect.addClassName("mp-dim");
            addComponentAtIndex(2, connect);
        }
        render();
    }

    private void renderAll() {
        render();
    }

    // -------------------------------------------------------------------------
    // P1 / H2D coverage - every mapped part once, with what each kind of printer has for it
    // -------------------------------------------------------------------------
    @Inject
    com.tfyre.bambu.printer.LibraryFileInfo fileInfo;

    /**
     * One mapped part.
     *
     * @param candidate a library file named like the P1 file plus {@code _H2D} that is not attached yet, or null
     */
    public record CoverageRow(String partKey, String file, String material, String p1, String h2dFile, int h2dMakes,
            int usedBy, String candidate) {
    }

    private final Grid<CoverageRow> coverageGrid = new Grid<>();
    private final Span coverageSummary = new Span();
    private final com.vaadin.flow.component.checkbox.Checkbox coverageMissingOnly
            = new com.vaadin.flow.component.checkbox.Checkbox("Only parts with no H2D file");

    private Div buildCoverageSection() {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4("P1 and H2D files"));
        final Span help = new Span("Every mapped part once. The first file is the P1 slice; a part with no H2D file is "
                + "never sent to the H2D. Name an H2D slice like the P1 file plus _H2D (Part_H2D.gcode.3mf), put it in "
                + "the library, and it is offered here to attach to every mapping that uses the part. Then set how "
                + "many pieces one H2D print makes in the mapping's editor.");
        help.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        sec.add(new Div(help));
        coverageSummary.getStyle().set("font-weight", "600");
        coverageMissingOnly.addValueChangeListener(e -> renderCoverage());
        final com.vaadin.flow.component.orderedlayout.HorizontalLayout bar
                = new com.vaadin.flow.component.orderedlayout.HorizontalLayout(coverageSummary, coverageMissingOnly);
        bar.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        bar.getStyle().set("flex-wrap", "wrap");
        sec.add(bar);

        if (coverageGrid.getColumns().isEmpty()) {
            coverageGrid.addColumn(r -> r.file() + (r.material().isBlank() ? "" : "  ·  " + r.material()))
                    .setHeader("Part").setFlexGrow(3).setSortable(true);
            coverageGrid.addColumn(CoverageRow::p1).setHeader("P1").setAutoWidth(true);
            coverageGrid.addComponentColumn(r -> {
                if (r.h2dFile() != null) {
                    return new Span("%s · makes %d".formatted(r.h2dFile(), r.h2dMakes()));
                }
                if (r.candidate() == null) {
                    final Span none = new Span("none");
                    none.getStyle().setColor("var(--lumo-secondary-text-color)");
                    return none;
                }
                final Button attach = new Button("Attach " + r.candidate(), e -> attachH2d(r));
                attach.addThemeVariants(ButtonVariant.LUMO_SMALL);
                attach.setTooltipText("Use this file as the H2D slice in every mapping that prints " + r.file());
                return attach;
            }).setHeader("H2D").setFlexGrow(3);
            coverageGrid.addColumn(r -> r.usedBy() + (r.usedBy() == 1 ? " mapping" : " mappings")).setHeader("Used by").setAutoWidth(true);
            coverageGrid.setWidth("100%");
            coverageGrid.setAllRowsVisible(true);
            coverageGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        }
        final com.vaadin.flow.component.details.Details details
                = new com.vaadin.flow.component.details.Details("Show every part", coverageGrid);
        details.setWidthFull();
        sec.add(details);
        return sec;
    }

    private List<MappingPart> allMappedParts() {
        final List<MappingPart> all = new ArrayList<>();
        etsyMapping.entries().values().forEach(e -> all.addAll(e.parts()));
        ebayMapping.entries().values().forEach(e -> all.addAll(e.parts()));
        return all;
    }

    private void renderCoverage() {
        final List<String> library = queuer.listLibraryFiles();
        final Map<String, CoverageRow> rows = new LinkedHashMap<>();
        for (final MappingPart p : allMappedParts()) {
            if (p.source() != com.tfyre.bambu.printer.GcodeSource.LIBRARY) {
                continue;
            }
            final String key = com.tfyre.bambu.printer.StockService.partKey(p);
            final CoverageRow before = rows.get(key);
            // A part can be mapped several times; if any of them has the H2D file, show that one.
            final String h2d = p.h2dPath().orElse(before == null ? null : before.h2dFile());
            final int makes = p.h2dPath().isPresent() ? p.h2dYield() : before == null ? 1 : before.h2dMakes();
            String candidate = null;
            if (h2d == null) {
                final String wanted = com.tfyre.bambu.printer.LibraryFileInfo.stem(p.path()) + "_h2d";
                candidate = library.stream()
                        .filter(f -> com.tfyre.bambu.printer.LibraryFileInfo.stem(f).equalsIgnoreCase(wanted))
                        .findFirst().orElse(null);
            }
            final com.tfyre.bambu.printer.LibraryFileInfo.Info info = fileInfo.of(p.path());
            final String p1 = info.family().isEmpty()
                    ? (info.modelId().isEmpty() ? "no slice information" : "sliced for " + info.modelId())
                    : com.tfyre.bambu.printer.BambuConst.PrinterModel.FAMILY_P1.equals(info.family()) ? "ready"
                    : "sliced for " + info.badge() + ", not a P1";
            final String material = (p.filamentType() == null ? "" : p.filamentType())
                    + (p.filamentColor() == null ? "" : " " + p.filamentColor());
            rows.put(key, new CoverageRow(key, p.path(), material.strip(), p1, h2d, makes,
                    (before == null ? 0 : before.usedBy()) + 1, candidate));
        }
        final long ready = rows.values().stream().filter(r -> r.h2dFile() != null).count();
        coverageSummary.setText("%d part%s mapped · %d with an H2D file · %d without".formatted(rows.size(),
                rows.size() == 1 ? "" : "s", ready, rows.size() - ready));
        final boolean missingOnly = Boolean.TRUE.equals(coverageMissingOnly.getValue());
        coverageGrid.setItems(rows.values().stream().filter(r -> !missingOnly || r.h2dFile() == null)
                .sorted((a, b) -> a.file().compareToIgnoreCase(b.file())).toList());
    }

    /** Sets {@code row.candidate()} as the H2D file on every mapping, in both marketplaces, that prints this part. */
    private void attachH2d(final CoverageRow row) {
        final String family = com.tfyre.bambu.printer.BambuConst.PrinterModel.FAMILY_H2D;
        final String problem = fileInfo.mismatch(row.candidate(), com.tfyre.bambu.printer.BambuConst.PrinterModel.H2D);
        if (problem != null) {
            showError(problem);
            return;
        }
        int changed = 0;
        for (final Map.Entry<String, EtsyMappingService.MappingEntry> e : etsyMapping.entries().entrySet()) {
            final List<MappingPart> updated = withH2d(e.getValue().parts(), row, family);
            if (updated != null) {
                etsyMapping.putByKey(e.getKey(), new EtsyMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        for (final Map.Entry<String, EbayMappingService.MappingEntry> e : ebayMapping.entries().entrySet()) {
            final List<MappingPart> updated = withH2d(e.getValue().parts(), row, family);
            if (updated != null) {
                ebayMapping.putByKey(e.getKey(), new EbayMappingService.MappingEntry(updated, e.getValue().productCode()));
                changed++;
            }
        }
        showNotification("%s attached as the H2D file on %d mapping%s. It is set to make 1 piece per print - change that in the mapping if the plate holds more."
                .formatted(row.candidate(), changed, changed == 1 ? "" : "s"), java.time.Duration.ofSeconds(10));
        renderAll();
    }

    /** {@code parts} with the H2D file set on the ones that are this row's part, or null when none of them is. */
    private static List<MappingPart> withH2d(final List<MappingPart> parts, final CoverageRow row, final String family) {
        boolean any = false;
        final List<MappingPart> out = new ArrayList<>();
        for (final MappingPart p : parts) {
            if (p.h2dPath().isEmpty() && row.partKey().equals(com.tfyre.bambu.printer.StockService.partKey(p))) {
                out.add(p.withVariant(family, row.candidate()));
                any = true;
            } else {
                out.add(p);
            }
        }
        return any ? out : null;
    }

    // -------------------------------------------------------------------------
    // Etsy - full active-listing pull
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // The list - every Etsy and eBay listing, one row each, filtered by the chips and the search box
    // -------------------------------------------------------------------------

    private void refreshListings(final Button refresh) {
        refresh.setEnabled(false);
        fetchedStatus.setText("Fetching listings…");
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        new Thread(() -> {
            final List<String> errors = new ArrayList<>();
            List<EtsyApiClient.Listing> etsy = null;
            List<EbayApiClient.EbayListing> ebay = null;
            if (etsyOauth.isConnected()) {
                try {
                    etsy = etsyClient.getActiveListings();
                } catch (Exception ex) {
                    Log.errorf(ex, "MappingsView: Etsy listing fetch failed: %s", ex.getMessage());
                    errors.add("Etsy: " + ex.getMessage());
                }
            }
            if (ebayOauth.isConnected()) {
                try {
                    ebay = ebayClient.getActiveListings();
                } catch (Exception ex) {
                    Log.errorf(ex, "MappingsView: eBay listing fetch failed: %s", ex.getMessage());
                    errors.add("eBay: " + ex.getMessage());
                }
            }
            final List<EtsyApiClient.Listing> etsyResult = etsy;
            final List<EbayApiClient.EbayListing> ebayResult = ebay;
            ui.ifPresent(u -> u.access(() -> {
                if (etsyResult != null) {
                    listingCache.setEtsy(etsyResult);
                }
                if (ebayResult != null) {
                    listingCache.setEbay(ebayResult);
                }
                errors.forEach(err -> showError("Could not load listings - " + err));
                refresh.setEnabled(true);
                render();
            }));
        }).start();
    }

    private List<ListingRow> buildRows() {
        final List<ListingRow> rows = new ArrayList<>();

        // Etsy: the fetched active listings, then any listing that only has a saved mapping
        final Map<String, EtsyMappingService.MappingEntry> etsySaved = etsyMapping.entries();
        final java.util.Set<String> etsySeen = new java.util.HashSet<>();
        listingCache.getEtsy().forEach(l -> {
            final String key = String.valueOf(l.listingId());
            etsySeen.add(key);
            final String state = mappedState(etsySaved, key);
            final boolean hidden = tracking.isListingHidden("etsy", key);
            final String title = com.tfyre.bambu.printer.HtmlText.plain(l.title());
            rows.add(new ListingRow("etsy", key, title, l.imageUrl(), state, hidden, true,
                    new EtsyRow(l.listingId(), title, l.quantityAvailable(), state, hidden, l.imageUrl(), l.hasVariations()), null));
        });
        etsySaved.keySet().stream().map(MappingsView::listingOf).distinct().filter(k -> !etsySeen.contains(k)).forEach(key -> {
            final long id;
            try {
                id = Long.parseLong(key);
            } catch (NumberFormatException ex) {
                return;
            }
            final String state = mappedState(etsySaved, key);
            final boolean hidden = tracking.isListingHidden("etsy", key);
            final String title = "Etsy listing " + key;
            rows.add(new ListingRow("etsy", key, title, "", state, hidden, false,
                    new EtsyRow(id, title, 0, state, hidden, "", true), null));
        });

        // eBay: fetched active listings (best titles + images), then keys from open orders, then bare mapped keys
        final Map<String, EbayMappingService.MappingEntry> ebaySaved = ebayMapping.entries();
        final Map<String, String> titles = new LinkedHashMap<>();
        final Map<String, EbayApiClient.EbayListing> byKey = new LinkedHashMap<>();
        listingCache.getEbay().forEach(l -> {
            titles.putIfAbsent(l.listingKey(), l.title());
            byKey.putIfAbsent(l.listingKey(), l);
        });
        ebayPolling.getOrders().forEach(o -> o.lineItems().forEach(li -> {
            final String key = li.listingKey();
            if (key != null && !key.isBlank()) {
                titles.putIfAbsent(key, li.title());
            }
        }));
        ebaySaved.keySet().stream().map(MappingsView::listingOf).forEach(k -> titles.putIfAbsent(k, ""));
        titles.forEach((key, rawTitle) -> {
            final EbayApiClient.EbayListing l = byKey.get(key);
            final String state = mappedState(ebaySaved, key);
            final boolean hidden = tracking.isListingHidden("ebay", key);
            final String title = rawTitle == null || rawTitle.isBlank() ? "eBay " + key : com.tfyre.bambu.printer.HtmlText.plain(rawTitle);
            final String image = l == null || l.imageUrl() == null ? "" : l.imageUrl();
            rows.add(new ListingRow("ebay", key, title, image, state, hidden, l != null,
                    null, new EbayRow(key, title, state, hidden, image, l == null ? key : l.itemId(),
                            l == null ? List.of() : l.variations())));
        });
        return rows;
    }

    private static String listingOf(final String storageKey) {
        final int sep = storageKey.indexOf('|');
        return sep < 0 ? storageKey : storageKey.substring(0, sep);
    }

    private List<MappingPart> baseParts(final ListingRow r) {
        return "etsy".equals(r.market())
                ? Optional.ofNullable(etsyMapping.entries().get(r.key() + "|")).map(EtsyMappingService.MappingEntry::parts).orElse(List.of())
                : Optional.ofNullable(ebayMapping.entries().get(r.key() + "|")).map(EbayMappingService.MappingEntry::parts).orElse(List.of());
    }

    private static final List<String> ORDER = List.of("needs", "variations", "mapped", "hidden");

    private void render() {
        final List<ListingRow> rows = buildRows();
        final Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(ListingRow::category, Collectors.counting()));
        chips.forEach((f, chip) -> {
            final long n = "all".equals(f) ? rows.stream().filter(r -> !r.hidden()).count() : counts.getOrDefault(f, 0L);
            chip.setText("%s %d".formatted(switch (f) {
                case "needs" -> "Needs a mapping";
                case "variations" -> "Variations only";
                case "mapped" -> "Mapped";
                case "hidden" -> "Hidden";
                default -> "All";
            }, n));
            chip.getElement().setAttribute("data-on", f.equals(filter) ? "true" : "false");
            chip.getElement().setAttribute("data-tone", n > 0 && ("needs".equals(f) || "variations".equals(f)) ? "warn" : "");
        });
        final String q = Optional.ofNullable(search.getValue()).orElse("").trim().toLowerCase(java.util.Locale.ENGLISH);
        final List<ListingRow> shown = rows.stream()
                .filter(r -> "all".equals(filter) ? !r.hidden() : filter.equals(r.category()))
                .filter(r -> q.isEmpty() || haystack(r).contains(q))
                .filter(r -> marketPick.getValue() == null || MARKET_ALL.equals(marketPick.getValue())
                        || ("Etsy".equals(marketPick.getValue()) == "etsy".equals(r.market())))
                .sorted(comparator())
                .toList();
        list.removeAll();
        if (shown.isEmpty()) {
            final Span none = new Span(rows.isEmpty() ? "No listings yet - press Refresh listings." : "Nothing matches.");
            none.addClassName("mp-dim");
            list.add(none);
        }
        shown.forEach(r -> list.add(rowCard(r)));
        fetchedStatus.setText("Etsy listings %s · eBay %s".formatted(
                listingCache.etsyLoadedAt().map(t -> "fetched " + timeAgo(t)).orElse("never fetched"),
                listingCache.ebayLoadedAt().map(MappingsView::timeAgo).orElse("never fetched")));
    }

    private java.util.Comparator<ListingRow> comparator() {
        final java.util.Comparator<ListingRow> byName = java.util.Comparator.comparing(ListingRow::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ListingRow::market);
        final String sort = Optional.ofNullable(sortBy.getValue()).orElse(SORT_ATTENTION);
        return switch (sort) {
            case SORT_NAME -> byName;
            case SORT_MARKET -> java.util.Comparator.comparing(ListingRow::market).thenComparing(byName);
            // Listings that print the same first file sit together - the Etsy and eBay listing of one product,
            // or two listings of the same part. Unmapped ones go last, by name.
            case SORT_FILE -> java.util.Comparator.comparing((ListingRow r) -> partKey(r).isEmpty())
                    .thenComparing(this::partKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byName);
            default -> java.util.Comparator.comparingInt((ListingRow r) -> ORDER.indexOf(r.category())).thenComparing(byName);
        };
    }

    /** The first file the listing prints, by its Files-page name, or "" when it has no listing-wide mapping. */
    private String partKey(final ListingRow r) {
        return baseParts(r).stream().findFirst().map(p -> friendly(p.path())).orElse("");
    }

    private String haystack(final ListingRow r) {
        final StringBuilder sb = new StringBuilder(r.title()).append(' ').append(r.key()).append(' ');
        final String prefix = r.key() + "|";
        final List<List<MappingPart>> mapped = new ArrayList<>();
        if ("etsy".equals(r.market())) {
            etsyMapping.entries().forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    mapped.add(v.parts());
                }
            });
        } else {
            ebayMapping.entries().forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    mapped.add(v.parts());
                }
            });
        }
        mapped.forEach(parts -> parts.forEach(p -> sb.append(p.path()).append(' ')
                .append(com.tfyre.bambu.printer.LibraryService.titleOf(p.path()))
                .append(' ').append(p.h2dPath().orElse("")).append(' ')));
        return sb.toString().toLowerCase(java.util.Locale.ENGLISH);
    }

    private Div rowCard(final ListingRow r) {
        final Span name = new Span(r.title());
        name.addClassName("mp-name");
        final Span market = new Span("etsy".equals(r.market()) ? "Etsy" : "eBay");
        market.addClassName("mp-badge");
        market.getElement().setAttribute("data-m", r.market());
        final Span state = new Span(switch (r.category()) {
            case "needs" -> "not mapped";
            case "mapped" -> r.state().startsWith("✓") ? "mapped" : r.state();
            default -> r.state();
        });
        state.addClassName("mp-chip");
        state.getElement().setAttribute("data-tone", switch (r.category()) {
            case "mapped" -> "ok";
            case "hidden" -> "";
            default -> "warn";
        });
        if (r.hidden()) {
            state.setText("hidden · " + state.getText());
        }
        final Div where = new Div(market, state);
        where.addClassName("mp-where");
        if ("ebay".equals(r.market()) && !r.key().equals(r.title())) {
            final Span key = new Span(r.key());
            key.addClassName("mp-dim");
            where.add(key);
        }
        if (!r.active()) {
            final Span gone = new Span("not in the active listings");
            gone.addClassName("mp-dim");
            where.add(gone);
        }
        final List<MappingPart> parts = baseParts(r);
        if (!parts.isEmpty()) {
            where.add(stockSummary(r.market(), r.key() + "|"));
        }
        final Div text = new Div(name, where);
        text.addClassName("mp-text");

        final Div partsDiv = new Div();
        partsDiv.addClassName("mp-parts");
        if (!parts.isEmpty()) {
            partsDiv.add(partsCell(parts));
        } else if ("variations".equals(r.category()) || r.state().endsWith(" only")) {
            final Span v = new Span("Each variation has its own mapping - see Variations.");
            v.addClassName("mp-dim");
            partsDiv.add(v);
        }

        final Div actions = new Div();
        actions.addClassName("mp-act");
        final boolean unmapped = parts.isEmpty();
        if (unmapped && "needs".equals(r.category())) {
            suggestButton(editorTitle(r), r.title(), p -> saveBase(r, p)).ifPresent(b -> {
                b.removeThemeVariants(ButtonVariant.LUMO_TERTIARY);
                b.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                b.setText("Use " + b.getText().replaceAll("\\?$", ""));
                actions.add(b);
            });
        }
        final Button edit = new Button(unmapped ? "Map…" : "Edit", new Icon(unmapped ? VaadinIcon.PLUS : VaadinIcon.EDIT));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL);
        edit.addClickListener(e -> openEditor(editorTitle(r), parts, p -> saveBase(r, p)));
        actions.add(edit);
        if (r.etsy() != null && r.etsy().hasVariations()) {
            actions.add(etsyVariationsButton(r.etsy()));
        } else if (r.ebay() != null && !r.ebay().variations().isEmpty()) {
            actions.add(ebayVariationsButton(r.ebay()));
        }
        actions.add(testButton(r.state(), () -> baseParts(r), r.title()));
        actions.add(hideButton(r.market(), r.key(), r.hidden()));
        deleteButton(r).ifPresent(actions::add);

        final Div card = new Div(thumbnail(r.imageUrl()), text, partsDiv, actions);
        card.addClassName("mp-row");
        return card;
    }

    private static String editorTitle(final ListingRow r) {
        return ("etsy".equals(r.market()) ? "Etsy: " : "eBay: ") + r.title();
    }

    private void saveBase(final ListingRow r, final List<MappingPart> parts) {
        if ("etsy".equals(r.market())) {
            etsyMapping.set(Long.parseLong(r.key()), List.of(), new EtsyMappingService.MappingEntry(parts));
        } else {
            ebayMapping.set(r.key(), List.of(), new EbayMappingService.MappingEntry(parts));
        }
        showNotification("Mapping saved for " + r.title());
        render();
    }

    /** Trash: removes this listing's mapping and its per-variation mappings, after a confirm. */
    private Optional<Button> deleteButton(final ListingRow r) {
        final String prefix = r.key() + "|";
        final List<String> keys = ("etsy".equals(r.market()) ? etsyMapping.entries().keySet() : ebayMapping.entries().keySet())
                .stream().filter(k -> k.startsWith(prefix)).toList();
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        final Button del = new Button(new Icon(VaadinIcon.TRASH));
        del.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        del.setTooltipText("Delete this listing's mapping" + (keys.size() > 1 ? " and its variation mappings" : ""));
        del.addClickListener(e -> com.tfyre.bambu.YesNoCancelDialog.show(
                "Delete %d mapping%s for %s?".formatted(keys.size(), keys.size() == 1 ? "" : "s", r.title()), ync -> {
                    if (!ync.isConfirmed()) {
                        return;
                    }
                    if ("etsy".equals(r.market())) {
                        keys.forEach(etsyMapping::removeByKey);
                    } else {
                        keys.forEach(ebayMapping::removeByKey);
                    }
                    showNotification("Mapping deleted");
                    render();
                }));
        return Optional.of(del);
    }

    /**
     * A mapping's parts, one block each: pieces per unit and the requirement on top, then the P1 file and the
     * H2D file on their own lines with how many each print makes - the same two rows the editor and the Files
     * page show, so a part reads the same everywhere.
     */
    private static Div partsCell(final List<MappingPart> parts) {
        final Div cell = new Div();
        cell.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px")
                .set("font-size", "var(--lumo-font-size-s)");
        if (parts.isEmpty()) {
            cell.add(new Span("(no parts)"));
        }
        for (final MappingPart p : parts) {
            final List<String> needs = new ArrayList<>();
            needs.add("%d per unit".formatted(p.copiesPerUnit()));
            if (p.filamentType() != null) {
                needs.add(p.filamentType() + (p.filamentColor() == null ? "" : " " + p.filamentColor()));
            }
            if (p.amsSlot() != null) {
                needs.add(AmsSlotSupport.label(p.amsSlot()));
            }
            p.extraSlots().forEach(s -> needs.add("2nd filament: " + AmsSlotSupport.label(s)));
            final Span head = new Span(String.join(" · ", needs));
            head.getStyle().setColor("var(--lumo-secondary-text-color)");
            final Div block = new Div(new Div(head));
            if (p.source() == com.tfyre.bambu.printer.GcodeSource.LIBRARY && p.h2dPath().isEmpty()
                    && com.tfyre.bambu.printer.LibraryFileInfo.namedH2d(p.path())) {
                // An H2D-only part (no P1 file): its one file is the H2D slice, not a P1 file with no H2D partner
                block.add(fileLine("H2D", "%s · plate %d · H2D only".formatted(friendly(p.path()), p.plateId()),
                        "var(--lumo-primary-text-color)", false));
            } else if (p.source() == com.tfyre.bambu.printer.GcodeSource.LIBRARY) {
                block.add(fileLine("P1", "%s · plate %d".formatted(friendly(p.path()), p.plateId()), "var(--lumo-success-text-color)", false));
                block.add(p.h2dPath()
                        .map(h -> fileLine("H2D", "%s · makes %d".formatted(friendly(h), p.h2dYield()), "var(--lumo-primary-text-color)", false))
                        .orElseGet(() -> fileLine("H2D", "none", "var(--lumo-secondary-text-color)", true)));
            } else {
                block.add(fileLine("SD", "%s · plate %d".formatted(p.path(), p.plateId()), "var(--lumo-warning-color, #e8a33d)", false));
            }
            cell.add(block);
        }
        return cell;
    }

    /** Library file as the Files page names it: "Rollhoop Grilles" rather than "Rollhoop/Rollhoop_Grilles_H2D.gcode.3mf". */
    private static String friendly(final String path) {
        return com.tfyre.bambu.printer.LibraryService.titleOf(path);
    }

    private static Div fileLine(final String kind, final String text, final String color, final boolean dashed) {
        final Span badge = new Span(kind);
        badge.getStyle().setColor(color).set("border", "1px %s currentColor".formatted(dashed ? "dashed" : "solid"))
                .set("border-radius", "4px").set("padding", "0 5px").set("font-size", "var(--lumo-font-size-xs)")
                .set("font-weight", "700").set("margin-right", "6px").set("display", "inline-block").set("min-width", "30px")
                .set("text-align", "center");
        final Span t = new Span(text);
        t.getStyle().set("overflow-wrap", "anywhere");
        if (dashed) {
            t.getStyle().setColor("var(--lumo-secondary-text-color)");
        }
        return new Div(badge, t);
    }

    private static String partsSummary(final List<MappingPart> parts) {
        if (parts.isEmpty()) {
            return "(no parts)";
        }
        return parts.stream()
                .map(p -> "%s plate %d ×%d%s%s%s".formatted(p.path(), p.plateId(), p.copiesPerUnit(),
                        p.amsSlot() != null ? " · " + AmsSlotSupport.label(p.amsSlot()) : "",
                        p.filamentType() != null ? " · " + p.filamentType() : "",
                        p.h2dPath().map(h -> " · H2D: " + h + (p.h2dYield() > 1 ? " (makes %d)".formatted(p.h2dYield()) : "")).orElse("")))
                .collect(Collectors.joining(";  "));
    }

    /** Compact "just now / 5 min ago / 2h 10m ago" for the cached-listings status line. */
    private static String timeAgo(final java.time.Instant t) {
        final long secs = java.time.Duration.between(t, java.time.Instant.now()).getSeconds();
        if (secs < 60) {
            return "just now";
        }
        if (secs < 3600) {
            return "%d min ago".formatted(secs / 60);
        }
        if (secs < 86400) {
            return "%dh %dm ago".formatted(secs / 3600, (secs % 3600) / 60);
        }
        final long days = secs / 86400;
        return days == 1 ? "1 day ago" : "%d days ago".formatted(days);
    }

    private static String mappedState(final Map<String, ?> saved, final String listingKeyPrefix) {
        final boolean base = saved.containsKey(listingKeyPrefix + "|");
        final long variations = saved.keySet().stream()
                .filter(k -> k.startsWith(listingKeyPrefix + "|") && !k.equals(listingKeyPrefix + "|"))
                .count();
        if (base && variations > 0) {
            return "base + %d variation%s".formatted(variations, variations == 1 ? "" : "s");
        }
        if (base) {
            return "✓ mapped";
        }
        if (variations > 0) {
            return "%d variation%s only".formatted(variations, variations == 1 ? "" : "s");
        }
        return "—";
    }

    /** 48px listing thumbnail, or an empty placeholder when the marketplace didn't provide an image. */
    private static com.vaadin.flow.component.Component thumbnail(final String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            final Div empty = new Div();
            empty.setWidth("48px");
            return empty;
        }
        final com.vaadin.flow.component.html.Image img = new com.vaadin.flow.component.html.Image(imageUrl, "listing");
        img.setWidth("48px");
        img.setHeight("48px");
        img.getStyle().set("object-fit", "cover").set("border-radius", "4px");
        return img;
    }

    /** Flask button: dry-runs the mapping through auto-queue's printer/filament selection without queueing. */
    private Button testButton(final String mappedState, final java.util.function.Supplier<List<MappingPart>> partsSupplier, final String title) {
        final Button b = new Button(new Icon(VaadinIcon.FLASK));
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        b.setTooltipText("Test: simulate auto-queueing 1 unit of this listing right now (nothing is queued)");
        b.setEnabled(!"—".equals(mappedState));
        b.addClickListener(e -> {
            final Dialog dialog = new Dialog();
            dialog.setHeaderTitle("Dry run: " + title);
            dialog.setWidth("700px");
            final VerticalLayout layout = new VerticalLayout();
            layout.setPadding(false);
            final List<com.tfyre.bambu.printer.AutoQueueService.DryRunLine> lines
                    = autoQueueService.dryRun(partsSupplier.get(), 1);
            final boolean allOk = lines.stream().allMatch(com.tfyre.bambu.printer.AutoQueueService.DryRunLine::ok);
            final Span verdict = new Span(allOk
                    ? "✓ This listing would auto-queue right now"
                    : "✗ This listing would be SKIPPED right now");
            verdict.getStyle().setFontWeight("bold")
                    .setColor(allOk ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
            layout.add(verdict);
            lines.forEach(l -> {
                final Span s = new Span("%s %s — %s".formatted(l.ok() ? "✓" : "✗", l.part(), l.outcome()));
                s.getStyle().setColor(l.ok() ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
                layout.add(s);
            });
            layout.add(new Span("Uses live printer state (filament loaded, queue depths) - results change as the farm does. "
                    + "Auto-queue toggle state and personalization are not part of this check."));
            dialog.add(layout);
            dialog.getFooter().add(new Button("Close", ev -> dialog.close()));
            dialog.open();
        });
        return b;
    }

    // -------------------------------------------------------------------------
    // Per-variation mapping (map a specific color/size/etc. to its own gcode)
    // -------------------------------------------------------------------------

    /** Opens a dialog listing this Etsy listing's variation combinations, each mappable to its own gcode. */
    private Button etsyVariationsButton(final EtsyRow row) {
        final Button b = new Button("Variations", new Icon(VaadinIcon.GRID_SMALL));
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        b.setEnabled(row.hasVariations());
        b.setTooltipText(row.hasVariations()
                ? "Map specific variations (color, size, …) of this listing to their own gcode"
                : "This listing has no variations - use its Map button for a single mapping");
        b.addClickListener(e -> openEtsyVariationsDialog(row));
        return b;
    }

    private void openEtsyVariationsDialog(final EtsyRow row) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Variations: " + row.title());
        dialog.setWidth("900px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        final Span loading = new Span("Loading variations…");
        layout.add(loading);
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", ev -> dialog.close()));
        dialog.open();

        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        new Thread(() -> {
            try {
                final List<EtsyApiClient.VariationCombo> combos = etsyClient.getListingVariations(row.listingId());
                ui.ifPresent(u -> u.access(() -> {
                    layout.removeAll();
                    if (combos.isEmpty()) {
                        layout.add(new Span("This listing has no variations. Use its Map button for a single listing-wide mapping."));
                        return;
                    }
                    final Runnable[] refresh = new Runnable[1];
                    refresh[0] = () -> {
                        layout.removeAll();
                        combos.forEach(c -> layout.add(buildEtsyVariationRow(row, c, refresh[0])));
                    };
                    refresh[0].run();
                }));
            } catch (Exception ex) {
                Log.errorf(ex, "MappingsView: Etsy variation fetch failed: %s", ex.getMessage());
                ui.ifPresent(u -> u.access(() -> {
                    layout.removeAll();
                    final Span err = new Span("Could not load variations: " + ex.getMessage());
                    err.getStyle().setColor("var(--lumo-error-text-color)");
                    layout.add(err);
                }));
            }
        }).start();
    }

    private Div buildEtsyVariationRow(final EtsyRow listing, final EtsyApiClient.VariationCombo combo, final Runnable refresh) {
        final String label = combo.variations().stream()
                .map(v -> com.tfyre.bambu.printer.HtmlText.plain(v.propertyName() + ": " + v.value()))
                .collect(Collectors.joining(", "));
        final String exactKey = listing.listingId() + "|" + EtsyMappingService.MappingKey.signatureOf(combo.variations());
        final boolean mapped = etsyMapping.entries().containsKey(exactKey);
        final boolean effective = etsyMapping.find(listing.listingId(), combo.variations()).isPresent();

        final Span name = new Span(label + (combo.sku().isBlank() ? "" : "  (SKU " + combo.sku() + ")"));
        name.getStyle().set("flex", "1");
        final Button edit = new Button(mapped ? "Edit" : "Map", new Icon(mapped ? VaadinIcon.EDIT : VaadinIcon.PLUS));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        edit.addClickListener(e -> openEditor("Etsy: " + listing.title() + " — " + label,
                etsyMapping.find(listing.listingId(), combo.variations()).map(EtsyMappingService.MappingEntry::parts).orElse(List.of()),
                parts -> {
                    etsyMapping.set(listing.listingId(), combo.variations(), new EtsyMappingService.MappingEntry(parts));
                    showNotification("Mapping saved for variation: " + label);
                    refresh.run();
                    renderAll();
                }));
        final Button test = testButton(effective ? "ok" : "—",
                () -> etsyMapping.find(listing.listingId(), combo.variations()).map(EtsyMappingService.MappingEntry::parts).orElse(List.of()),
                listing.title() + " — " + label);
        final Span stockLabel = new Span("stock:");
        stockLabel.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        final Div rowDiv = new Div(name, mappedBadge(mapped ? "✓ mapped" : "—"), edit, test, stockLabel, stockSummary("etsy", exactKey));
        rowDiv.getStyle().set("display", "flex").set("gap", "12px").set("align-items", "center").set("padding", "4px 0");
        return rowDiv;
    }

    /** Opens a dialog listing this eBay listing's variations, each mappable to its own gcode. */
    private Button ebayVariationsButton(final EbayRow row) {
        final int count = row.variations().size();
        final Button b = new Button(count > 0 ? "Variations (" + count + ")" : "Variations", new Icon(VaadinIcon.GRID_SMALL));
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        b.setEnabled(count > 0);
        b.setTooltipText(count > 0
                ? "Map each variation (color, size, …) of this listing to its own gcode"
                : "No variations found for this listing (load active listings from eBay first)");
        b.addClickListener(e -> openEbayVariationsDialog(row));
        return b;
    }

    private void openEbayVariationsDialog(final EbayRow row) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Variations: " + (row.title().isBlank() ? row.listingKey() : row.title()));
        dialog.setWidth("900px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        if (row.variations().isEmpty()) {
            layout.add(new Span("No variations found for this listing."));
        } else {
            final Runnable[] refresh = new Runnable[1];
            refresh[0] = () -> {
                layout.removeAll();
                row.variations().forEach(v -> layout.add(buildEbayVariationRow(row, v, refresh[0])));
            };
            refresh[0].run();
        }
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", ev -> dialog.close()));
        dialog.open();
    }

    private Div buildEbayVariationRow(final EbayRow listing, final EbayApiClient.EbayVariation variation, final Runnable refresh) {
        final String label = variation.specifics().stream()
                .map(v -> v.propertyName() + ": " + v.value())
                .collect(Collectors.joining(", "));
        // A variation with its own SKU is uniquely identified by that SKU, so map it SKU-wide (no specifics
        // signature) - the most robust match for an order line item that reports the same SKU. Only variations
        // without a SKU fall back to parent-item-id + specifics signature.
        final String key = variation.listingKey(listing.itemId());
        final List<EbayApiClient.Variation> sig = (variation.sku() == null || variation.sku().isBlank())
                ? variation.specifics() : List.of();
        final String exactKey = key + "|" + EbayMappingService.MappingKey.signatureOf(sig);
        final boolean mapped = ebayMapping.entries().containsKey(exactKey);
        final boolean effective = ebayMapping.find(key, sig).isPresent();

        final Span name = new Span(label + (variation.sku() == null || variation.sku().isBlank() ? "" : "  (SKU " + variation.sku() + ")"));
        name.getStyle().set("flex", "1");
        final Button edit = new Button(mapped ? "Edit" : "Map", new Icon(mapped ? VaadinIcon.EDIT : VaadinIcon.PLUS));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        edit.addClickListener(e -> openEditor("eBay: " + label + (key.isBlank() ? "" : " (" + key + ")"),
                ebayMapping.find(key, sig).map(EbayMappingService.MappingEntry::parts).orElse(List.of()),
                parts -> {
                    ebayMapping.set(key, sig, new EbayMappingService.MappingEntry(parts));
                    showNotification("Mapping saved for variation: " + label);
                    refresh.run();
                    renderAll();
                }));
        final Button test = testButton(effective ? "ok" : "—",
                () -> ebayMapping.find(key, sig).map(EbayMappingService.MappingEntry::parts).orElse(List.of()),
                label);
        final Span stockLabel = new Span("stock:");
        stockLabel.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "0.85em");
        final Div rowDiv = new Div(name, mappedBadge(mapped ? "✓ mapped" : "—"), edit, test, stockLabel, stockSummary("ebay", exactKey));
        rowDiv.getStyle().set("display", "flex").set("gap", "12px").set("align-items", "center").set("padding", "4px 0");
        return rowDiv;
    }

    /**
     * Read-only on-hand count for whatever a mapping prints, linking to the Inventory page - the one place stock
     * is edited. Stock is counted per printed part (see {@code StockService}), so a mapping with two parts shows
     * two numbers, in the order of its print jobs.
     * <p>
     * This used to be an editable field here, in both listing grids and in both variation dialogs, each writing a
     * per-listing count. Four places to type the same number is how the Etsy and eBay figures for one product
     * drifted apart.
     */
    private com.vaadin.flow.component.Component stockSummary(final String market, final String storageKey) {
        final List<MappingPart> parts = "etsy".equals(market)
                ? Optional.ofNullable(etsyMapping.entries().get(storageKey))
                        .map(EtsyMappingService.MappingEntry::parts).orElse(List.of())
                : Optional.ofNullable(ebayMapping.entries().get(storageKey))
                        .map(EbayMappingService.MappingEntry::parts).orElse(List.of());
        if (parts.isEmpty()) {
            final Span none = new Span("—");
            none.getStyle().setColor("var(--lumo-tertiary-text-color)");
            return none;
        }
        final String counts = parts.stream().map(p -> String.valueOf(stockService.get(p))).collect(Collectors.joining(" / "));
        final com.vaadin.flow.router.RouterLink link = new com.vaadin.flow.router.RouterLink(counts + " pcs", InventoryView.class);
        link.getElement().setAttribute("title", parts.stream()
                .map(p -> "%s: %d".formatted(com.tfyre.bambu.printer.StockService.describe(
                        com.tfyre.bambu.printer.StockService.partKey(p)).name(), stockService.get(p)))
                .collect(Collectors.joining(", ")) + " - edit on the Inventory page");
        return link;
    }

    /** Eye-slash to hide a never-printed listing, eye to bring it back (visible via "Show hidden listings"). */
    private Button hideButton(final String market, final String listingKey, final boolean hidden) {
        final Button b = new Button(new Icon(hidden ? VaadinIcon.EYE : VaadinIcon.EYE_SLASH));
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        b.setTooltipText(hidden
                ? "Unhide this listing"
                : "Hide this listing (not a printed product) - auto-queue will silently ignore it");
        b.addClickListener(e -> {
            if (hidden) {
                tracking.unhideListing(market, listingKey);
                showNotification("Listing unhidden");
            } else {
                tracking.hideListing(market, listingKey);
                showNotification("Listing hidden - auto-queue will ignore it");
            }
            renderAll();
        });
        return b;
    }

    private Span mappedBadge(final String state) {
        final Span s = new Span(state);
        s.getStyle().setColor("—".equals(state) ? "var(--lumo-error-text-color)" : "var(--lumo-success-text-color)");
        if (!"—".equals(state)) {
            s.getStyle().setFontWeight("bold");
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // Shared editor dialog + library helpers
    // -------------------------------------------------------------------------

    private void openEditor(final String title, final List<MappingPart> initial, final Consumer<List<MappingPart>> onSave) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("1100px");
        final MappingPartsPanel panel = new MappingPartsPanel(
                this::getLibraryFiles,
                this::loadPlateIds,
                queuer::listProjects,
                queuer::listProjectFiles,
                initial,
                parts -> {
                    onSave.accept(parts);
                    dialog.close();
                });
        dialog.add(panel);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()));
        dialog.open();
    }

    /** The library listing, read once per render of the page rather than once per unmapped row. */
    private List<String> suggestLibrary = List.of();
    private long suggestLibraryAt;

    /**
     * For an unmapped listing: a button naming the library file its title most resembles. It opens the normal
     * editor pre-filled with that file - nothing is saved until you press Save there, because the guess is from
     * the names alone and is sometimes the neighbouring product.
     * <p>
     * If the other marketplace already maps that file, its settings (copies per sale, AMS slot, material) are
     * copied, so the two listings end up printing exactly the same thing and share one row on the Inventory
     * page. Otherwise: PETG in slot 1, ASA in slot 2 when the file name says ASA, two copies for a "set of 2".
     */
    private Optional<Button> suggestButton(final String editorTitle, final String listingTitle,
            final Consumer<List<MappingPart>> onSave) {
        if (System.currentTimeMillis() - suggestLibraryAt > 5000) {
            // Not the H2D slices: the suggestion becomes the part's P1 file.
            suggestLibrary = getLibraryFiles().stream()
                    .filter(f -> !com.tfyre.bambu.printer.BambuConst.PrinterModel.FAMILY_H2D.equals(fileInfo.family(f))).toList();
            suggestLibraryAt = System.currentTimeMillis();
        }
        final Optional<String> file = com.tfyre.bambu.printer.MappingSuggester.suggest(listingTitle, suggestLibrary);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        final String path = file.get();
        final MappingPart suggested = java.util.stream.Stream.concat(
                etsyMapping.entries().values().stream().flatMap(e -> e.parts().stream()),
                ebayMapping.entries().values().stream().flatMap(e -> e.parts().stream()))
                .filter(p -> path.equals(p.path()))
                .findFirst()
                .orElseGet(() -> {
                    final boolean asa = path.toUpperCase().contains("ASA");
                    return new MappingPart(com.tfyre.bambu.printer.GcodeSource.LIBRARY, path, 1,
                            com.tfyre.bambu.printer.MappingSuggester.copiesFor(listingTitle), asa ? 1 : 0, asa ? "ASA" : "PETG");
                });
        final String shortName = path.substring(path.lastIndexOf('/') + 1).replaceAll("(?i)\\.gcode\\.3mf$|\\.3mf$", "");
        final Button b = new Button(shortName + "?", new Icon(VaadinIcon.MAGIC));
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        b.setTooltipText("Closest library file by name. Opens the editor pre-filled - check it and save.");
        b.addClickListener(e -> openEditor(editorTitle, List.of(suggested), onSave));
        return Optional.of(b);
    }

    /** Delegates to the queuer so mappings, batch print and auto-queue all see the same library (incl. projects). */
    private List<String> getLibraryFiles() {
        return queuer.listLibraryFiles();
    }

    private List<Integer> loadPlateIds(final String filename) {
        final Path file = Path.of(config.batchPrint().library()).resolve(filename);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final ProjectFile projectFile = projectFileInstance.get();
        try {
            return projectFile.setup(filename, file.toFile()).getPlates().stream().map(Plate::plateId).toList();
        } catch (Exception ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

}
