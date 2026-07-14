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
import com.tfyre.bambu.printer.PrintQueueService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
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
 * Three tabs: <b>Overview</b> (this class - a live pipeline dashboard from "order came in" through
 * auto-queue, per-printer queues + auto-start, AI-watched printing, to finished jobs), <b>Print Queue</b>
 * (the embedded {@link PrintQueueView}) and <b>AI Settings</b> (the embedded {@link AiSettingsView}).
 * The old {@code /print-queue} and {@code /ai-settings} routes still work as deep links; this page replaces
 * their sidebar entries.
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
    Instance<PrintQueueView> printQueueViewInstance;
    @Inject
    Instance<AiSettingsView> aiSettingsViewInstance;
    @Inject
    Instance<MappingsView> mappingsViewInstance;
    @Inject
    ScheduledExecutorService ses;

    private final Tab overviewTab = new Tab("Overview");
    private final Tab mappingsTab = new Tab("Mappings");
    private final Tab queueTab = new Tab("Print Queue");
    private final Tab aiTab = new Tab("AI Settings");
    private final Tabs tabs = new Tabs(overviewTab, mappingsTab, queueTab, aiTab);
    private final Div tabContent = new Div();
    private final Div overview = new Div();

    /** Embedded tab views, created lazily on first open and reused (they rebuild themselves on attach). */
    private PrintQueueView queueView;
    private AiSettingsView aiView;
    private MappingsView mappingsView;

    private String overviewKey = "";
    private Optional<ScheduledFuture<?>> future = Optional.empty();
    /** Fulfillment card filter: "all", "auto" (auto-started prints only) or "manual". */
    private String fulfillmentFilter = "all";

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
        if (selected == queueTab) {
            if (queueView == null) {
                queueView = printQueueViewInstance.get();
            }
            tabContent.add(queueView);
        } else if (selected == mappingsTab) {
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
        sections.add(buildControlsSection(key));
        sections.add(buildOrdersSection(key));
        sections.add(buildQueueSection(key));
        sections.add(buildPrintingSection(key));
        sections.add(buildFulfillmentSection(key));
        if (overviewKey.equals(key.toString())) {
            return;
        }
        overviewKey = key.toString();
        overview.removeAll();
        sections.forEach(overview::add);
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
        final int queuedJobs = details.stream().mapToInt(d -> queueService.size(d.name())).sum();
        final long printing = details.stream().filter(d -> d.printer().getGCodeState().isPrinting()).count();
        final long autoStartOn = details.stream().filter(d -> autoStartService.isEnabled(d.name())).count();
        final boolean aq = autoQueueService.isEnabled();
        final boolean aiConfigured = ollama.isEnabled();
        final boolean ai = aiService.isEnabled();
        key.append(openEtsy).append('|').append(openEbay).append('|').append(unqueued).append('|')
                .append(queuedJobs).append('|').append(printing).append('|').append(autoStartOn).append('|')
                .append(aq).append('|').append(ai).append('§');

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
        final Button asBtn = bigToggle("Auto-Start %d/%d".formatted(autoStartOn, details.size()), autoStartOn > 0,
                "Per-printer setting - click to manage on the Print Queue tab");
        asBtn.addClickListener(e -> tabs.setSelectedTab(queueTab));
        controls.add(aqBtn, aiBtn, asBtn);
        strip.add(controls);

        final Div row = flexRow();
        row.getStyle().set("margin-top", "10px");
        row.add(chip("%d open order%s%s".formatted(openEtsy + openEbay, openEtsy + openEbay == 1 ? "" : "s",
                unqueued > 0 ? " (%d not queued)".formatted(unqueued) : ""),
                unqueued > 0 ? "var(--lumo-warning-color, #e8a33d)" : "var(--lumo-success-color)"));
        row.add(chip("%d job%s queued".formatted(queuedJobs, queuedJobs == 1 ? "" : "s"), "var(--lumo-primary-color)"));
        row.add(chip("%d printing".formatted(printing), "var(--lumo-primary-color)"));
        strip.add(row);

        final boolean lightsOut = aq && ai && autoStartOn == details.size() && !details.isEmpty();
        final Span pipeline = new Span(lightsOut
                ? "✓ Fully automatic: mapped orders go from purchase to printing with zero clicks."
                : "Pipeline: order → auto-queue%s → queue → auto-start%s → AI-watched printing → manual shipping."
                        .formatted(aq ? "" : " (OFF - manual Queue Print)",
                                autoStartOn == 0 ? " (OFF everywhere - manual Start Next)" : ""));
        pipeline.getStyle().setColor(lightsOut ? "var(--lumo-success-text-color)" : "var(--lumo-secondary-text-color)");
        final Div pipelineLine = new Div(pipeline);
        pipelineLine.addClassName("automation-line");
        strip.add(pipelineLine);
        key.append(lightsOut).append('§');
        return strip;
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
        sec.add(new H4("1 · Orders in"));

        sec.add(marketRow("Etsy", etsyOauth.isConnected(), etsyPolling.getReceipts().size(),
                (int) etsyPolling.getReceipts().stream().filter(r -> tracking.queuedAt("etsy", String.valueOf(r.receiptId())).isEmpty()).count(),
                etsyPolling.getLastPolled(), etsyPolling.getLastError(), "etsy-orders", key));
        sec.add(marketRow("eBay", ebayOauth.isConnected(), ebayPolling.getOrders().size(),
                (int) ebayPolling.getOrders().stream().filter(o -> tracking.queuedAt("ebay", o.orderId()).isEmpty()).count(),
                ebayPolling.getLastPolled(), ebayPolling.getLastError(), "ebay-orders", key));

        // Recently queued orders (either by auto-queue or manually), newest first
        final List<Map.Entry<String, Instant>> recent = new ArrayList<>();
        tracking.queuedOrders("etsy").forEach((id, at) -> recent.add(Map.entry("Etsy #" + id, at)));
        tracking.queuedOrders("ebay").forEach((id, at) -> recent.add(Map.entry("eBay " + id, at)));
        recent.sort(Map.Entry.<String, Instant>comparingByValue().reversed());
        if (!recent.isEmpty()) {
            final Div list = new Div();
            list.add(secondary("Recently queued orders:"));
            recent.stream().limit(5).forEach(e -> {
                list.add(line(new Span("✓ %s — queued %s (%s)".formatted(e.getKey(),
                        TIME_FMT.format(e.getValue().atZone(ZoneId.systemDefault())), ago(e.getValue())))));
                key.append(e.getKey()).append(e.getValue().getEpochSecond()).append('|');
            });
            sec.add(list);
        }
        key.append('§');
        return sec;
    }

    private Div marketRow(final String name, final boolean connected, final int open, final int unqueued,
            final Optional<Instant> lastPolled, final Optional<String> lastError, final String route, final StringBuilder key) {
        key.append(name).append(connected).append(open).append(unqueued)
                .append(lastPolled.map(Instant::getEpochSecond).orElse(0L)).append(lastError.orElse("")).append('|');
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
        lastError.ifPresent(err -> {
            final Span e = new Span("  ⚠ last poll failed: " + truncate(err, 120));
            e.getStyle().setColor("var(--lumo-error-text-color)");
            row.add(e);
        });
        return row;
    }

    private Div buildQueueSection(final StringBuilder key) {
        final Div sec = section();
        sec.add(new H4("2 · Queue & auto-start"));
        for (final BambuPrinters.PrinterDetail detail : sortedPrinters()) {
            final String name = detail.name();
            final BambuConst.GCodeState state = detail.printer().getGCodeState();
            final int size = queueService.size(name);
            final String next = queueService.peek(name)
                    .map(q -> q.command().filename())
                    .map(f -> f.substring(f.lastIndexOf(BambuConst.PATHSEP) + 1))
                    .orElse("—");
            final String auto = autoStartService.getStatus(name);
            key.append(name).append(state).append(size).append(next).append(auto).append('|');

            final Div row = new Div();
            row.addClassName("automation-line");
            final Span n = new Span(name + "  ");
            n.getStyle().setFontWeight("bold");
            final Span st = new Span("● %s  ".formatted(state.getDescription()));
            st.getStyle().setColor(state.isPrinting() ? "var(--lumo-primary-text-color)"
                    : state.isReady() ? "var(--lumo-success-text-color)" : "var(--lumo-contrast-60pct)");
            final Span q = new Span(size == 0 ? "queue empty  " : "%d queued, next: %s  ".formatted(size, next));
            final Span a = new Span("auto-start: " + auto);
            a.getStyle().setColor("off".equals(auto) ? "var(--lumo-contrast-50pct)"
                    : auto.startsWith("blocked") || auto.startsWith("paused") ? "var(--lumo-error-text-color)"
                    : auto.startsWith("auto-started") ? "var(--lumo-success-text-color)"
                    : "var(--lumo-secondary-text-color)");
            row.add(n, st, q, a);
            sec.add(row);
        }
        sec.add(secondary("Manage queues and the per-printer auto-start toggle on the Print Queue tab."));
        key.append('§');
        return sec;
    }

    private Div buildPrintingSection(final StringBuilder key) {
        final Div sec = section();
        sec.add(new H4("3 · Printing & AI watch"));

        final List<BambuPrinters.PrinterDetail> printing = sortedPrinters().stream()
                .filter(d -> d.printer().getGCodeState().isPrinting())
                .toList();
        if (printing.isEmpty()) {
            sec.add(secondary("Nothing printing right now."));
            key.append("idle");
        } else {
            for (final BambuPrinters.PrinterDetail detail : printing) {
                final BambuPrinter printer = detail.printer();
                final Optional<PrintAiService.AiCheckResult> last = aiService.getLastResult(detail.name());
                final String file = printer.getLastPrintFile().orElse("(unknown file)");
                key.append(detail.name()).append(file).append(last.map(r -> r.description() + r.checkedAt()).orElse("")).append('|');
                final Div row = new Div();
                row.addClassName("automation-line");
                final Span n = new Span(detail.name() + "  ");
                n.getStyle().setFontWeight("bold");
                row.add(n, new Span("printing %s  ".formatted(file)));
                last.ifPresentOrElse(r -> {
                    final Span chip = new Span("%s AI: %s (%s)".formatted(r.good() ? "✓" : "✗",
                            truncate(r.description(), 80), ago(r.checkedAt())));
                    chip.getStyle().setColor(r.good() ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
                    row.add(chip);
                }, () -> row.add(secondary("no AI check yet this print")));
                sec.add(row);
            }
        }

        // Recent AI problems across the farm
        final List<PrintAiService.CheckRecord> problems = aiService.getHistory().stream()
                .filter(r -> Boolean.FALSE.equals(r.good()))
                .limit(3)
                .toList();
        if (!problems.isEmpty()) {
            sec.add(secondary("Recent AI flags:"));
            problems.forEach(r -> {
                key.append(r.printer()).append(r.at().getEpochSecond()).append('|');
                sec.add(line(new Span("⚠ %s · %s · %s — %s".formatted(
                        TIME_FMT.format(r.at().atZone(ZoneId.systemDefault())), r.printer(), r.checkType(),
                        truncate(r.description(), 120)))));
            });
        }
        sec.add(secondary("Failure checks run every %s while printing; snapshots, history and prompts are on the AI Settings tab."
                .formatted(config.ollama().failureCheckInterval())));
        key.append('§');
        return sec;
    }

    private Div buildFulfillmentSection(final StringBuilder key) {
        final Div sec = section();
        sec.add(new H4("4 · Fulfillment"));
        key.append(fulfillmentFilter).append('|');

        // Filter: everything / only auto-started prints / only manually started ones
        final Div filters = flexRow();
        filters.add(filterButton("All", "all"), filterButton("Auto-started", "auto"), filterButton("Manual", "manual"));
        sec.add(filters);

        final List<PrintHistoryService.PrintJob> recent = new ArrayList<>(historyService.getJobs());
        recent.sort(Comparator.comparing(PrintHistoryService.PrintJob::ended).reversed());
        final List<PrintHistoryService.PrintJob> filtered = recent.stream()
                .filter(j -> switch (fulfillmentFilter) {
                    case "auto" -> "auto-start".equals(j.trigger());
                    case "manual" -> !"auto-start".equals(j.trigger());
                    default -> true;
                })
                .limit(5)
                .toList();
        if (filtered.isEmpty()) {
            sec.add(secondary("auto".equals(fulfillmentFilter)
                    ? "No auto-started prints recorded yet (prints started before this feature aren't tagged)."
                    : "No completed jobs recorded yet."));
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
        sec.add(secondary("Shipping and marking orders fulfilled stays manual on Etsy/eBay - this app never writes back to the marketplaces."));
        key.append('§');
        return sec;
    }

    private Button filterButton(final String label, final String value) {
        final Button b = new Button(label);
        b.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
        if (value.equals(fulfillmentFilter)) {
            b.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        } else {
            b.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        }
        b.addClickListener(e -> {
            fulfillmentFilter = value;
            forceRefresh();
        });
        return b;
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
