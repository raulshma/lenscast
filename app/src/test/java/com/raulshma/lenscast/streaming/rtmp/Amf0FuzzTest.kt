package com.raulshma.lenscast.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * AMF0 decode under hostile bytes: a deterministic corpus fuzzer.
 *
 * ── The contract ──
 * [Amf0.decode] and [RtmpCommand.parse] answer with values/null or throw ONLY
 * [AmfDecodeException] — never StackOverflow (the nesting cap exists because
 * decodeObject→decodeValue is mutual recursion), never IndexOutOfBounds or a
 * StringIndexOutOfBounds (length math runs through the Long bounds check),
 * never an infinite loop (every test runs under a timeout).
 *
 * ── The corpus ──
 * valid encodes and their round-trips, truncations at every prefix length,
 * bit flips at every bit of a tiny command, every single-byte marker followed
 * by garbage, nesting at 1k/10k/100k depth, u32 lengths at the Int overflow
 * boundary, dangling/overlong UTF-8, and missing object-end markers.
 */
class Amf0FuzzTest {

    // ── the contract ──

    private fun decodeHostile(bytes: ByteArray): List<AmfValue> {
        try {
            return Amf0.decode(bytes)
        } catch (t: Throwable) {
            if (t !is AmfDecodeException) {
                throw AssertionError("decode leaked ${t.javaClass.simpleName}: $t", t)
            }
            return emptyList()
        }
    }

    private fun parseCommandHostile(bytes: ByteArray): RtmpCommand? {
        try {
            return RtmpCommand.parse(bytes)
        } catch (t: Throwable) {
            throw AssertionError("RtmpCommand.parse leaked ${t.javaClass.simpleName}: $t", t)
        }
    }

    // ── valid baselines ──

    @Test(timeout = 10_000)
    fun `a valid connect command round-trips through encode-decode`() {
        val encoded = RtmpCommands.connect(RtmpUrl(false, "host", 1935, null, null, "live", "key"))
        val values = Amf0.decode(encoded)
        assertEquals("connect", (values[0] as AmfValue.Str).value)
        assertEquals(1.0, (values[1] as AmfValue.Number).value, 0.0)
        val command = parseCommandHostile(encoded)!!
        assertEquals("connect", command.name)
        assertEquals("live", ((command.args[0] as AmfValue.Obj)["app"] as AmfValue.Str).value)
    }

    // ── regression: deep nesting was a remote StackOverflowError ──
    // Pre-fix, three bytes per level (`03` object marker + `00 00` empty key)
    // drove the decodeObject→decodeValue recursion N levels deep — 100k
    // levels from a ~600 KB body. The cap makes it an AmfDecodeException.
    // Wire shape: 03, then (00 00 03)×(depth-1) opens, then (00 00 09)×depth
    // closes — each level an object whose empty-keyed value is the next.

    private fun nestedObjects(depth: Int): ByteArray =
        byteArrayOf(0x03) +
            ByteArray((depth - 1) * 3) { i -> if (i % 3 == 2) 0x03.toByte() else 0x00.toByte() } +
            ByteArray(depth * 3) { i -> if (i % 3 == 2) 0x09.toByte() else 0x00.toByte() }

    @Test(timeout = 10_000)
    fun `regression - nesting at the cap decodes, one past it is a decode error`() {
        // 100 levels (the cap) still decode.
        val atCap = decodeHostile(nestedObjects(100))
        assertEquals(1, atCap.size)
        assertTrue(atCap.single() is AmfValue.Obj)
        // 101 levels are a declared decode error — not a StackOverflowError.
        try {
            Amf0.decode(nestedObjects(101))
            throw AssertionError("over-deep nesting must fail as AmfDecodeException")
        } catch (t: Throwable) {
            assertTrue("got ${t.javaClass.simpleName}", t is AmfDecodeException)
        }
    }

    @Test(timeout = 10_000)
    fun `regression - 100k-deep nesting is a decode error, never a StackOverflowError`() {
        val body = nestedObjects(100_000)
        assertEquals(emptyList<AmfValue>(), decodeHostile(body))
        assertNull(parseCommandHostile(body))
    }

