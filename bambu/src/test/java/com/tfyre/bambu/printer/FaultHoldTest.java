package com.tfyre.bambu.printer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12: the H2D's bed leveling failed, was auto-retried on the same dirty sensor and failed again; the same
 * morning four finished P1s sat for hours with order work waiting. These pin which failures hold a printer instead
 * of retrying, and when the bed-waiting escalation goes out.
 */
@DisplayName("Fault hold and bed-waiting escalation")
class FaultHoldTest {

    @Test
    void hardwareErrorsAreNotRetried() {
        assertTrue(HmsCodes.isHardwareError(0x03004002)); // Auto Bed Leveling failed - the H2D on 11-12
        assertTrue(HmsCodes.isHardwareError(0x03004000)); // Z homing failed
        assertTrue(HmsCodes.isHardwareError(0x03004005)); // hotend fan
        assertTrue(HmsCodes.isHardwareError(0x03004020)); // nozzle presence detection
    }

    @Test
    void transientAndNonHardwareErrorsKeepTheirRetry() {
        assertFalse(HmsCodes.isHardwareError(0x0300400C)); // cancelled
        assertFalse(HmsCodes.isHardwareError(0x0300404B)); // door open
        assertFalse(HmsCodes.isHardwareError(0x03008003)); // spaghetti pause - the AI's business, a retry may work
        assertFalse(HmsCodes.isHardwareError(0x07008011)); // AMS ran out
        assertFalse(HmsCodes.isHardwareError(0x05004003)); // unparseable file
        assertFalse(HmsCodes.isHardwareError(0));
    }

    @Test
    void onlyFatalToolheadHmsCounts() {
        final BambuConst.PrinterModel h2d = BambuConst.PrinterModel.H2D;
        assertTrue(HmsCodes.isHardwareHms(HmsCodes.hms(h2d, 0x03002600L, 0x00010008L)));  // eddy sensor, fatal
        assertFalse(HmsCodes.isHardwareHms(HmsCodes.hms(h2d, 0x03002600L, 0x00020008L))); // serious, not fatal
        assertFalse(HmsCodes.isHardwareHms(HmsCodes.hms(h2d, 0x07002000L, 0x00010001L))); // AMS
    }

    @Test
    void escalationWaitsThirtyMinutesThenRepeatsEveryTwoHours() {
        final Instant done = Instant.parse("2026-11-12T12:00:00Z");
        final Optional<Instant> none = Optional.empty();
        assertFalse(BedWaitingService.due(done, done.plus(Duration.ofMinutes(29)), none, none));
        assertTrue(BedWaitingService.due(done, done.plus(Duration.ofMinutes(30)), none, none));
        final Instant first = done.plus(Duration.ofMinutes(30));
        assertFalse(BedWaitingService.due(done, first.plus(Duration.ofMinutes(119)), Optional.of(done), Optional.of(first)));
        assertTrue(BedWaitingService.due(done, first.plus(Duration.ofHours(2)), Optional.of(done), Optional.of(first)));
        // a new finish is news straight away
        final Instant next = done.plus(Duration.ofHours(1));
        assertTrue(BedWaitingService.due(next, next.plus(Duration.ofMinutes(31)), Optional.of(done), Optional.of(first)));
    }

    @Test
    void quietWindowWrapsMidnight() {
        final LocalTime from = LocalTime.of(23, 0);
        final LocalTime until = LocalTime.of(7, 0);
        assertTrue(BedWaitingService.quiet(LocalTime.of(23, 0), from, until));
        assertTrue(BedWaitingService.quiet(LocalTime.of(3, 30), from, until));
        assertFalse(BedWaitingService.quiet(LocalTime.of(7, 0), from, until));
        assertFalse(BedWaitingService.quiet(LocalTime.of(22, 59), from, until));
    }

    @Test
    void quietWindowSameDayAndOff() {
        assertTrue(BedWaitingService.quiet(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)));
        assertFalse(BedWaitingService.quiet(LocalTime.of(14, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)));
        assertFalse(BedWaitingService.quiet(LocalTime.of(3, 0), null, null));
        assertFalse(BedWaitingService.quiet(LocalTime.of(3, 0), LocalTime.of(7, 0), LocalTime.of(7, 0)));
    }
}
