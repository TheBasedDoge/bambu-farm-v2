package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.SpoolService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Comparator;

/**
 * Filament Spools — inventory + remaining-grams tracking for non-Bambu spools (which have no AMS RFID). Assign a
 * spool to each printer tray; finished prints subtract their filament weight from the spool on the tray that was
 * active, and a low-stock notification fires when a spool crosses its warning threshold. Admin only.
 */
@Route(value = "spools", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Filament Spools")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class SpoolsView extends VerticalLayout implements NotificationHelper {

    @Inject
    SpoolService spoolService;
    @Inject
    BambuPrinters printers;

    private final Grid<SpoolService.Spool> grid = new Grid<>();
    private final Div assignmentHolder = new Div();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);
        add(new H3("Filament Spools"));
        add(new Span("Track how much filament is left on your non-Bambu spools. Assign a spool to each printer's "
                + "loaded tray below; when a print finishes, the grams it used are subtracted from the spool on the "
                + "tray the printer was feeding, and you get a Spool Low notification when it runs down."));
        add(buildSpoolSection());
        add(buildAssignmentSection());
        reload();
    }

    private void reload() {
        grid.setItems(spoolService.getSpools());
        renderAssignments();
    }

    private Div section(final String title) {
        final Div sec = new Div();
        sec.addClassName("ai-settings-section");
        sec.add(new H4(title));
        return sec;
    }

    // -------------------------------------------------------------------------
    // Spool inventory
    // -------------------------------------------------------------------------

    private Div buildSpoolSection() {
        final Div sec = section("Spools");
        final Button add = new Button("Add spool", new Icon(VaadinIcon.PLUS), e -> openSpoolDialog(null));
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        sec.add(add);

        if (grid.getColumns().isEmpty()) {
            grid.addColumn(SpoolService.Spool::name).setHeader("Name").setAutoWidth(true);
            grid.addColumn(SpoolService.Spool::material).setHeader("Material").setAutoWidth(true);
            grid.addComponentColumn(s -> colorSwatch(s.color())).setHeader("Color").setAutoWidth(true);
            grid.addComponentColumn(this::remainingBar).setHeader("Remaining").setFlexGrow(1);
            grid.addColumn(s -> "%.0f / %.0f g".formatted(s.remainingGrams(), s.totalGrams())).setHeader("Grams").setAutoWidth(true);
            grid.addColumn(s -> "%.0f g".formatted(s.lowThresholdGrams())).setHeader("Low at").setAutoWidth(true);
            grid.addComponentColumn(this::rowActions).setHeader("").setAutoWidth(true);
            grid.setWidth("100%");
            grid.setAllRowsVisible(true);
            grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
            grid.setColumnReorderingAllowed(true);
            grid.getColumns().forEach(c -> c.setResizable(true));
        }
        sec.add(grid);
        return sec;
    }

    private Span colorSwatch(final String color) {
        final Span s = new Span(color == null || color.isBlank() ? "—" : color);
        if (color != null && !color.isBlank()) {
            s.getStyle().set("border-left", "14px solid " + color).set("padding-left", "6px");
        }
        return s;
    }

    private Div remainingBar(final SpoolService.Spool spool) {
        final double total = spool.totalGrams() <= 0 ? 1 : spool.totalGrams();
        final ProgressBar bar = new ProgressBar(0, total, Math.min(spool.remainingGrams(), total));
        bar.setWidth("160px");
        if (spool.remainingGrams() <= spool.lowThresholdGrams()) {
            bar.getStyle().set("--lumo-primary-color", "var(--lumo-error-color)");
        }
        return new Div(bar);
    }

    private HorizontalLayout rowActions(final SpoolService.Spool spool) {
        final Button edit = new Button(new Icon(VaadinIcon.EDIT), e -> openSpoolDialog(spool));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        edit.setTooltipText("Edit");
        final Button refill = new Button(new Icon(VaadinIcon.REFRESH), e -> {
            spoolService.refill(spool.id());
            showNotification("'%s' refilled to full".formatted(spool.name()));
            reload();
        });
        refill.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        refill.setTooltipText("Refill (reset remaining to full)");
        final Button del = new Button(new Icon(VaadinIcon.TRASH), e -> YesNoCancelDialog.show(
                "Delete spool '%s'?".formatted(spool.name()), ync -> {
                    if (ync.isConfirmed()) {
                        spoolService.deleteSpool(spool.id());
                        showNotification("Spool deleted");
                        reload();
                    }
                }));
        del.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        del.setTooltipText("Delete");
        return new HorizontalLayout(edit, refill, del);
    }

    private void openSpoolDialog(final SpoolService.Spool existing) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(existing == null ? "Add spool" : "Edit spool");
        dialog.setWidth("420px");
        final TextField name = new TextField("Name");
        name.setPlaceholder("e.g. Overture PETG Black");
        name.setWidthFull();
        final TextField material = new TextField("Material");
        material.setPlaceholder("PETG, ASA, PLA…");
        final TextField color = new TextField("Color");
        color.setPlaceholder("black, #1a1a1a, …");
        final NumberField total = new NumberField("Total grams");
        total.setValue(1000d);
        total.setMin(0);
        final NumberField remaining = new NumberField("Remaining grams");
        remaining.setMin(0);
        final NumberField low = new NumberField("Low warning at (grams)");
        low.setValue(100d);
        low.setMin(0);

        if (existing != null) {
            name.setValue(existing.name());
            material.setValue(existing.material() == null ? "" : existing.material());
            color.setValue(existing.color() == null ? "" : existing.color());
            total.setValue(existing.totalGrams());
            remaining.setValue(existing.remainingGrams());
            low.setValue(existing.lowThresholdGrams());
        } else {
            remaining.setVisible(false); // new spool starts full
        }

        final VerticalLayout form = new VerticalLayout(name, material, color, total, remaining, low);
        form.setPadding(false);
        dialog.add(form);
        final Button save = new Button("Save", e -> {
            if (name.isEmpty()) {
                showError("Name is required");
                return;
            }
            final double totalG = total.getValue() == null ? 0 : total.getValue();
            final double lowG = low.getValue() == null ? 0 : low.getValue();
            if (existing == null) {
                spoolService.addSpool(name.getValue(), material.getValue(), color.getValue(), totalG, lowG);
            } else {
                final double rem = remaining.getValue() == null ? existing.remainingGrams() : remaining.getValue();
                spoolService.updateSpool(existing.id(), name.getValue(), material.getValue(), color.getValue(), totalG, rem, lowG);
            }
            showNotification("Spool saved");
            reload();
            dialog.close();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), save);
        dialog.open();
    }

    // -------------------------------------------------------------------------
    // Tray → spool assignment
    // -------------------------------------------------------------------------

    private Div buildAssignmentSection() {
        final Div sec = section("Which spool is loaded where");
        sec.add(new Span("Per printer, pick the spool currently loaded in each tray. Only trays the printer is "
                + "reporting filament in are shown. Consumption uses the tray the printer was actually feeding at "
                + "the time the print finished."));
        sec.add(assignmentHolder);
        return sec;
    }

    private void renderAssignments() {
        assignmentHolder.removeAll();
        printers.getPrintersDetail().forEach(pd -> {
            final Div card = new Div();
            card.addClassName("ai-settings-section");
            card.getStyle().set("max-width", "420px").set("display", "inline-block").set("margin", "8px 12px 0 0").set("vertical-align", "top");
            card.add(new H4(pd.name()));
            final var trays = pd.printer().getAmsTrayTypes();
            if (trays.isEmpty()) {
                final Span none = new Span("No AMS/filament telemetry yet.");
                none.getStyle().setColor("var(--lumo-secondary-text-color)");
                card.add(none);
            } else {
                trays.entrySet().stream()
                        .sorted(Comparator.comparingInt(java.util.Map.Entry::getKey))
                        .forEach(e -> card.add(trayCombo(pd.name(), e.getKey(), e.getValue())));
            }
            assignmentHolder.add(card);
        });
    }

    private ComboBox<SpoolService.Spool> trayCombo(final String printerName, final int tray, final String material) {
        final String label = tray == BambuConst.AMS_TRAY_VIRTUAL
                ? "External spool (%s)".formatted(material)
                : "Tray %d (%s)".formatted(tray + 1, material);
        final ComboBox<SpoolService.Spool> cb = new ComboBox<>(label);
        cb.setItems(spoolService.getSpools());
        cb.setItemLabelGenerator(s -> "%s — %s, %.0fg left".formatted(s.name(), s.material(), s.remainingGrams()));
        cb.setClearButtonVisible(true);
        cb.setPlaceholder("(not tracked)");
        cb.setWidthFull();
        spoolService.getAssignedSpoolId(printerName, tray).flatMap(spoolService::getSpool).ifPresent(cb::setValue);
        cb.addValueChangeListener(ev -> {
            spoolService.assign(printerName, tray, ev.getValue() == null ? null : ev.getValue().id());
            showNotification("Assignment updated");
        });
        return cb;
    }
}
