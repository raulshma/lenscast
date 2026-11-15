package com.raulshma.lenscast.companion.playback

import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

/**
 * The WHEP exchange's wire contract, one MockWebServer round trip per rule —
 * the Kotlin twin of web/src/lib/whepClient.test.ts: the pure verdicts, the
 * 201+Location+SDP handshake, the tolerated 200, the non-SDP refusal, the
 * auth/CSRF headers on the offer POST, and the teardown DELETE carrying the
 * captured session id. The PeerConnection half is the [FakePeer] seam (the
 * ts suite's fake RTCPeerConnection), so no native code runs here.
 *
 * Driven with plain runBlocking and a gated [WhepViewer]'s pauseMs — the
 * engine's every beat is released by an explicit tick, so no real sleeps and
 * no kotlinx-coroutines-test virtual time (which false-passes beside real
 * threads; see MjpegFrameSourceTest's header note).
 */
class WhepSignalingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LenscastApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LenscastApiClient(device()) {
            StoredCredentials(CompanionAuthKind.TOKEN, token = TOKEN)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun device() = CompanionDevice(
        id = "whep-device",
        name = "Whep Device",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        role = "admin",
        addedAtMs = 0L,
    )

    // ── fixture constants (minimal SDP shapes; the PC seam never parses them) ──

    private companion object {
        const val TOKEN = "tok-whep"
        const val ANSWER_ID = "abc123"
        val OFFER_SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"
        val ANSWER_SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=sendonly\r\n"
    }

    private fun answer(status: Int, body: String = ANSWER_SDP, location: String? = "/whep/$ANSWER_ID") =
        MockResponse().setResponseCode(status).setBody(body).apply {
            location?.let { setHeader("Location", it) }
        }

    /** The engine harness: fake peer, fake clock advanced by the engine's own pause calls. */
    private inner class Harness {
        var clock = 0L
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val fake = FakePeer()
        val viewer = WhepViewer(
            api = client,
            peerFactory = { fake },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clockMs = { clock },
            // The engine's sleep, faked: the delay itself advances the fake
            // clock (the engine hands the value in), then parks on a tick the
            // test releases — cancellation still lands here, like a real delay.
            pauseMs = { ms ->
                clock += ms
                ticks.receive()
            },
        )

        /** Releases [n] engine beats; each one resumes one parked pauseMs. */
        fun tick(n: Int = 1) {
            repeat(n) { check(ticks.trySend(Unit).isSuccess) }
        }

        /** Waits until the viewer reaches a terminal-or-live state. */
        suspend fun awaitSettled(): WhepState =
            withTimeout(5_000) {
                viewer.state.first { it is WhepState.Failed || it == WhepState.Live || it == WhepState.Idle }
            }

        /**
         * Parks until the viewer reports [target]. The engine's beats run on
         * their own coroutine — a released tick and the state write it
         * triggers are not atomic with this thread, so a bare `state.value`
         * read after [tick] is a coin flip; every state assert goes through
         * here instead.
         */
        suspend fun awaitState(target: WhepState): WhepState =
            withTimeout(5_000) { viewer.state.first { it == target } }

        /** Parks until the fake peer's close lands (a teardown finally races a Failed state read). */
        suspend fun awaitPeerClosed() {
            withTimeout(5_000) {
                while (!fake.closed) delay(10)
            }
        }
    }

    private class FakePeer : WhepPeer {
        /** Signalled when the answer reached the peer (the test's sync beat). */
        val answered = CompletableDeferred<Unit>()
        var lastAnswer: String? = null
        var firstFrame = false
        var link = WhepPeerLinkState.NEW
        var closed = false
        var attachCalls = 0

        override fun attachRenderer(renderer: SurfaceViewRenderer?) {
            attachCalls += 1
        }

        override suspend fun open(): String = OFFER_SDP

        override fun acceptAnswer(answerSdp: String) {
            lastAnswer = answerSdp
            answered.complete(Unit)
        }

        override fun hasFirstFrameSignal(): Boolean = firstFrame

        override fun linkState(): WhepPeerLinkState = link

        override fun close() {
            closed = true
        }
    }

    // ── pure verdicts (whepClient.test.ts parity) ──

    @Test
    fun `isWhepAnswer accepts 201 and 200 with an sdp body only`() {
        assertTrue(WhepSignaling.isWhepAnswer(201, "v=0\r\nm=video 9\r\n"))
        assertTrue("leading whitespace is trimmed like the ts trimStart", WhepSignaling.isWhepAnswer(200, "\r\n v=0"))
        assertTrue("tolerated 200 with SDP", WhepSignaling.isWhepAnswer(200, "v=0"))
        assertFalse("a JSON error envelope is a refusal", WhepSignaling.isWhepAnswer(201, "{\"error\":\"cap\"}"))
        assertFalse("an SDP body on a wrong status is a refusal", WhepSignaling.isWhepAnswer(503, "v=0"))
        assertFalse(WhepSignaling.isWhepAnswer(204, ""))
    }

