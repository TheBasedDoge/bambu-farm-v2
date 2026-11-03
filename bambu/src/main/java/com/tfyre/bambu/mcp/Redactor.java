package com.tfyre.bambu.mcp;

import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips secrets from anything an MCP tool returns or the audit log records.
 * <p>
 * Two layers: every secret value the app knows (printer access codes, user passwords, OAuth secrets, the webhook
 * URL, the MCP tokens, and any {@code data/.env} value whose key looks secret) is replaced literally wherever it
 * appears; then patterns catch secrets the app never saw as config (bearer tokens, passwords inside URLs, Discord
 * webhook URLs, {@code access_token=...} style pairs).
 */
@ApplicationScoped
public class Redactor {

    static final String MASK = "[redacted]";

    /** Values shorter than this are not replaced literally - "1234" would mangle every number in a log line. */
    static final int MIN_SECRET_LENGTH = 6;

    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(code|token|secret|password|passwd|webhook|api[-_.]?key|\\bkey)");

    /** Keys that match {@link #SECRET_KEY} but name a file, folder or schedule, e.g. {@code bambu.etsy.token-file}. */
    private static final Pattern NOT_SECRET_KEY = Pattern.compile("(?i)(file|path|dir|cron|interval|enabled|format|topic|url-base)$");

    private static final List<Pattern> PATTERNS = List.of(
            // Authorization: Bearer <token>
            Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            // scheme://user:password@host - keep the scheme, drop both user and password
            Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/\\s:@]*:[^/\\s@]+(?=@)"),
            // Discord / Slack webhook URLs carry their secret in the path
            Pattern.compile("(?i)()https://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/\\S+"),
            Pattern.compile("(?i)()https://hooks\\.slack\\.com/\\S+"),
            // key=value / "key": "value" pairs with a secret-looking key
            Pattern.compile("(?i)(\\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|shared[_-]?secret"
                    + "|access[_-]?code|api[_-]?key|password|passwd|secret|token)\"?\\s*[:=]\\s*\"?)[^\"\\s,&;}]{4,}"),
            // query-string secrets
            Pattern.compile("(?i)([?&](?:access_token|token|key|code|signature|sig)=)[^&\\s\"]+"));

    @Inject
    BambuConfig config;

    private volatile List<String> secrets = List.of();

    @PostConstruct
    void init() {
        final Set<String> found = new LinkedHashSet<>();
        config.printers().values().forEach(p -> found.add(p.accessCode()));
        config.users().values().forEach(u -> found.add(u.password()));
        add(found, config.notifications().webhookUrl());
        add(found, config.notifications().mqtt().password());
        add(found, config.cloud().token());
        add(found, config.etsy().clientId());
        add(found, config.etsy().sharedSecret());
        add(found, config.ebay().clientSecret());
        add(found, config.usps().clientSecret());
        add(found, config.mcp().readToken());
        add(found, config.mcp().controlToken());
        found.addAll(envSecrets(Path.of(config.maintenanceFile()).toAbsolutePath().getParent().resolve(".env")));
        secrets = ordered(found);
        Log.infof("Redactor: %d secret values loaded", secrets.size());
    }

    /** {@code text} with every known secret and secret-looking token replaced by {@value #MASK}. */
    public String apply(final String text) {
        return scrub(text, secrets);
    }

    static String scrub(final String text, final List<String> secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (final String s : secrets) {
            out = out.replace(s, MASK);
        }
        for (final Pattern p : PATTERNS) {
            final Matcher m = p.matcher(out);
            out = m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + MASK));
        }
        return out;
    }

    /** Longest first, so a secret that contains another is replaced whole. */
    static List<String> ordered(final Collection<String> values) {
        final List<String> list = new ArrayList<>();
        for (final String v : values) {
            if (v != null && v.trim().length() >= MIN_SECRET_LENGTH) {
                list.add(v.trim());
            }
        }
        list.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(list);
    }

    /** Values from {@code .env} lines whose key looks secret. Comment lines and blank values are skipped. */
    static List<String> envSecrets(final List<String> lines) {
        final List<String> out = new ArrayList<>();
        for (final String raw : lines) {
            final String line = raw.trim();
            final int eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (SECRET_KEY.matcher(key).find() && !NOT_SECRET_KEY.matcher(key).find() && !value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> envSecrets(final Path env) {
        if (!Files.isRegularFile(env)) {
            return List.of();
        }
        try {
            return envSecrets(Files.readAllLines(env, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("Redactor: could not read %s: %s", env, ex.getMessage());
            return List.of();
        }
    }

    private static void add(final Set<String> set, final Optional<String> value) {
        value.ifPresent(set::add);
    }
}
