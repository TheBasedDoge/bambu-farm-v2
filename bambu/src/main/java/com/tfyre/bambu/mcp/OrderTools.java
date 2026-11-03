package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.printer.DispatchQueueService;
import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.NotificationService;
import com.tfyre.bambu.printer.OrderBoardService;
import com.tfyre.bambu.printer.PrintQueueService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Phase 1 order tools (docs/MCP-PLAN.md): {@code orders} and {@code dispatch_pool}. Read-only. */
@Singleton
public class OrderTools {

    @Inject
    EtsyOrderPollingService etsy;
    @Inject
    EbayOrderPollingService ebay;
    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;
    @Inject
    OrderBoardService board;
    @Inject
    NotificationService notifications;
    @Inject
    DispatchQueueService dispatch;
    @Inject
    PrintQueueService queue;
    @Inject
    BambuPrinters printers;
    @Inject
    McpAudit audit;

    // ---------------------------------------------------------------------------------------------------------
    // orders
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "orders", description = """
            Open Etsy and eBay orders as the Orders page shows them (unshipped, not dismissed), newest first. Per \
            order: market, id, created, buyer, buyer note, board stage (FAILED / NOT_QUEUED / READY / PRINTING / \
            WAITING), parts printed of expected, pieces from stock, jobs printing and waiting, and per line the \
            title, quantity, variations, whether it is personalised and the mapped print files. NOT_QUEUED orders \
            include the latest auto-queue skip reason when one was logged.""",
            annotations = @Tool.Annotations(title = "Open orders", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse orders(
            @ToolArg(description = "etsy, ebay, or omit for both", required = false) final String market,
            @ToolArg(description = "Only orders in this stage, e.g. NOT_QUEUED or FAILED; omit for all", required = false) final String stage) {
        return audit.run("orders", McpAudit.args("market", market, "stage", stage),
                () -> audit.json(buildOrders(market, stage)));
    }

    Map<String, Object> buildOrders(final String market, final String stage) {
        final List<NotificationService.LogEntry> skips = notifications.getLog().stream()
                .filter(e -> "auto_queue_skipped".equals(e.event())).toList();
        final List<Map<String, Object>> list = new ArrayList<>();
        if (wants(market, "etsy")) {
            etsy.getReceipts().forEach(r -> list.add(etsyOrder(r, skips)));
        }
        if (wants(market, "ebay")) {
            ebay.getOrders().forEach(o -> list.add(ebayOrder(o, skips)));
        }
        final List<Map<String, Object>> shown = list.stream()
                .filter(o -> stage == null || stage.isBlank() || stage.equalsIgnoreCase(String.valueOf(o.get("stage"))))
                .sorted(Comparator.comparing((Map<String, Object> o) -> String.valueOf(o.get("created"))).reversed())
                .toList();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", shown.size());
        final Map<String, Long> byStage = new LinkedHashMap<>();
        list.forEach(o -> byStage.merge(String.valueOf(o.get("stage")), 1L, Long::sum));
        out.put("byStage", byStage);
        out.put("orders", shown);
        return out;
    }

    private Map<String, Object> etsyOrder(final EtsyApiClient.Receipt r, final List<NotificationService.LogEntry> skips) {
        final String id = String.valueOf(r.receiptId());
        final Map<String, Object> m = header("etsy", id, r.createTimestamp(), r.buyerName(), r.buyerNote(),
                board.entry("etsy", id, OrderBoardService.label(r)), skips);
        final List<Map<String, Object>> lines = new ArrayList<>();
        for (final EtsyApiClient.Transaction t : r.transactions()) {
            final Map<String, Object> l = new LinkedHashMap<>();
            l.put("title", t.title());
            l.put("qty", t.quantity());
            l.put("variations", variations(t.variations().stream().map(v -> v.propertyName() + "=" + v.value()).toList()));
            l.put("personalised", t.personalization().isPresent());
            l.put("listing", t.listingId());
            l.put("mapped", etsyMappings.find(t.listingId(), t.variations()).map(e -> parts(e.parts())).orElse(List.of()));
            lines.add(l);
        }
        m.put("lines", lines);
        return m;
    }

    private Map<String, Object> ebayOrder(final EbayApiClient.Order o, final List<NotificationService.LogEntry> skips) {
        final Map<String, Object> m = header("ebay", o.orderId(), o.creationDate(), o.buyerUsername(), o.buyerNote(),
                board.entry("ebay", o.orderId(), OrderBoardService.label(o)), skips);
        final List<Map<String, Object>> lines = new ArrayList<>();
        for (final EbayApiClient.LineItem li : o.lineItems()) {
            final Map<String, Object> l = new LinkedHashMap<>();
            l.put("title", li.title());
            l.put("qty", li.quantity());
            l.put("variations", variations(li.variationAspects().stream().map(v -> v.propertyName() + "=" + v.value()).toList()));
            l.put("personalised", li.personalization().isPresent());
            l.put("listing", li.listingKey());
            l.put("mapped", ebayMappings.find(li.listingKey(), li.variationAspects()).map(e -> parts(e.parts())).orElse(List.of()));
            lines.add(l);
        }
        m.put("lines", lines);
        return m;
    }

    private static Map<String, Object> header(final String market, final String id, final java.time.Instant created,
            final String buyer, final String note, final OrderBoardService.Entry e,
            final List<NotificationService.LogEntry> skips) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("market", market);
        m.put("id", id);
        m.put("created", created == null ? "" : FarmTools.ago(created));
        m.put("buyer", buyer);
        if (note != null && !note.isBlank()) {
            m.put("note", note);
        }
        m.put("stage", e.stage().name());
        m.put("printed", e.printed() + "/" + e.expected());
        if (e.fromStock() > 0) {
            m.put("fromStock", e.fromStock());
        }
        if (e.printing() > 0) {
            m.put("printing", e.printing());
        }
        if (e.waiting() > 0) {
            m.put("waiting", e.waiting());
        }
        if (e.abandoned() > 0) {
            m.put("abandoned", e.abandoned());
        }
        if (e.stage() == OrderBoardService.Stage.NOT_QUEUED) {
            skips.stream().filter(s -> s.message() != null && s.message().contains(id)).findFirst()
                    .ifPresent(s -> m.put("lastSkip", s.timestamp() + ": " + FarmTools.truncate(s.message(), 400)));
        }
        return m;
    }

