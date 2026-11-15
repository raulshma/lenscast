package com.raulshma.lenscast.companion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the companion ladder's transitions against the web ladder's semantics
 * (web/src/video/playerLadder.ts), minus the intentionally dropped h264
 * WebCodecs rung — and with the rung ORDER deliberately changed: the TS
 * ladder is whep → h264 → mjpeg → hls (mjpeg above hls for latency), while
 * the companion ladder demotes WHEP → HLS → MJPEG so a demotion keeps muxed
 * A/V available one rung longer. The cooldown is this port's own addition —
 * the TS has none (its failed-rung flags are caller-owned booleans) — pinned
 * here as the 30s post-demote ineligibility window documented on
 * [PlayerLadder.DEMOTE_COOLDOWN_MS].
 */
class PlayerLadderTest {

    private var now = 0L

    private fun ladder(start: PlaybackRung = PlaybackRung.WHEP): PlayerLadder =
        PlayerLadder(start = start, nowMs = { now })

    // ── demote: one rung down, MJPEG terminal ──

    @Test
    fun `demote walks whep then hls then stays at mjpeg`() {
        val rung = ladder()
        assertEquals(PlaybackRung.WHEP, rung.current)
        rung.demote()
        assertEquals(PlaybackRung.HLS, rung.current)
        rung.demote()
        assertEquals(PlaybackRung.MJPEG, rung.current)
        rung.demote()
        assertEquals(PlaybackRung.MJPEG, rung.current)
    }

    @Test
    fun `terminal demote leaves mjpeg without cooling it`() {
        val rung = ladder(start = PlaybackRung.MJPEG)
        rung.demote()
        assertEquals(PlaybackRung.MJPEG, rung.current)
        assertFalse(rung.isCoolingDown(PlaybackRung.MJPEG))
    }

    @Test
    fun `demote stamps the abandoned rung with cooldown`() {
        val rung = ladder()
        rung.demote()
        assertTrue(rung.isCoolingDown(PlaybackRung.WHEP))
        assertFalse(rung.isCoolingDown(PlaybackRung.HLS))
    }

    // ── cycle: manual wrap, cooldown-blind ──

    @Test
    fun `cycle wraps around every rung`() {
        val rung = ladder()
        rung.cycle()
        assertEquals(PlaybackRung.HLS, rung.current)
        rung.cycle()
        assertEquals(PlaybackRung.MJPEG, rung.current)
        rung.cycle()
        assertEquals(PlaybackRung.WHEP, rung.current)
    }

    @Test
    fun `cycle ignores cooldown the manual tap overrides what the ladder learned`() {
        val rung = ladder()
        rung.demote() // WHEP cooling, now at HLS
        rung.cycle() // → MJPEG
        rung.cycle() // → WHEP, which is still cooling — the manual wrap must not be filtered
        assertEquals(PlaybackRung.WHEP, rung.current)
    }

    // ── reset: best eligible rung from `preferred` ──

    @Test
    fun `reset lands on the preferred rung when nothing is cooling`() {
        val rung = ladder(start = PlaybackRung.MJPEG)
        rung.reset()
        assertEquals(PlaybackRung.WHEP, rung.current)
    }

    @Test
    fun `reset honors a non-default preferred rung`() {
        val rung = ladder(start = PlaybackRung.MJPEG)
        rung.reset(PlaybackRung.HLS)
        assertEquals(PlaybackRung.HLS, rung.current)
    }

    @Test
    fun `reset skips the just-demoted rung and lands one below`() {
        val rung = ladder()
        rung.demote() // WHEP failed → HLS; WHEP cooling
        rung.reset()
        assertEquals(PlaybackRung.HLS, rung.current)
    }

    @Test
    fun `reset keeps current when every rung is still cooling`() {
        val rung = ladder()
        rung.demote() // → HLS, WHEP cooling
        rung.demote() // → MJPEG, HLS cooling
        rung.reset()
        assertEquals(PlaybackRung.MJPEG, rung.current)
    }

    @Test
    fun `reset re-attempts the demoted rung once its cooldown expired`() {
        val rung = ladder()
        rung.demote() // WHEP cooling for 30s
        rung.reset()
        assertEquals(PlaybackRung.HLS, rung.current)

        now = PlayerLadder.DEMOTE_COOLDOWN_MS + 1
        rung.reset()
        assertEquals(PlaybackRung.WHEP, rung.current)
    }

    @Test
    fun `cooldown remaining reports the window and drains to zero`() {
        val rung = ladder()
        rung.demote()
        val remaining = rung.cooldownRemainingMs(PlaybackRung.WHEP)
        assertTrue(remaining in 1..PlayerLadder.DEMOTE_COOLDOWN_MS)

        now = PlayerLadder.DEMOTE_COOLDOWN_MS
        assertEquals(0L, rung.cooldownRemainingMs(PlaybackRung.WHEP))
        assertFalse(rung.isCoolingDown(PlaybackRung.WHEP))
    }

    @Test
    fun `fresh demote re-arms the cooldown on the same rung`() {
        val rung = ladder()
        rung.demote() // WHEP cooling
        now = PlayerLadder.DEMOTE_COOLDOWN_MS + 1 // expired
        rung.reset() // back to WHEP (eligible again)
        assertEquals(PlaybackRung.WHEP, rung.current)
        rung.demote() // failed again — must cool again, not inherit the stale expiry
        now = PlayerLadder.DEMOTE_COOLDOWN_MS // exactly at the old expiry, inside the new window
        assertTrue(rung.isCoolingDown(PlaybackRung.WHEP))
    }
}