    @Test
    fun `sessionIdFromLocation parses absolute relative and missing locations`() {
        assertNull(WhepSignaling.sessionIdFromLocation(null))
        assertNull("no marker, no id", WhepSignaling.sessionIdFromLocation("/sessions/abc123"))
        assertNull("marker without an id", WhepSignaling.sessionIdFromLocation("/whep/"))
        assertEquals("abc123", WhepSignaling.sessionIdFromLocation("/whep/abc123"))
        assertEquals("absolute Location keeps the id", "abc123", WhepSignaling.sessionIdFromLocation("https://cam:8443/whep/abc123"))
    }

    @Test
    fun `deleteSessionUrl resolves against the dialed whep url`() {
        assertEquals(
            "relative Location joins the dialed base",
            "https://cam:8443/whep/$ANSWER_ID",
            WhepSignaling.deleteSessionUrl("https://cam:8443/whep", "/whep/$ANSWER_ID"),
        )
        assertEquals(
            "absolute Location still deletes by id on the dialed base",
            "https://cam:8443/whep/$ANSWER_ID",
            WhepSignaling.deleteSessionUrl("https://cam:8443/whep", "https://elsewhere:1/whep/$ANSWER_ID"),
        )
        assertNull("no id → nothing to DELETE (the device reaps)", WhepSignaling.deleteSessionUrl("https://cam:8443/whep", null))
        assertNull(WhepSignaling.deleteSessionUrl("https://cam:8443/whep", "/whep/"))
    }

    // ── the HTTP exchange ──

    @Test
    fun `happy 201 offer reaches live after the first frame`() = runBlocking {
        server.enqueue(answer(201))
        val h = Harness()
        h.viewer.start()
        h.fake.answered.await()

        assertEquals(WhepState.Connecting, h.viewer.state.value)
        assertEquals("the answer SDP reached the peer", ANSWER_SDP, h.fake.lastAnswer)

        h.fake.firstFrame = true
        h.tick(1)
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))
        assertTrue("the session keeps its peer while live", !h.fake.closed)
        h.viewer.stop()
    }

    @Test
    fun `a 200 answer is tolerated like the web client`() = runBlocking {
        server.enqueue(answer(200))
        val h = Harness()
        h.viewer.start()
        h.fake.answered.await()
        h.fake.firstFrame = true
        h.tick(1)
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))
        h.viewer.stop()
    }

    @Test
    fun `a non-sdp body refuses with the status in the reason and closes the peer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"viewer cap\"}"))
        val h = Harness()
        h.viewer.start()
        val settled = h.awaitSettled()
        assertTrue("expected Failed, was $settled", settled is WhepState.Failed)
        assertTrue(
            "the refusal names the status",
            (settled as WhepState.Failed).reason.contains("503"),
        )
        h.awaitPeerClosed() // the close rides the engine's finally, a beat behind the Failed write
        assertTrue("the abandoned peer closes", h.fake.closed)
        assertEquals("no answer ever reached the peer", null, h.fake.lastAnswer)
        assertEquals("no session was captured, so no DELETE", 1, server.requestCount)
    }

    @Test
    fun `a transport failure fails the rung and closes the peer`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val h = Harness()
        h.viewer.start()
        val settled = h.awaitSettled()
        assertTrue("expected Failed, was $settled", settled is WhepState.Failed)
        h.awaitPeerClosed()
        assertTrue(h.fake.closed)
    }

    @Test
    fun `auth csrf and content-type headers ride the offer post`() = runBlocking {
        server.enqueue(answer(201))
        val h = Harness()
        h.viewer.start()
        h.fake.answered.await()

        val post = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the offer POST arrived", post)
        assertEquals("POST", post!!.method)
        assertEquals("/whep", post.path)
        assertEquals("application/sdp", post.getHeader("Content-Type"))
        assertEquals("the shared auth ladder rides the POST", TOKEN, post.getHeader("X-Api-Token"))
        assertEquals("cookie-authed writes need the CSRF cover", "XMLHttpRequest", post.getHeader("X-Requested-With"))
        assertEquals("the body is the local offer SDP", OFFER_SDP, post.body.readUtf8())
        h.viewer.stop()
    }

    @Test
    fun `stop deletes the session with the captured id and closes the peer`() = runBlocking {
        server.enqueue(answer(201))
        val h = Harness()
        h.viewer.start()
        h.fake.answered.await()
        h.fake.firstFrame = true
        h.tick(1)
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))

        h.viewer.stop()
        assertEquals(WhepState.Idle, h.viewer.state.value)
        assertTrue("the peer closed", h.fake.closed)
        // takeRequest drains in wire order: the offer POST is consumed first,
        // the teardown DELETE is the second request on the queue.
        assertNotNull("the offer POST fired", server.takeRequest(5, TimeUnit.SECONDS))
        val delete = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the teardown DELETE fired", delete)
        assertEquals("DELETE", delete!!.method)
        assertEquals("/whep/$ANSWER_ID", delete.path)
    }

    @Test
    fun `a missing location header plays without a teardown delete`() = runBlocking {
        server.enqueue(answer(201, location = null))
        val h = Harness()
        h.viewer.start()
        h.fake.answered.await()
        h.fake.firstFrame = true
        h.tick(1)
        assertEquals("no Location is not a refusal — the device reaps", WhepState.Live, h.awaitState(WhepState.Live))
        h.viewer.stop()
        assertEquals("nothing to DELETE", 1, server.requestCount)
    }
}
