package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H265RtpPacketizerTest {

    // Fresh instance per test class; assertions are self-relative, so no reset ritual.
    private val packetizer = H265RtpPacketizer()

    /**
     * An H.265 NAL unit: byte0 = type<<1 (layerIdHigh=0, F=0), byte1 = 0x01
     * (layerId 0, temporalIdPlus1 1 — what real encoders emit). Bodies avoid
     * 00 00 01 sequences like the parser test vectors.
     */
    private fun nal(type: Int, size: Int): ByteArray =
        ByteArray(size) { i ->
            when (i) {
                0 -> ((type and 0x3F) shl 1).toByte()
                1 -> 0x01.toByte()
                else -> ((i * 7 + 3) % 251).toByte()
            }
        }

    private fun payloadSize(p: ByteArray) = p.size - 12

    // ── single NAL unit mode ──

    @Test
    fun `small nal is sent as a single packet carrying the raw nal verbatim`() {
        val nal = nal(32, 50) // a VPS
        val packets = packetizer.packetizeNalUnit(nal, 12345, marker = true)

        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80.toByte().toInt() and 0xFF, p[0].toInt() and 0xFF) // V=2
        assertEquals(0x80 or 96, p[1].toInt() and 0xFF) // marker + PT=96
        assertEquals(50 + 12, p.size)
        // Single NAL mode: the payload IS the NAL unit, 2-byte header included.
        assertArrayEquals(nal, p.copyOfRange(12, p.size))
        assertEquals(nal[0], p[12])
        assertEquals(nal[1], p[13])
    }

    @Test
    fun `empty nal produces no packets`() {
        assertTrue(packetizer.packetizeNalUnit(ByteArray(0), 0, true).isEmpty())
    }

    // ── fragmentation units: the byte-pinned RFC 7798 §4.4.3 layout ──

    @Test
    fun `large nal fragments carry the exact payloadhdr and fu header bytes`() {
        val nal = nal(1, 5000) // TRAIL_R slice, 4998 payload bytes → 4 fragments
        val packets = packetizer.packetizeNalUnit(nal, 3750, marker = true)

        assertEquals(4, packets.size)
        assertEquals(packetizer.sentPacketCount, packets.size.toLong())

        // PayloadHdr byte 0 on every fragment: Type=49 → 49<<1 = 0x62 (F=0, layerIdHigh=0).
        for (p in packets) {
            assertEquals(0x62, p[12].toInt() and 0xFF)
        }
        // PayloadHdr byte 1: the original NAL's second header byte, verbatim.
        for (p in packets) {
            assertEquals(0x01, p[13].toInt() and 0xFF)
        }
        // FU headers: first S=1, last E=1, middle neither; low 6 bits = original type.
        val fuHeaders = packets.map { it[14].toInt() and 0xFF }
        assertEquals(0x80 or 1, fuHeaders.first())
        assertEquals(0x40 or 1, fuHeaders.last())
        assertTrue(fuHeaders.drop(1).dropLast(1).all { it and 0xC0 == 0 && it and 0x3F == 1 })
    }

    @Test
    fun `fragment payload budget fills 1400-byte packets exactly`() {
        val nal = nal(1, 5000)
        val packets = packetizer.packetizeNalUnit(nal, 0, marker = true)

        // maxPayload = 1400 - 12 - 3 = 1385; full fragments hit MAX_PACKET_SIZE exactly.
        assertEquals(1400, packets.first().size)
        for (p in packets.dropLast(1)) {
            assertEquals(1400, p.size)
            assertEquals(1385, payloadSize(p) - 3)
        }
        // Remainder: 4998 - 3*1385 = 843 payload bytes.
        assertEquals(843, payloadSize(packets.last()) - 3)
    }

    @Test
    fun `fragments reassemble byte-exact like an rfc 7798 receiver`() {
        val nal = nal(20, 5000) // IDR_N_LP
        val packets = packetizer.packetizeNalUnit(nal, 3750, marker = true)

        val reassembledPayload = java.io.ByteArrayOutputStream()
        for (p in packets) {
            reassembledPayload.write(p, 15, p.size - 15) // skip RTP header + PayloadHdr + FU header
        }
        // Payload chunks concatenate to the original NAL minus its 2-byte header.
        assertArrayEquals(nal.copyOfRange(2, nal.size), reassembledPayload.toByteArray())

        // Header reconstruction: byte0 = (PayloadHdr0 & 0x81) | ((fuHeader & 0x3F) << 1), byte1 = PayloadHdr1.
        val first = packets.first()
        val rebuiltByte0 =
            ((first[12].toInt() and 0x81) or ((first[14].toInt() and 0x3F) shl 1)).toByte()
        assertEquals(nal[0], rebuiltByte0)
        assertEquals(nal[1], first[13])
    }

    @Test
    fun `forbidden bit and layer id high bit survive into the payloadhdr`() {
        // F=1, type 1, layerIdHigh=1 → original byte0 = 0x84 (0x80 | 0x02 | 0x01... = 0x83|0x01).
        val nal = byteArrayOf(0x83.toByte(), 0x2A.toByte()) +
            ByteArray(4000) { i -> ((i * 11 + 5) % 253).toByte() }
        val packets = packetizer.packetizeNalUnit(nal, 0, marker = true)

        for (p in packets) {
            // PayloadHdr0 keeps F (0x80) and layerIdHigh (0x01), Type=49 (0x62): 0x80|0x62|0x01 = 0xE3.
            assertEquals(0xE3.toByte().toInt() and 0xFF, p[12].toInt() and 0xFF)
            assertEquals(0x2A.toByte(), p[13])
        }
        // Round-trips back to the original header byte.
        val first = packets.first()
        val rebuilt = ((first[12].toInt() and 0x81) or ((first[14].toInt() and 0x3F) shl 1)).toByte()
        assertEquals(nal[0], rebuilt)
    }

    @Test
    fun `all fragments share timestamp pt and a contiguous sequence`() {
        val nal = nal(1, 5000)
        val packets = packetizer.packetizeNalUnit(nal, 3750, marker = true)

        val ts = packets.map { p ->
            ((p[4].toInt() and 0xFF) shl 24) or ((p[5].toInt() and 0xFF) shl 16) or
                ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        }.toSet()
        assertEquals(setOf(3750), ts)
        assertTrue(packets.all { (it[1].toInt() and 0x7F) == 96 })

        val seqs = packets.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        assertEquals(seqs.size - 1, seqs.zipWithNext().count { (a, b) -> b == ((a + 1) and 0xFFFF) })
    }

    @Test
    fun `marker propagates only to final packet of fragmented nal`() {
        val nal = nal(1, 3000)
        val packets = packetizer.packetizeNalUnit(nal, 7500, marker = true)
        assertTrue(packets.size > 1)
        assertEquals(1, packets.count { (it[1].toInt() and 0x80) != 0 })
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
    }

    // ── access unit framing ──

    @Test
    fun `access unit marks exactly one packet - the last - regardless of nal count`() {
        val nals = listOf(nal(32, 100), nal(33, 100), nal(34, 100), nal(19, 100))
        val packets = packetizer.packetizeAccessUnit(nals, 3750)
        assertEquals(4, packets.size)
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
        assertTrue(packets.take(3).none { (it[1].toInt() and 0x80) != 0 })
    }

    @Test
    fun `access unit marks only final fragment of final nal when fragmented`() {
        val nals = listOf(nal(20, 5000), nal(1, 100))
        val packets = packetizer.packetizeAccessUnit(nals, 0)
        assertTrue(packets.size > 2)
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
    }

    // ── counters (the RTCP sender-report inputs, via RtpStreamState) ──

    @Test
    fun `ssrc is identical across packets and counters track octets`() {
        val nalA = nal(32, 100)
        val nalB = nal(20, 2000)
        val all = packetizer.packetizeNalUnit(nalA, 0, true) +
            packetizer.packetizeNalUnit(nalB, 3750, true)

        val ssrcs = all.map { p ->
            ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or
                ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
        }.toSet()
        assertEquals(1, ssrcs.size)
        assertEquals(ssrcs.first(), packetizer.wireSsrc)

        val expectedOctets = all.sumOf { it.size - 12 }.toLong()
        assertEquals(expectedOctets, packetizer.sentOctetCount)
        assertEquals(all.size.toLong(), packetizer.sentPacketCount)
    }
}
