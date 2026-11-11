package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM pin on the wall-clock RTP timestamp clocks ([RtpEpoch] +
 * [RtpTimestampClock]) that replace the fixed per-frame increment. The
 * regression at the heart of issue #17: a delivery stall must advance the
 * timestamp by the stalled time, not by one frame's worth.
 */
class RtpTimestampClockTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private fun epoch(clock: FakeClock) = RtpEpoch { clock.nowMs }

    // ── Epoch anchoring ──

    @Test
    fun `the epoch anchors lazily on the first elapsed read`() {
        val clock = FakeClock(nowMs = 5_000L)
        val e = epoch(clock)
        assertNull(e.elapsedMsOrNull())
        clock.advance(2_000L)
        // Still unanchored: the peek never moves the zero point.
        assertNull(e.elapsedMsOrNull())
        assertEquals(0L, e.elapsedMs())
        clock.advance(300L)
        assertEquals(300L, e.elapsedMs())
        assertEquals(300L, e.elapsedMsOrNull())
    }

    @Test
    fun `reset returns the epoch to unanchored`() {
        val clock = FakeClock()
        val e = epoch(clock)
        e.elapsedMs()
        clock.advance(1_000L)
        assertEquals(1_000L, e.elapsedMs())
        e.reset()
        assertNull(e.elapsedMsOrNull())
        assertEquals(0L, e.elapsedMs())
    }

    // ── Wall-clock tracking ──

    @Test
    fun `timestamps track elapsed time on an irregular cadence`() {
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        assertEquals(0L, video.next()) // anchors at t=0
        clock.advance(40L)
        assertEquals(3_600L, video.next())
        clock.advance(35L)
        assertEquals(6_750L, video.next())
        clock.advance(125L)
        assertEquals(18_000L, video.next())
    }

    @Test
    fun `regression - a 500ms stall jumps 45000 ticks not one frame increment`() {
        // The old fixed 90000/fps increment advanced 3000 ticks per delivered
        // AU whatever the gap: players froze through the stall and then
        // fast-forwarded the burst. The wall clock owes the full 500 ms.
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        video.next() // t=0
        clock.advance(33L)
        val before = video.next() // t=33ms
        clock.advance(500L)
        val after = video.next() // t=533ms
        assertEquals(45_000L, after - before)
    }

    @Test
    fun `an adaptive fps change rides through without a timestamp kink`() {
        // 24 fps cadence, then the adaptive controller drops to ~10 fps:
        // the timestamps keep owing real time, not per-frame steps.
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        video.next() // anchors at t=0
        repeat(4) {
            clock.advance(41L)
            video.next()
        }
        val atSwitch = video.current
        repeat(3) {
            clock.advance(100L)
            video.next()
        }
        assertEquals(300L * 90L, video.current - atSwitch)
    }

    // ── Monotonic clamp ──

    @Test
    fun `a backwards clock still yields strictly increasing timestamps`() {
        val clock = FakeClock(nowMs = 10_000L)
        val video = RtpTimestampClock(90_000L, epoch(clock))
        val first = video.next()
        clock.advance(100L)
        val second = video.next()
        clock.nowMs -= 5_000L // the clock steps back
        val third = video.next()
        assertTrue(third > second)
        assertEquals(second + 1, third)
        assertTrue(second > first)
    }

    @Test
    fun `two aus inside one millisecond advance by one tick`() {
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        assertEquals(0L, video.next())
        assertEquals(1L, video.next())
        assertEquals(2L, video.next())
    }

    // ── A/V sync: one shared epoch, two rates ──

    @Test
    fun `audio and video on one epoch stay proportional to their clock rates`() {
        val clock = FakeClock()
        val e = epoch(clock)
        val video = RtpTimestampClock(90_000L, e)
        val audio = RtpTimestampClock(48_000L, e)
        video.next() // anchors the shared epoch
        clock.advance(1_000L)
        assertEquals(90_000L, video.next())
        assertEquals(48_000L, audio.next())
        clock.advance(500L)
        assertEquals(135_000L, video.next())
        assertEquals(72_000L, audio.next())
    }

    @Test
    fun `audio advances one au of samples at the nominal cadence`() {
        // The retired AUDIO_TIMESTAMP_INCREMENT contract in wall-clock form:
        // one 1024-sample AU at 32 kHz spans exactly 32 ms, so the clock
        // owes exactly 1024 ticks per AU at the nominal cadence.
        val clock = FakeClock()
        val audio = RtpTimestampClock(32_000L, epoch(clock))
        audio.next()
        clock.advance(32L)
        assertEquals(1_024L, audio.next())
        clock.advance(32L)
        assertEquals(2_048L, audio.next())
    }

    // ── peekNext: the non-anchoring RTP-Info lower bound ──

    @Test
    fun `peek on a fresh clock does not anchor and reads zero`() {
        val clock = FakeClock(nowMs = 7_000L)
        val e = epoch(clock)
        val video = RtpTimestampClock(90_000L, e)
        assertEquals(0L, video.peekNext())
        assertNull(e.elapsedMsOrNull())
        // The first next() still anchors at its own call time.
        clock.advance(100L)
        assertEquals(0L, video.next())
    }

    @Test
    fun `peek reports what next would return without advancing`() {
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        video.next()
        clock.advance(250L)
        assertEquals(22_500L, video.peekNext())
        assertEquals(22_500L, video.peekNext())
        assertEquals(0L, video.current)
        assertEquals(22_500L, video.next())
    }

    @Test
    fun `peek keeps the monotonic clamp`() {
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        video.next()
        // Same millisecond: next() would owe last+1, and so does the peek.
        assertEquals(1L, video.peekNext())
    }

    // ── reset and long-run growth ──

    @Test
    fun `reset clocks restart from zero on the reset epoch`() {
        val clock = FakeClock()
        val e = epoch(clock)
        val video = RtpTimestampClock(90_000L, e)
        clock.advance(10_000L)
        video.next()
        e.reset()
        video.reset()
        clock.advance(500L)
        assertEquals(0L, video.next())
        clock.advance(100L)
        assertEquals(9_000L, video.next())
    }

    @Test
    fun `growth past 2 to the 32 stays a long - the wire writers mask`() {
        val clock = FakeClock()
        val video = RtpTimestampClock(90_000L, epoch(clock))
        video.next()
        clock.advance(50_000_000L) // ~13.9 h at 90 kHz = 4.5e9 ticks
        val ts = video.next()
        assertEquals(4_500_000_000L, ts)
        assertTrue(ts > 0xFFFF_FFFFL)
    }
}