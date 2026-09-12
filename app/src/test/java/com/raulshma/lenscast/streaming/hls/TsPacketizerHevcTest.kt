package com.raulshma.lenscast.streaming.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * JVM tests for the TS muxer's codec-aware PMT ([TsPacketizer]): the HEVC
 * stream-type flip keeps the PAT/PMT structure intact so Safari-grade HEVC
 * HLS rides the same muxer, and the H.264 default survives untouched.
 */
class TsPacketizerHevcTest {

    @After
    fun tearDown() {
        TsPacketizer.reset()
        TsPacketizer.setVideoStreamType(TsPacketizer.STREAM_TYPE_H264)
    }

    /**
     * The stream_type byte in the muxer's minimal PMT section: the section
     * carries a 10-byte fixed header (table id, length, program number,
     * version, section numbers, PCR PID, program_info_length), so the video
     * stream_type lands at section offset 10, packet index 4 + 10.
     */
    private fun pmtStreamType(): Int {
        val pmt = TsPacketizer.pmt()
        // The single-packet fast path writes the section straight after the
        // 4-byte TS header (no pointer field at payload_start with zero
        // offset — tsPacket copies chunk to pkt[4]).
        val sectionStart = 4
        val streamTypeOffset = 10
        assertEquals(0x02, pmt[sectionStart].toInt() and 0xFF) // table_id
        return pmt[sectionStart + streamTypeOffset].toInt() and 0xFF
    }

    private fun assertAudioStreamTypeIsAac() {
        // The AAC stream_type follows the 5-byte video ES entry in the section.
        val pmt = TsPacketizer.pmt()
        val sectionStart = 4
        assertEquals(
            TsPacketizer.STREAM_TYPE_AAC,
            pmt[sectionStart + 10 + 5].toInt() and 0xFF,
        )
    }

    @Test
    fun `default PMT declares H264 video and AAC audio`() {
        assertEquals(TsPacketizer.STREAM_TYPE_H264, pmtStreamType())
        assertAudioStreamTypeIsAac()
    }

    @Test
    fun `flipping the stream type to HEVC lands in the PMT`() {
        TsPacketizer.setVideoStreamType(TsPacketizer.STREAM_TYPE_HEVC)
        assertEquals(TsPacketizer.STREAM_TYPE_HEVC, pmtStreamType())
        assertAudioStreamTypeIsAac()
    }

    @Test
    fun `reset keeps the chosen stream type`() {
        // The stream type is deliberately NOT a reset-owned field: the codec
        // flip is manager-owned and must survive ring resets.
        TsPacketizer.setVideoStreamType(TsPacketizer.STREAM_TYPE_HEVC)
        TsPacketizer.reset()
        assertEquals(TsPacketizer.STREAM_TYPE_HEVC, TsPacketizer.currentVideoStreamType())
    }

    @Test
    fun `HEVC access units mux through the same Annex-B path`() {
        TsPacketizer.setVideoStreamType(TsPacketizer.STREAM_TYPE_HEVC)
        // One HEVC AU: VPS + SPS + PPS + IDR slice (2-byte-header NALs).
        val vps = byteArrayOf(0x40.toByte(), 0x01, 0x0C, 0x01)
        val sps = byteArrayOf(0x42.toByte(), 0x01, 0x01, 0x01, 0x60.toByte())
        val pps = byteArrayOf(0x44.toByte(), 0x01, 0xC0.toByte())
        val idr = byteArrayOf(0x26.toByte(), 0x01, 0xAF.toByte(), 0x12, 0x34)
        val packets = TsPacketizer.videoAuToTs(listOf(vps, sps, pps, idr), pts90k = 90_000)

        // Packet framing: sync byte every 188 bytes, video PES stream id 0xE0.
        assertEquals(0, packets.size % TsPacketizer.TS_PACKET_SIZE)
        for (offset in packets.indices step TsPacketizer.TS_PACKET_SIZE) {
            assertEquals(TsPacketizer.SYNC_BYTE, packets[offset])
        }
        val pesStart = intArrayOf(0x00, 0x00, 0x01, 0xE0)
        var found = false
        for (i in 0..packets.size - 4) {
            if ((0..3).all { packets[i + it ] == pesStart[it].toByte() }) {
                found = true
                break
            }
        }
        assertTrue("muxed bytes must carry the video PES header", found)
    }
}
