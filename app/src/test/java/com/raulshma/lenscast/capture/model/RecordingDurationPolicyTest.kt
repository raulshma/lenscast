package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-sequence tests for the bounded-recording policy: each test walks the
 * verdicts in the exact order the RecordingController consults them, pinning
 * the user-stop / auto-stop / repeat-fire / schedule-fire races the
 * controller's epoch + lock bookkeeping promises to close.
 */
class RecordingDurationPolicyTest {

    private fun config(
        durationSeconds: Long = 60,
        repeatIntervalSeconds: Long = 0,
    ) = RecordingConfig(
        durationSeconds = durationSeconds,
        repeatIntervalSeconds = repeatIntervalSeconds,
    )

    // ── Arming ──

    @Test
    fun `unlimited recordings arm no policy - null config or zero duration`() {
        assertFalse(RecordingDurationPolicy.shouldArm(null))
        assertFalse(RecordingDurationPolicy.shouldArm(config(durationSeconds = 0)))
        assertTrue(RecordingDurationPolicy.shouldArm(config(durationSeconds = 1)))
    }

    // ── The auto-stop's delay ──

    @Test
    fun `the auto-stop delay is the duration minus the elapsed time`() {
        val cfg = config(durationSeconds = 60)
        val startedAt = 1_000_000L

        assertEquals(60_000L, RecordingDurationPolicy.autoStopDelayMs(cfg, startedAt, startedAt))
        assertEquals(10_000L, RecordingDurationPolicy.autoStopDelayMs(cfg, startedAt, startedAt + 50_000))
        assertEquals(-5_000L, RecordingDurationPolicy.autoStopDelayMs(cfg, startedAt, startedAt + 65_000))
    }

    // ── Race 1: a user stop during the armed countdown ──

