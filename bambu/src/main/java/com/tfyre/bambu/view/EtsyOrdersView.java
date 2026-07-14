package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOAuthService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.OrderTrackingService;
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
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Etsy Sales Orders — pulls paid/unshipped receipts from Etsy, lets each listing (or a specific variation
 * combination of it) be mapped to a gcode file + plate in the batch print library, and queues that file for
 * printing. Admin only.
 * <p>
 * This page does not talk back to Etsy about fulfillment/tracking - shipping and marking an order as shipped on
 * Etsy is still a manual step on etsy.com. "Dismiss" here only hides the order from this list locally.
 */
@Route(value = "etsy-orders", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Etsy Sales Orders")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class EtsyOrdersView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    @Inject
    BambuConfig config;
    @Inject
    EtsyOAuthService oauth;
    @Inject
    EtsyApiClient client;
    @Inject
    EtsyOrderPollingService polling;
    @Inject
    EtsyMappingService mappingService;
    @Inject
    BambuPrinters printers;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    Instance<ProjectFile> projectFileInstance;
    @Inject
    OrderTrackingService tracking;
    @Inject
    com.tfyre.bambu.printer.AutoQueueService autoQueue;

    private final Div content = new Div();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);
        content.addClassName("etsy-orders-content");
        add(buildHeader(), content);
        render();
        // The scheduled poll only runs every bambu.etsy.poll-interval - without this, a freshly-connected shop
        // (or the first page load after a restart) shows "no orders" until that timer happens to fire.
        if (oauth.isConnected() && polling.getLastPolled().isEmpty()) {
            final Optional<UI> ui = Optional.of(UI.getCurrent());
            new Thread(() -> {
                polling.refresh();
                ui.ifPresent(u -> u.access(this::render));
            }).start();
        }
    }

    private Div buildHeader() {
        final Div header = new Div();
        header.add(new H3("Etsy Sales Orders"));
        return header;
    }

    private void render() {
        content.removeAll();

        if (oauth.isConfigured()) {
            content.add(buildConnectionBar());
        } else {
            final Span note = new Span("⚠ Etsy is not configured. Set bambu.etsy.client-id, "
                    + "bambu.etsy.shared-secret, bambu.etsy.shop-id and bambu.etsy.redirect-uri to enable this page.");
            note.getStyle().setColor("var(--lumo-error-text-color)");
            content.add(note);
            return;
        }

        if (!oauth.isConnected()) {
            content.add(buildConnectPrompt());
            return;
        }

        final List<EtsyApiClient.Receipt> receipts = polling.getReceipts();
        polling.getLastError().ifPresent(err -> {
            final Span errSpan = new Span("⚠ Last poll failed: " + err);
            errSpan.getStyle().setColor("var(--lumo-error-text-color)");
            content.add(errSpan);
            if (err.contains("does not own Shop")) {
                content.add(buildShopIdLookup());
            }
        });
        polling.getLastPolled().ifPresent(t -> {
            final Span info = new Span("Last checked: " + DATE_FMT.format(t.atZone(ZoneId.systemDefault())));
            info.getStyle().setColor("var(--lumo-secondary-text-color)");
            content.add(info);
        });

        if (receipts.isEmpty()) {
            content.add(new Span("No unfulfilled orders 🎉"));
            return;
        }

        receipts.forEach(r -> content.add(buildReceiptCard(r)));
    }

    private Div buildConnectionBar() {
        final Div bar = new Div();
        final HorizontalLayout row = new HorizontalLayout();
        row.setAlignItems(Alignment.CENTER);

        if (oauth.isConnected()) {
            final Span status = new Span("● Connected to Etsy");
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
            final com.vaadin.flow.component.checkbox.Checkbox autoQueueToggle
                    = new com.vaadin.flow.component.checkbox.Checkbox("Auto-queue new orders");
            autoQueueToggle.setValue(autoQueue.isEnabled());
            autoQueueToggle.setTooltipText("When a poll finds a new order whose line items are all mapped, queue "
                    + "the print jobs automatically - each part goes to a printer that currently has its required "
                    + "filament loaded (idle printers first, then shortest queue). Orders with unmapped items or "
                    + "no filament match are skipped with a notification. One global switch, shared with eBay.");
            autoQueueToggle.addValueChangeListener(e -> {
                autoQueue.setEnabled(Boolean.TRUE.equals(e.getValue()));
                showNotification("Auto-queue " + (autoQueue.isEnabled() ? "enabled" : "disabled"));
            });
            row.add(status, refresh, disconnect, autoQueueToggle);
        }
        bar.add(row);
        return bar;
    }

    private Div buildConnectPrompt() {
        final Div div = new Div();
        div.add(new Span("Connect your Etsy shop to pull unfulfilled orders here."));
        final Optional<String> url = oauth.buildAuthorizeUrl();
        if (url.isEmpty()) {
            div.add(new Span(" (configuration incomplete)"));
            return div;
        }
        final Button connectBtn = new Button("Connect to Etsy", new Icon(VaadinIcon.SIGN_IN));
        connectBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        connectBtn.addClickListener(e -> UI.getCurrent().getPage().setLocation(url.get()));
        div.add(new Div(connectBtn));
        return div;
    }

    private Div buildShopIdLookup() {
        final Div div = new Div();
        div.addClassName("ai-settings-section");
        div.add(new Span("bambu.etsy.shop-id doesn't match the account you connected with."));
        final Button lookupBtn = new Button("Look up my shop ID");
        lookupBtn.addClickListener(e -> {
            try {
                final Optional<Long> shopId = client.findMyShopId();
                if (shopId.isPresent()) {
                    showNotification("Your shop ID is %d - set bambu.etsy.shop-id=%d and restart bambuweb"
                            .formatted(shopId.get(), shopId.get()), Duration.ofSeconds(15));
                } else {
                    showError("Etsy didn't return a shop for this account - is it a buyer-only account with no shop?");
                }
            } catch (Exception ex) {
                showError("Lookup failed: " + ex.getMessage());
            }
        });
        div.add(new Div(lookupBtn));
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

    private Div buildReceiptCard(final EtsyApiClient.Receipt receipt) {
        final Div card = new Div();
        card.addClassName("ai-settings-section");

        final HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.add(new H4("Order #%d - %s".formatted(receipt.receiptId(), receipt.buyerName())));
        final Span when = new Span(DATE_FMT.format(receipt.createTimestamp().atZone(ZoneId.systemDefault())));
        when.getStyle().setColor("var(--lumo-secondary-text-color)");
        titleRow.add(when);
        if (!receipt.status().isBlank()) {
            final Span statusBadge = new Span(receipt.status());
            statusBadge.getStyle().setColor("var(--lumo-secondary-text-color)");
            titleRow.add(statusBadge);
        }
        // "queued ✓" badge - persisted, so it survives restarts and prevents double-printing an order
        final String orderKey = String.valueOf(receipt.receiptId());
        final Span queuedBadge = new Span("✓ queued");
        queuedBadge.getStyle().setColor("var(--lumo-success-text-color)").setFontWeight("bold");
        tracking.queuedAt("etsy", orderKey).ifPresentOrElse(
                at -> queuedBadge.setTitle("Print jobs queued " + DATE_FMT.format(at.atZone(ZoneId.systemDefault()))),
                () -> queuedBadge.setVisible(false));
        titleRow.add(queuedBadge);
        final Button dismiss = new Button("Dismiss", new Icon(VaadinIcon.EYE_SLASH));
        dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dismiss.setTooltipText("Hide this order from the list (does not mark it shipped on Etsy)");
        dismiss.addClickListener(e -> {
            polling.dismiss(receipt.receiptId());
            render();
        });
        titleRow.add(dismiss);
        card.add(titleRow);

        receipt.transactions().forEach(t -> card.add(buildTransactionRow(t, orderKey, queuedBadge)));
        return card;
    }

    private Div buildTransactionRow(final EtsyApiClient.Transaction t, final String orderKey, final Span queuedBadge) {
        final Div row = new Div();
        row.addClassName("etsy-transaction-row");

        final Span titleSpan = new Span("%dx %s".formatted(t.quantity(), t.title()));
        titleSpan.getStyle().setFontWeight("bold");
        row.add(titleSpan);

        if (!t.variations().isEmpty()) {
            final String variationText = t.variations().stream()
                    .map(v -> v.propertyName() + ": " + v.value())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            row.add(new Div(new Span(variationText)));
        }
        t.personalization().ifPresent(p -> {
            final Span pSpan = new Span("Personalization: " + p);
            pSpan.getStyle().set("font-style", "italic");
            row.add(new Div(pSpan));
        });

        final List<MappingPart> initialParts = mappingService.find(t.listingId(), t.variations())
                .map(EtsyMappingService.MappingEntry::parts)
                .orElse(List.of());
        final List<String> printerNames = printers.getPrintersDetail().stream()
                .map(BambuPrinters.PrinterDetail::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();

        final MappingPartsPanel panel = new MappingPartsPanel(
                this::getLibraryFiles,
                this::loadPlateIds,
                printerNames,
                initialParts,
                t.quantity(),
                parts -> {
                    mappingService.set(t.listingId(), t.variations(), new EtsyMappingService.MappingEntry(parts));
                    showNotification("Mapping saved for listing %d".formatted(t.listingId()));
                },
                (parts, selectedPrinters) -> {
                    final GcodeMappingQueuer.QueueResult result = queuer.queue(parts, t.quantity(), selectedPrinters);
                    if (result.totalQueued() > 0) {
                        showNotification("Queued %d job(s) for listing %d".formatted(result.totalQueued(), t.listingId()));
                        tracking.markQueued("etsy", orderKey);
                        queuedBadge.setTitle("Print jobs queued just now");
                        queuedBadge.setVisible(true);
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
