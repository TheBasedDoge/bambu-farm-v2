package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.AutoQueueService;
import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuErrors;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedDiffService;
import com.tfyre.bambu.printer.BedReferenceService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.MaintenanceService;
import com.tfyre.bambu.printer.OrderTrackingService;
import com.tfyre.bambu.printer.PrintAiService;
import com.tfyre.bambu.printer.PrintHistoryService;
import com.tfyre.bambu.printer.PrintQueueService;
import com.tfyre.bambu.printer.SpoolService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The wall display - one screen meant to be left up on a monitor and read from across the room.
 * <p>
 * <b>The governing rule: the screen should be boring when the farm is fine.</b> Everything here is arranged so
 * that a glance from three metres away is a binary question - is there colour, or isn't there. When nothing is
 * wrong the alert banner is absent entirely, the printer tiles carry no outlines, and the attention panel
 * collapses to a single line. When something IS wrong, exactly one headline says what, at a size you can read
 * standing up.
 * <p>
 * Deliberately <b>not</b> a denser version of the Automation overview. That page is for working: it has tabs,
 * expandable rows, buttons, and rewards a close read. This one has no interactions at all - nothing to click,
 * nothing to expand, no saved layout. A display is state that anyone can disturb by leaning on the desk, and the
 * failure mode of a dashboard that has been left in the wrong tab is that you stop trusting it.
 * <p>
 * Three things exist for the "left on for months" case specifically:
 * <ul>
 * <li><b>Burn-in protection</b> - the whole page is nudged a few pixels around a slow cycle, so no static edge
 * ever occupies one pixel long enough to ghost an OLED or plasma.</li>
 * <li><b>Overnight dim</b> - dimmed, not blanked. The farm still runs at 3am and a red banner should be visible
 * from the doorway; it just shouldn't light the room.</li>
 * <li><b>A chime</b> - opt-in, and fired only on the <i>transition</i> into a failed state. An alarm that
 * re-sounds every poll is one you mute permanently, which is worse than having none.</li>
 * </ul>
 * The camera tiles are <b>snapshots</b>, refreshed in place, never live streams: five WebRTC feeds running
 * permanently would hold the CPU forever, and the H2D needs an ffmpeg process per frame. A still answers the only
 * question this screen asks of a camera - is there a part on that plate.
 */
