package com.tfyre.bambu.mcp;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Bearer-token gate in front of the MCP endpoint ({@code /mcp}, docs/MCP-PLAN.md).
 * <p>
 * Runs as a Vert.x route filter, so it sees the request before the MCP extension does and never touches the body.
 * Every other path passes straight through. With no usable token configured the endpoint answers 503 and does
 * nothing: MCP is off until someone puts a token in {@code data/.env}.
 * <p>
 * The caller's access level is stored on the routing context for {@link McpAudit} and, from phase 2, for the
 * control tools.
 */
@ApplicationScoped
public class McpAccessFilter {

    /** Routing-context key holding the caller's {@link Access}. */
    static final String ACCESS_KEY = "bambu.mcp.access";

    /** Shorter tokens are ignored: a guessable token on an endpoint that can stop prints is worse than none. */
    static final int MIN_TOKEN_LENGTH = 24;

    /** READ and CONTROL work on /mcp; LITE (the lite token) only on /mcp-lite, where CONTROL also works. */
    public enum Access {
        READ, CONTROL, LITE
    }

    @Inject
    BambuConfig config;

    void onStart(@Observes final StartupEvent ev) {
        Log.infof("MCP: read token %s, control token %s, lite token %s", describe(config.mcp().readToken()),
                describe(config.mcp().controlToken()), describe(config.mcp().liteToken()));
    }

    void register(@Observes final Filters filters) {
        filters.register(this::filter, 100);
    }

    void filter(final RoutingContext rc) {
        final String path = rc.normalizedPath();
        if (!isMcpPath(path)) {
            rc.next();
            return;
        }
        // mcp-remote looks for OAuth metadata on every start; there is none, so answer quietly instead of
        // logging a rejected request each time.
        if (path.startsWith("/mcp/.well-known/") || path.startsWith("/mcp-lite/.well-known/")) {
            rc.response().setStatusCode(404).end();
            return;
        }
        final Optional<String> read = usable(config.mcp().readToken());
        final Optional<String> control = usable(config.mcp().controlToken());
        final Optional<String> lite = usable(config.mcp().liteToken());
        final boolean litePath = isLitePath(path);
        if (litePath ? lite.isEmpty() && control.isEmpty() : read.isEmpty() && control.isEmpty()) {
            rc.response().setStatusCode(503).putHeader("Content-Type", "text/plain")
                    .end("MCP is off: set bambu.mcp.read-token in data/.env");
            return;
        }
        final Optional<Access> access = litePath
                ? resolveLite(rc.request().getHeader("Authorization"), lite, control)
                : resolve(rc.request().getHeader("Authorization"), read, control);
        if (access.isEmpty()) {
            final String from = Optional.ofNullable(rc.request().getHeader("X-Forwarded-For"))
                    .orElseGet(() -> String.valueOf(rc.request().remoteAddress()));
            Log.warnf("MCP: rejected %s %s from %s (missing or wrong token)", rc.request().method(), path, from);
            rc.response().setStatusCode(401).putHeader("WWW-Authenticate", "Bearer").end();
            return;
        }
        // A GET without a session asks to open a server-sent-events stream before initialize. Clients probe for it
        // and fall back; the extension would answer the same 405 but log an ERROR for every probe.
        if (HttpMethod.GET.equals(rc.request().method())
                && (path.equals("/mcp") || path.equals("/mcp/") || path.equals("/mcp-lite") || path.equals("/mcp-lite/"))
                && rc.request().getHeader("Mcp-Session-Id") == null) {
            rc.response().setStatusCode(405).putHeader("Allow", "POST, DELETE").end();
            return;
        }
        rc.put(ACCESS_KEY, access.get());
        rc.next();
    }

    static boolean isMcpPath(final String path) {
        return path != null && (path.equals("/mcp") || path.startsWith("/mcp/") || isLitePath(path));
    }

    static boolean isLitePath(final String path) {
        return path != null && (path.equals("/mcp-lite") || path.startsWith("/mcp-lite/"));
    }

    /** On /mcp-lite: the lite token, or the control token (so Claude can test the lite tools). */
    static Optional<Access> resolveLite(final String header, final Optional<String> lite, final Optional<String> control) {
        final Optional<Access> a = resolve(header, lite, control);
        return a.map(x -> x == Access.READ ? Access.LITE : x);
    }

    /** The access a presented {@code Authorization} header grants; the control token also grants read. */
    static Optional<Access> resolve(final String header, final Optional<String> read, final Optional<String> control) {
        if (header == null || header.length() <= 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        final String presented = header.substring(7).trim();
        if (control.isPresent() && sameToken(presented, control.get())) {
            return Optional.of(Access.CONTROL);
        }
        if (read.isPresent() && sameToken(presented, read.get())) {
            return Optional.of(Access.READ);
        }
        return Optional.empty();
    }

    static Optional<String> usable(final Optional<String> token) {
        return token.map(String::trim).filter(t -> t.length() >= MIN_TOKEN_LENGTH);
    }

    /** Constant-time for equal lengths, so the comparison doesn't leak how much of a guess was right. */
    private static boolean sameToken(final String a, final String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String describe(final Optional<String> token) {
        if (token.map(String::isBlank).orElse(true)) {
            return "not set";
        }
        return usable(token).isPresent() ? "set" : "IGNORED (shorter than %d characters)".formatted(MIN_TOKEN_LENGTH);
    }
}
