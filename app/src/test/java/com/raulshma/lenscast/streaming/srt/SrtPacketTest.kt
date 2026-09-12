package com.raulshma.lenscast.streaming.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SRT data/control packet wire formats: the data header's flag bits and
 * sequence wrap, full-ACK parsing (last acked sequence + RTT), NAK loss-list
 * parsing (singles and ranges), keepalive/shutdown framing, and the inbound
 * classification. Fuzzed elsewhere ([SrtPacketFuzzTest]); here the exact
 * bytes are pinned.
 */
class SrtPacketTest {

    @Test
    fun `a data packet carries single ordered unencrypted framing`() {
        val payload = ByteArray(SrtPacket.DATA_PAYLOAD_BYTES) { it.toByte() }
        val packet = SrtPacket.dataPacket(
            sequence = 12345,
            messageNumber = 1,
            timestampUs = 0x01020304L,
            dstSocketId = 77,
            payload = payload,
        )
        assertEquals(16 + payload.size, packet.size)
        // Sequence: bit 15 (of the first word) = 0 → data; value = 12345.
        assertEquals(12345, readU32(packet, 0))
        // Flags byte: FF=10 (single), O=1 (ordered), kk=00, R=0, msgno top bits.
        assertEquals(0b10 shl 6 or (1 shl 5), packet[4].toInt() and 0b11100000)
        assertEquals(1, (packet[4].toInt() and 0x03) shl 24 or readU24(packet, 5))
        assertEquals(0x01020304, readU32(packet, 8))
        assertEquals(77, readU32(packet, 12))
        payload.forEachIndexed { index, byte -> assertEquals(byte, packet[16 + index]) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an oversized data payload is a programming error`() {
        SrtPacket.dataPacket(1, 1, 0, 0, ByteArray(SrtPacket.DATA_PAYLOAD_BYTES + 1))
    }

    @Test
    fun `a full ack parses into last acked sequence and rtt`() {
        val body = ByteArray(28)
        writeU32(body, 0, 0x80000100.toInt()) // last acked sequence (u31 → 256)
        writeU32(body, 4, 25_000) // RTT µs
        writeU32(body, 8, 4_000) // RTT variance
        val control = SrtPacket.controlPacket(SrtPacket.TYPE_ACK, 0, 0, timestampUs = 1_000L, dstSocketId = 5, payload = body)

        val incoming = SrtPacket.parseIncoming(control)
        assertTrue(incoming is SrtPacket.Incoming.Control)
        assertEquals(SrtPacket.TYPE_ACK, (incoming as SrtPacket.Incoming.Control).type)

        val ack = SrtPacket.ackFrom(incoming.payload)
        assertNotNull(ack)
        assertEquals(256, ack!!.lastAckSequence)
        assertEquals(25.0, ack.rttUs / 1000.0, 0.0)
        assertEquals(4_000, ack.rttVarUs)
    }

    @Test
    fun `a truncated ack body parses to null`() {
        assertNull(SrtPacket.ackFrom(ByteArray(10)))
    }

    @Test
    fun `a nak loss list parses singles and ranges`() {
        val body = ByteArray(20)
        writeU32(body, 0, 100) // single loss: 100
        writeU32(body, 4, 0x80000000.toInt() or 200) // range start 200 …
        writeU32(body, 8, 203) // … through 203
        writeU32(body, 12, 0x80000000.toInt() or 300)
        writeU32(body, 16, 300) // single-element range 300..300
        val ranges = SrtPacket.nakFrom(body)
        assertEquals(listOf(100..100, 200..203, 300..300), ranges)
    }

    @Test
    fun `a hostile nak range span is capped`() {
        val body = ByteArray(8)
        writeU32(body, 0, 0x80000000.toInt() or 1)
        writeU32(body, 4, 0x7FFFFFFF) // an absurd span
        val ranges = SrtPacket.nakFrom(body)
        assertEquals(1, ranges.size)
        assertEquals(SrtPacket.MAX_NAK_RANGE_SPAN, ranges[0].count())
    }

    @Test
    fun `keepalive and shutdown are bare 16-byte control frames`() {
        for ((type, bytes) in listOf(
            SrtPacket.TYPE_KEEPALIVE to SrtPacket.keepalive(9, 42),
            SrtPacket.TYPE_SHUTDOWN to SrtPacket.shutdown(9, 42),
        )) {
            assertEquals(16, bytes.size)
            assertEquals(0x8000 or type, readU16(bytes, 0))
            assertEquals(42, readU32(bytes, 12))
            val incoming = SrtPacket.parseIncoming(bytes)
            assertTrue(incoming is SrtPacket.Incoming.Control)
            assertEquals(type, (incoming as SrtPacket.Incoming.Control).type)
        }
    }

    @Test
    fun `data and control inbound classification`() {
        val data = SrtPacket.dataPacket(9, 1, 1, 2, ByteArray(188))
        val incoming = SrtPacket.parseIncoming(data)
        assertTrue(incoming is SrtPacket.Incoming.Data)
        assertEquals(9, (incoming as SrtPacket.Incoming.Data).sequence)
        assertNull(SrtPacket.parseIncoming(ByteArray(4)))
    }

    @Test
    fun `sequence numbers stay u31 through the wrap helper`() {
        val packet = SrtPacket.dataPacket(0x7FFFFFFF, 1, 0, 0, ByteArray(188))
        assertEquals(0x7FFFFFFF, readU32(packet, 0) and 0x7FFFFFFF)
    }

    private fun readU24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    private fun readU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) shl 24 or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun readU16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }
}
