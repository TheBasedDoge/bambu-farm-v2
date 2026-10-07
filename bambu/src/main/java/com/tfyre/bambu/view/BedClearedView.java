package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.OptionalParameter;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * Where the "Bed cleared" button on an alert lands: {@code /bed-cleared/<printer>}.
 * <p>
 * One confirm button, deliberately - the link itself does nothing. A URL that opened the bed gate just by being
 * fetched would be opened by link previews, browser prefetch and a thumb in a pocket.
 */
@Route(value = "bed-cleared", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Bed cleared")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class BedClearedView extends VerticalLayout implements HasUrlParameter<String> {

    @Inject
    BambuPrinters printers;
    @Inject
    BedClearService bedClear;
    @Inject
    DispatchQueueService dispatchQueue;

    private String printerName = "";

    @Override
    public void setParameter(final BeforeEvent event, @OptionalParameter final String parameter) {
        printerName = parameter == null ? "" : parameter;
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        if (printers.getPrinter(printerName).isEmpty()) {
            add(new H3("Bed cleared"), new Span("There is no printer called '%s'.".formatted(printerName)),
                    new RouterLink("Open the Overview", OverviewView.class));
            return;
        }
        final Span result = new Span("Press the button once the part is off %s's plate. The next job for it starts "
                + "without waiting for the camera check.".formatted(printerName));
        final Button confirm = new Button("%s's bed is clear".formatted(printerName));
        confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        confirm.addClickListener(e -> {
            if (bedClear.markCleared(printerName, "alert link")) {
                dispatchQueue.passNow();
                result.setText("Done - %s is marked clear. If a job is waiting for it, it is starting now.".formatted(printerName));
                result.getStyle().setColor("var(--lumo-success-text-color)");
            } else {
                result.setText("%s is printing right now, so there is nothing to clear.".formatted(printerName));
                result.getStyle().setColor("var(--lumo-warning-text-color, #e8a33d)");
            }
            confirm.setEnabled(false);
        });
        add(new H3(printerName), result, confirm, new RouterLink("Open the Overview", OverviewView.class));
    }
}