    @Test
    fun `a user stop during the armed countdown kills the auto-stop with it`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 30)
        val startedAt = 1_000_000L
        assertTrue(RecordingDurationPolicy.shouldArm(cfg))

        // Mid-countdown the stop() lands: the epoch bumps and the whole
        // cycle is cancelled, so on the duration job's late wake the fire
        // gate must refuse even though the job still identifies as current.
        val epochUnchanged = false
        assertFalse(
            RecordingDurationPolicy.shouldFireAutoStop(
                autoStopJobIsCurrent = true,
                epochUnchanged = epochUnchanged,
            )
        )
        // The controller therefore never sets stop-pending nor sends the
        // auto-stop intent; when the user stop's own report arrives it is
        // service-reported, and nothing survives it.
        assertFalse(
            RecordingDurationPolicy.doesRepeatSurviveStop(
                RecordingDurationPolicy.StopCause.SERVICE_REPORTED
            )
        )
    }

    @Test
    fun `a user stop landing between the auto-stop and the repeat re-arm wins`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 30)

        // The auto-stop fired legitimately (job current, epoch unchanged).
        assertTrue(
            RecordingDurationPolicy.shouldFireAutoStop(
                autoStopJobIsCurrent = true,
                epochUnchanged = true,
            )
        )
        // The stop intent is sent, stop-pending is set — and before the
        // re-arm step takes the lock, stop() bumps the epoch. The re-arm
        // gate must refuse despite the repeat being configured.
        assertFalse(
            RecordingDurationPolicy.shouldArmRepeatAfterAutoStop(
                cfg,
                epochUnchanged = false,
            )
        )
    }

    // ── Race 2: the auto-stop fires and re-arms the repeat ──

    @Test
    fun `the auto-stop fires on a clean wake and re-arms the configured repeat`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 30)

        assertTrue(
            RecordingDurationPolicy.shouldFireAutoStop(
                autoStopJobIsCurrent = true,
                epochUnchanged = true,
            )
        )
        // Stop-pending set, stop intent sent; the re-arm gate passes with
        // the epoch still at arm time.
        assertTrue(
            RecordingDurationPolicy.shouldArmRepeatAfterAutoStop(
                cfg,
                epochUnchanged = true,
            )
        )
        assertEquals(30_000L, RecordingDurationPolicy.repeatDelayMs(cfg))
    }

    @Test
    fun `the repeat fire triple-check refuses every lost race`() {
        // Happy path: still the active repeat job, epoch unchanged, and the
        // service drained back to Idle.
        assertTrue(
            RecordingDurationPolicy.shouldFireRepeat(
                repeatJobIsCurrent = true,
                epochUnchanged = true,
                stateIsIdle = true,
            )
        )
        // Superseded by a newer repeat job.
        assertFalse(
            RecordingDurationPolicy.shouldFireRepeat(
                repeatJobIsCurrent = false,
                epochUnchanged = true,
                stateIsIdle = true,
            )
        )
        // A user stop()/start() bumped the epoch during the gap.
        assertFalse(
            RecordingDurationPolicy.shouldFireRepeat(
                repeatJobIsCurrent = true,
                epochUnchanged = false,
                stateIsIdle = true,
            )
        )
        // The service never came back to Idle — never re-start over a live
        // recording.
        assertFalse(
            RecordingDurationPolicy.shouldFireRepeat(
                repeatJobIsCurrent = true,
                epochUnchanged = true,
                stateIsIdle = false,
            )
        )
    }

    // ── Race 3: service-reported stop vs repeat survival ──

    @Test
    fun `only the policy's own auto-stop report lets the armed repeat survive`() {
        assertTrue(
            RecordingDurationPolicy.doesRepeatSurviveStop(RecordingDurationPolicy.StopCause.AUTO)
        )
        assertFalse(
            RecordingDurationPolicy.doesRepeatSurviveStop(
                RecordingDurationPolicy.StopCause.SERVICE_REPORTED
            )
        )
        assertFalse(
            RecordingDurationPolicy.doesRepeatSurviveStop(RecordingDurationPolicy.StopCause.USER)
        )
    }

    @Test
    fun `the full bounded cycle survives its own stop report and fires the repeat`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 30)
        var stateIsIdle = false // recording

        assertTrue(RecordingDurationPolicy.shouldArm(cfg))
        assertTrue(
            RecordingDurationPolicy.shouldFireAutoStop(autoStopJobIsCurrent = true, epochUnchanged = true)
        )
        // The auto-stop's report lands: AUTO cause, so the repeat survives
        // the drain and waits out the gap.
        assertTrue(
            RecordingDurationPolicy.doesRepeatSurviveStop(RecordingDurationPolicy.StopCause.AUTO)
        )
        stateIsIdle = true
        // Gap over: the triple check passes and the controller re-starts.
        assertTrue(
            RecordingDurationPolicy.shouldFireRepeat(
                repeatJobIsCurrent = true,
                epochUnchanged = true,
                stateIsIdle = stateIsIdle,
            )
        )
    }

    @Test
    fun `an error stop report with a stale queued stop cancels the armed repeat`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 30)
        assertTrue(
            RecordingDurationPolicy.shouldArmRepeatAfterAutoStop(cfg, epochUnchanged = true)
        )
        // Nothing was policy-pending: an error or service death reported the
        // stop — the armed repeat must not fire behind it.
        assertFalse(
            RecordingDurationPolicy.doesRepeatSurviveStop(
                RecordingDurationPolicy.StopCause.SERVICE_REPORTED
            )
        )
    }

    // ── Repeat disabled vs enabled ──

    @Test
    fun `a repeat disabled config never arms a follow-up`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 0)
        assertFalse(RecordingDurationPolicy.shouldArmRepeat(cfg))
        assertFalse(RecordingDurationPolicy.shouldArmRepeatAfterAutoStop(cfg, epochUnchanged = true))
        // With no repeat armed, even a surviving stop report has nothing to
        // keep alive — the survival verdict is about the cause alone.
        assertTrue(
            RecordingDurationPolicy.doesRepeatSurviveStop(RecordingDurationPolicy.StopCause.AUTO)
        )
    }

    @Test
    fun `a repeat enabled config arms and waits out its configured gap`() {
        val cfg = config(durationSeconds = 60, repeatIntervalSeconds = 1)
        assertTrue(RecordingDurationPolicy.shouldArmRepeat(cfg))
        assertTrue(RecordingDurationPolicy.shouldArmRepeatAfterAutoStop(cfg, epochUnchanged = true))
        assertEquals(1_000L, RecordingDurationPolicy.repeatDelayMs(cfg))
    }
}
