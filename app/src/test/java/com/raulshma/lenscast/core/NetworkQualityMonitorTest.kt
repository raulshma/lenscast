package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitor's two-signal split (issue #25): the drain-rate congestion
 * signal keeps its math and drives the ladder; the display accessors report
 * the sliding windows' measured stream stats and never a default constant.
 * The window-timing behaviours live in NetworkQualityMonitorMeasuredStatsTest;
 * this file pins the drain signal and the ladder's default-aware idle view.
 */
class NetworkQualityMonitorTest {

    private fun monitorWithClientSending(
        clientId: String = "c1",
        frameSizeBytes: Int,
        sendDurationMs: Long,
        frames: Int = 20,
    ): NetworkQualityMonitor {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient(clientId)
        repeat(frames) {
            monitor.recordFrameSent(clientId, frameSizeBytes, sendDurationMs)
        }
        return monitor
    }

    @Test
    fun `no clients reports measured bandwidth of zero`() {
        val monitor = NetworkQualityMonitor()
        assertEquals(0, monitor.getMeasuredBandwidthKbps())
        assertEquals(0, monitor.activeClients)
    }

    @Test
    fun `register and unregister tracks active client count`() {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("a")
        monitor.registerClient("b")
        assertEquals(2, monitor.activeClients)
        monitor.unregisterClient("a")
        assertEquals(1, monitor.activeClients)
    }

    @Test
    fun `drain rate reflects per-frame bytes over drain time not a stream bitrate`() {
        // 10_000 bytes * 8 bits in 10 ms = 8000 kbps of DRAIN — the congestion
        // signal, deliberately distinct from the window-measured stream rate.
        val monitor = monitorWithClientSending(frameSizeBytes = 10_000, sendDurationMs = 10)
        assertEquals(8000, monitor.getClientDrainRateKbps("c1"))
        assertEquals(8000, monitor.getMeasuredBandwidthKbps())
    }

    @Test
    fun `quality ladder maps drain rates to levels`() {
        // 10_000 bytes in 10 ms = 8000 kbps drain → GOOD at 2+ clients (EXCELLENT needs ≤1)
        val twoClients = NetworkQualityMonitor()
        twoClients.registerClient("a")
        twoClients.registerClient("b")
        repeat(5) {
            twoClients.recordFrameSent("a", 10_000, 10)
            twoClients.recordFrameSent("b", 10_000, 10)
        }
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.GOOD, twoClients.getNetworkQualityLevel())

        // Single client at the same drain rate → EXCELLENT
        val oneClient = monitorWithClientSending(frameSizeBytes = 10_000, sendDurationMs = 10)
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, oneClient.getNetworkQualityLevel())
    }

    @Test
    fun `connected client with no frames measures zero but the ladder keeps its default view`() {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("idle")
        // Display truth: nothing measured — no drain sample, no window bitrate.
        assertEquals(0, monitor.getMinClientDrainRateKbps())
        assertEquals(0, monitor.getAvgClientDrainRateKbps())
        assertEquals(0, monitor.getClientThroughputKbps("idle"))
        // Ladder truth: the default-aware view keeps adapting while idle.
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, monitor.getNetworkQualityLevel())
    }

    @Test
    fun `worst client latency reports max send duration`() {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("a")
        monitor.registerClient("b")
        monitor.recordFrameSent("a", 1000, 5)
        monitor.recordFrameSent("b", 1000, 40)
        assertEquals(40L, monitor.getWorstClientLatencyMs())
    }

    @Test
    fun `total bytes sent accumulates across clients`() {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("a")
        monitor.registerClient("b")
        monitor.recordFrameSent("a", 1000, 1)
        monitor.recordFrameSent("b", 2500, 1)
        assertEquals(3500L, monitor.getTotalBytesSent())
    }

    @Test
    fun `resetStats clears everything`() {
        val monitor = monitorWithClientSending(frameSizeBytes = 1000, sendDurationMs = 5)
        monitor.resetStats()
        assertEquals(0, monitor.activeClients)
        assertEquals(0L, monitor.getTotalBytesSent())
        assertEquals(0, monitor.getMeasuredBandwidthKbps())
    }

    @Test
    fun `snapshot exposes zero throughput display fields with no clients`() {
        val monitor = NetworkQualityMonitor()
        val snapshot = monitor.getStatsSnapshot()
        assertEquals(0, snapshot.minThroughputKbps)
        assertEquals(0, snapshot.avgThroughputKbps)
        assertEquals(0, snapshot.estimatedBandwidthKbps)
    }

    @Test
    fun `idle accessors measure zero and only the ladder sees the default`() {
        val monitor = NetworkQualityMonitor()
        // Display truth: nothing measured while idle — no 5000 anywhere.
        assertEquals(0, monitor.getMinClientThroughputKbps())
        assertEquals(0, monitor.getAvgClientThroughputKbps())
        assertEquals(0, monitor.getMinClientDrainRateKbps())
        assertEquals(0, monitor.getAvgClientDrainRateKbps())
        // Ladder truth: the default-aware view keeps adapting while idle.
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, monitor.getNetworkQualityLevel())
    }

    @Test
    fun `sending client splits into measured display and drain signal`() {
        // 20 frames of 10_000 bytes: the window measures 200_000 bytes over
        // its 5 s span → 320 kbps and 4 fps; the drain signal still reads
        // 8000 kbps from bytes-over-drain-ms — same records, two signals.
        val monitor = monitorWithClientSending(frameSizeBytes = 10_000, sendDurationMs = 10)
        assertEquals(320, monitor.getClientThroughputKbps("c1"))
        assertEquals(4.0, monitor.getFramesPerSecond("c1"), 0.001)
        assertEquals(8000, monitor.getClientDrainRateKbps("c1"))
        assertEquals(8000, monitor.getMinClientDrainRateKbps())
        // Ladder verdict from the drain signal, untouched by the display rate.
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, monitor.getNetworkQualityLevel())

        val snapshot = monitor.getStatsSnapshot()
        assertEquals(320, snapshot.minThroughputKbps)
        assertEquals(320, snapshot.avgThroughputKbps)
        assertEquals(320, snapshot.clientDetails.getValue("c1").avgThroughputKbps)
    }

    @Test
    fun `zero-byte frames keep the ladder critical instead of defaulting`() {
        // Presence-gated, not value-gated: sampled drain rates of 0 kbps are
        // real measurements (CRITICAL), never the ladder's 5000 default.
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("a")
        repeat(5) { monitor.recordFrameSent("a", 0, 10) }
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.CRITICAL, monitor.getNetworkQualityLevel())
        assertEquals(0, monitor.getMinClientDrainRateKbps())
    }
}
