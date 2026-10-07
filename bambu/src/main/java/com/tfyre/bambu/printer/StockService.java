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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-hand stock, counted in <b>printed pieces per part</b>.
 * <p>
 * A part is a print file + plate + material + colour - the thing that is physically on the shelf. Until
 * 2026-10-05 stock was counted per marketplace listing (and optionally pooled by a hand-typed "product code"),
 * which is the wrong unit three ways: the same adapter sold on Etsy and eBay kept two counts unless someone
 * typed matching codes on both mappings; a "set of 2" listing and a single-piece listing drew "1" from a
 * shared pool for different amounts of plastic; and a kit could only be in stock as a whole kit. Every mapping
 * already says which files a listing needs and how many copies per sale, so keying stock by the file makes the
 * grouping across marketplaces and variations automatic - nothing to type, nothing to mistype.
 * <p>
 * Stock only ever goes <b>up by hand</b> (the Inventory page). Adding a finished print automatically was
 * considered and rejected: a print that loses its order link in a restart would be counted as a spare while
 * the order it belonged to still shipped it. It goes down by hand or when an accepted order is covered.
 * <p>
 * Every change is written to a ledger ({@code bambu-stock-log.json}) with its reason and the balance after it,
 * so "why does it say 3" has an answer.
 */
@ApplicationScoped
public class StockService {

    private static final String STORE_FILENAME = "bambu-stock.json";
    private static final String LEDGER_FILENAME = "bambu-stock-log.json";
    private static final String PART_PREFIX = "part|";
    private static final int LEDGER_MAX = 2000;

    /** One change to one part's count. {@code delta} is signed; {@code balance} is the count afterwards. */
    public record LedgerEntry(String timestamp, String partKey, int delta, String reason, int balance) {
    }

    /** What a part key is made of, for display. {@code material}/{@code color} are "" when the mapping has none. */
    public record PartId(String file, int plateId, String material, String color) {

        /** The file name without its slicer extensions - what you would call the part out loud. */
        public String name() {
            String n = file;
            for (final String ext : List.of(".gcode.3mf", ".3mf", ".gcode")) {
                if (n.toLowerCase(Locale.ROOT).endsWith(ext)) {
                    n = n.substring(0, n.length() - ext.length());
                    break;
                }
            }
            return plateId > 1 ? "%s (plate %d)".formatted(n, plateId) : n;
        }
    }

    /**
     * Pieces of one part that stock will supply to one order line: decided up front, committed later.
     *
     * @param partKey   the pool the pieces come off
     * @param pieces    how many
     * @param itemLabel the order line it is for, for the notification
     */
    public record PlannedCoverage(String partKey, int pieces, String itemLabel) {
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;
    @Inject
    NotificationService notificationService;
    @Inject
    SimulationService simulation;
    // Only for the one-off migration of per-listing counts. Neither mapping service injects this one back.
    @Inject
    EtsyMappingService etsyMapping;
    @Inject
    EbayMappingService ebayMapping;

    /** part key → pieces on hand. Absent = 0. May also hold legacy per-listing keys that could not be migrated. */
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    /** Oldest first. Guarded by {@code this}. */
    private final List<LedgerEntry> ledger = new ArrayList<>();

    // -------------------------------------------------------------------------
    // identity
    // -------------------------------------------------------------------------
    /** The pool a mapped part draws on. Material and colour are part of it: black PETG is not black ASA. */
    public static String partKey(final MappingPart part) {
        return PART_PREFIX + part.path() + "|" + part.plateId() + "|"
                + (part.filamentType() == null ? "" : part.filamentType().strip().toUpperCase(Locale.ROOT)) + "|"
                + (part.filamentColor() == null ? "" : part.filamentColor().strip());
    }

