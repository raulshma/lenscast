package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspVideoCodecTest {

    @Test
    fun `wire names are lowercase h264 and h265`() {
        assertEquals("h264", RtspVideoCodec.H264.wireName)
        assertEquals("h265", RtspVideoCodec.H265.wireName)
    }

    @Test
    fun `the default is h264 and matches the default wire name`() {
        assertEquals("h264", RtspVideoCodec.DEFAULT_WIRE_NAME)
        assertEquals(RtspVideoCodec.H264, RtspVideoCodec.DEFAULT)
    }

    @Test
    fun `valid wire names parse`() {
        assertEquals(RtspVideoCodec.H264, RtspVideoCodec.fromWireName("h264"))
        assertEquals(RtspVideoCodec.H265, RtspVideoCodec.fromWireName("h265"))
    }

    @Test
    fun `null blank and unknown wire names fall back to h264`() {
        assertEquals(RtspVideoCodec.DEFAULT, RtspVideoCodec.fromWireName(null))
        assertEquals(RtspVideoCodec.DEFAULT, RtspVideoCodec.fromWireName(""))
        assertEquals(RtspVideoCodec.DEFAULT, RtspVideoCodec.fromWireName("   "))
        assertEquals(RtspVideoCodec.DEFAULT, RtspVideoCodec.fromWireName("av1"))
        // Case-sensitive, like parseEnum and RtspResolution.
        assertEquals(RtspVideoCodec.DEFAULT, RtspVideoCodec.fromWireName("H265"))
    }

    @Test
    fun `the skip-apply variant answers null for absent or unknown names`() {
        assertNull(RtspVideoCodec.fromWireNameOrNull(null))
        assertNull(RtspVideoCodec.fromWireNameOrNull(""))
        assertNull(RtspVideoCodec.fromWireNameOrNull("mpeg2"))
        assertEquals(RtspVideoCodec.H265, RtspVideoCodec.fromWireNameOrNull(" h265 "))
    }

    @Test
    fun `wire names round-trip`() {
        for (codec in RtspVideoCodec.entries) {
            assertEquals(codec, RtspVideoCodec.fromWireName(codec.wireName))
        }
    }
}
