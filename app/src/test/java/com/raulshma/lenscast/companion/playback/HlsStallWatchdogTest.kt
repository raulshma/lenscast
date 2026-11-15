package com.raulshma.lenscast.companion.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The silent-strand verdict, pinned pure: a live HLS player parked in
 * BUFFERING with playlist refreshes but zero segment loads (the loopback
 * E2E's six-minute "Connecting…") must trip [HlsStallWatchdog] so the rung
 * can demote — media3 surfaces no onPlayerError for it. The healthy live
 * edge (READY at the playlist end) and a buffering-through-a-stall player
 * (segment loads resume) must never trip.
 */
class HlsStallWatchdogTest {

    private var nowMs = 100_000L

    @Before
    fun setUp() {
        nowMs = 100_000L
    }

    private fun watchdog(): HlsStallWatchdog = HlsStallWatchdog(
        nowMs = { nowMs },
        stallAfterMs = HlsStallWatchdog.STALL_AFTER_MS,
    )

    @Test
    fun `a fresh player buffering with no media ever loaded trips at the threshold`() {
        // The strand pose: prepare lands before the window; every playlist
        // refresh (≈3s cadence) consults the watchdog while nothing loads.
        val w = watchdog()
        repeat(3) {
            nowMs += 3_000L
            assertFalse(w.onPlaylistLoaded(Player.STATE_BUFFERING))
        }
        nowMs += 3_000L
        assertTrue(w.onPlaylistLoaded(Player.STATE_BUFFERING))
    }

    @Test
    fun `the healthy live edge waits in ready and never trips`() {
        val w = watchdog()
        nowMs += HlsStallWatchdog.STALL_AFTER_MS * 10
        assertFalse(w.onPlaylistLoaded(Player.STATE_READY))
    }

    @Test
    fun `segment loads re-arm the window while buffering through a stall`() {
        val w = watchdog()
        // Playing, then the encoder stalls: buffered media runs out, the
        // player buffers, loads stop — but the stall ends inside the window.
        nowMs += 60_000L
        w.onMediaLoaded()
        nowMs += HlsStallWatchdog.STALL_AFTER_MS - 1_000L
        assertFalse(w.onPlaylistLoaded(Player.STATE_BUFFERING))
        nowMs += 1_000L
        assertTrue(w.onPlaylistLoaded(Player.STATE_BUFFERING))
        // Recovery: the stall ends, media flows, and the trip clears.
        w.onMediaLoaded()
        assertFalse(w.onPlaylistLoaded(Player.STATE_READY))
        nowMs += HlsStallWatchdog.STALL_AFTER_MS + 5_000L
        // A recovered rung may strand again later — the window re-armed.
        assertTrue(w.onPlaylistLoaded(Player.STATE_BUFFERING))
    }

    @Test
    fun `a trip latches until the next media load`() {
        // One strand, one error: the refresh cadence keeps consulting the
        // watchdog for the remaining lifetime of the doomed session.
        val w = watchdog()
        nowMs += HlsStallWatchdog.STALL_AFTER_MS + 1_000L
        assertTrue(w.onPlaylistLoaded(Player.STATE_BUFFERING))
        nowMs += 3_000L
        assertFalse(w.onPlaylistLoaded(Player.STATE_BUFFERING))
        w.onMediaLoaded()
        nowMs += HlsStallWatchdog.STALL_AFTER_MS + 1_000L
        assertTrue(w.onPlaylistLoaded(Player.STATE_BUFFERING))
    }
}
