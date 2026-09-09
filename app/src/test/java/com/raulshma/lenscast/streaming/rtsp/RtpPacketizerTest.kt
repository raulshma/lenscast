package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {

    // Fresh instance per test class; assertions are self-relative, so no reset ritual.
    private val packetizer = RtpPacketizer()

    private fun nalHeader(type: Int, nri: Int = 3): Int = ((nri and 0x03) shl 5) or (type and 0x1F)

    @Test
    fun `small nal is sent as single-nal packet`() {
        val nal = ByteArray(50) { (it * 3).toByte() }.also { it[0] = nalHeader(7).toByte() }
        val packets = packetizer.packetizeNalUnit(nal, 12345, marker = true)

        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80.toByte().toInt() and 0xFF, p[0].toInt() and 0xFF) // V=2
        assertEquals(0x80 or 96, p[1].toInt() and 0xFF) // marker + PT=96
        assertEquals(50 + 12, p.size)
        assertArrayEquals(nal, p.copyOfRange(12, p.size))
    }

    @Test
    fun `large nal is fragmented into fu-a packets that reassemble byte-exact`() {
        val nal = ByteArray(5000) { (it * 31 + 7).toByte() }.also { it[0] = nalHeader(5).toByte() }
        val packets = packetizer.packetizeNalUnit(nal, 3750, marker = true)

        assertTrue(packets.size > 1)
        assertEquals(packetizer.sentPacketCount, packets.size.toLong())

        // All fragments share the same RTP timestamp and carry PT=96.
        val ts = packets.map { p ->
            ((p[4].toInt() and 0xFF) shl 24) or ((p[5].toInt() and 0xFF) shl 16) or
                ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        }.toSet()
        assertEquals(setOf(3750), ts)
        assertTrue(packets.all { (it[1].toInt() and 0x7F) == 96 })

        // Sequence numbers increase by one across fragments.
        val seqs = packets.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        assertEquals(seqs.size - 1, seqs.zipWithNext().count { (a, b) -> b == ((a + 1) and 0xFFFF) })

        // Marker set only on last fragment.
        assertFalse((packets.first()[1].toInt() and 0x80) != 0)
        assertTrue((packets.last()[1].toInt() and 0x80) != 0)

        // FU indicator: F=0, NRI from original header, type 28.
        for (p in packets) {
            val fuIndicator = p[12].toInt() and 0xFF
            assertEquals(0, fuIndicator and 0x80)
            assertEquals((nal[0].toInt() and 0x60), fuIndicator and 0x60)
            assertEquals(28, fuIndicator and 0x1F)
        }

        // FU headers: first S=1, last E=1, middle neither, all carrying original type.
        val fuHeaders = packets.map { it[13].toInt() and 0xFF }
        assertEquals(0x80 or 5, fuHeaders.first())
        assertEquals(0x40 or 5, fuHeaders.last())
        assertTrue(fuHeaders.drop(1).dropLast(1).all { it and 0xC0 == 0 && it and 0x1F == 5 })

        // Reassemble payload exactly like an RFC 6184 receiver.
        val reassembled = java.io.ByteArrayOutputStream()
        for (p in packets) {
            reassembled.write(p, 14, p.size - 14)
        }
        val expected = nal.copyOfRange(1, nal.size) // original NAL minus its header byte
        assertArrayEquals(expected, reassembled.toByteArray())
    }

    @Test
    fun `marker propagates only to final packet of fragmented nal`() {
        val nal = ByteArray(3000).also { it[0] = nalHeader(1).toByte() }
        val packets = packetizer.packetizeNalUnit(nal, 7500, marker = true)
        assertEquals(1, packets.count { (it[1].toInt() and 0x80) != 0 })
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
    }

    @Test
    fun `ssrc is identical across packets and counters track octets`() {
                val nalA = ByteArray(100).also { it[0] = nalHeader(7).toByte() }
        val nalB = ByteArray(2000).also { it[0] = nalHeader(5).toByte() }
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

    @Test
    fun `empty nal produces no packets`() {
                assertTrue(packetizer.packetizeNalUnit(ByteArray(0), 0, true).isEmpty())
    }

    @Test
    fun `access unit marks exactly one packet - the last - regardless of nal count`() {
        val nals = listOf(
            ByteArray(100).also { it[0] = nalHeader(7).toByte() },
            ByteArray(100).also { it[0] = nalHeader(8).toByte() },
            ByteArray(100).also { it[0] = nalHeader(5).toByte() },
        )
        val packets = packetizer.packetizeAccessUnit(nals, 3750)
        assertEquals(3, packets.size)
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
        assertTrue(packets.take(2).none { (it[1].toInt() and 0x80) != 0 })
    }

    @Test
    fun `access unit marks only final fragment of final nal when fragmented`() {
        val nals = listOf(
            ByteArray(3000).also { it[0] = nalHeader(5).toByte() },
            ByteArray(100).also { it[0] = nalHeader(1).toByte() },
        )
        val packets = packetizer.packetizeAccessUnit(nals, 0)
        assertTrue(packets.size > 2)
        assertEquals(packets.last(), packets.single { (it[1].toInt() and 0x80) != 0 })
    }
}
