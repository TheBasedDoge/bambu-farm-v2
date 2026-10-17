package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AMS drying: the auto-dry-on-finish settings (configured from the dashboard, persisted to JSON), the auto-dry
 * watcher, and alerts when a printer refuses a drying command.
 * <p>
 * Whether a unit is drying, and for how long, is NOT tracked here any more. Until 2026-10-12 the app recorded
 * "drying started" the moment it sent the command and counted down from that, which showed a 4-hour countdown
 * on two AMS units the printers had refused to heat. The printer's own report ({@link BambuPrinter#getAmsDryStates()})
 * is the only source now.
 */
@ApplicationScoped
public class AmsDryService {

    private static final String DEFAULT_FILENAME = "bambu-ams-dry.json";

    public static final int DEFAULT_TEMP = 55;
    public static final int DEFAULT_DURATION_HOURS = 4;

    /** Offered in the dry dialog. The firmware refuses a drying command that names no filament. */
    public static final List<String> FILAMENTS = List.of("PLA", "PETG", "ABS", "ASA", "TPU", "PA", "PC", "PVA", "HIPS");

    /** A start the printer hasn't answered within this long is reported as failed. */
    private static final Duration NO_ANSWER = Duration.ofMinutes(2);

    /**
     * Per-printer auto-dry configuration.
     *
     * @param autoOnFinish  trigger drying automatically when a print completes
     * @param temp          drying temperature in °C
     * @param durationHours drying duration in HOURS (firmware expects hours)
     */
    public record DrySetting(boolean autoOnFinish, int temp, int durationHours) {

        public static final DrySetting DEFAULT = new DrySetting(false, DEFAULT_TEMP, DEFAULT_DURATION_HOURS);

        public DrySetting withAutoOnFinish(final boolean enabled) {
            return new DrySetting(enabled, temp, durationHours);
        }

        public DrySetting withTempAndDuration(final int newTemp, final int newDuration) {
            return new DrySetting(autoOnFinish, newTemp, newDuration);
        }
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    BambuConfig config;

    @Inject
    BambuPrinters printers;

    @Inject
    NotificationService notificationService;

    private final ConcurrentHashMap<String, DrySetting> settings = new ConcurrentHashMap<>();

    /** printer|sequence_id of every refused or unanswered dry command already alerted on. */
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();

    /**
     * True when the printer reports drying settings for this AMS unit - i.e. its firmware can actually dry it.
     * An H2D reports dry_setting for its AMS 2 Pro; a P1S on 01.10 with the same AMS 2 Pro never does, and
     * Bambu Studio can't start a dry there either. So the dry button and auto-dry follow this, not the AMS model.
     */
    public static boolean canDry(final BambuPrinter printer, final int amsId) {
        final BambuPrinter.AmsDryState state = printer.getAmsDryStates().get(amsId);
        return state != null && state.supported();
    }

