package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arm / disarm / user-stop-suppression matrix and the chained-segment config
 * of the continuous NVR-style loop — the pure half of the
 * ContinuousRecordingController's decisions.
 */
class ContinuousRecordingPolicyTest {

    // ── shouldArm ──

    @Test
    fun `arms only when enabled and idle and not already armed`() {
        assertTrue(
            ContinuousRecordingPolicy.shouldArm(
                enabled = true,
                recordingStateIsIdle = true,
                alreadyArmed = false,
            )
        )
    }

    @Test
    fun `does not arm when disabled`() {
        assertFalse(
            ContinuousRecordingPolicy.shouldArm(
                enabled = false,
                recordingStateIsIdle = true,
                alreadyArmed = false,
            )
        )
    }

    @Test
    fun `does not arm while a recording is live`() {
        assertFalse(
            ContinuousRecordingPolicy.shouldArm(
                enabled = true,
                recordingStateIsIdle = false,
                alreadyArmed = false,
            )
        )
    }

    @Test
    fun `does not re-arm while already armed`() {
        assertFalse(
            ContinuousRecordingPolicy.shouldArm(
                enabled = true,
                recordingStateIsIdle = true,
                alreadyArmed = true,
            )
        )
    }

    // ── segmentConfig ──

    @Test
    fun `segment config chains bounded segments with audio per preference`() {
        val config = ContinuousRecordingPolicy.segmentConfig(segmentMinutes = 15, audioEnabled = true)
        assertEquals(15L * 60L, config.durationSeconds)
        assertTrue(config.repeatIntervalSeconds > 0) // the repeat IS the chain
        assertTrue(config.includeAudio)
    }

    @Test
    fun `segment config can drop audio`() {
        val config = ContinuousRecordingPolicy.segmentConfig(segmentMinutes = 5, audioEnabled = false)
        assertFalse(config.includeAudio)
    }

    @Test
    fun `full range is expressible - sixty minutes equals the bounded ceiling exactly`() {
        val config = ContinuousRecordingPolicy.segmentConfig(segmentMinutes = 60, audioEnabled = true)
        assertEquals(RecordingConfig.MAX_DURATION_SECONDS, config.durationSeconds)
        assertEquals(3_600L, config.durationSeconds)
    }

    @Test
    fun `segment minutes beyond the ceiling clamp to the bounded max`() {
        val config = ContinuousRecordingPolicy.segmentConfig(segmentMinutes = 120, audioEnabled = true)
        assertEquals(RecordingConfig.MAX_DURATION_SECONDS, config.durationSeconds)
    }

    // ── user-stop suppression ──

    @Test
    fun `a stop sets a sixty second suppressed-until stamp`() {
        assertEquals(
            1_000L + ContinuousRecordingPolicy.USER_STOP_SUPPRESSION_MS,
            ContinuousRecordingPolicy.suppressedUntilMs(stopMs = 1_000L),
        )
        assertEquals(
            60_000L,
            ContinuousRecordingPolicy.USER_STOP_SUPPRESSION_MS,
        )
    }

    @Test
    fun `arming is blocked while suppressed and allowed once the stamp lapses`() {
        val stopMs = 10_000L
        val suppressedUntil = ContinuousRecordingPolicy.suppressedUntilMs(stopMs)
        assertFalse(ContinuousRecordingPolicy.canArm(nowMs = suppressedUntil - 1, suppressedUntilMs = suppressedUntil))
        assertTrue(ContinuousRecordingPolicy.canArm(nowMs = suppressedUntil, suppressedUntilMs = suppressedUntil))
        assertTrue(ContinuousRecordingPolicy.canArm(nowMs = suppressedUntil + 5_000, suppressedUntilMs = suppressedUntil))
    }

    @Test
    fun `no suppression stamp means arming is always allowed`() {
        assertTrue(ContinuousRecordingPolicy.canArm(nowMs = 0L, suppressedUntilMs = 0L))
    }

    // ── chain-break grace ──

    @Test
    fun `an idle within the grace window is not a chain break`() {
        assertFalse(ContinuousRecordingPolicy.chainBreakConfirmed(idleForMs = ContinuousRecordingPolicy.CHAIN_BREAK_GRACE_MS - 1))
    }

    @Test
    fun `an idle persisting past the grace window confirms the chain broke`() {
        assertTrue(ContinuousRecordingPolicy.chainBreakConfirmed(idleForMs = ContinuousRecordingPolicy.CHAIN_BREAK_GRACE_MS))
    }

    @Test
    fun `the chain-break grace outlives the repeat gap`() {
        // The chained auto-stop spends about REPEAT_GAP_SECONDS in Idle; the
        // grace must be strictly longer or healthy chaining reads as a break.
        assertTrue(ContinuousRecordingPolicy.CHAIN_BREAK_GRACE_MS > ContinuousRecordingPolicy.REPEAT_GAP_SECONDS * 1_000L)
    }

    // ── disable teardown ──

    @Test
    fun `toggling off stops only a segment the controller armed`() {
        assertTrue(
            ContinuousRecordingPolicy.shouldStopOnDisable(
                enabled = false,
                recordingActive = true,
                armedByController = true,
            )
        )
        assertFalse(
            ContinuousRecordingPolicy.shouldStopOnDisable(
                enabled = false,
                recordingActive = true,
                armedByController = false,
            )
        )
        assertFalse(
            ContinuousRecordingPolicy.shouldStopOnDisable(
                enabled = true,
                recordingActive = true,
                armedByController = true,
            )
        )
    }
}
