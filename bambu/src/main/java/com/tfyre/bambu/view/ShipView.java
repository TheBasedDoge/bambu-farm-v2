package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.OrderBoardService;
import com.tfyre.bambu.printer.OrderBoardService.Stage;
import com.tfyre.bambu.printer.UspsPickupService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ship - the orders that are ready to pack, and the carrier pickup. Nothing else: this is the page for the
 * packing table, read off a phone.
 */
@Route(value = "ship", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Ready to ship")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class ShipView extends VerticalLayout {

    @Inject
    BambuConfig config;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    OrderBoardService board;
    @Inject
    UspsPickupService usps;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        setMaxWidth("720px");
        int count = 0;
        final Div cards = new Div();
        cards.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-s)").set("width", "100%");
        for (final var r : etsyPolling.getReceipts()) {
            final String id = String.valueOf(r.receiptId());
            final OrderBoardService.Entry e = board.entry("etsy", id, OrderBoardService.label(r));
            if (e.stage() == Stage.READY) {
                count++;
                cards.add(card("Etsy", r.buyerName(), r.buyerNote(),
                        r.transactions().stream().map(t -> "%d× %s".formatted(t.quantity(), t.title())).collect(Collectors.toList()),
                        e, config.etsy().orderUrl().replace("{id}", id)));
            }
        }
        for (final var o : ebayPolling.getOrders()) {
            final OrderBoardService.Entry e = board.entry("ebay", o.orderId(), OrderBoardService.label(o));
            if (e.stage() == Stage.READY) {
                count++;
                cards.add(card("eBay", o.buyerUsername(), o.buyerNote(),
                        o.lineItems().stream().map(li -> "%d× %s".formatted(li.quantity(), li.title())).collect(Collectors.toList()),
                        e, config.ebay().orderUrl().replace("{id}", o.orderId())));
            }
        }
        add(new H3(count == 0 ? "Nothing ready to ship" : "Ready to ship · %d order%s".formatted(count, count == 1 ? "" : "s")));
        add(cards);

        final Div pickup = new Div();
        pickup.getStyle().set("border", "1px dashed var(--lumo-contrast-20pct)").set("border-radius", "12px")
                .set("padding", "var(--lumo-space-m)").set("width", "100%").set("box-sizing", "border-box")
                .set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-xs)");
        final Span title = new Span("USPS pickup");
        title.getStyle().set("font-weight", "600");
        final Span state = new Span(usps.getBooked()
                .map(b -> "Booked for %s · %d package(s)".formatted(b.pickupDate(), b.packageCount()))
                .orElse("Nothing booked" + (count > 0 ? " · %d order%s ready".formatted(count, count == 1 ? "" : "s") : "")));
        state.getStyle().setColor("var(--lumo-secondary-text-color)");
        pickup.add(title, state, new RouterLink(usps.getBooked().isPresent() ? "Change or cancel" : "Book a pickup", UspsPickupView.class));
        add(pickup);
    }

    private static Div card(final String market, final String buyer, final String note, final List<String> lines,
            final OrderBoardService.Entry e, final String url) {
        final Div card = new Div();
        card.getStyle().set("background", "var(--lumo-contrast-5pct)").set("border-radius", "12px")
                .set("padding", "var(--lumo-space-m)").set("display", "flex").set("flex-direction", "column")
                .set("gap", "var(--lumo-space-xs)").set("box-shadow", "inset 3px 0 0 var(--lumo-success-color)");
        final Span head = new Span("%s · %s".formatted(market, buyer));
        head.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        card.add(head);
        lines.forEach(l -> card.add(new Div(new Span(l))));
        if (note != null && !note.isBlank()) {
            final Div noteBox = new Div(new Span("Buyer note: " + note));
            noteBox.getStyle().set("background", "var(--lumo-warning-color-10pct, rgba(232,163,61,0.12))")
                    .set("border-radius", "6px").set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
                    .set("white-space", "pre-wrap");
            card.add(noteBox);
        }
        final Span how = new Span((e.printed() > 0 ? "%d printed".formatted(e.printed()) : "")
                + (e.printed() > 0 && e.fromStock() > 0 ? " · " : "")
                + (e.fromStock() > 0 ? "%d from stock".formatted(e.fromStock()) : ""));
        how.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        card.add(how);
        final Anchor open = new Anchor(url, "Open on " + market);
        open.setTarget("_blank");
        open.getStyle().set("padding", "var(--lumo-space-s) 0").set("font-weight", "600");
        card.add(open);
        return card;
    }
}
