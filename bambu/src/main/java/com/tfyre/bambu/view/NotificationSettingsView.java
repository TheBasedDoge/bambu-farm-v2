package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.NotificationService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Notification Settings — view current webhook/MQTT config, suppress events at runtime,
 * and send a test notification.
 *
 * Accessible from the sidebar. Admin only.
 */
@Route(value = "notification-settings", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Notification Settings")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class NotificationSettingsView extends VerticalLayout implements NotificationHelper {

    /** Known event types that can be suppressed individually. */
    private static final List<EventDef> EVENTS = List.of(
            new EventDef("failure_detected", "AI Failure Detected",
                    "Spaghetti / blob / detach detected by the AI failure check"),
            new EventDef("first_layer_issue", "AI First Layer Issue",
                    "First layer quality problem detected by AI"),
            new EventDef("error", "Printer Error",
                    "Non-zero print error code reported by a printer"),
            new EventDef("maintenance", "Maintenance Due",
                    "A maintenance task is overdue on a printer")
    );

    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("ai-settings-view"); // reuse card-layout styles
        setPadding(true);
        setSpacing(true);

        add(new H3("Notification Settings"));
        add(buildConfigSection());
        add(buildEventsSection());
        add(buildTestSection());
    }

    // -------------------------------------------------------------------------
    // Config section (read-only — edit the config file to change)
    // -------------------------------------------------------------------------

    private Div buildConfigSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Current Configuration"));

        final BambuConfig.Notify notif = config.notifications();

        // Webhook
        final boolean webhookConfigured = notif.webhookUrl().isPresent();
        section.add(row("Webhook:", badge(webhookConfigured ? "● Configured" : "● Not configured", webhookConfigured)));
        if (webhookConfigured) {
            section.add(row("  URL:", new Span(mask(notif.webhookUrl().get()))));
            section.add(row("  Format:", new Span(notif.webhookFormat())));
        }

        // MQTT
        final boolean mqttConfigured = notif.mqtt().url().isPresent();
        section.add(row("MQTT:", badge(mqttConfigured ? "● Configured" : "● Not configured", mqttConfigured)));
        if (mqttConfigured) {
            section.add(row("  Broker:", new Span(mask(notif.mqtt().url().get()))));
            section.add(row("  Topic prefix:", new Span(notif.mqtt().topic())));
            notif.mqtt().username().ifPresent(u -> section.add(row("  Username:", new Span(u))));
        }

        if (!webhookConfigured && !mqttConfigured) {
            final Span note = new Span("⚠ No notification channels are configured. "
                    + "Set bambu.notifications.webhook-url and/or bambu.notifications.mqtt.url to enable.");
            note.getStyle().setColor("var(--lumo-error-text-color)");
            section.add(note);
        }

        section.add(new Span("To change these values, edit your configuration file and restart."));
        return section;
    }

    // -------------------------------------------------------------------------
    // Per-event runtime toggles
    // -------------------------------------------------------------------------

    private Div buildEventsSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Event Filters"));
        section.add(new Span("Toggle events on or off at runtime without restarting. Resets on server restart."));

        final Div list = new Div();
        list.getStyle()
                .setDisplay(Style.Display.FLEX)
                .setFlexDirection(Style.FlexDirection.COLUMN)
                .setGap("var(--lumo-space-s)")
                .setMarginTop("var(--lumo-space-s)");

        for (final EventDef def : EVENTS) {
            final Checkbox cb = new Checkbox(def.label());
            cb.setValue(!notificationService.isEventSuppressed(def.id()));
            cb.setTooltipText(def.description());
            cb.addValueChangeListener(e -> {
                if (e.getValue()) {
                    notificationService.unsuppressEvent(def.id());
                } else {
                    notificationService.suppressEvent(def.id());
                }
                showNotification("'%s' %s".formatted(def.label(), e.getValue() ? "enabled" : "suppressed"));
            });

            final Span desc = new Span(def.description());
            desc.getStyle()
                    .setColor("var(--lumo-secondary-text-color)")
                    .setFontSize("var(--lumo-font-size-s)")
                    .setMarginLeft("1.5em");

            final Div block = new Div(cb, desc);
            block.getStyle().setDisplay(Style.Display.FLEX).setFlexDirection(Style.FlexDirection.COLUMN);
            list.add(block);
        }

        section.add(list);
        return section;
    }

    // -------------------------------------------------------------------------
    // Test section
    // -------------------------------------------------------------------------

    private Div buildTestSection() {
        final Div section = new Div();
        section.addClassName("ai-settings-section");
        section.add(new H4("Test Notification"));
        section.add(new Span("Send a test event to all configured channels (always delivered, not affected by filters above)."));

        final Span result = new Span();
        result.getStyle().setMarginLeft("var(--lumo-space-m)");

        final Button testBtn = new Button("Send Test", new Icon(VaadinIcon.BELL));
        testBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        testBtn.addClickListener(e -> {
            if (!notificationService.isConfigured()) {
                result.setText("⚠ No channels configured");
                result.getStyle().setColor("var(--lumo-error-text-color)");
                return;
            }
            final Optional<UI> ui = Optional.of(UI.getCurrent());
            // Deliver directly; "test" is never in the suppressed set (we ensure it)
            notificationService.unsuppressEvent("test");
            notificationService.notifyEvent("test", "bambufarm", "Test notification from BambuFarm");
            ui.ifPresent(u -> u.access(() -> {
                result.setText("✓ Test sent");
                result.getStyle().setColor("var(--lumo-success-text-color)");
            }));
        });

        final HorizontalLayout testRow = new HorizontalLayout(testBtn, result);
        testRow.setAlignItems(Alignment.CENTER);
        section.add(testRow);
        return section;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Mask credentials in URLs — replace user:password@ with *****@. */
    private static String mask(final String url) {
        return url.replaceAll("://[^@]+@", "://*****@");
    }

    private static Span badge(final String text, final boolean good) {
        final Span s = new Span(text);
        s.getStyle()
                .setColor(good ? "var(--lumo-success-text-color)" : "var(--lumo-secondary-text-color)")
                .setFontWeight("bold");
        return s;
    }

    private static HorizontalLayout row(final String label, final com.vaadin.flow.component.Component value) {
        final HorizontalLayout hl = new HorizontalLayout();
        hl.setSpacing(true);
        hl.setAlignItems(Alignment.BASELINE);
        final Span lbl = new Span(label);
        lbl.getStyle().setFontWeight("bold").setMinWidth("160px");
        hl.add(lbl, value);
        return hl;
    }

    private record EventDef(String id, String label, String description) {}

}
