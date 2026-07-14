package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.OllamaService;
import com.tfyre.bambu.printer.PrintAiService;
import com.tfyre.bambu.printer.PrintQueueService;
import com.tfyre.bambu.BambuConfig;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
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
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Print Queue — one section per printer showing its queued jobs, with the same "Start Next" action (AI
 * bed-clear gate included) available from a dashboard card. Lets you see and manage every printer's queue in
 * one place instead of opening each card's queue dialog individually.
 *
 * Accessible from the sidebar. Admin only (same as Batch Print / SD Card, since this can start prints).
 */
@Route(value = "print-queue", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Print Queue")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class PrintQueueView extends VerticalLayout implements NotificationHelper {

    @Inject
    BambuPrinters printers;
    @Inject
    PrintQueueService queueService;
    @Inject
    PrintAiService aiService;
    @Inject
    AutoStartService autoStartService;
    @Inject
    BambuConfig config;
    @Inject
    ScheduledExecutorService ses;

    /** One refresh runnable per printer section, run together on every telemetry tick (same pattern as PushDiv). */
    private final List<Runnable> tickers = new ArrayList<>();
    private Optional<ScheduledFuture<?>> future = Optional.empty();

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        tickers.clear();
        addClassName("ai-settings-view");
        setPadding(true);
        setSpacing(true);

        add(new H3("Print Queue"));

        final List<BambuPrinters.PrinterDetail> details = printers.getPrintersDetail().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();

        if (details.isEmpty()) {
            add(new Span("No printers configured."));
            return;
        }

        details.forEach(detail -> add(buildPrinterSection(detail)));

        // Keep state badges, Start Next buttons and queue lists live while the page is open - a printer
        // finishing a print should enable its Start Next button without needing to re-navigate.
        final UI ui = attachEvent.getUI();
        future.ifPresent(f -> f.cancel(true));
        future = Optional.of(ses.scheduleAtFixedRate(
                () -> ui.access(() -> tickers.forEach(Runnable::run)),
                0, config.refreshInterval().getSeconds(), TimeUnit.SECONDS));
    }

    @Override
    protected void onDetach(final DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        future.ifPresent(f -> f.cancel(true));
        future = Optional.empty();
    }

    private Div buildPrinterSection(final BambuPrinters.PrinterDetail detail) {
        final Div section = new Div();
        section.addClassName("ai-settings-section");

        final HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setDefaultVerticalComponentAlignment(FlexLayout.Alignment.BASELINE);
        final Span stateBadge = new Span();
        stateBadge.getStyle().setColor("var(--lumo-secondary-text-color)").setFontWeight("bold");
        titleRow.add(new H4(detail.name()), stateBadge);
        section.add(titleRow);

        final Div list = new Div();
        section.add(list);

        final Button startNext = new Button(new Icon(VaadinIcon.TIME_FORWARD));
        startNext.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        section.add(startNext);

        // AI-gated auto-start: opt-in per printer, with a live status line showing the watcher's last decision
        final Checkbox autoStart = new Checkbox("Auto-start next when bed is clear (AI-checked)");
        autoStart.setValue(autoStartService.isEnabled(detail.name()));
        autoStart.setTooltipText("When idle with jobs queued, run the AI bed-clear check and start the next job "
                + "automatically if the bed is confirmed clear. Fails closed: no AI answer = no start.");
        final Span autoStartStatus = new Span();
        autoStartStatus.getStyle().setColor("var(--lumo-secondary-text-color)");
        autoStart.addValueChangeListener(e -> {
            autoStartService.setEnabled(detail.name(), Boolean.TRUE.equals(e.getValue()));
            autoStartStatus.setText("auto-start: " + autoStartService.getStatus(detail.name()));
        });
        final HorizontalLayout autoStartRow = new HorizontalLayout(autoStart, autoStartStatus);
        autoStartRow.setDefaultVerticalComponentAlignment(FlexLayout.Alignment.CENTER);
        section.add(autoStartRow);

        // Self-referencing refresh callback: reloads the state badge, the queue list (whose remove buttons
        // also call back into this same refresh), and the Start Next button's label/enabled state. Also runs
        // on every telemetry tick (see onAttach), so the queue list only rebuilds its DOM when the queue
        // actually changed - badge/button updates are no-ops client-side when values are unchanged.
        final AtomicReference<List<PrintQueueService.QueueEntry>> shown = new AtomicReference<>();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            stateBadge.setText("● %s".formatted(detail.printer().getGCodeState().getDescription()));
            final List<PrintQueueService.QueueEntry> queue = queueService.getQueue(detail.name());
            if (!queue.equals(shown.get())) {
                shown.set(queue);
                reloadQueueList(list, detail, queue, refresh[0]);
            }
            updateStartNextButton(startNext, detail);
            autoStartStatus.setText("auto-start: " + autoStartService.getStatus(detail.name()));
        };
        startNext.addClickListener(l -> doStartNext(detail, refresh[0]));
        refresh[0].run();
        tickers.add(refresh[0]);

        return section;
    }

    private void reloadQueueList(final Div list, final BambuPrinters.PrinterDetail detail,
            final List<PrintQueueService.QueueEntry> queue, final Runnable refresh) {
        list.removeAll();
        if (queue.isEmpty()) {
            final Span empty = new Span("Queue is empty — add jobs from Batch Print");
            empty.getStyle().setColor("var(--lumo-secondary-text-color)");
            list.add(empty);
            return;
        }
        for (int i = 0; i < queue.size(); i++) {
            final PrintQueueService.QueueEntry entry = queue.get(i);
            final Button remove = new Button(new Icon(VaadinIcon.TRASH), l -> {
                queueService.removeEntry(detail.name(), entry);
                refresh.run();
            });
            remove.setTooltipText("Remove from queue");
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            final HorizontalLayout row = new HorizontalLayout(
                    new Span("%d. %s (plate %d)".formatted(i + 1, entry.command().filename(), entry.command().plateId())),
                    remove);
            row.setDefaultVerticalComponentAlignment(FlexLayout.Alignment.CENTER);
            row.setWidthFull();
            row.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
            list.add(row);
        }
    }

    private void updateStartNextButton(final Button startNext, final BambuPrinters.PrinterDetail detail) {
        final BambuPrinter printer = detail.printer();
        final int size = queueService.size(detail.name());
        if (size == 0) {
            startNext.setVisible(false);
            return;
        }
        final boolean eligible = printer.getGCodeState().isReady() && !printer.isBlocked();
        final String file = queueService.peek(detail.name())
                .map(e -> e.command().filename())
                .orElse("");
        final String shortFile = file.substring(file.lastIndexOf(BambuConst.PATHSEP) + 1);
        startNext.setText(eligible
                ? "Start Next (%d queued): %s".formatted(size, shortFile)
                : "Start Next (%d queued) - printer is %s".formatted(size, printer.getGCodeState().getDescription().toLowerCase()));
        startNext.setVisible(true);
        startNext.setEnabled(eligible);
    }

    private void doStartNext(final BambuPrinters.PrinterDetail detail, final Runnable refresh) {
        final String printerName = detail.name();
        queueService.peek(printerName).ifPresentOrElse(entry -> {
            if (aiService.isEnabled()) {
                showNotification("%s: checking bed…".formatted(printerName));
                final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
                aiService.checkBedClear(printerName, "start-next").thenAccept(result ->
                        ui.ifPresent(u -> u.access(() -> {
                            if (result.isEmpty()) {
                                // no snapshot yet or Ollama error - fall through to manual confirmation
                                confirmAndStartNext(detail, entry, "", refresh);
                                return;
                            }
                            final OllamaService.AiResult aiResult = result.get();
                            if (aiResult.positive()) {
                                confirmAndStartNext(detail, entry,
                                        "\n\n✓ AI: bed appears clear — " + truncateAi(aiResult.description()), refresh);
                            } else {
                                YesNoCancelDialog.show(
                                        "%s — AI detected: bed may not be clear\n\n%s\n\nOverride and start anyway?"
                                                .formatted(printerName, truncateAi(aiResult.description())),
                                        ync -> {
                                            if (ync.isConfirmed()) {
                                                performStartNext(detail, refresh);
                                            }
                                        });
                            }
                        })));
            } else {
                confirmAndStartNext(detail, entry, "", refresh);
            }
        }, () -> showError("%s: queue is empty".formatted(printerName)));
    }

    private void confirmAndStartNext(final BambuPrinters.PrinterDetail detail, final PrintQueueService.QueueEntry entry,
            final String aiNote, final Runnable refresh) {
        YesNoCancelDialog.show("%s - Start next queued print [%s] plate %d\n\nIs the bed clear?%s"
                .formatted(detail.name(), entry.command().filename(), entry.command().plateId(), aiNote),
                ync -> {
                    if (ync.isConfirmed()) {
                        performStartNext(detail, refresh);
                    }
                });
    }

    private void performStartNext(final BambuPrinters.PrinterDetail detail, final Runnable refresh) {
        final Optional<UI> ui = Optional.ofNullable(UI.getCurrent());
        queueService.startNext(detail.name(),
                () -> ui.ifPresent(u -> u.access(() -> {
                    showNotification("%s: print started".formatted(detail.name()));
                    refresh.run();
                })),
                error -> ui.ifPresent(u -> u.access(() -> showError(error))));
    }

    private static String truncateAi(final String s) {
        return s.length() <= 150 ? s : s.substring(0, 150) + "…";
    }

}
