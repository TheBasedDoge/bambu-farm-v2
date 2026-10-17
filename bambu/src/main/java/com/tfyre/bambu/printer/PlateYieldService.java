package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many pieces the print now on each printer makes, when that is more than one.
 * <p>
 * A P1 print of an order part makes one piece. The H2D's bed is bigger, so its slice of the same part can hold
 * two (a pair of door speaker adapters) - one H2D print then stands in for two P1 prints, and when it finishes
 * the order has to be credited with two. Print history only knows "a print for order X ended", so the count is
 * kept here, per printer, from the moment the print is started until it ends.
 * <p>
 * On disk, because a restart in the middle of a four-hour plate must not turn two pieces into one and leave the
 * order waiting forever for a part that is already on the bed.
 */
@ApplicationScoped
public class PlateYieldService {

    private static final String STORE_FILENAME = "bambu-plate-yield.json";

    public record Entry(String market, String orderId, String partKey, int pieces) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private final Map<String, Entry> byPrinter = new ConcurrentHashMap<>();

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
            byPrinter.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Entry>>() {
            }));
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "PlateYieldService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), new LinkedHashMap<>(byPrinter));
        } catch (IOException ex) {
            Log.errorf(ex, "PlateYieldService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    /**
     * A queue-started print is going out to {@code printer}. Called for every such start, so that a plain
     * one-piece print also clears whatever the previous print left here.
     */
    public void started(final String printer, final OrderRef order, final MappingPart part, final int pieces) {
        final Entry before;
        if (order == null || part == null || pieces <= 1) {
            before = byPrinter.remove(printer);
            if (before != null) {
                save();
            }
            return;
        }
        byPrinter.put(printer, new Entry(order.market(), order.orderId(), StockService.partKey(part), pieces));
        save();
        Log.infof("PlateYieldService: %s: this print makes %d pieces for %s", printer, pieces, order.label());
    }

    /** Pieces the print that just ended on {@code printer} made for {@code order}: 1 unless recorded otherwise. */
    public int pieces(final String printer, final OrderRef order) {
        final Entry e = byPrinter.get(printer);
        return e != null && order != null && e.orderId().equals(order.orderId()) && e.market().equals(order.market())
                ? Math.max(1, e.pieces()) : 1;
    }

    public void clear(final String printer) {
        if (byPrinter.remove(printer) != null) {
            save();
        }
    }

    /** Multi-piece prints under way for one order. */
    public List<Entry> forOrder(final String market, final String orderId) {
        return byPrinter.values().stream()
                .filter(e -> e.market().equals(market) && e.orderId().equals(orderId)).toList();
    }
}
