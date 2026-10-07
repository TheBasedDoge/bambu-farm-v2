package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily farm digest, sent to the configured notification channels (Discord/ntfy/MQTT).
 * <p>
 * Two ways to schedule it. The one you can reach from the app: a time of day on the Notification Settings
 * page, persisted in {@code bambu-digest.json} and checked once a minute. The original: a cron expression in
 * config, {@code bambu.digest-cron=0 0 7 * * ?}. Before 2026-09-16 only the cron existed, and the "Daily
 * Digest" checkbox on the settings page - which only controls suppression - read as if it were the switch; the
 * digest sat "enabled" for weeks and never fired once.
 * <p>
 * Contents are built around the thing the numbers say costs the most: finished parts sitting on beds. In 24
 * days of history the median gap between a print ending and the next one starting on that printer was 7.6
 * hours, so "which beds need clearing, and what is waiting for them" leads; the last-24h tallies follow.
 */
@ApplicationScoped
public class DigestService {

    private static final String STORE_FILENAME = "bambu-digest.json";

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    PrintHistoryService historyService;
    @Inject
    PrintQueueService queueService;
    @Inject
    DispatchQueueService dispatchQueue;
    @Inject
    BambuPrinters printers;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    NotificationService notificationService;

    private final Map<String, String> settings = new ConcurrentHashMap<>();
    /** The local date the time-of-day schedule last fired, so a minute-long window can't send twice. */
    private LocalDate lastSent;

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = getPath();
        if (Files.exists(path)) {
            try {
                settings.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, String>>() {
                }));
            } catch (IOException ex) {
                Log.errorf(ex, "DigestService: cannot load %s: %s", path, ex.getMessage());
            }
        }
        Log.infof("DigestService: daily digest %s", getTime().map(t -> "at " + t + " (from " + STORE_FILENAME + ")")
                .orElse("has no time set - set one on the Notification Settings page"
                        + (config.digestCron().filter(c -> !"off".equalsIgnoreCase(c)).isPresent() ? "; cron schedule is active" : "")));
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), settings);
        } catch (IOException ex) {
            Log.errorf(ex, "DigestService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    /** Local time of day the digest goes out, if one is set. */
    public Optional<LocalTime> getTime() {
        final String t = settings.get("time");
        if (t == null || t.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(t));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /** {@code null} clears the schedule. */
    public void setTime(final LocalTime time) {
        if (time == null) {
            settings.remove("time");
        } else {
            settings.put("time", time.withSecond(0).withNano(0).toString());
        }
        lastSent = null;
        save();
        Log.infof("DigestService: daily digest %s", time == null ? "time cleared" : "scheduled for " + settings.get("time"));
    }

    @Scheduled(every = "1m", identity = "digest-time-of-day")
    void tick() {
        final Optional<LocalTime> at = getTime();
        if (at.isEmpty()) {
            return;
        }
        final LocalTime now = LocalTime.now().withSecond(0).withNano(0);
        final LocalDate today = LocalDate.now();
        if (now.equals(at.get()) && !today.equals(lastSent)) {
            lastSent = today;
            sendDigest();
        }
    }

    @Scheduled(cron = "${bambu.digest-cron:off}", identity = "digest-cron")
    void sendDigest() {
        notificationService.notifyEvent("digest", "farm", buildDigest());
    }

    /** The "Send now" button: same text, bypasses the schedule (but not the event filter). */
    public String sendNow() {
        final String text = buildDigest();
        notificationService.notifyEvent("digest", "farm", text);
        return text;
    }

    String buildDigest() {
        final OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        final List<PrintHistoryService.PrintJob> recent = historyService.getJobs().stream()
                .filter(j -> j.ended() != null && j.ended().isAfter(since))
                .toList();
        final long finished = recent.stream().filter(j -> "Finished".equals(j.result())).count();
        final long failed = recent.size() - finished;
        final double grams = recent.stream().mapToDouble(PrintHistoryService.PrintJob::grams).sum();
        final int openOrders = etsyPolling.getReceipts().size() + ebayPolling.getOrders().size();
        final int queued = printers.getPrintersDetail().stream().mapToInt(d -> queueService.size(d.name())).sum();
        final int pooled = dispatchQueue.size();
        final int parked = dispatchQueue.parkedCount();
        final List<String> errored = printers.getPrinters().stream()
                .filter(p -> p.getPrintError() != 0).map(BambuPrinter::getName).sorted().toList();
        // FINISH is the state a P1/X1 sits in from the end of a print until the next one starts - i.e. the part
        // is still on the plate. That list is the digest's reason to exist.
        final List<String> finishedBeds = printers.getPrinters().stream()
                .filter(p -> p.getGCodeState() == BambuConst.GCodeState.FINISH).map(BambuPrinter::getName).sorted().toList();
        final long printing = printers.getPrinters().stream().filter(p -> p.getGCodeState().isPrinting()).count();

        final StringBuilder sb = new StringBuilder("Farm digest. ");
        if (finishedBeds.isEmpty()) {
            sb.append("No finished parts waiting on beds. ");
        } else {
            sb.append("%d finished part(s) waiting on beds: %s. ".formatted(finishedBeds.size(), String.join(", ", finishedBeds)));
        }
        if (pooled > 0 || parked > 0) {
            sb.append("%d order job(s) waiting for a clear bed%s. ".formatted(pooled,
                    parked > 0 ? " (%d parked after repeated failures)".formatted(parked) : ""));
        }
        sb.append("Now: %d printing, %d open order(s), %d job(s) in printer queues%s. "
                .formatted(printing, openOrders, queued,
                        errored.isEmpty() ? "" : ", errors on " + String.join(", ", errored)));
        sb.append("Last 24h: %d finished, %d failed/stopped, %.0f g filament."
                .formatted(finished, failed, grams));
        return sb.toString();
    }

}
