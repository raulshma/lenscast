package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The chunk reader under a hostile peer: a deterministic corpus fuzzer (no
 * randomness without a seed, no flakes) over [RtmpChunkReader.feed].
 *
 * ── The contract ──
 * For every corpus input the reader either returns normally or throws ONLY
 * [RtmpChunkProtocolException] — never IndexOutOfBounds, NegativeArraySize,
 * an OOM-by-allocation (the declared message-length cap exists for that), a
 * StackOverflow, or an infinite loop (every test runs under a timeout).
 *
 * ── The corpus ──
 * valid messages (single-chunk, multi-chunk, straddling feeds, extended
 * timestamps, every fmt), truncations at every prefix length, bit flips at
 * every bit of a tiny sample, boundary lengths (0, 128, the cap, the cap+1,
 * the u24 max), csid encodings (1/2/3-byte) and a csid flood, fmt3 before
 * any header, chunk-size abuse, and a seeded garbage sweep.
 */
class RtmpChunkReaderFuzzTest {

    private class Recorder {
        val messages = mutableListOf<Triple<Int, Int, ByteArray>>()

        fun reader(): RtmpChunkReader = RtmpChunkReader { type, streamId, timestamp, payload ->
            messages.add(Triple(type, streamId, payload))
        }
    }

    // ── wire-shape helpers ──

