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
 * Persists the Etsy OAuth token pair (access + refresh) to a small JSON file, following the same
 * load-on-start / save-on-mutation pattern as {@link PrintQueueService} / {@link PrintHistoryService}.
 */
@ApplicationScoped
public class EtsyTokenStore {

    public record Tokens(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("user_id") String userId) {

        @JsonCreator
        public Tokens {
        }

        public boolean isExpired() {
            // refresh a little early to avoid races
            return Instant.now().isAfter(expiresAt.minusSeconds(60));
        }
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;

    private volatile Tokens tokens;
    private boolean dirty;

    private Path getPath() {
        return Path.of(config.etsy().tokenFile());
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            tokens = mapper.readValue(path.toFile(), Tokens.class);
            Log.info("EtsyTokenStore: loaded saved Etsy tokens");
        } catch (IOException ex) {
            Log.errorf(ex, "EtsyTokenStore: cannot load %s: %s", path, ex.getMessage());
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
            Log.errorf(ex, "EtsyTokenStore: cannot save %s: %s", getPath(), ex.getMessage());
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

    public synchronized void clear() {
        this.tokens = null;
        dirty = true;
        save();
    }

    public boolean isConnected() {
        return tokens != null;
    }

}
