package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspResolutionTest {

    @Test
    fun `every wire name maps to its documented dimensions`() {
        assertEquals(640 to 480, RtspResolution.P480.width to RtspResolution.P480.height)
        assertEquals(
            StreamDefaults.RTSP_VIDEO_WIDTH to StreamDefaults.RTSP_VIDEO_HEIGHT,
            RtspResolution.P720.width to RtspResolution.P720.height,
        )
        assertEquals(1920 to 1080, RtspResolution.P1080.width to RtspResolution.P1080.height)
    }

    @Test
    fun `the default is 720p and matches the encoder defaults`() {
        assertEquals("720p", RtspResolution.DEFAULT_WIRE_NAME)
        assertEquals(RtspResolution.P720, RtspResolution.DEFAULT)
        assertEquals(StreamDefaults.RTSP_VIDEO_WIDTH, RtspResolution.DEFAULT.width)
        assertEquals(StreamDefaults.RTSP_VIDEO_HEIGHT, RtspResolution.DEFAULT.height)
    }

    @Test
    fun `valid wire names parse`() {
        assertEquals(RtspResolution.P480, RtspResolution.fromWireName("480p"))
        assertEquals(RtspResolution.P720, RtspResolution.fromWireName("720p"))
        assertEquals(RtspResolution.P1080, RtspResolution.fromWireName("1080p"))
    }

    @Test
    fun `null blank and unknown wire names fall back to the default`() {
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName(null))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName(""))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("   "))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("4k"))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("720P")) // case-sensitive, like parseEnum
    }

    @Test
    fun `the skip-save variant answers null for absent or unknown names`() {
        assertNull(RtspResolution.fromWireNameOrNull(null))
        assertNull(RtspResolution.fromWireNameOrNull(""))
        assertNull(RtspResolution.fromWireNameOrNull("qhd"))
        assertEquals(RtspResolution.P480, RtspResolution.fromWireNameOrNull(" 480p "))
    }

    @Test
    fun `wire names round-trip`() {
        for (resolution in RtspResolution.entries) {
            assertEquals(resolution, RtspResolution.fromWireName(resolution.wireName))
        }
    }
}
