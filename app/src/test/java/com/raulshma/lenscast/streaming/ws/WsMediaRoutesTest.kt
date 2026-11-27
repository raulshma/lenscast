package com.raulshma.lenscast.streaming.ws

import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.streaming.AudioStreamingManager
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Socket-level handshake pass over [WsMediaRoutes] — the same-origin
 * /ws-surface's own contract (path gate + Web Auth Gate cookie verdict)
 * exercised over a real loopback socket against a real NanoWSD instance on
 * an ephemeral port. Production delegates [StreamingServer.openWebSocket]
 * to the routes; this test re-creates that one-line delegation with a bare
 * NanoWSD so the routes stay framework-free. The upgrade accept-key
 * computation itself lives in the NanoWSD library; what this pins is that a
 * valid handshake upgrades (with the RFC 6455 sample key's well-known accept
 * value, end to end) and that every rejected handshake — unknown path,
 * missing cookie, foreign cookie — never upgrades.
 *
 * **Not coverable here:** the OS-level enforcement semantics beyond the
 * handshake (frame fan-out timing under real encoders — device-only).
 */
class WsMediaRoutesTest {

    private val audioStreamingManager: AudioStreamingManager = mockk(relaxed = true)
    private val authGate = WebAuthGate()
    private val routes = WsMediaRoutes(audioStreamingManager, authGate)
    private val server = object : NanoWSD(0) {
        override fun openWebSocket(handshake: IHTTPSession): WebSocket =
            routes.openWebSocket(handshake)
    }
    private var port = 0

    @Before
    fun setUp() {
        server.start()
        port = server.listeningPort
        assertTrue(port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── helpers ──

    private class Handshake(val statusLine: String?, val headers: Map<String, String>) {
        val upgraded: Boolean get() = statusLine?.contains(" 101 ") == true
    }

    /** Sends one minimal RFC 6455 GET upgrade request with the RFC's sample key. */
    private fun handshake(path: String, extraHeaders: Map<String, String> = emptyMap()): Handshake {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
            return Handshake(statusLine, headers)
        }
    }

    private fun armAuth() {
        authGate.setCredentials("admin", StreamAuthCrypto.hashPassword("s3cret-pw"))
    }

    private fun sessionCookie(): String {
        val result = authGate.login(null, "admin", "s3cret-pw")
        assertTrue(result.success)
        return "${WebAuthGate.COOKIE_NAME}=${result.token}"
    }

    // ── the upgrade path ──

    @Test
    fun `video path handshake upgrades with the RFC 6455 sample accept key`() {
        val response = handshake("/ws/video")
        assertTrue("expected 101, got: ${response.statusLine}", response.upgraded)
        assertEquals(
            "the upgrade must carry the well-known accept value for the RFC's sample key",
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            response.headers["sec-websocket-accept"],
        )
    }

    @Test
    fun `talkback path handshake upgrades too`() {
        assertTrue(handshake("/ws/talkback").upgraded)
    }

    @Test
    fun `a valid session cookie upgrades while auth is armed — cookie-session-only auth`() {
        armAuth()
        val response = handshake("/ws/video", extraHeaders = mapOf("Cookie" to sessionCookie()))
        assertTrue("a cookie session must upgrade, got: ${response.statusLine}", response.upgraded)
    }

    // ── the rejection paths ──

    @Test
    fun `unknown path aborts the handshake`() {
        val response = handshake("/ws/unknown")
        assertFalse("an unknown WS path must never upgrade", response.upgraded)
    }

    @Test
    fun `armed auth rejects a cookieless handshake`() {
        armAuth()
        val response = handshake("/ws/video")
        assertFalse("no cookie must never upgrade: ${response.statusLine}", response.upgraded)
    }

    @Test
    fun `armed auth rejects a foreign session cookie`() {
        armAuth()
        val response = handshake(
            "/ws/video",
            extraHeaders = mapOf("Cookie" to "${WebAuthGate.COOKIE_NAME}=forged-token-value"),
        )
        assertFalse("a forged cookie must never upgrade: ${response.statusLine}", response.upgraded)
    }

    @Test
    fun `parameter set change pushes a fresh LCCF config before the new keyframe`() {
        val spsLandscape = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x01)
        val spsPortrait = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x02)
        val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xEC.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())

        // Seed initial landscape config before the client connects.
        routes.feedVideo(
            listOf(
                EncodedNalUnit(spsLandscape, false),
                EncodedNalUnit(pps, false),
                EncodedNalUnit(idr, true),
            ),
        )

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val req = buildString {
                append("GET /ws/video HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val input = BufferedInputStream(socket.getInputStream())
            val statusAndHeaders = readHttpHeaders(input)
            assertTrue(statusAndHeaders.contains(" 101 "))

            // Initial cached LCCF sent on open.
            val firstPayload = readNextBinaryWsPayload(input)
            assertEquals("LCCF", String(firstPayload, 0, 4, StandardCharsets.US_ASCII))

            // Orientation flip emits new SPS/PPS: connected client must receive
            // a fresh LCCF before the LCK1 keyframe.
            routes.feedVideo(
                listOf(
                    EncodedNalUnit(spsPortrait, false),
                    EncodedNalUnit(pps, false),
                    EncodedNalUnit(idr, true),
                ),
            )
            val updatedConfigPayload = readNextBinaryWsPayload(input)
            assertEquals("LCCF", String(updatedConfigPayload, 0, 4, StandardCharsets.US_ASCII))
            val keyframePayload = readNextBinaryWsPayload(input)
            assertEquals("LCK1", String(keyframePayload, 0, 4, StandardCharsets.US_ASCII))
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun readNextBinaryWsPayload(input: BufferedInputStream): ByteArray {
        val data = DataInputStream(input)
        while (true) {
            val b0 = data.readUnsignedByte()
            val b1 = data.readUnsignedByte()
            val opcode = b0 and 0x0F
            var len = b1 and 0x7F
            if (len == 126) {
                len = data.readUnsignedShort()
            } else if (len == 127) {
                len = data.readLong().toInt()
            }
            val payload = ByteArray(len)
            data.readFully(payload)
            if (opcode == 0x2) return payload
            if (opcode == 0x9) {
                // Ignore server keepalive pings if any arrive.
                continue
            }
        }
    }
}
