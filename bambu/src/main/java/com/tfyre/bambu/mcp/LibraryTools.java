package com.tfyre.bambu.mcp;

import com.tfyre.bambu.printer.EbayApiClient;
import com.tfyre.bambu.printer.EbayMappingService;
import com.tfyre.bambu.printer.EtsyApiClient;
import com.tfyre.bambu.printer.EtsyMappingService;
import com.tfyre.bambu.printer.LibraryFileInfo;
import com.tfyre.bambu.printer.LibraryService;
import com.tfyre.bambu.printer.MappingPart;
import com.tfyre.bambu.printer.MarketListingCache;
import com.tfyre.bambu.printer.OrderTrackingService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Phase 1 library tools (docs/MCP-PLAN.md): {@code mappings} and {@code library_file}. Read-only. */
@Singleton
public class LibraryTools {

    private static final int MAX_ROWS = 100;

    @Inject
    EtsyMappingService etsyMappings;
    @Inject
    EbayMappingService ebayMappings;
    @Inject
    MarketListingCache listings;
    @Inject
    OrderTrackingService tracking;
    @Inject
    LibraryService library;
    @Inject
    LibraryFileInfo fileInfo;
    @Inject
    McpAudit audit;

    // ---------------------------------------------------------------------------------------------------------
    // mappings
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "mappings", description = """
            Listing-to-print-file mappings (what the Mappings page edits): per market listing and variation, the \
            listing title, product code, hidden flag and the mapped parts (file, plate, copies, tray, filament, \
            extra trays for supports, H2D variant). Optionally also lists cached listings that have no mapping.""",
            annotations = @Tool.Annotations(title = "Mappings", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse mappings(
            @ToolArg(description = "Case-insensitive text to match in title, listing id, variation or file path", required = false) final String query,
            @ToolArg(description = "etsy, ebay, or omit for both", required = false) final String market,
            @ToolArg(description = "Also list cached listings with no mapping at all", required = false, defaultValue = "false") final boolean includeUnmapped) {
        return audit.run("mappings", McpAudit.args("query", query, "market", market, "includeUnmapped", includeUnmapped),
                () -> audit.json(buildMappings(query, market, includeUnmapped)));
    }

    Map<String, Object> buildMappings(final String query, final String market, final boolean includeUnmapped) {
        final String q = HistoryTools.blank(query) ? null : query.toLowerCase(Locale.ROOT);
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> unmapped = new ArrayList<>();
        int matching = 0;

        if (wants(market, "etsy")) {
            final Map<String, String> titles = new HashMap<>();
            listings.getEtsy().forEach(l -> titles.put(String.valueOf(l.listingId()), l.title()));
            final Map<String, EtsyMappingService.MappingEntry> entries = new TreeMap<>(etsyMappings.entries());
            for (final var e : entries.entrySet()) {
                final Map<String, Object> row = row("etsy", e.getKey(), titles, e.getValue().parts(), e.getValue().productCode());
                if (matches(row, q)) {
                    matching++;
                    if (rows.size() < MAX_ROWS) {
                        rows.add(row);
                    }
                }
            }
            if (includeUnmapped) {
                final Set<String> mapped = mappedIds(entries.keySet());
                for (final EtsyApiClient.Listing l : listings.getEtsy()) {
                    final String id = String.valueOf(l.listingId());
                    if (!mapped.contains(id) && (q == null || (l.title() + " " + id).toLowerCase(Locale.ROOT).contains(q))) {
                        unmapped.add("etsy " + id + ": " + l.title());
                    }
                }
            }
        }
        if (wants(market, "ebay")) {
            final Map<String, String> titles = new HashMap<>();
            listings.getEbay().forEach(l -> titles.put(l.listingKey(), l.title()));
            final Map<String, EbayMappingService.MappingEntry> entries = new TreeMap<>(ebayMappings.entries());
            for (final var e : entries.entrySet()) {
                final Map<String, Object> row = row("ebay", e.getKey(), titles, e.getValue().parts(), e.getValue().productCode());
                if (matches(row, q)) {
                    matching++;
                    if (rows.size() < MAX_ROWS) {
                        rows.add(row);
                    }
                }
            }
            if (includeUnmapped) {
                final Set<String> mapped = mappedIds(entries.keySet());
                for (final EbayApiClient.EbayListing l : listings.getEbay()) {
                    if (!mapped.contains(l.listingKey())
                            && (q == null || (l.title() + " " + l.listingKey()).toLowerCase(Locale.ROOT).contains(q))) {
                        unmapped.add("ebay " + l.listingKey() + ": " + l.title());
                    }
                }
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("matching", matching);
        if (matching > rows.size()) {
            out.put("note", "showing the first %d; narrow with query".formatted(rows.size()));
        }
        out.put("mappings", rows);
        if (includeUnmapped) {
            out.put("unmappedListings", unmapped);
            listings.etsyLoadedAt().ifPresent(t -> out.put("etsyListingsLoaded", FarmTools.ago(t)));
            listings.ebayLoadedAt().ifPresent(t -> out.put("ebayListingsLoaded", FarmTools.ago(t)));
        }
        return out;
    }

    private Map<String, Object> row(final String market, final String storageKey, final Map<String, String> titles,
            final List<MappingPart> parts, final String productCode) {
        final int bar = storageKey.indexOf('|');
        final String listing = bar < 0 ? storageKey : storageKey.substring(0, bar);
        final String variation = bar < 0 ? "" : storageKey.substring(bar + 1);
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("market", market);
        m.put("listing", listing);
        m.put("title", titles.getOrDefault(listing, "(not in the listing cache)"));
        m.put("variation", variation.isEmpty() ? "(all)" : variation);
        if (productCode != null && !productCode.isBlank()) {
            m.put("productCode", productCode);
        }
        if (tracking.isListingHidden(market, listing)) {
            m.put("hidden", true);
        }
        m.put("parts", parts == null || parts.isEmpty() ? List.of("(unmapped - manual)") : OrderTools.parts(parts));
        return m;
    }

    private static boolean matches(final Map<String, Object> row, final String q) {
        return q == null || row.values().stream().map(String::valueOf).collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT).contains(q);
    }

    private static Set<String> mappedIds(final Set<String> storageKeys) {
        return storageKeys.stream().map(k -> k.contains("|") ? k.substring(0, k.indexOf('|')) : k).collect(Collectors.toSet());
    }

    private static boolean wants(final String market, final String which) {
        return market == null || market.isBlank() || market.equalsIgnoreCase(which);
    }

    // ---------------------------------------------------------------------------------------------------------
    // library_file
    // ---------------------------------------------------------------------------------------------------------

    @Tool(name = "library_file", description = """
            The print library. With path: what that sliced file is (printer family it was sliced for, plates, \
            filament count, material, grams, print time, objects, size) and which listings use it. With query \
            instead: library parts whose title, project or file matches, with P1 and H2D files, print counts, \
            fail rate and when last printed.""",
            annotations = @Tool.Annotations(title = "Library file", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ToolResponse libraryFile(
            @ToolArg(description = "Library-relative path of a file, as mappings and history show it", required = false) final String path,
            @ToolArg(description = "Case-insensitive text to search parts by, when no path is given", required = false) final String query) {
        return audit.run("library_file", McpAudit.args("path", path, "query", query), () -> libraryBody(path, query));
    }

    private ToolResponse libraryBody(final String path, final String query) {
        if (!HistoryTools.blank(path)) {
            if (path.contains("..")) {
                return ToolResponse.error("path must be library-relative, without '..'");
            }
            return audit.json(describe(path.trim()));
        }
        if (HistoryTools.blank(query)) {
            return ToolResponse.error("give a path, or a query to search the library");
        }
        final String q = query.toLowerCase(Locale.ROOT);
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final LibraryService.Part p : library.parts()) {
            final String hay = (p.title() + " " + p.project() + " " + p.p1() + " " + p.h2d()).toLowerCase(Locale.ROOT);
            if (!hay.contains(q)) {
                continue;
            }
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", p.title());
            if (!HistoryTools.blank(p.project())) {
                m.put("project", p.project());
            }
            if (p.p1() != null) {
                m.put("p1", p.p1());
            }
            if (p.h2d() != null) {
                m.put("h2d", p.h2d());
                if (p.h2dMakes() > 1) {
                    m.put("h2dMakes", p.h2dMakes());
                }
            }
            m.put("usedBy", p.uses().size());
            m.put("printed", p.finished() + " ok / " + p.failed() + " failed (" + p.failPct() + "% fail)");
            if (p.lastPrinted() != null) {
                m.put("lastPrinted", FarmTools.ago(p.lastPrinted().toInstant()) + (p.lastPrinter() == null ? "" : " on " + p.lastPrinter()));
            }
            rows.add(m);
            if (rows.size() >= 40) {
                break;
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", rows.size());
        out.put("parts", rows);
        return audit.json(out);
    }

    Map<String, Object> describe(final String path) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        final LibraryFileInfo.Info info = fileInfo.of(path);
        m.put("slicedFor", info.badge() + (info.byName() ? " (guessed from the file name)" : ""));
        final LibraryFileInfo.Details d = fileInfo.details(path);
        if (d == null || d.bytes() <= 0) {
            m.put("exists", false);
            return m;
        }
        m.put("plates", fileInfo.plateIds(path));
        m.put("filaments", fileInfo.filamentCount(path));
        if (!HistoryTools.blank(d.material())) {
            m.put("material", d.material());
        }
        if (d.grams() > 0) {
            m.put("grams", Math.round(d.grams() * 10) / 10.0);
        }
        if (d.seconds() > 0) {
            m.put("printTime", FarmTools.human(java.time.Duration.ofSeconds(d.seconds())));
        }
        m.put("objects", d.objects());
        m.put("sizeKb", d.bytes() / 1024);
        m.put("modified", FarmTools.ago(Instant.ofEpochMilli(d.modifiedMillis())));
        m.put("sliced", d.sliced());
        final List<String> uses = new ArrayList<>();
        library.usesOf(path).forEach(u -> uses.add(u.market() + ": " + u.title()
                + (HistoryTools.blank(u.variation()) ? "" : " [" + u.variation() + "]")
                + (u.piecesPerUnit() > 1 ? " x" + u.piecesPerUnit() : "")));
        m.put("usedBy", uses);
        return m;
    }
}
