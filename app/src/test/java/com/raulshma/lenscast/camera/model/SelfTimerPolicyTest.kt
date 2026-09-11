package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTimerPolicyTest {

    // ── the durations ──

    @Test
    fun `off has no duration and no initial seconds`() {
        assertEquals(0L, SelfTimerPolicy.durationMs(SelfTimerMode.OFF))
        assertEquals(0, SelfTimerPolicy.initialSeconds(SelfTimerMode.OFF))
    }

    @Test
    fun `the short timer lasts three seconds`() {
        assertEquals(3_000L, SelfTimerPolicy.durationMs(SelfTimerMode.S3))
        assertEquals(3, SelfTimerPolicy.initialSeconds(SelfTimerMode.S3))
    }

    @Test
    fun `the long timer lasts ten seconds`() {
        assertEquals(10_000L, SelfTimerPolicy.durationMs(SelfTimerMode.S10))
        assertEquals(10, SelfTimerPolicy.initialSeconds(SelfTimerMode.S10))
    }

    // ── the shutter press verdict ──

    @Test
    fun `an off timer captures immediately`() {
        val press = SelfTimerPolicy.onShutterPress(SelfTimerMode.OFF)

        assertTrue(press is SelfTimerPolicy.ShutterPress.CaptureNow)
    }

    @Test
    fun `an armed timer starts a countdown of its full duration`() {
        val press = SelfTimerPolicy.onShutterPress(SelfTimerMode.S3)
            as SelfTimerPolicy.ShutterPress.StartCountdown

        assertEquals(3_000L, press.durationMs)
        assertEquals(3, press.initialSeconds)
    }

    @Test
    fun `the ten-second timer starts its own countdown`() {
        val press = SelfTimerPolicy.onShutterPress(SelfTimerMode.S10)
            as SelfTimerPolicy.ShutterPress.StartCountdown

        assertEquals(10_000L, press.durationMs)
        assertEquals(10, press.initialSeconds)
    }

    // ── the tick verdicts ──

    @Test
    fun `the first tick shows the full duration`() {
        val tick = SelfTimerPolicy.onTick(durationMs = 3_000L, elapsedMs = 200L)
            as SelfTimerPolicy.Tick.Counting

        assertEquals(3, tick.secondsRemaining)
    }

    @Test
    fun `each elapsed second steps the display down`() {
        assertEquals(
            2,
            (SelfTimerPolicy.onTick(3_000L, elapsedMs = 1_200L) as SelfTimerPolicy.Tick.Counting)
                .secondsRemaining,
        )
        assertEquals(
            1,
            (SelfTimerPolicy.onTick(3_000L, elapsedMs = 2_200L) as SelfTimerPolicy.Tick.Counting)
                .secondsRemaining,
        )
    }

    @Test
    fun `the display never dips below one before the fire boundary`() {
        val tick = SelfTimerPolicy.onTick(durationMs = 3_000L, elapsedMs = 2_999L)
            as SelfTimerPolicy.Tick.Counting

        assertEquals(1, tick.secondsRemaining)
    }

    @Test
    fun `the timer fires at the duration and stays fired past it`() {
        assertEquals(SelfTimerPolicy.Tick.Fire, SelfTimerPolicy.onTick(3_000L, elapsedMs = 3_000L))
        assertEquals(SelfTimerPolicy.Tick.Fire, SelfTimerPolicy.onTick(3_000L, elapsedMs = 3_200L))
        assertEquals(SelfTimerPolicy.Tick.Fire, SelfTimerPolicy.onTick(0L, elapsedMs = 0L))
    }

    // ── the tap verdicts ──

    @Test
    fun `a tap during an armed countdown cancels it`() {
        assertEquals(
            SelfTimerPolicy.TapVerdict.CANCEL_COUNTDOWN,
            SelfTimerPolicy.onTap(countdownActive = true),
        )
    }

    @Test
    fun `a tap with no countdown is a normal preview tap`() {
        assertEquals(
            SelfTimerPolicy.TapVerdict.PASS_THROUGH,
            SelfTimerPolicy.onTap(countdownActive = false),
        )
    }
}