    /** Takes a part key apart again. Tolerant: a key that is not one comes back as itself with nothing else. */
    public static PartId describe(final String partKey) {
        if (partKey == null || !partKey.startsWith(PART_PREFIX)) {
            return new PartId(String.valueOf(partKey), 1, "", "");
        }
        final String[] f = partKey.substring(PART_PREFIX.length()).split("\\|", -1);
        // The file path is everything before the last three fields, in case a path ever contains a bar.
        if (f.length < 4) {
            return new PartId(partKey.substring(PART_PREFIX.length()), 1, "", "");
        }
        final String file = String.join("|", java.util.Arrays.copyOfRange(f, 0, f.length - 3));
        int plate = 1;
        try {
            plate = Integer.parseInt(f[f.length - 3]);
        } catch (NumberFormatException ex) {
            plate = 1;
        }
        return new PartId(file, plate, f[f.length - 2], f[f.length - 1]);
    }

    // -------------------------------------------------------------------------
    // persistence
    // -------------------------------------------------------------------------
    private Path resolve(final String filename) {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(filename) : Path.of(filename);
    }

    @PostConstruct
    synchronized void load() {
        final Path path = resolve(STORE_FILENAME);
        if (Files.exists(path)) {
            try {
                stock.putAll(mapper.readValue(path.toFile(), new TypeReference<Map<String, Integer>>() {
                }));
                Log.infof("StockService: loaded %d stock entr(y/ies) from %s", stock.size(), path);
            } catch (IOException ex) {
                Log.errorf(ex, "StockService: cannot load %s: %s", path, ex.getMessage());
            }
        }
        final Path log = resolve(LEDGER_FILENAME);
        if (Files.exists(log)) {
            try {
                ledger.addAll(mapper.readValue(log.toFile(), new TypeReference<List<LedgerEntry>>() {
                }));
            } catch (IOException | RuntimeException ex) {
                Log.errorf(ex, "StockService: cannot load %s: %s", log, ex.getMessage());
            }
        }
        try {
            migrateLegacy(path);
        } catch (RuntimeException ex) {
            // Never let a migration problem stop the app from starting; the legacy keys are simply left alone.
            Log.errorf(ex, "StockService: could not migrate per-listing stock: %s", ex.getMessage());
        }
    }

    /**
     * One-off: turns per-listing counts ({@code etsy|…}, {@code ebay|…}) and shared pools ({@code product|…})
     * into per-part pieces. A listing count of 1 whose mapping prints two copies per sale becomes 2 pieces of
     * that file; a kit becomes that many of each of its parts.
     * <p>
     * A key whose mapping no longer exists cannot be converted - there is no way to know which file it meant -
     * so it is left in the file untouched and reported, rather than dropped. The original file is copied to
     * {@code bambu-stock.json.pre-parts} first.
     */
    private void migrateLegacy(final Path path) {
        final List<String> legacy = stock.keySet().stream().filter(k -> !k.startsWith(PART_PREFIX)).sorted().toList();
        if (legacy.isEmpty()) {
            return;
        }
        final Path backup = path.resolveSibling(STORE_FILENAME + ".pre-parts");
        try {
            if (Files.exists(path) && !Files.exists(backup)) {
                Files.copy(path, backup);
            }
        } catch (IOException ex) {
            Log.warnf("StockService: could not back up %s before migrating: %s", path, ex.getMessage());
        }
        boolean changed = false;
        for (final String key : legacy) {
            final int units = stock.getOrDefault(key, 0);
            final List<MappingPart> parts = legacyParts(key);
            if (units <= 0) {
                stock.remove(key);
                changed = true;
                continue;
            }
            if (parts.isEmpty()) {
                Log.warnf("StockService: %d unit(s) under '%s' could not be moved to per-part stock - no mapping "
                        + "uses that key any more. Left as it is; add the pieces on the Inventory page.", units, key);
                continue;
            }
            for (final MappingPart part : parts) {
                final String pk = partKey(part);
                final int pieces = units * part.copiesPerUnit();
                final int balance = stock.getOrDefault(pk, 0) + pieces;
                stock.put(pk, balance);
                ledger.add(new LedgerEntry(OffsetDateTime.now().toString(), pk, pieces, "Moved from listing stock (" + key + ")", balance));
                Log.infof("StockService: migrated %d unit(s) of '%s' -> %d piece(s) of %s", units, key, pieces, describe(pk).name());
            }
            stock.remove(key);
            changed = true;
        }
        if (changed) {
            save();
            saveLedger();
        }
    }

