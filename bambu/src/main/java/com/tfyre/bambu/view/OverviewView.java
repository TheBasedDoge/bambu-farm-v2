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
 * expandable rows, buttons, and rewards a close read. This one has exactly one interaction: click a camera to
 * enlarge it (the others shrink into a column beside it), click it again to go back. Nothing is saved, so a
 * reload is always the plain wall.
 * <p>
 * <b>Second layout, 2026-10-05.</b> The first one stacked a banner, four headline tiles, the printer row and two
 * panels, and the cameras got what was left - 175 px each on a 2000 px screen, which is why the page went
 * unused. Now the cameras ARE the page: one status line on top, the camera grid filling everything else, each
 * printer's name, job and progress drawn over its own picture, and a "needs you" rail down the side (along the
 * bottom on a portrait screen, absent when there is nothing to say).
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
    com.tfyre.bambu.printer.BedClearService bedClear;
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
    /** The status line. Persistent, because the clock inside it is ticked by the browser and must not be rebuilt. */
    private final Div bar = new Div();
    private final Div statsSlot = new Div();
    private final Div stageArea = new Div();
    private final Div printerGrid = new Div();
    private final Div railSlot = new Div();
    private String statsKey = "";
    private String railKey = "";

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
        bar.setClassName("wall-bar");
        bar.removeAll();
        statsSlot.setClassName("wall-stats-slot");
        // Rendered empty and filled by the client, so a stalled server shows a frozen clock rather than a
        // plausible-looking wrong time.
        final Span clock = new Span();
        clock.addClassName("wall-clock");
        bar.add(buildBrand(), statsSlot, clock);
        stageArea.setClassName("wall-stage-area");
        printerGrid.setClassName("wall-printers");
        railSlot.setClassName("wall-rail");
        stageArea.add(printerGrid, railSlot);
        board.add(bar, stageArea);
        add(board);

        statsKey = "";
        railKey = "";
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

                // Camera grid. Every tile is a 16:9 box positioned by this script - absolutely, not by flow,
                // for one reason: enlarging a tile must not MOVE it in the DOM. The H2D tile holds a live
                // WebRTC iframe, and an iframe that is re-parented is an iframe the browser reloads. Moving a
                // box by left/top/width costs nothing and animates for free.
                // Plain wall: try every column count and keep the one that gives the largest tile that still
                // fits the height; rows are centred, so an odd last row sits in the middle.
                // One enlarged: that tile takes the left, as big as fits, and the rest stack in a column
                // exactly as tall as it is.
                const grid = root.querySelector('.wall-printers');
                const CAM = 16 / 9;
                const put = (t, x, y, w) => {
                    t.style.left = Math.round(x) + 'px';
                    t.style.top = Math.round(y) + 'px';
                    t.style.width = Math.floor(w) + 'px';
                };
                const layout = () => {
                    if (!grid) { return; }
                    const tiles = Array.from(grid.querySelectorAll(':scope > .wall-p'));
                    const n = tiles.length;
                    if (!n || window.innerWidth <= 900) { return; }  // narrow screens are laid out by CSS
                    const W = grid.clientWidth, H = grid.clientHeight;
                    if (W <= 0 || H <= 0) { return; }
                    const g = Math.max(8, Math.round(W * 0.008));
                    const big = root.__wallFocus ? tiles.find(t => t.dataset.name === root.__wallFocus) : null;
                    tiles.forEach(t => t.classList.toggle('focus', t === big));
                    if (big && n > 1) {
                        const m = n - 1;
                        let bw = (m * (W - g) + (m - 1) * g * CAM) / (m + 1);
                        if (bw / CAM > H) { bw = H * CAM; }
                        const bh = bw / CAM;
                        const sh = (bh - (m - 1) * g) / m, sw = sh * CAM;
                        const x0 = (W - (bw + g + sw)) / 2, y0 = (H - bh) / 2;
                        put(big, x0, y0, bw);
                        tiles.filter(t => t !== big).forEach((t, i) => put(t, x0 + bw + g, y0 + i * (sh + g), sw));
                        return;
                    }
                    let best = { c: 1, w: 0 };
                    for (let c = 1; c <= n; c++) {
                        const r = Math.ceil(n / c);
                        const w = Math.min((W - g * (c - 1)) / c, ((H - g * (r - 1)) / r) * CAM);
                        // On a near-tie take the wider grid: 3+2 reads better than 2+2+1 and the tiles are
                        // the same size to within a couple of percent.
                        if (w >= best.w * 0.98) { best = { c: c, w: w }; }
                    }
                    const c = best.c, w = best.w, h = w / CAM, r = Math.ceil(n / c);
                    const y0 = (H - (r * h + (r - 1) * g)) / 2;
                    tiles.forEach((t, i) => {
                        const row = Math.floor(i / c);
                        const inRow = row === r - 1 ? n - c * (r - 1) : c;
                        const x0 = (W - (inRow * w + (inRow - 1) * g)) / 2;
                        put(t, x0 + (i % c) * (w + g), y0 + row * (h + g), w);
                    });
                };
                root.__wallLayout = layout;
                if (grid) {
                    new ResizeObserver(layout).observe(grid);
                    // Click a camera to enlarge it, click it again for the plain wall. Kept in the browser:
                    // it is a property of this screen, and a server refresh must not undo it.
                    grid.addEventListener('click', (e) => {
                        // The "Bed cleared" button sits on the tile; pressing it must not also enlarge it.
                        if (e.target.closest('.wall-clear-btn')) { return; }
                        const t = e.target.closest('.wall-p');
                        if (!t) { return; }
                        root.__wallFocus = root.__wallFocus === t.dataset.name ? null : t.dataset.name;
                        layout();
                    });
                }
                window.addEventListener('resize', layout);
                layout();

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
        final List<OrderSummary> orders = openOrders();
        final List<Item> items = attentionItems(states, orders);

        statsKey = fill(statsSlot, statsKey, () -> buildStats(states, orders, alert), statsKey(states, orders, alert));
        syncPrinters(states);
        final String newRailKey = railKey(items, orders);
        if (!railKey.equals(newRailKey)) {
            railKey = newRailKey;
            railSlot.removeAll();
            railSlot.add(buildAttention(items), buildOrders(orders));
        }
        // With nothing to say the rail goes away and the cameras take its width. A class on the parent, not a
        // remove/add of the grid: the camera grid must never be re-parented (live stream inside).
        stageArea.setClassName("wall-stage-area" + (items.isEmpty() && orders.isEmpty() ? " no-rail" : ""));

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
            boolean bedUnprotected, int queued, boolean bedCleared) {

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
                queueService.size(name), bedClear.isCleared(name));
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

    // -------------------------------------------------------------------------
    // the status line
    // -------------------------------------------------------------------------
    private Div buildBrand() {
        // The app mark IS the way back to the menu: clicking the logo is the ordinary web idiom, it needs no
        // space of its own, and it cannot collide with anything because it is laid out rather than positioned.
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
        return brand;
    }

    /** Printers that could take work now: idle AND not reporting a fault. */
    private static long readyCount(final List<PrinterState> states) {
        return states.stream().filter(s -> s.fault().isEmpty() && s.state().isReady()).count();
    }

    /** "P1S-2 in 1h 34m", or how many are free right now. The one line that replaced the "beds free next" list. */
    private String[] nextFree(final List<PrinterState> states) {
        final long ready = readyCount(states);
        if (ready > 0) {
            return new String[]{String.valueOf(ready), "free now", "ok"};
        }
        final Optional<PrinterState> soonest = states.stream()
                .filter(PrinterState::printing)
                .min(Comparator.comparingInt(PrinterState::remainingMinutes));
        if (soonest.isPresent()) {
            return new String[]{"%s in %s".formatted(soonest.get().name(), eta(soonest.get().remainingMinutes())),
                "next free", null};
        }
        // Nothing printing and nothing ready: every machine is paused, faulted or offline.
        return new String[]{"0", "ready", "warn"};
    }

    /** Everything the status line displays, so a tick that changes none of it doesn't touch the DOM. */
    private String statsKey(final List<PrinterState> states, final List<OrderSummary> orders, final Alert alert) {
        final PrintHistoryService.TodayStats today = historyService.getTodayStats();
        return keyOf(states.stream().filter(PrinterState::printing).count(), states.size(), onlineCount(),
                String.join("/", nextFree(states)[0], nextFree(states)[1]),
                orders.size(), orders.stream().filter(OrderSummary::readyToShip).count(),
                dispatchQueue.size(), dispatchQueue.parkedCount(), today.finished(), today.failed(),
                alert == null ? "-" : alert.critical() + alert.headline() + alert.aside());
    }

    private Div buildStats(final List<PrinterState> states, final List<OrderSummary> orders, final Alert alert) {
        final long printing = states.stream().filter(PrinterState::printing).count();
        final long online = onlineCount();
        final long readyToShip = orders.stream().filter(OrderSummary::readyToShip).count();
        final int pool = dispatchQueue.size();
        final int parked = dispatchQueue.parkedCount();
        final PrintHistoryService.TodayStats today = historyService.getTodayStats();

        final Div row = new Div();
        row.addClassName("wall-stats");
        row.add(stat("%d/%d".formatted(printing, states.size()), "printing", null));
        final String[] free = nextFree(states);
        // "next free" reads label-first ("next free P1S-2 in 1h 34m"); the counts read number-first.
        row.add("next free".equals(free[1]) ? statLabelFirst(free[1], free[0]) : stat(free[0], free[1], free[2]));
        if (online < states.size()) {
            row.add(stat("%d of %d".formatted(online, states.size()), "online", "warn"));
        }
        row.add(statSep());
        row.add(stat(String.valueOf(orders.size()), "open orders", null));
        row.add(stat(String.valueOf(readyToShip), "ready to ship", readyToShip > 0 ? "ok" : null));
        row.add(stat(String.valueOf(pool), "waiting for a bed", null));
        if (parked > 0) {
            row.add(stat(String.valueOf(parked), "parked", "bad"));
        }
        row.add(statSep());
        row.add(stat(String.valueOf(today.finished()), "done today", null));
        if (today.failed() > 0) {
            row.add(stat(String.valueOf(today.failed()), "failed", "bad"));
        }
        if (alert != null) {
            // What used to be a full-width banner. One chip: red and breathing when something is failing right
            // now, amber for "worth knowing". The detail is on the tile and in the rail, where there is room.
            final String text = alert.aside().isBlank() ? alert.headline() : alert.headline() + " · " + alert.aside();
            final Span chip = new Span(truncate(text, 70));
            chip.addClassName("wall-chip");
            chip.addClassName(alert.critical() ? "crit" : "warn");
            chip.getElement().setAttribute("title", alert.headline() + " - " + alert.detail());
            row.add(chip);
        }
        return row;
    }

    private static Div stat(final String value, final String label, final String tone) {
        final Span v = new Span(value);
        v.addClassName("wall-stat-v");
        final Div box = new Div(v, new Span(label));
        box.addClassName("wall-stat");
        if (tone != null) {
            box.addClassName("tone-" + tone);
        }
        return box;
    }

    private static Div statLabelFirst(final String label, final String value) {
        final Span v = new Span(value);
        v.addClassName("wall-stat-v");
        v.addClassName("small");
        final Div box = new Div(new Span(label), v);
        box.addClassName("wall-stat");
        return box;
    }

    private static Div statSep() {
        final Div d = new Div();
        d.addClassName("wall-stat-sep");
        return d;
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
        private final Div pill = new Div();
        private final Span pillText = new Span();
        private final Span jobText = new Span();
        private final Span pct = new Span();
        private final Span left = new Span();
        private final Div fill = new Div();
        /** Shown only while a finished or failed part is (as far as the printer knows) still on the plate. */
        private final com.vaadin.flow.component.button.Button clearBtn
                = new com.vaadin.flow.component.button.Button("Bed cleared");
        /** What is currently inside {@link #cam}, so it is only replaced when it genuinely has to be. */
        private String camKind = "";

        /**
         * The camera fills the tile and everything else is drawn over it: a state pill top-left, name and job
         * bottom-left, percentage and time bottom-right, the progress bar along the bottom edge. A caption
         * strip under the picture was costing a fifth of every tile's height.
         */
        Tile(final String name) {
            root.addClassName("wall-p");
            // The layout script finds the enlarged tile by this.
            root.getElement().setAttribute("data-name", name);
            cam.addClassName("wall-cam");

            final Div scrim = new Div();
            scrim.addClassName("wall-scrim");

            final Span dot = new Span();
            dot.addClassName("wall-pill-dot");
            pill.addClassName("wall-pill");
            pill.add(dot, pillText);

            final Div nameBox = new Div(new Span(name));
            nameBox.addClassName("wall-p-name");
            final Div jobBox = new Div(jobText);
            jobBox.addClassName("wall-p-job");
            final Div l = new Div(nameBox, jobBox);
            l.addClassName("wall-p-l");

            pct.addClassName("wall-p-pct");
            left.addClassName("wall-p-left");
            clearBtn.addClassName("wall-clear-btn");
            clearBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY,
                    com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
            // The overlay it sits in ignores the pointer (so a click anywhere on the picture enlarges the
            // tile); the button has to opt back in. Inline so this needs no theme rebuild.
            clearBtn.getStyle().set("pointer-events", "auto").set("cursor", "pointer");
            clearBtn.setVisible(false);
            final Div r = new Div(pct, left, clearBtn);
            r.addClassName("wall-p-r");

            final Div info = new Div(l, r);
            info.addClassName("wall-p-info");

            fill.addClassName("wall-fill");
            final Div track = new Div(fill);
            track.addClassName("wall-track");

            root.add(cam, scrim, pill, info, track);
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
            names.forEach(n -> {
                final Tile tile = new Tile(n);
                tile.clearBtn.addClickListener(e -> onBedCleared(n));
                tiles.put(n, tile);
            });
            tiles.values().forEach(t -> printerGrid.add(t.root));
            // The column count is worked out on the client from the printer count and the screen (see
            // installDisplayScripts) - a fixed rule would either crop the cameras or leave a hole the day a
            // printer is added. Re-run it now that the set of tiles has changed.
            getElement().executeJs("if (this.__wallLayout) { this.__wallLayout(); }");
        }
        states.forEach(s -> updateTile(tiles.get(s.name()), s));
    }

    private void updateTile(final Tile t, final PrinterState s) {
        final BambuConst.GCodeState state = s.state();
        final boolean paused = state == BambuConst.GCodeState.PAUSE;
        final boolean inJob = s.printing() || paused;
        // A FINISH or FAILED printer whose bed someone has marked clear is just an idle printer: the state only
        // lingers because nothing has been started on it since.
        final boolean failedPrint = state == BambuConst.GCodeState.FAILED && !s.bedCleared();
        final boolean bad = s.failedCheck() || s.fault().isPresent() || failedPrint;
        final boolean done = !bad && state == BambuConst.GCodeState.FINISH && !s.bedCleared();
        t.clearBtn.setVisible((failedPrint || done) && s.fault().isEmpty());
        final boolean off = !bad && !inJob && !state.isReady();

        // setClassName replaces the whole attribute - the tone has to be cleared as well as set, or a printer
        // that recovers keeps its red outline until the page is reloaded. An outline means "walk over there":
        // red for a fault or a failed print, green for a finished part waiting to come off the bed.
        t.root.setClassName("wall-p" + (bad ? " bad" : done ? " done" : paused ? " warn" : off ? " off" : ""));

        final String pill;
        if (s.fault().isPresent()) {
            pill = "Fault";
        } else if (s.failedCheck()) {
            pill = "Failed %s check".formatted(shortCheck(s.ai().orElseThrow().checkType()));
        } else if (failedPrint) {
            pill = "Print failed";
        } else if (done) {
            pill = "Finished · clear bed";
        } else if (paused) {
            pill = "Paused";
        } else if (s.printing()) {
            pill = state == BambuConst.GCodeState.RUNNING ? "Printing" : state.getDescription();
        } else if (state.isReady()) {
            pill = s.queued() > 0 ? "Idle · %d queued".formatted(s.queued())
                    : s.bedCleared() ? "Idle · bed cleared" : "Idle";
        } else {
            pill = state.getDescription();
        }
        t.pillText.setText(pill);

        final String jobText;
        if (s.fault().isPresent()) {
            jobText = truncate(s.fault().orElseThrow(), 90);
        } else if (s.failedCheck()) {
            jobText = truncate(s.ai().orElseThrow().description(), 90);
        } else if (inJob || failedPrint || done) {
            jobText = s.job().orElse(inJob ? "(unknown file)" : "");
        } else if (s.bedCleared()) {
            jobText = s.queued() == 0 ? "queue empty" : "";
        } else {
            jobText = state.isReady() && s.queued() == 0 ? "queue empty" : "";
        }
        t.jobText.setText(jobText);

        t.pct.setText(inJob ? s.percent() + "%" : "");
        final String left;
        if (s.fault().isPresent() || s.failedCheck()) {
            left = "needs you";
        } else if (s.printing()) {
            left = s.remainingMinutes() <= 0 ? "finishing"
                    : "%s left · done %s".formatted(eta(s.remainingMinutes()), clockIn(s.remainingMinutes()));
        } else {
            left = "";
        }
        t.left.setText(left);

        t.fill.setClassName("wall-fill" + (bad ? " bad" : ""));
        t.fill.getStyle().set("width", (inJob || failedPrint ? s.percent() : done ? 100 : 0) + "%");

        updateCam(t, s);
    }

    /**
     * "Bed cleared" on a tile: take the person's word for it, drop the outline, and let the next job go to this
     * printer now rather than at the next minute tick. See {@link com.tfyre.bambu.printer.BedClearService}.
     */
    private void onBedCleared(final String name) {
        if (bedClear.markCleared(name, "Overview")) {
            dispatchQueue.passNow();
        }
        refresh();
    }

    /** Wall-clock time {@code minutes} from now, as "9:41 am" - when to walk over, not how long to wait. */
    private static String clockIn(final int minutes) {
        return java.time.LocalTime.now().plusMinutes(minutes)
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH))
                .toLowerCase(java.util.Locale.ENGLISH);
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

    // -------------------------------------------------------------------------
    // the rail: what needs you, and the orders
    // -------------------------------------------------------------------------
    /** One line in "Needs you": a tone ({@code bad}/{@code warn}/{@code ok}), what to do, and why. */
    private record Item(String tone, String title, String sub) {

    }

    /** Everything the rail displays, flattened. */
    private String railKey(final List<Item> items, final List<OrderSummary> orders) {
        final StringBuilder k = new StringBuilder();
        items.forEach(it -> k.append(it.tone()).append(it.title()).append(it.sub()).append(','));
        k.append('|');
        orders.forEach(o -> k.append(o.orderId()).append(o.printed()).append('/').append(o.expected())
                .append(o.abandoned()).append(o.title()).append(','));
        k.append('|').append(dispatchQueue.size()).append('|');
        lowestSpool().ifPresent(sp -> k.append(sp.id()).append((long) sp.remainingGrams()));
        return k.toString();
    }

    /**
     * Everything that needs a human, worst first, phrased as the thing to DO.
     * <p>
     * The first version listed conditions ("P1P bed unverified", four times over). A condition makes you work
     * out what it wants from you; "Clear P1S-3" does not. Things that are the same action on several printers
     * are one line, so four beds without a reference cannot push a failed print off the bottom.
     */
    private List<Item> attentionItems(final List<PrinterState> states, final List<OrderSummary> orders) {
        final List<Item> items = new ArrayList<>();
        final int pool = dispatchQueue.size();
        final String waiting = pool > 0
                ? " %d order job%s waiting for a bed.".formatted(pool, pool == 1 ? " is" : "s are") : "";

        states.stream().filter(p -> p.fault().isPresent()).forEach(p ->
                items.add(new Item("bad", "Check " + p.name(), truncate(p.fault().orElseThrow(), 110))));
        states.stream().filter(p -> p.fault().isEmpty() && p.failedCheck()).forEach(p ->
                items.add(new Item("bad", "Check " + p.name(), "Failed its %s check %s: %s".formatted(
                        shortCheck(p.ai().orElseThrow().checkType()), ago(p.ai().orElseThrow().checkedAt()),
                        truncate(p.ai().orElseThrow().description(), 80)))));
        states.stream()
                .filter(p -> p.fault().isEmpty() && !p.failedCheck() && !p.bedCleared()
                        && p.state() == BambuConst.GCodeState.FAILED)
                .forEach(p -> items.add(new Item("bad", "Clear " + p.name(),
                        "Print failed%s.%s".formatted(p.job().map(j -> " (" + truncate(j, 40) + ")").orElse(""), waiting))));
        orders.stream().filter(OrderSummary::needsAttention).forEach(o ->
                items.add(new Item("bad", "Re-queue %s order".formatted(marketLabel(o.market())),
                        "%s is short %d part%s that failed.".formatted(o.title(), o.abandoned(), o.abandoned() == 1 ? "" : "s"))));
        states.stream()
                .filter(p -> p.fault().isEmpty() && !p.failedCheck() && !p.bedCleared()
                        && p.state() == BambuConst.GCodeState.FINISH)
                .forEach(p -> items.add(new Item("ok", "Clear " + p.name(),
                        "Finished%s.%s".formatted(p.job().map(j -> " " + truncate(j, 40)).orElse(""), waiting))));

        final long ship = orders.stream().filter(OrderSummary::readyToShip).count();
        if (ship > 0) {
            items.add(new Item("ok", "Ship %d order%s".formatted(ship, ship == 1 ? "" : "s"), "All parts printed."));
        }
        final List<OrderSummary> notQueued = orders.stream().filter(o -> o.expected() == 0).toList();
        if (!notQueued.isEmpty()) {
            items.add(new Item("warn", "Queue %d order%s".formatted(notQueued.size(), notQueued.size() == 1 ? "" : "s"),
                    "Nothing is queued for %s%s.".formatted(notQueued.get(0).title(),
                            notQueued.size() > 1 ? " and %d more".formatted(notQueued.size() - 1) : "")));
        }
        dispatchQueue.getBlockedStatus()
                .filter(b -> dispatchQueue.getBlockedKind() == DispatchQueueService.BlockKind.ATTENTION)
                .ifPresent(b -> items.add(new Item("warn", "Dispatch is held", truncate(b, 120))));
        // One line per TASK, not per printer: the same nozzle clean overdue on four machines is one errand, and
        // as four lines it pushed the orders off the bottom of the rail.
        final Map<String, List<String>> overdue = new LinkedHashMap<>();
        states.forEach(p -> maintenance.getTaskStatus(p.name()).stream()
                .filter(MaintenanceService.TaskStatus::overdue)
                .forEach(ts -> overdue.computeIfAbsent(ts.task().name(), k -> new ArrayList<>()).add(p.name())));
        overdue.forEach((task, who) -> items.add(new Item("warn", "%s overdue".formatted(task),
                String.join(", ", who))));
        final List<String> unverified = states.stream().filter(PrinterState::bedUnprotected)
                .map(PrinterState::name).toList();
        if (!unverified.isEmpty()) {
            items.add(new Item("warn", "%d bed%s without a reference".formatted(unverified.size(),
                    unverified.size() == 1 ? "" : "s"),
                    "%s - auto-start is gated on the AI check alone.".formatted(String.join(", ", unverified))));
        }
        return items;
    }

    /**
     * Capped. A panel that scrolls on a wall display shows you its first lines and hides the rest forever, so
     * it says how many it isn't showing rather than pretending that is all there is.
     */
    private Div buildAttention(final List<Item> items) {
        final Div panel = panel("Needs you");
        if (items.isEmpty()) {
            final Div ok = new Div(new Span("All clear - nothing needs you"));
            ok.addClassName("wall-clear");
            panel.add(ok);
        } else {
            items.stream().limit(7).forEach(it -> {
                final Span dot = new Span();
                dot.addClassName("wall-dot");
                dot.addClassName(it.tone());
                final Span title = new Span(it.title());
                title.addClassName("wall-att-l");
                final Span sub = new Span(it.sub());
                sub.addClassName("wall-att-sub");
                final Div line = new Div(dot, new Div(title, sub));
                line.addClassName("wall-att");
                panel.add(line);
            });
            if (items.size() > 7) {
                panel.add(quiet("and %d more".formatted(items.size() - 7)));
            }
        }

        // Filament runway. The one number that turns a running farm into a stopped one overnight, and the only
        // thing here you can act on BEFORE it becomes a problem.
        lowestSpool().ifPresent(sp -> {
            panel.add(sep());
            final Span l = new Span("Filament runway");
            l.addClassName("wall-row-l");
            final Span r = new Span("%s - %.0f g left".formatted(sp.name(), sp.remainingGrams()));
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

    private Div buildOrders(final List<OrderSummary> orders) {
        final long notQueued = orders.stream().filter(o -> o.expected() == 0).count();
        final long printingNow = orders.stream().filter(o -> o.expected() > 0 && !o.readyToShip()).count();
        final long ready = orders.stream().filter(OrderSummary::readyToShip).count();

        final Div panel = panel("Orders");
        final Div stages = new Div();
        stages.addClassName("wall-stages");
        stages.add(stage(notQueued, "not queued", false));
        stages.add(stage(dispatchQueue.size(), "waiting", false));
        stages.add(stage(printingNow, "printing", false));
        stages.add(stage(ready, "to ship", true));
        panel.add(stages);

        if (orders.isEmpty()) {
            panel.add(quiet("No open orders"));
            return panel;
        }
        // Every open order, in the order you would deal with them: short a part, ready to ship (those turn
        // into money the moment a label is printed), in progress, then not queued.
        final List<OrderSummary> lines = new ArrayList<>(orders);
        lines.sort(Comparator.comparingInt(OverviewView::orderRank));
        panel.add(sep());
        lines.stream().limit(9).forEach(o -> {
            // A pencil in front of an order whose buyer left a note or personalization: read it before packing.
            final Span l = new Span("%s%s · %s".formatted(hasBuyerText(o.market(), o.orderId()) ? "✎ " : "",
                    marketLabel(o.market()), o.title()));
            l.addClassName("wall-row-l");
            final Span r = new Span(o.needsAttention()
                    ? "%d part%s failed".formatted(o.abandoned(), o.abandoned() == 1 ? "" : "s")
                    : o.expected() == 0 ? "not queued"
                    : "%d/%d printed".formatted(o.printed(), o.expected()));
            r.addClassName("wall-row-r");
            final Div line = new Div(l, r);
            line.addClassName("wall-row");
            if (o.needsAttention()) {
                line.addClassName("tone-bad");
            } else if (o.readyToShip()) {
                line.addClassName("tone-ok");
            } else if (o.expected() == 0) {
                line.addClassName("tone-warn");
            }
            panel.add(line);
        });
        if (lines.size() > 9) {
            panel.add(quiet("and %d more".formatted(lines.size() - 9)));
        }
        return panel;
    }

    private static int orderRank(final OrderSummary o) {
        return o.needsAttention() ? 0 : o.readyToShip() ? 1 : o.expected() > 0 ? 2 : 3;
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

    /** True when the buyer left a checkout note or personalization on this open order. */
    private boolean hasBuyerText(final String market, final String orderId) {
        return "etsy".equals(market)
                ? etsyPolling.getReceipts().stream()
                        .filter(r -> String.valueOf(r.receiptId()).equals(orderId))
                        .anyMatch(r -> (r.buyerNote() != null && !r.buyerNote().isBlank())
                                || r.transactions().stream().anyMatch(t -> t.personalization().isPresent()))
                : ebayPolling.getOrders().stream()
                        .filter(o -> orderId.equals(o.orderId()))
                        .anyMatch(o -> (o.buyerNote() != null && !o.buyerNote().isBlank())
                                || o.lineItems().stream().anyMatch(li -> li.personalization().isPresent()));
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
