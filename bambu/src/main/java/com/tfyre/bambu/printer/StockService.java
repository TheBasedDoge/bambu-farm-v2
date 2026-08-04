package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Shutdown;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-hand stock per mapped listing/variation, so items you already have (printed extra, a return) are fulfilled
 * from stock instead of being reprinted. Keyed by {@code market|storageKey}, where {@code storageKey} is the same
 * mapping key the listing/variation is stored under (so listing-wide and per-variation stock both work). Every
 * count defaults to 0. Persisted to {@code bambu-stock.json}, same pattern as the mapping services.
 */
@ApplicationScoped
public class StockService {

    private static final String STORE_FILENAME = "bambu-stock.json";

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;
    @Inject
    SimulationService simulation;
    // Needed to resolve a listing's product code. Neither mapping service injects this one back, so no cycle.
    @Inject
    EtsyMappingService etsyMapping;
    @Inject
    EbayMappingService ebayMapping;

    /** "market|storageKey" → on-hand count. Absent = 0. */
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    private boolean dirty;

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
            stock.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Integer>>() {
            }));
            Log.infof("StockService: loaded %d stock entr(y/ies) from %s", stock.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "StockService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), stock);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "StockService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    /** Prefix marking a shared pool, so it can never collide with a "etsy|…"/"ebay|…" per-listing key. */
    private static final String PRODUCT_PREFIX = "product|";

    /**
     * The pool a listing's stock lives in.
     * <p>
     * Normally per marketplace listing (`etsy|1389360052|Size=…`), which means the same physical product sold on
     * both Etsy and eBay keeps two unrelated counts - stock set against the Etsy listing did nothing when the eBay
     * order arrived, and it printed. When the mapping carries a <b>product code</b>, both listings resolve to the
     * same `product|<code>` pool instead.
     * <p>
     * Resolving here rather than at each call site is deliberate: the stock editor in the UI and the order poller
     * both go through this, so they cannot disagree about which pool they are touching. That disagreement is the
     * whole bug class.
     */
    private String composite(final String market, final String storageKey) {
        return productCodeFor(market, storageKey)
                .map(code -> PRODUCT_PREFIX + code)
                .orElseGet(() -> market + "|" + storageKey);
    }

    private Optional<String> productCodeFor(final String market, final String storageKey) {
        if (storageKey == null) {
            return Optional.empty();
        }
        final String code = switch (market == null ? "" : market) {
            case "etsy" ->
                Optional.ofNullable(etsyMapping.entries().get(storageKey))
                        .map(EtsyMappingService.MappingEntry::productCode).orElse(null);
            case "ebay" ->
                Optional.ofNullable(ebayMapping.entries().get(storageKey))
                        .map(EbayMappingService.MappingEntry::productCode).orElse(null);
            default ->
                null;
        };
        return Optional.ofNullable(code).filter(c -> !c.isBlank());
    }

    /**
     * Moves any per-listing stock into the shared pool after a product code is assigned, so the count already on
     * hand isn't orphaned under a key nothing reads any more.
     * <p>
     * Additive into the pool and zeroing the source, so running it twice is harmless - the second call finds
     * nothing to move. Call it after saving a mapping whose product code was set.
     */
    public synchronized void mergeIntoProduct(final String market, final String storageKey, final String productCode) {
        if (productCode == null || productCode.isBlank() || storageKey == null) {
            return;
        }
        final String listingKey = market + "|" + storageKey;
        final int orphaned = stock.getOrDefault(listingKey, 0);
        if (orphaned <= 0) {
            return;
        }
        final String pool = PRODUCT_PREFIX + productCode;
        stock.put(pool, stock.getOrDefault(pool, 0) + orphaned);
        stock.remove(listingKey);
        dirty = true;
        save();
        Log.infof("StockService: moved %d unit(s) from %s into shared pool %s", orphaned, listingKey, pool);
    }

    /** On-hand count for a mapping storage key (0 when never set). */
    public int get(final String market, final String storageKey) {
        return stock.getOrDefault(composite(market, storageKey), 0);
    }

    /** Sets the on-hand count (values below 0 are clamped to 0). */
    public synchronized void set(final String market, final String storageKey, final int quantity) {
        final int q = Math.max(0, quantity);
        if (q == 0) {
            stock.remove(composite(market, storageKey));
        } else {
            stock.put(composite(market, storageKey), q);
        }
        dirty = true;
        save();
    }

    /** Every stored (non-zero) stock entry, keyed by the composite "market|storageKey" - for the Mappings tab. */
    public Map<String, Integer> entries() {
        return Map.copyOf(stock);
    }

    /**
     * Consumes up to {@code units} from stock for a storage key, returning how many were actually taken (0 when
     * empty). Decrements and persists.
     */
    public synchronized int consume(final String market, final String storageKey, final int units) {
        if (units <= 0) {
            return 0;
        }
        final int available = get(market, storageKey);
        final int take = Math.min(available, units);
        if (take > 0) {
            set(market, storageKey, available - take);
        }
        return take;
    }

    /**
     * One order line's planned stock coverage: decided up front, committed later.
     *
     * @param storageKey the mapping key the units come off
     * @param units      how many of the ordered quantity stock would cover
     * @param itemLabel  what it is, for the notification
     */
    public record PlannedCoverage(String storageKey, int units, String itemLabel) {
    }

    /**
     * How many of {@code quantity} on-hand stock could cover, <b>without consuming anything</b>. Zero when the line
     * isn't mapped, since there's no key to draw against.
     * <p>
     * This is deliberately split from {@link #commitCoverage}. Consuming at the point the order is read means the
     * units are gone even when the order is subsequently skipped - and it is skipped for any of five reasons,
     * including buyer personalization, an unmapped sibling line, and the global auto-queue switch simply being off.
     * The stock was then deducted for something that never printed, and printing it by hand later spent it twice.
     * Decide here; commit only once the order is accepted.
     */
    public synchronized int coverageFor(final String market, final Optional<String> storageKey, final int quantity) {
        if (storageKey.isEmpty() || quantity <= 0) {
            return 0;
        }
        return Math.min(get(market, storageKey.get()), quantity);
    }

    /**
     * Commits a coverage decided earlier by {@link #coverageFor}: decrements, persists, and fires
     * {@code order_from_stock}. Call this only once the order is definitely being fulfilled.
     * <p>
     * Re-checks availability rather than trusting the planned figure, so a concurrent consumer can only make this
     * take fewer units, never drive stock negative. A shortfall is logged because it means the order needed more
     * printing than was queued for it.
     */
    public synchronized void commitCoverage(final String market, final PlannedCoverage planned, final String orderLabel) {
        if (simulation.isEnabled()) {
            Log.infof("StockService: [SIMULATED] %s: would have taken %d× %s from stock", orderLabel,
                    planned.units(), planned.itemLabel());
            return;
        }
        final int consumed = consume(market, planned.storageKey(), planned.units());
        if (consumed <= 0) {
            return;
        }
        if (consumed < planned.units()) {
            Log.warnf("StockService: %s: only %d of the %d planned %s were still in stock - the shortfall was NOT queued",
                    orderLabel, consumed, planned.units(), planned.itemLabel());
        }
        final int left = get(market, planned.storageKey());
        notificationService.notifyEvent("order_from_stock", marketLabel(market),
                "%s: %d× %s fulfilled from on-hand stock, not printed (%d left)".formatted(
                        orderLabel, consumed, planned.itemLabel(), left));
        Log.infof("StockService: %s: %d× %s from stock (%d left)", orderLabel, consumed, planned.itemLabel(), left);
    }

    private static String marketLabel(final String market) {
        return "etsy".equals(market) ? "Etsy" : "ebay".equals(market) ? "eBay" : market;
    }
}