@Route(value = "overview", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Overview")
@RolesAllowed({SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_NORMAL})
public class OverviewView extends VerticalLayout {

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    PrintAiService aiService;
    @Inject
    AutoStartService autoStartService;
    @Inject
    AutoQueueService autoQueueService;
    @Inject
    BedDiffService bedDiff;
    @Inject
    BedReferenceService bedReference;
    @Inject
    DispatchQueueService dispatchQueue;
    @Inject
    OrderTrackingService tracking;
    @Inject
    PrintHistoryService historyService;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    MaintenanceService maintenance;
    @Inject
    SpoolService spools;
    @Inject
    ScheduledExecutorService ses;

    private final Div board = new Div();
    private Optional<ScheduledFuture<?>> future = Optional.empty();

    /**
     * The page is five persistent slots, each with its own change key, rather than one tree rebuilt wholesale.
     * <p>
     * That started as an efficiency argument and became a correctness one: the H2D tile holds a live WebRTC
     * {@code <iframe>}, and moving an iframe in the DOM makes the browser reload it. Under a whole-page rebuild
     * the stream would tear down and renegotiate every time any printer's percentage ticked - roughly once a
     * minute, forever. Slots that never move can hold something that must not be moved.
     */
    private final Div barSlot = new Div();
    private final Div alertSlot = new Div();
    private final Div kpiSlot = new Div();
    private final Div printerGrid = new Div();
    private final Div bottomSlot = new Div();
    private String barKey = "";
    private String alertKey = "";
    private String kpiKey = "";
    private String bottomKey = "";

    /** One per printer, built once and then UPDATED - never rebuilt. Insertion-ordered to match the grid. */
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    /** Live JPEG thumbnails, refreshed in place every tick - see {@link #updateCameras}. */
    private final Map<String, Image> liveCams = new HashMap<>();
    /** Last frame id pushed per printer, so an unchanged frame isn't re-registered for nothing. */
    private final Map<String, String> camIds = new HashMap<>();

    /**
     * Whether the previous pass was in the critical state. The chime fires on the false-to-true edge only; see
     * the class comment on why an alarm that repeats is an alarm that gets muted.
     */
    private boolean wasCritical;
    /** Set once the first pass has run, so opening the page during an existing failure doesn't sound the chime. */
    private boolean seenFirstPass;

    // -------------------------------------------------------------------------
    // lifecycle
    // -------------------------------------------------------------------------
    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        setPadding(false);
        setSpacing(false);
        // Full height, set here rather than left to CSS: a percentage height only resolves if every ancestor has
        // one, and if it silently doesn't the tiles collapse to their content and the bottom panels fall off the
        // screen - on a display nobody scrolls, that means they simply never appear.
        setSizeFull();
        addClassName("wall-view");

        board.addClassName("wall");
        alertSlot.addClassName("wall-alert-slot");
        printerGrid.addClassName("wall-printers");
        board.add(barSlot, alertSlot, kpiSlot, printerGrid, bottomSlot);
        add(board);

        barKey = "";
        alertKey = "";
        kpiKey = "";
        bottomKey = "";
        tiles.clear();
        printerGrid.removeAll();
        seenFirstPass = false;
        refresh();
        installDisplayScripts();
        hideChrome();

        final UI ui = attachEvent.getUI();
        future.ifPresent(f -> f.cancel(true));
        future = Optional.of(ses.scheduleAtFixedRate(
                () -> ui.access(this::refresh),
                0, Math.max(1, config.refreshInterval().getSeconds()), TimeUnit.SECONDS));
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        future.ifPresent(f -> f.cancel(true));
        future = Optional.empty();
        liveCams.clear();
        camIds.clear();
        tiles.clear();
        // Not optional. The class and the key handler live on the AppLayout, which OUTLIVES this view - leave
        // them behind and the user navigates to Settings and finds an app with no menu and no way back.
        //
        // Scheduled on the PAGE, not on this element: JS queued against an element that is being detached is
        // dropped, so cleaning up through getElement() here would work in testing and fail exactly when it
        // matters. The page survives the navigation that caused the detach.
        restoreChrome(detachEvent.getUI());
    }

    // -------------------------------------------------------------------------
    // fullscreen: hiding the app's own chrome
    // -------------------------------------------------------------------------
    /**
     * Hides the navbar and the sidebar, so the wall display is the whole screen.
     * <p>
     * A monitor across the room has no use for a hamburger and a username, and the twenty-odd percent of the
     * panel they occupy is the difference between a printer tile you can read standing up and one you squint at.
     * <p>
     * The class goes on the {@code vaadin-app-layout} element, which is <b>not</b> part of this view - it belongs
     * to {@link com.tfyre.bambu.MainLayout} and survives navigation. Everything set here is therefore undone in
     * {@link #onDetach}; the failure mode of forgetting is an app with no menu on every other page.
     * <p>
     * Two ways out, because one is never enough on a screen with no visible controls: <b>Esc</b>, and a button in
     * the top-right corner that appears whenever the mouse moves. Escape alone would be a keyboard shortcut nobody
     * was told about; the button alone would be invisible to anyone who nudged the mouse and saw nothing.
     */
    private void hideChrome() {
        getElement().executeJs("""
                const view = this;
                const app = view.closest('vaadin-app-layout') || document.querySelector('vaadin-app-layout');
                if (!app) { return; }
                app.classList.add('wall-fullscreen');

                // Held on the app-layout, not in a closure, so detach can find and remove it. Re-attaching this
                // view would otherwise stack a handler per visit and Esc would fire five times.
                if (app.__wallEsc) { document.removeEventListener('keydown', app.__wallEsc); }
                app.__wallEsc = (e) => {
                    if (e.key === 'Escape') { app.classList.toggle('wall-fullscreen'); }
                };
                document.addEventListener('keydown', app.__wallEsc);

                app.__wallToggle = () => app.classList.toggle('wall-fullscreen');

                // The pointer hides itself after a while and the exit button goes with it. On a display left up
                // for months an idle cursor sitting over a printer tile is both a distraction and, on an OLED, one
                // more static bright pixel.
                if (app.__wallIdle) { document.removeEventListener('mousemove', app.__wallIdle); }
                let timer = null;
                app.__wallIdle = () => {
                    view.classList.add('wall-awake');
                    clearTimeout(timer);
                    timer = setTimeout(() => view.classList.remove('wall-awake'), 3000);
                };
                document.addEventListener('mousemove', app.__wallIdle);
                app.__wallIdle();""");
    }

    /** Puts the navbar and sidebar back, and unhooks everything {@link #hideChrome} left on the app layout. */
    private void restoreChrome(final UI ui) {
        if (ui == null) {
            return;
        }
        ui.getPage().executeJs("""
                const app = document.querySelector('vaadin-app-layout');
                if (!app) { return; }
                app.classList.remove('wall-fullscreen');
                if (app.__wallEsc) { document.removeEventListener('keydown', app.__wallEsc); app.__wallEsc = null; }
                if (app.__wallIdle) { document.removeEventListener('mousemove', app.__wallIdle); app.__wallIdle = null; }
                app.__wallToggle = null;""");
    }

    // -------------------------------------------------------------------------
    // the display behaviours: burn-in, night dim, chime, clock
    // -------------------------------------------------------------------------
    /**
     * Client-side because all three are properties of the screen, not of the farm: they must keep working while
     * the server is redeploying, and none of them is worth a round trip.
     */
    private void installDisplayScripts() {
        getElement().executeJs("""
                const root = this;
                if (root.__wallWired) { return; }
                root.__wallWired = true;

                // Burn-in protection. Walks the page around a small box once a minute. A transform rather than
                // margins: it rides the compositor, costs nothing, and cannot reflow the layout underneath it.
                // The amplitude is the point - a few pixels is enough that no static edge sits on one pixel for
                // months, and small enough that nobody notices the page moved.
                const box = [[0,0],[4,2],[7,-1],[3,5],[-3,3],[-6,-2],[-2,-5],[2,-3]];
                let step = 0;
                setInterval(() => {
                    const p = box[step++ % box.length];
                    root.style.transform = 'translate(' + p[0] + 'px,' + p[1] + 'px)';
                }, 60000);

                // Overnight dim. Dimmed, not blanked: a red banner should still be readable from the doorway at
                // 3am, it just shouldn't light the room.
                const night = () => {
                    const h = new Date().getHours();
                    root.classList.toggle('wall-night', h >= 1 && h < 6);
                };
                night();
                setInterval(night, 60000);

                // The clock is client-side on purpose. If the server stops pushing, a frozen clock is the
                // clearest possible signal that what you are looking at is no longer true.
                const tick = () => {
                    const el = root.querySelector('.wall-clock');
                    if (!el) { return; }
                    const d = new Date();
                    let h = d.getHours();
                    const half = h < 12 ? 'am' : 'pm';
                    h = h % 12 || 12;
                    el.textContent = h + ':' + String(d.getMinutes()).padStart(2, '0') + ' ' + half;
                };
                // The camera age badges tick alongside it, from a data-at epoch stamped by the server. Client-side
                // for the same reason as the clock, plus one more: an age that changed server-side would land in
                // the change-detection key and rebuild the entire board once a second, forever.
                const ages = () => {
                    const now = Date.now();
                    root.querySelectorAll('.wall-cam-age[data-at]').forEach(el => {
                        const secs = Math.max(0, Math.round((now - Number(el.dataset.at)) / 1000));
                        el.textContent = secs < 60 ? secs + 's'
                                : secs < 3600 ? Math.round(secs / 60) + 'm'
                                : secs < 86400 ? Math.round(secs / 3600) + 'h'
                                : Math.round(secs / 86400) + 'd';
                        // Older than ten minutes stops being "the current view of the plate" and starts being a
                        // photograph. Past a day it is history, and the badge says so loudly enough to notice.
                        el.classList.toggle('stale', secs > 600);
                        el.classList.toggle('ancient', secs > 86400);
                    });
                };

                tick();
                ages();
                setInterval(() => { tick(); ages(); }, 5000);

                // WebAudio rather than a sound file: nothing to ship and nothing to 404. Two short falling tones,
                // distinct enough to recognise from another room and short enough not to be resented.
                let ctx = null;
                root.__wallChime = () => {
                    if (localStorage.getItem('bambufarm-wall-sound') !== 'on') { return; }
                    try {
                        ctx = ctx || new (window.AudioContext || window.webkitAudioContext)();
                        if (ctx.state === 'suspended') { ctx.resume(); }
                        [880, 620].forEach((f, i) => {
                            const o = ctx.createOscillator(), g = ctx.createGain();
                            const t = ctx.currentTime + i * 0.18;
                            o.type = 'sine';
                            o.frequency.setValueAtTime(f, t);
                            g.gain.setValueAtTime(0.0001, t);
                            g.gain.exponentialRampToValueAtTime(0.25, t + 0.02);
                            g.gain.exponentialRampToValueAtTime(0.0001, t + 0.34);
                            o.connect(g); g.connect(ctx.destination);
                            o.start(t); o.stop(t + 0.36);
                        });
                    } catch (e) {
                        // No audio device, or the browser declined. Not worth breaking a wall display over.
                    }
                };""");
    }

    /** Fires the chime, if the viewer has switched it on. Server decides WHEN; the browser decides whether. */
    private void chime() {
        getElement().executeJs("if (this.__wallChime) { this.__wallChime(); }");
    }

    // -------------------------------------------------------------------------
    // refresh
    // -------------------------------------------------------------------------
    /**
     * Refreshes each slot only when the data behind it actually changed.
     * <p>
     * Per slot rather than per page, for two reasons. The cheap one: on a screen left running for months, this is
     * the difference between a page that idles and one that rebuilds its whole DOM once a second forever. The
     * important one: the printer row is never rebuilt at all, only updated in place, because it can contain a live
     * video iframe and moving an iframe in the DOM makes the browser reload it.
     */
    private void refresh() {
        final List<PrinterState> states = printers.getPrintersDetail().stream()
                .map(this::readPrinter)
                .sorted(Comparator.comparing(PrinterState::name))
                .toList();

        final Alert alert = worstProblem(states);

        barKey = fill(barSlot, barKey, () -> buildBar(states), keyOf(states.size(), onlineCount()));
        alertKey = fillAlert(alert);
        kpiKey = fill(kpiSlot, kpiKey, () -> buildKpis(states), kpiKey(states));
        syncPrinters(states);
        bottomKey = fill(bottomSlot, bottomKey, () -> buildBottom(states), bottomKey(states));

        updateCameras();

        final boolean critical = alert != null && alert.critical();
        if (critical && !wasCritical && seenFirstPass) {
            chime();
        }
        wasCritical = critical;
        seenFirstPass = true;
    }

    /** Replaces a slot's contents only when its key moved. Returns the key to remember. */
    private String fill(final Div slot, final String currentKey, final java.util.function.Supplier<Component> build,
            final String newKey) {
        if (currentKey.equals(newKey)) {
            return currentKey;
        }
        slot.removeAll();
        slot.add(build.get());
        return newKey;
    }

    private String fillAlert(final Alert alert) {
        final String newKey = alert == null ? "-"
                : alert.critical() + alert.headline() + alert.detail() + alert.aside();
        if (alertKey.equals(newKey)) {
            return alertKey;
        }
        alertSlot.removeAll();
        // No setVisible needed: the slot is display:contents, so when it holds nothing it contributes nothing -
        // no box, no flex gap. It exists purely so the banner coming and going never re-parents the printer row
        // below it, which holds a live stream that must not be moved.
        if (alert != null) {
            alertSlot.add(buildAlert(alert));
        }
        return newKey;
    }

    private long onlineCount() {
        return printers.getPrintersDetail().stream().filter(BambuPrinters.PrinterDetail::isRunning).count();
    }

    private static String keyOf(final Object... parts) {
        final StringBuilder sb = new StringBuilder();
        for (final Object p : parts) {
            sb.append(p).append('|');
        }
        return sb.toString();
    }

    /** Pushes new frames into the images already on screen, instead of rebuilding the tiles around them. */
    private void updateCameras() {
        liveCams.forEach((name, img) -> printers.getPrinterDetail(name)
                .flatMap(d -> d.printer().getThumbnail())
                .ifPresent(t -> {
                    final String id = t.thumbnail().getId();
                    if (!id.equals(camIds.get(name))) {
                        camIds.put(name, id);
                        img.setSrc(t.thumbnail());
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // reading the farm
    // -------------------------------------------------------------------------
    /** Everything this screen needs about one printer, read once so the layout can't ask twice and disagree. */
    private record PrinterState(String name, BambuConst.GCodeState state, Optional<String> fault, Optional<String> job,
            int percent, int remainingMinutes, Optional<PrintAiService.AiCheckResult> ai, boolean aiStale,
            boolean bedUnprotected, int queued) {

        boolean printing() {
            return state.isPrinting();
        }

        boolean failedCheck() {
            return ai.filter(r -> !r.good()).isPresent() && !aiStale;
        }
    }

    private PrinterState readPrinter(final BambuPrinters.PrinterDetail detail) {
        final BambuPrinter printer = detail.printer();
        final String name = detail.name();
        final Optional<PrintAiService.AiCheckResult> ai = aiService.getLastResult(name);
        return new PrinterState(name, printer.getGCodeState(), fault(printer),
                printer.getSubtaskName().filter(s -> !s.isBlank()),
                Math.max(printer.getProgressPercent(), 0), Math.max(printer.getRemainingMinutes(), 0),
                ai, ai.isPresent() && isStale(ai.get(), printer), bedUnprotected(name),
                queueService.size(name));
    }

    private Optional<String> fault(final BambuPrinter printer) {
        final int code = printer.getPrintError();
        if (code != 0) {
            return Optional.of("Error %s: %s".formatted(Integer.toHexString(code),
                    BambuErrors.getPrinterError(code).orElse("unknown error")));
        }
        final List<String> hms = printer.getActiveHmsErrors();
        return hms.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", hms));
    }

    /** A verdict from before the printer's current situation is worse than no verdict. */
    private boolean isStale(final PrintAiService.AiCheckResult r, final BambuPrinter printer) {
        if (fault(printer).isPresent()) {
            return true;
        }
        return Duration.between(r.checkedAt(), Instant.now())
                .compareTo(config.ollama().failureCheckInterval().multipliedBy(2)) > 0;
    }

    /**
     * Whether this printer's bed gate is armed but its reference can no longer be relied on. Same
     * {@link BedDiffService#trustOf} the AI Settings and Automation pages use, so three screens cannot describe
     * one reading differently.
     */
    private boolean bedUnprotected(final String name) {
        if (!bedDiff.isEnabled()
                || !(autoStartService.isEnabled(name) || autoQueueService.isPrinterEnabled(name))) {
            return false;
        }
        return bedDiff.getLastMeasurement(name).map(bedDiff::trustOf)
                .filter(t -> t != BedDiffService.Trust.PROTECTING).isPresent();
    }

    // -------------------------------------------------------------------------
    // the banner
    // -------------------------------------------------------------------------
    /** @param critical true for "go and look now", false for "worth knowing, finish your coffee" */
    private record Alert(boolean critical, String headline, String detail, String aside) {

    }

    /**
     * The single worst thing wrong, or null.
     * <p>
     * <b>One headline, not a list.</b> Five warnings at equal weight is how you learn to read none of them. The
     * rest becomes a quiet count on the right, and the full list lives in the attention panel below where it
     * isn't competing for the same glance.
     * <p>
     * Ordered by what actually costs money: a print failing right now, then a printer that has faulted, then a
     * gate running without protection, then work that has stopped moving.
     */
    private Alert worstProblem(final List<PrinterState> states) {
        final List<String> also = new ArrayList<>();
        final long unprotected = states.stream().filter(PrinterState::bedUnprotected).count();
        final int parked = dispatchQueue.parkedCount();
        if (unprotected > 0) {
            also.add("%d bed%s unprotected".formatted(unprotected, unprotected == 1 ? "" : "s"));
        }
        if (parked > 0) {
            also.add("%d job%s parked".formatted(parked, parked == 1 ? "" : "s"));
        }
        final String aside = also.isEmpty() ? "" : String.join(" · ", also);

        final Optional<PrinterState> failed = states.stream().filter(PrinterState::failedCheck).findFirst();
        if (failed.isPresent()) {
            final PrinterState p = failed.get();
            final PrintAiService.AiCheckResult r = p.ai().orElseThrow();
            return new Alert(true, "%s — %s".formatted(p.name(), truncate(r.description(), 90)),
                    "%s · %s check%s".formatted(ago(r.checkedAt()), shortCheck(r.checkType()),
                            p.job().map(j -> " · " + j).orElse("")),
                    aside);
        }

        final Optional<PrinterState> faulted = states.stream().filter(s -> s.fault().isPresent()).findFirst();
        if (faulted.isPresent()) {
            final PrinterState p = faulted.get();
            return new Alert(true, "%s — %s".formatted(p.name(), truncate(p.fault().orElseThrow(), 90)),
                    "the printer is reporting this itself; nothing will dispatch to it", aside);
        }

        if (unprotected > 0) {
            return new Alert(false, "%d bed%s running unprotected".formatted(unprotected, unprotected == 1 ? "" : "s"),
                    "no usable bed reference — auto-start is gated on the AI check alone",
                    parked > 0 ? "%d job%s parked".formatted(parked, parked == 1 ? "" : "s") : "");
        }

        final Optional<String> blocked = dispatchQueue.getBlockedStatus();
        if (parked > 0 || (blocked.isPresent()
                && dispatchQueue.getBlockedKind() == DispatchQueueService.BlockKind.ATTENTION)) {
            return new Alert(false,
                    parked > 0 ? "%d job%s parked in the dispatch pool".formatted(parked, parked == 1 ? "" : "s")
                            : "Dispatch pool held",
                    blocked.orElse("nothing will start until this is cleared"), "");
        }
        return null;
    }

    private Div buildAlert(final Alert alert) {
        final Div box = new Div();
        box.addClassName("wall-alert");
        box.addClassName(alert.critical() ? "crit" : "warn");

        final Span glyph = new Span("⚠");
        glyph.addClassName("wall-alert-glyph");

        final Div head = new Div(new Span(alert.headline()));
        head.addClassName("wall-alert-head");
        final Div sub = new Div(new Span(alert.detail()));
        sub.addClassName("wall-alert-sub");
        final Div text = new Div(head, sub);
        text.addClassName("wall-alert-text");
        box.add(glyph, text);

        if (!alert.aside().isBlank()) {
            final Div aside = new Div(new Span("also waiting"), new Div(new Span(alert.aside())));
            aside.addClassName("wall-alert-aside");
            box.add(aside);
        }
        return box;
    }

    // -------------------------------------------------------------------------
    // sections
    // -------------------------------------------------------------------------
    private Div buildBar(final List<PrinterState> states) {
        final long online = onlineCount();
        final Div bar = new Div();
        bar.addClassName("wall-bar");

        // The app mark IS the way back. It used to be a separate pill floating in the top-right corner, which
        // put it straight on top of the clock - and a wall display has room for exactly one thing in each
        // corner. Clicking the logo to get the menu back is the ordinary web idiom anyway, it needs no space of
        // its own, and it cannot collide with anything because it is laid out rather than positioned.
        final Image mark = new Image("favicon.svg", "Show or hide the app menu");
        mark.addClassName("wall-mark");
        mark.getElement().setAttribute("title", "Show or hide the app menu (or press Esc)");
        mark.addClickListener(e -> getElement().executeJs("""
                const app = document.querySelector('vaadin-app-layout');
                if (app && app.__wallToggle) { app.__wallToggle(); }"""));
        final Span title = new Span("Overview");
        title.addClassName("wall-title");
        final Div brand = new Div(mark, title);
        brand.addClassName("wall-brand");

        final Span conn = new Span("%d of %d printers online".formatted(online, states.size()));
        if (online < states.size()) {
            conn.getStyle().setColor("var(--lumo-warning-text-color, #e8a33d)");
        }
        // Rendered empty and filled by the client, so a stalled server shows a frozen clock rather than a
        // plausible-looking wrong time.
        final Span clock = new Span();
        clock.addClassName("wall-clock");
        final Div meta = new Div(conn, clock);
        meta.addClassName("wall-meta");

        bar.add(brand, meta);
        return bar;
    }

    /** Everything the headline row displays, so a tick that changes none of it doesn't touch the DOM. */
    private String kpiKey(final List<PrinterState> states) {
        final PrintHistoryService.TodayStats today = historyService.getTodayStats();
        final List<OrderSummary> orders = openOrders();
        return keyOf(states.stream().filter(PrinterState::printing).count(), readyCount(states), states.size(),
                orders.size(), orders.stream().filter(OrderSummary::readyToShip).count(),
                dispatchQueue.size(), dispatchQueue.parkedCount(),
                today.finished(), today.failed(), (long) today.grams());
    }

    /** Printers that could take work now: idle AND not reporting a fault. */
    private static long readyCount(final List<PrinterState> states) {
        return states.stream().filter(s -> s.fault().isEmpty() && s.state().isReady()).count();
    }

    private Div buildKpis(final List<PrinterState> states) {
        final long printing = states.stream().filter(PrinterState::printing).count();
        // Ready means "could take work now". Total minus printing would count a paused or errored machine as
        // available, which is the one thing this number must never do.
        final long ready = readyCount(states);
        final List<OrderSummary> orders = openOrders();
        final long readyToShip = orders.stream().filter(OrderSummary::readyToShip).count();
        final int pool = dispatchQueue.size();
        final int parked = dispatchQueue.parkedCount();
        final PrintHistoryService.TodayStats today = historyService.getTodayStats();

        final Div row = new Div();
        row.addClassName("wall-kpis");

        // getNextFree() answers "when does the first BUSY printer finish", so it is empty when nothing is
        // printing - the opposite of "all busy". It's only worth asking once there is genuinely nothing free.
        final String printingSub;
        if (ready > 0) {
            printingSub = "%d ready".formatted(ready);
        } else if (printing == states.size() && printing > 0) {
            printingSub = dispatchQueue.getNextFree().map(s -> "next free " + s).orElse("all busy");
        } else {
            // Nothing printing and nothing ready: every machine is paused, faulted or offline. Saying "0 ready"
            // rather than inventing a cheerier phrasing - this is a farm that has stopped.
            printingSub = "none ready";
        }
        row.add(kpi("Printing", "%d".formatted(printing), " / %d".formatted(states.size()),
                printingSub, null, ready == 0 && printing < states.size() ? "warn" : null));
        row.add(kpi("Open orders", String.valueOf(orders.size()), "",
                readyToShip > 0 ? "%d ready to ship".formatted(readyToShip) : "%d in progress".formatted(orders.size()),
                readyToShip > 0 ? "ok" : null, readyToShip > 0 ? "ok" : null));
        row.add(kpi("Waiting to dispatch", String.valueOf(pool), "",
                parked > 0 ? "%d parked".formatted(parked) : "in the pool",
                parked > 0 ? "bad" : null, parked > 0 ? "bad" : null));
        row.add(kpi("Today", String.valueOf(today.finished()), "",
                today.failed() > 0
                        ? "%d failed · %.0f g".formatted(today.failed(), today.grams())
                        : "%.0f g filament".formatted(today.grams()),
                null, today.failed() > 0 ? "warn" : null));
        return row;
    }

    private static Div kpi(final String label, final String value, final String unit, final String sub,
            final String valueTone, final String subTone) {
        final Div box = new Div();
        box.addClassName("wall-kpi");

        final Div l = new Div(new Span(label));
        l.addClassName("wall-kpi-label");

        final Span v = new Span(value);
        final Span u = new Span(unit);
        u.addClassName("wall-kpi-unit");
        final Div val = new Div(v, u);
        val.addClassName("wall-kpi-value");
        if (valueTone != null) {
            val.addClassName("tone-" + valueTone);
        }

        final Div s = new Div(new Span(sub));
        s.addClassName("wall-kpi-sub");
        if (subTone != null) {
            s.addClassName("tone-" + subTone);
        }

        box.add(l, val, s);
        return box;
    }

    /**
     * One printer's tile, built once and thereafter only updated.
     * <p>
     * Holding references to the mutable pieces is more code than rebuilding the tile, and it is the price of the
     * H2D showing a live stream: an {@code <iframe>} that moves in the DOM is an iframe the browser reloads, so
     * the element holding it can never be replaced.
     */
    private static final class Tile {

        private final Div root = new Div();
        private final Div cam = new Div();
        private final Span dot = new Span();
        private final Span jobText = new Span();
        private final Div jobBox = new Div();
        private final Div fill = new Div();
        private final Span footLeft = new Span();
        private final Span footRight = new Span();
        private final Div foot = new Div();
        /** What is currently inside {@link #cam}, so it is only replaced when it genuinely has to be. */
        private String camKind = "";

        Tile(final String name) {
            root.addClassName("wall-p");
            cam.addClassName("wall-cam");

            dot.addClassName("wall-dot");
            final Div nameRow = new Div(dot, new Span(name));
            nameRow.addClassName("wall-p-name");

            jobBox.addClassName("wall-p-job");
            jobBox.add(jobText);

            fill.addClassName("wall-fill");
            final Div track = new Div(fill);
            track.addClassName("wall-track");

            foot.addClassName("wall-p-foot");
            foot.add(footLeft, footRight);

            final Div body = new Div(nameRow, jobBox, track, foot);
            body.addClassName("wall-p-body");
            root.add(cam, body);
        }
    }

    /**
     * Brings the printer row into line with the current states.
     * <p>
     * The grid is rebuilt only when the set of printers itself changes - a machine added, removed or renamed -
     * which is close to never. Every ordinary tick just updates text and classes in place.
     */
    private void syncPrinters(final List<PrinterState> states) {
        final List<String> names = states.stream().map(PrinterState::name).toList();
        if (!List.copyOf(tiles.keySet()).equals(names)) {
            tiles.clear();
            printerGrid.removeAll();
            liveCams.clear();
            camIds.clear();
            names.forEach(n -> tiles.put(n, new Tile(n)));
            tiles.values().forEach(t -> printerGrid.add(t.root));
            // One track per printer, set here rather than in CSS: the count is a property of your farm, and a
            // fixed five-column rule would leave a hole the day a printer is added or taken offline.
            printerGrid.getStyle().set("grid-template-columns",
                    "repeat(%d, minmax(0, 1fr))".formatted(Math.max(1, names.size())));
        }
        states.forEach(s -> updateTile(tiles.get(s.name()), s));
    }

    private void updateTile(final Tile t, final PrinterState s) {
        final boolean bad = s.failedCheck() || s.fault().isPresent();
        final boolean warn = !bad && s.bedUnprotected();

        // setClassName replaces the whole attribute - the tone has to be cleared as well as set, or a printer
        // that recovers keeps its red outline until the page is reloaded.
        t.root.setClassName("wall-p" + (bad ? " bad" : warn ? " warn" : ""));
        t.dot.setClassName("wall-dot " + (bad ? "bad" : warn ? "warn"
                : s.printing() || s.state().isReady() ? "ok" : "off"));

        final String jobText;
        if (s.fault().isPresent()) {
            jobText = truncate(s.fault().orElseThrow(), 40);
        } else if (s.failedCheck()) {
            jobText = truncate(s.ai().orElseThrow().description(), 40);
        } else if (s.printing()) {
            jobText = s.job().orElse("(unknown file)");
        } else if (s.bedUnprotected()) {
            jobText = "%s · bed unverified".formatted(s.state().getDescription().toLowerCase());
        } else {
            jobText = s.state().getDescription().toLowerCase();
        }
        t.jobText.setText(jobText);
        t.jobBox.setClassName("wall-p-job" + (bad ? " tone-bad" : warn ? " tone-warn" : ""));

        t.fill.setClassName("wall-fill" + (bad ? " bad" : ""));
        t.fill.getStyle().set("width", (s.printing() ? s.percent() : 0) + "%");

        t.footLeft.setText(s.printing() ? s.percent() + "%"
                : s.state().isReady() ? "ready" : s.state().getDescription().toLowerCase());
        t.footRight.setText(bad ? "needs you"
                : s.printing() ? eta(s.remainingMinutes())
                : s.queued() > 0 ? "%d queued".formatted(s.queued()) : "queue empty");
        t.foot.setClassName("wall-p-foot" + (bad ? " tone-bad" : ""));

        updateCam(t, s);
    }

    /**
     * A frame to show for a printer, and - the part that matters - <b>when it was taken</b>.
     *
     * @param at empty when the source keeps no timestamp; the badge then says "cached" rather than inventing an
     *           age, because a made-up "just now" on a stale picture is the exact failure this record exists to
     *           prevent
     */
    private record Frame(byte[] bytes, String source, Optional<Instant> at) {

    }

    /**
     * The best frame available for a printer, newest source first.
     * <p>
     * Only the P-series push the port-6000 JPEG stream. The X1C/X1E/H2D never do, so for those this falls through
     * to whatever was last captured by an AI check, and failing that to the saved empty-bed reference on disk -
     * which survives restarts and can be <i>weeks</i> old. That's still worth showing, because it is genuinely
     * this camera and a real picture beats a grey box on a screen whose job is to be glanceable. It is emphatically
     * not worth showing <i>silently</i>, which is what this page did at first: an H2D was displaying a reference
     * frame from ten days earlier, framed identically to a live thumbnail from twenty seconds ago.
     * <p>
     * Deliberately the CACHED frame, never {@code aiService.getSnapshot()}: that spawns ffmpeg when nothing is
     * cached, and this renders on a page that refreshes forever.
     */
    private Optional<Frame> lastFrame(final String name) {
        final Optional<PrintAiService.CheckRecord> check = aiService.getLastCheck(name)
                .filter(r -> r.snapshot() != null && r.snapshot().length > 0);
        if (check.isPresent()) {
            return Optional.of(new Frame(check.get().snapshot(), "last AI check", Optional.of(check.get().at())));
        }
        // The bed-diff backstop keeps its frame in memory with no timestamp. It can only have been captured by a
        // check since the last restart, so it is recent-ish - but "ish" is not a number, and the badge says so.
        final Optional<byte[]> diff = bedDiff.getLastFrame(name);
        if (diff.isPresent()) {
            return Optional.of(new Frame(diff.get(), "bed comparison", Optional.empty()));
        }
        return bedReference.getReference(name)
                .map(b -> new Frame(b, "saved empty-bed reference", bedReference.referenceCapturedAt(name)));
    }

    /**
     * Puts the right thing in a tile's camera cell, and - crucially - leaves it alone when it is already right.
     * <p>
     * Three cases, in order of how current they are:
     * <ol>
     * <li><b>A live stream</b> ({@code stream.url} configured, e.g. {@code /_camerastream/printer5} for the H2D).
     * Embedded once as an iframe and then never touched again. This is why the tile is updated rather than
     * rebuilt: re-inserting an iframe makes the browser tear the WebRTC session down and renegotiate, and under
     * the old whole-page rebuild that happened every time any printer's percentage ticked.</li>
     * <li><b>The port-6000 JPEG thumbnail</b>, which only the P-series push. Refreshed in place by
     * {@link #updateCameras}, so it is current by definition and needs no age badge.</li>
     * <li><b>The last still anyone captured</b>, with an age badge - see {@link #lastFrame}.</li>
     * </ol>
     * The live stream is preferred over the thumbnail where a printer somehow offers both: a stream is the more
     * current of the two, and this page has one job.
     */
    private void updateCam(final Tile t, final PrinterState s) {
        final Optional<BambuPrinters.PrinterDetail> detail = printers.getPrinterDetail(s.name());
        if (detail.isEmpty()) {
            return;
        }
        final Optional<String> stream = detail.get().printer().getIFrame();
        if (stream.isPresent()) {
            final String kind = "stream:" + stream.get();
            if (!kind.equals(t.camKind)) {
                t.camKind = kind;
                t.cam.removeAll();
                liveCams.remove(s.name());
                final IFrame frame = new IFrame(stream.get());
                frame.addClassName("wall-cam-stream");
                frame.getElement().setAttribute("scrolling", "no");
                frame.getElement().setAttribute("allow", "autoplay");
                // Nothing on this page is clickable, and a player's own controls sitting under the pointer on a
                // wall display is exactly the sort of state that gets left paused by someone leaning on the desk.
                frame.getElement().setAttribute("tabindex", "-1");
                t.cam.add(frame);
            }
            return;
        }

        final Optional<BambuPrinter.Thumbnail> thumb = detail.get().printer().getThumbnail();
        if (thumb.isPresent()) {
            if (!"thumb".equals(t.camKind)) {
                t.camKind = "thumb";
                t.cam.removeAll();
                final Image img = new Image(thumb.get().thumbnail(), "");
                t.cam.add(img);
                liveCams.put(s.name(), img);
            }
            return;
        }

        liveCams.remove(s.name());
        final Optional<Frame> frame = lastFrame(s.name());
        if (frame.isEmpty()) {
            if (!"none".equals(t.camKind)) {
                t.camKind = "none";
                t.cam.removeAll();
                final Div none = new Div(new Span("no camera"));
                none.addClassName("wall-cam-none");
                t.cam.add(none);
            }
            return;
        }
        final Frame f = frame.get();
        // Identity of the frame, so a genuinely new capture swaps the picture - and only then. Registering a new
        // StreamResource on every tick would churn the session resource registry for an image that hasn't moved.
        final String kind = "still:" + f.at().map(Instant::toEpochMilli).orElse((long) f.bytes().length);
        if (kind.equals(t.camKind)) {
            return;
        }
        t.camKind = kind;
        t.cam.removeAll();

        final byte[] bytes = f.bytes();
        t.cam.add(new Image(new StreamResource("wall-%s.jpg".formatted(s.name()),
                () -> new ByteArrayInputStream(bytes)), ""));

        // The badge is not decoration. Everything else on this screen is current by construction, so a picture
        // that silently isn't would be the one element quietly lying to you. Its text is filled in by the browser
        // from an epoch timestamp, the same arrangement the clock uses.
        final Span badge = new Span();
        badge.addClassName("wall-cam-age");
        f.at().ifPresent(at -> badge.getElement().setAttribute("data-at", String.valueOf(at.toEpochMilli())));
        badge.setText(f.at().isPresent() ? "" : "cached");
        badge.getElement().setAttribute("title", f.at()
                .map(at -> "%s — taken %s".formatted(f.source(), ago(at)))
                .orElse(f.source() + " — no capture time recorded"));
        t.cam.add(badge);
    }

    /** Everything the two bottom panels display, flattened. */
    private String bottomKey(final List<PrinterState> states) {
        final StringBuilder k = new StringBuilder();
        openOrders().forEach(o -> k.append(o.orderId()).append(o.printed()).append(o.expected())
                .append(o.abandoned()).append(','));
        k.append('|').append(dispatchQueue.size()).append('|');
        attentionItems(states).forEach(it -> k.append(it[0]).append(it[1]).append(it[2]).append(','));
        k.append('|');
        lowestSpool().ifPresent(sp -> k.append(sp.id()).append((long) sp.remainingGrams()));
        return k.toString();
    }

    private Div buildBottom(final List<PrinterState> states) {
        final Div row = new Div();
        row.addClassName("wall-bottom");
        row.add(buildPipeline(), buildAttention(states));
        return row;
    }

    private Div buildPipeline() {
        final List<OrderSummary> orders = openOrders();
        final long notQueued = orders.stream().filter(o -> o.expected() == 0).count();
        final long printingNow = orders.stream().filter(o -> o.expected() > 0 && !o.readyToShip()).count();
        final long ready = orders.stream().filter(OrderSummary::readyToShip).count();
        final int pool = dispatchQueue.size();

        final Div panel = panel("Order pipeline");
        final Div stages = new Div();
        stages.addClassName("wall-stages");
        stages.add(stage(notQueued, "not queued", false));
        stages.add(stage(pool, "in the pool", false));
        stages.add(stage(printingNow, "printing", false));
        stages.add(stage(ready, "ready to ship", true));
        panel.add(stages);

        // The named lines below the counts: ready-to-ship first, because those are the ones that turn into money
        // the moment you walk over and print a label.
        final List<OrderSummary> lines = new ArrayList<>(orders.stream()
                .filter(o -> o.readyToShip() || o.needsAttention())
                .toList());
        lines.sort(Comparator.comparing(OrderSummary::needsAttention).reversed());
        if (lines.isEmpty()) {
            panel.add(quiet("Nothing waiting to ship"));
        } else {
            panel.add(sep());
            lines.stream().limit(4).forEach(o -> {
                final Span l = new Span("%s · %s".formatted(marketLabel(o.market()), o.title()));
                l.addClassName("wall-row-l");
                final Span r = new Span(o.needsAttention()
                        ? "%d part%s failed".formatted(o.abandoned(), o.abandoned() == 1 ? "" : "s")
                        : "%d/%d printed".formatted(o.printed(), o.expected()));
                r.addClassName("wall-row-r");
                final Div line = new Div(l, r);
                line.addClassName("wall-row");
                line.addClassName(o.needsAttention() ? "tone-bad" : "tone-ok");
                panel.add(line);
            });
        }
        return panel;
    }

    /**
     * Everything that needs a human, worst first, as {@code [tone, text, when]}.
     * <p>
     * Extracted so the change key and the rendered panel are computed from one list. Keeping them as two parallel
     * expressions is how a panel ends up not repainting when the thing it shows has changed.
     */
    private List<String[]> attentionItems(final List<PrinterState> states) {
        final List<String[]> items = new ArrayList<>();
        states.stream().filter(p -> p.fault().isPresent()).forEach(p ->
                items.add(new String[]{"bad", p.name() + " — " + truncate(p.fault().orElseThrow(), 44), ""}));
        states.stream().filter(PrinterState::failedCheck).forEach(p ->
                items.add(new String[]{"bad", p.name() + " failed its " + shortCheck(p.ai().orElseThrow().checkType())
                        + " check", ago(p.ai().orElseThrow().checkedAt())}));
        states.stream().filter(PrinterState::bedUnprotected).forEach(p ->
                items.add(new String[]{"warn", p.name() + " bed unverified", ""}));
        dispatchQueue.getBlockedStatus()
                .filter(b -> dispatchQueue.getBlockedKind() == DispatchQueueService.BlockKind.ATTENTION)
                .ifPresent(b -> items.add(new String[]{"warn", "Dispatch held — " + truncate(b, 44), ""}));
        states.forEach(p -> maintenance.getTaskStatus(p.name()).stream()
                .filter(MaintenanceService.TaskStatus::overdue)
                .forEach(t -> items.add(new String[]{"warn",
                    "%s %s overdue".formatted(p.name(), t.task().name().toLowerCase()), ""})));
        return items;
    }

    /**
     * Everything that needs a human, in one list, worst first.
     * <p>
     * Capped at five lines. A panel that scrolls on a wall display shows you its first five items and hides the
     * rest forever, so it says how many it isn't showing rather than pretending five is all there is.
     */
    private Div buildAttention(final List<PrinterState> states) {
        final List<String[]> items = attentionItems(states);
        final Div panel = panel("Attention");
        if (items.isEmpty()) {
            final Div ok = new Div(new Span("All clear — nothing needs you"));
            ok.addClassName("wall-clear");
            panel.add(ok);
        } else {
            items.stream().limit(5).forEach(it -> {
                final Span dot = new Span();
                dot.addClassName("wall-dot");
                dot.addClassName(it[0]);
                final Span text = new Span(it[1]);
                text.addClassName("wall-att-l");
                final Span when = new Span(it[2]);
                when.addClassName("wall-att-when");
                final Div line = new Div(dot, text, when);
                line.addClassName("wall-att");
                panel.add(line);
            });
            if (items.size() > 5) {
                panel.add(quiet("and %d more".formatted(items.size() - 5)));
            }
        }

        // Filament runway. The one number that turns a running farm into a stopped one overnight, and the only
        // thing here you can act on BEFORE it becomes a problem.
        lowestSpool().ifPresent(sp -> {
            panel.add(sep());
            final Span l = new Span("Filament runway");
            l.addClassName("wall-row-l");
            final Span r = new Span("%s — %.0f g left".formatted(sp.name(), sp.remainingGrams()));
            r.addClassName("wall-row-r");
            final Div line = new Div(l, r);
            line.addClassName("wall-row");
            if (sp.remainingGrams() <= sp.lowThresholdGrams()) {
                line.addClassName("tone-warn");
            }
            panel.add(line);
        });
        return panel;
    }

    /** The spool closest to running out, ignoring ones with no threshold set (they're not being tracked). */
    private Optional<SpoolService.Spool> lowestSpool() {
        return spools.getSpools().stream()
                .filter(s -> s.totalGrams() > 0)
                .min(Comparator.comparingDouble(SpoolService.Spool::remainingGrams));
    }

    // -------------------------------------------------------------------------
    // orders
    // -------------------------------------------------------------------------
    private record OrderSummary(String market, String orderId, String title, int printed, int expected,
            int abandoned) {

        boolean readyToShip() {
            return expected > 0 && printed >= expected && abandoned == 0;
        }

        boolean needsAttention() {
            return abandoned > 0;
        }
    }

    /**
     * Orders the marketplace still lists as open. Restricted to those on purpose: progress entries outlive the
     * order, so anything shipped by hand sits at 0/N forever and would inflate every count on this screen.
     */
    private List<OrderSummary> openOrders() {
        final List<OrderSummary> out = new ArrayList<>();
        for (final String market : List.of("etsy", "ebay")) {
            tracking.progress(market).stream()
                    .filter(p -> isOpen(market, p.orderId()))
                    .forEach(p -> out.add(new OrderSummary(market, p.orderId(),
                            p.title() != null && !p.title().isBlank() ? truncate(p.title(), 44) : p.orderId(),
                            p.printed(), p.expected(), p.abandoned())));
        }
        return out;
    }

    private boolean isOpen(final String market, final String orderId) {
        return "etsy".equals(market)
                ? etsyPolling.getReceipts().stream().anyMatch(r -> String.valueOf(r.receiptId()).equals(orderId))
                : ebayPolling.getOrders().stream().anyMatch(o -> orderId.equals(o.orderId()));
    }

    private static String marketLabel(final String market) {
        return "etsy".equals(market) ? "Etsy" : "eBay";
    }

    // -------------------------------------------------------------------------
    // small helpers
    // -------------------------------------------------------------------------
    private static Div panel(final String heading) {
        final Div panel = new Div();
        panel.addClassName("wall-panel");
        final Div h = new Div(new Span(heading));
        h.addClassName("wall-panel-h");
        panel.add(h);
        return panel;
    }

    private static Div stage(final long count, final String label, final boolean ship) {
        final Div box = new Div();
        box.addClassName("wall-stage");
        if (ship && count > 0) {
            box.addClassName("ship");
        }
        final Div n = new Div(new Span(String.valueOf(count)));
        n.addClassName("wall-stage-n");
        final Div l = new Div(new Span(label));
        l.addClassName("wall-stage-l");
        box.add(n, l);
        return box;
    }

    private static Div sep() {
        final Div d = new Div();
        d.addClassName("wall-sep");
        return d;
    }

    private static Div quiet(final String text) {
        final Div d = new Div(new Span(text));
        d.addClassName("wall-quiet");
        return d;
    }

    private static String eta(final int minutes) {
        if (minutes <= 0) {
            return "finishing";
        }
        return minutes < 60 ? "%dm".formatted(minutes) : "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    private static String shortCheck(final String checkType) {
        return switch (checkType == null ? "" : checkType) {
            case "bed-clear" ->
                "bed";
            case "first-layer" ->
                "first layer";
            case "failure" ->
                "print";
            default ->
                checkType;
        };
    }

    private static String ago(final Instant t) {
        final long secs = Duration.between(t, Instant.now()).getSeconds();
        if (secs < 60) {
            return "just now";
        }
        if (secs < 3600) {
            return "%d min ago".formatted(secs / 60);
        }
        if (secs < 86400) {
            return "%dh %dm ago".formatted(secs / 3600, (secs % 3600) / 60);
        }
        return "%dd ago".formatted(secs / 86400);
    }

    private static String truncate(final String s, final int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
