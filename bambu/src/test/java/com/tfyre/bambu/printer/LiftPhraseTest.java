package com.tfyre.bambu.printer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-10: a P1S tray base lifted at a corner and the failure check never flagged it. The prompt now asks for
 * lifting; this pins the safety net behind it - a reply that describes lifting but files it under "Problems: none"
 * is overridden, while the model's usual "no ... lifting" list is not.
 */
@DisplayName("Failure check: unreported lifting")
class LiftPhraseTest {

    @Test
    void describedLiftingIsCaught() {
        assertTrue(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 10\nReason: The right front corner of the base appears lifted from the plate.").isPresent());
        assertTrue(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 5\nReason: The edge is curling up slightly but the print continues.").isPresent());
        assertTrue(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 5\nReason: Some warping at the base, otherwise normal.").isPresent());
    }

    @Test
    void negatedListsAreNotCaught() {
        assertFalse(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 0\nReason: The image shows a 3D print in progress with no clear signs of spaghetti, tangles, blobs, detachment, or lifting.").isPresent());
        assertFalse(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 0\nReason: No warping or detachment; the base sits flat on the plate.").isPresent());
        assertFalse(OllamaService.findUnreportedFailure(
                "NO\nProblems: none\nConfidence: 0\nReason: The print appears to be in progress with visible layers and no clear signs of failure like spaghetti, tangles, or detachment.").isPresent());
    }
}
