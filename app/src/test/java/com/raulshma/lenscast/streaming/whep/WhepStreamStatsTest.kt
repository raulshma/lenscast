package com.raulshma.lenscast.streaming.whep

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHEP lane's measured-stream stats (issue #25): libwebrtc hands out
 * CUMULATIVE counter totals per viewer session, so the rate is the delta
 * between two polls, and a 2 s poll cannot feed a 5 s sliding window
 * directly without the reading following the poll cadence. [WhepStreamStats]
 * spreads each poll's delta over the quarter-second cells the poll covered
 * and holds the not-yet-polled tail at the last poll's rate. These tests
 * pin what that buys: a steady stream reads its true rate at EVERY instant,
 * whatever the poll phase, the read phase or the cadence jitter; sessions
 * sum; and a removed, silent or no-longer-polled session stops contributing
 * honestly.
 *
 * Every poll below reports the cumulative totals of a steady stream, exactly
 * as a real `getStats` callback would; the clock is injected, so nothing in
 * the window depends on real time. The clock starts off any lattice (…037),
 * as a real poll never lands on a round number.
 */
class WhepStreamStatsTest {

    /** A window whose time only moves when the test moves it. */
    private class FakeClock {
        var nowMs: Long = 1_000_037L
    }

    /** A steady stream's cumulative counters: `kbps` kilobits and `fps` frames per second since [startMs]. */
    private class SteadyStream(private val kbps: Long, private val fps: Long, private val startMs: Long) {
        fun bytesAt(atMs: Long): Long = kbps * (atMs - startMs) / 8
        fun framesAt(atMs: Long): Long = fps * (atMs - startMs) / 1000
    }

    private fun stats(clock: FakeClock) = WhepStreamStats(pollIntervalMs = 2_000, nowMs = { clock.nowMs })

    /** Admits a session and stores its first observation: totals at zero, the baseline every later delta reads against. */
    private fun baseline(stats: WhepStreamStats, id: String, atMs: Long) {
        stats.onSessionAdmitted(id)
        stats.onStats(id, atMs, bytesSent = 0, framesEncoded = 0)
    }

    /** One real-shaped observation of [stream] at [atMs]. */
    private fun poll(
        stats: WhepStreamStats,
        stream: SteadyStream,
        id: String,
        atMs: Long,
        codec: String? = "vp8",
        width: Int? = 600,
        height: Int? = 800,
    ) {
        stats.onStats(
            id,
            atMs,
            bytesSent = stream.bytesAt(atMs),
            framesEncoded = stream.framesAt(atMs),
            codec = codec,
            width = width,
            height = height,
        )
    }

    /** Polls a steady `kbps`/`fps` stream on [id] every [intervalMs] until [untilMs]; the clock ends at the last poll. */
    private fun runPolls(
        clock: FakeClock,
        stats: WhepStreamStats,
        id: String,
        kbps: Long,
        fps: Long,
        untilMs: Long,
        intervalMs: Long = 2_000,
    ) {
        val stream = SteadyStream(kbps, fps, startMs = clock.nowMs)
        val start = clock.nowMs
        baseline(stats, id, start)
        var at = start
        while (at + intervalMs <= untilMs) {
            at += intervalMs
            clock.nowMs = at
            poll(stats, stream, id, at)
        }
    }

    /** Two sessions (24 and 15 fps) polled in lockstep for 10 s; the clock ends at the last poll. */
    private fun runTwoSessions(clock: FakeClock, stats: WhepStreamStats, aKbps: Long, bKbps: Long): Pair<SteadyStream, SteadyStream> {
        val start = clock.nowMs
        val a = SteadyStream(kbps = aKbps, fps = 24, startMs = start)
        val b = SteadyStream(kbps = bKbps, fps = 15, startMs = start)
        baseline(stats, "a", start)
        baseline(stats, "b", start)
        var at = start
        while (at + 2_000 <= start + 10_000) {
            at += 2_000
            clock.nowMs = at
            poll(stats, a, "a", at)
            poll(stats, b, "b", at)
        }
        return a to b
    }

    // ── sessions: admission, baseline, removal ──

