package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sliding window behind every displayed stream stat (issue #25): known
 * bytes over known intervals must read as known kbps/fps, samples must slide
 * out at the window edge (never summed forever), and silence must decay the
 * reading to 0. The clock is injected, so every boundary is deterministic.
 */
class SlidingRateWindowTest {

    /** A window whose time only moves when the test advances it. */
    private class FakeClock {
        var nowMs: Long = 1_000_000L
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private fun windowWith(clock: FakeClock): SlidingRateWindow =
        SlidingRateWindow(nowMs = { clock.nowMs })

    /** 12_500 bytes every 100 ms is exactly 1 000 kbps and 10 fps. */
    private fun recordSteady(window: SlidingRateWindow, frames: Int, startMs: Long, stepMs: Long = 100) {
        repeat(frames) { index ->
            window.record(startMs + index * stepMs, 12_500)
        }
    }

    @Test
    fun `a fresh window is empty and measures zero`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        assertEquals(0, window.sampleCount())
        assertEquals(0.0, window.measuredBitrateKbps(), 0.0)
        assertEquals(0.0, window.measuredFps(), 0.0)
    }

    @Test
    fun `a first sample counts as a frame but a single instant carries no rate spike`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 12_500)
        assertEquals(1, window.sampleCount())
        // One frame over the full 5 s window: 12_500 * 8 / 5 s = 20 kbps —
        // the divisor is the window span, never a sub-frame drain duration.
        assertEquals(20.0, window.measuredBitrateKbps(), 0.001)
        assertEquals(0.2, window.measuredFps(), 0.001)
    }

    @Test
    fun `a steady known rate reads as exactly its kbps and fps`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // 50 frames of 12_500 bytes at 100 ms spacing = 625_000 bytes inside
        // the 5 s window → 625_000 * 8 / 5 = 1_000 kbps; 50 / 5 = 10 fps.
        recordSteady(window, frames = 50, startMs = clock.nowMs)
        clock.advance(4_900) // read after the last frame landed
        assertEquals(1000.0, window.measuredBitrateKbps(), 0.001)
        assertEquals(10.0, window.measuredFps(), 0.001)
        assertEquals(50, window.sampleCount())
    }

    @Test
    fun `a sample exactly at the window edge is still inside`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        val t0 = clock.nowMs
        window.record(t0, 12_500)
        // Advance exactly the window: cutoff = t0, and the sample is kept
        // (the slide-out drops strictly-older samples).
        clock.advance(5_000)
        assertEquals(1, window.sampleCount())
        assertEquals(20.0, window.measuredBitrateKbps(), 0.001)
    }

    @Test
    fun `a sample one ms past the window edge has slid out`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 12_500)
        clock.advance(5_001)
        assertEquals(0, window.sampleCount())
        assertEquals(0.0, window.measuredBitrateKbps(), 0.0)
        assertEquals(0.0, window.measuredFps(), 0.0)
    }

    @Test
    fun `silence decays the reading to zero as the samples age out`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        recordSteady(window, frames = 50, startMs = clock.nowMs)
        // 9_901 ms of silence since the FIRST frame (5_001 since the LAST):
        // the whole burst is older than the window edge — nothing summed.
        clock.advance(9_901)
        assertEquals(0, window.sampleCount())
        assertEquals(0.0, window.measuredBitrateKbps(), 0.0)
        assertEquals(0.0, window.measuredFps(), 0.0)
    }

    @Test
    fun `a burst keeps its rate while it stays inside the window then decays`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // Burst: 20 frames of 62_500 bytes at 100 ms = 1 250 000 bytes →
        // 2 000 kbps, 4 fps — held verbatim while time flows with no new
        // frames (the window's contents only shrink at the far edge).
        val startMs = clock.nowMs
        repeat(20) { index ->
            window.record(startMs + index * 100, 62_500)
        }
        clock.advance(2_000)
        assertEquals(2000.0, window.measuredBitrateKbps(), 0.001)
        assertEquals(4.0, window.measuredFps(), 0.001)
        // After the whole burst has slid past the edge: 0.
        clock.advance(5_001)
        assertEquals(0.0, window.measuredBitrateKbps(), 0.0)
    }

    @Test
    fun `only the samples inside the window are summed`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // Half the burst is older than the window edge by the time of the read:
        // frames at t0..t4900, read at t0+7400 → cutoff t0+2400 keeps exactly
        // the frames from t2400 (the inclusive edge) through t4900 — 26 frames,
        // 325_000 bytes → 520 kbps, 5.2 fps.
        recordSteady(window, frames = 50, startMs = clock.nowMs)
        clock.advance(7_400)
        assertEquals(26, window.sampleCount())
        assertEquals(520.0, window.measuredBitrateKbps(), 0.001)
        assertEquals(5.2, window.measuredFps(), 0.001)
    }

    @Test
    fun `long-running recording does not accumulate samples forever`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // 10 minutes at 100 ms spacing: the window can only ever hold the
        // last ~5 s worth — 50 frames here (cutoff keeps t5950..t5999).
        repeat(6_000) {
            window.record(clock.nowMs, 12_500)
            clock.advance(100)
        }
        assertEquals(50, window.sampleCount())
    }

    @Test
    fun `a zero-byte frame counts for fps but adds no bytes`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 0)
        assertEquals(1, window.sampleCount())
        assertEquals(0.0, window.measuredBitrateKbps(), 0.0)
        assertEquals(0.2, window.measuredFps(), 0.001)
    }

    @Test
    fun `a negative byte count is rejected`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, -1)
        assertEquals(0, window.sampleCount())
    }

    // ── the per-sample frame count (the WHEP lane spreads one poll delta over
    // several sub-samples, so a sample no longer means exactly one frame) ──

    @Test
    fun `a sample can carry a frame count and fps sums the counts`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // One sub-sample standing for 10 frames: 10 / 5 s = 2 fps; the bytes
        // are read exactly as before.
        window.record(clock.nowMs, 12_500, frames = 10.0)
        assertEquals(1, window.sampleCount())
        assertEquals(2.0, window.measuredFps(), 0.001)
        assertEquals(20.0, window.measuredBitrateKbps(), 0.001)
    }

    @Test
    fun `a sample with zero frames adds its bytes but no frame`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 12_500, frames = 0.0)
        assertEquals(1, window.sampleCount())
        assertEquals(0.0, window.measuredFps(), 0.0)
        assertEquals(20.0, window.measuredBitrateKbps(), 0.001)
    }

    @Test
    fun `a negative frame count is rejected`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 12_500, frames = -1.0)
        assertEquals(0, window.sampleCount())
    }

    @Test
    fun `frame counts sum across samples and age out with them`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // 50 sub-samples of 6 frames every 100 ms = 300 frames / 5 s = 60 fps.
        val startMs = clock.nowMs
        repeat(50) { index -> window.record(startMs + index * 100, 1_000, frames = 6.0) }
        clock.advance(4_900)
        assertEquals(60.0, window.measuredFps(), 0.001)
        // …and nothing is summed forever: the counts leave with their samples.
        clock.advance(5_001)
        assertEquals(0.0, window.measuredFps(), 0.0)
    }

    @Test
    fun `a fractional frame count is kept as is`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        // A sub-sample standing for half a frame: 0.5 / 5 s = 0.1 fps.
        window.record(clock.nowMs, 12_500, frames = 0.5)
        assertEquals(0.1, window.measuredFps(), 0.001)
        assertEquals(20.0, window.measuredBitrateKbps(), 0.001)
    }

    @Test
    fun `a non-finite frame count is rejected`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        window.record(clock.nowMs, 12_500, frames = Double.NaN)
        window.record(clock.nowMs, 12_500, frames = Double.POSITIVE_INFINITY)
        assertEquals(0, window.sampleCount())
    }

    @Test
    fun `totals hand out the window's raw bytes and frames`() {
        val clock = FakeClock()
        val window = windowWith(clock)
        assertEquals(SlidingRateWindow.Totals(bytes = 0L, frames = 0.0), window.totals())

        window.record(clock.nowMs, 12_500, frames = 6.0)
        window.record(clock.nowMs, 1_000, frames = 0.0)
        // The sums behind the two measured rates: 13 500 bytes and 6 frames.
        assertEquals(SlidingRateWindow.Totals(bytes = 13_500L, frames = 6.0), window.totals())
        assertEquals(13_500L * 8 / 5.0 / 1000.0, window.measuredBitrateKbps(), 0.001)
        assertEquals(6.0 / 5.0, window.measuredFps(), 0.001)

        clock.advance(5_001)
        assertEquals(SlidingRateWindow.Totals(bytes = 0L, frames = 0.0), window.totals())
    }
}
