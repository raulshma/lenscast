package com.raulshma.lenscast.streaming.srt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Wire-parser fuzzing for the SRT surface ([RtmpUrlFuzzTest] /
 * [com.raulshma.lenscast.streaming.rtsp.RtspParserFuzzTest] pattern): hostile
 * URLs and hostile datagrams must never throw, hang, or allocate absurdly —
 * they parse to null, empty, or bounded results, always.
 */
class SrtFuzzTest {

    private fun randomSeeds(count: Int, size: Int, seed: Long): List<ByteArray> {
        val random = Random(seed)
        return List(count) { ByteArray(size) { random.nextInt(256).toByte() } }
    }

    @Test
    fun `random bytes never parse as a usable url`() {
        for (bytes in randomSeeds(count = 500, size = 40, seed = 0x5274)) {
            val url = SrtUrl.parse(String(bytes, Charsets.ISO_8859_1))
            // Fuzzed random bytes can be a valid URL shape by chance; the
            // only hard contract is "never throws".
            if (url != null) {
                assertTrue(url.port in 1..65535)
                assertTrue(url.host.isNotBlank())
            } else {
                assertNull(url)
            }
        }
    }

    @Test
    fun `hostile url shapes never throw`() {
        val hostile = listOf(
            "srt://",
            "srt://@",
            "srt://:@",
            "srt://a@",
            "srt://:::",
            "srt://[",
            "srt://h:?streamid=",
            "srt://h?streamid",
            "srt://h?&=&&",
            "srt://h:0",
            "srt://h:65536",
            "srt://h/-1?streamid=%",
            "SRT://UPPER:99999@h:1",
            "srt://h/segment/with/slashes?streamid=a=b&c",
        )
        for (candidate in hostile) {
            SrtUrl.parse(candidate) // the contract is: no throw, whatever the verdict
        }
    }

    @Test
    fun `random datagrams never break the incoming parse`() {
        for (bytes in randomSeeds(count = 1000, size = 1500, seed = 0xD47A)) {
            val incoming = SrtPacket.parseIncoming(bytes)
            if (incoming != null) {
                when (incoming) {
                    is SrtPacket.Incoming.Control -> {
                        assertTrue(incoming.type in 0..0x7FFF)
                        // A handshake-looking control parses only when it
                        // carries a plausible struct.
                        if (incoming.type == SrtPacket.TYPE_HANDSHAKE) {
                            SrtPacket.handshakeFrom(incoming.payload)
                            SrtPacket.hsRespFrom(incoming.payload)
                        }
                        if (incoming.type == SrtPacket.TYPE_ACK) SrtPacket.ackFrom(incoming.payload)
                        if (incoming.type == SrtPacket.TYPE_NAK) {
                            val lost = SrtPacket.nakFrom(incoming.payload).sumOf { it.last - it.first + 1 }
                            // The parse is bounded no matter the input.
                            assertTrue(
                                lost <= SrtPacket.MAX_NAK_RANGES * SrtPacket.MAX_NAK_RANGE_SPAN,
                            )
                        }
                    }
                    is SrtPacket.Incoming.Data -> Unit
                }
            }
        }
    }

    @Test
    fun `handshake build parse round trips stay total`() {
        val random = Random(0x5274)
        repeat(200) {
            val socketId = random.nextInt()
            val seq = random.nextInt()
            val ts = random.nextInt().toLong() and 0xFFFFFFFFL

            // The induction round-trips: control frame, handshake type, and
            // the struct fields we sent.
            val induction = SrtPacket.inductionRequest(ts, socketId, seq)
            val parsedInduction = SrtPacket.parseIncoming(induction) as SrtPacket.Incoming.Control
            assertEquals(SrtPacket.TYPE_HANDSHAKE, parsedInduction.type)
            val inductionStruct = SrtPacket.handshakeFrom(parsedInduction.payload)
            if (inductionStruct != null) {
                assertEquals(4, inductionStruct.version)
                assertEquals(socketId, inductionStruct.socketId)
            }

            // The conclusion carries the HSREQ block: header + struct + block.
            val conclusion = SrtPacket.conclusionRequest(ts, socketId, random.nextInt(), random.nextInt(), seq)
            val parsedConclusion = SrtPacket.parseIncoming(conclusion) as SrtPacket.Incoming.Control
            assertEquals(SrtPacket.TYPE_HANDSHAKE, parsedConclusion.type)
            assertEquals(52 + 20, parsedConclusion.payload.size)
            val conclusionStruct = SrtPacket.handshakeFrom(parsedConclusion.payload)
            if (conclusionStruct != null) {
                assertEquals(2, conclusionStruct.handshakeType)
                assertEquals(socketId, conclusionStruct.socketId)
            }
        }
    }
}
