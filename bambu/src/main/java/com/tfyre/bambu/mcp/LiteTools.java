package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.FarmStatusService;
import com.tfyre.bambu.printer.NotificationService;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code /mcp-lite} server. Tool names differ from /mcp (the extension wants them unique across
 * servers), and say what they do in plain words for small models. The /mcp-lite server (docs/MCP-PLAN.md): six tools for small local models in Open WebUI. Plain-sentence
 * answers under a kilobyte, flat string arguments, no confirm step (small models set it on their own anyway) and
 * nothing that can't be undone: no stop, no remove, bed_cleared always keeps the AI bed check, start_print always
 * goes through it. Every change still raises an Agent Action notification so a person sees it.
 */
@Singleton
@McpServer("lite")
public class LiteTools {

    private static final Set<String> ALERT_EVENTS = Set.of("error", "fail", "failure_detected", "ams_retry",
            "auto_queue_skipped", "dispatch_blocked", "mcp_action");

    @Inject
    FarmStatusService farmStatus;
    @Inject
    BambuPrinters printers;
    @Inject
    BedClearService bedClear;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    NotificationService notifications;
    @Inject
    OrderTools orderTools;
    @Inject
    PrintTools printTools;
    @Inject
    McpAudit audit;

    @Tool(name = "farm_summary", description = "What every 3D printer in the farm is doing right now, one line each.",
            annotations = @Tool.Annotations(title = "Farm summary", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse farmSummary() {
        return lite("farm_summary", McpAudit.args(), () -> {
            final FarmStatusService.Status s = farmStatus.status();
            final StringBuilder b = new StringBuilder(s.headline()).append('.');
            for (final FarmStatusService.PrinterLine l : s.printers()) {
                b.append('\n').append(l.name()).append(": ").append(l.text());
                if (l.inJob() && l.percent() > 0) {
                    b.append(" (").append(l.percent()).append("%)");
                }
            }
            return ToolResponse.success(b.toString());
        });
    }

    @Tool(name = "open_orders", description = "Open Etsy and eBay orders and whether each is printed, printing or waiting.",
            annotations = @Tool.Annotations(title = "Open orders", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse openOrders() {
        return lite("open_orders", McpAudit.args(), () -> {
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> orders = (List<Map<String, Object>>) orderTools.buildOrders(null, null).get("orders");
            if (orders.isEmpty()) {
                return ToolResponse.success("No open orders.");
            }
            final StringBuilder b = new StringBuilder("%d open order(s):".formatted(orders.size()));
            for (final Map<String, Object> o : orders) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> lines = (List<Map<String, Object>>) o.get("lines");
                final String items = String.join("; ", lines.stream()
                        .map(l -> l.get("qty") + "x " + FarmTools.truncate(String.valueOf(l.get("title")), 60)).toList());
                b.append("\n- ").append(o.get("market")).append(' ').append(o.get("id")).append(": ").append(items)
                        .append(" - ").append(stage(String.valueOf(o.get("stage")))).append(", printed ").append(o.get("printed"));
            }
            return ToolResponse.success(b.toString());
        });
    }

    @Tool(name = "print_part", description = """
            Start printing a part from the library, e.g. part="mk8 cupholder". The printer is picked automatically \
            unless one is named. The AI camera checks the bed is empty before it starts.""",
            annotations = @Tool.Annotations(title = "Start print", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    ToolResponse startPrint(
            @ToolArg(description = "Words from the part name, e.g. mk8 cupholder") final String part,
            @ToolArg(description = "Printer name like P1S-2, or any", required = false, defaultValue = "any") final String printer) {
        return lite("print_part", McpAudit.args("part", part, "printer", printer),
                () -> printTools.body(part, printer, null, 1, true, true));
    }

    @Tool(name = "clear_bed", description = """
            Tell the farm a printer's finished part has been taken off the bed. The camera still checks the bed \
            before the next print starts.""",
            annotations = @Tool.Annotations(title = "Bed cleared", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse bedCleared(@ToolArg(description = "Printer name like P1S-2") final String printer) {
        return lite("clear_bed", McpAudit.args("printer", printer), () -> {
            final Optional<BambuPrinter> p = find(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final String name = p.get().getName();
            if (!bedClear.markCleared(name, "MCP lite agent", false)) {
                return ToolResponse.error(name + " is printing - its bed can't be cleared now.");
            }
            dispatch.passNow();
            notifications.notifyEvent("mcp_action", name, "Agent (local model) marked %s's bed cleared - camera check still runs".formatted(name));
            return ToolResponse.success("Marked %s's bed cleared. The camera checks it before the next print.".formatted(name));
        });
    }

    @Tool(name = "pause_printer", description = "Pause a printer's current print. It can be resumed from the printer or the app.",
            annotations = @Tool.Annotations(title = "Pause print", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse pausePrint(@ToolArg(description = "Printer name like P1S-2") final String printer) {
        return lite("pause_printer", McpAudit.args("printer", printer), () -> {
            final Optional<BambuPrinter> p = find(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final BambuPrinter pr = p.get();
            if (!pr.getGCodeState().isPrinting()) {
                return ToolResponse.error("%s is not printing right now.".formatted(pr.getName()));
            }
            pr.commandControl(BambuConst.CommandControl.PAUSE);
            notifications.notifyEvent("mcp_action", pr.getName(), "Agent (local model) paused %s".formatted(pr.getName()));
            return ToolResponse.success("Paused %s.".formatted(pr.getName()));
        });
    }

    @Tool(name = "recent_alerts", description = "Problems and alerts from the farm in the last few hours.",
            annotations = @Tool.Annotations(title = "Recent alerts", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse recentAlerts(@ToolArg(description = "How many hours back (default 6)", required = false, defaultValue = "6") final int hours) {
        return lite("recent_alerts", McpAudit.args("hours", hours), () -> {
            final OffsetDateTime since = OffsetDateTime.now().minusHours(Math.max(1, Math.min(48, hours)));
            final List<String> lines = new ArrayList<>();
            for (final NotificationService.LogEntry e : notifications.getLog()) {
                if (!ALERT_EVENTS.contains(e.event()) || lines.size() >= 10) {
                    continue;
                }
                try {
                    if (OffsetDateTime.parse(e.timestamp()).isBefore(since)) {
                        continue;
                    }
                } catch (RuntimeException ex) {
                    continue;
                }
                lines.add("- %s %s: %s".formatted(e.timestamp().substring(11, 16), e.printer(), FarmTools.truncate(e.message(), 140)));
            }
            return ToolResponse.success(lines.isEmpty() ? "No alerts in the last %d hours.".formatted(hours)
                    : "Alerts, newest first:\n" + String.join("\n", lines));
        });
    }

    // ---------------------------------------------------------------------------------------------------------

    private ToolResponse lite(final String tool, final Map<String, Object> args, final Supplier<ToolResponse> body) {
        return audit.run("lite:" + tool, args, () -> {
            final McpAccessFilter.Access a = audit.caller().orElse(null);
            if (a != McpAccessFilter.Access.LITE && a != McpAccessFilter.Access.CONTROL) {
                return ToolResponse.error("this server needs the lite or control token");
            }
            return body.get();
        });
    }

    private Optional<BambuPrinter> find(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String want = name.trim().replace(' ', '-');
        return printers.getPrintersDetail().stream()
                .filter(d -> d.name().equalsIgnoreCase(want) || d.name().equalsIgnoreCase(name.trim()))
                .map(BambuPrinters.PrinterDetail::printer).findFirst();
    }

    private ToolResponse unknown(final String printer) {
        return ToolResponse.error("There is no printer called '%s'. Printers: %s.".formatted(printer, String.join(", ",
                printers.getPrintersDetail().stream().map(BambuPrinters.PrinterDetail::name).sorted().toList())));
    }

    static String stage(final String stage) {
        return switch (stage) {
            case "READY" -> "ready to ship";
            case "PRINTING" -> "printing";
            case "WAITING" -> "waiting for a printer";
            case "NOT_QUEUED" -> "not queued (needs a person)";
            case "FAILED" -> "a print failed";
            default -> stage.toLowerCase();
        };
    }
}
