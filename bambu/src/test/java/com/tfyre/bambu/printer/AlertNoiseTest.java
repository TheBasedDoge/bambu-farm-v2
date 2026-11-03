package com.tfyre.bambu.printer;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03: a P1S spool that fed for a minute after each AMS Retry got "Retry 1/3" eight times and sent 16 alerts
 * in 8 minutes; a confirmed P1S-2 spaghetti alert went out in the middle of them, read as "not pausing yet", and was
 * missed. These pin the three fixes.
 */
@DisplayName("Alert noise and urgency")
class AlertNoiseTest {

    private static final int FEED_FAILED = 0x07008005;
    private static final Instant T = Instant.parse("2026-11-03T14:37:27Z");

    @Test
    void sameErrorSoonAfterARetryIsTheSameProblem() {
        assertTrue(AmsRetryService.isRecurrence(FEED_FAILED, T, FEED_FAILED, T.plusSeconds(60)));
        assertTrue(AmsRetryService.isRecurrence(FEED_FAILED, T, FEED_FAILED, T.plus(Duration.ofMinutes(9))));
    }

    @Test
    void laterOrDifferentErrorsStartAfresh() {
        assertFalse(AmsRetryService.isRecurrence(FEED_FAILED, T, FEED_FAILED, T.plus(AmsRetryService.RECUR_WINDOW)));
        assertFalse(AmsRetryService.isRecurrence(FEED_FAILED, T, 0x07008010, T.plusSeconds(60)));
        assertFalse(AmsRetryService.isRecurrence(0, null, FEED_FAILED, T));
    }

    @Test
    void urgentDiscordAlertsPingAndStandOut() {
        assertEquals("@here 🚨 **P1S-2** spaghetti", NotificationService.discordContent("P1S-2", "spaghetti", true));
        assertEquals("**P1S** finished", NotificationService.discordContent("P1S", "finished", false));
        assertTrue(NotificationService.URGENT_EVENTS.contains("failure_detected"));
        assertTrue(NotificationService.URGENT_EVENTS.contains("fail"));
        assertFalse(NotificationService.URGENT_EVENTS.contains("ams_retry"));
    }

    @Test
    void onlyTheH2dWaitsForASecondConfirmedCheck() {
        assertTrue(PrintAiService.needsSecondCheckBeforePausing(BambuConst.PrinterModel.H2D));
        assertFalse(PrintAiService.needsSecondCheckBeforePausing(BambuConst.PrinterModel.P1S));
        assertFalse(PrintAiService.needsSecondCheckBeforePausing(BambuConst.PrinterModel.P1P));
    }
}
