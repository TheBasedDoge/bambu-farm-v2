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
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "This printer has been done for 30 minutes and order work is waiting for it" - one urgent alert.
 * <p>
 * 2026-11-12 morning: all four P1s sat finished for 2-5 hours with their parts still on the plate while order jobs
 * waited in the pool. Every one of them had raised a "bed is not clear" alert, but those are routine - they go out
 * for every blocked check and read as the farm working as designed, not as a printer losing time.
 * <p>
 * Fires for a printer that is FINISH or FAILED, not marked cleared, not held for a fault, has been in that state
 * for {@link #AFTER}, and has work it could take: a pool job it can print (right printer type and filament) or
 * its own queue. Once per printer per finish, again every {@link #REPEAT} while it keeps waiting. Not inside the
 * quiet window (Automation page, default 23:00-07:00, kept in {@code data/bambu-bed-waiting.json}) - it is urgent
 * (@here), so it goes out when the window ends instead.
 */
@ApplicationScoped
public class BedWaitingService {

    static final Duration AFTER = Duration.ofMinutes(30);
    static final Duration REPEAT = Duration.ofHours(2);
    static final LocalTime DEFAULT_QUIET_FROM = LocalTime.of(23, 0);
    static final LocalTime DEFAULT_QUIET_UNTIL = LocalTime.of(7, 0);
    private static final String STORE_FILENAME = "bambu-bed-waiting.json";

    private record Reminder(Instant epoch, Instant at) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    BambuPrinters printers;
    @Inject
    BedClearService bedClear;
    @Inject
    FaultHoldService faultHold;
    @Inject
    NotificationService notifications;
    @Inject
    Instance<DispatchQueueService> dispatch;
    @Inject
    Instance<AutoQueueService> autoQueue;
    @Inject
    Instance<AutoStartService> autoStart;
    @Inject
    Instance<PrintQueueService> queue;
    @Inject
    Instance<PrintAiService> ai;

    private final Map<String, Reminder> reminded = new ConcurrentHashMap<>();
    /** "quietFrom" / "quietUntil" as HH:mm; both blank = no quiet window. */
    private final Map<String, String> settings = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        final Path f = path();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            settings.putAll(mapper.readValue(f.toFile(), new TypeReference<Map<String, String>>() {
            }));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("BedWaitingService: could not read %s: %s", f, ex.getMessage());
        }
    }

    private Path path() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve(STORE_FILENAME);
    }

    private Optional<LocalTime> time(final String key, final LocalTime fallback) {
        if (!settings.containsKey(key)) {
            return Optional.of(fallback);
        }
        try {
            final String v = settings.get(key);
            return v == null || v.isBlank() ? Optional.empty() : Optional.of(LocalTime.parse(v));
        } catch (RuntimeException ex) {
            return Optional.of(fallback);
        }
    }

    /** Start of the quiet window; empty = no quiet window. */
    public Optional<LocalTime> getQuietFrom() {
        return time("quietFrom", DEFAULT_QUIET_FROM);
    }

    public Optional<LocalTime> getQuietUntil() {
        return time("quietUntil", DEFAULT_QUIET_UNTIL);
    }

    /** Both null (or equal) turns the quiet window off. */
    public void setQuietWindow(final LocalTime from, final LocalTime until) {
        final boolean off = from == null || until == null || from.equals(until);
        settings.put("quietFrom", off ? "" : from.toString());
        settings.put("quietUntil", off ? "" : until.toString());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(path().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf("BedWaitingService: could not write %s: %s", path(), ex.getMessage());
        }
        Log.infof("BedWaitingService: quiet window %s", off ? "off" : from + "-" + until);
    }

    /** "23:00-07:00", or "off". */
    public String describeQuietWindow() {
        final Optional<LocalTime> from = getQuietFrom();
        final Optional<LocalTime> until = getQuietUntil();
        return from.isPresent() && until.isPresent() ? from.get() + "-" + until.get() : "off";
    }

    /** Inside [from, until), wrapping past midnight when until is earlier than from. */
    static boolean quiet(final LocalTime t, final LocalTime from, final LocalTime until) {
        if (from == null || until == null || from.equals(until)) {
            return false;
        }
        return from.isBefore(until)
                ? !t.isBefore(from) && t.isBefore(until)
                : !t.isBefore(from) || t.isBefore(until);
    }

    boolean quiet(final LocalTime t) {
        return quiet(t, getQuietFrom().orElse(null), getQuietUntil().orElse(null));
    }

    /** Whether to remind now: waited long enough, and either never reminded for this finish or not for REPEAT. */
    static boolean due(final Instant epoch, final Instant now, final Optional<Instant> lastEpoch, final Optional<Instant> lastAt) {
        if (Duration.between(epoch, now).compareTo(AFTER) < 0) {
            return false;
        }
        if (lastEpoch.isEmpty() || !lastEpoch.get().equals(epoch)) {
            return true;
        }
        return lastAt.map(at -> Duration.between(at, now).compareTo(REPEAT) >= 0).orElse(true);
    }

    @Scheduled(every = "5m", delayed = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void check() {
        if (!autoStart.get().isGloballyEnabled()) {
            return;
        }
        final Instant now = Instant.now();
        final boolean quietNow = quiet(LocalTime.now());
        final List<DispatchQueueService.PendingJob> pool = dispatch.get().getPool().stream()
                .filter(j -> dispatch.get().getParkedReason(j.id()).isEmpty())
                .toList();
        for (final BambuPrinters.PrinterDetail pd : printers.getPrintersDetail()) {
            final String name = pd.name();
            final BambuConst.GCodeState state = pd.printer().getGCodeState();
            if (state != BambuConst.GCodeState.FINISH && state != BambuConst.GCodeState.FAILED) {
                reminded.remove(name);
                continue;
            }
            if (bedClear.isCleared(name) || faultHold.isHeld(name) || !autoQueue.get().isPrinterEnabled(name)) {
                continue;
            }
            final List<DispatchQueueService.PendingJob> canTake = pool.stream()
                    .filter(j -> autoQueue.get().resolveSlot(pd, j.part()).isPresent())
                    .toList();
            final int ownQueue = queue.get().size(name);
            if (canTake.isEmpty() && ownQueue == 0) {
                continue;
            }
            final Optional<Instant> epoch = autoStart.get().stateSince(name);
            if (epoch.isEmpty() || quietNow) {
                continue;
            }
            final Reminder last = reminded.get(name);
            if (!due(epoch.get(), now, Optional.ofNullable(last).map(Reminder::epoch), Optional.ofNullable(last).map(Reminder::at))) {
                continue;
            }
            reminded.put(name, new Reminder(epoch.get(), now));
            final TreeSet<String> orders = new TreeSet<>();
            canTake.forEach(j -> orders.add(j.orderRef() == null ? "no order" : j.orderRef().label()));
            final int jobs = canTake.size() + ownQueue;
            final String message = "%s %s %s ago and its bed hasn't been cleared - %d job%s waiting that it could print%s. Take the part off and press Bed cleared."
                    .formatted(name, state == BambuConst.GCodeState.FAILED ? "failed" : "finished",
                            DispatchQueueService.formatMinutes((int) Duration.between(epoch.get(), now).toMinutes()),
                            jobs, jobs == 1 ? "" : "s", orders.isEmpty() ? "" : " (" + String.join(", ", orders) + ")");
            notifications.notifyEvent("bed_waiting", name, message, ai.get().getSnapshot(name).orElse(null));
        }
    }
}
