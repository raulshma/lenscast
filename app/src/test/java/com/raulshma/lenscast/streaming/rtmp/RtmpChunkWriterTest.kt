package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class RtmpChunkWriterTest {

    private fun write(payload: ByteArray, block: (RtmpChunkWriter, ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        block(writer, out)
        return out.toByteArray()
    }

    // ── header format selection ──

    @Test
    fun `a stream's first message is an fmt0 header with a little-endian stream id`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = write(payload) { w, out ->
            val written = w.write(csid = 3, timestampMs = 0, typeId = 20, streamId = 0, payload = payload)
            assertEquals(12 + payload.size, written)
            assertEquals(out.size(), written)
        }
        assertEquals(0x03, bytes[0].toInt()) // fmt0, csid 3
        assertEquals(0, bytes[1].toInt()); assertEquals(0, bytes[2].toInt()); assertEquals(0, bytes[3].toInt()) // ts 0
        assertEquals(0, bytes[4].toInt()); assertEquals(0, bytes[5].toInt()); assertEquals(4, bytes[6].toInt()) // len 4
        assertEquals(20, bytes[7].toInt()) // type
        // Message stream id is the one little-endian field: streamId 1 → 01 00 00 00.
        val withStreamId = write(payload) { w, _ -> w.write(3, 0, 20, 1, payload) }
        assertEquals(1, withStreamId[8].toInt())
        assertEquals(0, withStreamId[9].toInt())
        assertEquals(0, withStreamId[10].toInt())
        assertEquals(0, withStreamId[11].toInt())
    }

    @Test
    fun `same size and type with a moved timestamp uses fmt2 carrying only the delta`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = write(payload) { w, _ ->
            w.write(3, 1000, 20, 0, payload)
            w.write(3, 2000, 20, 0, payload) // delta 1000 = 0x0003E8
        }
        assertEquals(0x03, bytes[0].toInt())
        // Message 1 is a full chunk: 12-byte fmt0 header + 4-byte payload, so
        // message 2's fmt2 header starts at 16.
        assertEquals(0x80 or 3, bytes[16].toInt() and 0xFF) // fmt2, csid 3
        assertEquals(0x00, bytes[17].toInt()); assertEquals(0x03, bytes[18].toInt()); assertEquals(0xE8, bytes[19].toInt() and 0xFF)
        assertEquals(12 + 4 + 1 + 3 + 4, bytes.size)
    }

    @Test
    fun `nothing moved at all is the bare fmt3 byte`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = write(payload) { w, _ ->
            w.write(3, 1000, 20, 0, payload) // fmt0 (state.delta starts at -1)
            w.write(3, 1000, 20, 0, payload) // fmt2: delta 0 over the fmt0
            w.write(3, 1000, 20, 0, payload) // delta 0 == the stored delta → bare fmt3
        }
        assertEquals(0x80 or 3, bytes[16].toInt() and 0xFF) // msg2's fmt2 (1 + 3 delta + 4 payload)
        assertEquals(0xC3, bytes[16 + 1 + 3 + 4].toInt() and 0xFF) // msg3's bare fmt3
        assertEquals(12 + 4 + 1 + 3 + 4 + 1 + 4, bytes.size)
    }

    @Test
    fun `a moved size or type uses fmt1 with delta length and type`() {
        val bytes = write(ByteArray(0)) { w, _ ->
            w.write(3, 1000, 20, 0, byteArrayOf(1, 2, 3, 4))
            w.write(3, 2000, 8, 0, byteArrayOf(5, 6, 7, 8)) // delta 1000, len 4, type 8
        }
        assertEquals(0x43, bytes[16].toInt() and 0xFF) // fmt1, csid 3
        assertEquals(0x00, bytes[17].toInt()); assertEquals(0x03, bytes[18].toInt()); assertEquals(0xE8, bytes[19].toInt() and 0xFF)
        assertEquals(0, bytes[20].toInt()); assertEquals(0, bytes[21].toInt()); assertEquals(4, bytes[22].toInt())
        assertEquals(8, bytes[23].toInt())
    }

    // ── large-message continuation ──

    @Test
    fun `a message larger than the chunk size continues as fmt3 chunks`() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = write(payload) { w, out ->
            val written = w.write(csid = 6, timestampMs = 0, typeId = 9, streamId = 1, payload = payload)
            // fmt0 (12) + 128 + fmt3 (1) + 128 + fmt3 (1) + 44
            assertEquals(12 + 128 + 1 + 128 + 1 + 44, written)
            assertEquals(out.size(), written)
        }
        assertEquals(0x06, bytes[0].toInt()) // fmt0, csid 6
        assertEquals(0xC6, bytes[12 + 128].toInt() and 0xFF) // first continuation
        assertEquals(0xC6, bytes[12 + 128 + 1 + 128].toInt() and 0xFF) // second continuation
        assertEquals(12 + 128 + 1 + 128 + 1 + 44, bytes.size)
    }

    @Test
    fun `a raised chunk size keeps a message in one chunk`() {
        val payload = ByteArray(300)
        val bytes = write(payload) { w, _ ->
            w.setChunkSize(4096)
            assertEquals(4096, w.currentChunkSize())
            w.write(6, 0, 9, 1, payload)
        }
        assertEquals(12 + 300, bytes.size)
    }

    @Test
    fun `chunk size bounds are enforced`() {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        try {
            writer.setChunkSize(0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            writer.setChunkSize(0x1000000)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── extended timestamps ──

    @Test
    fun `an extended timestamp is the sentinel plus four bytes`() {
        val bytes = write(ByteArray(0)) { w, _ ->
            w.write(3, 0x1000000, 20, 0, byteArrayOf(1, 2)) // 16777216 ≥ 0xFFFFFF
        }
        assertEquals(0x03, bytes[0].toInt())
        assertEquals(0xFF, bytes[1].toInt() and 0xFF); assertEquals(0xFF, bytes[2].toInt() and 0xFF); assertEquals(0xFF, bytes[3].toInt() and 0xFF)
        assertEquals(0x01, bytes[12].toInt()); assertEquals(0, bytes[13].toInt())
        assertEquals(0, bytes[14].toInt()); assertEquals(0, bytes[15].toInt())
        assertEquals(12 + 4 + 2, bytes.size)
    }

    @Test
    fun `continuation chunks repeat the extended timestamp`() {
        val payload = ByteArray(300)
        val bytes = write(payload) { w, _ ->
            w.write(6, 0x1000000, 9, 1, payload)
        }
        // Chunks: fmt0+ext+128, fmt3+ext+128, fmt3+ext+44.
        assertEquals(12 + 4 + 128 + 1 + 4 + 128 + 1 + 4 + 44, bytes.size)
        // Each continuation is fmt3, then the repeated 0x01000000.
        assertEquals(0xC6, bytes[12 + 4 + 128].toInt() and 0xFF)
        assertEquals(0x01, bytes[12 + 4 + 128 + 1].toInt())
        assertEquals(0xC6, bytes[12 + 4 + 128 + 1 + 4 + 128].toInt() and 0xFF)
        assertEquals(0x01, bytes[12 + 4 + 128 + 1 + 4 + 128 + 1].toInt())
    }

    @Test
    fun `an out-of-range csid is refused`() {
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        try {
            writer.write(csid = 64, timestampMs = 0, typeId = 20, streamId = 0, payload = ByteArray(0))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `an empty payload writes exactly one header`() {
        val bytes = write(ByteArray(0)) { w, _ ->
            w.write(3, 0, 20, 0, ByteArray(0))
        }
        assertEquals(12, bytes.size)
    }
}
