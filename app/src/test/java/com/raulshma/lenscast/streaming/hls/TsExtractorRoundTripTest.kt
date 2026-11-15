package com.raulshma.lenscast.streaming.hls

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The end-to-end demux contract: media3's own [TsExtractor] — the demuxer the
 * companion HLS rung ships — must emit real samples from a segment the
 * device's muxer produced (shipped fixture: the first 256 KB of a live
 * on-device segment). Byte-level contracts ([HlsTsDecodabilityTest]) pin the
 * muxer's bytes; this pins what a demanding demuxer DOES with them. The
 * on-device failure this guards: segments loaded fully, zero samples parsed,
 * the player parked in BUFFERING forever (root cause: the muxer's
 * zip-flavored PSI CRCs — media3's SectionReader dropped every PAT/PMT;
 * hls.js never verifies PSI CRCs, which is why only this rung stalled).
 */
@RunWith(RobolectricTestRunner::class)
class TsExtractorRoundTripTest {

    private class CollectingOutput : ExtractorOutput {
        val formats = mutableListOf<Format>()
        val sampleTimestamps = mutableListOf<Long>()
        val tracks = mutableListOf<Pair<Int, Int>>()
        var sampleCount = 0
        var trackCount = 0
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput {
            tracks.add(id to type)
            return object : TrackOutput {
                override fun format(format: Format) {
                    if (formats.none { it.sampleMimeType == format.sampleMimeType }) formats.add(format)
                }

                override fun sampleData(
                    data: androidx.media3.common.DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val skipped = ByteArray(length)
                    val read = data.read(skipped, 0, length)
                    if (read < length && !allowEndOfInput) throw java.io.EOFException()
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    data.skipBytes(length)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    sampleCount++
                    sampleTimestamps.add(timeUs)
                }
            }
        }

        override fun endTracks() {
            trackCount++
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private fun openedInput(segment: ByteArray, position: Long = 0): DefaultExtractorInput {
        val dataSource = ByteArrayDataSource(segment)
        val opened = dataSource.open(androidx.media3.datasource.DataSpec(Uri.EMPTY, position, -1))
        return DefaultExtractorInput(dataSource, position, segment.size.toLong())
    }

    private fun extract(segment: ByteArray, mode: Int = Int.MIN_VALUE): CollectingOutput {
        val output = CollectingOutput()
        val extractor = if (mode == Int.MIN_VALUE) TsExtractor() else TsExtractor(mode)
        extractor.init(output)
        // Extractors may return RESULT_SEEK (the duration reader does when the
        // input has a known length: it probes PCR timestamps, then asks to be
        // rewound before packet parsing continues). DefaultExtractorInput can't
        // rewind, so serve a fresh input at the requested position each read.
        val holder = PositionHolder()
        var position = 0L
        var reads = 0
        while (reads++ < 1600) {
            val input = openedInput(segment, position)
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> position = holder.position
                else -> position = input.position
            }
        }
        println(
            "extract(mode=${if (mode == Int.MIN_VALUE) "default" else mode}): finished at $position " +
                "of ${segment.size}B after $reads reads, seekHolder=${holder.position}",
        )
        return output
    }

    private fun fixture(): ByteArray = javaClass.classLoader!!
        .getResourceAsStream("hls/segment-fixture.ts")!!
        .readBytes()

