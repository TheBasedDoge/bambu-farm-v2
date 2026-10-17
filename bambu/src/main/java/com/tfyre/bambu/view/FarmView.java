package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.FarmStatusService;
import com.tfyre.bambu.printer.FarmStatusService.PrinterLine;
import com.tfyre.bambu.printer.FarmStatusService.Tone;
import com.tfyre.bambu.printer.PrintQueueService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Farm - the page for standing in front of the printers with a phone.
 * <p>
 * One row per printer, the ones that need you first, each with the one or two buttons you would want there
 * (Bed cleared, Start next, Resume). Tap a row for that printer's camera and the rest of its controls.
 * <p>
 * The same markup serves two shapes, switched by CSS on width alone: on a narrow screen the list and the printer
 * you tapped take turns filling it; on a wide one (a tablet, an unfolded phone) they sit side by side.
 */
@Route(value = "farm", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Farm")
@RolesAllowed({SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_NORMAL})
public class FarmView extends Div {

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    FarmStatusService farmStatus;
    @Inject
    BedClearService bedClear;
    @Inject
    DispatchQueueService dispatchQueue;
    @Inject
    PrintQueueService queueService;
    @Inject
    ScheduledExecutorService ses;

    private final Div list = new Div();
    private final Div detail = new Div();
    private final Image detailCam = new Image();
    private final Div detailBody = new Div();
    private final Map<String, Image> thumbs = new HashMap<>();
    private final Map<String, String> thumbIds = new HashMap<>();
    private String listKey = "";
    private String detailKey = "";
    private String detailCamId = "";
    private String selected;
    private boolean admin;
    private Optional<ScheduledFuture<?>> future = Optional.empty();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        thumbs.clear();
        thumbIds.clear();
        listKey = "";
        detailKey = "";
        detailCamId = "";
        final VaadinRequest request = VaadinRequest.getCurrent();
        admin = request != null && request.isUserInRole(SystemRoles.ROLE_ADMIN);

        addClassName("farm");
        list.setClassName("farm-list");
        detail.setClassName("farm-detail");
        detailCam.setClassName("farm-cam");
        detailCam.setAlt("");
        detailBody.setClassName("farm-detail-body");
        final Button back = new Button("All printers", e -> {
            getElement().removeAttribute("data-open");
        });
        back.addClassName("farm-back");
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        detail.removeAll();
        detail.add(back, detailCam, detailBody);
        add(list, detail);

        final UI ui = attachEvent.getUI();
        future.ifPresent(f -> f.cancel(true));
        future = Optional.of(RefreshTicker.start(ses, ui, "FarmView",
                config.refreshInterval().compareTo(java.time.Duration.ofSeconds(2)) < 0 ? java.time.Duration.ofSeconds(2) : config.refreshInterval(),
                this::refresh));
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        future.ifPresent(f -> f.cancel(true));
        future = Optional.empty();
    }

    private void refresh() {
        final List<PrinterLine> lines = farmStatus.status().printers();
        if (selected == null && !lines.isEmpty()) {
            // Something to show in the right-hand pane on a wide screen. Not "opened": on a narrow screen the
            // list stays in front until a row is actually tapped.
            selected = lines.get(0).name();
        }
        // Rebuilt only when what is shown changes. Rebuilding on every tick would swap a button out from under
        // a thumb that is half way through pressing it.
        final String key = lines.toString() + "|" + selected;
        if (!key.equals(listKey)) {
            listKey = key;
            list.removeAll();
            thumbs.clear();
            thumbIds.clear();
            lines.forEach(l -> list.add(row(l)));
        }
        lines.stream().filter(l -> l.name().equals(selected)).findFirst().ifPresent(this::showDetail);
        updateCameras();
    }

    private static String toneClass(final Tone tone) {
        return tone.name().toLowerCase();
    }

    private static String rightText(final PrinterLine l) {
        if (l.inJob()) {
            return l.percent() + "%";
        }
        if (l.tone() == Tone.BAD) {
            return l.canClear() ? "Failed" : "Fault";
        }
        return l.canClear() ? "Done" : "Idle";
    }

    private Div row(final PrinterLine l) {
        final Div row = new Div();
        row.setClassName("farm-row " + toneClass(l.tone()) + (l.name().equals(selected) ? " selected" : ""));
        final Image thumb = new Image();
        thumb.setAlt("");
        thumb.addClassName("farm-thumb");
        thumbs.put(l.name(), thumb);
        final Span name = new Span(l.name());
        name.addClassName("farm-name");
        final Span right = new Span(rightText(l));
        right.addClassName("farm-right");
        final Span text = new Span(l.text());
        text.addClassName("farm-text");
        final Div head = new Div(thumb, name, right, text);
        head.addClassName("farm-row-head");
        head.addClickListener(e -> {
            selected = l.name();
            getElement().setAttribute("data-open", "1");
            refresh();
        });
        row.add(head);
        if (admin) {
            final Div buttons = new Div();
            buttons.addClassName("farm-buttons");
            quickButtons(l).forEach(buttons::add);
            if (buttons.getComponentCount() > 0) {
                row.add(buttons);
            }
        }
        return row;
    }

    /** The one or two buttons worth a thumb's reach without opening the printer. */
    private List<Button> quickButtons(final PrinterLine l) {
        final List<Button> out = new java.util.ArrayList<>();
        if (l.canClear()) {
            out.add(primary(big("Bed cleared", e -> bedCleared(l.name()))));
            if (l.queued() > 0) {
                out.add(big("Start next", e -> startNext(l.name())));
            }
        } else if (l.paused()) {
            out.add(primary(big("Resume", e -> command(l.name(), p -> p.commandControl(BambuConst.CommandControl.RESUME)))));
            out.add(big("Stop", e -> stop(l.name())));
        }
        return out;
    }

    private static Button big(final String text, final com.vaadin.flow.component.ComponentEventListener<
            com.vaadin.flow.component.ClickEvent<Button>> listener) {
        final Button b = new Button(text, listener);
        b.addClassName("farm-big");
        return b;
    }

    private static Button primary(final Button b) {
        b.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return b;
    }

    private void showDetail(final PrinterLine l) {
        final String key = l.toString();
        if (key.equals(detailKey)) {
            return;
        }
        detailKey = key;
        detailBody.removeAll();
        final Span name = new Span(l.name());
        name.addClassName("farm-detail-name");
        final Span right = new Span(rightText(l));
        right.addClassName("farm-detail-right");
        final Div title = new Div(name, right);
        title.addClassName("farm-detail-title");
        final Span text = new Span(l.text());
        text.setClassName("farm-detail-text " + toneClass(l.tone()));
        detailBody.add(title, text);

        if (admin) {
            final Div buttons = new Div();
            buttons.addClassName("farm-buttons");
            if (l.canClear()) {
                buttons.add(primary(big("Bed cleared", e -> bedCleared(l.name()))));
            }
            if (!l.inJob() && l.queued() > 0) {
                buttons.add(big("Start next", e -> startNext(l.name())));
            }
            if (l.paused()) {
                buttons.add(primary(big("Resume", e -> command(l.name(), p -> p.commandControl(BambuConst.CommandControl.RESUME)))));
            } else if (l.inJob()) {
                buttons.add(big("Pause", e -> command(l.name(), p -> p.commandControl(BambuConst.CommandControl.PAUSE))));
            }
            if (l.inJob()) {
                buttons.add(big("Stop", e -> stop(l.name())));
            }
            buttons.add(big("Light", e -> command(l.name(), p -> p.commandLight(
                    p.getLightMode().orElse(BambuConst.LightMode.OFF) == BambuConst.LightMode.ON
                    ? BambuConst.LightMode.OFF : BambuConst.LightMode.ON))));
            detailBody.add(buttons);
        }
        final Div links = new Div();
        links.addClassName("farm-links");
        if (admin) {
            links.add(new Anchor("sdcard/" + l.name(), "SD card"));
        }
        links.add(new Anchor("printer/" + l.name(), "Open printer"));
        detailBody.add(links);
    }

    /** New frames go into the images already on screen; nothing is rebuilt around them. */
    private void updateCameras() {
        printers.getPrintersDetail().forEach(d -> d.printer().getThumbnail().ifPresent(t -> {
            final String id = t.thumbnail().getId();
            final Image thumb = thumbs.get(d.name());
            if (thumb != null && !id.equals(thumbIds.get(d.name()))) {
                thumbIds.put(d.name(), id);
                thumb.setSrc(t.thumbnail());
            }
            if (d.name().equals(selected) && !(d.name() + id).equals(detailCamId)) {
                detailCamId = d.name() + id;
                detailCam.setSrc(t.thumbnail());
            }
        }));
    }

    // -------------------------------------------------------------------------
    // actions
    // -------------------------------------------------------------------------
    private void command(final String name, final Consumer<BambuPrinter> action) {
        printers.getPrinterDetail(name).map(BambuPrinters.PrinterDetail::printer).ifPresent(action);
        refresh();
    }

    private void bedCleared(final String name) {
        if (bedClear.markCleared(name, "Farm")) {
            dispatchQueue.passNow();
        }
        refresh();
    }

    private void stop(final String name) {
        YesNoCancelDialog.show("%s - stop the print?\n\nThis cannot be undone.".formatted(name), ync -> {
            if (ync.isConfirmed()) {
                command(name, p -> p.commandControl(BambuConst.CommandControl.STOP));
            }
        });
    }

    private void startNext(final String name) {
        queueService.peek(name).ifPresentOrElse(entry -> YesNoCancelDialog.show(
                "%s - start the next queued print [%s] plate %d?\n\nIs the bed clear?"
                        .formatted(name, entry.command().filename(), entry.command().plateId()), ync -> {
                    if (!ync.isConfirmed()) {
                        return;
                    }
                    final Optional<UI> ui = getUI();
                    queueService.startNext(name,
                            () -> ui.ifPresent(u -> u.access(() -> {
                                Notification.show("%s: print started".formatted(name));
                                refresh();
                            })),
                            error -> ui.ifPresent(u -> u.access(() -> Notification.show("%s: %s".formatted(name, error)))));
                }), () -> Notification.show("%s: queue is empty".formatted(name)));
    }
}