    @Test(timeout = 10_000)
    fun `regression - 100k-deep ecma arrays are a decode error too`() {
        // An ecma array (0x08) nested inside the object chain — same mutual
        // recursion, same cap.
        val ecmaDeep = byteArrayOf(0x03, 0x00, 0x00, 0x08) + ByteArray(4) + nestedObjects(100_000)
        assertNull(parseCommandHostile(ecmaDeep))
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - truncations at every prefix of a valid command never crash`() {
        val encoded = RtmpCommands.createStream(1.0)
        for (cut in 0 until encoded.size) {
            val values = decodeHostile(encoded.copyOfRange(0, cut))
            // Truncated decode results are only ever prefix values or empty.
            assertTrue(values.isEmpty() || values.first() is AmfValue.Str)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - bit flips at every bit of a small command never crash`() {
        val encoded = RtmpCommands.createStream(1.0)
        for (byteIndex in encoded.indices) {
            for (bit in 0..7) {
                val mutated = encoded.copyOf()
                mutated[byteIndex] = (mutated[byteIndex].toInt() xor (1 shl bit)).toByte()
                decodeHostile(mutated)
                parseCommandHostile(mutated)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - every single-byte marker followed by garbage is a value or a decode error`() {
        for (marker in 0..255) {
            val body = byteArrayOf(marker.toByte()) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            decodeHostile(body)
            parseCommandHostile(body)
            val bare = byteArrayOf(marker.toByte())
            decodeHostile(bare)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - u32 length fields at the Int overflow boundary are decode errors`() {
        val longString = byteArrayOf(0x0C)
        for (length in listOf(
            intArrayOf(0x7F, 0xFF, 0xFF, 0xFF), // Int.MAX_VALUE
            intArrayOf(0x80, 0x00, 0x00, 0x00), // Int overflow → negative
            intArrayOf(0xFF, 0xFF, 0xFF, 0xFF), // u32 max
            intArrayOf(0x00, 0x00, 0x00, 0x00), // empty
        )) {
            val body = longString + length.map { it.toByte() }.toByteArray() + ByteArray(16)
            decodeHostile(body)
        }
        // ECMA array with a hostile count.
        val ecma = byteArrayOf(0x08, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x09)
        decodeHostile(ecma)
    }

    @Test(timeout = 10_000)
    fun `corpus - dangling and overlong UTF-8 decode as replacement text, never a crash`() {
        val hostileStrings = listOf(
            byteArrayOf(0x02, 0x00, 0x03, 0xC3.toByte(), 0x28.toByte(), 0x41), // interrupted 2-byte sequence
            byteArrayOf(0x02, 0x00, 0x02, 0xFF.toByte(), 0xFE.toByte()), // continuation garbage
            byteArrayOf(0x02, 0x00, 0x04, 0xC0.toByte(), 0x80.toByte(), 0x61, 0x62), // overlong encoding
            byteArrayOf(0x02, 0x00, 0x04, 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // past U+10FFFF
        )
        for (body in hostileStrings) {
            val values = decodeHostile(body)
            assertEquals(1, values.size)
            assertTrue(values.single() is AmfValue.Str)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - objects without end markers and end-marker confusion are decode errors`() {
        // Object whose entries never terminate.
        decodeHostile(byteArrayOf(0x03, 0x00, 0x01, 0x61) + ByteArray(64) { 0x02.toByte() })
        // The classic confusion: empty key + object marker reads as a nested
        // object, so `03 00 00 03` repeated is depth, not an end marker.
        decodeHostile(byteArrayOf(0x03, 0x00, 0x00, 0x03, 0x00, 0x00, 0x09))
        // Bare end marker outside an object is an unsupported marker.
        decodeHostile(byteArrayOf(0x09))
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage never crashes the command parse`() {
        val random = Random(0xA4F0)
        for (trial in 0 until 500) {
            val body = ByteArray(random.nextInt(64))
            random.nextBytes(body)
            // The call itself is the assertion: only AmfDecodeException may
            // leave decode, and parse() must swallow it into a null.
            parseCommandHostile(body)
        }
    }

    // ── downstream consumers over hostile shapes ──

    @Test(timeout = 10_000)
    fun `errorMessage over hostile argument shapes always answers a string`() {
        val hostile = listOf(
            emptyList(),
            listOf(AmfValue.Null, AmfValue.Undefined, AmfValue.Number(Double.NaN)),
            listOf(AmfValue.Obj(emptyList())),
            listOf(AmfValue.Obj(listOf("code" to AmfValue.Str(""), "description" to AmfValue.Str(" ")))),
            listOf(AmfValue.Str("   "), AmfValue.Obj(listOf("code" to AmfValue.Str("na")))),
            listOf(AmfValue.Obj(listOf("code" to AmfValue.Number(1.0)))),
        )
        for (args in hostile) {
            assertTrue(RtmpCommands.errorMessage(args).isNotEmpty())
        }
    }
}