    /**
     * Recomputes the MPEG-2 PSI CRC over every in-packet PAT/PMT section
     * ([HlsTsDecodabilityTest.mpeg2Crc32] is the reference implementation).
     * Sections that span packets (never produced by this muxer) are skipped.
     */
    private fun withValidPsiCrcs(segment: ByteArray): ByteArray {
        val out = segment.copyOf()
        var off = 0
        while (off + TsPacketizer.TS_PACKET_SIZE <= out.size) {
            val pid = ((out[off + 1].toInt() and 0x1F) shl 8) or (out[off + 2].toInt() and 0xFF)
            val afc = (out[off + 3].toInt() shr 4) and 3
            var pos = off + 4
            if (afc and 2 != 0) pos += 1 + (out[pos].toInt() and 0xFF)
            val pusi = out[off + 1].toInt() and 0x40 != 0
            if (afc and 1 != 0 && pusi && (pid == 0 || pid == TsPacketizer.PID_PMT) &&
                pos + 4 <= off + TsPacketizer.TS_PACKET_SIZE && out[pos] == 0.toByte()
            ) {
                val sec = pos + 1
                val secLen = ((out[sec + 1].toInt() and 0x0F) shl 8) or (out[sec + 2].toInt() and 0xFF)
                val end = sec + 3 + secLen
                if (end <= off + TsPacketizer.TS_PACKET_SIZE) {
                    val crc = mpeg2Crc32(out.copyOfRange(sec, end - 4))
                    for (i in 0 until 4) out[end - 4 + i] = ((crc ushr (24 - 8 * i)) and 0xFF).toByte()
                }
            }
            off += TsPacketizer.TS_PACKET_SIZE
        }
        return out
    }

    @Test
    fun `media3 demuxes a shipped on-device segment into video and audio samples`() {
        val segment = fixture()
        assertEquals(262_144, segment.size)

        // Recompute the PSI CRCs in place before demuxing. On the shipped
        // fixture — captured from the FIXED muxer — this is a no-op (the
        // muxer writes correct CRCs natively); it stays so a future fixture
        // from a regressed muxer still demuxes like the fleet of deployed
        // players patched around it would.
        val fixed = withValidPsiCrcs(segment)
        val output = extract(fixed)
        // MODE_HLS (what the companion rung drives through the HLS media
        // source) defers sample emission to the wrapper's timestamp adjuster,
        // which a bare input can't exercise — diagnostic only.
        val hlsOutput = extract(fixed, TsExtractor.MODE_HLS)
        println(
            "demux[HLS diagnostic]: formats=${hlsOutput.formats.mapNotNull { it.sampleMimeType }} " +
                "samples=${hlsOutput.sampleCount} tracks=${hlsOutput.tracks}",
        )
        val mimeTypes = output.formats.mapNotNull { it.sampleMimeType }
        assertTrue("expected H264 + AAC tracks, got $mimeTypes", mimeTypes.containsAll(listOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC)))
        assertTrue("samples were emitted (count=${output.sampleCount})", output.sampleCount > 0)
        // Timestamps advance: a muxer that folds every AU onto one instant
        // parses "fine" and still stalls every player.
        val sorted = output.sampleTimestamps.sorted()
        assertTrue("timestamps advance (span=${sorted.last() - sorted.first()}us)", sorted.last() - sorted.first() >= 500_000L)
    }

    @Test
    fun `ts extractor sniffs a shipped on-device segment`() {
        val segment = fixture()
        // The harness itself: the fixture starts on a packet boundary and the
        // opened input serves at least the sniff's 940-byte probe window.
        assertTrue("fixture starts with the TS sync byte", segment[0] == 0x47.toByte())

        val input = openedInput(segment)
        val probe = ByteArray(940)
        input.readFully(probe, 0, 940)
        assertTrue("input serves the fixture", probe[0] == 0x47.toByte())
        for (i in 0 until 5) {
            assertTrue(
                "sync byte at packet $i of the probe window",
                probe[i * 188] == 0x47.toByte(),
            )
        }

        // DefaultMediaSourceFactory sniffs segment media through the default
        // extractors: the muxed TS must be RECOGNIZED, not merely parseable
        // after forcing the type.
        assertTrue("TsExtractor sniffs the segment", TsExtractor().sniff(openedInput(segment)))
    }

    @Test
    fun `ts extractor sniffs the muxer's own bytes`() {
        TsPacketizer.reset()
        // ≥940B: PAT + PMT + a video PES long enough to split across packets.
        val segment = TsPacketizer.videoAuToTs(listOf(ByteArray(800)), 90_000L)
        assertTrue("synthetic segment ≥ the sniff window (${segment.size}B)", segment.size >= 940)
        assertTrue("TsExtractor sniffs the muxer's bytes", TsExtractor().sniff(openedInput(segment)))
    }
}
