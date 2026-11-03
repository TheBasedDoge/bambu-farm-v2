package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.NotificationService;
import com.tfyre.bambu.printer.PrintHistoryService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.eclipse.microprofile.config.ConfigProvider;

/** Phase 1 history tools (docs/MCP-PLAN.md): {@code print_history}, {@code notifications}, {@code search_logs}. */
@Singleton
public class HistoryTools {

    static final int MAX_LIMIT = 200;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Inject
    PrintHistoryService history;
    @Inject
    NotificationService notifications;
    @Inject
    McpAudit audit;

    // ---------------------------------------------------------------------------------------------------------
    // print_history
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "print_history", description = """
            Finished, failed and cancelled prints, newest first: printer, file, start and end, duration, result, \
            grams, what started it (trigger) and the order it was for. Ends with per-printer totals for the rows \
            returned.""",
            annotations = @Tool.Annotations(title = "Print history", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse printHistory(
            @ToolArg(description = "Only this printer (exact name, e.g. P1S-3)", required = false) final String printer,
            @ToolArg(description = "Only this result, e.g. FAILED, FINISH, CANCELLED (case-insensitive substring)", required = false) final String result,
            @ToolArg(description = "Only jobs that ended in the last N hours; 0 = no limit", required = false, defaultValue = "48") final int hours,
            @ToolArg(description = "Maximum rows (1-200)", required = false, defaultValue = "50") final int limit) {
        return audit.run("print_history", McpAudit.args("printer", printer, "result", result, "hours", hours, "limit", limit),
                () -> audit.json(buildHistory(printer, result, hours, limit)));
    }

    Map<String, Object> buildHistory(final String printer, final String result, final int hours, final int limit) {
        final OffsetDateTime since = hours > 0 ? OffsetDateTime.now().minusHours(hours) : null;
        final List<PrintHistoryService.PrintJob> jobs = history.getJobs().stream()
                .filter(j -> blank(printer) || printer.equalsIgnoreCase(j.printer()))
                .filter(j -> blank(result) || (j.result() != null && j.result().toLowerCase().contains(result.toLowerCase())))
                .filter(j -> since == null || (j.ended() != null && j.ended().isAfter(since)))
                .sorted(Comparator.comparing(PrintHistoryService.PrintJob::ended,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .limit(clamp(limit))
                .toList();
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<String, Map<String, Object>> totals = new LinkedHashMap<>();
        for (final PrintHistoryService.PrintJob j : jobs) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("printer", j.printer());
            m.put("file", j.file());
            m.put("started", fmt(j.started()));
            m.put("ended", fmt(j.ended()));
            m.put("duration", FarmTools.human(java.time.Duration.ofSeconds(j.durationSeconds())));
            m.put("result", j.result());
            if (j.grams() > 0) {
                m.put("grams", Math.round(j.grams() * 10) / 10.0);
            }
            if (!blank(j.trigger())) {
                m.put("trigger", j.trigger());
            }
            if (j.orderRef() != null) {
                m.put("order", j.orderRef().market() + " " + j.orderRef().orderId());
            }
            rows.add(m);
            final Map<String, Object> t = totals.computeIfAbsent(j.printer(), k -> new LinkedHashMap<>());
            t.merge("jobs", 1, (a, b) -> (Integer) a + (Integer) b);
            final String res = j.result() == null ? "?" : j.result().toUpperCase();
            t.merge(res.contains("FAIL") ? "failed" : res.contains("CANCEL") ? "cancelled" : "finished", 1,
                    (a, b) -> (Integer) a + (Integer) b);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("jobs", rows);
        out.put("totals", totals);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // notifications
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "notifications", description = """
            The app's notification log (what the Notifications page shows), newest first: time, event (e.g. \
            print_finished, print_failed, ai_failure, auto_queue_skipped, bed_not_clear), printer, message and \
            delivery status.""",
            annotations = @Tool.Annotations(title = "Notifications", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse notifications(
            @ToolArg(description = "Only this event type (exact, e.g. auto_queue_skipped)", required = false) final String event,
            @ToolArg(description = "Only this printer (exact name)", required = false) final String printer,
            @ToolArg(description = "Only entries from the last N hours; 0 = no limit", required = false, defaultValue = "24") final int hours,
            @ToolArg(description = "Maximum rows (1-200)", required = false, defaultValue = "50") final int limit) {
        return audit.run("notifications", McpAudit.args("event", event, "printer", printer, "hours", hours, "limit", limit),
                () -> audit.json(buildNotifications(event, printer, hours, limit)));
    }

    Map<String, Object> buildNotifications(final String event, final String printer, final int hours, final int limit) {
        final OffsetDateTime since = hours > 0 ? OffsetDateTime.now().minusHours(hours) : null;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<String, Integer> byEvent = new LinkedHashMap<>();
        for (final NotificationService.LogEntry e : notifications.getLog()) {
            if (!blank(event) && !event.equalsIgnoreCase(e.event())) {
                continue;
            }
            if (!blank(printer) && !printer.equalsIgnoreCase(e.printer())) {
                continue;
            }
            final Optional<OffsetDateTime> at = parse(e.timestamp());
            if (since != null && at.map(t -> t.isBefore(since)).orElse(false)) {
                continue;
            }
            byEvent.merge(e.event(), 1, Integer::sum);
            if (rows.size() >= clamp(limit)) {
                continue;
            }
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at.map(HistoryTools::fmt).orElse(e.timestamp()));
            m.put("event", e.event());
            if (!blank(e.printer())) {
                m.put("printer", e.printer());
            }
            m.put("message", FarmTools.truncate(e.message(), 500));
            if (!blank(e.status())) {
                m.put("status", e.status());
            }
            rows.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("matching", byEvent.values().stream().mapToInt(Integer::intValue).sum());
        out.put("byEvent", byEvent);
        out.put("entries", rows);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------
    // search_logs
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "search_logs", description = """
            Searches the app log (current file and rotations) with a regular expression, newest matches first. \
            Matches whole lines, stack-trace lines included. Secrets are redacted.""",
            annotations = @Tool.Annotations(title = "Search logs", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse searchLogs(
            @ToolArg(description = "Java regular expression, case-insensitive (e.g. 'P1S-3.*(FAILED|spaghetti)')") final String pattern,
            @ToolArg(description = "Only lines at this level or worse: ERROR, WARN, INFO (omit for any)", required = false) final String level,
            @ToolArg(description = "Only the last N hours; 0 = everything in the log files", required = false, defaultValue = "24") final int hours,
            @ToolArg(description = "Maximum lines (1-200)", required = false, defaultValue = "50") final int limit) {
        return audit.run("search_logs", McpAudit.args("pattern", pattern, "level", level, "hours", hours, "limit", limit),
                () -> searchLogsBody(pattern, level, hours, limit));
    }

    private ToolResponse searchLogsBody(final String pattern, final String level, final int hours, final int limit) {
        if (blank(pattern) || pattern.length() > 300) {
            return ToolResponse.error("pattern must be 1-300 characters");
        }
        final Pattern p;
        try {
            p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            return ToolResponse.error("bad regular expression: " + ex.getDescription());
        }
        final LocalDateTime since = hours > 0 ? LocalDateTime.now().minusHours(hours) : LocalDateTime.MIN;
        final String path = ConfigProvider.getConfig().getOptionalValue("quarkus.log.file.path", String.class).orElse("application.log");
        try {
            final List<String> lines = LogScan.read(LogScan.files(java.nio.file.Path.of(path).toAbsolutePath(), since));
            return audit.json(search(lines, p, level, since, clamp(limit)));
        } catch (IOException ex) {
            return ToolResponse.error("could not read the log: " + ex.getMessage());
        }
    }

    /**
     * Newest-first matches. A continuation line (stack trace) inherits the time and level of the entry above it,
     * so the time and level filters apply to it too.
     */
    static Map<String, Object> search(final List<String> lines, final Pattern p, final String level,
            final LocalDateTime since, final int limit) {
        final int minRank = rank(level);
        final List<String> hits = new ArrayList<>();
        int total = 0;
        LocalDateTime at = null;
        int lineRank = 0;
        final List<String> all = new ArrayList<>();
        for (final String line : lines) {
            final Optional<LogScan.Entry> e = LogScan.parse(line);
            if (e.isPresent()) {
                at = e.get().at();
                lineRank = rank(e.get().level());
            }
            if (at != null && at.isBefore(since)) {
                continue;
            }
            if (lineRank < minRank) {
                continue;
            }
            if (p.matcher(line).find()) {
                total++;
                all.add(FarmTools.truncate(line, 600));
            }
        }
        for (int i = all.size() - 1; i >= 0 && hits.size() < limit; i--) {
            hits.add(all.get(i));
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", total);
        out.put("shown", hits.size());
        out.put("lines", hits);
        return out;
    }

    static int rank(final String level) {
        if (level == null || level.isBlank()) {
            return 0;
        }
        return switch (level.toUpperCase()) {
            case "ERROR", "FATAL", "SEVERE" -> 3;
            case "WARN", "WARNING" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------------------------------------------------

    static int clamp(final int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    private static String fmt(final OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().format(LOCAL);
    }

    private static Optional<OffsetDateTime> parse(final String s) {
        try {
            return Optional.of(OffsetDateTime.parse(s));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
