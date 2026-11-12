package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.AutoStartService;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.BedClearService;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.GcodeMappingQueuer;
import com.tfyre.bambu.printer.GcodeSource;
import com.tfyre.bambu.printer.LibraryFileInfo;
import com.tfyre.bambu.printer.LibraryService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.PrintQueueService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code start_print} (docs/MCP-PLAN.md "Next: start_print"): one call from "print a Mk8 cupholder" to a running
 * print. It queues the library file on the chosen printer through {@link GcodeMappingQueuer#queuePart} - the same
 * path auto-queued orders take, with the wrong-printer and missing-file gates - and lets auto-start do the rest:
 * the AI bed check, then the start. With {@code wait} it watches for up to a minute and a half and reports what
 * happened, so the caller doesn't have to poll.
 */
@Singleton
public class PrintTools {

    /** Auto-start ticks once a minute and the bed check takes ~25 s. */
    static final Duration WAIT_FOR_START = Duration.ofSeconds(95);

    @Inject
    LibraryService library;
    @Inject
    LibraryFileInfo fileInfo;
    @Inject
    GcodeMappingQueuer queuer;
    @Inject
    PrintQueueService queue;
    @Inject
    BambuPrinters printers;
    @Inject
    AutoStartService autoStart;
    @Inject
    BedClearService bedClear;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    McpAudit audit;
    @Inject
    com.tfyre.bambu.printer.NotificationService notifications;

    record Candidate(String printer, String path, int slot, String tray, String filament, int rank, String why) {
    }

    @Tool(name = "start_print", description = """
            Starts a print of a library file: finds the file by name, picks a printer (or uses the one given), \
            picks the AMS tray holding the file's filament, queues it on that printer and lets auto-start run the \
            AI bed check and start it - normally within a minute. printer=any picks a free printer with a clear \
            bed, the right filament and auto-start on. Refuses files sliced for the other printer type and \
            multi-filament files (use their mapping). Without confirm=true it only says what it would do. Needs \
            the control token.""",
            annotations = @Tool.Annotations(title = "Start print", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    ToolResponse startPrint(
            @ToolArg(description = "Words from the part title or file name, e.g. 'mk8 cupholder'") final String file,
            @ToolArg(description = "Printer name, or 'any' (default)", required = false, defaultValue = "any") final String printer,
            @ToolArg(description = "AMS tray to use, e.g. A3 or HT1; default = the tray holding the file's filament", required = false) final String tray,
            @ToolArg(description = "Plate number (default 1)", required = false, defaultValue = "1") final int plate,
            @ToolArg(description = "Wait up to ~90 s and report whether it started (default true)", required = false, defaultValue = "true") final boolean wait,
            @ToolArg(description = "true to actually start it", required = false, defaultValue = "false") final boolean confirm) {
        return audit.run("start_print", McpAudit.args("file", file, "printer", printer, "tray", tray, "plate", plate,
                "wait", wait, "confirm", confirm), () -> {
                    if (audit.caller().orElse(null) != McpAccessFilter.Access.CONTROL) {
                        return ToolResponse.error("this tool needs the MCP control token; this connection uses the read token");
                    }
                    return body(file, printer, tray, plate, wait, confirm);
                });
    }

    /** The work behind start_print, without the access check - LiteTools calls it for its own print_part. */
    ToolResponse body(final String file, final String printer, final String tray, final int plate,
            final boolean wait, final boolean confirm) {
        final List<LibraryService.Part> parts = findParts(library.parts(), file);
        if (parts.isEmpty()) {
            return ToolResponse.error("no library part matches '%s' - check the spelling or use fewer words".formatted(file));
        }
        if (parts.size() > 1) {
            return ToolResponse.error("'%s' matches %d parts - be more specific: %s".formatted(file, parts.size(),
                    String.join(" | ", parts.stream().limit(8).map(LibraryService.Part::title).toList())));
        }
        final LibraryService.Part part = parts.get(0);
        final boolean any = printer == null || printer.isBlank() || "any".equalsIgnoreCase(printer.trim());

        final List<Candidate> ok = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        for (final BambuPrinters.PrinterDetail d : printers.getPrintersDetail()) {
            if (!any && !d.name().equalsIgnoreCase(printer.trim())) {
                continue;
            }
            final Candidate c = evaluate(d, part, tray, any);
            if (c.rank() < 0) {
                rejected.add(d.name() + ": " + c.why());
            } else {
                ok.add(c);
            }
        }
        if (!any && ok.isEmpty() && rejected.isEmpty()) {
            return ToolResponse.error("no printer named '%s'".formatted(printer));
        }
        if (ok.isEmpty()) {
            return ToolResponse.error("can't start %s anywhere right now - %s".formatted(part.title(), String.join("; ", rejected)));
        }
        ok.sort(Comparator.comparingInt(Candidate::rank).thenComparing(Candidate::printer, String.CASE_INSENSITIVE_ORDER));
        final Candidate pick = ok.get(0);
        final LibraryFileInfo.Details det = fileInfo.details(pick.path());
        final String what = "%s (%s, plate %d) on %s from tray %s %s%s%s".formatted(part.title(), pick.path(), plate,
                pick.printer(), pick.tray(), pick.filament(),
                det.seconds() > 0 ? ", ~" + FarmTools.human(Duration.ofSeconds(det.seconds())) : "",
                det.grams() > 0 ? ", %.0f g".formatted(det.grams()) : "");
        if (!confirm) {
            return ToolResponse.success("NOT DONE - confirmation needed. This would start %s after the AI bed check (%s)%s. "
                    .formatted(what, pick.why(), rejected.isEmpty() ? "" : "; not used: " + String.join("; ", rejected))
                    + "Call again with confirm=true once the user has agreed.");
        }

        final MappingPart mp = new MappingPart(GcodeSource.LIBRARY, pick.path(), Math.max(1, plate), 1, pick.slot(),
                pick.filament(), null, null, null, null);
        final int before = queue.size(pick.printer());
        final Optional<String> problem = queuer.queuePart(mp, pick.printer(), pick.slot(), null);
        if (problem.isPresent()) {
            return ToolResponse.error("not queued: " + problem.get());
        }
        notifications.notifyEvent("mcp_action", pick.printer(), "Agent queued " + what + " - starts after the bed check");
        if (!wait) {
            return ToolResponse.success("Queued %s - auto-start runs the bed check and starts it within about a minute%s."
                    .formatted(what, before > 0 ? " (behind %d job(s) already queued)".formatted(before) : ""));
        }
        return ToolResponse.success(waitForStart(pick.printer(), what, before));
    }

    /** Polls until the printer starts, auto-start holds, or the wait runs out; says which. */
    private String waitForStart(final String name, final String what, final int before) {
        final BambuPrinter p = printers.getPrinterDetail(name).map(BambuPrinters.PrinterDetail::printer).orElse(null);
        final Instant end = Instant.now().plus(WAIT_FOR_START);
        String status = autoStart.getStatus(name);
        while (Instant.now().isBefore(end)) {
            if (p != null && p.getGCodeState().isPrinting()) {
                return "Started: %s (auto-start: %s).".formatted(what, autoStart.getStatus(name));
            }
            status = autoStart.getStatus(name);
            if (status.startsWith("paused") || status.startsWith("held")) {
                return "Queued %s, but auto-start is holding it: %s. It stays in %s's queue; see ai_history for the bed check."
                        .formatted(what, status, name);
            }
            if (before > 0) {
                break; // behind other jobs - nothing to wait for
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "Queued %s; not started yet (auto-start: %s). It will start after the bed check passes - check farm_status."
                .formatted(what, status);
    }

    /**
     * Can {@code d} print {@code part} now? rank: 0 bed known clear, 1 bed state unknown (the AI check decides),
     * -1 = no, with the reason in why.
     */
    private Candidate evaluate(final BambuPrinters.PrinterDetail d, final LibraryService.Part part, final String tray,
            final boolean any) {
        final String name = d.name();
        final BambuPrinter p = d.printer();
        final BambuConst.PrinterModel model = p.getModel();
        final String path = model == BambuConst.PrinterModel.H2D ? part.h2d() : part.p1();
        if (path == null || path.isBlank()) {
            return no(name, "no %s file for %s".formatted(model == BambuConst.PrinterModel.H2D ? "H2D" : "P1", part.title()));
        }
        final String wrong = fileInfo.mismatch(path, model);
        if (wrong != null) {
            return no(name, wrong);
        }
        if (fileInfo.filamentCount(path) > 1) {
            return no(name, "uses %d filaments - queue it through its mapping instead".formatted(fileInfo.filamentCount(path)));
        }
        if (!d.isRunning()) {
            return no(name, "not connected");
        }
        if (!autoStart.isGloballyEnabled() || !autoStart.isEnabled(name)) {
            return no(name, "auto-start is off");
        }
        final BambuConst.GCodeState state = p.getGCodeState();
        if (state.isPrinting() || state == BambuConst.GCodeState.PAUSE) {
            if (any) {
                return no(name, "busy (" + state + ")");
            }
        } else if (!state.isReady()) {
            return no(name, "not ready (" + state + ")");
        }
        if (any && queue.size(name) > 0) {
            return no(name, "already has %d queued".formatted(queue.size(name)));
        }
        final String material = firstMaterial(fileInfo.details(path).material());
        final Optional<int[]> slot = tray == null || tray.isBlank()
                ? pickTray(p.getAmsTrayTypes(), material, p.getActiveTrayId())
                : parseTray(tray).map(s -> new int[] {s, telemetryKey(s)});
        if (slot.isEmpty()) {
            return no(name, tray != null && !tray.isBlank() ? "no tray " + tray
                    : "no %s loaded".formatted(material.isBlank() ? "matching filament" : material));
        }
        final String loaded = p.getAmsTrayTypes().getOrDefault(slot.get()[1], "");
        if (!material.isBlank() && !loaded.isBlank() && !loaded.equalsIgnoreCase(material)) {
            return no(name, "tray %s holds %s, file wants %s".formatted(FarmTools.trayLabel(slot.get()[0]), loaded, material));
        }
        final boolean busy = state.isPrinting() || state == BambuConst.GCodeState.PAUSE;
        final boolean cleared = bedClear.isCleared(name) || state == BambuConst.GCodeState.IDLE;
        final String why = busy ? "busy - it will start after the current print"
                : cleared ? "free, bed marked clear" : "free, bed not marked clear - the camera check decides";
        return new Candidate(name, path, slot.get()[0], FarmTools.trayLabel(slot.get()[0]),
                material.isBlank() ? loaded : material, busy ? 2 : cleared ? 0 : 1, why);
    }

    private static Candidate no(final String printer, final String why) {
        return new Candidate(printer, null, -1, "", "", -1, why);
    }

    // ---------------------------------------------------------------------------------------------------------
    // pure helpers (tested)
    // ---------------------------------------------------------------------------------------------------------

    /** Parts whose title or file names contain every word of {@code query}; an exact title match wins outright. */
    static List<LibraryService.Part> findParts(final List<LibraryService.Part> all, final String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        final String q = norm(query);
        final List<LibraryService.Part> exact = all.stream().filter(p -> norm(p.title()).equals(q)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        final String[] words = q.split(" ");
        return all.stream().filter(p -> {
            final String hay = norm(p.title() + " " + p.project() + " " + p.p1() + " " + p.h2d());
            for (final String w : words) {
                if (!hay.contains(w)) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    /** Lower case, separators (_ - . /) as spaces, single-spaced - "Mk8_Cupholder" matches "mk8 cupholder". */
    static String norm(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[_\\-./]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** "PETG + PLA" -> "PETG"; blank stays blank. */
    static String firstMaterial(final String material) {
        if (material == null || material.isBlank()) {
            return "";
        }
        return material.split("[+,;/]")[0].trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The tray to feed from: the active tray when it holds the material, else the lowest tray that does. Returns
     * {printer tray number, telemetry key}; with no material known, the active tray (or A1).
     */
    static Optional<int[]> pickTray(final Map<Integer, String> types, final String material, final int activeTray) {
        if (material == null || material.isBlank()) {
            final int t = activeTray >= 0 && activeTray != 255 ? activeTray : 0;
            return Optional.of(new int[] {slotOf(t), t});
        }
        if (activeTray >= 0 && material.equalsIgnoreCase(types.getOrDefault(activeTray, ""))) {
            return Optional.of(new int[] {slotOf(activeTray), activeTray});
        }
        for (final Map.Entry<Integer, String> e : new TreeMap<>(types).entrySet()) {
            if (material.equalsIgnoreCase(e.getValue())) {
                return Optional.of(new int[] {slotOf(e.getKey()), e.getKey()});
            }
        }
        return Optional.empty();
    }

    /** Telemetry key (amsId*4 + tray; 512 = first AMS HT) to the tray number the printer and mappings use (128). */
    static int slotOf(final int telemetryKey) {
        return telemetryKey >= 512 ? 128 + (telemetryKey - 512) / 4 : telemetryKey;
    }

    static int telemetryKey(final int slot) {
        return slot >= 128 && slot < 136 ? 512 + (slot - 128) * 4 : slot;
    }

    /** "A1".."D4" -> 0..15, "HT1".. -> 128.., "Ext" -> 254. */
    static Optional<Integer> parseTray(final String tray) {
        final String t = tray.trim().toUpperCase(Locale.ROOT);
        if (t.equals("EXT")) {
            return Optional.of(BambuConst.AMS_TRAY_VIRTUAL);
        }
        if (t.matches("HT[1-8]")) {
            return Optional.of(127 + Integer.parseInt(t.substring(2)));
        }
        if (t.matches("[A-D][1-4]")) {
            return Optional.of((t.charAt(0) - 'A') * 4 + (t.charAt(1) - '1'));
        }
        return Optional.empty();
    }
}
