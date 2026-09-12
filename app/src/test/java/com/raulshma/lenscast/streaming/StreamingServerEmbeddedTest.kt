package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.streaming.web.ApiMethod
import com.raulshma.lenscast.streaming.web.ApiRequest
import com.raulshma.lenscast.streaming.web.ApiResponse
import com.raulshma.lenscast.streaming.web.ApiRouter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Embedded-server smoke pass over the real [StreamingServer]: a NanoHTTPD
 * instance on an ephemeral loopback port with a mocked [WebApiStack] behind
 * the router seam, hit with real HTTP. Pins the four transport contracts the
 * pure tests can't see: the router dispatch translation (200 JSON + the
 * security headers), the auth gate's 401 in front of every /api route, the
 * login route's cookie contract end to end, the 64 KiB login-body 413, and
 * the SSE gzip exclusion that keeps the dashboard's EventSource alive.
 *
 * **Not coverable here:** the SSE client cap's 503 (four concurrently
 * half-open sockets + a fifth — the cap itself is pinned at the pump level in
 * [SseClientPumpTest]) and MJPEG/media egress (device-only frame sources).
 */
class StreamingServerEmbeddedTest {

    private val context: android.content.Context = mockk()
    private val router: ApiRouter = mockk()
    private val statusHandler: com.raulshma.lenscast.streaming.web.StatusWebHandler = mockk()
    private val auditLog: com.raulshma.lenscast.streaming.web.AuditLog = mockk(relaxed = true)
    private val authGate = WebAuthGate()

    private lateinit var server: StreamingServer
    private var port = 0

