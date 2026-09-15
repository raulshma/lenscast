package com.raulshma.lenscast.streaming.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The RFC 9725 wire mechanics, JVM-pure: the offer POST's shape, the
 * Authorization ladder (bearer token over URL Basic), the 201 + Location +
 * answer parse with absolute/relative resources, the DELETE settle rule, and
 * the one-shot candidate injection — no sockets, canned responses only.
 */
class WhipSignalingTest {

    private val endpoint = WhipUrl.parse("http://example.com:8000/whip")!!

    // ── the Authorization ladder ──

    @Test
    fun `a bearer token wins over the URL's basic credentials`() {
        assertEquals(
            "Bearer secret-token",
            WhipSignaling.authorizationHeader("  secret-token  ", "user", "pass"),
        )
        // A blank token never silently falls through to a stale Basic header.
        assertNull(WhipSignaling.authorizationHeader("   ", null, null))
    }

    @Test
    fun `URL userinfo becomes an HTTP Basic header when no token is set`() {
        assertEquals(
            "Basic dXNlcjpwYXNz", // base64("user:pass")
            WhipSignaling.authorizationHeader(null, "user", "pass"),
        )
        // A missing password degrades to an empty one, not a crash.
        assertEquals(
            "Basic dXNlcjo=", // base64("user:")
            WhipSignaling.authorizationHeader("", "user", null),
        )
    }

    @Test
    fun `an open endpoint carries no Authorization header`() {
        assertNull(WhipSignaling.authorizationHeader(null, null, null))
        assertNull(WhipSignaling.authorizationHeader("", "", ""))
    }

    // ── the offer POST ──

    @Test
    fun `the offer request posts SDP to the resource with the bearer token`() {
        val request = WhipSignaling.offerRequest(endpoint, "tok", "v=0\r\no=- 1 1 IN IP4 0.0.0.0")
        assertEquals("POST", request.method)
        assertEquals("http://example.com:8000/whip", request.url)
        assertEquals(WhipSignaling.CONTENT_TYPE_SDP, request.headers["Content-Type"])
        assertEquals(WhipSignaling.CONTENT_TYPE_SDP, request.headers["Accept"])
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertTrue(request.body!!.contentEquals("v=0\r\no=- 1 1 IN IP4 0.0.0.0".toByteArray()))
    }

    @Test
    fun `the offer request carries no Authorization for an open endpoint`() {
        val request = WhipSignaling.offerRequest(WhipUrl.parse("http://example.com/whip")!!, null, "v=0")
        assertNull(request.headers["Authorization"])
    }

    // ── the 201 + answer + Location parse ──

