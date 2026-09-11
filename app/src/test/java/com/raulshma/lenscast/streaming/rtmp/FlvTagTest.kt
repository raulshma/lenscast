package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FlvTagTest {

    // A real SPS/PPS pair (H.264 High, level 3.1) and the canonical AAC-LC
    // 44.1 kHz stereo AudioSpecificConfig — the exact wire shapes pinned here.
    private val sps = byteArrayOf(
        0x67.toByte(), 0x64.toByte(), 0x00, 0x1F, 0xAC.toByte(), 0xD9.toByte(), 0x40, 0x50,
        0x05, 0xBB.toByte(), 0x01, 0x6C.toByte(), 0x80.toByte(), 0x00, 0x00, 0x03,
        0x00, 0x80.toByte(), 0x00, 0x00, 0x1E, 0x07, 0x8C.toByte(), 0x18, 0xCB.toByte(),
    )
    private val pps = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte(), 0xB2.toByte(), 0x2C)
    private val asc = byteArrayOf(0x12, 0x10) // AAC-LC, 44.1 kHz, 2 channels

    // ── video: the AVC sequence header (AVCC) ──

    @Test
    fun `the avc sequence header carries the avcC record behind the tag body`() {
        val bytes = FlvTag.avcSequenceHeader(sps, pps)
        // FLV video tag body: frame type 1 (key) | codec 7 (AVC), AVCPacketType 0, ct 0.
        assertEquals(0x17, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt())
        // The avcC record: version, the SPS's profile/compat/level bytes,
        // lengthSizeMinusOne, numOfSequenceParameterSets, the SPS,
        // numOfPictureParameterSets, the PPS (ISO 14496-15 §5.2.4.1).
        val avcC = bytes.copyOfRange(5, bytes.size)
        assertEquals(6 + 2 + sps.size + 1 + 2 + pps.size, avcC.size)
        assertEquals(0x01, avcC[0].toInt()) // configurationVersion
        assertEquals(0x64.toByte(), avcC[1]) // AVCProfileIndication (sps[1])
        assertEquals(0, avcC[2].toInt()) // profile_compatibility (sps[2])
        assertEquals(0x1F, avcC[3].toInt()) // AVCLevelIndication (sps[3])
        assertEquals(0xFF.toByte(), avcC[4]) // lengthSizeMinusOne = 3
        assertEquals(0xE1.toByte(), avcC[5]) // one SPS
        assertEquals(0x00, avcC[6].toInt()); assertEquals(sps.size.toByte(), avcC[7])
        assertArrayEquals(sps, avcC.copyOfRange(8, 8 + sps.size))
        assertEquals(0x01, avcC[8 + sps.size].toInt()) // one PPS
        assertEquals(0x00, avcC[9 + sps.size].toInt())
        assertEquals(pps.size.toByte(), avcC[10 + sps.size])
        assertArrayEquals(pps, avcC.copyOfRange(11 + sps.size, avcC.size))
    }

    // ── video: NALU packets ──

    @Test
    fun `a keyframe nal packet length-prefixes each nal with the composition time`() {
        val idr = byteArrayOf(0x65.toByte(), 0x88.toByte(), 0x84.toByte())
        val sei = byteArrayOf(0x06, 0x01)
        val bytes = FlvTag.avcPacket(listOf(idr, sei), isKeyFrame = true, compositionTimeMs = 0)

        assertEquals(0x17, bytes[0].toInt() and 0xFF) // keyframe | AVC
        assertEquals(0x01, bytes[1].toInt()) // AVCPacketType 1 (NALUs)
        assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()); assertEquals(0, bytes[4].toInt()) // ct 0
        // Length prefixes are four-byte big-endian, one per NAL.
        assertEquals(0, bytes[5].toInt()); assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt()); assertEquals(3, bytes[8].toInt())
        assertArrayEquals(idr, bytes.copyOfRange(9, 12))
        assertEquals(0, bytes[12].toInt()); assertEquals(0, bytes[13].toInt())
        assertEquals(0, bytes[14].toInt()); assertEquals(2, bytes[15].toInt())
        assertArrayEquals(sei, bytes.copyOfRange(16, 18))
        assertEquals(18, bytes.size)
    }

    @Test
    fun `an interframe packet sets the frame-type nibble and carries its composition time`() {
        val nal = byteArrayOf(0x41.toByte(), 0x9A.toByte())
        val bytes = FlvTag.avcPacket(listOf(nal), isKeyFrame = false, compositionTimeMs = 66051) // 0x010203
        assertEquals(0x27, bytes[0].toInt() and 0xFF) // interframe | AVC
        assertEquals(0x01, bytes[1].toInt())
        assertEquals(0x01, bytes[2].toInt()); assertEquals(0x02, bytes[3].toInt()); assertEquals(0x03, bytes[4].toInt())
    }

    // ── audio: the AAC sequence header and raw frames ──

    @Test
    fun `the aac sequence header is the asc behind the aac tag header`() {
        val bytes = FlvTag.aacSequenceHeader(asc)
        // 0xAF: AAC (10) << 4 | 44 kHz (3) << 2 | 16-bit << 1 | stereo.
        assertEquals(0xAF.toByte(), bytes[0])
        assertEquals(0x00, bytes[1].toInt()) // AACPacketType 0 (sequence header)
        assertArrayEquals(asc, bytes.copyOfRange(2, bytes.size))
        assertEquals(2 + asc.size, bytes.size)
    }

    @Test
    fun `a raw aac frame rides packet type 1`() {
        val frame = byteArrayOf(0x21, 0x00, 0x4C, 0x50)
        val bytes = FlvTag.aacFrame(frame)
        assertEquals(0xAF.toByte(), bytes[0])
        assertEquals(0x01, bytes[1].toInt()) // AACPacketType 1 (raw)
        assertArrayEquals(frame, bytes.copyOfRange(2, bytes.size))
    }
}
