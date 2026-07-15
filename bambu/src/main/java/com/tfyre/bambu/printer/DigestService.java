package com.tfyre.bambu.printer;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Optional daily farm digest, sent to the configured notification channels (Discord/ntfy/MQTT).
 * Off by default - enable by setting a cron expression, e.g. every morning at 07:00:
 * <pre>{@code bambu.digest-cron=0 0 7 * * ?}</pre>
 * Contents: prints finished/failed in the last 24h (with filament used), open marketplace orders,
 * queued jobs, and any printer currently in an error state.
 */
@ApplicationScoped
public class DigestService {

    @Inject
    PrintHistoryService historyService;
    @Inject
    PrintQueueService queueService;
    @Inject
    BambuPrinters printers;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;
    @Inject
    NotificationService notificationService;

    @Scheduled(cron = "${bambu.digest-cron:off}")
    void sendDigest() {
        final OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        final List<PrintHistoryService.PrintJob> recent = historyService.getJobs().stream()
                .filter(j -> j.ended().isAfter(since))
                .toList();
        final long finished = recent.stream().filter(j -> "Finished".equals(j.result())).count();
        final long failed = recent.size() - finished;
        final double grams = recent.stream().mapToDouble(PrintHistoryService.PrintJob::grams).sum();
        final int openOrders = etsyPolling.getReceipts().size() + ebayPolling.getOrders().size();
        final int queued = printers.getPrintersDetail().stream().mapToInt(d -> queueService.size(d.name())).sum();
        final long errors = printers.getPrinters().stream().filter(p -> p.getPrintError() != 0).count();

        final String text = "Farm digest: last 24h %d print(s) finished, %d failed/stopped (%.0fg filament). "
                .formatted(finished, failed, grams)
                + "Right now: %d open order(s), %d job(s) queued%s."
                        .formatted(openOrders, queued, errors > 0 ? ", %d printer(s) with errors".formatted(errors) : "");
        Log.infof("DigestService: %s", text);
        notificationService.notifyEvent("digest", "farm", text);
    }

}