    @Test
    fun `a fresh stats object answers no snapshot and the first observation only stores a baseline`() {
        val clock = FakeClock()
        val stats = stats(clock)
        assertNull(stats.snapshot())

        stats.onSessionAdmitted("a")
        assertNull(stats.snapshot())

        stats.onStats("a", clock.nowMs, bytesSent = 900_000, framesEncoded = 400, codec = "vp8", width = 600, height = 800)

        // The first totals describe nothing yet — there is no earlier poll to
        // difference them against — so they measure zero, never a spike.
        val snapshot = stats.snapshot()
        assertNotNull(snapshot)
        assertEquals(0, snapshot!!.measuredBitrateKbps)
        assertEquals(0.0, snapshot.fps, 0.0)
        // …while the descriptive facts the same report carried are known.
        assertEquals("vp8", snapshot.codec)
        assertEquals(600, snapshot.width)
        assertEquals(800, snapshot.height)
    }

    @Test
    fun `an observation for a session that was never admitted is ignored`() {
        val clock = FakeClock()
        val stats = stats(clock)
        stats.onStats("stray", clock.nowMs, bytesSent = 1_000_000, framesEncoded = 240, codec = "vp8")
        // Nothing on the wire for a session the owner never announced.
        assertNull(stats.snapshot())
    }

    @Test
    fun `a baseline-only session stops answering once removed`() {
        val clock = FakeClock()
        val stats = stats(clock)
        baseline(stats, "a", clock.nowMs)
        assertNotNull(stats.snapshot())
        stats.onSessionRemoved("a")
        assertNull(stats.snapshot())
        // Removing an unknown session is a no-op.
        stats.onSessionRemoved("a")
        assertNull(stats.snapshot())
    }

    @Test
    fun `a report that lands after the session was removed cannot resurrect it`() {
        val clock = FakeClock()
        val stats = stats(clock)
        baseline(stats, "a", clock.nowMs)
        stats.onSessionRemoved("a")

        // A getStats report still in flight when the viewer left: the id is
        // unknown now, so it is dropped — the lane stays absent.
        clock.nowMs += 2_000
        stats.onStats("a", clock.nowMs, bytesSent = 425_000, framesEncoded = 48, codec = "vp8", width = 600, height = 800)
        assertNull(stats.snapshot())
    }

    // ── the measured rate ──

    @Test
    fun `a steady session reads its true bitrate and fps once the window is full`() {
        val clock = FakeClock()
        val stats = stats(clock)
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = clock.nowMs + 10_000)

