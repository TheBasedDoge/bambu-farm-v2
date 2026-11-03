package com.tfyre.bambu.mcp;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the MCP endpoint is allowed to say. Everything an agent sees passes through {@link Redactor}, so a miss here
 * is a leak. Plain JUnit against the package-private statics - no Quarkus boot.
 */
@DisplayName("MCP redaction and access")
class RedactorTest {

    private static final List<String> SECRETS = Redactor.ordered(List.of("12345678", "s3cretSharedValue", "ab12"));

    @Test
    void knownSecretsAreReplacedWherever_theyAppear() {
        final String out = Redactor.scrub("P1S-1 code 12345678; etsy x-api-key=keystring:s3cretSharedValue", SECRETS);
        assertFalse(out.contains("12345678"));
        assertFalse(out.contains("s3cretSharedValue"));
    }

    @Test
    void shortValuesAreNotReplacedLiterally() {
        // "ab12" is below MIN_SECRET_LENGTH: replacing it would mangle unrelated text
        assertEquals("tray ab12 loaded", Redactor.scrub("tray ab12 loaded", SECRETS));
    }

    @Test
    void longerSecretWinsOverOneItContains() {
        final List<String> s = Redactor.ordered(List.of("abcdef", "abcdefghij"));
        assertEquals("x [redacted] y", Redactor.scrub("x abcdefghij y", s));
    }

    @Test
    void bearerTokens() {
        assertEquals("Authorization: Bearer [redacted]",
                Redactor.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", List.of()));
    }

    @Test
    void passwordsInsideUrls() {
        // the shape the H2D RTSPS URL has in mediamtx.env / printers.list
        final String out = Redactor.scrub("source rtsps://bblp:98765432@192.168.0.50:322/streaming/live/1", List.of());
        assertEquals("source rtsps://[redacted]@192.168.0.50:322/streaming/live/1", out);
    }

    @Test
    void urlsWithoutCredentialsAreLeftAlone() {
        final String in = "pulling http://192.168.0.241:11434/api/generate and https://example.com:8081/mcp";
        assertEquals(in, Redactor.scrub(in, List.of()));
    }

    @Test
    void discordWebhookUrls() {
        final String out = Redactor.scrub("posting to https://discord.com/api/webhooks/123456/AbC-dEf_123 failed", List.of());
        assertEquals("posting to [redacted] failed", out);
    }

    @Test
    void secretLookingKeyValuePairs() {
        assertEquals("{\"access_token\":\"[redacted]\",\"expires_in\":3600}",
                Redactor.scrub("{\"access_token\":\"v^1.1#i^1#abcdefgh\",\"expires_in\":3600}", List.of()));
        assertEquals("client_secret=[redacted]&grant_type=refresh_token",
                Redactor.scrub("client_secret=PRD-abcdef123&grant_type=refresh_token", List.of()));
        assertEquals("password: [redacted]", Redactor.scrub("password: hunter22", List.of()));
    }

    @Test
    void queryStringSecrets() {
        assertEquals("GET /callback?code=[redacted]&state=xyz",
                Redactor.scrub("GET /callback?code=AbCdEf123456&state=xyz", List.of()));
    }

    @Test
    void ordinaryLogLinesSurviveUntouched() {
        final String in = "2026-11-02 16:56:10,354 d13d25c65bde java[1] INFO  [com.tfy.bam.pri.BambuPrinterImpl] "
                + "(MQTT Call: camel-paho-4ce6868773ef9de3) P1S-3: gcode_state RUNNING -> FINISH, token-file=bambu-etsy-tokens.json";
        assertEquals(in, Redactor.scrub(in, SECRETS));
    }

    @Test
    void envKeysThatLookSecretAreCollected() {
        final List<String> found = Redactor.envSecrets(List.of(
                "# comment with token=notme",
                "bambu.printers.P1S-1.access-code=11223344",
                "bambu.etsy.shared-secret=\"quotedSecret99\"",
                "bambu.etsy.token-file=bambu-etsy-tokens.json",
                "bambu.mcp.read-token=readtokenreadtokenreadtoken",
                "bambu.notifications.webhook-url=https://discord.com/api/webhooks/1/x",
                "bambu.ollama.model=gemma3:12b",
                "bambu.dark-mode=true",
                "broken line"));
        assertEquals(List.of("11223344", "quotedSecret99", "readtokenreadtokenreadtoken",
                "https://discord.com/api/webhooks/1/x"), found);
    }

    @Test
    void accessResolvesControlBeforeRead() {
        final Optional<String> read = Optional.of("r".repeat(30));
        final Optional<String> control = Optional.of("c".repeat(30));
        assertEquals(Optional.of(McpAccessFilter.Access.READ), McpAccessFilter.resolve("Bearer " + "r".repeat(30), read, control));
        assertEquals(Optional.of(McpAccessFilter.Access.CONTROL), McpAccessFilter.resolve("bearer " + "c".repeat(30), read, control));
        assertEquals(Optional.empty(), McpAccessFilter.resolve("Bearer " + "r".repeat(29), read, control));
        assertEquals(Optional.empty(), McpAccessFilter.resolve(null, read, control));
        assertEquals(Optional.empty(), McpAccessFilter.resolve("Basic abc", read, control));
        assertEquals(Optional.empty(), McpAccessFilter.resolve("Bearer " + "c".repeat(30), read, Optional.empty()));
    }

    @Test
    void shortTokensAreIgnored() {
        assertTrue(McpAccessFilter.usable(Optional.of("short")).isEmpty());
        assertTrue(McpAccessFilter.usable(Optional.of(" " + "x".repeat(24) + " ")).isPresent());
    }

    @Test
    void onlyTheMcpPathIsGated() {
        assertTrue(McpAccessFilter.isMcpPath("/mcp"));
        assertTrue(McpAccessFilter.isMcpPath("/mcp/sse"));
        assertFalse(McpAccessFilter.isMcpPath("/mcpx"));
        assertFalse(McpAccessFilter.isMcpPath("/"));
        assertFalse(McpAccessFilter.isMcpPath(null));
    }
}
