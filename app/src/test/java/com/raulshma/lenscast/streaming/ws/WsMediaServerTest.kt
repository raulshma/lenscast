package com.raulshma.lenscast.streaming.ws

import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.streaming.AudioStreamingManager
import com.raulshma.lenscast.streaming.WebAuthGate
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/**
 * Socket-level handshake pass over [WsMediaServer] — the WS sidecar's own
 * surface (path gate + Web Auth Gate cookie verdict) exercised over a real
 * loopback socket against a real NanoWSD instance on an ephemeral port. The
 * upgrade accept-key computation itself lives in the NanoWSD library; what
 * this pins is that a valid handshake upgrades (with the RFC 6455 sample
 * key's well-known accept value, end to end) and that every rejected
 * handshake — unknown path, missing cookie, foreign cookie — never upgrades.
 *
 * **Not coverable here:** the OS-level enforcement semantics beyond the
 * handshake (frame fan-out timing under real encoders — device-only).
 */
class WsMediaServerTest {

    private val audioStreamingManager: AudioStreamingManager = mockk(relaxed = true)
    private val authGate = WebAuthGate()
    private lateinit var server: WsMediaServer
    private var port = 0

    @Before
    fun setUp() {
        server = WsMediaServer(0, audioStreamingManager, authGate)
        assertTrue(server.startServer())
        port = server.listeningPort
        assertTrue(port > 0)
    }

    @After
    fun tearDown() {
        server.stopServer()
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
}
