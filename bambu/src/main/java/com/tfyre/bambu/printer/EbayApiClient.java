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
 * Thin HTTP wrapper around eBay's Sell Fulfillment API (getOrders) for reading open orders.
 * Critical calls (like fetching orders) throw with a descriptive message on failure so it can be surfaced to the
 * user; cosmetic calls swallow failures and return empty, per {@link #get(String)}.
 */
@ApplicationScoped
public class EbayApiClient {

    public record Variation(String propertyName, String value) {
    }

    public record LineItem(
            String lineItemId, String sku, String legacyItemId, String title, int quantity,
            List<Variation> variationAspects, Optional<String> personalization) {

        /** Stable identity for a listing when mapping to a gcode file: SKU if present, else the item id. */
        public String listingKey() {
            return (sku != null && !sku.isBlank()) ? sku : legacyItemId;
        }
    }

    public record Order(String orderId, String buyerUsername, Instant creationDate, String fulfillmentStatus, List<LineItem> lineItems) {
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    EbayOAuthService oauth;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String apiBase() {
        return config.ebay().sandbox() ? "https://api.sandbox.ebay.com" : "https://api.ebay.com";
    }

    /**
     * Performs the GET and throws a descriptive exception on any failure (missing auth, non-2xx, network error)
     * instead of swallowing it - callers that need the failure to surface to the user (e.g. the order poller,
     * whose error message is shown on the Sales Orders page) should use this rather than {@link #get(String)}.
     */
    private JsonNode getOrThrow(final String path) throws Exception {
        final Optional<String> token = oauth.getValidAccessToken();
        if (token.isEmpty()) {
            throw new IllegalStateException("Not connected to eBay, or the connection has expired - reconnect on the eBay Sales Orders page.");
        }
        final HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase() + path))
                .timeout(config.ebay().timeout())
                .header("Authorization", "Bearer " + token.get())
                .header("X-EBAY-C-MARKETPLACE-ID", config.ebay().marketplaceId())
                .GET()
                .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            Log.errorf("EbayApiClient: GET %s -> HTTP %d: %s", path, response.statusCode(), response.body());
            throw new IllegalStateException("eBay API returned HTTP %d for %s: %s"
                    .formatted(response.statusCode(), path, truncate(response.body())));
        }
        return mapper.readTree(response.body());
    }

    private static String truncate(final String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }

    // -------------------------------------------------------------------------
    // Active listings - Trading API GetMyeBaySelling
    // -------------------------------------------------------------------------

    /** An active listing, for the Mappings tab. {@link #listingKey()} matches {@link LineItem#listingKey()}. */
    public record EbayListing(String itemId, String sku, String title, int quantity) {

        /** Stable identity for mapping: SKU if present, else the item id - same rule as order line items. */
        public String listingKey() {
            return (sku != null && !sku.isBlank()) ? sku : itemId;
        }
    }

    private String tradingApiUrl() {
        return config.ebay().sandbox() ? "https://api.sandbox.ebay.com/ws/api.dll" : "https://api.ebay.com/ws/api.dll";
    }

    /**
     * Fetches ALL active listings via the Trading API's {@code GetMyeBaySelling} (paginated). The modern
     * Inventory API only returns listings created through it, so for shops managed via the eBay website this
     * legacy XML call is the one that actually sees everything. Requires the base + inventory scopes added to
     * the consent flow - accounts connected before that must Disconnect + Connect once on the orders page.
     */
    public List<EbayListing> getActiveListings() throws Exception {
        final Optional<String> token = oauth.getValidAccessToken();
        if (token.isEmpty()) {
            throw new IllegalStateException("Not connected to eBay - connect on the eBay Sales Orders page.");
        }
        final List<EbayListing> result = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        while (page <= totalPages && page <= 10) {
            final String body = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <GetMyeBaySellingRequest xmlns="urn:ebay:apis:eBLBaseComponents">
                      <ActiveList>
                        <Include>true</Include>
                        <Pagination>
                          <EntriesPerPage>200</EntriesPerPage>
                          <PageNumber>%d</PageNumber>
                        </Pagination>
                      </ActiveList>
                      <DetailLevel>ReturnAll</DetailLevel>
                    </GetMyeBaySellingRequest>
                    """.formatted(page);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(tradingApiUrl()))
                    .timeout(config.ebay().timeout())
                    .header("X-EBAY-API-COMPATIBILITY-LEVEL", "1193")
                    .header("X-EBAY-API-CALL-NAME", "GetMyeBaySelling")
                    .header("X-EBAY-API-SITEID", "0")
                    .header("X-EBAY-API-IAF-TOKEN", token.get())
                    .header("Content-Type", "text/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("EbayApiClient: GetMyeBaySelling -> HTTP %d: %s", response.statusCode(), response.body());
                throw new IllegalStateException("eBay Trading API returned HTTP %d: %s"
                        .formatted(response.statusCode(), truncate(response.body())));
            }
            final org.w3c.dom.Document doc = parseXml(response.body());
            final String ack = firstText(doc, "Ack");
            if ("Failure".equalsIgnoreCase(ack)) {
                final String error = Optional.ofNullable(firstText(doc, "LongMessage"))
                        .orElse(Optional.ofNullable(firstText(doc, "ShortMessage")).orElse("unknown error"));
                final boolean scopeIssue = error.toLowerCase().contains("token") || error.toLowerCase().contains("scope")
                        || error.toLowerCase().contains("iaf") || error.toLowerCase().contains("auth");
                throw new IllegalStateException("eBay listing fetch failed: %s%s".formatted(error, scopeIssue
                        ? " - if you connected before the listing permission was added, Disconnect and reconnect eBay on the Sales Orders page."
                        : ""));
            }
            totalPages = Optional.ofNullable(firstText(doc, "TotalNumberOfPages")).map(Integer::parseInt).orElse(1);
            final org.w3c.dom.NodeList items = doc.getElementsByTagName("Item");
            for (int i = 0; i < items.getLength(); i++) {
                final org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
                final String itemId = childText(item, "ItemID");
                if (itemId.isBlank()) {
                    continue;
                }
                final int quantity = Optional.of(childText(item, "QuantityAvailable")).filter(s -> !s.isBlank())
                        .or(() -> Optional.of(childText(item, "Quantity")).filter(s -> !s.isBlank()))
                        .map(Integer::parseInt).orElse(0);
                result.add(new EbayListing(itemId, childText(item, "SKU"), childText(item, "Title"), quantity));
            }
            page++;
        }
        result.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        return result;
    }

    private static org.w3c.dom.Document parseXml(final String xml) throws Exception {
        final javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        // XXE hardening - we only ever parse eBay's response envelope
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String firstText(final org.w3c.dom.Document doc, final String tag) {
        final org.w3c.dom.NodeList nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static String childText(final org.w3c.dom.Element parent, final String tag) {
        final org.w3c.dom.NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    /** Swallowing variant for non-critical calls. */
    private Optional<JsonNode> get(final String path) {
        try {
            return Optional.of(getOrThrow(path));
        } catch (Exception ex) {
            Log.errorf(ex, "EbayApiClient: GET %s failed: %s", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private static List<Variation> parseVariations(final JsonNode lineItem) {
        final List<Variation> result = new ArrayList<>();
        for (final JsonNode v : lineItem.path("variationAspects")) {
            final String name = v.path("name").asText("");
            final String value = v.path("value").asText("");
            if (!name.isBlank()) {
                result.add(new Variation(name, value));
            }
        }
        return result;
    }

    private static Optional<String> parsePersonalization(final JsonNode lineItem) {
        // eBay's documented getOrders schema has no dedicated personalization field (unlike Etsy); check the couple
        // of shapes seen for "Personalize It" listings in case one is present, without assuming a fixed structure.
        final JsonNode custom = lineItem.path("customizedOptions");
        if (custom.isArray() && !custom.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (final JsonNode opt : custom) {
                final String name = opt.path("name").asText("");
                final String value = opt.path("value").asText("");
                if (!value.isBlank()) {
                    sb.append(sb.isEmpty() ? "" : "; ").append(name.isBlank() ? value : name + ": " + value);
                }
            }
            if (!sb.isEmpty()) {
                return Optional.of(sb.toString());
            }
        }
        final String notes = lineItem.path("buyerCustomization").asText("");
        return notes.isBlank() ? Optional.empty() : Optional.of(notes);
    }

    private LineItem parseLineItem(final JsonNode li) {
        return new LineItem(
                li.path("lineItemId").asText(""),
                li.path("sku").asText(""),
                li.path("legacyItemId").asText(""),
                li.path("title").asText(""),
                Math.max(1, li.path("quantity").asInt(1)),
                parseVariations(li),
                parsePersonalization(li));
    }

    private Order parseOrder(final JsonNode o) {
        final List<LineItem> lineItems = new ArrayList<>();
        for (final JsonNode li : o.path("lineItems")) {
            lineItems.add(parseLineItem(li));
        }
        return new Order(
                o.path("orderId").asText(""),
                o.path("buyer").path("username").asText("(unknown buyer)"),
                parseInstant(o.path("creationDate").asText("")),
                o.path("orderFulfillmentStatus").asText("NOT_STARTED"),
                lineItems);
    }

    private static Instant parseInstant(final String text) {
        try {
            return text.isBlank() ? Instant.EPOCH : Instant.parse(text);
        } catch (Exception ex) {
            return Instant.EPOCH;
        }
    }

    /**
     * Fetches orders that still need fulfillment (NOT_STARTED or IN_PROGRESS), newest first. Throws on failure so
     * the caller can surface the real reason instead of silently showing "no orders".
     */
    public List<Order> getOpenOrders() throws Exception {
        final String filter = java.net.URLEncoder.encode("orderfulfillmentstatus:{NOT_STARTED|IN_PROGRESS}", java.nio.charset.StandardCharsets.UTF_8);
        final String path = "/sell/fulfillment/v1/order?filter=" + filter + "&limit=200";
        final JsonNode root = getOrThrow(path);
        final List<Order> result = new ArrayList<>();
        for (final JsonNode o : root.path("orders")) {
            result.add(parseOrder(o));
        }
        result.sort((a, b) -> b.creationDate().compareTo(a.creationDate()));
        return result;
    }

    public boolean isConnected() {
        return oauth.isConnected();
    }

}
