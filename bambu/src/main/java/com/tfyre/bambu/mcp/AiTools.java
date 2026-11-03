package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.PrintAiService;
import io.quarkiverse.mcp.server.ImageContent;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 1 AI tools (docs/MCP-PLAN.md): {@code ai_history} and {@code ai_frame}. Read-only. */
@Singleton
public class AiTools {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    PrintAiService ai;
    @Inject
    McpAudit audit;
    @Inject
    AiLabels labels;

    @Tool(name = "ai_history", description = """
            Past AI checks (bed-clear, failure, first-layer, extrusion), newest first: frame id, time, printer, \
            check type, trigger, verdict (good / problem / incomplete), severity, the model's explanation and the \
            pixel-diff score for bed checks, and the label if one was set with label_frame. Pass a frame id to \
            ai_frame to see the image the model judged.""",
            annotations = @Tool.Annotations(title = "AI check history", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse aiHistory(
            @ToolArg(description = "Only this printer (exact name)", required = false) final String printer,
            @ToolArg(description = "Only this check type: bed-clear, failure, first-layer, extrusion", required = false) final String checkType,
            @ToolArg(description = "Only this verdict: good, problem, or incomplete (the check could not finish)", required = false) final String verdict,
            @ToolArg(description = "Only the last N hours; 0 = all kept history", required = false, defaultValue = "24") final int hours,
            @ToolArg(description = "Maximum rows (1-200)", required = false, defaultValue = "30") final int limit,
            @ToolArg(description = "Only frames that have a label", required = false, defaultValue = "false") final boolean labelled) {
        return audit.run("ai_history", McpAudit.args("printer", printer, "checkType", checkType, "verdict", verdict,
                "hours", hours, "limit", limit, "labelled", labelled),
                () -> audit.json(buildHistory(printer, checkType, verdict, hours, limit, labelled)));
    }

    Map<String, Object> buildHistory(final String printer, final String checkType, final String verdict, final int hours,
            final int limit, final boolean labelled) {
        final Instant since = hours > 0 ? Instant.now().minusSeconds(hours * 3600L) : Instant.EPOCH;
        final List<Map<String, Object>> rows = new ArrayList<>();
        int matching = 0;
        for (final PrintAiService.CheckRecord r : ai.getHistory()) {
            if (r.at() == null || r.at().isBefore(since)) {
                continue;
            }
            if (!HistoryTools.blank(printer) && !printer.equalsIgnoreCase(r.printer())) {
                continue;
            }
            if (!HistoryTools.blank(checkType) && !checkType.equalsIgnoreCase(r.checkType())) {
                continue;
            }
            if (!HistoryTools.blank(verdict) && !verdict.equalsIgnoreCase(verdictOf(r))) {
                continue;
            }
            final Optional<AiLabels.Label> label = labels.get(frameId(r));
            if (labelled && label.isEmpty()) {
                continue;
            }
            matching++;
            if (rows.size() >= HistoryTools.clamp(limit)) {
                continue;
            }
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("frame", frameId(r));
            m.put("at", LocalDateTime.ofInstant(r.at(), ZoneId.systemDefault()).format(LOCAL));
            m.put("printer", r.printer());
            m.put("type", r.checkType());
            m.put("trigger", r.trigger());
            m.put("verdict", verdictOf(r));
            if (r.severity() != null) {
                m.put("severity", r.severity().name());
            }
            m.put("description", FarmTools.truncate(r.description(), 400));
            if (r.pixelDiff() != null) {
                m.put("pixelDiff", Math.round(r.pixelDiff() * 1000) / 1000.0);
            }
            if (r.context() != null && !r.context().isBlank()) {
                m.put("context", FarmTools.truncate(r.context(), 200));
            }
            label.ifPresent(l -> m.put("label", l.truth() + (HistoryTools.blank(l.note()) ? "" : " - " + FarmTools.truncate(l.note(), 160))));
            rows.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("matching", matching);
        out.put("checks", rows);
        return out;
    }

    @Tool(name = "ai_frame", description = """
            The JPEG an AI check analysed, as an image, plus that check's verdict. Take the frame id from \
            ai_history (printer@epochMillis). Frames of older checks may have been pruned.""",
            annotations = @Tool.Annotations(title = "AI check frame", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse aiFrame(@ToolArg(description = "Frame id from ai_history, e.g. P1S-3@1793660430123") final String frameId) {
        return audit.run("ai_frame", McpAudit.args("frameId", frameId), () -> frameBody(frameId));
    }

    private ToolResponse frameBody(final String frameId) {
        final Optional<PrintAiService.CheckRecord> rec = find(frameId);
        if (rec.isEmpty()) {
            return ToolResponse.error("no AI check with frame id " + frameId + " (see ai_history)");
        }
        final byte[] jpeg = ai.frameOf(rec.get());
        final PrintAiService.CheckRecord r = rec.get();
        final String caption = "%s %s %s at %s: %s. %s".formatted(r.printer(), r.checkType(), verdictOf(r),
                LocalDateTime.ofInstant(r.at(), ZoneId.systemDefault()).format(LOCAL),
                r.severity() == null ? "-" : r.severity().name(), FarmTools.truncate(r.description(), 400));
        if (jpeg == null || jpeg.length == 0) {
            return ToolResponse.error("the frame for " + frameId + " is no longer stored. " + caption);
        }
        return ToolResponse.success(new TextContent(caption),
                new ImageContent(Base64.getEncoder().encodeToString(jpeg), "image/jpeg"));
    }

    Optional<PrintAiService.CheckRecord> find(final String frameId) {
        if (frameId == null) {
            return Optional.empty();
        }
        final int at = frameId.lastIndexOf('@');
        if (at <= 0) {
            return Optional.empty();
        }
        final String printer = frameId.substring(0, at);
        final long millis;
        try {
            millis = Long.parseLong(frameId.substring(at + 1).trim());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        return ai.getHistory().stream()
                .filter(r -> r.at() != null && r.at().toEpochMilli() == millis && printer.equalsIgnoreCase(r.printer()))
                .findFirst();
    }

    static String frameId(final PrintAiService.CheckRecord r) {
        return r.printer() + "@" + r.at().toEpochMilli();
    }

    static String verdictOf(final PrintAiService.CheckRecord r) {
        return r.good() == null ? "incomplete" : r.good() ? "good" : "problem";
    }
}
