package com.tfyre.bambu.printer;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-memory cache of the last "Load active listings" pull for each marketplace, so the Mappings tab shows the
 * listings again after a page reload or navigating away and back - instead of coming up empty until the user
 * clicks Load again. Application-scoped, so it's shared across page visits and survives until the app restarts;
 * the Reload button on the Mappings tab still re-fetches fresh data on demand.
 */
@ApplicationScoped
public class MarketListingCache {

    private volatile List<EtsyApiClient.Listing> etsy = List.of();
    private volatile List<EbayApiClient.EbayListing> ebay = List.of();
    private volatile Instant etsyLoadedAt;
    private volatile Instant ebayLoadedAt;

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
    }

    public void setEbay(final List<EbayApiClient.EbayListing> listings) {
        this.ebay = listings == null ? List.of() : listings;
        this.ebayLoadedAt = Instant.now();
    }
}
