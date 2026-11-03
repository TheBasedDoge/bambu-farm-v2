package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.NotificationService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase 2 control tools (docs/MCP-PLAN.md). Every one needs the control token; {@link ControlToolFilter} hides them
 * from read-token sessions and each tool checks again. Anything that can spoil a print or a bed needs
 * {@code confirm=true}: without it the tool only says what it would do. Each change is audited and raises an
 * {@code mcp_action} notification. These call the same service methods as the UI buttons.
 */
@Singleton
public class ControlTools {

    /** Tool names, for {@link ControlToolFilter}. */
    static final Set<String> NAMES = Set.of("pause_print", "resume_print", "stop_print", "bed_cleared", "set_light",
            "set_auto_start", "retry_pool_job", "remove_pool_job", "retry_auto_queue", "label_frame");

    @Inject
    BambuPrinters printers;
    @Inject
    BedClearService bedClear;
    @Inject
    AutoStartService autoStart;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    EtsyOrderPollingService etsy;
    @Inject
    EbayOrderPollingService ebay;
    @Inject
    NotificationService notifications;
    @Inject
    McpAudit audit;

    // ---------------------------------------------------------------------------------------------------------
    // print control
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "pause_print", description = """
            Pauses a running print (same as the Pause button). Without confirm=true it only reports what it would \
            pause. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Pause print", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse pausePrint(
            @ToolArg(description = "Printer name, e.g. P1S-3") final String printer,
            @ToolArg(description = "true to actually pause", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("pause_print", McpAudit.args("printer", printer, "confirm", confirm), () -> control(() -> {
            final Optional<BambuPrinter> p = printer(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final BambuPrinter pr = p.get();
            if (!pr.getGCodeState().isPrinting()) {
                return ToolResponse.error("%s is not printing (%s)".formatted(pr.getName(), pr.getGCodeState()));
            }
            if (!confirm) {
                return preview("pause %s (%s)".formatted(pr.getName(), job(pr)));
            }
            pr.commandControl(BambuConst.CommandControl.PAUSE);
            return done(pr.getName(), "paused %s (%s)".formatted(pr.getName(), job(pr)));
        }));
    }

    @Tool(name = "resume_print", description = """
            Resumes a paused print (same as the Resume button). Check why it paused first (farm_status shows HMS \
            errors). Without confirm=true it only reports what it would resume. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Resume print", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse resumePrint(
            @ToolArg(description = "Printer name, e.g. P1S-3") final String printer,
            @ToolArg(description = "true to actually resume", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("resume_print", McpAudit.args("printer", printer, "confirm", confirm), () -> control(() -> {
            final Optional<BambuPrinter> p = printer(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final BambuPrinter pr = p.get();
            if (pr.getGCodeState() != BambuConst.GCodeState.PAUSE) {
                return ToolResponse.error("%s is not paused (%s)".formatted(pr.getName(), pr.getGCodeState()));
            }
            if (!confirm) {
                final String hms = pr.getActiveHmsErrors().isEmpty() ? "" : "; active errors: " + String.join(" · ", pr.getActiveHmsErrors());
                return preview("resume %s (%s)%s".formatted(pr.getName(), job(pr), hms));
            }
            pr.commandControl(BambuConst.CommandControl.RESUME);
            return done(pr.getName(), "resumed %s (%s)".formatted(pr.getName(), job(pr)));
        }));
    }

    @Tool(name = "stop_print", description = """
            STOPS a running or paused print (same as the Stop button). Cannot be undone - the part is lost. Only \
            when the user has asked for it. Without confirm=true it only reports what it would stop. Needs the \
            control token.""",
            annotations = @Tool.Annotations(title = "Stop print", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    ToolResponse stopPrint(
            @ToolArg(description = "Printer name, e.g. P1S-3") final String printer,
            @ToolArg(description = "true to actually stop the print", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("stop_print", McpAudit.args("printer", printer, "confirm", confirm), () -> control(() -> {
            final Optional<BambuPrinter> p = printer(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final BambuPrinter pr = p.get();
            final BambuConst.GCodeState state = pr.getGCodeState();
            if (!state.isPrinting() && state != BambuConst.GCodeState.PAUSE) {
                return ToolResponse.error("%s has no print to stop (%s)".formatted(pr.getName(), state));
            }
            if (!confirm) {
                return preview("STOP %s (%s). This cannot be undone".formatted(pr.getName(), job(pr)));
            }
            pr.commandControl(BambuConst.CommandControl.STOP);
            return done(pr.getName(), "STOPPED %s (%s)".formatted(pr.getName(), job(pr)));
        }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // bed, light, auto-start
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "bed_cleared", description = """
            Marks a finished or failed printer's bed as cleared, so the dispatcher can send it the next job right \
            away. By default the AI camera check still runs before that job starts. skip_camera_check=true is the \
            same as a person pressing "Bed cleared" - only when the user says they removed the part themselves. \
            Without confirm=true it only reports what it would do. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Bed cleared", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse bedCleared(
            @ToolArg(description = "Printer name, e.g. P1S-3") final String printer,
            @ToolArg(description = "Skip the AI bed check for the next start (only on the user's word)", required = false,
                    defaultValue = "false") final boolean skipCameraCheck,
            @ToolArg(description = "true to actually mark it", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("bed_cleared", McpAudit.args("printer", printer, "skipCameraCheck", skipCameraCheck, "confirm", confirm),
                () -> control(() -> {
                    final Optional<BambuPrinter> p = printer(printer);
                    if (p.isEmpty()) {
                        return unknown(printer);
                    }
                    final BambuPrinter pr = p.get();
                    final String check = skipCameraCheck ? "WITHOUT the AI bed check" : "after the AI bed check";
                    if (!confirm) {
                        return preview("mark %s's bed cleared (%s); its next job can start %s".formatted(pr.getName(),
                                pr.getGCodeState(), check));
                    }
                    if (!bedClear.markCleared(pr.getName(), "MCP agent", skipCameraCheck)) {
                        return ToolResponse.error("%s is printing (%s) - nothing to clear".formatted(pr.getName(), pr.getGCodeState()));
                    }
                    dispatch.passNow();
                    return done(pr.getName(), "marked %s's bed cleared; next job starts %s".formatted(pr.getName(), check));
                }));
    }

    @Tool(name = "set_light", description = "Turns a printer's chamber light on or off. Needs the control token.",
            annotations = @Tool.Annotations(title = "Chamber light", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse setLight(
            @ToolArg(description = "Printer name, e.g. P1S-3") final String printer,
            @ToolArg(description = "true = on, false = off") final boolean on) {
        return audit.run("set_light", McpAudit.args("printer", printer, "on", on), () -> control(() -> {
            final Optional<BambuPrinter> p = printer(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            p.get().commandLight(on ? BambuConst.LightMode.ON : BambuConst.LightMode.OFF);
            return ToolResponse.success("%s light %s".formatted(p.get().getName(), on ? "on" : "off"));
        }));
    }

    @Tool(name = "set_auto_start", description = """
            Switches auto-start for one printer, or the farm-wide master switch with printer=all. With auto-start \
            off nothing starts by itself on that printer (or anywhere). Without confirm=true it only reports what \
            it would change. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Auto-start", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse setAutoStart(
            @ToolArg(description = "Printer name, or all for the farm-wide switch") final String printer,
            @ToolArg(description = "true = on, false = off") final boolean on,
            @ToolArg(description = "true to actually change it", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("set_auto_start", McpAudit.args("printer", printer, "on", on, "confirm", confirm), () -> control(() -> {
            if ("all".equalsIgnoreCase(String.valueOf(printer).trim())) {
                if (!confirm) {
                    return preview("turn the farm-wide auto-start switch %s (now %s)".formatted(on ? "ON" : "OFF",
                            autoStart.isGloballyEnabled() ? "on" : "off"));
                }
                autoStart.setGloballyEnabled(on);
                return done(null, "turned the farm-wide auto-start switch " + (on ? "ON" : "OFF"));
            }
            final Optional<BambuPrinter> p = printer(printer);
            if (p.isEmpty()) {
                return unknown(printer);
            }
            final String name = p.get().getName();
            if (!confirm) {
                return preview("turn auto-start %s for %s (now %s)".formatted(on ? "ON" : "OFF", name,
                        autoStart.isEnabled(name) ? "on" : "off"));
            }
            autoStart.setEnabled(name, on);
            return done(name, "turned auto-start %s for %s".formatted(on ? "ON" : "OFF", name));
        }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // pool and orders
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "retry_pool_job", description = """
            Clears a parked pool job's failure state so the dispatcher tries it again on the next pass. Take the \
            id from dispatch_pool. Needs the control token.""",
            annotations = @Tool.Annotations(title = "Retry pool job", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse retryPoolJob(@ToolArg(description = "Job id from dispatch_pool") final String id) {
        return audit.run("retry_pool_job", McpAudit.args("id", id), () -> control(() -> {
            final Optional<DispatchQueueService.PendingJob> job = poolJob(id);
            if (job.isEmpty()) {
                return ToolResponse.error("no pool job " + id + " (see dispatch_pool)");
            }
            dispatch.retry(job.get().id());
            dispatch.passNow();
            return done(null, "retrying pool job " + describe(job.get()));
        }));
    }

    @Tool(name = "remove_pool_job", description = """
            REMOVES a job from the dispatch pool: it will never print, and its order's expected count drops by one. \
            Only when the user asks. Without confirm=true it only reports what it would remove. Needs the control \
            token.""",
            annotations = @Tool.Annotations(title = "Remove pool job", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    ToolResponse removePoolJob(
            @ToolArg(description = "Job id from dispatch_pool") final String id,
            @ToolArg(description = "true to actually remove it", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("remove_pool_job", McpAudit.args("id", id, "confirm", confirm), () -> control(() -> {
            final Optional<DispatchQueueService.PendingJob> job = poolJob(id);
            if (job.isEmpty()) {
                return ToolResponse.error("no pool job " + id + " (see dispatch_pool)");
            }
            if (!confirm) {
                return preview("REMOVE pool job " + describe(job.get()) + " - it will never print");
            }
            dispatch.remove(job.get().id());
            return done(null, "removed pool job " + describe(job.get()));
        }));
    }

    @Tool(name = "retry_auto_queue", description = """
            Runs auto-queue again for an open order it skipped (the Orders page's "Try auto-queue again"), e.g. \
            after a mapping was fixed or a filament loaded. Does nothing to an order that is already queued. Needs \
            the control token.""",
            annotations = @Tool.Annotations(title = "Retry auto-queue", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse retryAutoQueue(
            @ToolArg(description = "etsy or ebay") final String market,
            @ToolArg(description = "Order id (Etsy receipt number or eBay order id)") final String orderId) {
        return audit.run("retry_auto_queue", McpAudit.args("market", market, "orderId", orderId), () -> control(() -> {
            final Optional<String> problem;
            if ("etsy".equalsIgnoreCase(market)) {
                problem = etsy.retryAutoQueue(orderId.trim());
            } else if ("ebay".equalsIgnoreCase(market)) {
                problem = ebay.retryAutoQueue(orderId.trim());
            } else {
                return ToolResponse.error("market must be etsy or ebay");
            }
            if (problem.isPresent()) {
                return ToolResponse.error("%s order %s: %s".formatted(market, orderId, problem.get()));
            }
            return done(market, "re-ran auto-queue for %s order %s - it was queued".formatted(market, orderId));
        }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------------------

    /** Runs {@code body} only for a control-token caller. */
    private ToolResponse control(final Supplier<ToolResponse> body) {
        if (audit.caller().orElse(null) != McpAccessFilter.Access.CONTROL) {
            return ToolResponse.error("this tool needs the MCP control token; this connection uses the read token");
        }
        return body.get();
    }

    private Optional<BambuPrinter> printer(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return printers.getPrintersDetail().stream()
                .filter(d -> d.name().equalsIgnoreCase(name.trim()))
                .map(BambuPrinters.PrinterDetail::printer)
                .findFirst();
    }

    private Optional<DispatchQueueService.PendingJob> poolJob(final String id) {
        return id == null ? Optional.empty()
                : dispatch.getPool().stream().filter(j -> j.id().equals(id.trim())).findFirst();
    }

    private static String describe(final DispatchQueueService.PendingJob j) {
        return (j.part() == null ? j.id() : OrderTools.part(j.part()))
                + (j.orderRef() == null ? "" : " for " + j.orderRef().market() + " " + j.orderRef().orderId());
    }

    private ToolResponse unknown(final String printer) {
        final String names = String.join(", ", printers.getPrintersDetail().stream().map(BambuPrinters.PrinterDetail::name).sorted().toList());
        return ToolResponse.error("no printer named '%s' (printers: %s)".formatted(printer, names));
    }

    private static String job(final BambuPrinter p) {
        final String name = p.getSubtaskName().filter(s -> !s.isBlank()).orElse("no job name");
        return "%s, %d%%, layer %d/%d".formatted(name, Math.max(0, p.getProgressPercent()), p.getLayerNum(), p.getTotalLayerNum());
    }

    /** Not an error, but plainly not done: the agent has to call again with confirm=true. */
    private static ToolResponse preview(final String what) {
        return ToolResponse.success("NOT DONE - confirmation needed. This would " + what
                + ". Call again with confirm=true once the user has agreed.");
    }

    private ToolResponse done(final String printer, final String what) {
        try {
            notifications.notifyEvent("mcp_action", printer == null ? "Agent" : printer, "Agent " + what);
        } catch (RuntimeException ex) {
            Log.warnf("MCP: could not send the mcp_action notification: %s", ex.getMessage());
        }
        return ToolResponse.success("Done: " + what);
    }
}