    private static Object variations(final List<String> v) {
        return v.isEmpty() ? "" : String.join("; ", v);
    }

    /** A mapped part in one line: file, plate, copies, tray and filament, extra trays, H2D variant. */
    static String part(final MappingPart p) {
        final StringBuilder b = new StringBuilder(String.valueOf(p.path()));
        if (p.plateId() > 0) {
            b.append(" plate ").append(p.plateId());
        }
        if (p.copiesPerUnit() > 1) {
            b.append(" x").append(p.copiesPerUnit());
        }
        if (p.amsSlot() != null) {
            b.append(" tray ").append(FarmTools.trayLabel(p.amsSlot()));
        }
        if (p.filamentType() != null && !p.filamentType().isBlank()) {
            b.append(' ').append(p.filamentType());
            if (p.filamentColor() != null && !p.filamentColor().isBlank()) {
                b.append(" #").append(p.filamentColor());
            }
        }
        if (p.extraSlots() != null && !p.extraSlots().isEmpty()) {
            b.append(" + trays ").append(String.join(",", p.extraSlots().stream().map(FarmTools::trayLabel).toList()));
        }
        if (p.variants() != null && !p.variants().isEmpty()) {
            b.append(" variants ").append(p.variants());
        }
        if (p.source() != null) {
            b.append(" [").append(p.source()).append(']');
        }
        return b.toString();
    }

    static List<String> parts(final List<MappingPart> parts) {
        return parts == null ? List.of() : parts.stream().map(OrderTools::part).toList();
    }

    private static boolean wants(final String market, final String which) {
        return market == null || market.isBlank() || market.equalsIgnoreCase(which);
    }

    // ---------------------------------------------------------------------------------------------------------
    // dispatch_pool
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "dispatch_pool", description = """
            The auto-queue dispatcher: jobs waiting in the shared pool (part, order, parked reason), why the \
            dispatcher is holding (blocked status), next pass, next free printer, job-retry back-off, and per \
            printer its own queue size, the next queued file and any start-verify or dirty-bed back-off.""",
            annotations = @Tool.Annotations(title = "Dispatch pool", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse dispatchPool() {
        return audit.run("dispatch_pool", Map.of(), () -> audit.json(buildPool()));
    }

    Map<String, Object> buildPool() {
        final Map<String, Object> out = new LinkedHashMap<>();
        dispatch.getBlockedStatus().ifPresent(s -> out.put("blocked", s));
        out.put("blockKind", String.valueOf(dispatch.getBlockedKind()));
        dispatch.getNextTickIn().ifPresent(d -> out.put("nextPassIn", FarmTools.human(d)));
        dispatch.getNextFree().ifPresent(s -> out.put("nextFree", s));
        dispatch.getJobRetryRemaining().ifPresent(d -> out.put("jobRetryIn", FarmTools.human(d)));
        out.put("parked", dispatch.parkedCount());
        final List<Map<String, Object>> pool = new ArrayList<>();
        for (final DispatchQueueService.PendingJob j : dispatch.getPool()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id());
            m.put("part", j.part() == null ? "" : part(j.part()));
            if (j.orderRef() != null) {
                m.put("order", j.orderRef().market() + " " + j.orderRef().orderId());
            }
            dispatch.getParkedReason(j.id()).ifPresent(r -> m.put("parked", r));
            pool.add(m);
        }
        out.put("pool", pool);
        final List<Map<String, Object>> perPrinter = new ArrayList<>();
        for (final BambuPrinters.PrinterDetail d : printers.getPrintersDetail()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("printer", d.name());
            m.put("queued", queue.size(d.name()));
            queue.peek(d.name()).ifPresent(e -> m.put("next", e.part() != null ? part(e.part())
                    : e.command() == null ? "?" : e.command().filename() + " plate " + e.command().plateId()));
            dispatch.getStartVerifyRemaining(d.name()).ifPresent(t -> m.put("startVerifyIn", FarmTools.human(t)));
            dispatch.getBedBackoffRemaining(d.name()).ifPresent(t -> m.put("bedBackoffIn", FarmTools.human(t)));
            perPrinter.add(m);
        }
        perPrinter.sort(Comparator.comparing(m -> String.valueOf(m.get("printer")), String.CASE_INSENSITIVE_ORDER));
        out.put("printers", perPrinter);
        return out;
    }
}