    @Before
    fun setUp() {
        val webApi: WebApiStack = mockk()
        every { webApi.router } returns router
        every { webApi.status } returns statusHandler
        every { webApi.gallery } returns mockk()
        every { webApi.capture } returns mockk()
        every { webApi.detectionEvents } returns mockk()
        every { webApi.auditLog } returns auditLog
        server = StreamingServer(
            port = 0,
            context = context,
            audioStreamingManager = mockk(relaxed = true),
            webApi = webApi,
            networkQualityMonitor = mockk(relaxed = true),
            webAuthGate = authGate,
            encodedStreamActive = { false },
            onvifServer = mockk(relaxed = true),
            whepServer = null,
        )
        server.start(5_000, true)
        port = server.listeningPort
        assertTrue(port > 0)
        coEvery { router.dispatch(any()) } returns ApiResponse.ok("""{"streaming":true}""")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── helpers ──

    private class HttpReply(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        val bodyText: String get() = String(body, StandardCharsets.UTF_8)
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun exchange(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpReply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
        }
        val reply = try {
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            HttpReply(conn.responseCode, conn.headerFields, out.toByteArray())
        } catch (e: java.io.IOException) {
            // Non-2xx answers surface as IOException on the input stream.
            val out = ByteArrayOutputStream()
            conn.errorStream?.use { it.copyTo(out) }
            HttpReply(conn.responseCode, conn.headerFields, out.toByteArray())
        }
        conn.disconnect()
        return reply
    }

    private fun armAuth() {
        authGate.setCredentials("admin", StreamAuthCrypto.hashPassword("s3cret-pw"))
    }

    /** One full login over HTTP; returns the session cookie header value. */
    private fun login(password: String): HttpReply =
        exchange(
            "POST",
            "/api/auth/login",
            body = """{"username":"admin","password":"$password"}""".toByteArray(),
        )

    // ── router dispatch translation ──

    @Test
    fun `api status dispatches through the router seam and answers 200 JSON with security headers`() {
        val reply = exchange("GET", "/api/status")
        assertEquals(200, reply.status)
        assertEquals("""{"streaming":true}""", reply.bodyText)
        assertEquals("application/json", reply.header("Content-Type")?.substringBefore(";"))
        assertEquals("nosniff", reply.header("X-Content-Type-Options"))
        assertEquals("DENY", reply.header("X-Frame-Options"))
        assertNotNull(reply.header("Content-Security-Policy"))
        // The dispatch the transport made: auth-off callers speak as ADMIN.
        coVerify(exactly = 1) {
            router.dispatch(ApiRequest(method = ApiMethod.GET, path = "/api/status"))
        }
    }

    // ── auth gate in front of /api ──

    @Test
    fun `armed auth answers 401 for a cookieless api request before the router is reached`() {
        armAuth()
        val reply = exchange("GET", "/api/status")
        assertEquals(401, reply.status)
        coVerify(exactly = 0) { router.dispatch(any()) }
    }

    @Test
    fun `login round trip wrong password 401 then right password 200 with a session cookie that passes the gate`() {
        armAuth()
        assertEquals(401, login("wrong-pw").status)

        val ok = login("s3cret-pw")
        assertEquals(200, ok.status)
        assertTrue(ok.bodyText.contains(""""success":true"""))
        val setCookie = ok.header("Set-Cookie")
        assertNotNull(setCookie)
        assertTrue(setCookie!!.startsWith("${WebAuthGate.COOKIE_NAME}="))
        assertTrue(setCookie.contains("HttpOnly"))

        val token = setCookie.substringBefore(";").substringAfter("=")
        val reply = exchange(
            "GET",
            "/api/status",
            headers = mapOf("Cookie" to "${WebAuthGate.COOKIE_NAME}=$token"),
        )
        assertEquals("the session cookie must clear the gate", 200, reply.status)
        assertEquals("""{"streaming":true}""", reply.bodyText)
    }

    // ── request-body cap ──

    @Test
    fun `login body beyond the 64 KiB cap answers 413 without reaching the credential check`() {
        armAuth()
        val oversized = """{"username":"admin","password":"""" +
            "x".repeat(64 * 1024 + 1) +
            """"}"""
        val reply = exchange("POST", "/api/auth/login", body = oversized.toByteArray())
        assertEquals(413, reply.status)
        assertTrue(reply.bodyText.contains("Request body too large"))
        coVerify(exactly = 0) { router.dispatch(any()) }
    }

    // ── SSE gzip exclusion ──

    @Test
    fun `the sse status channel answers uncompressed while finite json keeps the default gzip`() {
        coEvery { statusHandler.get() } returns """{"tick":1}"""

        val sse = rawHeadersFor("GET", "/api/events")
        assertTrue(sse.statusLine.contains(" 200 "))
        assertTrue(
            "the SSE channel must answer as text/event-stream",
            sse.headers.entries.any { it.key.equals("Content-Type", true) && it.value.first().startsWith("text/event-stream") },
        )
        assertNull(
            "NanoHTTPD's gzip deflater buffers SSE frames — the exclusion is the dashboard fix",
            sse.header("Content-Encoding"),
        )

        val json = rawHeadersFor("GET", "/api/status", acceptEncoding = "gzip")
        assertEquals("finite JSON keeps NanoHTTPD's default compression", "gzip", json.header("Content-Encoding"))
    }

    /** Raw-socket GET that reads only the response head — the SSE body never ends. */
    private fun rawHeadersFor(method: String, path: String, acceptEncoding: String? = null): RawHead {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                if (acceptEncoding != null) append("Accept-Encoding: $acceptEncoding\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val raw = ByteArray(4 * 1024)
            var count = 0
            val input = socket.getInputStream()
            val deadline = System.currentTimeMillis() + 5_000
            // Read until the header/body separator or the deadline — enough
            // bytes arrive for the head on the first TCP segment.
            while (count < raw.size && System.currentTimeMillis() < deadline) {
                val n = input.read(raw, count, raw.size - count)
                if (n < 0) break
                count += n
                if (String(raw, 0, count, StandardCharsets.US_ASCII).contains("\r\n\r\n")) break
            }
            val head = String(raw, 0, count, StandardCharsets.US_ASCII)
            val separator = head.indexOf("\r\n\r\n")
            val headerBlock = if (separator >= 0) head.substring(0, separator) else head
            val lines = headerBlock.split("\r\n")
            val headers = mutableMapOf<String, MutableList<String>>()
            lines.drop(1).forEach { line ->
                val split = line.indexOf(':')
                if (split > 0) {
                    headers.getOrPut(line.substring(0, split).trim()) { mutableListOf() }
                        .add(line.substring(split + 1).trim())
                }
            }
            return RawHead(statusLine = lines.first(), headers = headers)
        }
    }

    private class RawHead(val statusLine: String, val headers: Map<String, List<String>>) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }
}
