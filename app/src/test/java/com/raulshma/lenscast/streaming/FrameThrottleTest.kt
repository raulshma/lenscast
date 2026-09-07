package com.raulshma.lenscast.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameThrottleTest {

    // ── default semantics: reference stamped on accepted frames only ──

    @Test
    fun `first frame at a wall-clock timestamp is accepted then immediate frames rejected`() {
        val throttle = FrameThrottle({ 100L })
        // A fresh throttle's reference is epoch 0, so any realistic wall clock passes.
        assertTrue(throttle.accept(10_000))
        // 0ms later: rejected.
        assertFalse(throttle.accept(10_000))
        // 99ms later: still below the interval → rejected.
        assertFalse(throttle.accept(10_099))
    }

    @Test
    fun `first frame below one interval is measured against epoch zero`() {
        // Documents the initial-reference behavior call sites rely on (real call
        // sites pass wall-clock nowMs, which dwarfs the interval).
        val throttle = FrameThrottle(100)
        assertFalse(throttle.accept(50))
        assertTrue(throttle.accept(100))
    }

    @Test
    fun `at-interval frame is accepted against the last accepted frame`() {
        val throttle = FrameThrottle({ 100L })
        assertTrue(throttle.accept(1_000))
        // Rejected frames do not move the reference: 100ms after the accepted
        // frame at 1_000 (not after the rejected attempt at 1_099) passes.
        assertFalse(throttle.accept(1_099))
        assertTrue(throttle.accept(1_100))
        // Burst: 1_100 accepted → 1_150 rejected → 1_200 accepted.
        assertFalse(throttle.accept(1_150))
        assertTrue(throttle.accept(1_200))
    }

    @Test
    fun `boundary is inclusive - elapsed exactly at the interval passes`() {
        val throttle = FrameThrottle({ 100L })
        assertTrue(throttle.accept(1_000))
        assertFalse(throttle.accept(1_099)) // 99 < 100
        assertTrue(throttle.accept(1_100)) // 100 >= 100
    }

    // ── tolerance ──

    @Test
    fun `rtsp tolerance accepts slightly early frames at the 0_8 boundary`() {
        val throttle = FrameThrottle({ 100L }, tolerance = FrameThrottle.TOLERANCE)
        assertTrue(throttle.accept(1_000))
        assertFalse(throttle.accept(1_079)) // 79 < 80 = 100 * 0.8
        assertTrue(throttle.accept(1_080)) // 80 >= 80
    }

    @Test
    fun `tolerance of one replicates exact-interval behavior`() {
        val throttle = FrameThrottle({ 1_000L }, tolerance = 1.0f)
        assertTrue(throttle.accept(10_000))
        assertFalse(throttle.accept(10_999))
        assertTrue(throttle.accept(11_000))
    }

    // ── updateClockOnReject: the RTSP sliding-clock behavior ──

    @Test
    fun `rejected frames advance the reference when updateClockOnReject is set`() {
        val throttle = FrameThrottle({ 100L }, tolerance = 1.0f, updateClockOnReject = true)
        assertTrue(throttle.accept(1_000))
        // A rejected attempt still stamps the clock...
        assertFalse(throttle.accept(1_050))
        // ...so the next wait starts over from 1_050: only 50ms elapsed here,
        // where the default semantics would have accepted (100ms since 1_000).
        assertFalse(throttle.accept(1_100))
        // The slide continues until a full interval passes since the last attempt.
        assertTrue(throttle.accept(1_200))
    }

    @Test
    fun `updateClockOnReject accepts every frame at or above interval-tolerance cadence`() {
        // The RTSP path's actual configuration: interval 100ms, tolerance 0.8,
        // clock on every attempt — a steady 80ms cadence never drops a frame.
        val throttle = FrameThrottle({ 100L }, tolerance = FrameThrottle.TOLERANCE, updateClockOnReject = true)
        assertTrue(throttle.accept(10_000))
        for (t in 10_080L..10_400L step 80L) {
            assertTrue("accepted at $t", throttle.accept(t))
        }
    }

    // ── interval supplier ──

    @Test
    fun `interval is read from the supplier on every decision`() {
        var interval = 100L
        val throttle = FrameThrottle({ interval })
        assertTrue(throttle.accept(1_000))
        interval = 300L
        assertFalse(throttle.accept(1_100)) // 100 < 300
        assertTrue(throttle.accept(1_300)) // 300 >= 300
        interval = 10L
        assertFalse(throttle.accept(1_305)) // 5 < 10
        assertTrue(throttle.accept(1_310)) // 10 >= 10
    }

    @Test
    fun `a clock running backwards never passes`() {
        val throttle = FrameThrottle({ 100L })
        assertTrue(throttle.accept(1_000))
        assertFalse(throttle.accept(900)) // negative elapsed
    }
}
