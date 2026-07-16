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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** One row in the Etsy listings table. */
    public record EtsyRow(long listingId, String title, int quantity, String mappedState, boolean hidden, String imageUrl,
            boolean hasVariations) {
    }

    /** One row in the eBay listing-keys table. */
    public record EbayRow(String listingKey, String title, String mappedState, boolean hidden, String imageUrl,
            String itemId, List<EbayApiClient.EbayVariation> variations) {
    }

    /** Whether hidden (never-printed) listings are shown in the tables. */
    private boolean showHidden;

    /** One row in the all-saved-mappings table. */
    public record SavedRow(String market, String storageKey, String listing, String variations, String summary) {
    }

    @Inject
    com.tfyre.bambu.printer.MarketListingCache listingCache;
    @Inject
    com.tfyre.bambu.printer.StockService stockService;

    private final Grid<EtsyRow> etsyGrid = new Grid<>();
    private final Grid<EbayRow> ebayGrid = new Grid<>();
    private final Grid<SavedRow> savedGrid = new Grid<>();
    private final Span etsyStatus = new Span();
    private final Span ebayStatus = new Span();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        addClassName("ai-settings-view");
        addClassName("ai-settings-wide");
        setPadding(true);
        setSpacing(true);

        add(new H3("Listing → GCODE Mappings"));
        add(new Span("Assign print jobs to your shop listings here, before orders arrive - a mapped listing is "
                + "what lets auto-queue handle an order with zero clicks. Mappings saved here are listing-wide "
                + "(they apply to every variation); per-variation overrides can still be saved from the order pages. "
                + "Hide listings that are never printed (digital items, add-ons) - hidden, unmapped listings are "
                + "silently ignored by auto-queue instead of blocking orders with a 'not mapped' alert."));

        final com.vaadin.flow.component.checkbox.Checkbox showHiddenToggle
                = new com.vaadin.flow.component.checkbox.Checkbox("Show hidden listings");
        showHiddenToggle.setValue(showHidden);
        showHiddenToggle.addValueChangeListener(e -> {
            showHidden = Boolean.TRUE.equals(e.getValue());
            renderAll();
        });
        add(showHiddenToggle);

        add(buildEtsySection());
        add(buildEbaySection());
        add(buildSavedSection());
        renderAll();
    }

    private void renderAll() {
        renderEtsy();
        renderEbay();
        renderSaved();
    }

    // -------------------------------------------------------------------------
    // Etsy - full active-listing pull
    // -------------------------------------------------------------------------

    private Div buildEtsySection() {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4("Etsy listings"));

        final Button load = new Button("Load active listings from Etsy", new Icon(VaadinIcon.REFRESH));
        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.setEnabled(etsyOauth.isConnected());
        if (!etsyOauth.isConnected()) {
            sec.add(new Span("Connect Etsy on the Etsy Sales Orders page first."));
        }
        load.addClickListener(e -> {
            load.setEnabled(false);
            etsyStatus.setText("Loading…");
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            new Thread(() -> {
                try {
                    final List<EtsyApiClient.Listing> listings = etsyClient.getActiveListings();
                    ui.ifPresent(u -> u.access(() -> {
                        listingCache.setEtsy(listings);
                        etsyStatus.setText("%d active listing(s)".formatted(listings.size()));
                        renderEtsy();
                        load.setEnabled(true);
                    }));
                } catch (Exception ex) {
                    Log.errorf(ex, "MappingsView: listing fetch failed: %s", ex.getMessage());
                    ui.ifPresent(u -> u.access(() -> {
                        etsyStatus.setText("");
                        showError("Could not load listings: " + ex.getMessage());
                        load.setEnabled(true);
                    }));
                }
            }).start();
        });
        final Div bar = new Div(load, etsyStatus);
        bar.getStyle().set("display", "flex").set("gap", "12px").set("align-items", "center");
        sec.add(bar);

        if (etsyGrid.getColumns().isEmpty()) {
            etsyGrid.addComponentColumn(row -> thumbnail(row.imageUrl())).setHeader("").setAutoWidth(true).setFlexGrow(0);
            etsyGrid.addColumn(EtsyRow::title).setHeader("Listing").setFlexGrow(1);
            etsyGrid.addColumn(EtsyRow::listingId).setHeader("ID").setAutoWidth(true);
            etsyGrid.addColumn(EtsyRow::quantity).setHeader("Listed qty").setAutoWidth(true);
            etsyGrid.addComponentColumn(row -> mappedBadge(row.mappedState())).setHeader("Mapping").setAutoWidth(true);
            etsyGrid.addComponentColumn(row -> stockField("etsy", row.listingId() + "|")).setHeader("On-hand").setAutoWidth(true);
            etsyGrid.addComponentColumn(row -> {
                final Button edit = new Button("—".equals(row.mappedState()) ? "Map" : "Edit",
                        new Icon("—".equals(row.mappedState()) ? VaadinIcon.PLUS : VaadinIcon.EDIT));
                edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                edit.addClickListener(e -> openEditor("Etsy: " + row.title(),
                        etsyMapping.find(row.listingId(), List.of()).map(EtsyMappingService.MappingEntry::parts).orElse(List.of()),
                        parts -> {
                            etsyMapping.set(row.listingId(), List.of(), new EtsyMappingService.MappingEntry(parts));
                            showNotification("Mapping saved for listing %d".formatted(row.listingId()));
                            renderAll();
                        }));
                return new Div(edit, testButton(row.mappedState(),
                        () -> etsyMapping.find(row.listingId(), List.of()).map(EtsyMappingService.MappingEntry::parts).orElse(List.of()),
                        row.title()), etsyVariationsButton(row), hideButton("etsy", String.valueOf(row.listingId()), row.hidden()));
            }).setHeader("").setAutoWidth(true);
            etsyGrid.setWidth("100%");
            etsyGrid.setAllRowsVisible(true);
            etsyGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        }
        sec.add(etsyGrid);
        return sec;
    }

    private void renderEtsy() {
        final Map<String, EtsyMappingService.MappingEntry> saved = etsyMapping.entries();
        etsyGrid.setItems(listingCache.getEtsy().stream()
                .map(l -> new EtsyRow(l.listingId(), l.title(), l.quantityAvailable(),
                        mappedState(saved, String.valueOf(l.listingId())),
                        tracking.isListingHidden("etsy", String.valueOf(l.listingId())),
                        l.imageUrl(), l.hasVariations()))
                .filter(r -> showHidden || !r.hidden())
                .toList());
        etsyGrid.setVisible(!listingCache.getEtsy().isEmpty());
        listingCache.etsyLoadedAt().ifPresent(at -> etsyStatus.setText(
                "%d active listing(s) · cached %s".formatted(listingCache.getEtsy().size(), timeAgo(at))));
    }

    // -------------------------------------------------------------------------
    // eBay - saved mappings + listing keys from open orders (scope can't list inventory)
    // -------------------------------------------------------------------------

    private Div buildEbaySection() {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4("eBay listings"));

        final Button load = new Button("Load active listings from eBay", new Icon(VaadinIcon.REFRESH));
        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.setEnabled(ebayOauth.isConnected());
        if (!ebayOauth.isConnected()) {
            sec.add(new Span("Connect eBay on the eBay Sales Orders page first."));
        }
        load.addClickListener(e -> {
            load.setEnabled(false);
            ebayStatus.setText("Loading…");
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            new Thread(() -> {
                try {
                    final List<EbayApiClient.EbayListing> listings = ebayClient.getActiveListings();
                    ui.ifPresent(u -> u.access(() -> {
                        listingCache.setEbay(listings);
                        ebayStatus.setText("%d active listing(s)".formatted(listings.size()));
                        renderEbay();
                        load.setEnabled(true);
                    }));
                } catch (Exception ex) {
                    Log.errorf(ex, "MappingsView: eBay listing fetch failed: %s", ex.getMessage());
                    ui.ifPresent(u -> u.access(() -> {
                        ebayStatus.setText("");
                        showError("Could not load eBay listings: " + ex.getMessage());
                        load.setEnabled(true);
                    }));
                }
            }).start();
        });
        final Div bar = new Div(load, ebayStatus);
        bar.getStyle().set("display", "flex").set("gap", "12px").set("align-items", "center");
        sec.add(bar);
        sec.add(new Span("Rows also include every listing key already mapped or appearing in an open order. "
                + "If loading reports a permissions error, Disconnect and reconnect eBay once - the listing "
                + "permission was added after your original connection."));

        if (ebayGrid.getColumns().isEmpty()) {
            ebayGrid.addComponentColumn(row -> thumbnail(row.imageUrl())).setHeader("").setAutoWidth(true).setFlexGrow(0);
            ebayGrid.addColumn(EbayRow::listingKey).setHeader("SKU / item id").setAutoWidth(true);
            ebayGrid.addColumn(EbayRow::title).setHeader("Title").setFlexGrow(1);
            ebayGrid.addComponentColumn(row -> mappedBadge(row.mappedState())).setHeader("Mapping").setAutoWidth(true);
            ebayGrid.addComponentColumn(row -> stockField("ebay", row.listingKey() + "|")).setHeader("On-hand").setAutoWidth(true);
            ebayGrid.addComponentColumn(row -> {
                final Button edit = new Button("—".equals(row.mappedState()) ? "Map" : "Edit",
                        new Icon("—".equals(row.mappedState()) ? VaadinIcon.PLUS : VaadinIcon.EDIT));
                edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                edit.addClickListener(e -> openEditor("eBay: " + row.listingKey(),
                        ebayMapping.find(row.listingKey(), List.of()).map(EbayMappingService.MappingEntry::parts).orElse(List.of()),
                        parts -> {
                            ebayMapping.set(row.listingKey(), List.of(), new EbayMappingService.MappingEntry(parts));
                            showNotification("Mapping saved for %s".formatted(row.listingKey()));
                            renderAll();
                        }));
                return new Div(edit, testButton(row.mappedState(),
                        () -> ebayMapping.find(row.listingKey(), List.of()).map(EbayMappingService.MappingEntry::parts).orElse(List.of()),
                        row.listingKey()), ebayVariationsButton(row), hideButton("ebay", row.listingKey(), row.hidden()));
            }).setHeader("").setAutoWidth(true);
            ebayGrid.setWidth("100%");
            ebayGrid.setAllRowsVisible(true);
            ebayGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        }
        sec.add(ebayGrid);
        return sec;
    }

    private void renderEbay() {
        final Map<String, EbayMappingService.MappingEntry> saved = ebayMapping.entries();
        // Known keys: fetched active listings first (best titles + images), then open orders, then bare mapped keys
        final Map<String, String> titles = new LinkedHashMap<>();
        final Map<String, String> images = new LinkedHashMap<>();
        final Map<String, EbayApiClient.EbayListing> byKey = new LinkedHashMap<>();
        listingCache.getEbay().forEach(l -> {
            titles.putIfAbsent(l.listingKey(), l.title());
            byKey.putIfAbsent(l.listingKey(), l);
            if (l.imageUrl() != null && !l.imageUrl().isBlank()) {
                images.putIfAbsent(l.listingKey(), l.imageUrl());
            }
        });
        ebayPolling.getOrders().forEach(o -> o.lineItems().forEach(li -> {
            final String key = li.listingKey();
            if (key != null && !key.isBlank()) {
                titles.putIfAbsent(key, li.title());
            }
        }));
        saved.keySet().stream()
                .map(k -> k.substring(0, k.indexOf('|') < 0 ? k.length() : k.indexOf('|')))
                .forEach(k -> titles.putIfAbsent(k, ""));
        ebayGrid.setItems(titles.entrySet().stream()
                .map(e -> {
                    final EbayApiClient.EbayListing l = byKey.get(e.getKey());
                    return new EbayRow(e.getKey(), e.getValue(), mappedState(saved, e.getKey()),
                            tracking.isListingHidden("ebay", e.getKey()), images.getOrDefault(e.getKey(), ""),
                            l == null ? e.getKey() : l.itemId(),
                            l == null ? List.of() : l.variations());
                })
                .filter(r -> showHidden || !r.hidden())
                .sorted((a, b) -> a.listingKey().compareToIgnoreCase(b.listingKey()))
                .toList());
        ebayGrid.setVisible(!titles.isEmpty());
        listingCache.ebayLoadedAt().ifPresent(at -> ebayStatus.setText(
                "%d active listing(s) · cached %s".formatted(listingCache.getEbay().size(), timeAgo(at))));
    }

    // -------------------------------------------------------------------------
    // All saved mappings (both marketplaces, including per-variation entries)
    // -------------------------------------------------------------------------

    private Div buildSavedSection() {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4("All saved mappings"));

        if (savedGrid.getColumns().isEmpty()) {
            savedGrid.addColumn(SavedRow::market).setHeader("Market").setAutoWidth(true);
            savedGrid.addColumn(SavedRow::listing).setHeader("Listing").setAutoWidth(true);
            savedGrid.addColumn(SavedRow::variations).setHeader("Variation").setAutoWidth(true);
            savedGrid.addColumn(SavedRow::summary).setHeader("Print jobs").setFlexGrow(1);
            savedGrid.addComponentColumn(row -> stockField(row.market(), row.storageKey())).setHeader("On-hand").setAutoWidth(true);
            savedGrid.addComponentColumn(row -> {
                final Button edit = new Button(new Icon(VaadinIcon.EDIT));
                edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                edit.setTooltipText("Edit this mapping");
                edit.addClickListener(e -> {
                    final List<MappingPart> initial = "etsy".equals(row.market())
                            ? etsyMapping.entries().getOrDefault(row.storageKey(), new EtsyMappingService.MappingEntry(List.of())).parts()
                            : ebayMapping.entries().getOrDefault(row.storageKey(), new EbayMappingService.MappingEntry(List.of())).parts();
                    openEditor("%s: %s %s".formatted(row.market(), row.listing(), row.variations()), initial, parts -> {
                        if ("etsy".equals(row.market())) {
                            etsyMapping.putByKey(row.storageKey(), new EtsyMappingService.MappingEntry(parts));
                        } else {
                            ebayMapping.putByKey(row.storageKey(), new EbayMappingService.MappingEntry(parts));
                        }
                        showNotification("Mapping updated");
                        renderAll();
                    });
                });
                final Button del = new Button(new Icon(VaadinIcon.TRASH));
                del.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
                del.setTooltipText("Delete this mapping");
                del.addClickListener(e -> com.tfyre.bambu.YesNoCancelDialog.show(
                        "Delete the mapping for %s %s?".formatted(row.listing(), row.variations()), ync -> {
                            if (!ync.isConfirmed()) {
                                return;
                            }
                            if ("etsy".equals(row.market())) {
                                etsyMapping.removeByKey(row.storageKey());
                            } else {
                                ebayMapping.removeByKey(row.storageKey());
                            }
                            showNotification("Mapping deleted");
                            renderAll();
                        }));
                final Div actions = new Div(edit, del);
                return actions;
            }).setHeader("").setAutoWidth(true);
            savedGrid.setWidth("100%");
            savedGrid.setAllRowsVisible(true);
            savedGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        }
        sec.add(savedGrid);
        return sec;
    }

    private void renderSaved() {
        final List<SavedRow> rows = new ArrayList<>();
        etsyMapping.entries().forEach((k, v) -> rows.add(savedRow("etsy", k, v.parts())));
        ebayMapping.entries().forEach((k, v) -> rows.add(savedRow("ebay", k, v.parts())));
        rows.sort((a, b) -> (a.market() + a.listing()).compareToIgnoreCase(b.market() + b.listing()));
        savedGrid.setItems(rows);
    }

    private SavedRow savedRow(final String market, final String storageKey, final List<MappingPart> parts) {
        final int sep = storageKey.indexOf('|');
        final String listing = sep < 0 ? storageKey : storageKey.substring(0, sep);
        final String variations = sep < 0 || sep == storageKey.length() - 1 ? "(all)" : storageKey.substring(sep + 1);
        return new SavedRow(market, storageKey, listing, variations, partsSummary(parts));
    }

    private static String partsSummary(final List<MappingPart> parts) {
        if (parts.isEmpty()) {
            return "(no parts)";
        }
        return parts.stream()
                .map(p -> "%s plate %d ×%d%s%s".formatted(p.path(), p.plateId(), p.copiesPerUnit(),
                        p.amsSlot() != null ? " · " + AmsSlotSupport.label(p.amsSlot()) : "",
                        p.filamentType() != null ? " · " + p.filamentType() : ""))
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
        return "%dh %dm ago".formatted(secs / 3600, (secs % 3600) / 60);
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
                .map(v -> v.propertyName() + ": " + v.value())
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
        final Div rowDiv = new Div(name, mappedBadge(mapped ? "✓ mapped" : "—"), edit, test, stockLabel, stockField("etsy", exactKey));
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
        final Div rowDiv = new Div(name, mappedBadge(mapped ? "✓ mapped" : "—"), edit, test, stockLabel, stockField("ebay", exactKey));
        rowDiv.getStyle().set("display", "flex").set("gap", "12px").set("align-items", "center").set("padding", "4px 0");
        return rowDiv;
    }

    /**
     * A small on-hand stock editor for a mapping storage key. When an order arrives, in-stock units are fulfilled
     * from here (decremented, not printed) - so bump this when you print extra or take a return. Default 0.
     */
    private com.vaadin.flow.component.textfield.IntegerField stockField(final String market, final String storageKey) {
        final com.vaadin.flow.component.textfield.IntegerField f = new com.vaadin.flow.component.textfield.IntegerField();
        f.setValue(stockService.get(market, storageKey));
        f.setMin(0);
        f.setStepButtonsVisible(true);
        f.setWidth("128px");
        f.setTooltipText("On-hand stock. New orders are filled from this first (decremented, not printed). "
                + "Increase it when you print spares or take a return.");
        f.addValueChangeListener(e -> {
            final int v = e.getValue() == null ? 0 : e.getValue();
            stockService.set(market, storageKey, v);
        });
        return f;
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
                initial,
                parts -> {
                    onSave.accept(parts);
                    dialog.close();
                });
        dialog.add(panel);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()));
        dialog.open();
    }

    private List<String> getLibraryFiles() {
        try (Stream<Path> stream = Files.list(Path.of(config.batchPrint().library()))) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(".3mf"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
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
