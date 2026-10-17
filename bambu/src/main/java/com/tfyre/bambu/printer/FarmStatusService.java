package com.tfyre.bambu.printer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The farm in one sentence, plus one line per printer - what the header chip and the phone's Farm page show.
 * <p>
 * The sentence answers "why is nothing starting?", worst case first: something only a person can fix (red), one
 * action away from starting (amber), running fine with a time for the next free printer (green), or nothing
 * waiting at all (grey).
 */
@ApplicationScoped
public class FarmStatusService {

    public enum Tone {
        BAD, WARN, OK, QUIET
    }

    /**
     * @param canClear the bed is holding a finished or failed part that nobody has marked as removed yet
     * @param inJob    printing or paused
     */
    public record PrinterLine(String name, Tone tone, String text, boolean canClear, boolean inJob, boolean paused,
            int percent, int queued) {

        /** Printers that need a person first, then the ones that are simply working, then the idle ones. */
        int rank() {
            return tone == Tone.BAD ? 0 : tone == Tone.WARN ? 1 : inJob ? 2 : 3;
        }
    }

    /**
     * @param headline the sentence for the chip
     * @param detail   the dispatcher's own longer explanation when there is one, else blank
     */
    public record Status(Tone tone, String headline, String detail, List<PrinterLine> printers) {
    }

    @Inject
    BambuPrinters printers;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    PrintQueueService queueService;
    @Inject
    BedClearService bedClear;

    public PrinterLine line(final BambuPrinters.PrinterDetail detail) {
        final BambuPrinter p = detail.printer();
        final String name = detail.name();
        final BambuConst.GCodeState state = p.getGCodeState();
        final boolean cleared = bedClear.isCleared(name);
        final boolean paused = state == BambuConst.GCodeState.PAUSE;
        final boolean inJob = state.isPrinting() || paused;
        final int percent = Math.max(0, p.getProgressPercent());
        final int queued = queueService.size(name);
        final String job = p.getSubtaskName().filter(s -> !s.isBlank()).orElse("");

        final Optional<String> fault = fault(p);
        if (fault.isPresent()) {
            return new PrinterLine(name, Tone.BAD, fault.get(), false, inJob, paused, percent, queued);
        }
        if (paused) {
            return new PrinterLine(name, Tone.WARN, "Paused" + (job.isEmpty() ? "" : " · " + job), false, true, true, percent, queued);
        }
        if (state == BambuConst.GCodeState.FAILED && !cleared) {
            return new PrinterLine(name, Tone.BAD, "Print failed · bed not cleared", true, false, false, percent, queued);
        }
        if (state == BambuConst.GCodeState.FINISH && !cleared) {
            return new PrinterLine(name, Tone.WARN, "Finished · bed not cleared", true, false, false, 100, queued);
        }
        if (state.isPrinting()) {
            final int left = p.getRemainingMinutes();
            final String when = left > 0 ? "free in " + DispatchQueueService.formatMinutes(left) : "finishing";
            return new PrinterLine(name, Tone.OK, (job.isEmpty() ? "Printing" : job) + " · " + when, false, true, false, percent, queued);
        }
        if (state.isReady() || state == BambuConst.GCodeState.FINISH || state == BambuConst.GCodeState.FAILED) {
            return new PrinterLine(name, Tone.QUIET, queued > 0 ? "Free · %d queued".formatted(queued) : "Free · nothing queued",
                    false, false, false, 0, queued);
        }
        return new PrinterLine(name, Tone.QUIET, state.getDescription(), false, false, false, 0, queued);
    }

    private static Optional<String> fault(final BambuPrinter p) {
        final int code = p.getPrintError();
        if (code != 0) {
            return Optional.of("Error %s: %s".formatted(Integer.toHexString(code),
                    BambuErrors.getPrinterError(code).orElse("unknown error")));
        }
        final List<String> hms = p.getActiveHmsErrors();
        return hms.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", hms));
    }

    public Status status() {
        final List<PrinterLine> lines = new ArrayList<>();
        printers.getPrintersDetail().forEach(d -> lines.add(line(d)));
        lines.sort(Comparator.comparingInt(PrinterLine::rank).thenComparing(PrinterLine::name, String.CASE_INSENSITIVE_ORDER));

        final int waiting = dispatch.size() + lines.stream().mapToInt(PrinterLine::queued).sum();
        final String jobs = "%d waiting".formatted(waiting);
        final String detail = dispatch.getBlockedStatus().orElse("");
        final String key = dispatch.getBlockedKey().orElse("");
        final Optional<PrinterLine> toClear = lines.stream().filter(PrinterLine::canClear).findFirst();
        final Optional<PrinterLine> faulted = lines.stream().filter(l -> l.tone() == Tone.BAD && !l.canClear()).findFirst();

        if (waiting > 0 && dispatch.getBlockedKind() == DispatchQueueService.BlockKind.ATTENTION && !key.isEmpty()
                && !key.startsWith("bed-dirty")) {
            return new Status(Tone.BAD, blockedHeadline(key) + " · " + jobs, detail, lines);
        }
        if (waiting > 0 && toClear.isPresent()) {
            return new Status(Tone.WARN, "%s bed not cleared · %s".formatted(toClear.get().name(), jobs), detail, lines);
        }
        if (waiting > 0 && key.startsWith("bed-dirty")) {
            return new Status(Tone.WARN, "%s bed not clear · %s".formatted(printerOf(key), jobs), detail, lines);
        }
        if (faulted.isPresent()) {
            return new Status(Tone.BAD, "%s needs you".formatted(faulted.get().name()), faulted.get().text(), lines);
        }
        if (waiting > 0) {
            return new Status(Tone.OK, dispatch.getNextFree().map(n -> "Next free: " + n + " · " + jobs).orElse(jobs), detail, lines);
        }
        final long free = lines.stream().filter(l -> !l.inJob() && l.tone() == Tone.QUIET && l.text().startsWith("Free")).count();
        return new Status(Tone.QUIET, "Nothing waiting · %d printer%s free".formatted(free, free == 1 ? "" : "s"), "", lines);
    }

    private static String printerOf(final String key) {
        final int bar = key.indexOf('|');
        return bar < 0 ? "A printer's" : key.substring(bar + 1);
    }

    /** The dispatcher's reason codes, in the fewest words that still say what to do. */
    private static String blockedHeadline(final String key) {
        final String reason = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
        return switch (reason) {
            case "auto-start-off" -> "Auto-start is off";
            case "ai-off" -> "AI checks are off";
            case "all-parked" -> "Every waiting job is parked";
            case "none-opted-in" -> "No printer takes auto-queue jobs";
            case "no-filament" -> "No printer has the filament loaded";
            case "ai-unavailable" -> printerOf(key) + ": AI check unavailable";
            case "start-failed" -> printerOf(key) + " failed to start a job";
            case "job-parked" -> "A job is parked after failing";
            default -> "Jobs are held";
        };
    }
}
