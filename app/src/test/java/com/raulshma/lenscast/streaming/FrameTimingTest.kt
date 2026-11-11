package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTimingTest {

    @Test
    fun `positive fps passes through`() {
        assertEquals(1, FrameTiming.effectiveFps(1))
        assertEquals(24, FrameTiming.effectiveFps(24))
        assertEquals(30, FrameTiming.effectiveFps(30))
        assertEquals(60, FrameTiming.effectiveFps(60))
    }

    @Test
    fun `zero and negative fps fall back to the stream default`() {
        assertEquals(StreamDefaults.STREAM_FPS, FrameTiming.effectiveFps(0))
        assertEquals(StreamDefaults.STREAM_FPS, FrameTiming.effectiveFps(-1))
        assertEquals(StreamDefaults.STREAM_FPS, FrameTiming.effectiveFps(Int.MIN_VALUE))
    }

    @Test
    fun `frame interval is 1000 over the effective fps`() {
        assertEquals(41L, FrameTiming.frameIntervalMs(24))
        assertEquals(33L, FrameTiming.frameIntervalMs(30))
        assertEquals(16L, FrameTiming.frameIntervalMs(60))
        assertEquals(1_000L, FrameTiming.frameIntervalMs(1))
        assertEquals(1_000L / StreamDefaults.STREAM_FPS, FrameTiming.frameIntervalMs(0))
    }

    @Test
    fun `the two functions agree on one effective fps`() {
        for (fps in listOf(0, -3, 1, 24, 30)) {
            val effective = FrameTiming.effectiveFps(fps)
            assertEquals(1_000L / effective, FrameTiming.frameIntervalMs(fps))
        }
    }
}