    private fun response(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        WhipHttpResponse(code, headers, body.toByteArray())

    @Test
    fun `a 201 with an absolute Location parses into resource and answer`() {
        val answer = WhipSignaling.parseOfferResponse(
            response(201, "v=0\r\nanswer", mapOf("location" to "http://example.com:8000/session/abc")),
            endpoint.resourceUrl,
        )
        assertEquals("http://example.com:8000/session/abc", answer.resourceUrl)
        assertEquals("v=0\r\nanswer", answer.answerSdp)
    }

    @Test
    fun `a root-relative Location resolves against the endpoint origin`() {
        val answer = WhipSignaling.parseOfferResponse(
            response(201, "v=0", mapOf("location" to "/session/xyz")),
            endpoint.resourceUrl,
        )
        assertEquals("http://example.com:8000/session/xyz", answer.resourceUrl)
    }

    @Test
    fun `a bare relative Location joins the endpoint origin with a slash`() {
        val answer = WhipSignaling.parseOfferResponse(
            response(201, "v=0", mapOf("location" to "session/xyz")),
            endpoint.resourceUrl,
        )
        assertEquals("http://example.com:8000/session/xyz", answer.resourceUrl)
    }

    @Test
    fun `a non-201 refuses with the status and the body detail`() {
        try {
            WhipSignaling.parseOfferResponse(
                response(401, "unauthorized", mapOf("Content-Type" to "text/plain")),
                endpoint.resourceUrl,
            )
            fail("expected WhipSignalingException")
        } catch (e: WhipSignalingException) {
            assertTrue(e.message!!.contains("401"))
            assertTrue(e.message!!.contains("unauthorized"))
        }
    }

    @Test
    fun `a 201 with an empty answer SDP refuses`() {
        try {
            WhipSignaling.parseOfferResponse(
                response(201, "  ", mapOf("location" to "/session/abc")),
                endpoint.resourceUrl,
            )
            fail("expected WhipSignalingException")
        } catch (e: WhipSignalingException) {
            assertTrue(e.message!!.contains("empty answer SDP"))
        }
    }

    @Test
    fun `a 201 without a Location refuses`() {
        try {
            WhipSignaling.parseOfferResponse(response(201, "v=0"), endpoint.resourceUrl)
            fail("expected WhipSignalingException")
        } catch (e: WhipSignalingException) {
            assertTrue(e.message!!.contains("Location"))
        }
    }

    // ── the resource DELETE ──

    @Test
    fun `the delete request targets the resource with the same credential rule`() {
        val request = WhipSignaling.deleteRequest("http://example.com:8000/session/abc", "tok", endpoint.username, endpoint.password)
        assertEquals("DELETE", request.method)
        assertEquals("http://example.com:8000/session/abc", request.url)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertNull(request.body)
    }

    @Test
    fun `any 2xx or an already-gone 404-410 settles the delete`() {
        assertTrue(WhipSignaling.isDeleteSettled(200))
        assertTrue(WhipSignaling.isDeleteSettled(204))
        assertTrue(WhipSignaling.isDeleteSettled(404))
        assertTrue(WhipSignaling.isDeleteSettled(410))
        assertFalse(WhipSignaling.isDeleteSettled(401))
        assertFalse(WhipSignaling.isDeleteSettled(500))
        assertFalse(WhipSignaling.isDeleteSettled(301))
    }

    // ── the scripted fake transport (the publisher's [WhipHttpClient] seam) ──

    @Test
    fun `a full offer-delete exchange rides a scripted fake transport`() {
        val requestLog = mutableListOf<WhipHttpRequest>()
        val responses = ArrayDeque(
            listOf(
                WhipHttpResponse(201, mapOf("location" to "/session/abc"), "v=0\r\nanswer".toByteArray()),
                WhipHttpResponse(204, emptyMap(), ByteArray(0)),
            ),
        )
        val http = object : WhipHttpClient {
            override fun execute(request: WhipHttpRequest): WhipHttpResponse {
                requestLog += request
                return responses.removeFirst()
            }
        }

        val url = WhipUrl.parse("https://ingest.example.com/whip")!!
        val answer = WhipSignaling.parseOfferResponse(
            http.execute(WhipSignaling.offerRequest(url, "tok", "v=0\r\noffer")),
            url.resourceUrl,
        )
        assertEquals("https://ingest.example.com/session/abc", answer.resourceUrl)
        assertEquals("v=0\r\nanswer", answer.answerSdp)

        val settled = WhipSignaling.isDeleteSettled(
            http.execute(
                WhipSignaling.deleteRequest(answer.resourceUrl, "tok", url.username, url.password),
            ).statusCode,
        )
        assertTrue(settled)

        assertEquals(2, requestLog.size)
        assertEquals("POST", requestLog[0].method)
        assertEquals("https://ingest.example.com/whip", requestLog[0].url)
        assertEquals("DELETE", requestLog[1].method)
        assertEquals("https://ingest.example.com/session/abc", requestLog[1].url)
        assertEquals("Bearer tok", requestLog[1].headers["Authorization"])
    }

    // ── the one-shot candidate injection ──

    private val offer = listOf(
        "v=0",
        "o=- 1 1 IN IP4 127.0.0.1",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "c=IN IP4 0.0.0.0",
        "a=sendonly",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "c=IN IP4 0.0.0.0",
        "a=sendonly",
    ).joinToString("\r\n")

    @Test
    fun `candidates are appended into every media section`() {
        val lines = listOf("a=candidate:1 1 udp 1 10.0.0.1 5000 typ host")
        val out = WhipOfferBuilder.injectCandidates(offer, lines)
        val sections = out.split("\r\nm=").drop(1)
        assertEquals(2, sections.size)
        // Both sections close with the injected candidate line.
        sections.forEach { section ->
            assertTrue(section.trimEnd().endsWith("a=candidate:1 1 udp 1 10.0.0.1 5000 typ host"))
        }
    }

    @Test
    fun `injection is idempotent - present lines are not duplicated`() {
        val line = "a=candidate:1 1 udp 1 10.0.0.1 5000 typ host"
        val once = WhipOfferBuilder.injectCandidates(offer, listOf(line))
        // The first pass already put one copy in each of the two sections.
        assertEquals(2, once.lineSequence().count { it.trim() == line })
        val twice = WhipOfferBuilder.injectCandidates(once, listOf("  $line  ", "a=candidate:2 1 udp 2 10.0.0.2 5001 typ host"))
        // The re-injected copy is recognized as present and NOT duplicated,
        // while the new candidate lands in both sections.
        assertEquals(2, twice.lineSequence().count { it.trim() == line })
        assertEquals(2, twice.lineSequence().count { it.contains("a=candidate:2") })
    }

    @Test
    fun `an empty candidate list returns the SDP untouched`() {
        assertEquals(offer, WhipOfferBuilder.injectCandidates(offer, emptyList()))
    }

    @Test
    fun `blank candidate lines are dropped, not injected`() {
        assertEquals(offer, WhipOfferBuilder.injectCandidates(offer, listOf("", "   ")))
    }

    @Test
    fun `lf-only SDP is normalized to crlf on injection`() {
        val lfOnly = offer.replace("\r\n", "\n")
        val out = WhipOfferBuilder.injectCandidates(lfOnly, listOf("a=candidate:1 1 udp 1 10.0.0.1 5000 typ host"))
        assertFalse(out.contains("a=candidate\n"))
        assertTrue(out.contains("\r\n"))
    }

    @Test
    fun `trailing crlf sdp never grows a blank line before the candidates`() {
        // libwebrtc's local description ends with a CRLF; the injected
        // candidates must replace that position, not trail a blank line —
        // browsers reject an SDP containing one empty line outright.
        val candidate = "a=candidate:1 1 udp 1 10.0.0.1 5000 typ host"
        val withTrailing = offer + "\r\n"
        val out = WhipOfferBuilder.injectCandidates(withTrailing, listOf(candidate))
        // A blank line between content lines is what browsers reject; the one
        // empty element split() yields from the single trailing CRLF is fine.
        assertFalse(out.split("\r\n").dropLast(1).any { it.isBlank() })
        assertTrue(out.endsWith(candidate + "\r\n"))
        assertFalse(out.endsWith("\r\n\r\n"))
    }
}
