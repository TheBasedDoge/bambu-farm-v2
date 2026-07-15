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

    private static String composite(final String market, final String storageKey) {
        return market + "|" + storageKey;
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
     * Applies stock to one order line: consumes what it can, fires an {@code order_from_stock} notification if any
     * units were covered, and returns how many units still need to be printed. When no mapping key is known (the
     * line isn't mapped) nothing is consumed and the full quantity is returned.
     */
    public int applyToOrderLine(final String market, final Optional<String> storageKey, final int quantity,
            final String itemLabel, final String orderLabel) {
        if (storageKey.isEmpty()) {
            return quantity;
        }
        final int consumed = consume(market, storageKey.get(), quantity);
        if (consumed > 0) {
            final int left = get(market, storageKey.get());
            notificationService.notifyEvent("order_from_stock", marketLabel(market),
                    "%s: %d× %s fulfilled from on-hand stock, not printed (%d left)".formatted(
                            orderLabel, consumed, itemLabel, left));
            Log.infof("StockService: %s: %d× %s from stock (%d left)", orderLabel, consumed, itemLabel, left);
        }
        return quantity - consumed;
    }

    private static String marketLabel(final String market) {
        return "etsy".equals(market) ? "Etsy" : "ebay".equals(market) ? "eBay" : market;
    }
}
