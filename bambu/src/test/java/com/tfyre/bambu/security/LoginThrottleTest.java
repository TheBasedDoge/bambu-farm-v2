package com.tfyre.bambu.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-18 security audit: nothing limited password guessing. */
@DisplayName("Login slowdown")
class LoginThrottleTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-18T20:00:00Z"));

    private LoginThrottle throttle() {
        final LoginThrottle t = new LoginThrottle();
        t.clock = now::get;
        return t;
    }

    private void advance(final Duration d) {
        now.set(now.get().plus(d));
    }

    @Test
    void fourWrongPasswordsAreFreeTheFifthLocksForAMinute() {
        final LoginThrottle t = throttle();
        for (int i = 0; i < 4; i++) {
            assertEquals(Optional.empty(), t.failed("Vishal"));
        }
        assertTrue(t.lockedFor("vishal").isEmpty());
        assertEquals(Optional.of(Duration.ofMinutes(1)), t.failed("VISHAL")); // case doesn't matter
        assertTrue(t.lockedFor("Vishal").isPresent());
        advance(Duration.ofSeconds(61));
        assertTrue(t.lockedFor("Vishal").isEmpty());
    }

    @Test
    void eachFurtherFailureDoublesUpToHalfAnHour() {
        assertEquals(Duration.ofMinutes(1), LoginThrottle.lockFor(0));
        assertEquals(Duration.ofMinutes(2), LoginThrottle.lockFor(1));
        assertEquals(Duration.ofMinutes(16), LoginThrottle.lockFor(4));
        assertEquals(Duration.ofMinutes(30), LoginThrottle.lockFor(5));
        assertEquals(Duration.ofMinutes(30), LoginThrottle.lockFor(40));
    }

    @Test
    void oldFailuresAgeOutAndSuccessClears() {
        final LoginThrottle t = throttle();
        for (int i = 0; i < 4; i++) {
            t.failed("vishal");
        }
        advance(Duration.ofMinutes(16));
        assertEquals(Optional.empty(), t.failed("vishal")); // the first four fell out of the 15-minute window
        for (int i = 0; i < 3; i++) {
            t.failed("vishal");
        }
        t.succeeded("vishal");
        assertEquals(Optional.empty(), t.failed("vishal"));
    }

    @Test
    void otherUsernamesAreUnaffected() {
        final LoginThrottle t = throttle();
        for (int i = 0; i < 5; i++) {
            t.failed("admin");
        }
        assertTrue(t.lockedFor("admin").isPresent());
        assertFalse(t.lockedFor("vishal").isPresent());
    }

    @Test
    void typedUsernamesCannotPingOrBreakLogLines() {
        assertEquals("?everyone", LoginThrottle.shown("@everyone"));
        assertEquals("a??b", LoginThrottle.shown("a\r\nb"));
        assertEquals(43, LoginThrottle.shown("x".repeat(100)).length());
    }
}
