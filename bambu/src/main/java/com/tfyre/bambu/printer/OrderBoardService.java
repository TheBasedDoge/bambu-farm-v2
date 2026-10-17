package com.tfyre.bambu.printer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Where each open order stands, worked out in one place so the Orders page and the sidebar count cannot
 * disagree: has it been queued, how much of it is printed, taken from stock, printing right now or still
 * waiting for a printer.
 */
@ApplicationScoped
public class OrderBoardService {

    /** In the order a person would deal with them. */
    public enum Stage {
        /** A part failed and nothing will reprint it. */
        FAILED,
        /** No print jobs were ever queued for it - unmapped, personalized, or auto-queue was off. */
        NOT_QUEUED,
        /** Everything it needs is printed or came off the shelf. */
        READY,
        /** At least one of its parts is on a printer now. */
        PRINTING,
        /** Queued, nothing on a printer yet. */
        WAITING;

        public boolean needsYou() {
            return this == FAILED || this == NOT_QUEUED;
        }
    }

    /**
     * @param expected  print jobs the order is waiting on in total (finished ones included)
     * @param abandoned jobs that failed and were not re-queued
     * @param fromStock pieces taken off the shelf for it
     * @param printing  jobs on a printer now
     * @param waiting   jobs in the dispatch pool
     */
    public record Entry(Stage stage, int printed, int expected, int abandoned, int fromStock, int printing, int waiting) {
    }

    @Inject
    OrderTrackingService tracking;
    @Inject
    StockService stock;
    @Inject
    PrintHistoryService history;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;

    /** The label the pollers give an Etsy order when they queue it - stock taken for it is recorded under this. */
    public static String label(final EtsyApiClient.Receipt r) {
        return "Etsy order #%d (%s)".formatted(r.receiptId(), r.buyerName());
    }

    public static String label(final EbayApiClient.Order o) {
        return "eBay order %s (%s)".formatted(o.orderId(), o.buyerUsername());
    }

    public Entry entry(final String market, final String orderId, final String label) {
        final Optional<OrderTrackingService.ProgressView> progress = tracking.progress(market).stream()
                .filter(p -> orderId.equals(p.orderId())).findFirst();
        final boolean queued = progress.isPresent() || tracking.queuedAt(market, orderId).isPresent();
        final int fromStock = stock.takenFor(label).values().stream().mapToInt(Integer::intValue).sum();
        final int printing = history.inFlightFiles(market, orderId).size();
        final int waiting = (int) dispatch.getPool().stream()
                .filter(j -> j.orderRef() != null && market.equals(j.orderRef().market()) && orderId.equals(j.orderRef().orderId()))
                .count();
        final int printed = progress.map(OrderTrackingService.ProgressView::printed).orElse(0);
        final int expected = progress.map(OrderTrackingService.ProgressView::expected).orElse(0);
        final int abandoned = progress.map(OrderTrackingService.ProgressView::abandoned).orElse(0);

        final Stage stage;
        if (abandoned > 0) {
            stage = Stage.FAILED;
        } else if (!queued) {
            stage = Stage.NOT_QUEUED;
        } else if (progress.map(OrderTrackingService.ProgressView::complete).orElse(fromStock > 0)) {
            stage = Stage.READY;
        } else {
            stage = printing > 0 ? Stage.PRINTING : Stage.WAITING;
        }
        return new Entry(stage, printed, expected, abandoned, fromStock, printing, waiting);
    }

    /** Open orders that need a person: a failed part, or nothing queued. For the sidebar count. */
    public int needsYou() {
        int n = 0;
        for (final EtsyApiClient.Receipt r : etsyPolling.getReceipts()) {
            if (entry("etsy", String.valueOf(r.receiptId()), label(r)).stage().needsYou()) {
                n++;
            }
        }
        for (final EbayApiClient.Order o : ebayPolling.getOrders()) {
            if (entry("ebay", o.orderId(), label(o)).stage().needsYou()) {
                n++;
            }
        }
        return n;
    }
}
