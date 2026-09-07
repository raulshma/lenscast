package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `throughput reflects measured frames not invented constants`() {
        // 10_000 bytes * 8 bits in 10 ms = 8000 kbps
        val monitor = monitorWithClientSending(frameSizeBytes = 10_000, sendDurationMs = 10)
        assertEquals(8000, monitor.getClientThroughputKbps("c1"))
        assertEquals(8000, monitor.getMeasuredBandwidthKbps())
    }

    @Test
    fun `quality ladder maps throughput to levels`() {
        // 10_000 bytes in 10 ms = 8000 kbps → GOOD at 2+ clients (EXCELLENT needs ≤1)
        val twoClients = NetworkQualityMonitor()
        twoClients.registerClient("a")
        twoClients.registerClient("b")
        repeat(5) {
            twoClients.recordFrameSent("a", 10_000, 10)
            twoClients.recordFrameSent("b", 10_000, 10)
        }
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.GOOD, twoClients.getNetworkQualityLevel())

        // Single client at the same throughput → EXCELLENT
        val oneClient = monitorWithClientSending(frameSizeBytes = 10_000, sendDurationMs = 10)
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, oneClient.getNetworkQualityLevel())
    }

    @Test
    fun `client with no samples falls back to default for the ladder`() {
        val monitor = NetworkQualityMonitor()
        monitor.registerClient("idle")
        // Ladder view: 5000 kbps default → GOOD with one client
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, monitor.getNetworkQualityLevel())
        assertTrue(monitor.getMinClientThroughputKbps() > 0)
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
}
