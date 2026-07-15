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
    public record EbayListing(String itemId, String sku, String title, int quantity, String imageUrl,
            List<EbayVariation> variations) {

        /** Stable identity for mapping: SKU if present, else the item id - same rule as order line items. */
        public String listingKey() {
            return (sku != null && !sku.isBlank()) ? sku : itemId;
        }
    }

    /**
     * One variation of a multi-variation eBay listing (e.g. Color=Red, Size=Large). Each variation of a listing
     * usually carries its own SKU, which is exactly what an order line item reports, so
     * {@link #listingKey(String)} matches {@link LineItem#listingKey()} for that variation.
     */
    public record EbayVariation(String sku, List<Variation> specifics, int quantityAvailable) {

        /** SKU if the variation has one, else the parent listing's item id - the same rule order line items use. */
        public String listingKey(final String parentItemId) {
            return (sku != null && !sku.isBlank()) ? sku : parentItemId;
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
                // SKU read as a DIRECT child only: on a variation listing getElementsByTagName would otherwise
                // return the first nested variation SKU, making the parent row collide with a variation's key.
                result.add(new EbayListing(itemId, directChildText(item, "SKU"), childText(item, "Title"), quantity,
                        childText(item, "GalleryURL"), parseListingVariations(item)));
            }
            page++;
        }
        result.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        return result;
    }

    /**
     * Parses the {@code <Variations>} block of an active listing's {@code <Item>} into per-variation SKU +
     * specifics, scoped to direct children so item-level {@code ItemSpecifics} (which reuse {@code NameValueList})
     * aren't mistaken for variation specifics. Returns an empty list for single-variation listings.
     */
    private static List<EbayVariation> parseListingVariations(final org.w3c.dom.Element item) {
        final org.w3c.dom.Element variations = directChild(item, "Variations");
        if (variations == null) {
            return List.of();
        }
        final List<EbayVariation> result = new ArrayList<>();
        for (final org.w3c.dom.Element variation : directChildren(variations, "Variation")) {
            final List<Variation> specifics = new ArrayList<>();
            final org.w3c.dom.Element specificsEl = directChild(variation, "VariationSpecifics");
            if (specificsEl != null) {
                for (final org.w3c.dom.Element nv : directChildren(specificsEl, "NameValueList")) {
                    final String name = childText(nv, "Name");
                    final String value = childText(nv, "Value");
                    if (!name.isBlank() && !value.isBlank()) {
                        specifics.add(new Variation(name, value));
                    }
                }
            }
            if (specifics.isEmpty()) {
                continue;
            }
            final int qty = parseIntOrZero(childText(variation, "Quantity"));
            final int sold = parseIntOrZero(childText(variation, "QuantitySold"));
            result.add(new EbayVariation(childText(variation, "SKU"), specifics, Math.max(0, qty - sold)));
        }
        return result;
    }

    private static int parseIntOrZero(final String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Trimmed text of the first direct child element with the given tag name, or {@code ""}. */
    private static String directChildText(final org.w3c.dom.Element parent, final String tag) {
        final org.w3c.dom.Element child = directChild(parent, tag);
        return child == null ? "" : child.getTextContent().trim();
    }

    /** First direct child element with the given tag name, or {@code null}. */
    private static org.w3c.dom.Element directChild(final org.w3c.dom.Element parent, final String tag) {
        final org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final org.w3c.dom.Node n = children.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (org.w3c.dom.Element) n;
            }
        }
        return null;
    }

    /** All direct child elements with the given tag name. */
    private static List<org.w3c.dom.Element> directChildren(final org.w3c.dom.Element parent, final String tag) {
        final List<org.w3c.dom.Element> result = new ArrayList<>();
        final org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final org.w3c.dom.Node n = children.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                result.add((org.w3c.dom.Element) n);
            }
        }
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

    private static String firstText(final org.w3c.dom.Doc