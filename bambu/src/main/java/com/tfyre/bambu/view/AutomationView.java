package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.AutoQueueService;
import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.EbayOAuthService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOAuthService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.OrderTrackingService;
import com.tfyre.bambu.printer.PrintAiService;
import com.tfyre.bambu.printer.PrintHistoryService;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.PrintQueueService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.tfyre.bambu.YesNoCancelDialog;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Automation — one page for the whole order-to-print pipeline.
 * <p>
 * Three tabs: <b>Overview</b> (this class), <b>Mappings</b> and <b>AI Settings</b>.
 * <p>
 * The Overview is the whole pipeline on one screen: headline numbers, then <b>one row per printer</b> carrying
 * its job, progress, queue depth, AI verdict and auto-start state, with the queue entries, per-printer settings
 * and full AI reasoning folded into a click-to-expand detail. That row expansion is what the separate Print Queue
 * tab used to be - printers were previously split across two cards, so you read the same machines twice, and the
 * AI reasoning text crowded out everything worth scanning. The dispatch pool sits below the table.
 * <p>
 * {@link PrintQueueView} still serves the {@code /print-queue} route as a deep link.
 */
@Route(value = "automation", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Automation")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class AutomationView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    @Inject
    BambuConfig config;
    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    PrintAiService aiService;
    @Inject
    OllamaService ollama;
    @Inject
    AutoStartService autoStartService;
    @Inject
    AutoQueueService autoQueueService;
    @Inject
    com.tfyre.bambu.printer.DispatchQueueService dispatchQueue;
    @Inject
    OrderTrackingService tracking;
    @Inject
    PrintHistoryService historyService;
    @Inject
    EtsyOAuthService etsyOauth;
    @Inject
    EbayOAuthService ebayOauth;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    Instance<AiSettingsView> aiSettingsViewInstance;
    @Inject
    Instance<MappingsView> mappingsViewInstance;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    ScheduledExecutorService ses;

    private final Tab overviewTab = new Tab("Overview");
    private final Tab mappingsTab = new Tab("Mappings");
    private final Tab aiTab = new Tab("AI Settings");
    private final Tabs tabs = new Tabs(overviewTab, mappingsTab, aiTab);
    private final Div tabContent = new Div();
    private final Div overview = new Div();

    /** Embedded tab views, created lazily on first open and reused (they rebuild themselves on attach). */
    private AiSettingsView aiView;
    private MappingsView mappingsView;

    private String overviewKey = "";
    /**
     * Live countdowns. Held as a field and updated OUTSIDE the change-detection key: the seconds tick every
     * refresh, so including them in the key would rebuild the whole overview DOM once a second.
     */
    private final Div pendingTimers = new Div();
    private Optional<ScheduledFuture<?>> future = Optional.empty();
    /** The controls card currently in the DOM - the timer strip is parented into it after each rebuild. */
    private Div liveControls;
    /** Printer rows the user has expanded; kept across rebuilds and part of the change-detection key. */
    private final java.util.Set<String> expandedPrinters = new java.util.LinkedHashSet<>();
    /** Camera images built during the current pass; promoted to {@link #liveCams} only if that pass is committed. */
    private final Map<String, com.vaadin.flow.component.html.Image> pendingCams = new java.util.HashMap<>();
    /** Camera images actually on screen, refreshed in place every tick - see {@link #updateCameras}. */
    private final Map<String, com.vaadin.flow.component.html.Image> liveCams = new java.util.HashMap<>();
    /** Last stream-resource id pushed per printer, so an unchanged frame isn't re-registered. */
    private final Map<String, String> camIds = new java.util.HashMap<>();

    public AutomationView() {
        // Registered once here, NOT in onAttach - this view can re-attach and listeners would stack
        tabs.addSelectedChangeListener(e -> showTab(e.getSelectedTab()));
    }

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        addClassName("ai-settings-view");
        addClassName("ai-settings-wide");
        setPadding(true);
        setSpacing(true);

        add(new H3("Automation"));
        add(tabs);
        tabContent.setWidthFull();
        add(tabContent);

        overview.addClassName("automation-grid");
        overviewKey = "";
        showTab(tabs.getSelectedTab());

        final UI ui = attachEvent.getUI();
        future.ifPresent(f -> f.cancel(true));
        future = Optional.of(ses.scheduleAtFixedRate(
                () -> ui.access(() -> {
                    if (tabs.getSelectedTab() == overviewTab) {
                        refreshOverview();
                    }
                }),
                0, config.refreshInterval().getSeconds(), TimeUnit.SECONDS));
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        future.ifPresent(f -> f.cancel(true));
        future = Optional.empty();
    }

    private void showTab(final Tab selected) {
        tabContent.removeAll();
        if (selected == mappingsTab) {
            if (mappingsView == null) {
                mappingsView = mappingsViewInstance.get();
            }
            tabContent.add(mappingsView);
        } else if (selected == aiTab) {
            if (aiView == null) {
                aiView = aiSettingsViewInstance.get();
            }
            tabContent.add(aiView);
        } else {
            tabContent.add(overview);
            refreshOverview();
        }
    }

    // -------------------------------------------------------------------------
    // Overview - the pipeline dashboard
    // -------------------------------------------------------------------------

    /** Rebuilds the overview only when the displayed data actually changed (change-detection key). */
    private void refreshOverview() {
        final List<Component> sections = new ArrayList<>();
        final StringBuilder key = new StringBuilder();
        pendingCams.clear();
        final Div controls = buildControlsSection(key);
        sections.add(controls);
        sections.add(buildPrinterTable(key));
        sections.add(buildDispatchPoolCard(key));
        // Orders + Recently finished share their own two-column strip. Left in the outer auto-fit grid they sat in
        // tracks 1-2 of however many 460px tracks the window allowed, with the rest empty - auto-fit can't collapse
        // tracks that the full-width sections above are spanning.
        final Div bottom = new Div();
        bottom.addClassName("automation-full");
        bottom.addClassName("automation-bottom");
        bottom.add(buildOrdersSection(key), buildFulfillmentSection(key));
        sections.add(bottom);
        if (!overviewKey.equals(key.toString())) {
            overviewKey = key.toString();
            overview.removeAll();
            sections.forEach(overview::add);
            liveControls = controls;
            // Only adopt the cameras we actually put on screen. A no-change refresh still BUILDS a whole throwaway
            // tree, and adopting those would leave us pushing frames into detached components while the visible
            // ones went stale - the same trap the timer strip above documents.
            liveCams.clear();
            liveCams.putAll(pendingCams);
        }
        // The timer strip's seconds tick every refresh, so it can't be part of the key - it would rebuild the
        // whole DOM once a second. Instead it's a held component parented into whichever controls card is
        // actually on screen (never into a throwaway one built for a no-change refresh, which would silently
        // steal it out of the visible DOM).
        if (liveControls != null && pendingTimers.getParent().filter(p -> p == liveControls).isEmpty()) {
            liveControls.add(pendingTimers);
        }
        updatePendingTimers();
        updateCameras();
    }

    /**
     * Pushes the newest camera frame into the rows already on screen, outside the change-detection key.
     * <p>
     * The thumbnail changes roughly every second; putting its timestamp in the key would rebuild the entire
     * overview DOM that often. Leaving it out entirely was the bug: during a print nothing else in a printer
     * row's key moves - state stays PRINTING, the queue depth and auto-start status are unchanged - so the row
     * was never rebuilt and the "live" thumbnail sat frozen for the whole print. Swapping just the image source
     * is cheap and keeps the DOM stable.
     */
    private void updateCameras() {
        liveCams.forEach((name, img) -> printers.getPrinterDetail(name)
                .flatMap(d -> d.printer().getThumbnail())
                .ifPresent(t -> {
                    // Same guard the dashboard uses: re-setting an unchanged resource re-registers it for nothing.
                    final String id = t.thumbnail().getId();
                    if (!id.equals(camIds.get(name))) {
                        camIds.put(name, id);
                        img.setSrc(t.thumbnail());
                    }
                }));
    }

    /**
     * "What is the pipeline waiting on, and for how long" - rebuilt every refresh, deliberately outside the
     * overview's change-detection key. Without this a waiting farm is indistinguishable from a stuck one:
     * everything sits idle for up to a minute between dispatcher passes, and a backed-off printer looks dead
     * for three.
     */
    private void updatePendingTimers() {
        pendingTimers.removeAll();
        final List<Span> items = new ArrayList<>();

        dispatchQueue.getCheckingPrinters().forEach(p ->
                items.add(timerChip("%s: checking bed now…".formatted(p), "var(--lumo-primary-text-color)")));

        sortedPrinters().forEach(d -> dispatchQueue.getStartVerifyRemaining(d.name()).ifPresent(rem ->
                items.add(timerChip("%s: confirming print started · %s".formatted(d.name(), shortDuration(rem)),
                        "var(--lumo-primary-text-color)"))));

        sortedPrinters().forEach(d -> dispatchQueue.getBedBackoffRemaining(d.name()).ifPresent(rem ->
                items.add(timerChip("%s: bed re-check in %s".formatted(d.name(), shortDuration(rem)),
                        "var(--lumo-warning-text-color, #e8a33d)"))));

        if (dispatchQueue.size() > 0) {
            dispatchQueue.getNextTickIn().ifPresent(rem ->
                    items.add(timerChip("Next dispatch pass in %s".formatted(shortDuration(rem)),
                            "var(--lumo-secondary-text-color)")));
        }
        dispatchQueue.getJobRetryRemaining().ifPresent(rem ->
                items.add(timerChip("Failed job retries in %s".formatted(shortDuration(rem)),
                        "var(--lumo-warning-text-color, #e8a33d)")));

        nextPollIn(etsyPolling.getLastPolled(), config.etsy().pollInterval()).ifPresent(rem ->
                items.add(timerChip("Next Etsy poll in %s".formatted(shortDuration(rem)), "var(--lumo-secondary-text-color)")));
        nextPollIn(ebayPolling.getLastPolled(), config.ebay().pollInterval()).ifPresent(rem ->
                items.add(timerChip("Next eBay poll in %s".formatted(shortDuration(rem)), "var(--lumo-secondary-text-color)")));

        pendingTimers.setVisible(!items.isEmpty());
        if (items.isEmpty()) {
            return;
        }
        pendingTimers.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px 18px")
                .set("margin-top", "12px").set("padding-top", "10px")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("font-size", "var(--lumo-font-size-s)");
        items.forEach(pendingTimers::add);
    }

    private static Span timerChip(final String text, final String color) {
        final Span s = new Span("⏱ " + text);
        s.getStyle().setColor(color);
        return s;
    }

    private static Optional<java.time.Duration> nextPollIn(final Optional<java.time.Instant> lastPolled,
            final java.time.Duration interval) {
        return lastPolled.map(t -> {
            final java.time.Duration d = java.time.Duration.between(java.time.Instant.now(), t.plus(interval));
            return d.isNegative() ? java.time.Duration.ZERO : d;
        });
    }

    /** "2m 05s" / "45s" - compact enough to sit in a chip row. */
    private static String shortDuration(final java.time.Duration d) {
        final long total = Math.max(0, d.toSeconds());
        return total >= 60 ? "%dm %02ds".formatted(total / 60, total % 60) : "%ds".formatted(total);
    }

    /** Full-width card at the top: the big pipeline switches plus the at-a-glance chips. */
    private Div buildControlsSection(final StringBuilder key) {
        final List<BambuPrinters.PrinterDetail> details = sortedPrinters();
        final int openEtsy = etsyPolling.getReceipts().size();
        final int openEbay = ebayPolling.getOrders().size();
        final int unqueued = (int) (etsyPolling.getReceipts().stream()
                .filter(r -> tracking.queuedAt("etsy", String.valueOf(r.receiptId())).isEmpty()).count()
                + ebayPolling.getOrders().stream()
                        .filter(o -> tracking.queuedAt("ebay", o.orderId()).isEmpty()).count());
        final int queuedJobs = details.stream().mapToInt(d -> queueService.size(d.name())).sum() + dispatchQueue.size();
        final long printing = details.stream().filter(d -> d.printer().getGCodeState().isPrinting()).count();
        final long autoStartOn = details.stream().filter(d -> autoStartService.isEnabled(d.name())).count();
        final boolean asGlobal = autoStartService.isGloballyEnabled();
        final boolean aq = autoQueueService.isEnabled();
        final boolean aiConfigured = ollama.isEnabled();
        final boolean ai = aiService.isEnabled();
        key.append(openEtsy).append('|').append(openEbay).append('|').append(unqueued).append('|')
                .append(queuedJobs).append('|').append(printing).append('|').append(autoStartOn).append('|')
                .append(asGlobal).append('|').append(aq).append('|').append(ai).append('§');

        final Div strip = section();
        strip.addClassName("automation-full");

        // The headline controls - big, obvious, one click
        final Div controls = flexRow();
        final Button aqBtn = bigToggle("Auto-Queue", aq,
                aq ? "New mapped orders queue themselves to filament-matching printers. Click to turn OFF."
                        : "New orders wait for a manual Queue Print. Click to turn ON.");
        aqBtn.addClickListener(e -> {
            autoQueueService.setEnabled(!autoQueueService.isEnabled());
            showNotification("Auto-queue " + (autoQueueService.isEnabled() ? "enabled" : "disabled"));
            forceRefresh();
        });
        final Button aiBtn = bigToggle("AI Checks", ai,
                !aiConfigured ? "Set bambu.ollama.url to enable AI checks"
                        : ai ? "Failure/first-layer/bed-clear checks are running. Click to suspend."
                                : "All AI checks (and auto-start's bed gate) are suspended. Click to resume.");
        aiBtn.setEnabled(aiConfigured);
        aiBtn.addClickListener(e -> {
            aiService.setRuntimeEnabled(!aiService.isRuntimeEnabled());
            showNotification("AI checks " + (aiService.isRuntimeEnabled() ? "enabled" : "disabled"));
            forceRefresh();
        });
        final Button asBtn = bigToggle("Auto-Start %d/%d".formatted(autoStartOn, details.size()), asGlobal,
                asGlobal ? "Master switch is ON - each printer enabled on the Print Queue tab auto-starts its queue "
                        + "after the AI bed-clear check. Click to turn auto-start OFF for the whole farm (per-printer "
                        + "selections are kept)."
                        : "Master switch is OFF - nothing auto-starts even where enabled per printer. Click to turn ON. "
                        + "Choose which printers participate on the Print Queue tab.");
        asBtn.addClickListener(e -> {
            autoStartService.setGloballyEnabled(!autoStartService.isGloballyEnabled());
            showNotification("Auto-start " + (autoStartService.isGloballyEnabled() ? "enabled globally" : "disabled globally"));
            forceRefresh();
        });
        final boolean requeue = autoQueueService.isAutoRequeueEnabled();
        key.append(requeue).append('|');
        final Button rqBtn = bigToggle("Auto-Requeue", requeue,
                requeue ? "A failed queue-started print goes back to the front of the queue for ONE retry. Click to turn OFF."
                        : "Failed prints stay failed until you requeue them. Click to enable a single automatic retry.");
        rqBtn.addClickListener(e -> {
            autoQueueService.setAutoRequeueEnabled(!autoQueueService.isAutoRequeueEnabled());
            showNotification("Auto-requeue " + (autoQueueService.isAutoRequeueEnabled() ? "enabled" : "disabled"));
            forceRefresh();
        });
        // Action (not a toggle): you've just cleared the beds and want the waiting order jobs to go NOW rather
        // than waiting out the dispatcher's per-printer backoff.
        final int waiting = dispatchQueue.size();
        key.append(waiting).append('|');
        final Button dispatchBtn = new Button(waiting > 0 ? "Dispatch Now (%d)".formatted(waiting) : "Dispatch Now");
        dispatchBtn.addClassName("automation-toggle");
        dispatchBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_LARGE);
        dispatchBtn.setEnabled(waiting > 0);
        dispatchBtn.setTooltipText(waiting > 0
                ? "Re-check every idle printer's bed right now and send the waiting order jobs to whichever ones "
                + "are clear - use this after clearing beds instead of waiting for the next automatic pass."
                : "No order jobs are waiting in the dispatch pool.");
        dispatchBtn.addClickListener(e -> {
            showNotification(dispatchQueue.dispatchNow());
            forceRefresh();
        });
        controls.add(aqBtn, aiBtn, asBtn, rqBtn, dispatchBtn);
        strip.add(controls);

        // Headline numbers: the four things worth knowing before reading anything else on the page.
        final int parked = dispatchQueue.parkedCount();
        final long inProgressOrders = inProgressOrders().size();
        final long attention = parked
                + details.stream().filter(d -> aiService.getLastResult(d.name())
                        .filter(r -> !r.good()).isPresent()).count()
                + (dispatchQueue.getBlockedKind() == com.tfyre.bambu.printer.DispatchQueueService.BlockKind.ATTENTION
                        && dispatchQueue.getBlockedStatus().isPresent() ? 1 : 0);
        key.append(parked).append(inProgressOrders).append(attention).append('|');

        final Div kpis = new Div();
        kpis.addClassName("automation-kpis");
        final long readyToShip = inProgressOrders().stream().filter(OrderSummary::readyToShip).count();
        key.append(readyToShip).append('|');
        kpis.add(kpi("Open orders", String.valueOf(openEtsy + openEbay),
                readyToShip > 0 ? "%d ready to ship".formatted(readyToShip)
                        : unqueued > 0 ? "%d not queued".formatted(unqueued)
                        : "%d in progress".formatted(inProgressOrders),
                readyToShip > 0 ? "var(--lumo-success-text-color)"
                        : unqueued > 0 ? "var(--lumo-warning-text-color, #e8a33d)" : null));
        kpis.add(kpi("Waiting to dispatch", String.valueOf(dispatchQueue.size()),
                parked > 0 ? "%d parked".formatted(parked) : "in the pool", parked > 0 ? "var(--lumo-error-text-color)" : null));
        kpis.add(kpi("Printing", String.valueOf(printing), "of %d printers".formatted(details.size()), null));
        kpis.add(kpi("Needs attention", String.valueOf(attention), attention == 0 ? "all clear" : "see below",
                attention > 0 ? "var(--lumo-warning-text-color, #e8a33d)" : "var(--lumo-success-text-color)"));
        strip.add(kpis);

        // The dispatch pool fails closed: if the AI gate can't pass, order jobs sit there indefinitely. Say so
        // loudly here rather than letting the pipeline look healthy while nothing starts.
        final Optional<String> dispatchBlocked = dispatchQueue.getBlockedStatus();
        key.append(dispatchBlocked.orElse("")).append('|');
        if (dispatchBlocked.isPresent()) {
            // "Every printer is busy" is normal congestion, not a fault - amber and an hourglass, not red.
            final boolean congestion = dispatchQueue.getBlockedKind() == com.tfyre.bambu.printer.DispatchQueueService.BlockKind.WAITING;
            final Span warn = new Span((congestion ? "⏳ Dispatch pool waiting: " : "⚠ Dispatch pool held: ") + dispatchBlocked.get());
            warn.getStyle().setColor(congestion ? "var(--lumo-warning-text-color, #e8a33d)" : "var(--lumo-error-text-color)");
            final Div warnLine = new Div(warn);
            warnLine.addClassName("automation-line");
            strip.add(warnLine);
        }

        key.append('§');
        return strip;
    }

    private static Div kpi(final String label, final String value, final String sub, final String color) {
        final Div box = new Div();
        box.addClassName("automation-kpi");
        final Div l = new Div(new Span(label));
        l.addClassName("kpi-label");
        final Span v = new Span(value);
        final Span s = new Span(" " + sub);
        s.addClassName("kpi-sub");
        final Div val = new Div(v, s);
        val.addClassName("kpi-value");
        if (color != null) {
            v.getStyle().setColor(color);
        }
        box.add(l, val);
        return box;
    }

    // -------------------------------------------------------------------------
    // Printers - one row per machine, replacing the old split queue/printing cards
    // -------------------------------------------------------------------------

    /**
     * The heart of the page: every printer on one row, with its queue, per-printer settings and last AI check
     * folded into a click-to-expand detail (this is what the old Print Queue tab held). Splitting printers across
     * two cards meant reading the same five machines twice; the AI reasoning that used to fill the page now lives
     * in the expansion, where it doesn't compete with the at-a-glance state.
     */
    private Div buildPrinterTable(final StringBuilder key) {
        final Div sec = section();
        sec.addClassName("automation-full");
        sec.add(new H4("Printers"));

        final Div table = new Div();
        table.addClassName("printer-table");

        final Div head = new Div();
        head.addClassName("pt-head");
        head.add(new Span("View"), new Span("Printer"), new Span("Job"), new Span("Progress"),
                new Span("Filament"), new Span("Queue"), new Span("AI check"), new Span("Auto-start"), new Span(""));
        table.add(head);

        for (final BambuPrinters.PrinterDetail detail : sortedPrinters()) {
            table.add(buildPrinterRow(detail, key));
            problems(detail.printer()).ifPresent(msg -> {
                key.append(msg).append('|');
                table.add(errorBanner(msg));
            });
            if (expandedPrinters.contains(detail.name())) {
                table.add(buildPrinterDetail(detail));
            }
        }
        sec.add(table);
        sec.add(secondary("Click a printer for its queue, settings and last AI check."));
        key.append('§');
        return sec;
    }

    private Div buildPrinterRow(final BambuPrinters.PrinterDetail detail, final StringBuilder key) {
        final String name = detail.name();
        final BambuPrinter printer = detail.printer();
        final BambuConst.GCodeState state = printer.getGCodeState();
        final int queued = queueService.size(name);
        final String auto = autoStartService.getStatus(name);
        final Optional<PrintAiService.AiCheckResult> ai = aiService.getLastResult(name);
        final boolean open = expandedPrinters.contains(name);
        // Progress and remaining time MUST be in the key. Without them a printing row never changed - state stays
        // PRINTING, the queue depth and auto-start status hold steady - so the bar and the ETA sat frozen for the
        // whole print. They tick at most once per percent and once per minute, so this does not thrash the DOM.
        key.append(name).append(state).append(queued).append(auto)
                .append(printer.getProgressPercent()).append('/').append(printer.getRemainingMinutes())
                // Loaded filament too: swapping a spool on an IDLE printer moves nothing else in this key, so the
                // chips would keep showing the old material until something unrelated happened to change.
                .append(printer.getAmsTrayTypes()).append(printer.getActiveTrayId())
                .append(ai.map(r -> r.good() + r.checkedAt().toString()).orElse("")).append(open).append('|');

        final Div row = new Div();
        row.addClassName("pt-row");
        row.addClickListener(e -> {
            if (!expandedPrinters.remove(name)) {
                expandedPrinters.add(name);
            }
            forceRefresh();
        });

        row.add(cameraCell(detail));

        final boolean faulted = problems(printer).isPresent();
        final Span dot = new Span(open ? "▾ ● " : "▸ ● ");
        dot.getStyle().setColor(faulted ? "var(--lumo-error-text-color)"
                : state.isPrinting() ? "var(--lumo-primary-text-color)"
                : state.isReady() ? "var(--lumo-success-text-color)" : "var(--lumo-contrast-50pct)");
        // The name links to that printer's own page; stopPropagation so it doesn't also toggle the row
        final Anchor link = new Anchor("printer/" + name, name);
        link.addClassName("pt-name");
        link.setTitle("Open " + name + "'s page");
        link.getElement().addEventListener("click", e -> { }).stopPropagation();
        row.add(new Div(dot, link));

        final Span file = new Span(state.isPrinting()
                ? jobName(printer).orElse("(unknown file)") : state.getDescription().toLowerCase());
        final Div fileCell = new Div(file);
        fileCell.addClassName("pt-ellipsis");
        if (faulted) {
            file.getStyle().setColor("var(--lumo-error-text-color)");
        } else if (!state.isPrinting()) {
            file.getStyle().setColor("var(--lumo-secondary-text-color)");
        }
        row.add(fileCell);

        row.add(progressCell(printer, state));
        row.add(filamentCell(printer));
        row.add(new Span(queued == 0 ? "—" : "%d queued".formatted(queued)));

        // A verdict from before the printer's current situation is worse than no verdict - a green tick next to a
        // faulted printer reads as "all fine". Grey those out rather than showing them as current.
        final boolean stale = ai.isPresent() && isStale(ai.get(), printer);
        final Span aiCell = new Span(ai
                .map(r -> stale ? "— stale" : (r.good() ? "✓ " : "⚠ ") + shortCheck(r.checkType()))
                .orElse("— not run"));
        aiCell.getStyle().setColor(stale || ai.isEmpty() ? "var(--lumo-contrast-50pct)"
                : ai.get().good() ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        ai.ifPresent(r -> aiCell.setTitle("%s — %s".formatted(ago(r.checkedAt()), truncate(r.description(), 200))));
        row.add(aiCell);

        final String autoText = faulted ? "blocked: error" : auto;
        final Span autoCell = new Span(autoText);
        autoCell.getStyle().setColor(faulted || autoText.startsWith("blocked") || autoText.startsWith("paused")
                ? "var(--lumo-error-text-color)"
                : "off".equals(autoText) ? "var(--lumo-contrast-50pct)" : "var(--lumo-secondary-text-color)");
        row.add(autoCell);

        // Start Next lives on the row so a ready printer with a queue is one click from running.
        // stopPropagation matters: without it the click also bubbles to the row and toggles the expansion.
        final Div action = new Div();
        action.getElement().addEventListener("click", e -> { }).stopPropagation();
        if (queued > 0 && state.isReady() && !printer.isBlocked()) {
            final Button start = new Button("Start next");
            start.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
            start.addClickListener(e -> doStartNext(detail));
            action.add(start);
        }
        row.add(action);
        return row;
    }

    /**
     * Live camera thumbnail, click to enlarge.
     * <p>
     * Two sources, because the fleet is split: P1/A1 push raw JPEGs over the port-6000 stream, which is what
     * {@code getThumbnail()} already holds - free to render, no extra polling. X1C/X1E/H2D never push that stream
     * (RTSPS only), so there is no still frame to show without spawning ffmpeg on every refresh; for those the tile
     * opens the live view instead, which is the thing you actually wanted to look at anyway.
     */
    private Div cameraCell(final BambuPrinters.PrinterDetail detail) {
        final BambuPrinter printer = detail.printer();
        final Div cell = new Div();
        cell.addClassName("pt-cam");
        // Clicks here must not also toggle the row expansion
        cell.getElement().addEventListener("click", e -> { }).stopPropagation();

        final Optional<BambuPrinter.Thumbnail> thumb = printer.getThumbnail();
        final Optional<String> stream = printer.getIFrame();
        if (thumb.isPresent()) {
            final com.vaadin.flow.component.html.Image img =
                    new com.vaadin.flow.component.html.Image(thumb.get().thumbnail(), detail.name() + " camera");
            img.setTitle("Click to enlarge");
            img.addClickListener(e -> openCamera(detail));
            cell.add(img);
            // Registered for in-place frame updates; the thumbnail is deliberately not in the rebuild key.
            pendingCams.put(detail.name(), img);
        } else if (stream.isPresent()) {
            final Div tile = new Div(new Span("live view"));
            tile.addClassName("pt-cam-none");
            tile.setTitle("No still frame on this model - click to open the live stream");
            tile.addClickListener(e -> openCamera(detail));
            cell.add(tile);
        } else {
            final Div tile = new Div(new Span("no camera"));
            tile.addClassName("pt-cam-none");
            tile.setTitle("No camera frame or live stream configured for this printer");
            cell.add(tile);
        }
        return cell;
    }

    /**
     * Enlarged live view. X1C/X1E/H2D expose an embeddable stream; P1/A1 don't - their "live view" is the
     * port-6000 JPEG feed, so the image is re-pointed at the newest frame on every tick while the dialog is open.
     * A single still would be a freeze-frame, which is not what clicking a camera should give you.
     */
    private void openCamera(final BambuPrinters.PrinterDetail detail) {
        final com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(detail.name());
        dialog.setWidth("900px");
        final Optional<String> stream = detail.printer().getIFrame();
        if (stream.isPresent()) {
            final com.vaadin.flow.component.html.IFrame frame =
                    new com.vaadin.flow.component.html.IFrame(stream.get());
            frame.setWidthFull();
            frame.setHeight("500px");
            frame.getStyle().set("border", "0");
            dialog.add(frame);
        } else if (detail.printer().getThumbnail().isPresent()) {
            final com.vaadin.flow.component.html.Image img = new com.vaadin.flow.component.html.Image(
                    detail.printer().getThumbnail().get().thumbnail(), detail.name() + " camera");
            img.setWidthFull();
            dialog.add(img);
            final UI ui = UI.getCurrent();
            final ScheduledFuture<?> feed = ses.scheduleAtFixedRate(
                    () -> ui.access(() -> detail.printer().getThumbnail()
                            .ifPresent(t -> img.setSrc(t.thumbnail()))),
                    1, 1, TimeUnit.SECONDS);
            dialog.addDetachListener(e -> feed.cancel(true));
            dialog.addDialogCloseActionListener(e -> {
                feed.cancel(true);
                dialog.close();
            });
        } else {
            dialog.add(new Span("No camera for this printer."));
        }
        final Button open = new Button("Open printer page", e -> {
            dialog.close();
            getUI().ifPresent(u -> u.navigate("printer/" + detail.name()));
        });
        dialog.getFooter().add(open, new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    /**
     * The printer's own error text, if any - a print error code resolved through {@link BambuErrors}, else an
     * active HMS alert. Without this a paused printer with a jammed AMS is indistinguishable from a paused one.
     */
    private Optional<String> problems(final BambuPrinter printer) {
        final int code = printer.getPrintError();
        if (code != 0) {
            return Optional.of("Error %s: %s".formatted(Integer.toHexString(code),
                    com.tfyre.bambu.printer.BambuErrors.getPrinterError(code).orElse("unknown error")));
        }
        final List<String> hms = printer.getActiveHmsErrors();
        return hms.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", hms));
    }

    private static Div errorBanner(final String message) {
        final Div wrap = new Div();
        wrap.addClassName("pt-error");
        final Span icon = new Span("⚠ ");
        icon.getStyle().setFontWeight("bold");
        wrap.add(icon, new Span(message));
        return wrap;
    }

    /** Materials currently loaded, one chip per occupied tray - this is what decides dispatch eligibility. */
    private Div filamentCell(final BambuPrinter printer) {
        final Div cell = new Div();
        cell.addClassName("pt-filament");
        final Map<Integer, String> trays = printer.getAmsTrayTypes();
        if (trays.isEmpty()) {
            final Span none = new Span("—");
            none.getStyle().setColor("var(--lumo-contrast-50pct)");
            cell.add(none);
            return cell;
        }
        final int active = printer.getActiveTrayId();
        trays.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    final Span chip = new Span(e.getValue());
                    chip.addClassName("pt-chip");
                    if (e.getKey() == active) {
                        chip.addClassName("pt-chip-active");
                        chip.setTitle("Currently feeding from this tray");
                    }
                    cell.add(chip);
                });
        return cell;
    }

    /** A check is stale once the printer has faulted, or once it's older than two check intervals. */
    private boolean isStale(final PrintAiService.AiCheckResult r, final BambuPrinter printer) {
        if (problems(printer).isPresent()) {
            return true;
        }
        final Duration age = Duration.between(r.checkedAt(), Instant.now());
        return age.compareTo(config.ollama().failureCheckInterval().multipliedBy(2)) > 0;
    }

    private Div progressCell(final BambuPrinter printer, final BambuConst.GCodeState state) {
        final Div cell = new Div();
        if (!state.isPrinting()) {
            return cell;
        }
        // Sticky values from BambuPrinter - polling the latest MQTT message reads 0 most of the time,
        // because Bambu's partial delta pushes usually omit mc_percent entirely.
        final int pct = Math.max(printer.getProgressPercent(), 0);
        final long remaining = Math.max(printer.getRemainingMinutes(), 0);
        final Div bar = new Div();
        bar.addClassName("pt-bar");
        final Span fill = new Span();
        fill.getStyle().set("width", pct + "%");
        bar.add(fill);
        final Div sub = new Div(new Span(remaining > 0
                ? "%d%% · %s left".formatted(pct, shortDuration(java.time.Duration.ofMinutes(remaining)))
                : "%d%%".formatted(pct)));
        sub.addClassName("pt-sub");
        cell.add(bar, sub);
        return cell;
    }

    /** Expanded row: queue entries, per-printer automation settings, and the last AI check in full. */
    private Div buildPrinterDetail(final BambuPrinters.PrinterDetail detail) {
        final String name = detail.name();
        final Div wrap = new Div();
        wrap.addClassName("pt-detail");
        final Div inner = new Div();
        inner.addClassName("pt-detail-inner");

        final List<PrintQueueService.QueueEntry> queue = queueService.getQueue(name);
        if (queue.isEmpty()) {
            inner.add(secondary("Queue is empty."));
        } else {
            for (int i = 0; i < queue.size(); i++) {
                final PrintQueueService.QueueEntry entry = queue.get(i);
                final int idx = i;
                final Div qrow = new Div();
                qrow.addClassName("pt-qrow");
                final String order = entry.orderRef() == null ? "" : "  [%s]".formatted(entry.orderRef().label());
                qrow.add(new Span("%d. %s (plate %d)%s".formatted(i + 1, entry.command().filename(),
                        entry.command().plateId(), order)));
                final Div btns = new Div();
                if (idx > 0) {
                    final Button front = new Button(new Icon(VaadinIcon.ANGLE_DOUBLE_UP));
                    front.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
                    front.setTooltipText("Move to front - prints next");
                    front.addClickListener(e -> {
                        queueService.moveToFront(name, entry);
                        forceRefresh();
                    });
                    btns.add(front);
                }
                final Button del = new Button(new Icon(VaadinIcon.TRASH));
                del.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
                final boolean backToPool = PrintQueueService.returnsToPool(entry);
                del.setTooltipText(backToPool
                        ? "Take off this printer - goes back to the dispatch pool"
                        : "Remove from queue");
                del.addClickListener(e -> {
                    queueService.removeEntry(name, entry);
                    showNotification(backToPool
                            ? "Returned to the dispatch pool" : "Removed from the queue");
                    forceRefresh();
                });
                btns.add(del);
                qrow.add(btns);
                inner.add(qrow);
            }
        }

        // The last AI check in full - this is the text that used to crowd the overview
        aiService.getLastCheck(name).ifPresent(rec -> {
            final Div box = new Div();
            box.getStyle().set("margin-top", "8px").set("font-size", "var(--lumo-font-size-s)");
            final Span verdict = new Span("%s %s — %s".formatted(
                    Boolean.TRUE.equals(rec.good()) ? "✓" : "⚠", shortCheck(rec.checkType()), ago(rec.at())));
            verdict.getStyle().setColor(Boolean.TRUE.equals(rec.good())
                    ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
            box.add(verdict);
            if (rec.pixelDiff() != null) {
                box.add(new Span(" · pixel diff %.2f".formatted(rec.pixelDiff())));
            }
            box.add(new Div(secondary(rec.description() == null ? "(no detail)" : rec.description())));
            inner.add(box);
        });

        // Per-printer automation settings. They're configuration, not monitoring, so they live in here.
        final Checkbox autoStart = new Checkbox("Auto-start when the bed is clear");
        autoStart.setValue(autoStartService.isEnabled(name));
        autoStart.setTooltipText("Starts the next queued job once the AI confirms the bed is clear. No answer = no start.");
        autoStart.addValueChangeListener(e -> {
            autoStartService.setEnabled(name, Boolean.TRUE.equals(e.getValue()));
            showNotification("Auto-start on %s %s".formatted(name, autoStartService.isEnabled(name) ? "enabled" : "disabled"));
        });
        final Checkbox autoQueue = new Checkbox("Auto-queue new orders here");
        autoQueue.setValue(autoQueueService.isPrinterEnabled(name));
        autoQueue.setTooltipText("Uncheck to keep new orders off this printer. Manual and batch prints still work.");
        autoQueue.addValueChangeListener(e -> {
            autoQueueService.setPrinterEnabled(name, Boolean.TRUE.equals(e.getValue()));
            showNotification("Auto-queue to %s %s".formatted(name,
                    autoQueueService.isPrinterEnabled(name) ? "enabled" : "disabled"));
        });
        final Div opts = new Div(autoStart, autoQueue);
        opts.addClassName("pt-opts");
        inner.add(opts);

        wrap.add(inner);
        return wrap;
    }

    /**
     * Same AI-gated flow the dashboard and the Print Queue page use: bed check first, then a confirmation that
     * carries the AI's verdict, then start. Never starts without the user seeing what the check said.
     */
    private void doStartNext(final BambuPrinters.PrinterDetail detail) {
        final String name = detail.name();
        queueService.peek(name).ifPresentOrElse(entry -> {
            if (!aiService.isEnabled()) {
                confirmAndStart(detail, entry, "");
                return;
            }
            showNotification("%s: checking bed…".formatted(name));
            final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
            aiService.checkBedClear(name, "start-next").thenAccept(result -> ui.ifPresent(u -> u.access(() -> {
                if (result.isEmpty()) {
                    confirmAndStart(detail, entry, "");
                    return;
                }
                final OllamaService.AiResult ai = result.get();
                if (ai.positive()) {
                    confirmAndStart(detail, entry, "\n\n\u2713 AI: bed appears clear \u2014 " + truncate(ai.description(), 150));
                    return;
                }
                YesNoCancelDialog.show("%s \u2014 AI detected: bed may not be clear\n\n%s\n\nOverride and start anyway?"
                        .formatted(name, truncate(ai.description(), 150)),
                        ync -> {
                            if (ync.isConfirmed()) {
                                performStart(detail);
                            }
                        });
            })));
        }, () -> showError("%s: queue is empty".formatted(name)));
    }

    private void confirmAndStart(final BambuPrinters.PrinterDetail detail,
            final PrintQueueService.QueueEntry entry, final String aiNote) {
        YesNoCancelDialog.show("%s - Start next queued print [%s] plate %d\n\nIs the bed clear?%s"
                .formatted(detail.name(), entry.command().filename(), entry.command().plateId(), aiNote),
                ync -> {
                    if (ync.isConfirmed()) {
                        performStart(detail);
                    }
                });
    }

    private void performStart(final BambuPrinters.PrinterDetail detail) {
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        queueService.startNext(detail.name(),
                () -> ui.ifPresent(u -> u.access(() -> {
                    showNotification("%s: print started".formatted(detail.name()));
                    forceRefresh();
                })),
                error -> ui.ifPresent(u -> u.access(() -> showError(error))));
    }

    private Optional<String> jobName(final BambuPrinter printer) {
        return printer.getSubtaskName().or(printer::getLastPrintFile);
    }

    private static String shortCheck(final String checkType) {
        return switch (checkType == null ? "" : checkType) {
            case "bed-clear" -> "bed";
            case "first-layer" -> "first layer";
            case "failure" -> "print";
            default -> checkType;
        };
    }

    // -------------------------------------------------------------------------
    // Dispatch pool (moved here from the Print Queue tab)
    // -------------------------------------------------------------------------

    private Div buildDispatchPoolCard(final StringBuilder key) {
        final Div sec = section();
        sec.addClassName("automation-full");
        final List<com.tfyre.bambu.printer.DispatchQueueService.PendingJob> pool = dispatchQueue.getPool();
        sec.add(new H4("Order dispatch pool — %d waiting".formatted(pool.size())));
        key.append(pool.size()).append('|');

        dispatchQueue.getBlockedStatus().ifPresent(why -> {
            final boolean congestion = dispatchQueue.getBlockedKind()
                    == com.tfyre.bambu.printer.DispatchQueueService.BlockKind.WAITING;
            final Span warn = new Span((congestion ? "⏳ " : "⚠ ") + why);
            warn.getStyle().setColor(congestion
                    ? "var(--lumo-warning-text-color, #e8a33d)" : "var(--lumo-error-text-color)");
            sec.add(line(warn));
            key.append(why).append('|');
        });

        if (pool.isEmpty()) {
            sec.add(secondary("Nothing waiting."));
            key.append('§');
            return sec;
        }
        dispatchQueue.getNextFree().ifPresent(next -> {
            key.append(next).append('|');
            sec.add(line(secondary("Next printer free: " + next)));
        });
        final List<BambuPrinters.PrinterDetail> details = sortedPrinters();
        pool.forEach(job -> {
            key.append(job.id()).append('|');
            final Div row = new Div();
            row.addClassName("pt-qrow");
            final var part = job.part();
            final Optional<String> parked = dispatchQueue.getParkedReason(job.id());
            final Span label = new Span("%s (plate %d)%s%s".formatted(part.path(), part.plateId(),
                    part.filamentType() != null ? " · " + part.filamentType() : "",
                    job.orderRef() != null ? "  [" + job.orderRef().label() + "]" : ""));
            if (parked.isPresent()) {
                label.getStyle().setColor("var(--lumo-error-text-color)");
                label.setTitle("Parked: " + parked.get());
            }
            row.add(label);

            final Div btns = new Div();
            final ComboBox<BambuPrinters.PrinterDetail> sendTo = new ComboBox<>();
            sendTo.setPlaceholder("Send to…");
            sendTo.setItems(details.stream().filter(pd -> autoQueueService.resolveSlot(pd, part).isPresent()).toList());
            sendTo.setItemLabelGenerator(BambuPrinters.PrinterDetail::name);
            sendTo.setWidth("160px");
            sendTo.setTooltipText("Only printers with the right filament loaded");
            sendTo.addValueChangeListener(e -> {
                final BambuPrinters.PrinterDetail pd = e.getValue();
                if (pd == null) {
                    return;
                }
                final Integer slot = autoQueueService.resolveSlot(pd, part)
                        .map(AutoQueueService.Candidate::resolvedSlot).orElse(part.amsSlot());
                final Optional<String> error = queuer.queuePart(part, pd.name(), slot, job.orderRef());
                if (error.isPresent()) {
                    showError(error.get());
                    return;
                }
                dispatchQueue.markDispatched(job.id());
                showNotification("Sent to %s — use its Start next".formatted(pd.name()));
                forceRefresh();
            });
            btns.add(sendTo);
            if (parked.isPresent()) {
                final Button retry = new Button(new Icon(VaadinIcon.REFRESH));
                retry.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
                retry.setTooltipText("Parked: %s — click to try again".formatted(parked.get()));
                retry.addClickListener(e -> {
                    dispatchQueue.retry(job.id());
                    showNotification("Job un-parked");
                    forceRefresh();
                });
                btns.add(retry);
            }
            final Button remove = new Button(new Icon(VaadinIcon.TRASH));
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            remove.setTooltipText("Remove - won't be printed");
            remove.addClickListener(e -> {
                dispatchQueue.remove(job.id());
                showNotification("Removed from the dispatch pool");
                forceRefresh();
            });
            btns.add(remove);
            row.add(btns);
            sec.add(row);
        });
        key.append('§');
        return sec;
    }

    private Button bigToggle(final String label, final boolean on, final String tooltip) {
        final Button b = new Button("%s: %s".formatted(label, on ? "ON" : "OFF"));
        b.addClassName("automation-toggle");
        b.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_LARGE);
        if (on) {
            b.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY,
                    com.vaadin.flow.component.button.ButtonVariant.LUMO_SUCCESS);
        }
        b.setTooltipText(tooltip);
        return b;
    }

    private void forceRefresh() {
        overviewKey = "";
        refreshOverview();
    }

    private Div buildOrdersSection(final StringBuilder key) {
        final Div sec = section();
        sec.add(new H4("Orders"));

        sec.add(marketRow("Etsy", etsyOauth.isConnected(), etsyPolling.getReceipts().size(),
                (int) etsyPolling.getReceipts().stream().filter(r -> tracking.queuedAt("etsy", String.valueOf(r.receiptId())).isEmpty()).count(),
                etsyPolling.getLastPolled(), etsyPolling.getLastError(), "etsy-orders",
                etsyPolling.getReceipts().stream()
                        .filter(r -> tracking.queuedAt("etsy", String.valueOf(r.receiptId())).isEmpty())
                        .map(r -> r.createTimestamp()).min(Instant::compareTo), key));
        sec.add(marketRow("eBay", ebayOauth.isConnected(), ebayPolling.getOrders().size(),
                (int) ebayPolling.getOrders().stream().filter(o -> tracking.queuedAt("ebay", o.orderId()).isEmpty()).count(),
                ebayPolling.getLastPolled(), ebayPolling.getLastError(), "ebay-orders",
                ebayPolling.getOrders().stream()
                        .filter(o -> tracking.queuedAt("ebay", o.orderId()).isEmpty())
                        .map(o -> o.creationDate()).min(Instant::compareTo), key));

        // Orders currently being printed. Lead with WHAT was ordered - an order number tells you nothing at a
        // glance, and knowing you owe someone a cupholder is the point of the card.
        final List<OrderSummary> open = inProgressOrders();
        if (!open.isEmpty()) {
            
            // Ready-to-ship first - those are the ones waiting on you rather than on a printer
            open.stream()
                    .sorted(Comparator.comparing(OrderSummary::readyToShip).reversed())
                    .limit(6)
                    .forEach(o -> {
                        key.append(o.orderId()).append(o.printed()).append(o.expected())
                                .append(o.abandoned()).append('|');
                        final String icon = o.needsAttention() ? "⚠" : o.readyToShip() ? "✓" : "⏳";
                        final String status = o.needsAttention()
                                ? "%d part%s failed - re-queue".formatted(o.abandoned(), o.abandoned() == 1 ? "" : "s")
                                : o.readyToShip() ? "ready to ship" : "%d/%d printed".formatted(o.printed(), o.expected());
                        final String colour = o.needsAttention() ? "var(--lumo-error-text-color, #e05c5c)"
                                : o.readyToShip() ? "var(--lumo-success-text-color)"
                                        : "var(--lumo-warning-text-color, #e8a33d)";
                        sec.add(orderLine(icon, o.title(), o.subtitle(), status, colour));
                    });
        }

        key.append('§');
        return sec;
    }

    /** One order row: item name on top, order number + buyer underneath, status on the right. */
    private Div orderLine(final String icon, final String title, final String subtitle,
            final String status, final String statusColor) {
        final Div row = new Div();
        row.addClassName("order-line");
        final Div left = new Div();
        left.addClassName("order-line-main");
        final Span t = new Span("%s %s".formatted(icon, title));
        final Span sub = new Span(subtitle);
        sub.addClassName("order-line-sub");
        left.add(t, sub);
        final Span st = new Span(status);
        st.addClassName("order-line-status");
        st.getStyle().setColor(statusColor);
        row.add(left, st);
        return row;
    }

    /** An in-flight order with enough context to be readable without looking anything up. */
    private record OrderSummary(String market, String orderId, String title, String subtitle,
            int printed, int expected, int abandoned) {

        /** Every part printed, but the order is still open at the marketplace - it's waiting on you to ship it. */
        boolean readyToShip() {
            return printed >= expected && abandoned == 0;
        }

        /** A part failed and was never reprinted. The order is short regardless of what the counts say. */
        boolean needsAttention() {
            return abandoned > 0;
        }
    }

    /**
     * Orders still being printed. Restricted to orders the marketplace still lists as OPEN: progress entries
     * outlive the order (they're only completed by a finished job carrying an order ref, so anything queued
     * before order linkage existed, or shipped by hand, sits at 0/N forever). Those are finished business, not
     * work in progress - and being absent from the open list is also why they could only ever show an ID.
     */
    private List<OrderSummary> inProgressOrders() {
        final List<OrderSummary> out = new ArrayList<>();
        for (final String market : List.of("etsy", "ebay")) {
            tracking.progress(market).stream()
                    // abandoned > 0 keeps a single-part order visible after its only part failed and released its
                    // expectation - expected drops to 0, and without this the order would vanish from the page at
                    // exactly the moment it needs you.
                    .filter(p -> p.expected() > 0 || p.abandoned() > 0)
                    .filter(p -> isOpen(market, p.orderId()))
                    .forEach(p -> out.add(new OrderSummary(market, p.orderId(),
                            p.title() != null && !p.title().isBlank()
                                    ? truncate(p.title(), 70) : orderTitle(market, p.orderId()),
                            orderSubtitle(market, p.orderId()), p.printed(), p.expected(), p.abandoned())));
        }
        return out;
    }

    /** Whether the marketplace still lists this order as open and unfulfilled. */
    private boolean isOpen(final String market, final String orderId) {
        return "etsy".equals(market)
                ? etsyPolling.getReceipts().stream().anyMatch(r -> String.valueOf(r.receiptId()).equals(orderId))
                : ebayPolling.getOrders().stream().anyMatch(o -> o.orderId().equals(orderId));
    }

    /**
     * What was actually ordered, from the polled receipts. Falls back to the order number for an order that has
     * already dropped out of the open list - better a number than a blank line.
     */
    private String orderTitle(final String market, final String orderId) {
        final List<String> titles = "etsy".equals(market)
                ? etsyPolling.getReceipts().stream()
                        .filter(r -> String.valueOf(r.receiptId()).equals(orderId))
                        .flatMap(r -> r.transactions().stream())
                        .map(t -> t.quantity() > 1 ? "%dx %s".formatted(t.quantity(), t.title()) : t.title())
                        .toList()
                : ebayPolling.getOrders().stream()
                        .filter(o -> o.orderId().equals(orderId))
                        .flatMap(o -> o.lineItems().stream())
                        .map(li -> li.quantity() > 1 ? "%dx %s".formatted(li.quantity(), li.title()) : li.title())
                        .toList();
        if (titles.isEmpty()) {
            return "%s %s".formatted("etsy".equals(market) ? "Etsy" : "eBay", orderId);
        }
        final String first = truncate(titles.get(0), 70);
        return titles.size() == 1 ? first : "%s  +%d more".formatted(first, titles.size() - 1);
    }

    private String orderSubtitle(final String market, final String orderId) {
        final String buyer = "etsy".equals(market)
                ? etsyPolling.getReceipts().stream()
                        .filter(r -> String.valueOf(r.receiptId()).equals(orderId))
                        .map(r -> r.buyerName()).findFirst().orElse("")
                : ebayPolling.getOrders().stream()
                        .filter(o -> o.orderId().equals(orderId))
                        .map(o -> o.buyerUsername()).findFirst().orElse("");
        final String label = "%s %s".formatted("etsy".equals(market) ? "Etsy #" : "eBay", orderId);
        return buyer == null || buyer.isBlank() ? label : "%s · %s".formatted(label, buyer);
    }

    private Div marketRow(final String name, final boolean connected, final int open, final int unqueued,
            final Optional<Instant> lastPolled, final Optional<String> lastError, final String route,
            final Optional<Instant> oldestUnqueued, final StringBuilder key) {
        key.append(name).append(connected).append(open).append(unqueued)
                .append(lastPolled.map(Instant::getEpochSecond).orElse(0L)).append(lastError.orElse(""))
                .append(oldestUnqueued.map(Instant::getEpochSecond).orElse(0L)).append('|');
        final Div row = new Div();
        row.addClassName("automation-line");
        final Span dot = new Span("● ");
        dot.getStyle().setColor(connected ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
        final Anchor link = new Anchor(route, name);
        link.getStyle().setFontWeight("bold");
        row.add(dot, link, new Span(connected
                ? "  —  %d open order%s%s, last poll %s".formatted(open, open == 1 ? "" : "s",
                        unqueued > 0 ? " (%d not queued yet)".formatted(unqueued) : "",
                        lastPolled.map(this::ago).orElse("never (waits for the poll interval)"))
                : "  —  not connected"));
        // Aging: flag when the oldest unqueued order has been waiting a while
        oldestUnqueued.ifPresent(oldest -> {
            final long days = Duration.between(oldest, Instant.now()).toDays();
            if (days >= 1) {
                final Span age = new Span("  ⏰ oldest unqueued order is %dd old".formatted(days));
                age.getStyle().setColor(days >= 3 ? "var(--lumo-error-text-color)" : "var(--lumo-warning-text-color, #e8a33d)")
                        .setFontWeight("bold");
                row.add(age);
            }
        });
        lastError.ifPresent(err -> {
            final Span e = new Span("  ⚠ last poll failed: " + truncate(err, 120));
            e.getStyle().setColor("var(--lumo-error-text-color)");
            row.add(e);
        });
        return row;
    }

    private Div buildFulfillmentSection(final StringBuilder key) {
        final Div sec = section();
        sec.add(new H4("Recent"));
        sec.add(secondary("Finished:"));
        final List<PrintHistoryService.PrintJob> recent = new ArrayList<>(historyService.getJobs());
        recent.sort(Comparator.comparing(PrintHistoryService.PrintJob::ended).reversed());
        final List<PrintHistoryService.PrintJob> filtered = recent.stream().limit(4).toList();
        if (filtered.isEmpty()) {
            sec.add(secondary("No completed jobs recorded yet."));
        } else {
            filtered.forEach(j -> {
                key.append(j.printer()).append(j.ended()).append(j.result()).append('|');
                final Span result = new Span(("Finished".equals(j.result()) ? "✓ " : "✗ ") + j.result());
                result.getStyle().setColor("Finished".equals(j.result())
                        ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
                final long h = j.durationSeconds() / 3600;
                final long m = j.durationSeconds() % 3600 / 60;
                final String how = "auto-start".equals(j.trigger()) ? " · ⚙ auto"
                        : "queue".equals(j.trigger()) ? " · queue" : "";
                sec.add(line(result, new Span("  %s · %s · %dh %dm · %s%s".formatted(
                        j.printer(), j.file(), h, m, TIME_FMT.format(j.ended()), how))));
            });
        }

        // Recently queued sits here rather than on the Orders card, so that card is purely "what is still open"
        final List<Map.Entry<String, Instant>> queued = new ArrayList<>();
        tracking.queuedOrders("etsy").forEach((id, at) -> queued.add(Map.entry("etsy|" + id, at)));
        tracking.queuedOrders("ebay").forEach((id, at) -> queued.add(Map.entry("ebay|" + id, at)));
        queued.sort(Map.Entry.<String, Instant>comparingByValue().reversed());
        if (!queued.isEmpty()) {
            sec.add(secondary("Queued:"));
            queued.stream().limit(5).forEach(e -> {
                final String market = e.getKey().substring(0, e.getKey().indexOf('|'));
                final String id = e.getKey().substring(e.getKey().indexOf('|') + 1);
                sec.add(orderLine("✓", orderTitle(market, id), orderSubtitle(market, id),
                        ago(e.getValue()), "var(--lumo-secondary-text-color)"));
                key.append(e.getKey()).append(e.getValue().getEpochSecond()).append('|');
            });
        }
        key.append('§');
        return sec;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<BambuPrinters.PrinterDetail> sortedPrinters() {
        return printers.getPrintersDetail().stream()
                .sorted(Comparator.comparing(BambuPrinters.PrinterDetail::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static Div section() {
        final Div div = new Div();
        div.addClassName("ai-settings-section");
        return div;
    }

    /** A content row with the roomier .automation-line styling. */
    private static Div line(final Component... components) {
        final Div div = new Div(components);
        div.addClassName("automation-line");
        return div;
    }

    private static Div flexRow() {
        final Div row = new Div();
        row.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "10px").set("align-items", "center");
        return row;
    }

    private static Span chip(final String text, final String color) {
        final Span dot = new Span("● ");
        dot.getStyle().setColor(color);
        final Span chip = new Span(dot, new Span(text));
        chip.getStyle().set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "12px").set("padding", "2px 10px").set("white-space", "nowrap");
        return chip;
    }

    private static Span secondary(final String text) {
        final Span s = new Span(text);
        s.getStyle().setColor("var(--lumo-secondary-text-color)");
        return s;
    }

    private String ago(final Instant t) {
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