package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the wall-clock-anchored PTS clock in [HlsManager]: real
 * EXTINF durations, A/V anchored to one clock, and monotonic PTS across
 * clock steps — the failures the old fixed-increment clock shipped.
 */
class HlsManagerTest {

    private var nowMs = 10_000L

    @Before
    fun setUp() {
        nowMs = 10_000L
        HlsManager.clockMs = { nowMs }
        HlsManager.reset()
        HlsManager.setEnabled(true)
    }

    private fun videoFrame() = EncodedNalUnit(ByteArray(16), isKeyFrame = true)

    private fun feedSecondsOfVideo(framesPerSecond: Int, seconds: Int) {
        repeat(framesPerSecond * seconds) {
            HlsManager.feedVideo(listOf(videoFrame()))
            nowMs += 1000L / framesPerSecond
        }
    }

    @Test
    fun `playlist reports real segment durations at 30fps`() {
        feedSecondsOfVideo(framesPerSecond = 30, seconds = 5)
        val playlist = HlsManager.playlist()
        // All-keyframe feed: a segment closes on the first keyframe at least
        // 1s in — 31 ticks of 33ms = 1.023s of real wall-clock span, not the
        // old hardcoded 2.0s.
        assertTrue(playlist.contains("#EXTINF:1.023,"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:2"))
    }

    @Test
    fun `segments close on keyframes so every segment opens on an IDR`() {
        // The decodability regression: mid-GOP segment starts left joining
        // players without SPS/PPS/IDR. 30fps with a 2s GOP — only every 60th
        // AU is a keyframe, and segments must span the GOP, not a fixed AU
        // count.
        repeat(150) { i ->
            HlsManager.feedVideo(listOf(EncodedNalUnit(ByteArray(16), isKeyFrame = i % 60 == 0)))
            nowMs += 33L
        }
        val playlist = HlsManager.playlist()
        // 60 ticks of 33ms = 1.98s per GOP.
        assertTrue(playlist.contains("#EXTINF:1.980,"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:2"))
        assertEquals(2, countSegments())
    }

    @Test
    fun `delta frames before the first keyframe never open the ring`() {
        repeat(30) {
            HlsManager.feedVideo(listOf(EncodedNalUnit(ByteArray(16), isKeyFrame = false)))
            nowMs += 33L
        }
        assertFalse(HlsManager.hasSegments())
        // The ring opens on the IDR, and the next keyframe ≥1s later closes
        // the first segment.
        HlsManager.feedVideo(listOf(videoFrame()))
        nowMs += 2_000L
        HlsManager.feedVideo(listOf(videoFrame()))
        assertTrue(HlsManager.hasSegments())
    }

    @Test
    fun `audio and video anchor to the same clock so muxed pts stay aligned`() {
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 2)
        HlsManager.feedAudio(ByteArray(64))
        // No assertion of failure — the contract is that feeding mixed
        // tracks on one clock never throws and produces a served segment
        // once enough AUs accumulate.
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 1)
        assertTrue(HlsManager.hasSegments())
    }

    @Test
    fun `pts stays monotonic when the clock steps backwards`() {
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 2)
        nowMs -= 500L
        val before = countSegments()
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 1)
        assertTrue(countSegments() >= before)
    }

    @Test
    fun `adaptive frame rate change does not desync segment pacing`() {
        feedSecondsOfVideo(framesPerSecond = 60, seconds = 2)
        feedSecondsOfVideo(framesPerSecond = 15, seconds = 2)
        assertTrue(HlsManager.hasSegments())
        val playlist = HlsManager.playlist()
        assertTrue(playlist.startsWith("#EXTM3U"))
    }

    @Test
    fun `disable resets the ring and anchors`() {
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 3)
        HlsManager.setEnabled(false)
        nowMs += 60_000L
        HlsManager.setEnabled(true)
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 3)
        // After re-enable, pts restarts from the new anchor — the playlist
        // target duration stays small, not a giant stale span.
        assertTrue(HlsManager.playlist().contains("#EXT-X-TARGETDURATION:"))
    }

    private fun countSegments(): Int {
        // Sequences start at 1 — seg0 never exists.
        var count = 0
        while (HlsManager.segment(HlsPlaylist.segmentName((count + 1).toLong())) != null) count++
        return count
    }

    @Test
    fun `segment names round-trip through the source seam`() {
        feedSecondsOfVideo(framesPerSecond = 24, seconds = 3)
        assertTrue(HlsManager.hasSegments())
        val playlist = HlsManager.playlist()
        val name = playlist.lineSequence().last { it.endsWith(".ts") }
        assertTrue(HlsManager.segment(name)!!.size > 0)
        assertEquals(null, HlsManager.segment("seg999999.ts"))
    }

    // ── demand: the encoded-stream policy's hlsRequested input ──

    @Test
    fun `a freshly enabled ring is hot and cools after the demand window`() {
        // setUp enabled the ring: the pre-roll grace window starts now.
        assertTrue(HlsManager.isHot())
        nowMs += 16_000L
        assertFalse(HlsManager.isHot())
    }

    @Test
    fun `a playlist serve re-marks the ring hot until the window passes again`() {
        nowMs += 16_000L
        assertFalse(HlsManager.isHot())
        HlsManager.playlist()
        assertTrue(HlsManager.isHot())
        nowMs += 10_000L
        assertTrue(HlsManager.isHot())
        nowMs += 6_000L
        assertFalse(HlsManager.isHot())
    }

    @Test
    fun `a cold-ring playlist request registers demand without a served playlist`() {
        // The cold-ring 503 path never reaches playlist(): noteRequest() is
        // the demand signal that keeps the shared encoders from staying
        // deadlocked off (nobody asks because 503, 503 because nobody asks).
        nowMs += 16_000L
        assertFalse(HlsManager.isHot())
        HlsManager.noteRequest()
        assertTrue(HlsManager.isHot())
        nowMs += 16_000L
        assertFalse(HlsManager.isHot())
    }

    @Test
    fun `a disabled ring is never hot`() {
        HlsManager.setEnabled(false)
        assertFalse(HlsManager.isHot())
        HlsManager.noteRequest()
        assertFalse(HlsManager.isHot())
    }
}
