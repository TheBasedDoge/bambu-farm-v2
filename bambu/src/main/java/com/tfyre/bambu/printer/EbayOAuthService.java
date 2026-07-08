package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * eBay OAuth 2.0 (Authorization Code Grant) flow, per
 * https://developer.ebay.com/api-docs/static/oauth-auth-code-grant-request.html
 * <p>
 * Unlike Etsy, eBay's flow has no PKCE, uses an opaque "RuName" in place of a real redirect_uri in the authorize/
 * token requests (the real HTTPS callback is configured separately in the eBay developer account against that
 * RuName), and authenticates the token endpoint with HTTP Basic auth (client_id:client_secret) rather than sending
 * the client_id as a body param alone. The refresh grant also does not return a new refresh token.
 */
@ApplicationScoped
public class EbayOAuthService {

    private static final String SCOPE = "https://api.ebay.com/oauth/api_scope/sell.fulfillment.readonly";

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    EbayTokenStore tokenStore;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Single-use state tokens for in-flight authorization requests, to guard against CSRF. */
    private final Map<String, Boolean> pendingStates = new ConcurrentHashMap<>();

    public boolean isConfigured() {
        return config.ebay().clientId().isPresent()
                && config.ebay().clientSecret().isPresent()
                && config.ebay().ruName().isPresent();
    }

    private String authorizeBase() {
        return config.ebay().sandbox() ? "https://auth.sandbox.ebay.com/oauth2/authorize" : "https://auth.ebay.com/oauth2/authorize";
    }

    private String tokenUrl() {
        return config.ebay().sandbox() ? "https://api.sandbox.ebay.com/identity/v1/oauth2/token" : "https://api.ebay.com/identity/v1/oauth2/token";
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomState() {
        final byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Optional<String> buildAuthorizeUrl() {
        if (!isConfigured()) {
            return Optional.empty();
        }
        final String state = randomState();
        pendingStates.put(state, Boolean.TRUE);
        final String url = authorizeBase()
                + "?client_id=" + enc(config.ebay().clientId().get())
                + "&redirect_uri=" + enc(config.ebay().ruName().get())
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state);
        return Optional.of(url);
    }

    private String basicAuthHeader() {
        final String raw = config.ebay().clientId().orElse("") + ":" + config.ebay().clientSecret().orElse("");
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Completes the flow: exchanges the authorization code for tokens and persists them.
     *
     * @return empty on success, or an error message to show the user
     */
    public Optional<String> handleCallback(final String state, final String code) {
        if (pendingStates.remove(state) == null) {
            return Optional.of("Unknown or expired authorization request - please try connecting again.");
        }
        if (!isConfigured()) {
            return Optional.of("eBay is not configured (missing client id / secret / RuName).");
        }
        try {
            final String form = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(config.ebay().ruName().get());
            final HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl()))
                    .timeout(config.ebay().timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", basicAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("EbayOAuthService: token exchange HTTP %d: %s", response.statusCode(), response.body());
                return Optional.of("eBay rejected the token exchange (HTTP %d).".formatted(response.statusCode()));
            }
            final JsonNode root = mapper.readTree(response.body());
            final String accessToken = root.path("access_token").asText();
            final String refreshToken = root.path("refresh_token").asText();
            final int expiresIn = root.path("expires_in").asInt(7200);
            final int refreshExpiresIn = root.path("refresh_token_expires_in").asInt(0);
            final Instant now = Instant.now();
            tokenStore.set(new EbayTokenStore.Tokens(accessToken, now.plusSeconds(expiresIn),
                    refreshToken, refreshExpiresIn > 0 ? now.plusSeconds(refreshExpiresIn) : null));
            Log.info("EbayOAuthService: connected to eBay successfully");
            return Optional.empty();
        } catch (Exception ex) {
            Log.errorf(ex, "EbayOAuthService: token exchange failed: %s", ex.getMessage());
            return Optional.of("Token exchange failed: %s".formatted(ex.getMessage()));
        }
    }

    /**
     * Returns a currently-valid access token, refreshing it first if it has expired. Empty when not connected, the
     * refresh token has itself expired (re-authorization required), or the refresh call fails.
     */
    public Optional<String> getValidAccessToken() {
        final Optional<EbayTokenStore.Tokens> oTokens = tokenStore.get();
        if (oTokens.isEmpty()) {
            return Optional.empty();
        }
        final EbayTokenStore.Tokens current = oTokens.get();
        if (!current.isExpired()) {
            return Optional.of(current.accessToken());
        }
        return refresh(current);
    }

    private synchronized Optional<String> refresh(final EbayTokenStore.Tokens current) {
        final EbayTokenStore.Tokens latest = tokenStore.get().orElse(current);
        if (!latest.isExpired()) {
            return Optional.of(latest.accessToken());
        }
        if (latest.isRefreshExpired()) {
            Log.warn("EbayOAuthService: refresh token has expired - user must reconnect");
            tokenStore.clear();
            return Optional.empty();
        }
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            final String form = "grant_type=refresh_token&refresh_token=" + enc(latest.refreshToken()) + "&scope=" + enc(SCOPE);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl()))
                    .timeout(config.ebay().timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", basicAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("EbayOAuthService: refresh HTTP %d: %s - clearing stored tokens", response.statusCode(), response.body());
                tokenStore.clear();
                return Optional.empty();
            }
            final JsonNode root = mapper.readTree(response.body());
            final String accessToken = root.path("access_token").asText();
            final int expiresIn = root.path("expires_in").asInt(7200);
            tokenStore.updateAccessToken(accessToken, Instant.now().plusSeconds(expiresIn));
            return Optional.of(accessToken);
        } catch (Exception ex) {
            Log.errorf(ex, "EbayOAuthService: refresh failed: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    public void disconnect() {
        tokenStore.clear();
    }

    /** Whether we have stored tokens at all (does not verify/refresh them - cheap check for UI state). */
    public boolean isConnected() {
        return tokenStore.isConnected();
    }

}
