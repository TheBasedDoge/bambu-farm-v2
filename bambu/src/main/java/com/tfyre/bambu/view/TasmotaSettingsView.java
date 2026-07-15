package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.TasmotaService;
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
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Tasmota Settings — view and control all smart plugs assigned to printers.
 *
 * Accessible from the sidebar. Admin only.
 */
@Route(value = "tasmota-settings", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Tasmota Settings")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class TasmotaSettingsView extends VerticalLayout implements NotificationHelper {

    @Inject
    BambuPrinters printers;
    @Inject
    TasmotaService tasmotaService;
    @Inject
    BambuConfig config;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);

        add(new H3("Tasmota Smart Plugs"));

        // Collect printers that have a tasmota URL configured
        final long plugCount = printers.getPrintersDetail().stream()
                .filter(pd -> pd.config().tasmota().isPresent())
                .count();

        if (plugCount == 0) {
            final Span note = new Span("⚠ No Tasmota smart plugs are configured. "
                    + "Add bambu.printers.<id>.tasmota=http://<ip> for each printer to enable.");
            note.getStyle().setColor("var(--lumo-error-text-color)");
            add(note);
            return;
        }

        // One card per printer that has a plug
        printers.getPrintersDetail().stream()
                .filter(pd -> pd.config().tasmota().isPresent())
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .forEach(pd -> add(buildPlugCard(pd.name(),
                        TasmotaService.TasmotaTarget.of(pd.config().tasmota().get(), pd.config().tasmotaChannel()))));
    }

    private Div buildPlugCard(final String printerName, final TasmotaService.TasmotaTarget target) {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4(printerName));

        // URL display
        final HorizontalLayout urlRow = new HorizontalLayout();
        urlRow.setAlignItems(Alignment.BASELINE);
        final Span urlLbl = new Span("Plug:");
        urlLbl.getStyle().setFontWeight("bold").setMinWidth("120px");
        urlRow.add(urlLbl, new Span(target.label()));
        section.add(urlRow);

        // Status display
        final Span statusBadge = new Span("● Checking…");
        statusBadge.getStyle().setColor("var(--lumo-secondary-text-color)").setFontWeight("bold");
        final HorizontalLayout statusRow = new HorizontalLayout();
        statusRow.setAlignItems(Alignment.CENTER);
        final Span statusLbl = new Span("Power:");
        statusLbl.getStyle().setFontWeight("bold").setMinWidth("120px");
        statusRow.add(statusLbl, statusBadge);
        section.add(statusRow);

        // Controls row
        final Optional<UI> ui = Optional.of(UI.getCurrent());

        final Button onBtn = new Button("Power On", new Icon(VaadinIcon.PLUG));
        onBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);
        onBtn.addClickListener(e -> {
            onBtn.setEnabled(false);
            tasmotaService.power(target, true,
                    () -> ui.ifPresent(u -> u.access(() -> {
                        refreshStatus(target, statusBadge, ui);
                        onBtn.setEnabled(true);
                        showNotification("%s: plug switched ON".formatted(printerName));
                    })),
                    err -> ui.ifPresent(u -> u.access(() -> {
                        onBtn.setEnabled(true);
                        showError("%s: %s".formatted(printerName, err));
                    })));
        });

        final Button offBtn = new Button("Power Off", new Icon(VaadinIcon.CLOSE_CIRCLE));
        offBtn.addThemeVariants(ButtonVariant.LUMO_ERROR);
        offBtn.addClickListener(e -> {
            offBtn.setEnabled(false);
            tasmotaService.power(target, false,
                    () -> ui.ifPresent(u -> u.access(() -> {
                        refreshStatus(target, statusBadge, ui);
                        offBtn.setEnabled(true);
                        showNotification("%s: plug switched OFF".formatted(printerName));
                    })),
                    err -> ui.ifPresent(u -> u.access(() -> {
                        offBtn.setEnabled(true);
                        showError("%s: %s".formatted(printerName, err));
                    })));
        });

        final Button refreshBtn = new Button(new Icon(VaadinIcon.REFRESH));
        refreshBtn.setTooltipText("Refresh status");
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        refreshBtn.addClickListener(e -> refreshStatus(target, statusBadge, ui));

        final HorizontalLayout controls = new HorizontalLayout(onBtn, offBtn, refreshBtn);
        controls.setSpacing(true);
        section.add(controls);

        // Idle auto-off: switch the plug off after the printer has sat ready with an empty queue this long
        final com.vaadin.flow.component.textfield.IntegerField autoOff
                = new com.vaadin.flow.component.textfield.IntegerField("Auto-off after idle minutes (0 = off)");
        autoOff.setMin(0);
        autoOff.setStepButtonsVisible(true);
        autoOff.setWidth("260px");
        autoOff.setValue(tasmotaService.getAutoOffMinutes(printerName));
        autoOff.setTooltipText("Automatically switch this plug OFF once the printer has been finished/idle with an "
                + "empty print queue for this many minutes. Never fires while jobs are queued or printing. "
                + "0 disables it.");
        autoOff.addValueChangeListener(e -> {
            tasmotaService.setAutoOffMinutes(printerName, e.getValue() == null ? 0 : e.getValue());
            showNotification("%s: auto-off %s".formatted(printerName,
                    e.getValue() == null || e.getValue() <= 0 ? "disabled" : "after " + e.getValue() + " idle min"));
        });
        section.add(autoOff);

        // Load status on render
        refreshStatus(target, statusBadge, ui);

        return section;
    }

    private void refreshStatus(final TasmotaService.TasmotaTarget target, final Span statusBadge, final Optional<UI> ui) {
        tasmotaService.getStatus(target, powerOpt ->
                ui.ifPresent(u -> u.access(() -> {
                    if (powerOpt.isEmpty()) {
                        statusBadge.setText("● Unreachable");
                        statusBadge.getStyle().setColor("var(--lumo-error-text-color)");
                    } else if (Boolean.TRUE.equals(powerOpt.get())) {
                        statusBadge.setText("● ON");
                        statusBadge.getStyle().setColor("var(--lumo-success-text-color)");
                    } else {
                        statusBadge.setText("● OFF");
                        statusBadge.getStyle().setColor("var(--lumo-secondary-text-color)");
                    }
                })));
    }

}
