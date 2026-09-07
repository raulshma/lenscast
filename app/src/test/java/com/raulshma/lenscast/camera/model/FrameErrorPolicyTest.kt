package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameErrorPolicyTest {

    // ── threshold ──

    @Test
    fun `the tenth consecutive error recovers`() {
        assertTrue(
            FrameErrorPolicy.shouldRecover(
                consecutiveErrors = FrameErrorPolicy.MAX_CONSECUTIVE_FRAME_ERRORS,
                nowMs = 10_000,
                lastErrorMs = 10_000,
            ),
        )
    }

    @Test
    fun `the ninth consecutive error does not recover`() {
        assertFalse(
            FrameErrorPolicy.shouldRecover(
                consecutiveErrors = FrameErrorPolicy.MAX_CONSECUTIVE_FRAME_ERRORS - 1,
                nowMs = 10_000,
                lastErrorMs = 10_000,
            ),
        )
    }

    @Test
    fun `the threshold stays at ten errors`() {
        assertEquals(10, FrameErrorPolicy.MAX_CONSECUTIVE_FRAME_ERRORS)
        assertEquals(5000L, FrameErrorPolicy.ERROR_RESET_WINDOW_MS)
    }

    // ── reset window boundary ──

    @Test
    fun `errors exactly one window apart are still consecutive`() {
        // Exactly 5000ms: not expired (the original check was strict >).
        assertFalse(FrameErrorPolicy.streakExpired(nowMs = 5000, lastErrorMs = 0))
    }

    @Test
    fun `errors just past the window expire the streak`() {
        assertTrue(FrameErrorPolicy.streakExpired(nowMs = 5001, lastErrorMs = 0))
    }

    @Test
    fun `an expired streak does not recover even at the threshold`() {
        // Ten errors recorded, but the newest one arrived after the window —
        // the caller resets the counter instead of recovering.
        assertFalse(
            FrameErrorPolicy.shouldRecover(
                consecutiveErrors = FrameErrorPolicy.MAX_CONSECUTIVE_FRAME_ERRORS,
                nowMs = 10_000,
                lastErrorMs = 4_999,
            ),
        )
    }

    @Test
    fun `a long streak inside the window recovers`() {
        assertTrue(
            FrameErrorPolicy.shouldRecover(
                consecutiveErrors = FrameErrorPolicy.MAX_CONSECUTIVE_FRAME_ERRORS,
                nowMs = 4_999 + FrameErrorPolicy.ERROR_RESET_WINDOW_MS,
                lastErrorMs = 4_999,
            ),
        )
    }
}
