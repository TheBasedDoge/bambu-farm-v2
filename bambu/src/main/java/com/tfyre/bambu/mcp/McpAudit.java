package com.tfyre.bambu.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkiverse.mcp.server.Content;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.logging.Log;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs a tool body and appends one line per call to {@code data/mcp-audit.jsonl}: when, caller access level, tool,
 * arguments, outcome and duration. Text the tool returns goes through {@link Redactor} on the way out, so a tool
 * cannot leak a secret by accident.
 */
@ApplicationScoped
public class McpAudit {

    /** Above this the file is rolled to {@code .1} (one generation kept). */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    Redactor redactor;
    @Inject
    CurrentVertxRequest currentRequest;

    /** A successful response carrying {@code value} as compact JSON. */
    public ToolResponse json(final Object value) {
        try {
            return ToolResponse.success(mapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("could not serialise the result: " + ex.getMessage(), ex);
        }
    }

    /** Tool arguments for the audit line, in order; unlike {@code Map.of} it accepts nulls (an omitted argument). */
    public static Map<String, Object> args(final Object... keyValues) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    public ToolResponse run(final String tool, final Map<String, Object> args, final Supplier<ToolResponse> body) {
        final long start = System.nanoTime();
        final String access = caller().map(Enum::name).orElse("UNKNOWN");
        ToolResponse response;
        String outcome;
        try {
            response = scrub(body.get());
            outcome = response.isError() ? "error" : "ok";
        } catch (RuntimeException ex) {
            Log.warnf(ex, "MCP: tool %s failed", tool);
            response = ToolResponse.error("%s failed: %s".formatted(tool, redactor.apply(String.valueOf(ex.getMessage()))));
            outcome = "exception: " + redactor.apply(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        write(tool, access, args, outcome, (System.nanoTime() - start) / 1_000_000);
        return response;
    }

    /** The access level {@link McpAccessFilter} granted this request, when the request is still reachable. */
    public Optional<McpAccessFilter.Access> caller() {
        try {
            final RoutingContext rc = currentRequest.getCurrent();
            return rc == null ? Optional.empty()
                    : Optional.ofNullable(rc.<McpAccessFilter.Access>get(McpAccessFilter.ACCESS_KEY));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private ToolResponse scrub(final ToolResponse response) {
        if (response.content() == null) {
            return response;
        }
        final List<Content> out = new ArrayList<>();
        for (final Content c : response.content()) {
            out.add(c instanceof TextContent t ? new TextContent(redactor.apply(t.text())) : c);
        }
        return new ToolResponse(response.isError(), out, response.structuredContent(), response._meta());
    }

    private synchronized void write(final String tool, final String access, final Map<String, Object> args,
            final String outcome, final long ms) {
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
        line.put("access", access);
        line.put("tool", tool);
        final Map<String, Object> safeArgs = new LinkedHashMap<>();
        args.forEach((k, v) -> safeArgs.put(k, v == null ? null : redactor.apply(String.valueOf(v))));
        line.put("args", safeArgs);
        line.put("outcome", outcome);
        line.put("ms", ms);
        final Path file = file();
        try {
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            Log.warnf("MCP: could not write the audit log %s: %s", file, ex.getMessage());
        }
    }

    private Path file() {
        return Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve("mcp-audit.jsonl");
    }
}
