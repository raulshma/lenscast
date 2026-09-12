package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the NetworkQualityMonitor's encoded-sink throughput lane: the
 * aggregate bytes/time sampling behind the adaptive encoded-bitrate policy,
 * kept deliberately separate from the per-client MJPEG lane.
 */
class NetworkQualityMonitorEncodedLaneTest {

    @Test
    fun `empty lane answers zero and the default-aware level`() {
        val monitor = NetworkQualityMonitor()
        assertEquals(0, monitor.getEncodedThroughputKbps())
        assertFalse(monitor.hasEncodedSamples())
        // No samples: the ladder's default view applies → EXCELLENT, not CRITICAL.
        assertEquals(
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
            monitor.getEncodedQualityLevel(),
        )
    }

    @Test
    fun `recorded samples average into the lane`() {
        val monitor = NetworkQualityMonitor()
        // 10_000 bytes * 8 / 40 ms = 2000 kbps; 20_000 * 8 / 40 = 4000 kbps.
        monitor.recordEncodedSend(10_000, 40)
        monitor.recordEncodedSend(20_000, 40)
        assertTrue(monitor.hasEncodedSamples())
        assertEquals(3000, monitor.getEncodedThroughputKbps())
    }

    @Test
    fun `degenerate samples are dropped`() {
        val monitor = NetworkQualityMonitor()
        monitor.recordEncodedSend(0, 40)
        monitor.recordEncodedSend(10_000, 0)
        monitor.recordEncodedSend(-5, 10)
        assertFalse(monitor.hasEncodedSamples())
    }

    @Test
    fun `slow samples drop the encoded level`() {
        val monitor = NetworkQualityMonitor()
        // 400 bytes * 8 / 40 ms = 80 kbps → CRITICAL rung.
        repeat(5) { monitor.recordEncodedSend(400, 40) }
        assertEquals(
            NetworkQualityMonitor.NetworkQualityLevel.CRITICAL,
            monitor.getEncodedQualityLevel(),
        )
    }

    @Test
    fun `the encoded lane never re-weights the MJPEG client lane`() {
        val monitor = NetworkQualityMonitor()
        repeat(5) { monitor.recordEncodedSend(400, 40) } // slow encoded lane
        // No MJPEG client registered: the client lane stays at its default view.
        assertEquals(
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
            monitor.getNetworkQualityLevel(),
        )
        // And vice versa: an MJPEG client's samples never touch the encoded lane.
        monitor.resetStats()
        monitor.registerClient("mjpeg_1")
        repeat(5) { monitor.recordFrameSent("mjpeg_1", 400, 40) }
        assertFalse(monitor.hasEncodedSamples())
        assertEquals(
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
            monitor.getEncodedQualityLevel(),
        )
    }

    @Test
    fun `the lane window is bounded`() {
        val monitor = NetworkQualityMonitor()
        // First 20 samples slow (80 kbps), then fast (8000 kbps); after the
        // window slides, the slow samples age out.
        repeat(20) { monitor.recordEncodedSend(400, 40) }
        repeat(20) { monitor.recordEncodedSend(40_000, 40) }
        assertTrue(monitor.getEncodedThroughputKbps() >= 8000 - 1)
    }
}
