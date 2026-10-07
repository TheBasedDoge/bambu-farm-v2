package com.tfyre.bambu.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * How long a rate-limited (HTTP 429) webhook post waits before it is sent again. The first case is the body
 * Discord actually returned on 2026-10-04, when three photo alerts fired in the same second and two were lost.
 */
class NotificationRetryAfterTest {

    @Test
    void discordBodyInFractionalSeconds() {
        final String body = "{\"message\": \"You are being rate limited.\", \"retry_after\": 0.328, \"global\": false}";
        assertEquals(428L, NotificationService.retryAfterMillis(body, null));
    }

    @Test
    void bodyWinsOverHeader() {
        assertEquals(2100L, NotificationService.retryAfterMillis("{\"retry_after\":2}", "7"));
    }

    @Test
    void headerInSecondsWhenTheBodyHasNothing() {
        assertEquals(3100L, NotificationService.retryAfterMillis("Too Many Requests", "3"));
        assertEquals(3100L, NotificationService.retryAfterMillis(null, " 3 "));
    }

    @Test
    void oneSecondWhenNobodySays() {
        assertEquals(1100L, NotificationService.retryAfterMillis(null, null));
        assertEquals(1100L, NotificationService.retryAfterMillis("", "Wed, 21 Oct 2026 07:28:00 GMT"));
    }

    @Test
    void clampedAtBothEnds() {
        assertEquals(250L, NotificationService.retryAfterMillis("{\"retry_after\": 0.001}", null));
        assertEquals(10_000L, NotificationService.retryAfterMillis("{\"retry_after\": 3600}", null));
    }
}
