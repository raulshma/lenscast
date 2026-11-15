package com.raulshma.lenscast.streaming.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The decodability contract players enforce on every TS segment, pinned at
 * the byte level: the elementary stream reassembles exactly (no stuffing
 * leaked into payload), PCR rides the PID the PMT names, AAC is ADTS-framed
 * with the encoder's format, and audio PES declares its length. These are
 * the failures that left the HLS preview at 0:00 — a demuxer rejects the
 * segment before any frame renders.
 */
class HlsTsDecodabilityTest {

    @Before
    fun setUp() {
        TsPacketizer.reset()
    }

    private class Packet(val pid: Int, val pusi: Boolean, val cc: Int, val adaptation: ByteArray?, val payload: ByteArray)

    private fun parse(bytes: ByteArray): List<Packet> {
        assertEquals(0, bytes.size % TsPacketizer.TS_PACKET_SIZE)
        val out = mutableListOf<Packet>()
        var off = 0
        while (off < bytes.size) {
            assertEquals(TsPacketizer.SYNC_BYTE, bytes[off])
            val pid = ((bytes[off + 1].toInt() and 0x1F) shl 8) or (bytes[off + 2].toInt() and 0xFF)
            val pusi = bytes[off + 1].toInt() and 0x40 != 0
            val afc = (bytes[off + 3].toInt() shr 4) and 3
            var pos = off + 4
            var adaptation: ByteArray? = null
            if (afc and 2 != 0) {
                val len = bytes[pos].toInt() and 0xFF
                adaptation = bytes.copyOfRange(pos + 1, pos + 1 + len)
                pos += 1 + len
            }
            val payload = if (afc and 1 != 0) bytes.copyOfRange(pos, off + TsPacketizer.TS_PACKET_SIZE) else ByteArray(0)
            out.add(Packet(pid, pusi, bytes[off + 3].toInt() and 0x0F, adaptation, payload))
            off += TsPacketizer.TS_PACKET_SIZE
        }
        return out
    }

    /** Concatenated PID payload with the PES header stripped. */
    private fun elementaryStream(packets: List<Packet>, pid: Int): ByteArray {
        val body = packets.filter { it.pid == pid }.fold(ByteArray(0)) { acc, p -> acc + p.payload }
        val headerDataLength = body[8].toInt() and 0xFF
        return body.copyOfRange(9 + headerDataLength, body.size)
    }

    @Test
    fun `video elementary stream reassembles byte-exact with no stuffing in the payload`() {
        // The old muxer wrote 0xFF padding as payload: the ES grew a garbage
        // tail and strict demuxers choked. A 500-byte NALU spans a PCR first
        // packet (176B), a full packet (184B) and a partial last packet.
        val nalu = ByteArray(500) { (it % 251).toByte() }
        val packets = parse(TsPacketizer.videoAuToTs(listOf(nalu), pts90k = 90_000L))
        val es = elementaryStream(packets, TsPacketizer.PID_VIDEO)
        assertTrue(es.contentEquals(byteArrayOf(0, 0, 0, 1) + nalu))
        // The partial tail is declared as adaptation field, not payload.
        val last = packets.last { it.pid == TsPacketizer.PID_VIDEO }
        assertTrue(last.adaptation != null)
        // Continuity counters cycle per PID across the AU's packets.
        val video = packets.filter { it.pid == TsPacketizer.PID_VIDEO }
        assertEquals(listOf(0, 1, 2), video.map { it.cc })
    }

    @Test
    fun `pcr rides the video pid the pmt names`() {
        val packets = parse(TsPacketizer.videoAuToTs(listOf(ByteArray(10)), pts90k = 12_345L))
        val pmt = packets.first { it.pid == TsPacketizer.PID_PMT }.payload
        // payload[0] is the pointer field (0); PCR_PID sits at section bytes 8..9.
        val pcrPid = ((pmt[9].toInt() and 0x1F) shl 8) or (pmt[10].toInt() and 0xFF)
        assertEquals(TsPacketizer.PID_VIDEO, pcrPid)
        val first = packets.first { it.pid == TsPacketizer.PID_VIDEO }
        assertTrue(first.pusi)
        val af = first.adaptation!!
        assertTrue("PCR flag set", af[0].toInt() and 0x10 != 0)
        val base = ((af[1].toLong() and 0xFF) shl 25) or
            ((af[2].toLong() and 0xFF) shl 17) or
            ((af[3].toLong() and 0xFF) shl 9) or
            ((af[4].toLong() and 0xFF) shl 1) or
            ((af[5].toLong() and 0xFF) shr 7)
        assertEquals(12_345L, base)
    }

    @Test
    fun `audio is adts framed with the encoder's sample rate and channels`() {
        // Raw AAC is correct for RTSP/RTMP but TS stream type 0x0F demands
        // ADTS; without the sync word the audio PID is unparseable.
        val raw = ByteArray(128) { 0x42.toByte() }
        val packets = parse(TsPacketizer.audioFrameToTs(raw, pts90k = 0L, sampleRateHz = 44_100, channelCount = 2))
        val es = elementaryStream(packets, TsPacketizer.PID_AUDIO)
        assertEquals(0xFF, es[0].toInt() and 0xFF)
        assertEquals(0xF1, es[1].toInt() and 0xFF)
        assertEquals(4, (es[2].toInt() shr 2) and 0x0F) // 44100 Hz index
        val chan = ((es[2].toInt() and 3) shl 2) or ((es[3].toInt() shr 6) and 3)
        assertEquals(2, chan)
        val len = ((es[3].toInt() and 3) shl 11) or ((es[4].toInt() and 0xFF) shl 3) or ((es[5].toInt() shr 5) and 7)
        assertEquals(raw.size + 7, len)
        assertTrue(es.copyOfRange(7, es.size).contentEquals(raw))
    }

