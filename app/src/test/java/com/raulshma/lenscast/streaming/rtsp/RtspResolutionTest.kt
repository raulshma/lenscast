package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.camera.model.StreamAspect
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
        assertEquals(960 to 720, RtspResolution.P720_43.width to RtspResolution.P720_43.height)
        assertEquals(1920 to 1080, RtspResolution.P1080.width to RtspResolution.P1080.height)
        assertEquals(1440 to 1080, RtspResolution.P1080_43.width to RtspResolution.P1080_43.height)
    }

    @Test
    fun `every entry's dimensions match its declared aspect`() {
        for (resolution in RtspResolution.entries) {
            when (resolution.aspect) {
                StreamAspect.STANDARD_4_3 ->
                    assertEquals(resolution.wireName, resolution.width * 3, resolution.height * 4)
                StreamAspect.WIDE_16_9 ->
                    assertEquals(resolution.wireName, resolution.width * 9, resolution.height * 16)
            }
        }
    }

    @Test
    fun `the four-three tiers carry the standard aspect`() {
        assertEquals(StreamAspect.STANDARD_4_3, RtspResolution.P480.aspect)
        assertEquals(StreamAspect.STANDARD_4_3, RtspResolution.P720_43.aspect)
        assertEquals(StreamAspect.STANDARD_4_3, RtspResolution.P1080_43.aspect)
        assertEquals(StreamAspect.WIDE_16_9, RtspResolution.P720.aspect)
        assertEquals(StreamAspect.WIDE_16_9, RtspResolution.P1080.aspect)
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
        assertEquals(RtspResolution.P720_43, RtspResolution.fromWireName("720p43"))
        assertEquals(RtspResolution.P1080, RtspResolution.fromWireName("1080p"))
        assertEquals(RtspResolution.P1080_43, RtspResolution.fromWireName("1080p43"))
    }

    @Test
    fun `null blank and unknown wire names fall back to the default`() {
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName(null))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName(""))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("   "))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("4k"))
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("720P")) // case-sensitive, like parseEnum
        assertEquals(RtspResolution.DEFAULT, RtspResolution.fromWireName("720P43"))
    }

    @Test
    fun `the skip-save variant answers null for absent or unknown names`() {
        assertNull(RtspResolution.fromWireNameOrNull(null))
        assertNull(RtspResolution.fromWireNameOrNull(""))
        assertNull(RtspResolution.fromWireNameOrNull("qhd"))
        assertEquals(RtspResolution.P480, RtspResolution.fromWireNameOrNull(" 480p "))
        assertEquals(RtspResolution.P720_43, RtspResolution.fromWireNameOrNull(" 720p43 "))
    }

    @Test
    fun `wire names round-trip`() {
        for (resolution in RtspResolution.entries) {
            assertEquals(resolution, RtspResolution.fromWireName(resolution.wireName))
        }
    }
}
