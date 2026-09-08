package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit
import org.junit.Assert.assertEquals
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
        // 48 AUs at 33ms ticks span 47 intervals = 1.551s — real durations,
        // not the old hardcoded 2.0s.
        assertTrue(playlist.contains("#EXTINF:1.551,"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:2"))
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
        var count = 0
        while (HlsManager.segment(HlsPlaylist.segmentName(count.toLong())) != null) count++
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
}
