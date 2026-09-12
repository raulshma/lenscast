package com.raulshma.lenscast.streaming.ws

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the WS HEVC half ([WsVideoProtocol]): HEVC NAL-type decode,
 * the VPS/SPS/PPS extraction, and the hvcC record + 'LCHC' config envelope —
 * the self-describing message a joining browser configures from.
 */
class WsVideoProtocolHevcTest {

    /** Builds a HEVC NAL from its 6-bit type + payload (2-byte header + data). */
    private fun hevcNal(type: Int, payload: ByteArray = ByteArray(8) { it.toByte() }): ByteArray {
        val first = ((type shl 1) and 0x7E).toByte()
        return byteArrayOf(first, 0x01) + payload
    }

    private fun parameterSetNals(): Triple<ByteArray, ByteArray, ByteArray> {
        val vps = hevcNal(WsVideoProtocol.NAL_HEVC_VPS)
        // 14+ bytes so the hvcC profile_tier_level copy has real bytes.
        val sps = hevcNal(WsVideoProtocol.NAL_HEVC_SPS, ByteArray(20) { (it + 1).toByte() })
        val pps = hevcNal(WsVideoProtocol.NAL_HEVC_PPS)
        return Triple(vps, sps, pps)
    }

    @Test
    fun `hevc nal type decodes the 6-bit type field`() {
        val (vps, sps, pps) = parameterSetNals()
        assertEquals(WsVideoProtocol.NAL_HEVC_VPS, WsVideoProtocol.hevcNalType(vps))
        assertEquals(WsVideoProtocol.NAL_HEVC_SPS, WsVideoProtocol.hevcNalType(sps))
        assertEquals(WsVideoProtocol.NAL_HEVC_PPS, WsVideoProtocol.hevcNalType(pps))
        assertEquals(-1, WsVideoProtocol.hevcNalType(ByteArray(1)))
        assertEquals(-1, WsVideoProtocol.hevcNalType(ByteArray(0)))
    }

    @Test
    fun `extraction finds the parameter-set triple`() {
        val (vps, sps, pps) = parameterSetNals()
        val sets = WsVideoProtocol.extractHevcParameterSets(listOf(vps, sps, pps))
        assertNotNull(sets)
        assertArrayEquals(vps, sets!!.vps)
        assertArrayEquals(sps, sets.sps)
        assertArrayEquals(pps, sets.pps)
    }

    @Test
    fun `extraction needs all three sets`() {
        val (vps, sps, pps) = parameterSetNals()
        assertNull(WsVideoProtocol.extractHevcParameterSets(listOf(sps, pps)))
        assertNull(WsVideoProtocol.extractHevcParameterSets(listOf(vps)))
        assertNull(WsVideoProtocol.extractHevcParameterSets(emptyList()))
    }

    @Test
    fun `h264 extraction does not fire on HEVC parameter sets`() {
        val (vps, sps, pps) = parameterSetNals()
        // VPS type 32 → first byte 0x40, low 5 bits 0 — no H.264 SPS/PPS (7/8).
        assertNull(WsVideoProtocol.extractParameterSets(listOf(vps, sps, pps)))
    }

    @Test
    fun `hvcC record has the ISO 14496-15 shape`() {
        val (_, sps, _) = parameterSetNals()
        val (vps, _, pps) = parameterSetNals()
        val record = WsVideoProtocol.hevcC(vps, sps, pps)

        assertEquals(1, record[0].toInt()) // configurationVersion
        // profile_tier_level copy: byte 1 = SPS payload byte 2 (profile_space/tier/idc)
        assertEquals(sps[2], record[1])
        // 4 compatibility + 6 constraint bytes copied from the SPS
        for (i in 0 until 10) {
            assertEquals(sps[3 + i], record[2 + i])
        }
        assertEquals(sps[13], record[12]) // general_level_idc
        assertEquals(3, record[18].toInt()) // numOfArrays
    }

    @Test
    fun `hvcC arrays carry VPS SPS PPS in order`() {
        val (vps, sps, pps) = parameterSetNals()
        val record = WsVideoProtocol.hevcC(vps, sps, pps)

        // Walk the three arrays after the 19-byte fixed header.
        var i = 19
        for ((type, nal) in listOf(
            WsVideoProtocol.NAL_HEVC_VPS to vps,
            WsVideoProtocol.NAL_HEVC_SPS to sps,
            WsVideoProtocol.NAL_HEVC_PPS to pps,
        )) {
            assertEquals(0x80 or type, record[i].toInt() and 0xFF) // completeness + type
            assertEquals(0, record[i + 1].toInt()) // numNalus high
            assertEquals(1, record[i + 2].toInt()) // numNalus low
            val len = ((record[i + 3].toInt() and 0xFF) shl 8) or (record[i + 4].toInt() and 0xFF)
            assertEquals(nal.size, len)
            i += 5 + nal.size
        }
        assertEquals(record.size, i)
    }

    @Test
    fun `hevc config message is self-describing as LCHC`() {
        val (vps, sps, pps) = parameterSetNals()
        val message = WsVideoProtocol.hevcVideoConfig(vps, sps, pps)
        assertEquals("LCHC", String(message, 0, 4, Charsets.US_ASCII))
        val payloadLen = ((message[4].toInt() and 0xFF) shl 24) or
            ((message[5].toInt() and 0xFF) shl 16) or
            ((message[6].toInt() and 0xFF) shl 8) or
            (message[7].toInt() and 0xFF)
        assertEquals(message.size - 8, payloadLen)
    }

    @Test
    fun `frame messages keep the shared AVCC envelope under HEVC`() {
        val nal = hevcNal(19) // IDR_W_RADL
        val avcc = WsVideoProtocol.nalUnitsToAvcc(listOf(nal))
        val frame = WsVideoProtocol.videoFrameAvcc(avcc, isKeyFrame = true)
        assertEquals("LCK1", String(frame, 0, 4, Charsets.US_ASCII))
        // Length prefix round-trips the NAL through the AVCC framing.
        val len = ((frame[8].toInt() and 0xFF) shl 24) or
            ((frame[9].toInt() and 0xFF) shl 16) or
            ((frame[10].toInt() and 0xFF) shl 8) or
            (frame[11].toInt() and 0xFF)
        assertEquals(nal.size, len)
        // The keyframe verdict rides the encoder's flag (the raw-ByteArray
        // scan is H.264-typed and must not be consulted for HEVC NALs).
        assertTrue(
            WsVideoProtocol.containsKeyframe(
                listOf(com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit(nal, isKeyFrame = true)),
            )
        )
    }
}
