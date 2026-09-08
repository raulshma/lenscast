package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.hls.HlsPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    @Test
    fun `playlist renders sliding window with sequence`() {
        val names = (1L..7L).map { HlsPlaylist.segmentName(it) }
        val m3u8 = HlsPlaylist.build(names, 7L)
        assertTrue(m3u8.startsWith("#EXTM3U"))
        assertTrue("#EXT-X-MEDIA-SEQUENCE:3" in m3u8)
        assertTrue("seg7.ts" in m3u8)
    }

    @Test
    fun `segment names are stable`() {
        assertEquals("seg42.ts", HlsPlaylist.segmentName(42L))
    }
}
