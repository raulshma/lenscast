package com.raulshma.lenscast.streaming.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SRT caller handshake, pinned byte-for-byte against the SRT
 * specification (draft-sharabayko-srt §4.3.1) and round-tripped through the
 * parsers: the induction request's exact field values, a recorded-form
 * induction response (Version 5, magic 0x4A17, cookie), the conclusion
 * request with its HSREQ extension block, and the HSRESP parse.
 *
 * The "known-good byte patterns" below are the spec's own field tables
 * (Version 4/5, Handshake Type 1/2, Extension Field 2 → 0x4A17 → 0x8000,
 * cookie echo), hand-assembled here — the builder's output must match them
 * byte for byte, so a spec-compliant listener accepts what we send.
 */
class SrtHandshakeTest {

    @Test
    fun `the induction request matches the spec field table byte for byte`() {
        val packet = SrtPacket.inductionRequest(
            timestampUs = 0x11223344L,
            socketId = 0x0A0B0C0D.toInt(),
            initialSequence = 0x55667788,
        )

        // Control header: F=1, type 0 (handshake), subtype 0, type-specific 0.
        assertEquals(0x8000, ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF))
        assertEquals(0, ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF))
        assertEquals(0, readU32(packet, 4))
        assertEquals(0x11223344L, readU32(packet, 8).toLong() and 0xFFFFFFFFL)
        assertEquals(0, readU32(packet, 12)) // dst socket id 0 = connection request

        // Handshake struct (52 bytes): Version 4, Encryption 0, Extension 2.
        assertEquals(4, readU32(packet, 16))
        assertEquals(0, readU16(packet, 20))
        assertEquals(2, readU16(packet, 22))
        assertEquals(0x55667788, readU32(packet, 24)) // ISN
        assertEquals(1500, readU32(packet, 28)) // MTU
        assertEquals(8192, readU32(packet, 32)) // flow window
        assertEquals(1, readU32(packet, 36)) // handshake type = INDUCTION (WAVEAHAND)
        assertEquals(0x0A0B0C0D, readU32(packet, 40)) // our socket id
        assertEquals(0, readU32(packet, 44)) // cookie 0
        // Peer IP: 16 zero bytes.
        for (offset in 48 until 64) {
            assertEquals(0, packet[offset].toInt())
        }
        // No extension blocks on induction.
        assertEquals(16 + 52, packet.size)
    }

    /** The 52-byte handshake struct out of the control frame's payload. */
    private fun payloadOf(packet: ByteArray): ByteArray = packet.copyOfRange(16, packet.size)

    @Test
    fun `an induction response parses into cookie and peer socket id`() {
        val response = knownInductionResponse()
        val handshake = SrtPacket.handshakeFrom(payloadOf(response))
        assertNotNull(handshake)
        with(handshake!!) {
            assertEquals(SrtPacket.HS_VERSION_SRT, version)
            assertEquals(SrtPacket.HS_MAGIC, extensionField)
            assertEquals(SrtPacket.HS_INDUCTION, handshakeType)
            assertEquals(0x1D2E3F40, socketId) // the listener's socket id
            assertEquals(0x05FA0C31, cookie) // the cookie the conclusion must echo
        }
    }

    @Test
    fun `the conclusion request echoes the cookie and carries the HSREQ block`() {
        val packet = SrtPacket.conclusionRequest(
            timestampUs = 0x000000FFL,
            socketId = 0x0A0B0C0D.toInt(),
            peerSocketId = 0x1D2E3F40,
            cookie = 0x05FA0C31,
            initialSequence = 0x55667788,
        )

        assertEquals(0x1D2E3F40, readU32(packet, 12)) // dst = the listener's socket id
        assertEquals(5, readU32(packet, 16)) // Version 5 on the conclusion
        assertEquals(SrtPacket.HS_EXT_HSREQ, readU16(packet, 22)) // HSREQ extension flag
        assertEquals(2, readU32(packet, 36)) // handshake type = CONCLUSION
        assertEquals(0x05FA0C31, readU32(packet, 44)) // the echoed cookie

        // The HSREQ extension block: type 1, four words.
        val ext = 16 + 52
        assertEquals(1, readU16(packet, ext))
        assertEquals(4, readU16(packet, ext + 2))
        assertEquals(SrtPacket.SRT_VERSION, readU32(packet, ext + 4))
        assertEquals(SrtPacket.SRT_ADVERTISED_FLAGS, readU32(packet, ext + 8))
        assertEquals(SrtPacket.LATENCY_MS, readU16(packet, ext + 12))
        assertEquals(SrtPacket.LATENCY_MS, readU16(packet, ext + 14))
        assertEquals(ext + 20, packet.size)
    }

    @Test
    fun `a conclusion response parses and its HSRESP yields the peer config`() {
        val response = knownConclusionResponse()
        val handshake = SrtPacket.handshakeFrom(payloadOf(response))
        assertNotNull(handshake)
        assertEquals(SrtPacket.HS_CONCLUSION, handshake!!.handshakeType)

        val peer = SrtPacket.hsRespFrom(payloadOf(response))
        assertNotNull(peer)
        assertEquals(SrtPacket.SRT_VERSION, peer!!.version)
        assertEquals(0x07, peer.flags)
        assertEquals(120, peer.latencyMs)
    }

    @Test
    fun `a legacy version-4 induction response still parses`() {
        val response = knownInductionResponse().clone()
        // Version (u32 at 16) 5 → 4: the low byte carries the value;
        // Extension Field (u16 at 22) magic → 0.
        response[19] = 4
        response[22] = 0
        response[23] = 0
        val handshake = SrtPacket.handshakeFrom(payloadOf(response))
        assertNotNull(handshake)
        assertEquals(4, handshake!!.version)
        assertEquals(0x05FA0C31, handshake.cookie)
    }

    @Test
    fun `junk or truncated handshakes are rejected, never crash`() {
        assertNull(SrtPacket.handshakeFrom(ByteArray(51)))
        assertNull(SrtPacket.handshakeFrom(ByteArray(0)))
        val junk = ByteArray(52) { it.toByte() }
        assertNull(SrtPacket.handshakeFrom(junk)) // version 0x03020100 is neither 4 nor 5
        // The HSRESP scan tolerates truncation inside the extension area.
        assertNull(SrtPacket.hsRespFrom(payloadOf(knownInductionResponse())))
    }

    // ── recorded-form fixtures (spec-assembled, libsrt-shaped) ──

    /** An induction response the way a spec-compliant listener sends it. */
    private fun knownInductionResponse(): ByteArray {
        val out = ByteArray(16 + 52)
        writeU16(out, 0, 0x8000 or SrtPacket.TYPE_HANDSHAKE)
        writeU32(out, 8, 0x00000100) // timestamp
        writeU32(out, 12, 0x0A0B0C0D) // dst = the caller's socket id
        writeU32(out, 16, 5) // Version 5
        writeU32(out, 20, 0x00004A17) // Encryption 0 | Extension Field magic
        writeU32(out, 24, 0x55667788) // ISN
        writeU32(out, 28, 1500)
        writeU32(out, 32, 8192)
        writeU32(out, 36, SrtPacket.HS_INDUCTION)
        writeU32(out, 40, 0x1D2E3F40) // the listener's socket id
        writeU32(out, 44, 0x05FA0C31) // the cookie
        return out
    }

    /** A conclusion response with an HSRESP block (version, flags, latency). */
    private fun knownConclusionResponse(): ByteArray {
        val out = knownInductionResponse()
        writeU32(out, 36, SrtPacket.HS_CONCLUSION)
        writeU32(out, 44, 0) // no cookie on the conclusion response
        val extended = out + ByteArray(20)
        val ext = 16 + 52
        writeU16(extended, ext, SrtPacket.EXT_HSRESP)
        writeU16(extended, ext + 2, 4)
        writeU32(extended, ext + 4, SrtPacket.SRT_VERSION)
        writeU32(extended, ext + 8, 0x07)
        writeU16(extended, ext + 12, 120)
        writeU16(extended, ext + 14, 120)
        return extended
    }

    // ── byte helpers (test-local; the parsers are the code under test) ──

    private fun readU16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) shl 24 or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }
}
