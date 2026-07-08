package com.tfyre.bambu.security;

import com.vaadin.flow.server.VaadinServletRequest;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side store for "Remember this device" tokens.
 *
 * Flow:
 *  1. On successful login with "Remember this device" checked:
 *       token = createToken(username)
 *       browser receives cookie COOKIE_NAME=token (via JS)
 *  2. On next visit (LoginView.beforeEnter):
 *       readCookieToken() → getUserForToken(token) → SecurityUtils.login(username, token)
 *       TFyreIdentityProvider accepts the token via isValidToken()
 *  3. On logout:
 *       removeTokensForUser(username) + JS clears the cookie
 *
 * Tokens are stored in-memory only — lost on restart, which is acceptable for a home-lab tool.
 */
@ApplicationScoped
public class RememberMeService {

    /** Cookie name set in the browser. */
    public static final String COOKIE_NAME = "bambu-rm";

    /** 30-day lifetime for both the server-side token and the browser cookie. */
    public static final int MAX_AGE_SECONDS = 30 * 24 * 3600;

    private record RememberToken(String username, Instant expiresAt) {
        boolean valid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** token → RememberToken (username + expiry) */
    private final Map<String, RememberToken> tokens = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    // -------------------------------------------------------------------------
    // Token lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates a 256-bit secure random token for {@code username} and stores it.
     *
     * @return the raw token string to embed in the cookie
     */
    public String createToken(final String username) {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new RememberToken(username, Instant.now().plus(MAX_AGE_SECONDS, ChronoUnit.SECONDS)));
        Log.debugf("RememberMeService: created token for %s (total tokens: %d)", username, tokens.size());
        return token;
    }

    /**
     * Returns the username bound to {@code token} if the token exists and has not expired.
     */
    public Optional<String> getUserForToken(final String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.get(token))
                .filter(RememberToken::valid)
                .map(RememberToken::username);
    }

    /**
     * Returns {@code true} when {@code token} is valid and belongs to {@code username}.
     * Used by {@link com.tfyre.servlet.TFyreIdentityProvider} as a secondary credential check.
     */
    public boolean isValidToken(final String username, final String token) {
        return getUserForToken(token)
                .map(u -> u.equalsIgnoreCase(username))
                .orElse(false);
    }

    /**
     * Removes all tokens belonging to {@code username}.
     * Called on logout so remembered sessions on all devices are invalidated.
     */
    public void removeTokensForUser(final String username) {
        final int before = tokens.size();
        tokens.entrySet().removeIf(e -> e.getValue().username().equalsIgnoreCase(username));
        Log.debugf("RememberMeService: removed tokens for %s (%d → %d)", username, before, tokens.size());
    }

    // -------------------------------------------------------------------------
    // Cookie helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the remember-me cookie from the current Vaadin/servlet request.
     * Returns the raw token value, or empty if no cookie is present.
     */
    public Optional<String> readCookieToken() {
        return Optional.ofNullable(VaadinServletRequest.getCurrent())
                .map(r -> r.getCookies())
                .filter(c -> c != null)
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(c -> COOKIE_NAME.equals(c.getName()))
                        .map(Cookie::getValue)
                        .findFirst());
    }

    /**
     * JavaScript expression that sets the remember-me cookie in the browser.
     * Pass the token as the first {@code $0} argument to {@code Page.executeJs()}.
     *
     * <pre>{@code
     * UI.getCurrent().getPage().executeJs(RememberMeService.SET_COOKIE_JS, token);
     * }</pre>
     */
    public static final String SET_COOKIE_JS =
            "document.cookie = '" + COOKIE_NAME + "=' + encodeURIComponent($0)"
            + " + '; path=/; max-age=" + MAX_AGE_SECONDS + "; SameSite=Strict'";

    /**
     * JavaScript expression that clears the remember-me cookie from the browser.
     *
     * <pre>{@code
     * UI.getCurrent().getPage().executeJs(RememberMeService.CLEAR_COOKIE_JS);
     * }</pre>
     */
    public static final String CLEAR_COOKIE_JS =
            "document.cookie = '" + COOKIE_NAME + "=; path=/; max-age=0'";

    // -------------------------------------------------------------------------
    // Maintenance
    // -------------------------------------------------------------------------

    @Scheduled(every = "1h")
    void cleanExpired() {
        final int before = tokens.size();
        tokens.entrySet().removeIf(e -> !e.getValue().valid());
        final int removed = before - tokens.size();
        if (removed > 0) {
            Log.debugf("RememberMeService: cleaned %d expired tokens", removed);
        }
    }
}
