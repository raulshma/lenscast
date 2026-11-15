package com.raulshma.lenscast.companion.ui.tabs.live

import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.playback.AudioListener
import com.raulshma.lenscast.companion.playback.MicTalkbackSource
import com.raulshma.lenscast.companion.playback.MjpegPlayer
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.companion.playback.PlayerLadder
import com.raulshma.lenscast.companion.playback.WhepPeer
import com.raulshma.lenscast.companion.playback.WhepPeerLinkState
import com.raulshma.lenscast.companion.playback.WhepViewer
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The live tab ViewModel's control rules, one MockWebServer round trip per
 * verdict: every command POSTs its exact route; 200 → the optimistic state
 * sticks, 403 → the viewer-permissions notice and a revert, 401 → the
 * re-pair notice, anything else → the generic control notice. Also pinned:
 * the clients roster's WEB/RTSP mapping, the kick confirm's DELETE + refresh,
 * the viewer role's write gate, the recording poll's active-only cadence, and
 * the talkback cookie-gate mapping (`companion_live_talkback_needs_login`).
 *
 * `runBlocking` with REAL threads — OkHttp's dispatcher threads are real, so
 * virtual time would false-pass (the DevicesViewModelTest header note).
 * Playback factories are the noop set ([failingFactories]): WHEP fails
 * locally with no network, HLS construction throws, MJPEG parks in its first
 * budget backoff — the ladder itself has its own test class.
 */
class LiveViewModelControlsTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        filesDir = createTempDir("live-vm")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, PlaintextCipher)
        device = seedDevice()
        credentials.put(device.id, StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
    }

    @After
    fun tearDown() {
        // A parked long-lived lane (the session's SSE sockets, a WS transport)
        // can outlast MockWebServer's shutdown patience after the assertions
        // have already landed — the TalkbackClientTest precedent.
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private object PlaintextCipher : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    /** A minimal but valid GET /api/status answer (the session-mirror shape). */
    private val statusJson =
        "{\"streaming\":{\"isActive\":false,\"url\":\"\",\"webStreamingEnabled\":false," +
            "\"webStreamingActive\":false,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"\"},\"thermal\":\"NORMAL\"," +
            "\"battery\":{\"level\":50,\"isCharging\":false,\"isPowerSaveMode\":false},\"camera\":\"ok\"," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    private fun ok() = MockResponse().setResponseCode(200).setBody("""{"success":true}""")

    /** The clients roster the clients-card fetches parse (one web + one rtsp row). */
    private fun clientsJson() =
        "{\"httpClients\":[\"192.168.1.20:45110\"],\"httpCount\":1,\"rtspCount\":1,\"maxHttp\":4," +
            "\"rtspClients\":[{\"id\":\"rtsp-1\",\"remoteAddress\":\"192.168.1.30:51000\"," +
            "\"connectedAtMs\":1700000000000,\"transport\":\"TCP\",\"media\":[\"video\"]," +
            "\"playing\":true,\"framesSent\":10}]}"

    private fun seedDevice(id: String = "dev-1"): CompanionDevice = CompanionDevice(
        id = id,
        name = "Garage",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        role = "admin",
        addedAtMs = 0L,
    ).also { roster.upsert(it) }

    /**
     * A WHEP peer that fails the offer locally — the noop rung set's way of
     * never touching the network (see the class KDoc).
     */
    private class FailingPeer : WhepPeer {
        override fun attachRenderer(renderer: org.webrtc.SurfaceViewRenderer?) = Unit
        override suspend fun open(): String = throw IOException("peer refused")
        override fun acceptAnswer(answerSdp: String) = Unit
        override fun hasFirstFrameSignal(): Boolean = true
        override fun linkState(): WhepPeerLinkState = WhepPeerLinkState.CONNECTED
        override fun close() = Unit
    }

    /** The VM under test — scope is the caller's, cancelled in each test's finally. */
    private fun viewModel(
        scope: CoroutineScope,
        canControl: Boolean = true,
        timings: LiveTimings = LiveTimings(),
        session: DeviceSession = DeviceSession("dev-1"),
        hasStoredCredential: Boolean = true,
        micFactory: (() -> MicTalkbackSource)? = null,
    ): LiveViewModel = LiveViewModel(
        session = session,
        api = LenscastApiClient(device) { credentials.get(device.id) },
        canControl = canControl,
        hasStoredCredential = hasStoredCredential,
        scope = scope,
        playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ladder = PlayerLadder(start = PlaybackRung.WHEP),
        timings = timings,
        whepFactory = { api -> WhepViewer(api, peerFactory = { FailingPeer() }, scope = scope) },
        hlsFactory = { _ -> throw IOException("no ExoPlayer on the JVM") },
        mjpegFactory = { api ->
            MjpegPlayer(
                openStream = { throw IOException("noop mjpeg") },
                // Park in the first backoff: one failed attempt, no network, no churn.
                pauseMs = { awaitCancellation() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        },
        audioFactory = { api -> AudioListener(api) },
        talkbackConnect = { api ->
            com.raulshma.lenscast.companion.client.ws.TalkbackClient(api.sharedEngine())
                .connect(api.talkbackWsUrl(), api.sharedAuthHeaders())
        },
        micFactory = micFactory,
    )

    /** Parks until the single Ready field reaches [condition] (no sleeps). */
    private suspend fun awaitState(
        vm: LiveViewModel,
        timeoutMs: Long = 10_000,
        condition: (LiveUiState.Ready) -> Boolean,
    ): LiveUiState.Ready {
        var hit: LiveUiState.Ready? = null
        withTimeout(timeoutMs) {
            vm.state.first { current ->
                val ready = current as? LiveUiState.Ready ?: return@first false
                hit = ready
                condition(ready)
            }
        }
        return checkNotNull(hit)
    }

    private suspend fun awaitEffect(
        vm: LiveViewModel,
        // 60s, not 10: the notice rides a real WS dial on a shared OkHttp
        // engine, and under full-suite load that chain has stretched past
        // 10s. A real regression still fails, just bounded at a minute.
        timeoutMs: Long = 60_000,
        condition: (LiveEffect.Notice) -> Boolean,
    ): LiveEffect.Notice {
        var hit: LiveEffect.Notice? = null
        withTimeout(timeoutMs) {
            vm.effects.first { effect ->
                val notice = effect as? LiveEffect.Notice ?: return@first false
                hit = notice
                condition(notice)
            }
        }
        return checkNotNull(hit)
    }

    private fun takeRequest(timeoutSeconds: Long = 10): RecordedRequest =
        checkNotNull(server.takeRequest(timeoutSeconds, TimeUnit.SECONDS)) {
            "expected a request within ${timeoutSeconds}s"
        }

    // ── controls ──

    @Test
    fun `torch toggle posts the torch route and sticks the optimistic state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onTorchToggle()
            awaitState(vm) { it.torchOn }

            val request = takeRequest()
            assertEquals("/api/camera/torch", request.path)
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("\"enabled\":true"))
            // The token pairing rides its header on every lane.
            assertEquals("t", request.getHeader("X-Api-Token"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a 403 torch verdict reverts and posts the viewer-permissions notice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"role"}"""))
            val vm = viewModel(scope)

            vm.onTorchToggle()
            awaitEffect(vm) { it.resId == R.string.companion_live_forbidden }

            // No optimistic residue: the mirror reverted before the notice.
            val ready = awaitState(vm) { !it.torchOn }
            assertEquals(false, ready.torchOn)
            assertEquals("/api/camera/torch", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a 401 torch verdict posts the re-pair notice`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
            val vm = viewModel(scope)

            vm.onTorchToggle()
            awaitEffect(vm) { it.resId == R.string.companion_live_control_auth }

            assertEquals("/api/camera/torch", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `capture photo posts the capture route and confirms`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onCapturePhoto()
            awaitEffect(vm) { it.resId == R.string.companion_live_capture_ok }

            assertEquals("/api/capture", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `siren toggle posts the siren route with the wire field`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onSirenToggle()
            awaitState(vm) { it.sirenActive }

            val request = takeRequest()
            assertEquals("/api/deterrence/siren", request.path)
            assertTrue(request.body.readUtf8().contains("\"siren\":true"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `web stream toggle posts start then stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onWebStreamToggle()
            awaitState(vm) { it.webStreamActive }
            assertEquals("/api/stream/web/start", takeRequest().path)

            vm.onWebStreamToggle()
            awaitState(vm) { !it.webStreamActive }
            assertEquals("/api/stream/web/stop", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `zoom commit posts the previewed ratio`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onZoomPreview(2.5)
            vm.onZoomCommit()
            awaitState(vm) { it.zoomRatio == 2.5 } // synchronous preview; park keeps ordering

            val request = takeRequest()
            assertEquals("/api/camera/zoom", request.path)
            assertTrue(request.body.readUtf8().contains("\"zoomRatio\":2.5"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recording toggle posts start and lands the recording view`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(ok())
            val vm = viewModel(scope)

            vm.onRecordingToggle()
            awaitState(vm) { it.recording?.isRecording == true }

            assertEquals("/api/recording/start", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    // ── clients card ──

    @Test
    fun `clients list maps web and rtsp rows`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(clientsJson()))
            val vm = viewModel(scope)

            vm.refreshClients()
            val ready = awaitState(vm) { it.clients.size == 2 }

            val web = ready.clients[0]
            assertEquals("192.168.1.20:45110", web.label)
            assertEquals(StreamClientRow.ClientKind.WEB, web.kind)
            assertNull(web.connectedAtMs)
            val rtsp = ready.clients[1]
            assertEquals("rtsp-1", rtsp.id)
            assertEquals("192.168.1.30:51000", rtsp.label)
            assertEquals(StreamClientRow.ClientKind.RTSP, rtsp.kind)
            assertEquals(1700000000000L, rtsp.connectedAtMs)
            assertEquals("/api/stream/clients", takeRequest().path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `kick confirm deletes the encoded client id and refreshes the roster`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(clientsJson())) // initial roster
            server.enqueue(ok()) // the kick DELETE
            server.enqueue(ok()) // the post-kick refresh (body irrelevant to the asserts)
            val vm = viewModel(scope)

            vm.refreshClients()
            awaitState(vm) { it.clients.size == 2 }

            vm.onRequestKick("192.168.1.20:45110")
            vm.onConfirmKick()
            awaitEffect(vm) { it.resId == R.string.companion_live_kick_ok }

            assertEquals("/api/stream/clients", takeRequest().path)
            val kick = takeRequest()
            assertEquals("DELETE", kick.method)
            assertEquals("/api/stream/clients/192.168.1.20%3A45110", kick.path)
            assertEquals("/api/stream/clients", takeRequest().path) // the refresh
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `viewer pairing cannot trigger controls`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope, canControl = false)

            vm.onTorchToggle()
            vm.onCapturePhoto()
            vm.onRecordingToggle()
            vm.onConfirmKick() // nothing armed; must stay a no-op regardless

            assertEquals(0, server.requestCount)
            assertEquals(false, (vm.state.value as LiveUiState.Ready).torchOn)
        } finally {
            scope.cancel()
        }
    }

    // ── recording poll cadence ──

    @Test
    fun `recording status polls only while the strip is active`() = runBlocking {
        val dispatcher = PathDispatcher()
        server.dispatcher = dispatcher
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            dispatcher.enqueue("/api/recording/status", """{"isRecording":true,"elapsedSeconds":42}""")
            dispatcher.enqueue("/api/recording/status", """{"isRecording":true,"elapsedSeconds":57}""")
            val vm = viewModel(
                scope,
                timings = LiveTimings(clientsRefreshMs = 60_000, recordingPollMs = 150),
            )

            vm.setActive(true)
            awaitState(vm) { it.recording?.elapsedSeconds == 42 }
            // The periodic tick: the second answer proves a second poll.
            awaitState(vm) { it.recording?.elapsedSeconds == 57 }
            val pollsWhileActive = dispatcher.count("/api/recording/status")
            assertTrue("expected ≥2 polls, saw $pollsWhileActive", pollsWhileActive >= 2)

            vm.setActive(false)
            // Negative proof needs a window, not an await: one bounded delay,
            // then the poll count must be frozen (the loop was cancelled).
            kotlinx.coroutines.delay(5 * 150)
            assertEquals(pollsWhileActive, dispatcher.count("/api/recording/status"))
        } finally {
            scope.cancel()
        }
    }

    // ── talkback cookie gate ──

    @Test
    fun `talkback failure without a cookie maps to the sign-in notice`() = runBlocking {
        // Path-matched: the session's SSE lanes park on hang answers while
        // the WS dial gets its own plain-200 (non-upgrade) refusal.
        val dispatcher = PathDispatcher()
        server.dispatcher = dispatcher
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = com.raulshma.lenscast.companion.session.DeviceSessionManager(
            roster,
            credentials,
            CoroutineScope(SupervisorJob()),
        )
        try {
            // A connected session (the PTT gate requires the CONNECTED chip).
            dispatcher.enqueue("/api/status", statusJson)
            val session = manager.session("dev-1")
            session.start()
            withTimeout(10_000) { session.connectionState.first { it == ConnectionState.CONNECTED } }

            // The WS dial answers a plain 200 — not an upgrade: the handshake
            // fails immediately, TalkbackState lands FAILED.
            dispatcher.enqueue("/ws/talkback", """{"success":true}""")
            val vm = viewModel(scope, session = session, micFactory = null)

            // The hold gates on the VM's connection MIRROR (not the session)
            // — wait for the mirror's first emission before pressing, or the
            // press lands on the IDLE default and is silently dropped (the
            // suite-load race no budget could paper over).
            awaitState(vm) { it.connection == ConnectionState.CONNECTED }
            vm.onTalkStart()
            awaitEffect(vm) { it.resId == R.string.companion_live_talkback_needs_login }

            val ready = vm.state.value as LiveUiState.Ready
            assertTrue(ready.talkbackNeedsLogin)
            assertEquals(false, ready.talkbackActive)
        } finally {
            manager.release("dev-1")
            scope.cancel()
        }
    }
}

/**
 * Path-matched response queues: requests to [enqueue]ed prefixes consume the
 * FIFO answer for that route; an exhausted queue re-serves that route's LAST
 * body (polls are idempotent here — a hung status read would trip OkHttp's
 * real-time read budget and flip the session to RECONNECTING mid-assert);
 * routes never enqueued get a hang-style SSE answer (headers immediately,
 * body 60 s late) so the session's long-lived SSE lanes park open-but-silent
 * instead of blocking in a global FIFO queue — and two concurrent
 * activate-time requests (clients + recording) can never cross-consume each
 * other's answers. Never blocks: every dispatch returns.
 */
private class PathDispatcher : Dispatcher() {

    private val queues = LinkedHashMap<String, ArrayDeque<MockResponse>>()
    private val lastBody = LinkedHashMap<String, String>()
    private val hits = LinkedHashMap<String, Int>()

    @Synchronized
    fun enqueue(pathPrefix: String, body: String) {
        queues.getOrPut(pathPrefix) { ArrayDeque() }
            .addLast(MockResponse().setResponseCode(200).setBody(body))
        lastBody[pathPrefix] = body
    }

    @Synchronized
    fun count(pathPrefix: String): Int = hits[pathPrefix] ?: 0

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        val prefix = synchronized(this) {
            queues.keys.firstOrNull { path.startsWith(it) }
        } ?: return hang()
        synchronized(this) {
            hits[prefix] = (hits[prefix] ?: 0) + 1
        }
        val queued = synchronized(this) { queues[prefix]?.removeFirstOrNull() }
        if (queued != null) return queued
        val body = synchronized(this) { lastBody[prefix] } ?: return hang()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    /** Open-but-silent: the SSE lanes read it as a live stream; JSON readers time out. */
    private fun hang(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("retry: 1000\n\n")
        .setBodyDelay(60_000, TimeUnit.MILLISECONDS)
}
