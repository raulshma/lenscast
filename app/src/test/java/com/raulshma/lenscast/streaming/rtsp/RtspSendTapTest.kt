package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM pin on the writer-thread send tap ([RtspSendTap]): sub-millisecond
 * writes accumulate until the window is owed, then one honest bytes/time
 * sample goes out. The window exists because NetworkQualityMonitor drops
 * durationMs <= 0 samples — an unwindowed per-AU tap on a LAN would silently
 * disable the encoded-bitrate adaptation.
 */
class RtspSendTapTest {

    private class Recorder {
        val samples = mutableListOf<Pair<Int, Long>>()
        val tap = RtspSendTap { bytes, ms -> samples += bytes to ms }
    }

    @Test
    fun `sub-millisecond writes accumulate without emitting`() {
        val r = Recorder()
        // 200 writes of 0.2 ms each = 40 ms total: under the 100 ms window.
        repeat(200) { r.tap.record(1_000, 200_000L) }
        assertTrue(r.samples.isEmpty())
    }

    @Test
    fun `the window emits one sample with the accumulated bytes and duration`() {
        val r = Recorder()
        // 600 writes of 0.2 ms = 120 ms: the 500th write crosses 100 ms.
        repeat(600) { r.tap.record(1_000, 200_000L) }
        assertEquals(1, r.samples.size)
        val (bytes, ms) = r.samples[0]
        assertEquals(500_000, bytes) // 500 writes × 1000 bytes
        assertEquals(100L, ms)
        assertTrue(ms >= 1L)
        // The remaining 100 writes (20 ms) sit in the fresh window.
        r.tap.record(1_000, 80_000_000L)
        assertEquals(2, r.samples.size)
        assertEquals(101_000, r.samples[1].first)
        assertEquals(100L, r.samples[1].second)
    }

    @Test
    fun `one long congested write emits immediately`() {
        val r = Recorder()
        r.tap.record(250_000, 350_000_000L) // 350 ms blocking write
        assertEquals(1, r.samples.size)
        assertEquals(250_000 to 350L, r.samples[0])
    }

    @Test
    fun `the emitted duration is never zero`() {
        val r = Recorder()
        repeat(1_000) { r.tap.record(500, 100_000L) } // 0.1 ms each, 100 ms total
        assertEquals(1, r.samples.size)
        assertTrue(r.samples[0].second >= 1L)
    }

    @Test
    fun `reset drops the partial window`() {
        val r = Recorder()
        repeat(400) { r.tap.record(1_000, 200_000L) } // 80 ms pending
        r.tap.reset()
        repeat(400) { r.tap.record(1_000, 200_000L) } // another 80 ms
        assertTrue(r.samples.isEmpty())
        r.tap.record(1_000, 20_000_000L) // crosses 100 ms
        assertEquals(1, r.samples.size)
        assertEquals(401_000, r.samples[0].first)
    }

    @Test
    fun `a custom window bounds the sample cadence`() {
        val samples = mutableListOf<Pair<Int, Long>>()
        val tap = RtspSendTap(minWindowMs = 10L) { bytes, ms -> samples += bytes to ms }
        tap.record(100, 9_000_000L)
        assertTrue(samples.isEmpty())
        tap.record(100, 1_000_000L)
        assertEquals(listOf(200 to 10L), samples)
    }
}