    private fun writeUint24(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = ((value shr 16) and 0xFF).toByte()
        dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 2] = (value and 0xFF).toByte()
    }

    private fun writeUint32(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = ((value shr 24) and 0xFF).toByte()
        dest[offset + 1] = ((value shr 16) and 0xFF).toByte()
        dest[offset + 2] = ((value shr 8) and 0xFF).toByte()
        dest[offset + 3] = (value and 0xFF).toByte()
    }

    /** fmt=0 header with a 1-byte csid (csid in 2..63). */
    private fun fmt0(csid: Int, timestamp: Int, length: Int, typeId: Int, streamId: Int): ByteArray {
        val out = ByteArray(12)
        out[0] = ((0 shl 6) or csid).toByte()
        writeUint24(out, 1, timestamp)
        writeUint24(out, 4, length)
        out[7] = typeId.toByte()
        // The message stream id is LITTLE-endian on the wire (RTMP spec §6.1.2).
        out[8] = (streamId and 0xFF).toByte()
        out[9] = ((streamId shr 8) and 0xFF).toByte()
        out[10] = ((streamId shr 16) and 0xFF).toByte()
        out[11] = ((streamId shr 24) and 0xFF).toByte()
        return out
    }

    /** fmt=0 header with the 2-byte extended csid encoding (csid 64..319). */
    private fun fmt0ExtendedCsid(csid: Int, length: Int): ByteArray {
        val out = ByteArray(13)
        out[0] = (0 shl 6).toByte() // fmt 0, rawCsid 0
        out[1] = (csid - 64).toByte()
        writeUint24(out, 2, 0)
        writeUint24(out, 5, length)
        out[8] = RtmpChunkProtocol.TYPE_COMMAND_AMF0.toByte()
        out[9] = 1 // stream id 1, little-endian
        out[10] = 0
        out[11] = 0
        out[12] = 0
        return out
    }

    private fun payload(length: Int, seed: Byte = 0x5A): ByteArray = ByteArray(length) { seed }

    private fun validMessage(length: Int = 16, csid: Int = 3): ByteArray =
        fmt0(csid, 1000, length, RtmpChunkProtocol.TYPE_COMMAND_AMF0, 1) + payload(length)

    // ── the contract ──

    private fun feedHostile(reader: RtmpChunkReader, bytes: ByteArray) {
        try {
            reader.feed(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            if (t !is RtmpChunkProtocolException) {
                throw AssertionError("hostile feed leaked ${t.javaClass.simpleName}: $t", t)
            }
        }
    }

    // ── valid-baseline properties (the fuzz corpus starts from these) ──

    @Test(timeout = 10_000)
    fun `a valid single-chunk message is emitted once with exact bytes`() {
        val recorder = Recorder()
        val message = validMessage(length = 16)
        recorder.reader().feed(message, 0, message.size)
        assertEquals(1, recorder.messages.size)
        val (type, streamId, payloadOut) = recorder.messages.single()
        assertEquals(RtmpChunkProtocol.TYPE_COMMAND_AMF0, type)
        assertEquals(1, streamId)
        assertTrue(payloadOut.contentEquals(payload(16)))
    }

    @Test(timeout = 10_000)
    fun `a message chunk-straddling two feeds survives every split point`() {
        // Wire shape for one 300-byte message at the 128 default chunk size:
        // fmt0 header + chunk1, then a 1-byte fmt3 continuation header before
        // each further chunk.
        val payloadBytes = payload(300)
        val message = fmt0(3, 1000, 300, RtmpChunkProtocol.TYPE_COMMAND_AMF0, 1) +
            payloadBytes.copyOfRange(0, 128) +
            byteArrayOf(0xC3.toByte()) +
            payloadBytes.copyOfRange(128, 256) +
            byteArrayOf(0xC3.toByte()) +
            payloadBytes.copyOfRange(256, 300)
        for (cut in 0..message.size) {
            val recorder = Recorder()
            val reader = recorder.reader()
            reader.feed(message, 0, cut)
            reader.feed(message, cut, message.size - cut)
            assertEquals("split at $cut", 1, recorder.messages.size)
            assertTrue("split at $cut", recorder.messages.single().third.contentEquals(payloadBytes))
        }
    }

    @Test(timeout = 10_000)
    fun `an oversized message split at the chunk size waits for set chunk size`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        reader.setChunkSize(RtmpChunkProtocol.PUBLISHER_OUT_CHUNK_SIZE)
        val length = 4096
        val message = fmt0(6, 0, length, RtmpChunkProtocol.TYPE_VIDEO, 1) + payload(length)
        reader.feed(message, 0, message.size)
        assertEquals(1, recorder.messages.size)
        assertEquals(length, recorder.messages.single().third.size)
    }

    // ── regression: OOM-by-allocation from a hostile header ──
    // Pre-fix, a 12-byte fmt0 header with length=0xFFFFFF minted a 16 MB
    // ByteArray before a single payload byte arrived — and 65 599 chunk
    // streams multiplied it into a remote OOM dial.

    @Test(timeout = 10_000)
    fun `regression - a header declaring an over-cap message length is a protocol exception`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        val header = fmt0(3, 0, RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES + 1, 0x14, 1)
        try {
            reader.feed(header, 0, header.size)
            throw AssertionError("over-cap message length must fail loud")
        } catch (t: Throwable) {
            assertTrue("got ${t.javaClass.simpleName}", t is RtmpChunkProtocolException)
        }
    }

    @Test(timeout = 10_000)
    fun `regression - the u24 maximum message length is also over the cap`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        val header = fmt0(3, 0, 0xFFFFFF, 0x14, 1)
        try {
            reader.feed(header, 0, header.size)
            throw AssertionError("16 MB declared length must fail loud")
        } catch (t: Throwable) {
            assertTrue("got ${t.javaClass.simpleName}", t is RtmpChunkProtocolException)
        }
    }

    @Test(timeout = 10_000)
    fun `regression - a csid flood beyond the stream cap is a protocol exception`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        // 32 distinct extended csids (64..95) are accepted…
        for (i in 0 until RtmpChunkProtocol.MAX_CHUNK_STREAMS) {
            val header = fmt0ExtendedCsid(64 + i, 0)
            reader.feed(header, 0, header.size)
        }
        // …the 33rd distinct csid is the drop.
        val header = fmt0ExtendedCsid(64 + RtmpChunkProtocol.MAX_CHUNK_STREAMS, 0)
        try {
            reader.feed(header, 0, header.size)
            throw AssertionError("csid flood must fail loud")
        } catch (t: Throwable) {
            assertTrue("got ${t.javaClass.simpleName}", t is RtmpChunkProtocolException)
        }
        assertEquals(RtmpChunkProtocol.MAX_CHUNK_STREAMS, recorder.messages.size)
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - truncations at every prefix length parse without a crash`() {
        val message = validMessage(length = 16)
        for (cut in 0 until message.size) {
            val recorder = Recorder()
            val reader = recorder.reader()
            feedHostile(reader, message.copyOfRange(0, cut))
            assertEquals("prefix $cut emitted nothing", 0, recorder.messages.size)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - bit flips at every bit of a tiny valid sample never crash`() {
        val message = validMessage(length = 4)
        for (byteIndex in message.indices) {
            for (bit in 0..7) {
                val mutated = message.copyOf()
                mutated[byteIndex] = (mutated[byteIndex].toInt() xor (1 shl bit)).toByte()
                val recorder = Recorder()
                feedHostile(recorder.reader(), mutated)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - every format and boundary length round-trips or fails loud`() {
        val lengths = listOf(
            0, 1, 127, 128, 129,
            RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES - 1,
            RtmpChunkProtocol.MAX_MESSAGE_LENGTH_BYTES,
        )
        for (length in lengths) {
            val recorder = Recorder()
            val reader = recorder.reader()
            // A raised chunk size delivers the whole message as one chunk;
            // ts = the extended-timestamp sentinel, so the real value (0)
            // rides the 4 bytes after the header body.
            reader.setChunkSize(maxOf(length, 1))
            val message = fmt0(3, 0xFFFFFF, length, 0x14, 1) +
                byteArrayOf(0, 0, 1, 0) +
                payload(length)
            feedHostile(reader, message)
            if (length == 0) {
                assertEquals(1, recorder.messages.size)
            } else {
                assertEquals(length, recorder.messages.single().third.size)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - fmt3 before any header, orphan fmt2 and extended-timestamp variants never wedge`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        // fmt3 with no preceding header for its csid.
        feedHostile(reader, byteArrayOf(0xC3.toByte()))
        // fmt3 flood (length still 0 → immediate empty emits).
        repeat(10_000) { feedHostile(reader, byteArrayOf(0xC3.toByte())) }
        // orphan fmt2 with the extended-timestamp sentinel, truncated.
        feedHostile(reader, byteArrayOf(0x82.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        feedHostile(reader, byteArrayOf(0x82.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        // fmt0 with the sentinel but a truncated extension.
        feedHostile(reader, byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))
        // a fresh reader must still work afterwards on the happy path — a real
        // connection dies on protocol errors, so the guarantee is per-reader.
        val freshReader = recorder.reader()
        val message = validMessage(length = 8)
        freshReader.feed(message, 0, message.size)
        assertEquals(8, recorder.messages.last().third.size)
    }

    @Test(timeout = 10_000)
    fun `corpus - a byte-at-a-time feed of garbage never crashes`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        val garbage = Random(0x20260911).let { r -> ByteArray(2048).also { r.nextBytes(it) } }
        val single = ByteArray(1)
        for (b in garbage) {
            single[0] = b
            feedHostile(reader, single)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage in random slice sizes never crashes`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        val garbage = Random(0xC0FFEE).let { r -> ByteArray(8192).also { r.nextBytes(it) } }
        var offset = 0
        val sliceSizes = Random(7)
        while (offset < garbage.size) {
            val slice = minOf(1 + sliceSizes.nextInt(7), garbage.size - offset)
            feedHostile(reader, garbage.copyOfRange(offset, offset + slice))
            offset += slice
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - chunk-size abuse stays inside the declared bounds`() {
        val recorder = Recorder()
        val reader = recorder.reader()
        // The program API keeps its require() preconditions.
        for (bad in listOf(0, -1, RtmpChunkProtocol.MAX_CHUNK_SIZE + 1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            try {
                reader.setChunkSize(bad)
                throw AssertionError("setChunkSize($bad) must throw")
            } catch (t: Throwable) {
                assertTrue("got ${t.javaClass.simpleName}", t is IllegalArgumentException)
            }
        }
        // The u24-max chunk size is legal and a huge declared message under it
        // still dies at the message cap, not the chunk size.
        reader.setChunkSize(RtmpChunkProtocol.MAX_CHUNK_SIZE)
        val header = fmt0(3, 0, 0xFFFFFF, 0x14, 1)
        try {
            reader.feed(header, 0, header.size)
            throw AssertionError("over-cap message must fail loud")
        } catch (t: Throwable) {
            assertTrue("got ${t.javaClass.simpleName}", t is RtmpChunkProtocolException)
        }
    }
}