    /**
     * The filament loaded in an AMS unit, as the dry command wants it ("PETG", not "PETG-CF" or "PETG HF"):
     * the most common base type across its four trays. Blank when nothing is loaded.
     */
    public static String loadedFilament(final BambuPrinter printer, final int amsId) {
        final Map<Integer, String> types = printer.getAmsTrayTypes();
        final Map<String, Long> counts = java.util.stream.IntStream.range(amsId * 4, amsId * 4 + 4)
                .mapToObj(types::get)
                .filter(t -> t != null && !t.isBlank())
                .map(AmsDryService::baseType)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    static String baseType(final String type) {
        final String upper = type.trim().toUpperCase(Locale.ROOT);
        final int cut = upper.indexOf('-') > 0 ? upper.indexOf('-') : upper.indexOf(' ');
        return cut > 0 ? upper.substring(0, cut) : upper;
    }

    private Path getPath() {
        // Store in same directory as maintenance file, or current dir if unconfigured
        final Path maintenance = Path.of(config.maintenanceFile());
        final Path parent = maintenance.getParent();
        return parent != null ? parent.resolve(DEFAULT_FILENAME) : Path.of(DEFAULT_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Map<String, DrySetting> loaded = mapper.readValue(path.toFile(),
                    new TypeReference<Map<String, DrySetting>>() {});
            settings.putAll(loaded);
            Log.infof("AmsDryService: loaded %d printer setting(s) from %s", loaded.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "AmsDryService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        final Path path = getPath();
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "AmsDryService: cannot save %s: %s", path, ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public DrySetting getSetting(final String printerName) {
        return settings.getOrDefault(printerName, DrySetting.DEFAULT);
    }

    public void setSetting(final String printerName, final DrySetting setting) {
        settings.put(printerName, setting);
        save(); // write immediately so a crash doesn't lose the setting
    }

    // -------------------------------------------------------------------------
    // Auto-dry on print finish, and alerts for refused commands - server-side
    // -------------------------------------------------------------------------

    /** Last known GCodeState per printer, used to detect printing → finished transitions. */
    private final ConcurrentHashMap<String, BambuConst.GCodeState> lastStates = new ConcurrentHashMap<>();

    /**
     * Auto-dry waits this long after a print ends. Starting the second the print finishes gave the printer no
     * time to settle, and an immediate start is pointless anyway: the dry runs for hours.
     */
    static final Duration AUTO_DELAY = Duration.ofMinutes(5);
    /** A refused auto-dry that isn't explained by a dry already running is tried once more after this. */
    static final Duration RETRY_DELAY = Duration.ofMinutes(10);

    /** printer → when its auto-dry is due (print ended + {@link #AUTO_DELAY}). */
    private final ConcurrentHashMap<String, Instant> autoDue = new ConcurrentHashMap<>();
    /** printer|ams → the auto-dry start the app sent, so a refusal can be retried once instead of alerted. */
    private final ConcurrentHashMap<String, AutoAttempt> autoAttempts = new ConcurrentHashMap<>();
    /** printer|ams → when a retry is due. */
    private final ConcurrentHashMap<String, Instant> retryDue = new ConcurrentHashMap<>();
    /**
     * printer|ams → when a dry the printer ACCEPTED should end (accepted at + its hours). The H2D keeps drying
     * through a print that starts mid-dry, and a second start while it is still drying is refused with
     * 0x0500C04C - which is what the "refused" alerts of 2026-10-15 18:06 and 10-17 00:21 were: both prints
     * started inside the 4 h dry begun after the previous print. The printer's own dry_time isn't relied on
     * alone because it isn't known to be reported while a print runs.
     */
    private final ConcurrentHashMap<String, Instant> dryUntil = new ConcurrentHashMap<>();

    record AutoAttempt(int attempt, Instant sentAt) {
    }

    private static String key(final BambuPrinter printer, final int amsId) {
        return printer.getName() + "|" + amsId;
    }

    /** Why this unit counts as drying already, or null when it doesn't. */
    private String alreadyDrying(final BambuPrinter printer, final int amsId) {
        final BambuPrinter.AmsDryState state = printer.getAmsDryStates().get(amsId);
        if (state != null && state.isDrying()) {
            return "the printer reports %dh %02dm of drying left".formatted(state.minutesLeft() / 60, state.minutesLeft() % 60);
        }
        final Instant until = dryUntil.get(key(printer, amsId));
        if (until != null && until.isAfter(Instant.now())) {
            final long mins = Duration.between(Instant.now(), until).toMinutes();
            return "the dry it accepted earlier is still running, about %dh %02dm left".formatted(mins / 60, mins % 60);
        }
        return null;
    }

    private int hoursOf(final BambuPrinter printer, final int amsId) {
        final BambuPrinter.AmsDryState state = printer.getAmsDryStates().get(amsId);
        return state != null && state.hours() > 0 ? state.hours() : getSetting(printer.getName()).durationHours();
    }

    /**
     * Fires auto-dry when a print finishes. Lives here (server-side, like PrintAiService's state watcher)
     * rather than in the dashboard UI tick, so it works with no browser open - the whole point of
     * "auto on finish" - and fires exactly once per transition instead of once per open browser tab.
     */
    @Scheduled(every = "30s")
    void watchAutoDry() {
        printers.getPrinters().forEach(printer -> {
            trackAccepted(printer);
            checkAnswers(printer);
            runDue(printer);
            final BambuConst.GCodeState current = printer.getGCodeState();
            final BambuConst.GCodeState previous = lastStates.put(printer.getName(), current);
            if (previous == null || previous == current || !previous.isPrinting()) {
                return;
            }
            // FINISH = normal completion; IDLE = printer went straight past FINISH to idle
            if (current != BambuConst.GCodeState.FINISH && current != BambuConst.GCodeState.IDLE) {
                return;
            }
            if (!getSetting(printer.getName()).autoOnFinish()) {
                return;
            }
            autoDue.put(printer.getName(), Instant.now().plus(AUTO_DELAY));
            Log.infof("AmsDryService: %s: print ended - auto-dry in %d min", printer.getName(), AUTO_DELAY.toMinutes());
        });
    }

    private void runDue(final BambuPrinter printer) {
        final Instant now = Instant.now();
        final Instant due = autoDue.get(printer.getName());
        if (due != null && !due.isAfter(now)) {
            autoDue.remove(printer.getName());
            if (printer.getGCodeState().isPrinting()) {
                Log.infof("AmsDryService: %s: auto-dry skipped - a new print started first", printer.getName());
            } else {
                dryUnits(printer).forEach(amsId -> startAuto(printer, amsId, 1));
            }
        }
        retryDue.forEach((k, at) -> {
            if (!k.startsWith(printer.getName() + "|") || at.isAfter(now)) {
                return;
            }
            retryDue.remove(k);
            final int amsId = Integer.parseInt(k.substring(k.lastIndexOf('|') + 1));
            if (printer.getGCodeState().isPrinting()) {
                Log.infof("AmsDryService: %s: AMS #%d auto-dry retry skipped - a print started", printer.getName(), amsId);
                return;
            }
            startAuto(printer, amsId, 2);
        });
    }

    private List<Integer> dryUnits(final BambuPrinter printer) {
        return printer.getModules().stream()
                .filter(m -> m.getAmsType().isSupportsDrying())
                .map(BambuPrinter.ModuleInfo::unitIndex)
                .distinct()
                .toList();
    }

    private void startAuto(final BambuPrinter printer, final int amsId, final int attempt) {
        final DrySetting setting = getSetting(printer.getName());
        if (!canDry(printer, amsId)) {
            Log.infof("AmsDryService: %s: AMS #%d not auto-dried - the printer doesn't report drying support for it",
                    printer.getName(), amsId);
            return;
        }
        final String drying = alreadyDrying(printer, amsId);
        if (drying != null) {
            Log.infof("AmsDryService: %s: AMS #%d not auto-dried - already drying (%s)", printer.getName(), amsId, drying);
            return;
        }
        final String filament = loadedFilament(printer, amsId);
        if (filament.isBlank()) {
            Log.infof("AmsDryService: %s: AMS #%d not auto-dried - no filament loaded", printer.getName(), amsId);
            return;
        }
        autoAttempts.put(key(printer, amsId), new AutoAttempt(attempt, Instant.now().minusSeconds(1)));
        printer.commandAmsDry(amsId, filament, setting.temp(), setting.durationHours());
        Log.infof("AmsDryService: %s: auto-drying AMS #%d (%s, %d°C / %dh)%s", printer.getName(), amsId, filament,
                setting.temp(), setting.durationHours(), attempt > 1 ? " - retry" : "");
    }

    /** Remembers when an accepted dry ends, and forgets it on an accepted stop. */
    private void trackAccepted(final BambuPrinter printer) {
        printer.getAmsDryStates().values().forEach(state -> {
            final BambuPrinter.DryCommand cmd = state.lastCommand();
            if (cmd == null || !"success".equalsIgnoreCase(cmd.result())) {
                return;
            }
            final String k = key(printer, state.amsId());
            if (!cmd.start()) {
                dryUntil.remove(k);
                return;
            }
            final Instant end = cmd.sentAt().plus(Duration.ofHours(Math.max(1, hoursOf(printer, state.amsId()))));
            final Instant known = dryUntil.get(k);
            // Only from a newer accepted start - the same command is seen every 30 s
            if (known == null || end.isAfter(known)) {
                dryUntil.put(k, end);
            }
        });
    }

    /**
     * One alert per refused or unanswered start, so an unattended auto-dry that didn't happen doesn't go
     * unnoticed - except a refusal because the unit is drying already (nothing is wrong), and the first refusal
     * of an auto-dry, which is retried once after {@link #RETRY_DELAY} before anyone is told.
     */
    private void checkAnswers(final BambuPrinter printer) {
        printer.getAmsDryStates().values().forEach(state -> {
            final BambuPrinter.DryCommand cmd = state.lastCommand();
            if (cmd == null || !cmd.start()) {
                return;
            }
            final boolean noAnswer = cmd.pending() && Duration.between(cmd.sentAt(), Instant.now()).compareTo(NO_ANSWER) > 0;
            if (!cmd.failed() && !noAnswer) {
                return;
            }
            if (!alerted.add(printer.getName() + "|" + cmd.sequenceId())) {
                return;
            }
            final String k = key(printer, state.amsId());
            final String drying = alreadyDrying(printer, state.amsId());
            if (cmd.failed() && drying != null) {
                Log.infof("AmsDryService: %s: AMS #%d refused a dry start because it is already drying (%s) - nothing to do",
                        printer.getName(), state.amsId(), drying);
                autoAttempts.remove(k);
                return;
            }
            final AutoAttempt auto = autoAttempts.get(k);
            if (auto != null && !cmd.sentAt().isBefore(auto.sentAt()) && auto.attempt() == 1) {
                retryDue.put(k, Instant.now().plus(RETRY_DELAY));
                Log.infof("AmsDryService: %s: AMS #%d %s the auto-dry - trying again in %d min", printer.getName(),
                        state.amsId(), noAnswer ? "didn't answer" : "refused", RETRY_DELAY.toMinutes());
                return;
            }
            autoAttempts.remove(k);
            final String what = noAnswer
                    ? "did not answer a drying command for AMS #%d".formatted(state.amsId())
                    : "refused to start drying AMS #%d%s".formatted(state.amsId(),
                            cmd.errCode() != 0 ? " (error 0x%08X)".formatted(cmd.errCode()) : "");
            notificationService.notifyEvent("dry_failed", printer.getName(),
                    "%s %s%s. Nothing is drying - start it again from the dashboard or Bambu Studio.".formatted(
                            printer.getName(), what, auto != null && auto.attempt() > 1 ? ", twice" : ""));
        });
    }

}
