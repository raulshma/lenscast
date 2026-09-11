package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class RtmpChunkReaderTest {

    /** The (type, streamId, timestamp, payload) triples [RtmpChunkReader] emitted. */
    private class Messages {
        val items = mutableListOf<Triple<Int, Int, Pair<Int, ByteArray>>>()

        fun add(typeId: Int, streamId: Int, timestampMs: Int, payload: ByteArray) {
            items += Triple(typeId, streamId, timestampMs to payload)
        }
    }

    private fun feedAll(reader: RtmpChunkReader, bytes: ByteArray) {
        reader.feed(bytes, 0, bytes.size)
    }

    // ── the writer → reader round-trip ──

    @Test
    fun `round-trips every header format the writer picks`() {
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        val small = byteArrayOf(1, 2, 3, 4)

        writer.write(3, 0, 20, 0, small) // fmt0: first on the stream
        writer.write(3, 1000, 20, 0, small) // fmt2: same size/type, moved ts (delta 1000)
        writer.write(3, 2000, 20, 0, small) // fmt3: same delta again
        writer.write(3, 4000, 8, 0, byteArrayOf(5, 6, 7, 8)) // fmt1: type moved
        feedAll(reader, out.toByteArray())

        assertEquals(4, messages.items.size)
        // Triple/Pair data-class equality would compare the ByteArray by
        // identity — assert each field (payloads by content) instead.
        fun assertMessage(
            index: Int,
            typeId: Int,
            streamId: Int,
            timestampMs: Int,
            payload: ByteArray,
        ) {
            val item = messages.items[index]
            assertEquals("type at $index", typeId, item.first)
            assertEquals("stream at $index", streamId, item.second)
            assertEquals("timestamp at $index", timestampMs, item.third.first)
            assertArrayEquals("payload at $index", payload, item.third.second)
        }
        assertMessage(0, 20, 0, 0, small) // fmt0
        assertMessage(1, 20, 0, 1000, small) // fmt2
        assertMessage(2, 20, 0, 2000, small) // fmt3
        assertMessage(3, 8, 0, 4000, byteArrayOf(5, 6, 7, 8)) // fmt1
    }

    @Test
    fun `a multi-chunk message reassembles inside a single feed`() {
        // The continuation bug: a fmt3 continuation must accumulate into the
        // partially-received message, never reset its accumulator.
        val payload = ByteArray(300) { (it + 1).toByte() }
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        writer.write(6, 50, 9, 1, payload)
        feedAll(reader, out.toByteArray())

        assertEquals(1, messages.items.size)
        val (typeId, streamId, pair) = messages.items.single()
        assertEquals(9, typeId)
        assertEquals(1, streamId)
        assertEquals(50, pair.first)
        assertArrayEquals(payload, pair.second)
    }

    @Test
    fun `a multi-chunk message survives a feed boundary mid-chunk`() {
        val payload = ByteArray(300) { (it * 3).toByte() }
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val bytes = ByteArrayOutputStream().also {
            RtmpChunkWriter(it).write(6, 0, 9, 1, payload)
        }.toByteArray()

        reader.feed(bytes, 0, 100) // mid first-chunk
        assertEquals(0, messages.items.size)
        reader.feed(bytes, 100, 150) // straddles the first continuation header
        assertEquals(0, messages.items.size)
        reader.feed(bytes, 250, bytes.size - 250)
        assertEquals(1, messages.items.size)
        assertArrayEquals(payload, messages.items.single().third.second)
    }

    @Test
    fun `interleaved chunk streams reassemble independently`() {
        // Hand-built interleave: video msg A (csid 6, 200-byte payload, two
        // chunks), audio msg B (csid 4, 10 bytes) slipped between A's chunks —
        // the wire order a real publisher produces.
        val videoPayload = ByteArray(200) { (it + 10).toByte() }
        val audioPayload = ByteArray(10) { (it + 1).toByte() }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x06, 0, 0, 0, 0, 0x00.toByte(), 0xC8.toByte(), 0x09)) // fmt0 csid6: ts0 len200 type9
        out.write(byteArrayOf(1, 0, 0, 0)) // streamId 1 (LE)
        out.write(videoPayload, 0, 128) // A's first chunk
        out.write(byteArrayOf(0x04, 0, 0, 0, 0, 0, 0x0A, 0x08)) // fmt0 csid4: ts0 len10 type8
        out.write(byteArrayOf(1, 0, 0, 0))
        out.write(audioPayload) // B's whole message
        out.write(byteArrayOf(0xC6.toByte())) // A's continuation
        out.write(videoPayload, 128, 72)

        val messages = Messages()
        RtmpChunkReader(messages::add).feed(out.toByteArray(), 0, out.size())
        assertEquals(2, messages.items.size)
        // B completed first: chunk interleaving must not delay the small stream.
        assertEquals(8, messages.items[0].first)
        assertArrayEquals(audioPayload, messages.items[0].third.second)
        assertEquals(9, messages.items[1].first)
        assertArrayEquals(videoPayload, messages.items[1].third.second)
    }

    @Test
    fun `extended timestamps decode to their real value`() {
        val payload = ByteArray(300) // forces continuations, which repeat the ext field
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val out = ByteArrayOutputStream()
        RtmpChunkWriter(out).write(6, 0x1000000, 9, 1, payload)
        feedAll(reader, out.toByteArray())

        assertEquals(1, messages.items.size)
        assertEquals(0x1000000, messages.items.single().third.first)
        assertArrayEquals(payload, messages.items.single().third.second)
    }

    @Test
    fun `fmt3 continuations never double-advance extended timestamps`() {
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        writer.write(6, 0x1000000, 9, 1, ByteArray(300)) // fmt0 + ext (+ continuations)
        // fmt2 + ext (delta 0x1000000), payload spanning three chunks — each
        // continuation repeats the ext field with no timestamp effect.
        writer.write(6, 0x2000000, 9, 1, ByteArray(300))
        feedAll(reader, out.toByteArray())

        assertEquals(2, messages.items.size)
        assertEquals(0x1000000, messages.items[0].third.first)
        assertEquals(0x2000000, messages.items[1].third.first)
        assertEquals(300, messages.items[1].third.second.size)
    }

    @Test
    fun `an fmt3 new message advances by the implicit previous delta`() {
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        val out = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(out)
        val payload = byteArrayOf(1, 2, 3, 4)
        writer.write(3, 1000, 20, 0, payload) // fmt0
        writer.write(3, 2000, 20, 0, payload) // fmt2 (delta 1000)
        writer.write(3, 3000, 20, 0, payload) // bare fmt3: delta 1000 is implicit, not on the wire
        feedAll(reader, out.toByteArray())

        assertEquals(3, messages.items.size)
        assertEquals(3000, messages.items[2].third.first)
        assertArrayEquals(payload, messages.items[2].third.second)
    }

    @Test
    fun `a raised peer chunk size applies to later messages`() {
        val payload = ByteArray(300)
        val messages = Messages()
        val reader = RtmpChunkReader(messages::add)
        reader.setChunkSize(4096)
        val out = ByteArrayOutputStream()
        RtmpChunkWriter(out, chunkSize = 4096).write(6, 0, 9, 1, payload)
        feedAll(reader, out.toByteArray())

        assertEquals(1, messages.items.size)
        assertArrayEquals(payload, messages.items.single().third.second)
    }

    @Test
    fun `a chunk-size change is enforced`() {
        try {
            RtmpChunkReader { _, _, _, _ -> }.setChunkSize(-1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `two-byte csid headers decode`() {
        // fmt0 with the two-byte extended csid form: basic header 0x00, then
        // csid-64 in one byte (0x10 → csid 80), then the 11-byte header.
        val payload = byteArrayOf(1, 2, 3)
        val bytes = byteArrayOf(
            0x00, 0x10, // fmt0, csid form 0, csid = 64 + 16 = 80
            0, 0, 0, // timestamp
            0, 0, 0x03, // length
            0x14, // type
            1, 0, 0, 0, // streamId (LE)
        ) + payload
        val messages = Messages()
        RtmpChunkReader(messages::add).feed(bytes, 0, bytes.size)
        assertEquals(1, messages.items.size)
        assertEquals(20, messages.items[0].first)
        assertArrayEquals(payload, messages.items[0].third.second)
    }

    @Test
    fun `an fmt3 before any header for its csid cannot wedge the stream`() {
        // A lone fmt3 with no state: treated as an empty-length message so the
        // parser keeps its position instead of misreading payload as headers.
        // The upstream dispatch ignores the phantom empty message.
        val bytes = byteArrayOf(0xC3.toByte(), 0x00, 0x03, 0x54)
        val messages = Messages()
        RtmpChunkReader(messages::add).feed(bytes, 0, bytes.size)
        assertEquals(1, messages.items.size)
        assertEquals(0, messages.items.single().first) // type 0
        assertEquals(0, messages.items.single().third.second.size) // empty payload
    }
}
