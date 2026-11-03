package com.tfyre.bambu.mcp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure helpers behind the MCP tools: tray labels, log search and log grouping. Plain JUnit, no Quarkus. */
@DisplayName("MCP tool helpers")
class McpToolsTest {

    private static final String H = "d13d25c65bde /usr/lib/jvm/zulu21-ca-amd64/bin/java[1]";
    private static final List<String> LOG = List.of(
            "2026-11-02 18:30:12,001 " + H + " ERROR [com.tfy.bam.ssl.NoopTrustSocketFactory] (main) Using INSECURE factory",
            "2026-11-02 19:40:49,411 " + H + " ERROR [io.qua.mcp.Handler] (vert.x-eventloop-thread-1) Mcp-Session-Id header not found",
            "2026-11-02 19:41:00,000 " + H + " WARN  [com.tfy.bam.pri.AmsDryService] (executor-thread-3) H2D: dry refused, retry 3 of 5",
            "2026-11-02 19:42:00,000 " + H + " WARN  [com.tfy.bam.pri.AmsDryService] (executor-thread-4) H2D: dry refused, retry 4 of 5",
            "2026-11-02 19:43:00,000 " + H + " INFO  [com.tfy.bam.pri.BambuPrinterImpl] (MQTT Call: camel-paho-1) P1S-3: RUNNING -> FAILED",
            "2026-11-02 19:44:00,000 " + H + " ERROR [com.tfy.bam.pri.PrintQueueService] (executor-thread-9) P1S-3 upload failed",
            "java.io.IOException: FTP 550 P1S-3 no such file",
            "\tat com.tfyre.bambu.printer.Ftp.put(Ftp.java:42)");

    @Test
    void trayLabels() {
        assertEquals("A1", FarmTools.trayLabel(0));
        assertEquals("D4", FarmTools.trayLabel(15));
        assertEquals("Ext", FarmTools.trayLabel(254));
        assertEquals("HT1", FarmTools.trayLabel(128));  // mapping / printer numbering
        assertEquals("HT1", FarmTools.trayLabel(512));  // telemetry key amsId*4 + tray
        assertEquals("HT2", FarmTools.trayLabel(516));
        assertEquals("tray 300", FarmTools.trayLabel(300));
    }

    @Test
    void searchIsNewestFirstAndKeepsStackTraceLines() {
        final Map<String, Object> r = HistoryTools.search(LOG, Pattern.compile("P1S-3", Pattern.CASE_INSENSITIVE), null,
                LocalDateTime.MIN, 10);
        assertEquals(3, r.get("matches"));
        @SuppressWarnings("unchecked")
        final List<String> lines = (List<String>) r.get("lines");
        assertEquals("java.io.IOException: FTP 550 P1S-3 no such file", lines.get(0));
        assertEquals(true, lines.get(2).contains("RUNNING -> FAILED"));
    }

    @Test
    void searchLevelFilterAppliesToContinuationLines() {
        // the stack trace belongs to an ERROR, so it survives level=ERROR; the INFO line does not
        final Map<String, Object> r = HistoryTools.search(LOG, Pattern.compile("P1S-3"), "ERROR", LocalDateTime.MIN, 10);
        assertEquals(2, r.get("matches"));
    }

    @Test
    void searchTimeFilter() {
        final Map<String, Object> r = HistoryTools.search(LOG, Pattern.compile("."), null,
                LocalDateTime.of(2026, 11, 2, 19, 43, 30), 10);
        assertEquals(3, r.get("matches")); // the 19:44 error and its two trace lines
    }

    @Test
    void searchLimit() {
        final Map<String, Object> r = HistoryTools.search(LOG, Pattern.compile("."), null, LocalDateTime.MIN, 2);
        assertEquals(8, r.get("matches"));
        assertEquals(2, r.get("shown"));
    }

    @Test
    void labelWordsMapToTwoTruths() {
        assertEquals(java.util.Optional.of("problem"), AiLabels.truthOf("missed"));
        assertEquals(java.util.Optional.of("problem"), AiLabels.truthOf(" Failure "));
        assertEquals(java.util.Optional.of("ok"), AiLabels.truthOf("false_alarm"));
        assertEquals(java.util.Optional.of("ok"), AiLabels.truthOf("clear"));
        assertEquals(java.util.Optional.empty(), AiLabels.truthOf("maybe"));
    }

    @Test
    void positiveAnswerMeaning() {
        assertEquals(true, EvalTools.positiveMeansGood(com.tfyre.bambu.printer.AiPromptService.PromptType.BED_CLEAR));
        assertEquals(true, EvalTools.positiveMeansGood(com.tfyre.bambu.printer.AiPromptService.PromptType.FIRST_LAYER));
        assertEquals(false, EvalTools.positiveMeansGood(com.tfyre.bambu.printer.AiPromptService.PromptType.FAILURE));
        assertEquals(java.util.Optional.of(com.tfyre.bambu.printer.AiPromptService.PromptType.BED_CLEAR), EvalTools.typeOf("bed-clear"));
        assertEquals(java.util.Optional.empty(), EvalTools.typeOf("extrusion"));
    }

    @Test
    void logScanGroupsByShape() {
        final LogScan.Result r = LogScan.scan(LOG, LocalDateTime.MIN, 10, 5);
        assertEquals(3, r.errors());
        assertEquals(2, r.warnings());
        // "retry 3 of 5" and "retry 4 of 5" are one kind of warning
        assertEquals(2, r.groups().stream().filter(g -> g.level().equals("WARN")).mapToInt(LogScan.Group::count).sum());
        assertEquals(1, r.groups().stream().filter(g -> g.level().equals("WARN")).count());
    }
}
