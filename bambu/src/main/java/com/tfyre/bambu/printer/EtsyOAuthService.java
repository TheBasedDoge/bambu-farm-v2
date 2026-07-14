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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Etsy OAuth 2.0 (Authorization Code + PKCE) flow, per
 * https://developer.etsy.com/documentation/essentials/authentication/
 * <p>
 * Only one scope is requested: {@code transactions_r} (read receipts/orders) plus {@code listings_r} (read listing
 * details/images for the mapping UI).
 */
@ApplicationScoped
public class EtsyOAuthService {

    private static final String AUTHORIZE_URL = "https://www.etsy.com/oauth/connect";
    private static final String TOKEN_URL = "https://api.etsy.com/v3/public/oauth/token";
    private static final String SCOPES = "transactions_r listings_r";

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    EtsyTokenStore tokenStore;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** How long an in-flight authorization request stays valid before it's purged. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    private record PendingAuth(String verifier, Instant created) {
    }

    /** state -> PKCE verifier + creation time, for in-flight authorization requests. Single-use, purged after {@link #PENDING_TTL}. */
    private final Map<String, PendingAuth> pending = new ConcurrentHashMap<>();

    public boolean isConfigured() {
        return config.etsy().clientId().isPresent()
                && config.etsy().sharedSecret().isPresent()
                && config.etsy().shopId().isPresent()
                && config.etsy().redirectUri().isPresent();
    }

    private static String randomUrlSafe(final int numBytes) {
        final byte[] bytes = new byte[numBytes];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(final String verifier) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Builds the URL to send the user to in order to grant access. Generates and stashes a fresh state + PKCE
     * verifier pair.
     */
    public Optional<String> buildAuthorizeUrl() {
        if (!isConfigured()) {
            return Optional.empty();
        }
        // Purge abandoned auth attempts so the map can't grow forever
        pending.values().removeIf(p -> p.created().isBefore(Instant.now().minus(PENDING_TTL)));
        final String state = randomUrlSafe(24);
        final String verifier = randomUrlSafe(48);
        pending.put(state, new PendingAuth(verifier, Instant.now()));
        final String challenge = codeChallenge(verifier);
        final String url = AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + enc(config.etsy().clientId().get())
                + "&redirect_uri=" + enc(config.etsy().redirectUri().get())
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256";
        return Optional.of(url);
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Completes the flow: exchanges the authorization code for tokens and persists them. Called from the OAuth
     * callback route with the {@code state} and {@code code} query params Etsy sent back.
     *
     * @return empty on success, or an error message to show the user
     */
    public Optional<String> handleCallback(final String state, final String code) {
        final PendingAuth pendingAuth = state == null ? null : pending.remove(state);
        if (pendingAuth == null || pendingAuth.created().isBefore(Instant.now().minus(PENDING_TTL))) {
            return Optional.of("Unknown or expired authorization request - please try connecting again.");
        }
        final String verifier = pendingAuth.verifier();
        if (!isConfigured()) {
            return Optional.of("Etsy is not configured (missing client id / secret / shop id / redirect uri).");
        }
        try {
            final String form = "grant_type=authorization_code"
                    + "&client_id=" + enc(config.etsy().clientId().get())
                    + "&redirect_uri=" + enc(config.etsy().redirectUri().get())
                    + "&code=" + enc(code)
                    + "&code_verifier=" + enc(verifier);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(config.etsy().timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("EtsyOAuthService: token exchange HTTP %d: %s", response.statusCode(), response.body());
                return Optional.of("Etsy rejected the token exchange (HTTP %d).".formatted(response.statusCode()));
            }
            saveTokenResponse(response.body());
            Log.info("EtsyOAuthService: connected to Etsy successfully");
            return Optional.empty();
        } catch (Exception ex) {
            Log.errorf(ex, "EtsyOAuthService: token exchange failed: %s", ex.getMessage());
            return Optional.of("Token exchange failed: %s".formatted(ex.getMessage()));
        }
    }

    private void saveTokenResponse(final String body) throws Exception {
        final JsonNode root = mapper.readTree(body);
        final String accessToken = root.path("access_token").asText();
        final String refreshToken = root.path("refresh_token").asText();
        final int expiresIn = root.path("expires_in").asInt(3600);
        final String userId = accessToken.contains(".") ? accessToken.substring(0, accessToken.indexOf('.')) : "";
        tokenStore.set(new EtsyTokenStore.Tokens(accessToken, refreshToken, Instant.now().plusSeconds(expiresIn), userId));
    }

    /**
     * Returns a currently-valid access token, refreshing it first if it has expired. Empty when not connected or the
     * refresh fails (in which case the stored tokens are cleared so the UI prompts to reconnect).
     */
    public Optional<String> getValidAccessToken() {
        final Optional<EtsyTokenStore.Tokens> oTokens = tokenStore.get();
        if (oTokens.isEmpty()) {
            return Optional.empty();
        }
        final EtsyTokenStore.Tokens current = oTokens.get();
        if (!current.isExpired()) {
            return Optional.of(current.accessToken());
        }
        return refresh(current);
    }

    private synchronized Optional<String> refresh(final EtsyTokenStore.Tokens current) {
        // Another thread may have refreshed already while we waited for the lock
        final EtsyTokenStore.Tokens latest = tokenStore.get().orElse(current);
        if (!latest.isExpired()) {
            return Optional.of(latest.accessToken());
        }
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            final String form = "grant_type=refresh_token"
                    + "&client_id=" + enc(config.etsy().clientId().get())
                    + "&refresh_token=" + enc(latest.refreshToken());
            final HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(config.etsy().timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // Only a 4xx means the refresh token itself is bad (revoked/expired) - clearing then is correct
                // so the UI prompts to reconnect. A 5xx is Etsy having a moment; keep the tokens and let the
                // next poll retry, otherwise a transient blip silently disconnects the shop.
                if (response.statusCode() < 500) {
                    Log.errorf("EtsyOAuthService: refresh HTTP %d: %s - clearing stored tokens", response.statusCode(), response.body());
                    tokenStore.clear();
                } else {
                    Log.errorf("EtsyOAuthService: refresh HTTP %d (transient?): %s - keeping tokens, will retry", response.statusCode(), response.body());
                }
                return Optional.empty();
            }
            saveTokenResponse(response.body());
            return tokenStore.get().map(EtsyTokenStore.Tokens::accessToken);
        } catch (Exception ex) {
            Log.errorf(ex, "EtsyOAuthService: refresh failed: %s", ex.getMessage());
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
