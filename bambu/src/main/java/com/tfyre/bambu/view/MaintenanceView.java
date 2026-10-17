package com.tfyre.bambu.view;

import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.MaintenanceService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maintenance: every printer's tasks as a card, overdue first, each with a bar of hours since it was last done
 * against its interval and a Done button.
 * <p>
 * Until 2026-10-13 this page was a table of printer connection times, with the tasks behind a wrench in its last
 * column. The connection table moved to Settings → Printer connections ({@link PrinterConnectionsView}).
 */
@Route(value = "maintenance", layout = MainLayout.class)
@PageTitle("Maintenance")
@RolesAllowed({ SystemRoles.ROLE_ADMIN })
public class MaintenanceView extends VerticalLayout implements NotificationHelper {

    /** A task this far into its interval turns amber; at 1.0 it is due. */
    private static final double SOON = 0.75;
    /** Tasks below this share of their interval are folded into "N more on track". */
    private static final double FOLD_BELOW = 0.60;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    @Inject
    BambuPrinters printers;
    @Inject
    MaintenanceService maintenanceService;

    private final Span dueChip = new Span();
    private final Div cards = new Div();
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            addClassName("maintenance-view");
            setPadding(true);
            final Button addEverywhere = new Button("Add a task to every printer", new Icon(VaadinIcon.PLUS), e -> addToAll());
            addEverywhere.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            dueChip.addClassName("mt-chip");
            final HorizontalLayout top = new HorizontalLayout(new H3("Maintenance"), dueChip, addEverywhere);
            top.setAlignItems(FlexComponent.Alignment.CENTER);
            top.getStyle().set("flex-wrap", "wrap");
            cards.addClassName("mt-grid");
            final Span hint = new Span("Hours are print hours. A bar turns amber three quarters of the way to its "
                    + "interval and red when the task is due; an overdue task also puts a red wrench on that printer's "
                    + "Dashboard card. The printers' connections are under Settings → Printer connections.");
            hint.addClassName("mt-hint");
            add(top, cards, hint);
        }
        render();
    }

    private static double ratio(final MaintenanceService.TaskStatus ts) {
        return ts.task().intervalHours() <= 0 ? 0 : ts.hoursSince() / ts.task().intervalHours();
    }

    private void render() {
        record PrinterTasks(String name, double hours, List<MaintenanceService.TaskStatus> tasks, long due, double worst) {
        }
        final List<PrinterTasks> list = new ArrayList<>();
        for (final BambuPrinters.PrinterDetail pd : printers.getPrintersDetail()) {
            final List<MaintenanceService.TaskStatus> tasks = new ArrayList<>(maintenanceService.getTaskStatus(pd.name()));
            tasks.sort(Comparator.comparingDouble(MaintenanceView::ratio).reversed());
            final long due = tasks.stream().filter(MaintenanceService.TaskStatus::overdue).count();
            final double worst = tasks.stream().mapToDouble(MaintenanceView::ratio).max().orElse(0);
            list.add(new PrinterTasks(pd.name(), maintenanceService.getPrintHours(pd.name()), tasks, due, worst));
        }
        list.sort(Comparator.comparingLong(PrinterTasks::due).reversed()
                .thenComparing(Comparator.comparingDouble(PrinterTasks::worst).reversed())
                .thenComparing(PrinterTasks::name));
        final long totalDue = list.stream().mapToLong(PrinterTasks::due).sum();
        dueChip.setText(totalDue == 0 ? "nothing due" : "%d task%s due".formatted(totalDue, totalDue == 1 ? "" : "s"));
        dueChip.getElement().setAttribute("data-tone", totalDue == 0 ? "ok" : "bad");

        cards.removeAll();
        for (final PrinterTasks p : list) {
            final Div card = new Div();
            card.addClassName("mt-card");
            if (p.due() > 0) {
                card.addClassName("mt-due");
            }
            final Span name = new Span(p.name());
            name.addClassName("mt-name");
            final Span hours = new Span("%.0f print hours".formatted(p.hours()));
            hours.addClassName("mt-dim");
            final Div head = new Div(name, hours);
            head.addClassName("mt-head");
            if (p.due() > 0) {
                final Span chip = new Span("%d due".formatted(p.due()));
                chip.addClassName("mt-chip");
                chip.getElement().setAttribute("data-tone", "bad");
                head.add(chip);
            }
            final Button edit = new Button(new Icon(VaadinIcon.ELLIPSIS_DOTS_H), e -> showPrinterDialog(p.name()));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            edit.setTooltipText("Print hours, add or remove tasks");
            edit.addClassName("mt-edit");
            head.add(edit);
            card.add(head);

            final List<MaintenanceService.TaskStatus> shown = p.tasks().stream().filter(ts -> ratio(ts) >= FOLD_BELOW).toList();
            final List<MaintenanceService.TaskStatus> folded = p.tasks().stream().filter(ts -> ratio(ts) < FOLD_BELOW).toList();
            shown.forEach(ts -> card.add(taskRow(p.name(), ts)));
            if (!folded.isEmpty()) {
                final Div rest = new Div();
                folded.forEach(ts -> rest.add(taskRow(p.name(), ts)));
                final Details more = new Details("%d more on track".formatted(folded.size()), rest);
                more.addClassName("mt-more");
                card.add(more);
            }
            if (p.tasks().isEmpty()) {
                final Span none = new Span("No tasks - add some with ⋯");
                none.addClassName("mt-dim");
                card.add(none);
            }
            cards.add(card);
        }
    }

    private Div taskRow(final String printer, final MaintenanceService.TaskStatus ts) {
        final double r = ratio(ts);
        final Span name = new Span(ts.task().name());
        name.addClassName("mt-task-name");
        final String last = ts.task().lastDoneDate() == null || ts.task().lastDoneDate().isBlank() ? ""
                : " · last " + lastDone(ts.task().lastDoneDate());
        final Span meta = new Span("%.0f of %.0f h%s".formatted(ts.hoursSince(), ts.task().intervalHours(), last));
        meta.addClassName("mt-dim");
        final Div fill = new Div();
        fill.addClassName("mt-fill");
        fill.getElement().setAttribute("data-tone", ts.overdue() ? "bad" : r >= SOON ? "warn" : "ok");
        fill.getStyle().set("width", "%.0f%%".formatted(Math.min(100, Math.max(2, r * 100))));
        final Div bar = new Div(fill);
        bar.addClassName("mt-bar");
        final Button done = new Button("Done", e -> {
            maintenanceService.markDone(printer, ts.task().name());
            showNotification("%s: %s marked done".formatted(printer, ts.task().name()));
            render();
        });
        done.addThemeVariants(ButtonVariant.LUMO_SMALL);
        done.setTooltipText("Mark as done now - resets its hours to zero");
        final Div text = new Div(name, meta);
        text.addClassName("mt-task-text");
        final Div row = new Div(text, done, bar);
        row.addClassName("mt-task");
        return row;
    }

    /** "2026-08-18" → "Aug 18"; anything else as it is. */
    private static String lastDone(final String date) {
        try {
            return DAY.format(LocalDate.parse(date.length() > 10 ? date.substring(0, 10) : date));
        } catch (RuntimeException ex) {
            return date;
        }
    }

    private void addToAll() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Add a task to every printer");
        final TextField name = new TextField("Task");
        name.setWidthFull();
        final NumberField interval = new NumberField("Every (print hours)");
        interval.setValue(100.0);
        interval.setMin(1);
        dialog.add(new VerticalLayout(name, interval));
        final Button add = new Button("Add", e -> {
            final String n = Optional.ofNullable(name.getValue()).orElse("").trim();
            if (n.isEmpty()) {
                showError("Give the task a name");
                return;
            }
            final double hours = Optional.ofNullable(interval.getValue()).orElse(100.0);
            printers.getPrintersDetail().forEach(pd -> maintenanceService.addTask(pd.name(), n, hours));
            dialog.close();
            render();
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), add);
        dialog.open();
    }

    /** One printer's hours and task list - what the wrench dialog on the old page did. */
    private void showPrinterDialog(final String printer) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("%s: maintenance".formatted(printer));
        dialog.setWidth("min(750px, 95vw)");

        final Grid<MaintenanceService.TaskStatus> taskGrid = new Grid<>();
        final Runnable reload = () -> taskGrid.setItems(maintenanceService.getTaskStatus(printer));
        taskGrid.addColumn(ts -> ts.task().name()).setHeader("Task").setFlexGrow(2);
        taskGrid.addColumn(ts -> "%.0f".formatted(ts.task().intervalHours())).setHeader("Every (h)");
        taskGrid.addComponentColumn(ts -> {
            final Span result = new Span("%.1f".formatted(ts.hoursSince()));
            result.addClassName(ts.overdue() ? LumoUtility.TextColor.ERROR : LumoUtility.TextColor.SUCCESS);
            return result;
        }).setHeader("Hours since");
        taskGrid.addColumn(ts -> ts.task().lastDoneDate().isEmpty() ? "--" : ts.task().lastDoneDate()).setHeader("Last done");
        taskGrid.addComponentColumn(ts -> {
            final Button remove = new Button("", new Icon(VaadinIcon.TRASH), l -> {
                maintenanceService.removeTask(printer, ts.task().name());
                reload.run();
            });
            remove.setTooltipText("Remove task");
            return remove;
        }).setAutoWidth(true).setFlexGrow(0);
        taskGrid.setAllRowsVisible(true);
        reload.run();

        final NumberField hoursField = new NumberField("Total print hours");
        hoursField.setValue(Math.round(maintenanceService.getPrintHours(printer) * 10) / 10.0);
        hoursField.setStep(0.1);
        hoursField.setMin(0);
        final Button saveHours = new Button("Save hours", l -> {
            maintenanceService.setPrintHours(printer, Optional.ofNullable(hoursField.getValue()).orElse(0.0));
            reload.run();
            showNotification("Print hours updated");
        });
        final HorizontalLayout hoursLayout = new HorizontalLayout(hoursField, saveHours);
        hoursLayout.setDefaultVerticalComponentAlignment(Alignment.END);

        final TextField newName = new TextField("New task");
        final NumberField newInterval = new NumberField("Every (h)");
        newInterval.setValue(100.0);
        newInterval.setMin(0);
        final Button add = new Button("Add", l -> {
            final String name = Optional.ofNullable(newName.getValue()).orElse("").trim();
            if (name.isEmpty()) {
                showError("Task name is required");
                return;
            }
            maintenanceService.addTask(printer, name, Optional.ofNullable(newInterval.getValue()).orElse(0.0));
            newName.clear();
            reload.run();
        });
        final HorizontalLayout addLayout = new HorizontalLayout(newName, newInterval, add);
        addLayout.setDefaultVerticalComponentAlignment(Alignment.END);
        addLayout.getStyle().set("flex-wrap", "wrap");

        dialog.add(hoursLayout, taskGrid, addLayout);
        dialog.getFooter().add(new Button("Close", l -> {
            dialog.close();
            render();
        }));
        dialog.open();
    }
}
