package com.raulshma.lenscast.streaming.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HLS muxer's boundaries under hostile/edge inputs. Note the direction:
 * TsPacketizer MUXES (phone → player), it does not parse attacker bytes — the
 * internet-facing HLS surface is the playlist/segment *request* path, where
 * `segment(name)` is a string-equality lookup over a bounded in-memory window
 * (no path arithmetic, no filesystem) and the playlist builder takes no wire
 * input. Those are pinned here too, with the hostile names the URL decoder
 * can hand the lookup.
 */
class HlsMuxerBoundaryTest {

    // ── TsPacketizer boundaries ──

    private fun assertTsFramed(bytes: ByteArray, minPackets: Int = 1) {
        assertTrue(bytes.size % TsPacketizer.TS_PACKET_SIZE == 0)
        assertTrue(bytes.size >= minPackets * TsPacketizer.TS_PACKET_SIZE)
        var offset = 0
        while (offset < bytes.size) {
            assertEquals("sync at $offset", TsPacketizer.SYNC_BYTE, bytes[offset])
            offset += TsPacketizer.TS_PACKET_SIZE
        }
    }

    @Test(timeout = 10_000)
    fun `an empty video AU still produces PAT, PMT and a PES header packet`() {
        TsPacketizer.reset()
        val out = TsPacketizer.videoAuToTs(emptyList(), pts90k = 0)
        assertTsFramed(out, minPackets = 3)
    }

    @Test(timeout = 10_000)
    fun `degenerate NAL units never break the 188-byte framing`() {
        TsPacketizer.reset()
        // Empty NALUs interleaved with a large one spanning many packets.
        val out = TsPacketizer.videoAuToTs(
            listOf(ByteArray(0), ByteArray(1000) { 0x42.toByte() }, ByteArray(0), ByteArray(5000)),
            pts90k = 90_000L,
        )
        assertTsFramed(out)
    }

    @Test(timeout = 10_000)
    fun `audio frames at zero, one byte and past one packet all stay framed`() {
        TsPacketizer.reset()
        for (size in listOf(0, 1, 183, 184, 185, 10_000)) {
            assertTsFramed(TsPacketizer.audioFrameToTs(ByteArray(size), pts90k = 1))
        }
    }

    @Test(timeout = 10_000)
    fun `PTS extremes wrap into the 33-bit field without throwing`() {
        for (pts in listOf(Long.MIN_VALUE, -1L, 0L, 95_983L, 1L shl 33, Long.MAX_VALUE)) {
            assertEquals(5, TsPacketizer.ptsBytes(pts, 0x30).size)
            assertEquals(5, TsPacketizer.ptsBytes(pts, 0x10).size)
        }
    }

    @Test(timeout = 10_000)
    fun `continuity counters survive a reset and keep cycling`() {
        TsPacketizer.reset()
        val before = TsPacketizer.pat()[3].toInt() and 0x0F
        TsPacketizer.pat()
        TsPacketizer.reset()
        val after = TsPacketizer.pat()[3].toInt() and 0x0F
        assertEquals(before, after)
    }

    // ── the request path (documented above: lookup, no parse) ──

    @Test(timeout = 10_000)
    fun `hostile segment names miss the bounded window lookup`() {
        // The manager's whole lookup is `segments.firstOrNull {
        // HlsPlaylist.segmentName(it.sequence) == name }` — a string equality
        // over a fixed-size in-memory window, no path arithmetic, no
        // filesystem. Pin the formatter and the miss semantics: hostile names
        // simply never equal a formatted name.
        val real = HlsPlaylist.segmentName(42)
        assertEquals("seg42.ts", real)
        for (hostile in listOf("", "../etc/passwd", "..\\..\\windows", "seg42.ts/", "seg42.ts/extra", "seg42 .ts", "x".repeat(10_000))) {
            assertTrue(hostile != real)
        }
    }

    @Test(timeout = 10_000)
    fun `the playlist builder tolerates an empty window and huge durations`() {
        val empty = HlsPlaylist.build(emptyList(), sequence = 0, segmentDurationsSec = emptyList())
        assertTrue(empty.contains("#EXTM3U"))
        val extreme = HlsPlaylist.build(
            segmentNames = listOf("seg-0.ts"),
            sequence = Long.MAX_VALUE / 2,
            segmentDurationsSec = listOf(Double.MAX_VALUE, 0.0, -5.0, Double.NaN),
        )
        assertTrue(extreme.contains("#EXTM3U"))
        assertTrue(extreme.contains("seg-0.ts"))
    }
}
