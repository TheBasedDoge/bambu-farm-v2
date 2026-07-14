package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOAuthService;
import com.tfyre.bambu.printer.MappingPart;
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
    EbayOrderPollingService ebayPolling;
    @Inject
    Instance<ProjectFile> projectFileInstance;

    /** One row in the Etsy listings table. */
    public record EtsyRow(long listingId, String title, int quantity, String mappedState) {
    }

    /** One row in the eBay listing-keys table. */
    public record EbayRow(String listingKey, String title, String mappedState) {
    }

    /** One row in the all-saved-mappings table. */
    public record SavedRow(String market, String storageKey, String listing, String variations, String summary) {
    }

    private final Grid<EtsyRow> etsyGrid = new Grid<>();
    private final Grid<EbayRow> ebayGrid = new Grid<>();
    private final Grid<SavedRow> savedGrid = new Grid<>();
    private final Span etsyStatus = new Span();

    /** Fetched on demand via the button; kept for re-renders while the view lives. */
    private List<EtsyApiClient.Listing> etsyListings = List.of();

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
                + "(they apply to every variation); per-variation overrides can still be saved from the order pages."));

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
                        etsyListings = listings;
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
            etsyGrid.addColumn(EtsyRow::title).setHeader("Listing").setFlexGrow(1);
            etsyGrid.addColumn(EtsyRow::listingId).setHeader("ID").setAutoWidth(true);
            etsyGrid.addColumn(EtsyRow::quantity).setHeader("Stock").setAutoWidth(true);
            etsyGrid.addComponentColumn(row -> mappedBadge(row.mappedState())).setHeader("Mapping").setAutoWidth(true);
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
                return edit;
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
        etsyGrid.setItems(etsyListings.stream()
                .map(l -> new EtsyRow(l.listingId(), l.title(), l.quantityAvailable(), mappedState(saved, String.valueOf(l.listingId()))))
                .toList());
        etsyGrid.setVisible(!etsyListings.isEmpty());
    }

    // -------------------------------------------------------------------------
    // eBay - saved mappings + listing keys from open orders (scope can't list inventory)
    // -------------------------------------------------------------------------

    private Div buildEbaySection() {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4("eBay listings"));
        sec.add(new Span("eBay's fulfillment-only API scope can't pull your listings, so this table shows every "
                + "listing key (SKU or item id) already mapped or currently appearing in an open order."));

        if (ebayGrid.getColumns().isEmpty()) {
            ebayGrid.addColumn(EbayRow::listingKey).setHeader("SKU / item id").setAutoWidth(true);
            ebayGrid.addColumn(EbayRow::title).setHeader("Title (from open orders)").setFlexGrow(1);
            ebayGrid.addComponentColumn(row -> mappedBadge(row.mappedState())).setHeader("Mapping").setAutoWidth(true);
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
                return edit;
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
        // Known keys: everything mapped + everything in open orders (with a title when we have one)
        final Map<String, String> titles = new LinkedHashMap<>();
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
                .map(e -> new EbayRow(e.getKey(), e.getValue(), mappedState(saved, e.getKey())))
                .sorted((a, b) -> a.listingKey().compareToIgnoreCase(b.listingKey()))
                .toList());
        ebayGrid.setVisible(!titles.isEmpty());
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
