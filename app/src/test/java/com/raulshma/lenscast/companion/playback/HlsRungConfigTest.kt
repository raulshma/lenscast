package com.raulshma.lenscast.companion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The hls.js → ExoPlayer translation, pinned pure (no player instantiation —
 * DefaultLoadControl's own construction-time asserts fire on-device only, so
 * [HlsRungConfig.validate] is the pure twin the tests can hold). Each default
 * names the StreamPreview.tsx setting it translates; retuning a value here
 * must be a deliberate hls.js-parity decision, not a drift.
 */
class HlsRungConfigTest {

    @Test
    fun `defaults translate the streampreview hls tuning`() {
        val config = HlsRungConfig.live()
        // backBufferLength: 10 — seconds kept behind, converted to ms.
        assertEquals(10_000L, config.backBufferMs)
        // liveSyncDurationCount: 2 — join 2 segments (2s each) from the live edge.
        assertEquals(4_000L, config.targetOffsetMs)
        // No hls.js bounds to mirror — 1..4 segments around the target.
        assertEquals(2_000L, config.minOffsetMs)
        assertEquals(8_000L, config.maxOffsetMs)
        // maxBufferLength: 6 — at most 6s of forward buffer (modest memory).
        assertEquals(6_000L, config.maxBufferMs)
        assertEquals(4_000L, config.minBufferMs)
        // Start (and resume) after one buffered segment.
        assertEquals(2_000L, config.bufferForPlaybackMs)
        assertEquals(2_000L, config.bufferForPlaybackAfterRebufferMs)
        config.validate() // the defaults satisfy DefaultLoadControl's invariants
    }

    @Test
    fun `the live offset target sits inside its bounds`() {
        val config = HlsRungConfig.live()
        assertTrue(config.minOffsetMs <= config.targetOffsetMs)
        assertTrue(config.targetOffsetMs <= config.maxOffsetMs)
    }

    @Test
    fun `validate refuses a forward buffer below its floor`() {
        val config = HlsRungConfig.live().copy(minBufferMs = 7_000L)
        try {
            config.validate()
            fail("minBufferMs above maxBufferMs must not validate")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("minBufferMs"))
        }
    }

    @Test
    fun `validate refuses a start margin above the floor`() {
        val config = HlsRungConfig.live().copy(bufferForPlaybackMs = 5_000L)
        try {
            config.validate()
            fail("bufferForPlaybackMs above minBufferMs must not validate")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("bufferForPlaybackMs"))
        }
    }

    @Test
    fun `validate refuses inverted live offset bounds`() {
        val config = HlsRungConfig.live().copy(minOffsetMs = 9_000L)
        try {
            config.validate()
            fail("a target outside its bounds must not validate")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("live offset"))
        }
    }

    @Test
    fun `a copy keeps the mapping shape so retunes stay validated`() {
        // The retune pattern wave 3 would use: copy + validate, never raw.
        val tuned = HlsRungConfig.live().copy(bufferForPlaybackAfterRebufferMs = 3_000L)
        tuned.validate()
        assertEquals(3_000L, tuned.bufferForPlaybackAfterRebufferMs)
        assertEquals("an untouched knob keeps the hls.js translation", 6_000L, tuned.maxBufferMs)
    }
}
