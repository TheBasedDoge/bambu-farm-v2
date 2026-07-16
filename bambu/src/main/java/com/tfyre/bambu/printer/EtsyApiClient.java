package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin HTTP wrapper around the Etsy Open API v3 for reading shop receipts (orders) and listing images.
 * Critical calls (like fetching orders) throw with a descriptive message on failure so it can be surfaced to the
 * user; cosmetic calls swallow failures and return empty, per {@link #get(String)}.
 */
@ApplicationScoped
public class EtsyApiClient {

    private static final String BASE = "https://api.etsy.com/v3/application";

    public record Variation(String propertyName, String value) {
    }

    public record Transaction(
            long transactionId, long listingId, String title, int quantity,
            List<Variation> variations, Optional<String> personalization, Optional<String> imageUrl) {
    }

    public record Receipt(
            long receiptId, String buyerName, boolean isShipped, boolean isPaid, String status,
            Instant createTimestamp, List<Transaction> transactions) {

        /** Still needs to be printed/shipped - not already shipped, canceled, or refunded. */
        public boolean isUnfulfilled() {
            if (isShipped) {
                return false;
            }
            final String s = status == null ? "" : status.toLowerCase();
            return !s.equals("completed") && !s.equals("canceled") && !s.contains("refunded");
        }
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    EtsyOAuthService oauth;
    @Inject
    EtsyTokenStore tokenStore;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Optional<String> apiKey() {
        return config.etsy().clientId().flatMap(id -> config.etsy().sharedSecret().map(secret -> id + ":" + secret));
    }

    /**
     * Performs the GET and throws a descriptive exception on any failure (missing auth, non-2xx, network error)
     * instead of swallowing it - callers that need the failure to surface to the user (e.g. the order poller,
     * whose error message is shown on the Sales Orders page) should use this rather than {@link #get(String)}.
     */
    private JsonNode getOrThrow(final String path) throws Exception {
        final Optional<String> token = oauth.getValidAccessToken();
        if (token.isEmpty()) {
            throw new IllegalStateException("Not connected to Etsy, or the connection has expired - reconnect on the Etsy Sales Orders page.");
        }
        final Optional<String> key = apiKey();
        if (key.isEmpty()) {
            throw new IllegalStateException("Etsy client id / shared secret is not configured.");
        }
        final HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(config.etsy().timeout())
                .header("x-api-key", key.get())
                .header("Authorization", "Bearer " + token.get())
                .GET()
                .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            Log.errorf("EtsyApiClient: GET %s -> HTTP %d: %s", path, response.statusCode(), response.body());
            throw new IllegalStateException("Etsy API returned HTTP %d for %s: %s"
                    .formatted(response.statusCode(), path, truncate(response.body())));
        }
        return mapper.readTree(response.body());
    }

    private static String truncate(final String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }

    /** Swallowing variant for non-critical, cosmetic calls (e.g. listing thumbnail lookup). */
    private Optional<JsonNode> get(final String path) {
        try {
            return Optional.of(getOrThrow(path));
        } catch (Exception ex) {
            Log.errorf(ex, "EtsyApiClient: GET %s failed: %s", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private static List<Variation> parseVariations(final JsonNode transactionNode) {
        final List<Variation> result = new ArrayList<>();
        for (final JsonNode v : transactionNode.path("variations")) {
            final String name = v.path("formatted_name").asText("");
            final String value = v.path("formatted_value").asText("");
            if (!name.isBlank()) {
                result.add(new Variation(name, value));
            }
        }
        return result;
    }

    private static Optional<String> parsePersonalization(final JsonNode transactionNode) {
        // Older/newer Etsy schema variants both surface personalization text somewhere on the transaction;
        // check the couple of shapes seen in the wild rather than assuming one.
        final JsonNode direct = transactionNode.path("personalization");
        if (direct.isTextual() && !direct.asText().isBlank()) {
            return Optional.of(direct.asText());
        }
        if (direct.isObject()) {
            final String value = direct.path("value").asText(direct.path("personalized_instructions").asText(""));
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        for (final JsonNode v : transactionNode.path("variations")) {
            if ("Personalization".equalsIgnoreCase(v.path("formatted_name").asText(""))) {
                final String value = v.path("formatted_value").asText("");
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private Transaction parseTransaction(final JsonNode t) {
        return new Transaction(
                t.path("transaction_id").asLong(),
                t.path("listing_id").asLong(),
                t.path("title").asText(""),
                Math.max(1, t.path("quantity").asInt(1)),
                parseVariations(t),
                parsePersonalization(t),
                Optional.empty());
    }

    private Receipt parseReceipt(final JsonNode r) {
        final List<Transaction> transactions = new ArrayList<>();
        for (final JsonNode t : r.path("transactions")) {
            transactions.add(parseTransaction(t));
        }
        return new Receipt(
                r.path("receipt_id").asLong(),
                r.path("name").asText("(unknown buyer)"),
                r.path("is_shipped").asBoolean(false),
                r.path("is_paid").asBoolean(true),
                r.path("status").asText(""),
                Instant.ofEpochSecond(r.path("create_timestamp").asLong(0)),
                transactions);
    }

    /**
     * Fetches receipts that still need to be printed and shipped, newest first. Requests the was_paid/was_shipped
     * query filters as a first pass, but does NOT rely on Etsy actually honoring them - every result is also
     * checked client-side via {@link Receipt#isUnfulfilled()} (is_shipped + status), since a "completed" order can
     * apparently keep is_shipped=false for some order types, and the reverse (an unshipped order reappearing) is
     * far worse than a redundant check. Throws on failure (missing config, auth, non-2xx) so the caller can surface
     * the real reason instead of silently showing "no orders".
     */
    public List<Receipt> getUnfulfilledReceipts() throws Exception {
        final Optional<String> shopId = config.etsy().shopId();
        if (shopId.isEmpty()) {
            throw new IllegalStateException("bambu.etsy.shop-id is not configured.");
        }
        // Sorting is done client-side below - avoids depending on getting Etsy's sort_on/sort_order enum values
        // exactly right, since a rejected query param here fails the whole call.
        // Paginate via offset: a single page caps at 100 receipts, and while >100 open orders is a good
        // problem to have, silently hiding the oldest ones would be a bad way to find out.
        final List<Receipt> result = new ArrayList<>();
        final int pageSize = 100;
        for (int offset = 0; offset < 1000; offset += pageSize) {
            final String path = "/shops/%s/receipts?was_paid=true&was_shipped=false&limit=%d&offset=%d"
                    .formatted(shopId.get(), pageSize, offset);
            final JsonNode root = getOrThrow(path);
            int pageCount = 0;
            for (final JsonNode r : root.path("results")) {
                pageCount++;
                final Receipt receipt = parseReceipt(r);
                if (receipt.isUnfulfilled()) {
                    result.add(receipt);
                }
            }
            if (pageCount < pageSize) {
                break;
            }
        }
        result.sort((a, b) -> b.createTimestamp().compareTo(a.createTimestamp()));
        return result;
    }

    /** An active shop listing, for the Mappings tab's assign-gcodes table. {@code imageUrl} may be blank. */
    public record Listing(long listingId, String title, int quantityAvailable, String imageUrl, boolean hasVariations) {
    }

    /**
     * Fetches ALL active listings in the shop (paginated), so every product can be pre-mapped to gcode before
     * an order ever arrives. Requires the {@code listings_r} scope, which the connect flow already requests.
     * <p>
     * Uses {@code getListingsByShop} ({@code /shops/{id}/listings?state=active}) rather than
     * {@code findAllActiveListingsByShop} ({@code /shops/{id}/listings/active}): only the former accepts the
     * {@code includes} parameter, so it's the one that actually embeds listing images (and it returns
     * {@code has_variations}, which drives the per-variation Map button in the UI).
     */
    public List<Listing> getActiveListings() throws Exception {
        final Optional<String> shopId = config.etsy().shopId();
        if (shopId.isEmpty()) {
            throw new IllegalStateException("bambu.etsy.shop-id is not configured.");
        }
        final List<Listing> result = new ArrayList<>();
        final int pageSize = 100;
        for (int offset = 0; offset < 1000; offset += pageSize) {
            final JsonNode root = getOrThrow("/shops/%s/listings?state=active&limit=%d&offset=%d&includes=Images"
                    .formatted(shopId.get(), pageSize, offset));
            int pageCount = 0;
            for (final JsonNode l : root.path("results")) {
                pageCount++;
                final JsonNode firstImage = l.path("images").path(0);
                final String imageUrl = firstImage.path("url_75x75").asText(firstImage.path("url_170x135").asText(""));
                result.add(new Listing(
                        l.path("listing_id").asLong(),
                        l.path("title").asText(""),
                        l.path("quantity").asInt(0),
                        imageUrl,
                        l.path("has_variations").asBoolean(false)));
            }
            if (pageCount < pageSize) {
                break;
            }
        }
        result.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        return result;
    }

    /** One purchasable variation combination of a listing (e.g. Color=Red, Size=Large), from its inventory. */
    public record VariationCombo(List<Variation> variations, String sku, int quantity) {
    }

    /**
     * Fetches a listing's variation combinations from its inventory, so each can be pre-mapped to its own gcode in
     * the Mappings tab (before any order arrives). Each combo's {@code variations} carry the same property
     * name/value pairs an order transaction reports, so a mapping saved here resolves for the matching order.
     * Returns an empty list for listings that have no variations (a listing without variations still returns one
     * property-less product, which is skipped). Uses the {@code listings_r} scope the connect flow already requests.
     */
    public List<VariationCombo> getListingVariations(final long listingId) throws Exception {
        final JsonNode root = getOrThrow("/listings/%d/inventory".formatted(listingId));
        final List<VariationCombo> result = new ArrayList<>();
        for (final JsonNode product : root.path("products")) {
            final List<Variation> variations = new ArrayList<>();
            for (final JsonNode pv : product.path("property_values")) {
                final String name = pv.path("property_name").asText("");
                final String value = pv.path("values").path(0).asText("");
                if (!name.isBlank() && !value.isBlank()) {
                    variations.add(new Variation(name, value));
                }
            }
            if (variations.isEmpty()) {
                continue;
            }
            int quantity = 0;
            for (final JsonNode off : product.path("offerings")) {
                quantity += Math.max(0, off.path("quantity").asInt(0));
            }
            result.add(new VariationCombo(variations, product.path("sku").asText(""), quantity));
        }
        return result;
    }

    /**
     * Fetches the primary listing image URL for a listing, used to help identify which gcode file to map it to.
     */
    public Optional<String> getListingImageUrl(final long listingId) {
        final Optional<JsonNode> oRoot = get("/listings/%d/images".formatted(listingId));
        if (oRoot.isEmpty()) {
            return Optional.empty();
        }
        final JsonNode results = oRoot.get().path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        final String url = results.get(0).path("url_570xN").asText("");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    public boolean isConnected() {
        return oauth.isConnected();
    }

    /**
     * Looks up the shop ID that belongs to the Etsy account you connected with - handy when
     * {@code bambu.etsy.shop-id} is wrong (e.g. a shop ID copied from a different account, or the numeric buyer
     * user ID used by mistake), which shows up as an HTTP 403 "User does not own Shop ..." on every other call.
     */
    public Optional<Long> findMyShopId() throws Exception {
        final String userId = tokenStore.get().map(EtsyTokenStore.Tokens::userId).orElse("");
        if (userId.isBlank()) {
            throw new IllegalStateException("Not connected to Etsy.");
        }
        final JsonNode root = getOrThrow("/users/%s/shops".formatted(userId));
        final long shopId = root.path("shop_id").asLong(0);
        return shopId > 0 ? Optional.of(shopId) : Optional.empty();
    }

}
