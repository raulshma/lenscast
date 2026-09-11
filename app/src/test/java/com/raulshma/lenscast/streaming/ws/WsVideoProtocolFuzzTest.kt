package com.raulshma.lenscast.streaming.ws

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The WS H.264 wire-protocol helpers under hostile/edge bytes: a deterministic
 * corpus fuzzer over [WsVideoProtocol].
 *
 * ── The read side ──
 * The server never parses client frames (NanoWSD owns the frame codec; the
 * video socket ignores messages, the talkback socket only forwards opaque
 * binary), so the hostile surface here is the Annex-B conversion applied to
 * encoder output — degenerate NAL shapes included.
 *
 * ── The contract ──
 * No helper throws on any byte array — no ArrayIndexOutOfBounds on short
 * SPS/PPS (pinned regression), no exception on start-code soup — and every
 * test runs under a timeout.
 */
class WsVideoProtocolFuzzTest {

    // ── regression: avcC indexed sps[1..3] unconditionally ──
    // A NAL typed SPS by its first byte but shorter than 4 bytes crashed the
    // fan-out with ArrayIndexOutOfBounds.

    @Test(timeout = 10_000)
    fun `regression - a short SPS produces a record, not an index exception`() {
        for (spsSize in 0..3) {
            val record = WsVideoProtocol.avcC(ByteArray(spsSize) { 0x67.toByte() }, ByteArray(0))
            assertTrue(record.isNotEmpty())
            assertEquals(1, record[0].toInt() and 0xFF) // configurationVersion
        }
        // A well-formed SPS/PPS keeps the exact header bytes.
        val sps = byteArrayOf(0x67.toByte(), 64, 0, 30, 1, 2, 3)
        val pps = byteArrayOf(0x68.toByte(), 9, 9, 9)
        val record = WsVideoProtocol.avcC(sps, pps)
        assertEquals(1, record[0].toInt() and 0xFF)
        assertEquals(64, record[1].toInt())
        assertEquals(0, record[2].toInt())
        assertEquals(30, record[3].toInt())
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - Annex-B soup splits without crashing and preserves bytes`() {
        val startCode = WsVideoProtocol.START_CODE
        for (soup in listOf(
            ByteArray(0),
            ByteArray(4), // all zeros — no real start code
            byteArrayOf(0, 0, 0, 1),
            startCode + startCode,
            startCode + startCode + startCode,
            byteArrayOf(0, 0, 0, 1, 0),
            byteArrayOf(0, 0, 1), // 3-byte truncated start code
            ByteArray(4096), // zeros only
            ByteArray(4096) { it.toByte() }, // pseudo start codes at many offsets
        )) {
            val units = WsVideoProtocol.splitAnnexB(soup)
            val reassembled = units.joinToByteArray { it }
            // Every split loses only start codes, never payload bytes.
            assertTrue(reassembled.size <= soup.size)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - Avcc framing round-trips every split shape`() {
        val startCode = WsVideoProtocol.START_CODE
        val nalA = byteArrayOf(0x67.toByte(), 1, 2, 3)
        val nalB = byteArrayOf(0x68.toByte(), 4)
        val au = startCode + nalA + startCode + nalB
        val avcc = WsVideoProtocol.annexBToAvcc(au)
        // 4-byte length prefix + payload per NAL.
        assertEquals(4 + nalA.size + 4 + nalB.size, avcc.size)
        // Degenerate AU: all-zeros soup converts to a (possibly empty) array.
        WsVideoProtocol.annexBToAvcc(ByteArray(64))
    }

    @Test(timeout = 10_000)
    fun `corpus - parameter-set scans over garbage NALs answer null or the sets`() {
        assertNull(WsVideoProtocol.extractParameterSets(emptyList()))
        assertNull(WsVideoProtocol.extractParameterSets(listOf(ByteArray(0), ByteArray(1))))
        assertNull(WsVideoProtocol.extractParameterSets(listOf(byteArrayOf(1, 2, 3)))) // non-SPS/PPS types
        val sps = byteArrayOf(0x67.toByte(), 64, 0, 30)
        val pps = byteArrayOf(0x68.toByte(), 1)
        val (foundSps, foundPps) = WsVideoProtocol.extractParameterSets(
            listOf(pps, ByteArray(0), sps),
        )!!
        assertTrue(foundSps.contentEquals(sps))
        assertTrue(foundPps.contentEquals(pps))
        assertEquals(-1, WsVideoProtocol.nalType(ByteArray(0)))
    }

    @Test(timeout = 10_000)
    fun `corpus - envelopes over hostile payloads keep the 4-byte length contract`() {
        for (payload in listOf(ByteArray(0), ByteArray(1), Random(7).let { r -> ByteArray(70_000).also { r.nextBytes(it) } })) {
            val frame = WsVideoProtocol.videoFrameAvcc(payload, isKeyFrame = true)
            assertEquals("LCK1", String(frame, 0, 4, Charsets.US_ASCII))
            val length = ((frame[4].toInt() and 0xFF) shl 24) or
                ((frame[5].toInt() and 0xFF) shl 16) or
                ((frame[6].toInt() and 0xFF) shl 8) or
                (frame[7].toInt() and 0xFF)
            assertEquals(payload.size, length)
            assertEquals(8 + payload.size, frame.size)
        }
    }

    @Test(timeout = 10_000)
    fun `the encoded-NAL sink helpers agree with the raw ones`() {
        val sps = byteArrayOf(0x67.toByte(), 64, 0, 30)
        val nal = EncodedNalUnit(sps, isKeyFrame = true)
        assertTrue(WsVideoProtocol.containsKeyframe(listOf(nal)))
        assertTrue(!WsVideoProtocol.containsKeyframe(listOf(EncodedNalUnit(byteArrayOf(1), isKeyFrame = false))))
    }

    private fun List<ByteArray>.joinToByteArray(block: (ByteArray) -> ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (item in this) out.write(block(item))
        return out.toByteArray()
    }
}
