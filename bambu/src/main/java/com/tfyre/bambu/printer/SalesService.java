package com.tfyre.bambu.printer;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sales history for the Profit page: one record per order line, pulled from Etsy and eBay a few times a day and
 * kept on disk, because both marketplaces stop listing an order as "open" the moment it ships - which is exactly
 * when it becomes a sale worth counting.
 * <p>
 * Only what the marketplace reports is stored (what the buyer paid, eBay's fee). Everything that is an
 * assumption - Etsy's fee formula, what a label costs - is applied when the page is drawn, so changing an
 * assumption re-prices the whole history.
 */
@ApplicationScoped
public class SalesService {

    private static final String STORE_FILENAME = "bambu-sales.json";
    /** How far back each sync reaches. eBay's order search goes back 90 days without extra parameters. */
    private static final Duration ETSY_WINDOW = Duration.ofDays(120);
    private static final Duration EBAY_WINDOW = Duration.ofDays(90);

    /**
     * One order line.
     *
     * @param mappingKey    the mapping the line resolved to when it was synced (blank = unmapped); looked up
     *                      again when the page is drawn, so a later change to the mapping's parts applies
     * @param revenue       what the buyer paid for this line's items, after seller discounts, before shipping and tax
     * @param shippingShare this line's share of the delivery charge the buyer paid
     * @param orderShare    this line's share of its order (lines of one order add up to 1) - spreads per-order
     *                      costs such as a label
     * @param marketFee     this line's share of the fee the marketplace reported for the order; 0 for Etsy,
     *                      whose API does not report one
     */
    public record Sale(String market, String orderId, String lineId, String date, String listingKey, String title,
            String mappingKey, int quantity, double revenue, double shippingShare, double orderShare, double marketFee,
            boolean cancelled) {
    }

    /**
     * @param etsyFeePercent  Etsy's cut of items + shipping: 6.5% transaction + 3% payment processing by default
     * @param etsyFeePerOrder Etsy's fixed charges per order: $0.25 processing + $0.20 listing renewal by default
     * @param labelCost       what a shipping label costs you per order, on average (labels are bought elsewhere,
     *                        so the app cannot see the real figure)
     */
    public record Settings(double etsyFeePercent, double etsyFeePerOrder, double labelCost) {

        public static Settings defaults() {
            return new Settings(9.5, 0.45, 0);
        }
    }

    public record Store(Settings settings, Map<String, Sale> sales) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    EtsyApiClient etsy;
    @Inject
    EbayApiClient ebay;
    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;

    private final Map<String, Sale> sales = new LinkedHashMap<>();
    private Settings settings = Settings.defaults();
    private Instant lastSync;
    private String lastError;

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Store store = mapper.readValue(path.toFile(), Store.class);
            if (store.sales() != null) {
                sales.putAll(store.sales());
            }
            if (store.settings() != null) {
                settings = store.settings();
            }
            Log.infof("SalesService: loaded %d sale line(s) from %s", sales.size(), path);
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "SalesService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), new Store(settings, sales));
        } catch (IOException ex) {
            Log.errorf(ex, "SalesService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    public synchronized Settings getSettings() {
        return settings;
    }

    public synchronized void setSettings(final Settings s) {
        settings = s;
        save();
    }

    public synchronized List<Sale> getSales() {
        return new ArrayList<>(sales.values());
    }

    public synchronized Optional<Instant> getLastSync() {
        return Optional.ofNullable(lastSync);
    }

    public synchronized Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    @Scheduled(every = "6h", delayed = "2m", identity = "sales-sync")
    void scheduled() {
        sync();
    }

    /**
     * Pulls recent orders from both marketplaces and updates the records. A line already on record is replaced,
     * so a later cancellation or an eBay fee that was not assessed yet is picked up on the next pass.
     */
    public void sync() {
        final List<String> problems = new ArrayList<>();
        final List<Sale> fresh = new ArrayList<>();
        if (etsy.isConnected()) {
            try {
                for (final EtsyApiClient.Receipt r : etsy.getReceiptsSince(Instant.now().minus(ETSY_WINDOW))) {
                    fresh.addAll(etsyLines(r));
                }
            } catch (Exception ex) {
                problems.add("Etsy: " + ex.getMessage());
            }
        }
        if (ebay.isConnected()) {
            try {
                for (final EbayApiClient.Order o : ebay.getOrdersSince(Instant.now().minus(EBAY_WINDOW))) {
                    fresh.addAll(ebayLines(o));
                }
            } catch (Exception ex) {
                problems.add("eBay: " + ex.getMessage());
            }
        }
        synchronized (this) {
            fresh.forEach(s -> sales.put(s.market() + "|" + s.orderId() + "|" + s.lineId(), s));
            lastSync = Instant.now();
            lastError = problems.isEmpty() ? null : String.join("; ", problems);
            if (!fresh.isEmpty()) {
                save();
            }
        }
        if (problems.isEmpty()) {
            Log.infof("SalesService: synced %d sale line(s); %d on record", fresh.size(), sales.size());
        } else {
            Log.warnf("SalesService: sync incomplete: %s", String.join("; ", problems));
        }
    }

    /** Each line's share of its order, by what was paid for it; equal shares when nothing has a price. */
    private static double[] weights(final double[] amounts) {
        double sum = 0;
        for (final double a : amounts) {
            sum += Math.max(0, a);
        }
        final double[] w = new double[amounts.length];
        for (int i = 0; i < amounts.length; i++) {
            w[i] = sum > 0 ? Math.max(0, amounts[i]) / sum : 1.0 / amounts.length;
        }
        return w;
    }

    private List<Sale> etsyLines(final EtsyApiClient.Receipt r) {
        final List<EtsyApiClient.Transaction> lines = r.transactions();
        final double[] listed = new double[lines.size()];
        double listedTotal = 0;
        for (int i = 0; i < lines.size(); i++) {
            listed[i] = lines.get(i).unitPrice() * lines.get(i).quantity();
            listedTotal += listed[i];
        }
        final double[] w = weights(listed);
        // Line prices are before shop discounts; the receipt's item total is after them. Spread the difference.
        final double paid = r.itemTotal() > 0 ? r.itemTotal() : listedTotal;
        final List<Sale> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final EtsyApiClient.Transaction t = lines.get(i);
            out.add(new Sale("etsy", String.valueOf(r.receiptId()), String.valueOf(t.transactionId()),
                    r.createTimestamp().toString(), String.valueOf(t.listingId()), t.title(),
                    etsyMappings.findKey(t.listingId(), t.variations()).orElse(""),
                    t.quantity(), paid * w[i], r.shippingCharged() * w[i], w[i], 0, r.isCancelled()));
        }
        return out;
    }

    private List<Sale> ebayLines(final EbayApiClient.Order o) {
        final List<EbayApiClient.LineItem> lines = o.lineItems();
        final double[] paid = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            paid[i] = lines.get(i).lineTotal();
        }
        final double[] w = weights(paid);
        final List<Sale> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final EbayApiClient.LineItem li = lines.get(i);
            out.add(new Sale("ebay", o.orderId(), li.lineItemId(), o.creationDate().toString(), li.listingKey(), li.title(),
                    ebayMappings.findKey(li.listingKey(), li.variationAspects()).orElse(""),
                    li.quantity(), paid[i], o.shippingCharged() * w[i], w[i], o.marketplaceFee() * w[i], o.cancelled()));
        }
        return out;
    }
}
