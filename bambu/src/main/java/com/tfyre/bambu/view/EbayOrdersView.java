package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOAuthService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.view.batchprint.Plate;
import com.tfyre.bambu.view.batchprint.ProjectFile;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * eBay Sales Orders — pulls open (not-started/in-progress) orders from eBay, lets each listing (identified by SKU,
 * or a specific variation combination of it) be mapped to a gcode file + plate in the batch print library, and
 * queues that file for printing. Admin only.
 * <p>
 * This page does not talk back to eBay about fulfillment/tracking - shipping and marking an order as shipped on
 * eBay is still a manual step there. "Dismiss" here only hides the order from this list locally.
 */
@Route(value = "ebay-orders", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("eBay Sales Orders")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class EbayOrdersView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    @Inject
    BambuConfig config;
    @Inject
    EbayOAuthService oauth;
    @Inject
    EbayOrderPollingService polling;
    @Inject
    EbayMappingService mappingService;
    @Inject
    BambuPrinters printers;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    Instance<ProjectFile> projectFileInstance;

    private final Div content = new Div();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);
        content.addClassName("etsy-orders-content");
        add(new H3("eBay Sales Orders"), content);
        render();
        // The scheduled poll only runs every bambu.ebay.poll-interval - without this, a freshly-connected
        // account (or the first page load after a restart) shows "no orders" until that timer happens to fire.
        if (oauth.isConnected() && polling.getLastPolled().isEmpty()) {
            final Optional<UI> ui = Optional.of(UI.getCurrent());
            new Thread(() -> {
                polling.refresh();
                ui.ifPresent(u -> u.access(this::render));
            }).start();
        }
    }

    private void render() {
        content.removeAll();

        if (oauth.isConfigured()) {
            content.add(buildConnectionBar());
        } else {
            final Span note = new Span("⚠ eBay is not configured. Set bambu.ebay.client-id, "
                    + "bambu.ebay.client-secret and bambu.ebay.ru-name to enable this page.");
            note.getStyle().setColor("var(--lumo-error-text-color)");
            content.add(note);
            return;
        }

        if (!oauth.isConnected()) {
            content.add(buildConnectPrompt());
            return;
        }

        final List<EbayApiClient.Order> orders = polling.getOrders();
        polling.getLastError().ifPresent(err -> {
            final Span errSpan = new Span("⚠ Last poll failed: " + err);
            errSpan.getStyle().setColor("var(--lumo-error-text-color)");
            content.add(errSpan);
        });
        polling.getLastPolled().ifPresent(t -> {
            final Span info = new Span("Last checked: " + DATE_FMT.format(t.atZone(ZoneId.systemDefault())));
            info.getStyle().setColor("var(--lumo-secondary-text-color)");
            content.add(info);
        });

        if (orders.isEmpty()) {
            content.add(new Span("No open orders 🎉"));
            return;
        }

        orders.forEach(o -> content.add(buildOrderCard(o)));
    }

    private Div buildConnectionBar() {
        final Div bar = new Div();
        final HorizontalLayout row = new HorizontalLayout();
        row.setAlignItems(Alignment.CENTER);

        if (oauth.isConnected()) {
            final Span status = new Span("● Connected to eBay");
            status.getStyle().setColor("var(--lumo-success-text-color)").setFontWeight("bold");
            final Button refresh = new Button("Refresh Orders", new Icon(VaadinIcon.REFRESH));
            refresh.addClickListener(e -> {
                refresh.setEnabled(false);
                final Optional<UI> ui = Optional.of(UI.getCurrent());
                new Thread(() -> {
                    polling.refresh();
                    ui.ifPresent(u -> u.access(() -> {
                        refresh.setEnabled(true);
                        render();
                        showNotification("Orders refreshed");
                    }));
                }).start();
            });
            final Button disconnect = new Button("Disconnect");
            disconnect.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            disconnect.addClickListener(e -> {
                oauth.disconnect();
                render();
            });
            row.add(status, refresh, disconnect);
        }
        bar.add(row);
        return bar;
    }

    private Div buildConnectPrompt() {
        final Div div = new Div();
        div.add(new Span("Connect your eBay seller account to pull open orders here."));
        final Optional<String> url = oauth.buildAuthorizeUrl();
        if (url.isEmpty()) {
            div.add(new Span(" (configuration incomplete)"));
            return div;
        }
        final Button connectBtn = new Button("Connect to eBay", new Icon(VaadinIcon.SIGN_IN));
        connectBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectBtn.addClickListener(e -> UI.getCurrent().getPage().setLocation(url.get()));
        div.add(new Div(connectBtn));
        return div;
    }

    private List<String> getLibraryFiles() {
        final Path path = Path.of(config.batchPrint().library());
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toLowerCase().endsWith(".3mf"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            Log.error(ex.getMessage(), ex);
            return List.of();
        }
    }

    private Div buildOrderCard(final EbayApiClient.Order order) {
        final Div card = new Div();
        card.addClassName("ai-settings-section");

        final HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.add(new H4("Order #%s - %s".formatted(order.orderId(), order.buyerUsername())));
        final Span when = new Span(DATE_FMT.format(order.creationDate().atZone(ZoneId.systemDefault())));
        when.getStyle().setColor("var(--lumo-secondary-text-color)");
        titleRow.add(when);
        final Span statusBadge = new Span(order.fulfillmentStatus());
        statusBadge.getStyle().setColor("var(--lumo-secondary-text-color)");
        titleRow.add(statusBadge);
        final Button dismiss = new Button("Dismiss", new Icon(VaadinIcon.EYE_SLASH));
        dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dismiss.setTooltipText("Hide this order from the list (does not mark it shipped on eBay)");
        dismiss.addClickListener(e -> {
            polling.dismiss(order.orderId());
            render();
        });
        titleRow.add(dismiss);
        card.add(titleRow);

        order.lineItems().forEach(li -> card.add(buildLineItemRow(li)));
        return card;
    }

    private Div buildLineItemRow(final EbayApiClient.LineItem li) {
        final Div row = new Div();
        row.addClassName("etsy-transaction-row");

        final Span titleSpan = new Span("%dx %s".formatted(li.quantity(), li.title()));
        titleSpan.getStyle().setFontWeight("bold");
        row.add(titleSpan);

        if (li.sku() != null && !li.sku().isBlank()) {
            row.add(new Div(new Span("SKU: " + li.sku())));
        }

        if (!li.variationAspects().isEmpty()) {
            final String variationText = li.variationAspects().stream()
                    .map(v -> v.propertyName() + ": " + v.value())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            row.add(new Div(new Span(variationText)));
        }
        li.personalization().ifPresent(p -> {
            final Span pSpan = new Span("Personalization: " + p);
            pSpan.getStyle().set("font-style", "italic");
            row.add(new Div(pSpan));
        });

        final String listingKey = li.listingKey();
        final List<MappingPart> initialParts = mappingService.find(listingKey, li.variationAspects())
                .map(EbayMappingService.MappingEntry::parts)
                .orElse(List.of());
        final List<String> printerNames = printers.getPrintersDetail().stream()
                .map(BambuPrinters.PrinterDetail::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();

        final MappingPartsPanel panel = new MappingPartsPanel(
                this::getLibraryFiles,
                this::loadPlateIds,
                printerNames,
                initialParts,
                li.quantity(),
                parts -> {
                    if (listingKey == null || listingKey.isBlank()) {
                        showError("No SKU or item id to map against for this line item");
                        return;
                    }
                    mappingService.set(listingKey, li.variationAspects(), new EbayMappingService.MappingEntry(parts));
                    showNotification("Mapping saved for %s".formatted(listingKey));
                },
                (parts, selectedPrinters) -> {
                    final GcodeMappingQueuer.QueueResult result = queuer.queue(parts, li.quantity(), selectedPrinters);
                    if (result.totalQueued() > 0) {
                        showNotification("Queued %d job(s) for %s".formatted(result.totalQueued(), listingKey));
                    }
                    result.errors().forEach(this::showError);
                });
        row.add(panel);
        return row;
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
