package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic "Retry" for the AMS feed errors that, on this farm, usually clear on a retry:
 * <ul>
 * <li>{@code 0700_8010} - AMS assist motor is overloaded</li>
 * <li>{@code 0700_8005} - Failed to feed the filament outside the AMS</li>
 * <li>{@code 0700_8006} - Failed to feed the filament into the toolhead (added 2026-10-04 after the H2D sat on
 * it five times in five minutes with nothing retrying; the printer's own text for it ends "click Retry")</li>
 * </ul>
 * The third byte of the code is the AMS unit (00-03, FF = external spool), so matching masks it out.
 * <p>
 * The printer pauses with the code in {@code print_error}. Bambu Studio's Retry button for these sends
 * {@code ams_control} / {@code resume}, so that is what goes out here - not {@code clean_print_error}, which
 * only dismisses the dialog. Retries are bounded two ways so a genuinely stuck spool can't be hammered all
 * night: {@link #MAX_ATTEMPTS_PER_ERROR} per error occurrence (an occurrence ends when the code clears) and
 * {@link #MAX_ATTEMPTS_PER_HOUR} per printer. Once either runs out the printer stays paused and an
 * {@code ams_retry} alert says so; a human is needed for that one anyway.
 * <p>
 * Global on/off only, persisted in {@code bambu-ams-retry.json}; the switch lives on the Automation overview.
 */
@ApplicationScoped
public class AmsRetryService {

    private static final String STORE_FILENAME = "bambu-ams-retry.json";
    /** Mask keeping the module (07 = AMS) and the error, dropping the AMS-unit byte. */
    private static final int CODE_MASK = 0xFF00FFFF;
    private static final Set<Integer> RETRYABLE = Set.of(0x07008010, 0x07008005, 0x07008006);
    public static final int MAX_ATTEMPTS_PER_ERROR = 3;
    public static final int MAX_ATTEMPTS_PER_HOUR = 8;
    /** Let the printer finish reporting the pause before the first retry, and let a retry play out before the next. */
    private static final Duration SETTLE = Duration.ofSeconds(20);
    private static final Duration BETWEEN_ATTEMPTS = Duration.ofSeconds(60);
    /**
     * The same code coming back this soon after a retry cleared it is the same problem, not a new one. Until
     * 2026-11-03 every clear ended the occurrence, so a spool that fed for a minute after each Retry got "Retry 1/3"
     * eight times in a row (only the hourly cap stopped it) and sent 16 alerts in 8 minutes - which buried a real
     * spaghetti alert from another printer.
     */
    static final Duration RECUR_WINDOW = Duration.ofMinutes(10);

    /** One error occurrence on one printer: same code, from first sight until it clears. */
    private static final class Episode {
        final int code;
        final Instant firstSeen;
        int attempts;
        Instant lastAttempt;
        boolean gaveUp;

        Episode(final int code, final Instant firstSeen) {
            this.code = code;
            this.firstSeen = firstSeen;
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
    @Inject
    SimulationService simulation;
    /** Lazy: PrintAiService is heavy and only needed for the alert photo. */
    @Inject
    Instance<PrintAiService> aiServiceInstance;

    private final Map<String, Boolean> settings = new ConcurrentHashMap<>();
    private final Map<String, Episode> episodes = new ConcurrentHashMap<>();
    /** Occurrences that cleared after at least one retry, kept for {@link #RECUR_WINDOW} in case the code returns. */
    private final Map<String, Episode> cleared = new ConcurrentHashMap<>();
    private final Map<String, Instant> clearedAt = new ConcurrentHashMap<>();
    /** Retry timestamps per printer for the rolling hourly cap. */
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Boolean>>() {
            }));
            Log.infof("AmsRetryService: settings loaded from %s (enabled=%s)", path, isEnabled());
        } catch (IOException ex) {
            Log.errorf(ex, "AmsRetryService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "AmsRetryService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    public boolean isEnabled() {
        return settings.getOrDefault("enabled", Boolean.FALSE);
    }

    public void setEnabled(final boolean enabled) {
        settings.put("enabled", enabled);
        save();
        Log.infof("AmsRetryService: AMS auto-retry %s", enabled ? "enabled" : "disabled");
    }

    /** True for the three feed errors, whichever AMS unit (or the external spool) raised them. */
    public static boolean isRetryable(final int printError) {
        return printError != 0 && RETRYABLE.contains(printError & CODE_MASK);
    }

    @Scheduled(every = "15s", identity = "ams-retry")
    synchronized void tick() {
        final Instant now = Instant.now();
        for (final BambuPrinter printer : printers.getPrinters()) {
            final String name = printer.getName();
            final int code = printer.getPrintError();
            if (code == 0) {
                // Error cleared (retry worked, or a human dealt with it) - the occurrence is over.
                final Episode done = episodes.remove(name);
                if (done != null && done.attempts > 0) {
                    Log.infof("AmsRetryService: %s: error [%s] cleared after %d automatic retr%s",
                            name, Integer.toHexString(done.code), done.attempts, done.attempts == 1 ? "y" : "ies");
                    cleared.put(name, done);
                    clearedAt.put(name, now);
                }
                continue;
            }
            if (!isEnabled() || !isRetryable(code) || simulation.isEnabled()) {
                continue;
            }
            if (printer.getGCodeState() != BambuConst.GCodeState.PAUSE) {
                // Not paused (yet, or any more) - nothing to retry. A retry sent while RUNNING is a no-op at
                // best, so wait for the printer to actually stop.
                continue;
            }
            Episode ep = episodes.get(name);
            if (ep == null || ep.code != code) {
                final Episode before = cleared.remove(name);
                final Instant beforeAt = clearedAt.remove(name);
                if (isRecurrence(before == null ? 0 : before.code, beforeAt, code, now)) {
                    ep = before;
                    Log.infof("AmsRetryService: %s: error [%s] came back %ds after a retry cleared it - same problem, "
                            + "%d of %d retries used", name, Integer.toHexString(code),
                            Duration.between(beforeAt, now).toSeconds(), ep.attempts, MAX_ATTEMPTS_PER_ERROR);
                } else {
                    ep = new Episode(code, now);
                    Log.infof("AmsRetryService: %s: paused on retryable error [%s] %s", name, Integer.toHexString(code),
                            BambuErrors.getPrinterError(code).orElse(""));
                }
                episodes.put(name, ep);
            }
            if (ep.gaveUp) {
                continue;
            }
            final Instant since = ep.lastAttempt != null ? ep.lastAttempt : ep.firstSeen;
            final Duration wait = ep.lastAttempt != null ? BETWEEN_ATTEMPTS : SETTLE;
            if (Duration.between(since, now).compareTo(wait) < 0) {
                continue;
            }
            final Deque<Instant> window = recent.computeIfAbsent(name, k -> new ArrayDeque<>());
            while (!window.isEmpty() && Duration.between(window.peekFirst(), now).toHours() >= 1) {
                window.pollFirst();
            }
            if (ep.attempts >= MAX_ATTEMPTS_PER_ERROR || window.size() >= MAX_ATTEMPTS_PER_HOUR) {
                ep.gaveUp = true;
                final String why = ep.attempts >= MAX_ATTEMPTS_PER_ERROR
                        ? "%d automatic retries".formatted(ep.attempts)
                        : "%d retries in the last hour".formatted(window.size());
                Log.warnf("AmsRetryService: %s: still paused on [%s] after %s - giving up, needs a human",
                        name, Integer.toHexString(code), why);
                notificationService.notifyEvent("ams_retry", name,
                        "Still paused on AMS error [%s] after %s - NOT retrying again, check the spool. %s"
                                .formatted(Integer.toHexString(code), why, BambuErrors.getPrinterError(code).orElse("")),
                        snapshot(name));
                continue;
            }
            ep.attempts++;
            ep.lastAttempt = now;
            window.addLast(now);
            printer.commandAmsControl("resume");
            Log.infof("AmsRetryService: %s: sent Retry %d/%d for [%s]", name, ep.attempts, MAX_ATTEMPTS_PER_ERROR,
                    Integer.toHexString(code));
            // In the app's notification log only: a retry that works needs nobody, and one that doesn't ends in the
            // "NOT retrying again" alert above, which does go out.
            notificationService.logOnly("ams_retry", name,
                    "AMS error [%s] - automatic Retry %d/%d sent (%s)".formatted(Integer.toHexString(code),
                            ep.attempts, MAX_ATTEMPTS_PER_ERROR, BambuErrors.getPrinterError(code).orElse("")),
                    snapshot(name));
        }
    }

    /** True when {@code code} is the same error that a retry cleared less than {@link #RECUR_WINDOW} ago. */
    static boolean isRecurrence(final int clearedCode, final Instant clearedAt, final int code, final Instant now) {
        return clearedAt != null && clearedCode == code
                && Duration.between(clearedAt, now).compareTo(RECUR_WINDOW) < 0;
    }

    private byte[] snapshot(final String printer) {
        try {
            return aiServiceInstance.get().getSnapshot(printer).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
