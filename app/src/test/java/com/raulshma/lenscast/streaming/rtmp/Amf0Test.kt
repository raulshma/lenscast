package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class Amf0Test {

    // ── encode/decode round-trips ──

    @Test
    fun `numbers booleans and strings round-trip`() {
        val values = listOf(
            AmfValue.Number(0.0),
            AmfValue.Number(-1.5),
            AmfValue.Number(239.0),
            AmfValue.Number(0.1), // an exact-bit double, not a decimal literal
            AmfValue.Bool(true),
            AmfValue.Bool(false),
            AmfValue.Str(""),
            AmfValue.Str("live"),
            AmfValue.Str("UTF-8 ✓ é"),
        )
        assertEquals(values, Amf0.decode(Amf0.encode(values)))
    }

    @Test
    fun `objects round-trip with wire order preserved and duplicate keys kept`() {
        val obj = AmfValue.Obj(
            listOf(
                "app" to AmfValue.Str("live"),
                "fpad" to AmfValue.Bool(false),
                "flags" to AmfValue.Obj(listOf("a" to AmfValue.Number(1.0))),
                "dup" to AmfValue.Str("first"),
                "dup" to AmfValue.Str("second"),
            ),
        )
        val decoded = Amf0.decode(Amf0.encode(listOf(obj))).single() as AmfValue.Obj
        assertEquals(
            listOf("app", "fpad", "flags", "dup", "dup"),
            decoded.entries.map { it.first },
        )
        assertEquals("second", (decoded["dup"] as AmfValue.Str).value)
        assertEquals(1.0, ((decoded["flags"] as AmfValue.Obj)["a"] as AmfValue.Number).value, 0.0)
    }

    @Test
    fun `null and undefined round-trip`() {
        val values = listOf(AmfValue.Null, AmfValue.Undefined)
        assertEquals(values, Amf0.decode(Amf0.encode(values)))
    }

    // ── wire bytes are pinned ──

    @Test
    fun `the number marker is 0x00 followed by big-endian double bits`() {
        val bytes = Amf0.encode(listOf(AmfValue.Number(1.0)))
        // 1.0 → 0x3FF0000000000000
        assertArrayEquals(
            byteArrayOf(0x00, 0x3F.toByte(), 0xF0.toByte(), 0, 0, 0, 0, 0, 0),
            bytes,
        )
    }

    @Test
    fun `an object ends with the 000009 end marker`() {
        val bytes = Amf0.encode(listOf(AmfValue.Obj(emptyList())))
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x00, 0x09),
            bytes,
        )
    }

    // ── the decode-only shapes servers actually send back ──

    @Test
    fun `ecma arrays decode as objects`() {
        // Marker 0x08, u32 dense-count, object entries, end marker.
        val bytes = byteArrayOf(
            0x08,
            0, 0, 0, 2, // count (advisory)
            0x00, 0x03, 'k'.code.toByte(), 'e'.code.toByte(), 'y'.code.toByte(), 0x00, 0x40, 0x14, 0, 0, 0, 0, 0, 0, // key: 5.0
            0x00, 0x00, 0x09, // object end
        )
        val decoded = Amf0.decode(bytes).single()
        val obj = decoded as AmfValue.Obj
        assertEquals(5.0, (obj["key"] as AmfValue.Number).value, 0.0)
    }

    @Test
    fun `long strings decode as strings`() {
        val body = "long".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0x0C, 0, 0, 0, body.size.toByte()) + body
        assertEquals("long", (Amf0.decode(bytes).single() as AmfValue.Str).value)
    }

    @Test
    fun `an oversized u32 length is a decode error not an index bomb`() {
        val bytes = byteArrayOf(0x0C, -1, -1, -1, -1) // 0xFFFFFFFF length
        try {
            Amf0.decode(bytes)
            fail("expected AmfDecodeException")
        } catch (e: AmfDecodeException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `truncated payloads and unknown markers throw readable decode errors`() {
        for (truncated in listOf(
            byteArrayOf(0x00, 0x3F), // number missing its body
            byteArrayOf(0x02, 0x00, 0x05, 'a'.code.toByte()), // string claims 5, carries 1
            byteArrayOf(0x03, 0x00, 0x01, 'a'.code.toByte()), // object cut mid-entry
            byteArrayOf(0x03), // object with no end marker
        )) {
            try {
                Amf0.decode(truncated)
                fail("expected AmfDecodeException for ${truncated.toList()}")
            } catch (e: AmfDecodeException) {
                assertNotNull(e.message)
            }
        }
        try {
            Amf0.decode(byteArrayOf(0x07)) // reference marker: unsupported
            fail("expected AmfDecodeException")
        } catch (_: AmfDecodeException) {
        }
    }

    // ── RtmpCommand.parse ──

    @Test
    fun `parse splits name transaction and args`() {
        val payload = Amf0.encode(
            listOf(
                AmfValue.Str("_result"),
                AmfValue.Number(3.0),
                AmfValue.Null,
                AmfValue.Number(1.0),
            ),
        )
        val command = RtmpCommand.parse(payload)!!
        assertEquals("_result", command.name)
        assertEquals(3.0, command.transactionId, 0.0)
        assertEquals(listOf<AmfValue>(AmfValue.Null, AmfValue.Number(1.0)), command.args)
    }

    @Test
    fun `parse returns null for non-command payloads`() {
        assertNull(RtmpCommand.parse(Amf0.encode(listOf(AmfValue.Number(1.0))))) // no name
        assertNull(RtmpCommand.parse(Amf0.encode(listOf(AmfValue.Str("connect"))))) // no transaction
        assertNull(RtmpCommand.parse(byteArrayOf(0x07))) // undecodable
    }

    // ── RtmpCommands: the wire shapes the publisher sends, byte-pinned ──

    @Test
    fun `connect carries app tcUrl capabilities and userinfo credentials`() {
        val url = RtmpUrl.parse("rtmp://user:secret@example.com:1936/live/key")!!
        val decoded = Amf0.decode(RtmpCommands.connect(url))

        val name = decoded[0] as AmfValue.Str
        val txn = decoded[1] as AmfValue.Number
        val obj = decoded[2] as AmfValue.Obj
        assertEquals("connect", name.value)
        assertEquals(1.0, txn.value, 0.0)
        assertEquals("live", (obj["app"] as AmfValue.Str).value)
        // tcUrl is host[:port]/app — credentials ride the username/password
        // entries below, not the URL-shaped tcUrl (the RtmpUrl contract).
        assertEquals("rtmp://example.com:1936/live", (obj["tcUrl"] as AmfValue.Str).value)
        assertEquals("nonprivate", (obj["type"] as AmfValue.Str).value)
        assertEquals(239.0, (obj["capabilities"] as AmfValue.Number).value, 0.0)
        assertEquals("user", (obj["username"] as AmfValue.Str).value)
        assertEquals("secret", (obj["password"] as AmfValue.Str).value)
    }

    @Test
    fun `connect without userinfo carries no credential entries`() {
        val url = RtmpUrl.parse("rtmp://example.com/live/key")!!
        val obj = Amf0.decode(RtmpCommands.connect(url))[2] as AmfValue.Obj
        assertNull(obj["username"])
        assertNull(obj["password"])
    }

    @Test
    fun `releaseStream createStream and publish carry their transactions and the stream key`() {
        val release = Amf0.decode(RtmpCommands.releaseStream(2.0, "key"))
        assertEquals("releaseStream", (release[0] as AmfValue.Str).value)
        assertEquals(2.0, (release[1] as AmfValue.Number).value, 0.0)
        assertEquals(AmfValue.Null, release[2])
        assertEquals("key", (release[3] as AmfValue.Str).value)

        val create = Amf0.decode(RtmpCommands.createStream(3.0))
        assertEquals("createStream", (create[0] as AmfValue.Str).value)
        assertEquals(3.0, (create[1] as AmfValue.Number).value, 0.0)

        val publish = Amf0.decode(RtmpCommands.publish(4.0, "key"))
        assertEquals("publish", (publish[0] as AmfValue.Str).value)
        assertEquals(4.0, (publish[1] as AmfValue.Number).value, 0.0)
        assertEquals("key", (publish[3] as AmfValue.Str).value)
        assertEquals("live", (publish[4] as AmfValue.Str).value)
    }

    @Test
    fun `the clean-close pair is FCUnpublish then deleteStream with the stream id`() {
        val unpublish = Amf0.decode(RtmpCommands.fcUnpublish("key"))
        assertEquals("FCUnpublish", (unpublish[0] as AmfValue.Str).value)
        assertEquals("key", (unpublish[3] as AmfValue.Str).value)

        val delete = Amf0.decode(RtmpCommands.deleteStream(7.0))
        assertEquals("deleteStream", (delete[0] as AmfValue.Str).value)
        assertEquals(7.0, (delete[3] as AmfValue.Number).value, 0.0)
    }

    // ── errorMessage: the readable failure inside _error / onStatus ──

    @Test
    fun `errorMessage prefers code plus description from the info object`() {
        val args = listOf(
            AmfValue.Null,
            AmfValue.Obj(
                listOf(
                    "level" to AmfValue.Str("error"),
                    "code" to AmfValue.Str("NetStream.Publish.BadName"),
                    "description" to AmfValue.Str("already publishing"),
                ),
            ),
        )
        assertEquals("NetStream.Publish.BadName — already publishing", RtmpCommands.errorMessage(args))
    }

    @Test
    fun `errorMessage falls back to a bare string then to the generic line`() {
        assertEquals("plain reason", RtmpCommands.errorMessage(listOf(AmfValue.Str("plain reason"))))
        assertEquals(
            "server rejected the command",
            RtmpCommands.errorMessage(listOf(AmfValue.Null, AmfValue.Number(0.0))),
        )
    }
}
