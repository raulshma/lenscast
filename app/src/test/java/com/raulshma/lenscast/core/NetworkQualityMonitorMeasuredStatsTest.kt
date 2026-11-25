package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The display half of the monitor's two-signal split (issue #25): every
 * displayed throughput/fps is measured from (timestamp, bytes) records in the
 * per-client sliding windows — known cadences read as known rates, silence
 * decays to 0, and the 5000 kbps ladder default never reaches a display
 * accessor. The injected clock makes every cadence deterministic.
 */
class NetworkQualityMonitorMeasuredStatsTest {

    private class FakeClock {
        var nowMs: Long = 1_000_000L
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    /** Records [frames] frames of [frameSizeBytes] at [stepMs] spacing on the fake clock. */
    private fun recordCadence(
        monitor: NetworkQualityMonitor,
        clientId: String,
        clock: FakeClock,
        frames: Int,
        frameSizeBytes: Int,
        stepMs: Long,
        sendDurationMs: Long = 0,
    ) {
        repeat(frames) {
            monitor.recordFrameSent(clientId, frameSizeBytes, sendDurationMs)
            clock.advance(stepMs)
        }
    }

    @Test
    fun `two clients at different rates average and min correctly`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("fast")
        monitor.registerClient("slow")
        // Interleaved cadences on one clock: fast sends 12_500 bytes per
        // 100 ms → 1000 kbps; slow sends 6_250 → 500 kbps (the divisor is
        // the fixed 5 s window, not the bytes' arrival spacing).
        repeat(50) {
            monitor.recordFrameSent("fast", 12_500, 0)
            monitor.recordFrameSent("slow", 6_250, 0)
            clock.advance(100)
        }
        assertEquals(1000, monitor.getClientThroughputKbps("fast"))
        assertEquals(500, monitor.getClientThroughputKbps("slow"))
        assertEquals(500, monitor.getMinClientThroughputKbps())
        assertEquals(750, monitor.getAvgClientThroughputKbps())
    }

    @Test
    fun `a single client reads avg equal to min equal to its own rate`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("solo")
        recordCadence(monitor, "solo", clock, frames = 50, frameSizeBytes = 12_500, stepMs = 100)
        assertEquals(1000, monitor.getClientThroughputKbps("solo"))
        assertEquals(1000, monitor.getMinClientThroughputKbps())
        assertEquals(1000, monitor.getAvgClientThroughputKbps())
        val snapshot = monitor.getStatsSnapshot()
        assertEquals(1000, snapshot.minThroughputKbps)
        assertEquals(1000, snapshot.avgThroughputKbps)
        assertEquals(1000, snapshot.clientDetails.getValue("solo").avgThroughputKbps)
    }

    @Test
    fun `no clients and no frames measure zero - never the ladder default`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        assertEquals(0, monitor.getMinClientThroughputKbps())
        assertEquals(0, monitor.getAvgClientThroughputKbps())
        // Connected but not yet sending: still zero, still no 5000.
        monitor.registerClient("quiet")
        assertEquals(0, monitor.getMinClientThroughputKbps())
        assertEquals(0, monitor.getAvgClientThroughputKbps())
        assertEquals(0, monitor.getStatsSnapshot().minThroughputKbps)
        assertEquals(0, monitor.getStatsSnapshot().avgThroughputKbps)
    }

    @Test
    fun `fps is measured from the recorded frame cadence`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("cam")
        // 100 frames at 100 ms → the window holds the last 5 s (50 frames) → 10 fps.
        recordCadence(monitor, "cam", clock, frames = 100, frameSizeBytes = 12_500, stepMs = 100)
        assertEquals(10.0, monitor.getFramesPerSecond("cam"), 0.001)
    }

    @Test
    fun `silence decays the measured rates to zero without disconnecting`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("stalled")
        recordCadence(monitor, "stalled", clock, frames = 50, frameSizeBytes = 12_500, stepMs = 100)
        assertEquals(1000, monitor.getAvgClientThroughputKbps())
        assertEquals(10.0, monitor.getFramesPerSecond("stalled"), 0.001)
        // The client stays connected but the frames stopped: the last sample
        // ages out of the window 5 s later, and the aggregate reflects it.
        clock.advance(5_001)
        assertEquals(1, monitor.activeClients)
        assertEquals(0, monitor.getClientThroughputKbps("stalled"))
        assertEquals(0, monitor.getAvgClientThroughputKbps())
        assertEquals(0, monitor.getMinClientThroughputKbps())
        assertEquals(0.0, monitor.getFramesPerSecond("stalled"), 0.0)
        assertEquals(0, monitor.getStatsSnapshot().avgThroughputKbps)
    }

    @Test
    fun `a disconnected client stops contributing to the aggregates`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("a")
        monitor.registerClient("b")
        // Interleaved so both windows are live at the read: a = 1000 kbps,
        // b = 500 kbps.
        repeat(50) {
            monitor.recordFrameSent("a", 12_500, 0)
            monitor.recordFrameSent("b", 6_250, 0)
            clock.advance(100)
        }
        assertEquals(750, monitor.getAvgClientThroughputKbps())

        monitor.unregisterClient("b")
        assertEquals(1000, monitor.getAvgClientThroughputKbps())
        assertEquals(1000, monitor.getMinClientThroughputKbps())
    }

    @Test
    fun `the ladder verdict ignores the display windows entirely`() {
        val clock = FakeClock()
        val monitor = NetworkQualityMonitor(nowMs = { clock.nowMs })
        monitor.registerClient("drained")
        // A punishing drain rate (400 bytes over 40 ms = 80 kbps → CRITICAL)
        // while the window still carries recent frames.
        repeat(5) {
            monitor.recordFrameSent("drained", 400, 40)
            clock.advance(100)
        }
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.CRITICAL, monitor.getNetworkQualityLevel())
        // And vice versa: empty windows never stall the ladder.
        val idle = NetworkQualityMonitor(nowMs = { clock.nowMs })
        idle.registerClient("fresh")
        assertEquals(NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT, idle.getNetworkQualityLevel())
    }
}
