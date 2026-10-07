package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.NotificationService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
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
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Notifications - every alert the farm raised, newest first, with what became of it (sent, suppressed by a
 * filter, failed). The same text that went to Discord/ntfy/MQTT, so the channel does not have to be opened to
 * see what was said; click a row for the full message and, for the newest ones, the camera frame.
 * <p>
 * Read-only. Which events go out is decided on Notification Settings.
 */
@Route(value = "notifications", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Notifications")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class NotificationsView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss");

    @Inject
    NotificationService notificationService;

    private final Grid<NotificationService.LogEntry> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final ComboBox<String> eventFilter = new ComboBox<>();
    private final Checkbox showSuppressed = new Checkbox("Show suppressed", true);
    private final Span count = new Span();
    private boolean built;
    private boolean reloadingEvents;
    private Runnable unsubscribe;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        reloadEvents();
        refresh();
        final UI ui = attachEvent.getUI();
        unsubscribe = notificationService.addLogListener(() -> {
            try {
                ui.access(this::refresh);
            } catch (UIDetachedException ex) {
                // tab closed between the event and now - onDetach unsubscribes
            }
        });
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
    }

    private void build() {
        addClassName("notifications-view");
        setSizeFull();

        filter.setPlaceholder("Search text, printer, order...");
        filter.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        filter.setClearButtonVisible(true);
        filter.setWidth("280px");
        filter.setValueChangeMode(ValueChangeMode.TIMEOUT);
        filter.addValueChangeListener(e -> refresh());

        eventFilter.setPlaceholder("All events");
        eventFilter.setClearButtonVisible(true);
        eventFilter.setWidth("220px");
        eventFilter.addValueChangeListener(e -> {
            if (!reloadingEvents) {
                refresh();
            }
        });

        showSuppressed.addValueChangeListener(e -> refresh());

        final Button refreshBtn = new Button("Refresh", new Icon(VaadinIcon.REFRESH), e -> {
            reloadEvents();
            refresh();
        });
        count.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final HorizontalLayout toolbar = new HorizontalLayout(filter, eventFilter, showSuppressed, refreshBtn, count);
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.getStyle().set("flex-wrap", "wrap");
        add(toolbar);

        grid.addColumn(e -> when(e.timestamp())).setHeader("When").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(NotificationService.LogEntry::event).setHeader("Event").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(NotificationService.LogEntry::printer).setHeader("About").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(NotificationService.LogEntry::message).setHeader("Message").setFlexGrow(1);
        grid.addComponentColumn(e -> {
            if (!e.photo()) {
                return new Span();
            }
            final Icon icon = new Icon(VaadinIcon.PICTURE);
            icon.setSize("16px");
            icon.getElement().setAttribute("title", "Sent with a camera photo");
            return icon;
        }).setHeader("Photo").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(e -> statusSpan(e.status())).setHeader("Status").setAutoWidth(true).setFlexGrow(0);
        grid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setColumnReorderingAllowed(true);
        grid.getColumns().forEach(c -> c.setResizable(true));
        GridLayoutMemory.remember(grid, "notifications");
        grid.addItemClickListener(e -> showDialog(e.getItem()));
        grid.setSizeFull();
        addAndExpand(grid);
    }

    /** The event dropdown lists what is actually in the log, so it never offers a filter that matches nothing. */
    private void reloadEvents() {
        final String keep = eventFilter.getValue();
        final List<String> events = notificationService.getLog().stream()
                .map(NotificationService.LogEntry::event)
                .distinct()
                .sorted()
                .toList();
        reloadingEvents = true;
        try {
            eventFilter.setItems(events);
            if (keep != null && events.contains(keep)) {
                eventFilter.setValue(keep);
            }
        } finally {
            reloadingEvents = false;
        }
    }

    private void refresh() {
        final List<NotificationService.LogEntry> all = notificationService.getLog();
        final String event = eventFilter.getValue();
        final String text = filter.getValue() == null ? "" : filter.getValue().strip().toLowerCase(Locale.ROOT);
        final boolean suppressedToo = Boolean.TRUE.equals(showSuppressed.getValue());
        final List<NotificationService.LogEntry> shown = all.stream()
                .filter(e -> event == null || event.equals(e.event()))
                .filter(e -> suppressedToo || !NotificationService.STATUS_SUPPRESSED.equals(e.status()))
                .filter(e -> text.isEmpty()
                        || e.message().toLowerCase(Locale.ROOT).contains(text)
                        || e.printer().toLowerCase(Locale.ROOT).contains(text)
                        || e.event().toLowerCase(Locale.ROOT).contains(text))
                .toList();
        grid.setItems(shown);
        count.setText(shown.size() == all.size()
                ? "%d notification(s)".formatted(all.size())
                : "%d of %d".formatted(shown.size(), all.size()));
    }

    private static String when(final String timestamp) {
        try {
            return TIME_FMT.format(OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault()));
        } catch (DateTimeParseException | NullPointerException ex) {
            return String.valueOf(timestamp);
        }
    }

    private static Span statusSpan(final String status) {
        final String text = status == null ? "" : status;
        final Span span = new Span(text);
        final String color;
        if (NotificationService.STATUS_SENT.equals(text)) {
            color = "var(--lumo-success-text-color)";
        } else if (text.startsWith(NotificationService.STATUS_FAILED_PREFIX)) {
            color = "var(--lumo-error-text-color)";
        } else {
            color = "var(--lumo-secondary-text-color)";
        }
        span.getStyle().set("color", color);
        return span;
    }

    private void showDialog(final NotificationService.LogEntry entry) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("%s - %s (%s)".formatted(entry.event(), entry.printer(), when(entry.timestamp())));
        dialog.setWidth("860px");
        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        if (entry.photo()) {
            final Optional<byte[]> photo = notificationService.getLogPhoto(entry.id());
            if (photo.isPresent()) {
                final byte[] bytes = photo.get();
                final Image img = new Image(new StreamResource("notification-%d.jpg".formatted(entry.id()),
                        () -> new ByteArrayInputStream(bytes)), "Photo sent with this notification");
                img.setWidth("100%");
                img.getStyle().set("border-radius", "6px");
                layout.add(img);
            } else {
                layout.add(new Span("This alert went out with a camera photo, but only the newest photos are kept "
                        + "(in memory, until a restart) - open the channel to see this one."));
            }
        }
        final Div message = new Div();
        message.setText(entry.message());
        message.getStyle().set("white-space", "pre-wrap");
        layout.add(message, new HorizontalLayout(new Span("Status:"), statusSpan(entry.status())));
        dialog.add(layout);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }
}
