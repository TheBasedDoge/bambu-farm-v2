package com.tfyre.bambu.printer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which print_error codes the AMS auto-retry is allowed to act on. The AMS-unit byte varies (00-03 for the
 * four AMS slots, FF for the external spool) and must not matter; anything else must not match.
 */
class AmsRetryCodeTest {

    @Test
    void assistMotorOverloadedOnAnyAmsUnit() {
        assertTrue(AmsRetryService.isRetryable(0x07008010));
        assertTrue(AmsRetryService.isRetryable(0x07018010));
        assertTrue(AmsRetryService.isRetryable(0x07038010));
        assertTrue(AmsRetryService.isRetryable(0x07FF8010));
    }

    @Test
    void failedToFeedOnAnyAmsUnit() {
        assertTrue(AmsRetryService.isRetryable(0x07008005));
        assertTrue(AmsRetryService.isRetryable(0x07028005));
        assertTrue(AmsRetryService.isRetryable(0x07FF8005));
    }

    @Test
    void failedToFeedIntoToolheadOnAnyAmsUnit() {
        assertTrue(AmsRetryService.isRetryable(0x07008006)); // as logged by the H2D: "Print error [7008006]"
        assertTrue(AmsRetryService.isRetryable(0x07018006));
        assertTrue(AmsRetryService.isRetryable(0x07FF8006));
    }

    @Test
    void everythingElseIsLeftAlone() {
        assertFalse(AmsRetryService.isRetryable(0));
        assertFalse(AmsRetryService.isRetryable(0x07008011)); // neighbouring AMS code
        assertFalse(AmsRetryService.isRetryable(0x07008007)); // the other neighbour
        assertFalse(AmsRetryService.isRetryable(0x0300400A)); // a non-AMS module
        assertFalse(AmsRetryService.isRetryable(0x05008010)); // same error number, different module
    }
}
