package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the HLS DVR window: the playlist builder's LIVE vs EVENT
 * branches ([HlsPlaylist]) and the ring-bound behavior behind the DVR
 * setting ([HlsManager.setDvrSegments]).
 */
class HlsDvrPlaylistTest {

    // ── playlist builder branches ──

    private fun names(count: Long) = (1L..count).map { HlsPlaylist.segmentName(it) }

    @Test
    fun `live playlist stays sliding and untyped`() {
        val m3u8 = HlsPlaylist.build(names(9), sequence = 9)
        assertFalse(m3u8.contains("#EXT-X-PLAYLIST-TYPE"))
        assertTrue(m3u8.contains("#EXT-X-MEDIA-SEQUENCE:5"))
        // Exactly the sliding window of 5 segments.
        assertEquals(5, m3u8.lineSequence().count { it.endsWith(".ts") })
    }

    @Test
    fun `event playlist renders the full retained window`() {
        val m3u8 = HlsPlaylist.build(names(9), sequence = 9, segmentDurationsSec = names(9).map { 2.0 }, dvr = true)
        assertTrue(m3u8.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertTrue(m3u8.contains("#EXT-X-MEDIA-SEQUENCE:1"))
        assertEquals(9, m3u8.lineSequence().count { it.endsWith(".ts") })
    }

    @Test
    fun `event playlist anchors the sequence at the oldest retained segment`() {
        val m3u8 = HlsPlaylist.build(names(120), sequence = 120, segmentDurationsSec = names(120).map { 2.0 }, dvr = true)
        assertTrue(m3u8.contains("#EXT-X-MEDIA-SEQUENCE:1"))
        assertEquals(120, m3u8.lineSequence().count { it.endsWith(".ts") })
    }

    @Test
    fun `event playlist on a cold ring still renders validly`() {
        val m3u8 = HlsPlaylist.build(emptyList(), sequence = 0, segmentDurationsSec = emptyList(), dvr = true)
        assertTrue(m3u8.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertTrue(m3u8.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertEquals(0, m3u8.lineSequence().count { it.endsWith(".ts") })
    }

    // ── ring bounds behind the setting ──

    private var nowMs = 10_000L

    @Before
    fun setUp() {
        nowMs = 10_000L
        HlsManager.clockMs = { nowMs }
        HlsManager.encodedSendTap = null
        HlsManager.reset()
        HlsManager.setDvrSegments(0)
        HlsManager.setEnabled(true)
    }

    /** Feeds [count] whole segments of keyframe AUs at a 24 fps tick. */
    private fun feedSegmentsViaVideo(count: Int) {
        repeat(count * StreamDefaults.HLS_SEGMENT_AUS) {
            HlsManager.feedVideo(listOf(EncodedNalUnit(ByteArray(16), isKeyFrame = true)))
            nowMs += 1000L / 24
        }
    }

    @Test
    fun `dvr zero keeps the sliding live ring`() {
        HlsManager.setDvrSegments(0)
        feedSegmentsViaVideo(12)
        val m3u8 = HlsManager.playlist()
        assertFalse(m3u8.contains("#EXT-X-PLAYLIST-TYPE"))
        assertEquals(5, m3u8.lineSequence().count { it.endsWith(".ts") })
    }

    @Test
    fun `raised dvr retains up to the setting and renders event`() {
        HlsManager.setDvrSegments(6)
        feedSegmentsViaVideo(8)
        val m3u8 = HlsManager.playlist()
        assertTrue(m3u8.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        // 8 segments produced, ring capped at 6 → 6 listed, sequence anchored at the oldest.
        assertEquals(6, m3u8.lineSequence().count { it.endsWith(".ts") })
        assertTrue(m3u8.contains("#EXT-X-MEDIA-SEQUENCE:3"))
    }

    @Test
    fun `dvr setting clamps to the StreamDefaults ceiling`() {
        HlsManager.setDvrSegments(Int.MAX_VALUE)
        assertEquals(StreamDefaults.HLS_DVR_SEGMENTS_MAX, HlsManager.dvrSegments())
        HlsManager.setDvrSegments(-5)
        assertEquals(0, HlsManager.dvrSegments())
        HlsManager.setDvrSegments(120)
        assertEquals(120, HlsManager.dvrSegments())
    }
}
