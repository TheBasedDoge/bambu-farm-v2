package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.model.Print;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuErrors;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Camera wall: live view of all printers with critical info (progress / ETA / errors) overlaid on the image.
 * Supports a fixed-grid mode (e.g. 2×2) where all cameras fill the entire page.
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "cameras", layout = MainLayout.class)
@PageTitle("Cameras")
@RolesAllowed({ SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_NORMAL })
public class CameraView extends PushDiv implements ViewHelper, NotificationHelper, UpdateHeader {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String JS_SIZE_KEY = "bambufarm-camera-sizes";
    private static final String JS_GRID_KEY = "bambufarm-camera-grid";

    private static final List<String> GRID_OPTIONS = List.of(
            "Auto", "1×1", "1×2", "2×1", "2×2", "2×3", "3×2", "3×3", "3×4", "4×3", "4×4"
    );

    @Inject
    BambuPrinters printers;
    @Inject
    BambuConfig config;
    @Inject
    com.tfyre.bambu.printer.BedClearService bedClear;

    private final List<CameraCard> cards = new ArrayList<>();
    private Select<String> gridSelect;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("camera-view");
        cards.clear();
        printers.getPrinters().stream()
                .sorted(Comparator.comparing(BambuPrinter::getName))
                .forEach(printer -> {
                    final CameraCard card = new CameraCard(printer);
                    cards.add(card);
                    add(card.build());
                });
        applySavedSizes();
        final UI ui = attachEvent.getUI();
        createRefresh(ui, config.refreshInterval(), () -> cards.forEach(CameraCard::update));
    }

    private void applySavedSizes() {
        getElement().executeJs("""
                const KEY = $0;
                const root = this;
                const saved = JSON.parse(localStorage.getItem(KEY) || '{}');
                const setSpan = (c, n) => {
                    c.classList.remove('bspan-2', 'bspan-3', 'bspan-4', 'bspan-5', 'bspan-6');
                    if (n >= 2) { c.classList.add('bspan-' + n); }
                };
                root.querySelectorAll('.camera-card[data-printer]').forEach(c => {
                    const name = c.getAttribute('data-printer');
                    const n = saved[name];
                    if (typeof n === 'number') { setSpan(c, Math.max(1, Math.min(6, n))); }
                    c.addEventListener('mouseup', () => {
                        if (!c.style.width) { return; }
                        const styles = getComputedStyle(root);
                        const cols = styles.gridTemplateColumns.split(' ').length;
                        const gap = parseFloat(styles.columnGap) || 0;
                        const pad = (parseFloat(styles.paddingLeft) || 0) + (parseFloat(styles.paddingRight) || 0);
                        const cell = (root.clientWidth - pad - gap * (cols - 1)) / cols;
                        const span = Math.max(1, Math.min(6, Math.min(cols,
                                Math.round((parseFloat(c.style.width) + gap) / (cell + gap)))));
                        c.style.width = '';
                        c.style.height = '';
                        setSpan(c, span);
                        const data = JSON.parse(localStorage.getItem(KEY) || '{}');
                        data[name] = span;
                        localStorage.setItem(KEY, JSON.stringify(data));
                    });
                });""", JS_SIZE_KEY);
    }

    /** Apply a named grid size like "2×2" or "Auto". Sets CSS vars and height via JS. */
    private void applyGridSize(final String size) {
        if (size == null || "Auto".equals(size)) {
            getElement().executeJs("""
                    this.classList.remove('camera-view--grid');
                    this.style.height = '';
                    this.style.overflow = '';
                    this.style.removeProperty('--cam-cols');
                    this.style.removeProperty('--cam-rows');
                    """);
        } else {
            final String[] parts = size.split("[×x]");
            if (parts.length != 2) {
                return;
            }
            try {
                final int cols = Integer.parseInt(parts[0].trim());
                final int rows = Integer.parseInt(parts[1].trim());
                getElement().executeJs("""
                        this.classList.add('camera-view--grid');
                        this.style.setProperty('--cam-cols', $0);
                        this.style.setProperty('--cam-rows', $1);
                        const al = document.querySelector('vaadin-app-layout');
                        const nav = al && al.shadowRoot ? al.shadowRoot.querySelector('[part=navbar]') : null;
                        const h = nav ? nav.offsetHeight : 65;
                        this.style.height = 'calc(100vh - ' + h + 'px)';
                        this.style.overflow = 'hidden';
                        """, String.valueOf(cols), String.valueOf(rows));
            } catch (NumberFormatException ex) {
                // ignore bad format
            }
        }
    }

    @Override
    public void updateHeader(final HasComponents component) {
        final Button reset = new Button("Reset Layout",
                l -> getElement().executeJs("localStorage.removeItem($0); location.reload();", JS_SIZE_KEY));
        reset.setTooltipText("Reset per-card sizes");

        gridSelect = new Select<>();
        gridSelect.setItems(GRID_OPTIONS);
        gridSelect.setValue("Auto");
        gridSelect.setTooltipText("Camera grid layout — fills the page with the chosen N×M arrangement");
        gridSelect.getStyle().setWidth("100px");

        // Restore saved grid size then wire the change listener
        getElement().executeJs("return localStorage.getItem($0) || 'Auto';", JS_GRID_KEY)
                .then(String.class, saved -> {
                    final String value = GRID_OPTIONS.contains(saved) ? saved : "Auto";
                    gridSelect.setValue(value);
                    applyGridSize(value);
                });

        gridSelect.addValueChangeListener(e -> {
            final String value = e.getValue() != null ? e.getValue() : "Auto";
            applyGridSize(value);
            getElement().executeJs("localStorage.setItem($0, $1);", JS_GRID_KEY, value);
        });

        component.add(reset, gridSelect);
    }

    // -------------------------------------------------------------------------
    // Camera card
    // -------------------------------------------------------------------------

    private final class CameraCard {

        private final BambuPrinter printer;
        private final Image thumbnail = new Image();
        private final Span state = new Span("--");
        private final Span progressText = new Span("");
        private final Span etaText = new Span("");
        private final Span jobText = new Span("");
        private final Div fill = new Div();
        private Div root;
        private String thumbnailId;
        private BambuConst.GCodeState gcodeState = BambuConst.GCodeState.UNKNOWN;

        private CameraCard(final BambuPrinter printer) {
            this.printer = printer;
        }

        private Component getImage() {
            return printer.getIFrame()
                    .map(url -> {
                        final IFrame iframe = new IFrame(url);
                        iframe.getElement().setAttribute("allow", "autoplay; fullscreen; picture-in-picture");
                        iframe.getElement().setAttribute("allowfullscreen", "true");
                        return (Component) iframe;
                    })
                    .orElse(thumbnail);
        }

        private Div build() {
            final Div name = new Div(new Span(printer.getName()));
            name.addClassName("camera-name");
            name.addClickListener(l -> UI.getCurrent().navigate(PrinterView.class, printer.getName()));

            // The Overview's overlay (2026-10-07): a state pill top-left, name and job bottom-left, percentage and
            // time bottom-right, the progress bar along the bottom edge, and an outline on the card that means
            // what it means there. Same classes as the Dashboard cards, so the three pages cannot drift apart.
            final Span dot = new Span();
            dot.addClassName("dash-pill-dot");
            final Div pill = new Div(dot, state);
            pill.addClassName("dash-pill");

            jobText.addClassName("dash-file");
            final Div left = new Div(name, jobText);
            left.addClassName("cam-left");
            progressText.addClassName("dash-pct");
            etaText.addClassName("dash-left");
            final Div right = new Div(progressText, etaText);
            right.addClassName("dash-right");
            final Div info = new Div(left, right);
            info.addClassName("dash-info");

            final Div scrim = new Div();
            scrim.addClassName("dash-scrim");
            fill.addClassName("dash-fill");
            final Div track = new Div(fill);
            track.addClassName("dash-track");

            final Div result = new Div(getImage(), scrim, pill, info, track);
            root = result;
            result.addClassName("camera-card");
            result.getElement().setAttribute("data-printer", printer.getName());
            result.getElement().executeJs("""
                    this.addEventListener('click', e => {
                        if (e.target.closest('.camera-name')) { return; }
                        const img = this.querySelector('img');
                        if (!img || !img.src) { return; }
                        const LIGHTBOX_ID = 'bambu-camera-lightbox';
                        if (document.getElementById(LIGHTBOX_ID)) { return; }
                        const overlay = document.createElement('div');
                        overlay.id = LIGHTBOX_ID;
                        overlay.style.cssText = [
                            'position:fixed','inset:0','z-index:9999',
                            'background:rgba(0,0,0,0.92)',
                            'display:flex','align-items:center','justify-content:center',
                            'cursor:zoom-out'
                        ].join(';');
                        const enlarged = document.createElement('img');
                        enlarged.src = img.src;
                        enlarged.style.cssText = 'max-width:95vw;max-height:95vh;object-fit:contain;border-radius:4px;box-shadow:0 0 40px rgba(0,0,0,0.8);';
                        const close = () => overlay.remove();
                        overlay.addEventListener('click', close);
                        const onKey = (e) => { if (e.key === 'Escape') { close(); document.removeEventListener('keydown', onKey); } };
                        document.addEventListener('keydown', onKey);
                        overlay.appendChild(enlarged);
                        document.body.appendChild(overlay);
                    });""");
            update();
            return result;
        }

        private void update() {
            final BambuConst.GCodeState newState = printer.getGCodeState();
            notifyPrintState(CameraView.this, printer, gcodeState, newState);
            gcodeState = newState;

            printer.getThumbnail().ifPresent(data -> {
                if (data.thumbnail().getId().equals(thumbnailId)) {
                    return;
                }
                thumbnailId = data.thumbnail().getId();
                thumbnail.setSrc(data.thumbnail());
            });

            final boolean cleared = bedClear.isCleared(printer.getName());
            final boolean paused = gcodeState == BambuConst.GCodeState.PAUSE;
            final boolean printing = gcodeState.isPrinting() || paused;
            final int error = printer.getPrintError();
            final boolean fault = error != 0 || !printer.getActiveHmsErrors().isEmpty();
            final boolean failed = gcodeState == BambuConst.GCodeState.FAILED && !cleared;
            final boolean done = !fault && gcodeState == BambuConst.GCodeState.FINISH && !cleared;

            state.setText(fault ? "Fault" : failed ? "Print failed" : done ? "Finished · clear bed"
                    : paused ? "Paused" : gcodeState.isPrinting() ? "Printing"
                    : gcodeState.isReady() ? "Idle" : gcodeState.getDescription());
            if (root != null) {
                root.getElement().setAttribute("data-tone", fault || failed ? "bad" : done ? "done" : paused ? "warn" : "none");
            }

            if (error != 0) {
                jobText.setText("Error [%s]: %s".formatted(Integer.toHexString(error),
                        BambuErrors.getPrinterError(error).orElse("Unknown")));
            } else if (fault) {
                jobText.setText(String.join(" · ", printer.getActiveHmsErrors()));
            } else {
                jobText.setText(printing || failed || done ? printer.getSubtaskName().orElse("") : "");
            }

            if (printing) {
                printer.getStatus()
                        .map(m -> m.message().hasPrint() ? m.message().getPrint() : null)
                        .ifPresent(this::updateProgress);
            } else {
                progressText.setText("");
                etaText.setText("");
            }
            final int percent = Math.max(0, printer.getProgressPercent());
            fill.getStyle().set("width", (printing || failed ? percent : done ? 100 : 0) + "%");
        }

        private void updateProgress(final Print print) {
            if (print == null) {
                return;
            }
            if (print.hasMcPercent()) {
                progressText.setText("%d%%".formatted(print.getMcPercent()));
            }
            if (print.hasMcRemainingTime()) {
                final Duration remaining = Duration.ofMinutes(print.getMcRemainingTime());
                etaText.setText("%s left · done %s".formatted(formatTime(remaining), HHMM.format(LocalTime.now().plus(remaining))));
            }
        }

    }

}