    private List<MappingPart> legacyParts(final String key) {
        if (key.startsWith("product|")) {
            final String code = key.substring("product|".length());
            return etsyMapping.entries().values().stream()
                    .filter(e -> code.equals(e.productCode())).map(EtsyMappingService.MappingEntry::parts).findFirst()
                    .or(() -> ebayMapping.entries().values().stream()
                            .filter(e -> code.equals(e.productCode())).map(EbayMappingService.MappingEntry::parts).findFirst())
                    .orElse(List.of());
        }
        if (key.startsWith("etsy|")) {
            return Optional.ofNullable(etsyMapping.entries().get(key.substring("etsy|".length())))
                    .map(EtsyMappingService.MappingEntry::parts).orElse(List.of());
        }
        if (key.startsWith("ebay|")) {
            return Optional.ofNullable(ebayMapping.entries().get(key.substring("ebay|".length())))
                    .map(EbayMappingService.MappingEntry::parts).orElse(List.of());
        }
        return List.of();
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(resolve(STORE_FILENAME).toFile(), new java.util.TreeMap<>(stock));
        } catch (IOException ex) {
            Log.errorf(ex, "StockService: cannot save %s: %s", resolve(STORE_FILENAME), ex.getMessage());
        }
    }

    private synchronized void saveLedger() {
        while (ledger.size() > LEDGER_MAX) {
            ledger.remove(0);
        }
        try {
            mapper.writeValue(resolve(LEDGER_FILENAME).toFile(), ledger);
        } catch (IOException ex) {
            Log.errorf(ex, "StockService: cannot save %s: %s", resolve(LEDGER_FILENAME), ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // reading and changing
    // -------------------------------------------------------------------------
    /** Pieces on hand for a part key (0 when never set). */
    public int get(final String partKey) {
        return partKey == null ? 0 : stock.getOrDefault(partKey, 0);
    }

    public int get(final MappingPart part) {
        return get(partKey(part));
    }

    /** Every part with pieces on hand. Legacy keys that could not be migrated are not included. */
    public Map<String, Integer> entries() {
        final Map<String, Integer> out = new java.util.TreeMap<>();
        stock.forEach((k, v) -> {
            if (k.startsWith(PART_PREFIX) && v > 0) {
                out.put(k, v);
            }
        });
        return out;
    }

    /**
     * Adds ({@code delta > 0}) or removes pieces, never going below zero, and records why.
     *
     * @return the count afterwards
     */
    public synchronized int adjust(final String partKey, final int delta, final String reason) {
        final int before = get(partKey);
        final int after = Math.max(0, before + delta);
        if (after == before) {
            return before;
        }
        if (after == 0) {
            stock.remove(partKey);
        } else {
            stock.put(partKey, after);
        }
        ledger.add(new LedgerEntry(OffsetDateTime.now().toString(), partKey, after - before, reason, after));
        save();
        saveLedger();
        return after;
    }

    /** The newest {@code limit} changes to one part, newest first. */
    public synchronized List<LedgerEntry> history(final String partKey, final int limit) {
        final List<LedgerEntry> out = new ArrayList<>();
        for (int i = ledger.size() - 1; i >= 0 && out.size() < limit; i--) {
            if (ledger.get(i).partKey().equals(partKey)) {
                out.add(ledger.get(i));
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // orders
    // -------------------------------------------------------------------------
    /**
     * Pieces an order took off the shelf and has not given back, per part - read from the ledger, so it is what
     * actually happened rather than what was planned. Used when an order is cancelled.
     */
    public synchronized Map<String, Integer> takenFor(final String orderLabel) {
        final Map<String, Integer> out = new java.util.LinkedHashMap<>();
        final String took = orderLabel + " took ";
        final String returned = orderLabel + " cancelled - returned ";
        for (final LedgerEntry e : ledger) {
            if (e.reason() == null) {
                continue;
            }
            if (e.reason().startsWith(took) || e.reason().startsWith(returned)) {
                // delta is negative for a take and positive for a return, so the sum is what is still out.
                out.merge(e.partKey(), -e.delta(), Integer::sum);
            }
        }
        out.values().removeIf(n -> n <= 0);
        return out;
    }

    /**
     * Works out, part by part, how many pieces of an order line stock could supply - <b>without consuming
     * anything</b>.
     * <p>
     * Split from {@link #commitCoverage} on purpose. Consuming at the point the order is read means the pieces
     * are gone even when the order is then skipped - and it is skipped for any of five reasons, including an
     * unmapped sibling line and the auto-queue switch simply being off. Decide here; commit only once the order
     * is accepted.
     *
     * @param reserved pieces already promised to earlier lines of the SAME order, so two lines wanting the last
     *                 piece don't both get it; updated in place
     * @param planned  receives one entry per part that stock will supply; updated in place
     * @return for each of {@code parts}, in order, the pieces to take from stock rather than print
     */
    public synchronized List<Integer> planCoverage(final List<MappingPart> parts, final int quantity,
            final String itemLabel, final Map<String, Integer> reserved, final List<PlannedCoverage> planned) {
        final List<Integer> out = new ArrayList<>();
        for (final MappingPart part : parts) {
            final String key = partKey(part);
            final int need = Math.max(1, quantity) * part.copiesPerUnit();
            final int free = Math.max(0, get(key) - reserved.getOrDefault(key, 0));
            final int take = Math.min(free, need);
            if (take > 0) {
                reserved.merge(key, take, Integer::sum);
                planned.add(new PlannedCoverage(key, take, itemLabel));
            }
            out.add(take);
        }
        return out;
    }

    /** True when stock supplies every piece of every part of the line, so nothing is left to print. */
    public static boolean fullyCovered(final List<MappingPart> parts, final int quantity, final List<Integer> fromStock) {
        if (parts.isEmpty() || fromStock == null || fromStock.size() != parts.size()) {
            return false;
        }
        for (int i = 0; i < parts.size(); i++) {
            if (fromStock.get(i) < Math.max(1, quantity) * parts.get(i).copiesPerUnit()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Commits a coverage decided earlier by {@link #planCoverage}: decrements, records it, and fires
     * {@code order_from_stock}. Call this only once the order is definitely being fulfilled.
     * <p>
     * Takes what is actually there rather than trusting the planned figure, so a concurrent change can only make
     * this take fewer pieces, never drive the count negative. A shortfall is logged because it means the order
     * needed more printing than was queued for it.
     */
    public synchronized void commitCoverage(final String market, final PlannedCoverage planned, final String orderLabel) {
        final String part = describe(planned.partKey()).name();
        if (simulation.isEnabled()) {
            Log.infof("StockService: [SIMULATED] %s: would have taken %d× %s from stock", orderLabel, planned.pieces(), part);
            return;
        }
        final int take = Math.min(get(planned.partKey()), planned.pieces());
        if (take <= 0) {
            return;
        }
        final int left = adjust(planned.partKey(), -take, "%s took %d".formatted(orderLabel, take));
        if (take < planned.pieces()) {
            Log.warnf("StockService: %s: only %d of the %d planned %s were still in stock - the shortfall was NOT queued",
                    orderLabel, take, planned.pieces(), part);
        }
        notificationService.notifyEvent("order_from_stock", marketLabel(market),
                "%s: %d× %s for '%s' taken from on-hand stock, not printed (%d left)".formatted(
                        orderLabel, take, part, planned.itemLabel(), left));
        Log.infof("StockService: %s: %d× %s from stock (%d left)", orderLabel, take, part, left);
    }

    private static String marketLabel(final String market) {
        return "etsy".equals(market) ? "Etsy" : "ebay".equals(market) ? "eBay" : market;
    }
}