        val snapshot = stats.snapshot()!!
        assertEquals(1700, snapshot.measuredBitrateKbps)
        assertEquals(24.0, snapshot.fps, 0.001)
    }

    @Test
    fun `the reading ramps up with the window and never spikes past the truth`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = start + 2_000)

        // One poll in: 8 of the window's 20 cells are recorded — 8/20 of the
        // true rate, the same partial-window-reads-LOW rule every lane has.
        assertEquals(680, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(9.6, stats.snapshot()!!.fps, 0.001)

        // Until the next poll the not-yet-polled tail fills in behind it,
        // held at that rate: 4 more cells after 1 s, 8 after 2 s.
        clock.nowMs = start + 3_000
        assertEquals(1020, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(14.4, stats.snapshot()!!.fps, 0.001)
        clock.nowMs = start + 4_000
        assertEquals(1360, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(19.2, stats.snapshot()!!.fps, 0.001)
    }

    @Test
    fun `the reading stays stable whatever the poll phase`() {
        listOf(0L, 733L, 1_499L).forEach { phase ->
            val clock = FakeClock()
            val stats = stats(clock)
            clock.nowMs += phase
            val lastPoll = clock.nowMs + 16_000
            runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = lastPoll)

            // Every millisecond from just before a poll to just before the
            // next one: old cells slide out continuously while new ones only
            // land at polls, yet the reading never breathes with the cadence
            // — nor with the read's own phase on the cell lattice.
            for (offset in -300..2_000) {
                clock.nowMs = lastPoll + offset
                val snapshot = stats.snapshot()!!
                assertEquals("bitrate at poll phase $phase, poll $offset ms ago", 1700, snapshot.measuredBitrateKbps)
                assertEquals("fps at poll phase $phase, poll $offset ms ago", 24.0, snapshot.fps, 0.001)
            }
        }
    }

    @Test
    fun `a jittered poll cadence still reads the true rate`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        val stream = SteadyStream(kbps = 1_700, fps = 24, startMs = start)
        baseline(stats, "a", start)
        // Intervals around the loop's 2 s: late and early, none a multiple of
        // the 250 ms cell — some polls cover 7 or 9 cells instead of 8.
        val jitter = listOf(2_003L, 1_996L, 2_017L, 2_000L, 2_009L, 1_991L, 2_024L, 2_004L)
        var at = start
        val reads = mutableListOf<Pair<Long, WhepStreamStats.Snapshot>>()
        repeat(15) { index ->
            at += jitter[index % jitter.size]
            clock.nowMs = at + 2
            poll(stats, stream, "a", at)
            reads += at to stats.snapshot()!!
        }
        // Every poll past the first full window reads the stream's own rate.
        // framesEncoded is an integer counter, so each poll's frame delta
        // carries up to half a frame of quantization — the fps tolerance.
        reads.drop(5).forEach { (polledAt, snapshot) ->
            assertEquals("bitrate at +${polledAt - start} ms", 1700, snapshot.measuredBitrateKbps)
            assertEquals("fps at +${polledAt - start} ms", 24.0, snapshot.fps, 0.25)
        }
    }

    @Test
    fun `the sub-sample interval must divide the window exactly`() {
        // The "always a whole number of cells in the window" property is
        // arithmetic: the window span must be a multiple of the cell.
        WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = 250, windowMs = 5_000)
        WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = 500, windowMs = 5_000)
        listOf(300L, 7L, 0L, -250L).forEach { sub ->
            val rejected = runCatching { WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = sub, windowMs = 5_000) }.isFailure
            assertTrue("sub-sample $sub ms must be refused", rejected)
        }
        assertTrue(runCatching { WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = 250, windowMs = 4_999) }.isFailure)
        assertTrue(runCatching { WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = 250, windowMs = 0) }.isFailure)
        assertTrue(runCatching { WhepStreamStats(pollIntervalMs = 2_000, subSampleMs = 250, windowMs = -5_000) }.isFailure)
        assertTrue(runCatching { WhepStreamStats(pollIntervalMs = 0) }.isFailure)
        assertTrue(runCatching { WhepStreamStats(pollIntervalMs = -2_000) }.isFailure)
    }

    @Test
    fun `the defaults are StreamDefaults' window and the quarter-second cell`() {
        assertEquals(5_000L, StreamDefaults.STATS_WINDOW_MS)
        assertEquals(250L, StreamDefaults.STATS_SUB_SAMPLE_MS)
        // The default cell count of one window: the arithmetic the stability
        // property rests on, so it is pinned rather than assumed — and the
        // defaults must satisfy the constructor's whole-cells rule.
        assertEquals(0L, StreamDefaults.STATS_WINDOW_MS % StreamDefaults.STATS_SUB_SAMPLE_MS)
        assertEquals(20L, StreamDefaults.STATS_WINDOW_MS / StreamDefaults.STATS_SUB_SAMPLE_MS)
        WhepStreamStats(pollIntervalMs = 2_000)
    }

    // ── counter deltas ──

    @Test
    fun `an observation answers the delta it accounted, or nothing when it measured nothing`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        stats.onSessionAdmitted("a")

        // The baseline stores the totals and measures nothing…
        assertNull(stats.onStats("a", start, bytesSent = 900_000, framesEncoded = 400))
        // …so does an observation still inside the baseline's cell…
        assertNull(stats.onStats("a", start + 100, bytesSent = 900_500, framesEncoded = 401))
        // …and the next one carries the whole interval since the baseline:
        // 425_000 bytes / 48 frames of 1700 kbps / 24 fps over 2 s.
        val accounted = stats.onStats("a", start + 2_000, bytesSent = 1_325_000, framesEncoded = 448)!!
        assertEquals(2_000L, accounted.intervalMs)
        assertEquals(425_000L, accounted.bytes)
        assertEquals(48L, accounted.frames)
        assertEquals(680, stats.snapshot()!!.measuredBitrateKbps)

        // A counter that did not advance is accounted too — as silence.
        val stalled = stats.onStats("a", start + 4_000, bytesSent = 1_325_000, framesEncoded = 448)!!
        assertEquals(2_000L, stalled.intervalMs)
        assertEquals(0L, stalled.bytes)
        assertEquals(0L, stalled.frames)
    }

    @Test
    fun `a counter that did not advance records silence and re-baselines`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = start + 6_000)
        assertEquals(1700, stats.snapshot()!!.measuredBitrateKbps)

        // The encoder stalled: two polls report the last totals unchanged
        // (3 × 425 000 bytes, 3 × 48 frames after 6 s of 1700 kbps / 24 fps).
        repeat(2) {
            clock.nowMs += 2_000
            stats.onStats("a", clock.nowMs, bytesSent = 1_275_000, framesEncoded = 144, codec = "vp8", width = 600, height = 800)
        }
        // The stall's cells are recorded as silence, so the reading steps
        // down with the real cells that are left: 4 of the 20.
        assertEquals(340, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(4.8, stats.snapshot()!!.fps, 0.001)
        clock.nowMs = start + 10_876
        assertEquals(0, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(0.0, stats.snapshot()!!.fps, 0.0)

        // …and the next real delta measures only what came after the stall:
        // 425 000 bytes / 48 frames since the last (unchanged) totals.
        clock.nowMs = start + 12_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_700_000, framesEncoded = 192, codec = "vp8", width = 600, height = 800)
        assertEquals(680, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(9.6, stats.snapshot()!!.fps, 0.001)
    }

    @Test
    fun `a counter that went backwards is a reset, never a negative sample`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = start + 6_000)

        // libwebrtc replaced the outbound stream (new SSRC): the totals
        // restart from a lower value. The interval reads as silence — 8 of
        // the window's 20 cells — while the 12 pre-reset cells in it still
        // count the bytes that really went out.
        clock.nowMs = start + 8_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_000, framesEncoded = 3, codec = "vp8", width = 600, height = 800)
        assertEquals(1020, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(14.4, stats.snapshot()!!.fps, 0.001)

        // Nothing negative, nothing held: once the pre-reset cells are out
        // the lane reads zero, not a frozen last value.
        clock.nowMs = start + 14_876
        assertEquals(0, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(0.0, stats.snapshot()!!.fps, 0.0)
    }

    @Test
    fun `after a reset the next delta is measured against the reset totals`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = start + 6_000)
        clock.nowMs = start + 8_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_000, framesEncoded = 3, codec = "vp8", width = 600, height = 800)

        // 425 000 bytes / 48 frames since the reset — not against the
        // pre-reset totals, which would read as another (negative) reset.
        clock.nowMs = start + 10_000
        stats.onStats("a", clock.nowMs, bytesSent = 426_000, framesEncoded = 51, codec = "vp8", width = 600, height = 800)
        // 4 pre-reset cells + 8 silent + 8 since the reset = 12 real cells.
        assertEquals(1020, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(14.4, stats.snapshot()!!.fps, 0.001)
    }

    @Test
    fun `two observations inside one cell are deferred, not divided by a zero interval`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        baseline(stats, "a", start)
        // 100 ms later: the poll has not left the baseline's cell, so its
        // delta waits for the next poll instead of landing on a sample that
        // would double-book the cell.
        clock.nowMs = start + 100
        stats.onStats("a", clock.nowMs, bytesSent = 25_000, framesEncoded = 2)
        assertEquals(0, stats.snapshot()!!.measuredBitrateKbps)

        // The next poll carries both deltas, spread over the cells crossed since the baseline.
        clock.nowMs = start + 2_000
        stats.onStats("a", clock.nowMs, bytesSent = 425_000, framesEncoded = 48)
        assertEquals(680, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(9.6, stats.snapshot()!!.fps, 0.001)
    }

    @Test
    fun `a clock jump forward is bounded to one window of cells`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        val stream = SteadyStream(kbps = 1_700, fps = 24, startMs = start)
        baseline(stats, "a", start)
        // The wall clock jumps an hour: the delta is real, but only the last
        // window's worth of cells can still be inside the window — the
        // spread never walks a cell per skipped millisecond.
        clock.nowMs = start + 3_600_000
        poll(stats, stream, "a", clock.nowMs)
        val snapshot = stats.snapshot()!!
        assertEquals(1700, snapshot.measuredBitrateKbps)
        assertEquals(24.0, snapshot.fps, 0.001)
    }

    @Test
    fun `a clock that steps backwards restarts the session's accounting`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = start + 6_000)

        // The clock steps back a second: cells already stamped would now sit
        // in the "future" and over-count until time caught up, so this report
        // starts the accounting over — nothing measured, the facts kept.
        clock.nowMs = start + 5_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_275_000, framesEncoded = 144, codec = "vp8", width = 600, height = 800)
        val restarted = stats.snapshot()!!
        assertEquals(0, restarted.measuredBitrateKbps)
        assertEquals(0.0, restarted.fps, 0.0)
        assertEquals("vp8", restarted.codec)

        // The next report is measured from that one, over the interval time
        // really covered: 425 000 bytes / 48 frames in 3 s.
        clock.nowMs = start + 8_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_700_000, framesEncoded = 192, codec = "vp8", width = 600, height = 800)
        assertEquals(680, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(9.6, stats.snapshot()!!.fps, 0.001)
    }

    // ── several sessions ──

    @Test
    fun `two sessions are summed`() {
        val clock = FakeClock()
        val stats = stats(clock)
        runTwoSessions(clock, stats, aKbps = 1_700, bKbps = 1_000)

        val snapshot = stats.snapshot()!!
        assertEquals(2700, snapshot.measuredBitrateKbps)
        assertEquals(39.0, snapshot.fps, 0.001)
        // Both describe the lane the same way here.
        assertEquals("vp8", snapshot.codec)
        assertEquals(600, snapshot.width)
    }

    @Test
    fun `sessions polled out of phase still sum to the lane's true rate at every instant`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        val a = SteadyStream(kbps = 1_700, fps = 24, startMs = start)
        val b = SteadyStream(kbps = 1_000, fps = 15, startMs = start)
        baseline(stats, "a", start)
        // b joins mid-interval, so its polls land 733 ms off a's for good.
        clock.nowMs = start + 733
        baseline(stats, "b", clock.nowMs)

        val aPolls = (2_000..18_000 step 2_000).map { start + it }
        val bPolls = (2_733..18_733 step 2_000).map { start + it }
        for (offset in 0..20_000) {
            clock.nowMs = start + offset
            if (clock.nowMs in aPolls) poll(stats, a, "a", clock.nowMs)
            if (clock.nowMs in bPolls) poll(stats, b, "b", clock.nowMs)
            if (offset < 10_000) continue // both windows are still filling
            val snapshot = stats.snapshot()!!
            assertEquals("bitrate at +$offset ms", 2700, snapshot.measuredBitrateKbps)
            // b's 15 fps is 3.75 frames a cell, and framesEncoded is an
            // integer counter: each poll's delta carries up to one frame of
            // quantization.
            assertEquals("fps at +$offset ms", 39.0, snapshot.fps, 0.5)
        }
    }

    @Test
    fun `removal stops a session's contribution`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val (a, b) = runTwoSessions(clock, stats, aKbps = 1_700, bKbps = 1_000)
        assertEquals(2700, stats.snapshot()!!.measuredBitrateKbps)

        // The viewer left: its samples leave the sum at once, not as they
        // would age out over the next 5 s.
        stats.onSessionRemoved("a")
        val remaining = stats.snapshot()!!
        assertEquals(1000, remaining.measuredBitrateKbps)
        assertEquals(15.0, remaining.fps, 0.001)

        // A report still in flight for the removed session adds nothing —
        // not even its held tail.
        clock.nowMs += 2_000
        poll(stats, a, "a", clock.nowMs)
        assertEquals(1000, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(15.0, stats.snapshot()!!.fps, 0.001)

        // Re-admitting the id (a fresh session) starts from a clean baseline:
        // polled like any viewer, it adds nothing until its second report.
        stats.onSessionAdmitted("a")
        clock.nowMs += 2_000
        poll(stats, b, "b", clock.nowMs)
        poll(stats, a, "a", clock.nowMs)
        assertEquals(1000, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(15.0, stats.snapshot()!!.fps, 0.001)
    }

    @Test
    fun `removing the last session withdraws the snapshot`() {
        val clock = FakeClock()
        val stats = stats(clock)
        runTwoSessions(clock, stats, aKbps = 1_700, bKbps = 1_000)
        stats.onSessionRemoved("a")
        assertNotNull(stats.snapshot())
        stats.onSessionRemoved("b")
        assertNull(stats.snapshot())
    }

    @Test
    fun `decay to 0 when stats stop, keeping the last known codec and size`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val lastPoll = clock.nowMs + 10_000
        runPolls(clock, stats, "a", kbps = 1_700, fps = 24, untilMs = lastPoll)
        assertEquals(1700, stats.snapshot()!!.measuredBitrateKbps)

        // No poll lands for a while. The tail stays held for one poll
        // interval (the next poll may just be late)…
        clock.nowMs = lastPoll + 2_251
        assertEquals(1700, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(24.0, stats.snapshot()!!.fps, 0.001)

        // …then the hold is released and the recorded cells age out
        // quarter-second by quarter-second to 0 — never a frozen value.
        clock.nowMs = lastPoll + 2_339
        assertEquals(1615, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(22.8, stats.snapshot()!!.fps, 0.001)
        clock.nowMs = lastPoll + 4_876
        assertEquals(765, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(10.8, stats.snapshot()!!.fps, 0.001)
        clock.nowMs = lastPoll + 7_089
        assertEquals(0, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals(0.0, stats.snapshot()!!.fps, 0.0)

        // …while the session still exists and keeps describing the lane.
        val silent = stats.snapshot()!!
        assertEquals("vp8", silent.codec)
        assertEquals(600, silent.width)
        assertEquals(800, silent.height)
    }

    // ── the descriptive facts ──

    @Test
    fun `codec and size carry the last known values`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        baseline(stats, "a", start)
        clock.nowMs = start + 2_000
        stats.onStats("a", clock.nowMs, bytesSent = 425_000, framesEncoded = 48, codec = "vp8", width = 600, height = 800)

        // A report without frame-size members (libwebrtc omits them until
        // frames were encoded) and without a codec id says nothing new.
        clock.nowMs = start + 4_000
        stats.onStats("a", clock.nowMs, bytesSent = 850_000, framesEncoded = 96, codec = null, width = null, height = null)
        var snapshot = stats.snapshot()!!
        assertEquals("vp8", snapshot.codec)
        assertEquals(600, snapshot.width)
        assertEquals(800, snapshot.height)

        // Zero dimensions are "unknown" too, not a real geometry.
        clock.nowMs = start + 6_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_275_000, framesEncoded = 144, codec = "vp8", width = 0, height = 0)
        assertEquals(600, stats.snapshot()!!.width)
        assertEquals(800, stats.snapshot()!!.height)

        // A renegotiated stream updates them.
        clock.nowMs = start + 8_000
        stats.onStats("a", clock.nowMs, bytesSent = 1_700_000, framesEncoded = 192, codec = "h264", width = 1280, height = 720)
        snapshot = stats.snapshot()!!
        assertEquals("h264", snapshot.codec)
        assertEquals(1280, snapshot.width)
        assertEquals(720, snapshot.height)
    }

    @Test
    fun `unknown descriptive facts start unknown`() {
        val clock = FakeClock()
        val stats = stats(clock)
        stats.onSessionAdmitted("a")
        stats.onStats("a", clock.nowMs, bytesSent = 0, framesEncoded = 0)
        val snapshot = stats.snapshot()!!
        assertNull(snapshot.codec)
        assertNull(snapshot.width)
        assertNull(snapshot.height)
    }

    @Test
    fun `the dominant contributor describes a multi-viewer lane`() {
        val clock = FakeClock()
        val stats = stats(clock)
        val start = clock.nowMs
        val a = SteadyStream(kbps = 400, fps = 24, startMs = start)
        val b = SteadyStream(kbps = 1_000, fps = 30, startMs = start)
        baseline(stats, "a", start)
        baseline(stats, "b", start)
        var at = start
        while (at + 2_000 <= start + 10_000) {
            at += 2_000
            clock.nowMs = at
            poll(stats, a, "a", at, codec = "h264", width = 1280, height = 720)
            clock.nowMs = at + 1
            poll(stats, b, "b", clock.nowMs, codec = "vp8", width = 600, height = 800)
        }
        // b moves the most bytes, so its codec and geometry answer for the lane.
        val snapshot = stats.snapshot()!!
        assertEquals(1400, snapshot.measuredBitrateKbps)
        assertEquals("vp8", snapshot.codec)
        assertEquals(600, snapshot.width)
        assertEquals(800, snapshot.height)

        // When nothing moves any more (a silent lane) the most recently
        // observed session still describes it.
        clock.nowMs += 8_000
        assertEquals(0, stats.snapshot()!!.measuredBitrateKbps)
        assertEquals("vp8", stats.snapshot()!!.codec)
    }
}
