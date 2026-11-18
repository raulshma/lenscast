package com.raulshma.lenscast.companion.playback

import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

/**
 * The WHEP "answer ≠ media" watchdog, pinned against a fake clock: no first
 * frame inside [WhepSignaling.FIRST_FRAME_TIMEOUT_MS] is a Failed verdict (the
 * caller demotes), a fatal link state before or during playback is one too,
 * and a live session whose link dies mid-hold does the same. The PeerConnection
 * half is the fake seam; the ticks drive the engine's pauseMs, so the 6-second
 * window is traversed without real time and without virtual-time machinery.
 */
class WhepWatchdogTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LenscastApiClient

    // Every Harness registers itself so tearDown can cancel its viewer
    // scope: a test that reaches Live parks the hold loop forever by
    // design, and an uncancelled scope leaks that loop — plus anything the
    // engine still retries — into the rest of the suite's JVM.
    private val harnesses = mutableListOf<Harness>()

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
        harnesses.forEach { it.scope.cancel() }
        harnesses.clear()
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

    private companion object {
        const val TOKEN = "tok-whep"
        const val ANSWER_ID = "abc123"
        val OFFER_SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val ANSWER_SDP = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
    }

    private fun answer(status: Int = 201) =
        MockResponse().setResponseCode(status).setBody(ANSWER_SDP).setHeader("Location", "/whep/$ANSWER_ID")

    private inner class Harness {
        var clock = 0L
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val fake = FakePeer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewer = WhepViewer(
            api = client,
            peerFactory = { fake },
            scope = scope,
            clockMs = { clock },
            pauseMs = { ms ->
                clock += ms
                ticks.receive()
            },
        )

        init {
            harnesses += this
        }

        fun tick(n: Int = 1) {
            repeat(n) { check(ticks.trySend(Unit).isSuccess) }
        }

        suspend fun awaitSettled(): WhepState =
            withTimeout(5_000) {
                viewer.state.first { it is WhepState.Failed || it == WhepState.Live || it == WhepState.Idle }
            }

        /**
         * Parks until the viewer reports [target]. A released tick and the
         * state write it triggers are not atomic with this thread (the
         * engine's beats run on their own coroutine) — every state assert
         * goes through here instead of a bare `state.value` read.
         */
        suspend fun awaitState(target: WhepState): WhepState =
            withTimeout(5_000) { viewer.state.first { it == target } }

        /**
         * Parks until the viewer settles on a state OTHER than [from]. The
         * settled predicate also matches the state a live-hold test is
         * leaving (Live), so a bare [awaitSettled] returns that stale value
         * before the engine has processed the tick instead of waiting for
         * the write — and still returns instantly when the tick DID process
         * inline (Failed already current), which a "must differ from the
         * sampled value" rule without the argument would get wrong.
         */
        suspend fun awaitSettledAfter(from: WhepState): WhepState =
            withTimeout(5_000) {
                viewer.state.first {
                    it != from && (it is WhepState.Failed || it == WhepState.Live || it == WhepState.Idle)
                }
            }

        /**
         * The Failed write precedes the peer close (runSession's finally):
         * waiting on the state alone can observe Failed before the effect
         * lands under full-suite load. Every closed-peer assert parks here.
         */
        suspend fun awaitPeerClosed() {
            withTimeout(5_000) { while (!fake.closed) delay(10) }
        }

        /** The handshake, settled up to (but not into) the watchdog loop. */
        suspend fun negotiate() {
            server.enqueue(answer())
            viewer.start()
            fake.answered.await()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // the POST landed
        }
    }

    private class FakePeer : WhepPeer {
        val answered = CompletableDeferred<Unit>()
        @Volatile var firstFrame = false
        @Volatile var link = WhepPeerLinkState.NEW
        @Volatile var closed = false
        private var attachCalls = 0

        override fun attachRenderer(renderer: SurfaceViewRenderer?) {
            attachCalls += 1
        }

        override suspend fun open(): String = OFFER_SDP

        override fun acceptAnswer(answerSdp: String) {
            answered.complete(Unit)
        }

        override fun hasFirstFrameSignal(): Boolean = firstFrame

        override fun linkState(): WhepPeerLinkState = link

        override fun close() {
            closed = true
        }
    }

    // ── the first-frame window ──

    @Test
    fun `six seconds without a first frame fails the rung and frees the slot`() = runBlocking {
        val h = Harness()
        h.negotiate()
        assertEquals(WhepState.Connecting, h.viewer.state.value)

        // The window is 6000ms at a 100ms tick: 59 ticks keep it inside…
        h.tick((WhepSignaling.FIRST_FRAME_TIMEOUT_MS / WhepViewer.WATCHDOG_TICK_MS - 1).toInt())
        assertEquals(WhepState.Connecting, h.awaitState(WhepState.Connecting))
        assertTrue(!h.fake.closed)

        // …the 60th lands on the deadline.
        h.tick(1)
        val settled = h.awaitSettled()
        assertTrue("expected Failed, was $settled", settled is WhepState.Failed)
        assertTrue(
            "the reason names the media window",
            (settled as WhepState.Failed).reason.contains("no video"),
        )
        h.awaitPeerClosed()
        // The handshake DID mint a session — the slot must be released.
        val delete = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("DELETE", delete?.method)
        assertEquals("/whep/$ANSWER_ID", delete?.path)
    }

    @Test
    fun `a fatal link state before the first frame fails immediately`() = runBlocking {
        val h = Harness()
        h.negotiate()
        h.fake.link = WhepPeerLinkState.FAILED
        h.tick(1)
        val settled = h.awaitSettled()
        assertTrue(settled is WhepState.Failed)
        assertTrue((settled as WhepState.Failed).reason.contains("failed"))
        h.awaitPeerClosed()
    }

    @Test
    fun `a closed link state is fatal like failed`() = runBlocking {
        val h = Harness()
        h.negotiate()
        h.fake.link = WhepPeerLinkState.CLOSED
        h.tick(1)
        val settled = h.awaitSettled()
        assertTrue(settled is WhepState.Failed)
        assertTrue((settled as WhepState.Failed).reason.contains("closed"))
    }

    // ── the live hold ──

    @Test
    fun `a link that dies during playback fails the live rung`() = runBlocking {
        val h = Harness()
        h.negotiate()
        h.fake.firstFrame = true
        h.tick(1)
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))

        h.fake.link = WhepPeerLinkState.FAILED
        h.tick(1)
        val settled = h.awaitSettledAfter(WhepState.Live)
        assertTrue(settled is WhepState.Failed)
        assertTrue(
            "the hold failure names the playback phase",
            (settled as WhepState.Failed).reason.contains("playback"),
        )
        h.awaitPeerClosed()
        val delete = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("the dead session's slot is freed", "DELETE", delete?.method)
    }

    @Test
    fun `a disconnected link is transient and does not end the hold`() = runBlocking {
        val h = Harness()
        h.negotiate()
        h.fake.firstFrame = true
        h.tick(1)
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))
        h.fake.link = WhepPeerLinkState.DISCONNECTED
        h.tick(3) // several hold beats pass
        assertEquals(
            "a Wi-Fi blip waits out (the server's hold-loop rule)",
            WhepState.Live,
            h.awaitState(WhepState.Live),
        )
        assertTrue(!h.fake.closed)
        h.viewer.stop()
    }

    @Test
    fun `first frame cancels the watchdog even when the deadline is close`() = runBlocking {
        val h = Harness()
        h.negotiate()
        // Almost the whole window elapses, THEN the frame arrives.
        h.tick((WhepSignaling.FIRST_FRAME_TIMEOUT_MS / WhepViewer.WATCHDOG_TICK_MS - 1).toInt())
        h.fake.firstFrame = true
        h.tick(1)
        // awaitState, not a bare read: the Live write rides the engine's
        // answer-processing coroutine, which under full-suite load can land
        // after this line without a park.
        assertEquals(WhepState.Live, h.awaitState(WhepState.Live))
        h.viewer.stop()
    }
}
