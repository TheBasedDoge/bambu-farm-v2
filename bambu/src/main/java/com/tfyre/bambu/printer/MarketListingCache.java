package com.tfyre.bambu.printer;

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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cache of the last "Load active listings" pull for each marketplace, so the Mappings tab shows the listings again
 * instead of coming up empty until the user clicks Load. The Reload button still re-fetches fresh data on demand.
 * <p>
 * <b>Persisted to disk</b>, not just held in memory. It was application-scoped fields only, which survived a page
 * reload but not a restart - and on a day with a dozen deploys that meant re-clicking Load constantly. Listings are
 * flat records holding image URLs rather than image data, so the file is small.
 * <p>
 * The cached copy is only a display convenience: mappings and orders never read it, so a stale or missing file
 * costs nothing beyond one click. Load failures are logged and swallowed for that reason.
 */
@ApplicationScoped
public class MarketListingCache {

    private static final String STORE_FILENAME = "bambu-listing-cache.json";

    /** On-disk shape. Nullable fields throughout: a half-populated cache (one marketplace loaded) is normal. */
    public record CacheData(List<EtsyApiClient.Listing> etsy, List<EbayApiClient.EbayListing> ebay,
            Instant etsyLoadedAt, Instant ebayLoadedAt) {
    }

    @Inject
    ObjectMapper mapper;
    @Inject
    BambuConfig config;

    private volatile List<EtsyApiClient.Listing> etsy = List.of();
    private volatile List<EbayApiClient.EbayListing> ebay = List.of();
    private volatile Instant etsyLoadedAt;
    private volatile Instant ebayLoadedAt;

    private Path storePath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return (parent != null ? parent : Path.of(".")).resolve(STORE_FILENAME);
    }

    @PostConstruct
    void load() {
        final Path path = storePath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final CacheData data = mapper.readValue(path.toFile(), CacheData.class);
            etsy = data.etsy() == null ? List.of() : data.etsy();
            ebay = data.ebay() == null ? List.of() : data.ebay();
            etsyLoadedAt = data.etsyLoadedAt();
            ebayLoadedAt = data.ebayLoadedAt();
            Log.infof("MarketListingCache: loaded %d Etsy and %d eBay listing(s) from %s",
                    etsy.size(), ebay.size(), path);
        } catch (Exception ex) {
            // Deliberately broad, and deliberately not fatal. This runs at startup, and the cache is a display
            // convenience nothing else reads - mappings and orders never touch it. A file written by an older
            // build, or a record shape that has since changed, must cost one click of "Load active listings",
            // not a failed boot.
            Log.warnf("MarketListingCache: cannot load %s (%s) - listings will be empty until reloaded",
                    path, ex.getMessage());
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(storePath().toFile(),
                    new CacheData(etsy, ebay, etsyLoadedAt, ebayLoadedAt));
        } catch (IOException ex) {
            Log.errorf(ex, "MarketListingCache: cannot save %s: %s", storePath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public List<EtsyApiClient.Listing> getEtsy() {
        return etsy;
    }

    public List<EbayApiClient.EbayListing> getEbay() {
        return ebay;
    }

    public Optional<Instant> etsyLoadedAt() {
        return Optional.ofNullable(etsyLoadedAt);
    }

    public Optional<Instant> ebayLoadedAt() {
        return Optional.ofNullable(ebayLoadedAt);
    }

    public void setEtsy(final List<EtsyApiClient.Listing> listings) {
        this.etsy = listings == null ? List.of() : listings;
        this.etsyLoadedAt = Instant.now();
        save();
    }

    public void setEbay(final List<EbayApiClient.EbayListing> listings) {
        this.ebay = listings == null ? List.of() : listings;
        this.ebayLoadedAt = Instant.now();
        save();
    }
}
