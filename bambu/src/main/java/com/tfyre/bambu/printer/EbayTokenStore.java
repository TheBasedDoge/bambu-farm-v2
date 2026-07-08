package com.tfyre.bambu.printer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.Optional;

/**
 * Persists the eBay OAuth token pair (access + refresh) to a small JSON file. Same load/save pattern as
 * {@link EtsyTokenStore}. Unlike Etsy, eBay's refresh grant does not return a new refresh token, so
 * {@code refreshToken}/{@code refreshExpiresAt} only ever get set once, at the initial authorization.
 */
@ApplicationScoped
public class EbayTokenStore {

    public record Tokens(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_at") Instant refreshExpiresAt) {

        @JsonCreator
        public Tokens {
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt.minusSeconds(60));
        }

        public boolean isRefreshExpired() {
            return refreshExpiresAt != null && Instant.now().isAfter(refreshExpiresAt.minusSeconds(60));
        }
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private volatile Tokens tokens;
    private boolean dirty;

    private Path getPath() {
        return Path.of(config.ebay().tokenFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            tokens = mapper.readValue(path.toFile(), Tokens.class);
            Log.info("EbayTokenStore: loaded saved eBay tokens");
        } catch (IOException ex) {
            Log.errorf(ex, "EbayTokenStore: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), tokens);
            dirty = false;
        } catch (IOException ex) {
            Log.errorf(ex, "EbayTokenStore: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    @Shutdown
    void onShutdown() {
        save();
    }

    public synchronized Optional<Tokens> get() {
        return Optional.ofNullable(tokens);
    }

    public synchronized void set(final Tokens newTokens) {
        this.tokens = newTokens;
        dirty = true;
        save();
    }

    /** Updates just the access token/expiry after a refresh, keeping the existing refresh token. */
    public synchronized void updateAccessToken(final String accessToken, final Instant expiresAt) {
        if (tokens == null) {
            return;
        }
        this.tokens = new Tokens(accessToken, expiresAt, tokens.refreshToken(), tokens.refreshExpiresAt());
        dirty = true;
        save();
    }

    public synchronized void clear() {
        this.tokens = null;
        dirty = true;
        save();
    }

    public boolean isConnected() {
        return tokens != null;
    }

}