    @Test
    fun `audio pes declares its length while video stays unbounded`() {
        val audioBody = parse(TsPacketizer.audioFrameToTs(ByteArray(64), pts90k = 0L))
            .filter { it.pid == TsPacketizer.PID_AUDIO }
            .fold(ByteArray(0)) { acc, p -> acc + p.payload }
        val declared = ((audioBody[4].toInt() and 0xFF) shl 8) or (audioBody[5].toInt() and 0xFF)
        assertEquals(audioBody.size - 6, declared)

        val videoBody = parse(TsPacketizer.videoAuToTs(listOf(ByteArray(64)), pts90k = 0L))
            .filter { it.pid == TsPacketizer.PID_VIDEO }
            .fold(ByteArray(0)) { acc, p -> acc + p.payload }
        assertEquals(0, ((videoBody[4].toInt() and 0xFF) shl 8) or (videoBody[5].toInt() and 0xFF))
    }

    @Test
    fun `pmt walks to the elementary streams the way a demuxer does`() {
        // hls.js reads program_info_length at section[10..11] and starts the
        // ES loop at 12 + program_info_length, bounded by section_length. The
        // old section omitted program_info_length: the declared length ran
        // past the bytes and the loop found no tracks ("Found no media").
        val payload = parse(TsPacketizer.videoAuToTs(listOf(ByteArray(4)), pts90k = 0L))
            .first { it.pid == TsPacketizer.PID_PMT }.payload
        val s = payload.copyOfRange(1, payload.size)
        val sectionLength = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        assertEquals(s.size - 3, sectionLength)
        val programInfoLength = ((s[10].toInt() and 0x0F) shl 8) or (s[11].toInt() and 0xFF)
        assertEquals(0, programInfoLength)
        val tableEnd = 3 + sectionLength - 4
        var o = 12 + programInfoLength
        val entries = mutableListOf<Pair<Int, Int>>()
        while (o < tableEnd) {
            val streamType = s[o].toInt() and 0xFF
            val pid = ((s[o + 1].toInt() and 0x1F) shl 8) or (s[o + 2].toInt() and 0xFF)
            val esInfoLength = ((s[o + 3].toInt() and 0x0F) shl 8) or (s[o + 4].toInt() and 0xFF)
            entries.add(streamType to pid)
            o += 5 + esInfoLength
        }
        assertEquals(
            listOf(
                TsPacketizer.STREAM_TYPE_H264 to TsPacketizer.PID_VIDEO,
                TsPacketizer.STREAM_TYPE_AAC to TsPacketizer.PID_AUDIO,
            ),
            entries,
        )
    }

    @Test
    fun `pat and pmt carry a valid mpeg2 crc32`() {
        // ISO 13818-1 annex A: poly 0x04C11DB7, MSB-first, init 0xFFFFFFFF,
        // no final inversion. The zip algorithm (java.util.zip.CRC32) is a
        // DIFFERENT checksum — the earlier muxer shipped it, the zip-based
        // check here passed it, and media3's SectionReader silently dropped
        // every PAT/PMT (the HLS stall on the loopback E2E). hls.js never
        // verifies PSI CRCs, which is why only ExoPlayer stalled.
        val packets = parse(TsPacketizer.videoAuToTs(listOf(ByteArray(4)), pts90k = 0L))
        for (pid in listOf(TsPacketizer.PID_PAT, TsPacketizer.PID_PMT)) {
            val payload = packets.first { it.pid == pid }.payload
            // PUSI PSI packets open with the mandatory pointer_field.
            assertEquals(0, payload[0].toInt())
            val section = payload.copyOfRange(1, payload.size)
            val actual = readCrc32(section.copyOfRange(section.size - 4, section.size))
            assertEquals("CRC on PID $pid", mpeg2Crc32(section.copyOfRange(0, section.size - 4)), actual)
        }
    }

    @Test
    fun `crc32 is the mpeg2 psi variant`() {
        // The standard check value pins the exact algorithm: the zip CRC of
        // the same bytes is CBF43926, and the reflected-register bit-reverse
        // shortcut is also wrong (refin=false PSI needs bit-reversed input
        // bytes — not worth the confusion).
        assertEquals(0x0376E6E7, mpeg2Crc32("123456789".toByteArray(Charsets.US_ASCII)))
    }

    private fun readCrc32(bytes: ByteArray): Int = ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
}

/** MSB-first CRC-32/MPEG-2 (ISO 13818-1 annex A) over [data]. */
internal fun mpeg2Crc32(data: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    for (b in data) {
        crc = crc xor ((b.toInt() and 0xFF) shl 24)
        repeat(8) {
            crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
        }
    }
    return crc
}