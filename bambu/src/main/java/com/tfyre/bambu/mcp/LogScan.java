package com.tfyre.bambu.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the app's own log file(s) for {@code health_check}: WARN/ERROR counts grouped by logger and message shape,
 * Vert.x blocked-thread warnings, and the last few errors.
 * <p>
 * Line format (Quarkus file handler): {@code 2026-11-02 16:54:43,580 host /path/java[1] INFO  [category] (thread) msg}.
 * Continuation lines (stack traces) are ignored. Times are local, as written.
 */
final class LogScan {

    static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)[,.]\\d+\\s+\\S+\\s+\\S+\\s+(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)\\s+\\[([^\\]]*)\\]\\s+\\((.*?)\\)\\s(.*)$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");
    private static final int GROUP_TEXT = 110;

    record Entry(LocalDateTime at, String level, String logger, String thread, String message) {

        boolean isError() {
            return level.equals("ERROR") || level.equals("FATAL") || level.equals("SEVERE");
        }

        boolean isWarn() {
            return level.startsWith("WARN");
        }
    }

    record Group(String level, String logger, String shape, int count, LocalDateTime last) {
    }

    record Result(LocalDateTime since, int lines, int errors, int warnings, int blockedThread, List<Group> groups,
            List<Entry> lastErrors) {
    }

    private LogScan() {
    }

    static Optional<Entry> parse(final String line) {
        final Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(LocalDateTime.parse(m.group(1), TS), m.group(2), m.group(3), m.group(4), m.group(5)));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /** The message with numbers collapsed, so "retry 3 of 5" and "retry 4 of 5" count as one kind. */
    static String shape(final String message) {
        final String s = NUMBERS.matcher(message).replaceAll("#").replaceAll("\\s+", " ").trim();
        return s.length() > GROUP_TEXT ? s.substring(0, GROUP_TEXT) + "…" : s;
    }

    static Result scan(final List<String> lines, final LocalDateTime since, final int maxGroups, final int maxErrors) {
        final Map<String, Group> groups = new LinkedHashMap<>();
        final List<Entry> errors = new ArrayList<>();
        int count = 0;
        int errorCount = 0;
        int warnCount = 0;
        int blocked = 0;
        for (final String line : lines) {
            final Optional<Entry> parsed = parse(line);
            if (parsed.isEmpty() || parsed.get().at().isBefore(since)) {
                continue;
            }
            final Entry e = parsed.get();
            count++;
            if (e.logger().contains("BlockedThreadChecker")) {
                blocked++;
            }
            if (!e.isError() && !e.isWarn()) {
                continue;
            }
            if (e.isError()) {
                errorCount++;
                errors.add(e);
                if (errors.size() > maxErrors) {
                    errors.remove(0);
                }
            } else {
                warnCount++;
            }
            final String level = e.isError() ? "ERROR" : "WARN";
            final String shape = shape(e.message());
            final String key = level + '|' + e.logger() + '|' + shape;
            final Group g = groups.get(key);
            groups.put(key, new Group(level, e.logger(), shape, g == null ? 1 : g.count() + 1, e.at()));
        }
        final List<Group> top = groups.values().stream()
                .sorted(Comparator.comparing((Group g) -> g.level().equals("ERROR") ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(Group::count).reversed()))
                .limit(maxGroups).toList();
        return new Result(since, count, errorCount, warnCount, blocked, top, List.copyOf(errors));
    }

    /**
     * The log file and its numbered rotations ({@code .1} newest), oldest first, skipping any rotation last written
     * before {@code since} - nothing in it can be newer.
     */
    static List<Path> files(final Path log, final LocalDateTime since) {
        final List<Path> out = new ArrayList<>();
        for (int i = 9; i >= 1; i--) {
            final Path p = log.resolveSibling(log.getFileName() + "." + i);
            if (Files.isRegularFile(p) && !modifiedBefore(p, since)) {
                out.add(p);
            }
        }
        if (Files.isRegularFile(log)) {
            out.add(log);
        }
        return out;
    }

    static List<String> read(final List<Path> files) throws IOException {
        final List<String> lines = new ArrayList<>();
        for (final Path p : files) {
            // InputStreamReader replaces malformed bytes instead of throwing like Files.readAllLines would
            try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static boolean modifiedBefore(final Path p, final LocalDateTime since) {
        try {
            final Instant m = Files.getLastModifiedTime(p).toInstant();
            return LocalDateTime.ofInstant(m, ZoneId.systemDefault()).isBefore(since);
        } catch (IOException ex) {
            return false;
        }
    }
}
