package com.raulshma.lenscast.companion.console

import app.cash.turbine.test
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.CountdownState
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.ws.TalkbackClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.playback.AudioListener
import com.raulshma.lenscast.companion.playback.MjpegPlayer
import com.raulshma.lenscast.companion.playback.WhepPeer
import com.raulshma.lenscast.companion.playback.WhepPeerLinkState
import com.raulshma.lenscast.companion.playback.WhepViewer
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.session.SessionTimings
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.selfTimerPref
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The remote console backend's rules, one MockWebServer round trip per
 * verdict — real threads (`runBlocking`, never `runTest`'s virtual clock;
 * the DeviceSessionManagerTest convention, since OkHttp's dispatcher threads
 * and the session's lane coroutines must actually run):
 *
 * - the session climb mirrored onto `cameraState` (Initializing → Ready →
 *   the stop-imposed Idle, the AUTH_FAILED re-pair error on the 401);
 * - torch/zoom optimistic writes with revert-on-failure, the
 *   Forbidden → viewer-permissions notice, and the write's own demand
 *   status read reconciling the accepted value once the write settles
 *   (no intermediate reset to the stale pre-write sample);
 * - quick-setting writes emitting the WHOLE camera-section PUT (and routing
 *   torch/zoom to their dedicated endpoints with no settings PUT at all);
 * - the capture flash event only on `POST /api/capture` success, immediate
 *   and self-timer fired;
 * - the recording poll's wire→[RecordingState] mapping plus the toggle;
 * - the stream verdicts: a failed whole-server start filling
 *   `lastServerError` with the shared wording (and a success clearing it),
 *   and the device's in-band `success:false, "…disabled"` answer degrading
 *   to the Disabled toast without leaving optimistic residue;
 * - the settings composition: the fetched document mapped through
 *   [RemoteSettingsMapper], overlaid with the status lane's live torch/zoom
 *   and the mapped control ranges.
 *
 * Robolectric is here only for [SettingsDataStore] (the viewer-local lanes —
 * the SettingsWebHandlerTorchTest pattern); every network interaction is a
 * real MockWebServer round trip. The playback factories are the noop set
 * (WHEP fails locally, HLS construction throws, MJPEG parks in its first
 * backoff), so no test touches a media transport it does not assert on.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceConsoleViewModelTest {

    private companion object {
        const val DEVICE_ID = "dev-console"
        const val AUTH_ERROR_MESSAGE = "Access was rejected. Re-pair this device to sign in again."
    }

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice
    private lateinit var settingsStore: SettingsDataStore

    private val parentScopes = mutableListOf<CoroutineScope>()

    /** Path(-prefix) → answer; unmatched routes hang (held-open silent stream). */
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val handler = routes[path]
                    ?: routes.entries.filter { path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }
                        ?.value
                return handler?.invoke(request) ?: hang()
            }
        }
        server.start()
        filesDir = createTempDir("console-vm")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = seedDevice()
        credentials.put(device.id, StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
        settingsStore = SettingsDataStore(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        parentScopes.forEach { it.cancel() }
        routes.clear()
        // A parked long-lived lane can outlast the shutdown patience after
        // the assertions have landed (the TalkbackClientTest precedent).
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private fun plaintextCipher() = object : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun seedDevice(): CompanionDevice = CompanionDevice(
        id = DEVICE_ID,
        name = "Console",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        role = "admin",
        addedAtMs = 0L,
    ).also { roster.upsert(it) }

    private fun hang(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

    private fun ok(json: String = """{"success":true}"""): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    private fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    /**
     * The truth fetches every CONNECTED edge fires (settings, lenses,
     * recording) — routed so no lane hangs on an unmatched path. Test-specific
     * routes registered BEFORE this call win (routes are overwritten in
     * map order here).
     */
    private fun routeConnectedDefaults() {
        route("/api/settings") { ok(settingsJson()) }
        route("/api/camera/lenses") { ok("""{"lenses":[],"selectedIndex":0}""") }
        route("/api/recording/status") { ok("""{"isRecording":false,"elapsedSeconds":0}""") }
    }

    // ── fixtures: status / settings / SSE ──

    private fun statusJson(
        torchOn: Boolean = false,
        zoomRatio: Double = 1.0,
        active: Boolean = false,
        withRanges: Boolean = false,
    ): String {
        val ranges = if (withRanges) {
            "," + "\"zoomRange\":{\"min\":1.0,\"max\":8.0}" +
                ",\"isoRange\":{\"min\":100,\"max\":1600}" +
                ",\"exposureCompensationRange\":{\"min\":-6,\"max\":6}"
        } else {
            ""
        }
        return "{\"streaming\":{\"isActive\":$active,\"url\":\"http://x/stream\"," +
            "\"webStreamingEnabled\":true,\"webStreamingActive\":false,\"clientCount\":0," +
            "\"audioEnabled\":false,\"audioUrl\":\"http://x/audio\",\"rtspEnabled\":true," +
            "\"rtspStreamingActive\":false,\"rtspUrl\":\"rtsp://x/live\"},\"thermal\":\"NORMAL\"," +
            "\"camera\":\"Ready\",\"battery\":{\"level\":50,\"isCharging\":false," +
            "\"isPowerSaveMode\":false},\"torchOn\":$torchOn,\"sirenActive\":false," +
            "\"zoomRatio\":$zoomRatio$ranges}"
    }

    private fun statusFrame(json: String) = "event: status\ndata: $json"

    private fun settingsJson(iso: Int = 400): String =
        "{\"camera\":{\"iso\":$iso,\"frameRate\":30},\"streaming\":{\"webStreamingEnabled\":true}}"

    /** SSE frames then a clean lifetime close — the lane falls to the poll ladder after. */
    private fun completingSse(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { "$it\n\n" }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    // ── fixtures: connected session (fast timings) ──

    private fun connectedSession(parent: Job): DeviceSession {
        val parentScope = CoroutineScope(parent + Dispatchers.IO)
        parentScopes.add(parentScope)
        val session = DeviceSession(DEVICE_ID)
        session.attach(roster, credentials, parentScope)
        session.timings = SessionTimings(
            tickMs = 20L,
            statusSseRetryMs = 40L,
            detectionPollMs = 60L,
            detectionSseRetryMs = 120L,
            ladderBackoffMs = listOf(20L, 30L, 40L),
            ladderGiveUpAtCapTier = 3,
            dialConnectTimeoutMs = 250L,
        )
        return session
    }

    // ── fixtures: the VM under test ──

    private class FailingPeer : WhepPeer {
        override fun attachRenderer(renderer: org.webrtc.SurfaceViewRenderer?) = Unit
        override suspend fun open(): String = throw IOException("peer refused")
        override fun acceptAnswer(answerSdp: String) = Unit
        override fun hasFirstFrameSignal(): Boolean = true
        override fun linkState(): WhepPeerLinkState = WhepPeerLinkState.CONNECTED
        override fun close() = Unit
    }

    private fun viewModel(
        scope: CoroutineScope,
        session: DeviceSession = DeviceSession(DEVICE_ID),
        canControl: Boolean = true,
        toasts: MutableCollection<String>? = null,
        // Robolectric freezes SystemClock — a real-time clock drives the countdown.
        selfTimerClock: () -> Long = { System.nanoTime() / 1_000_000L },
    ): DeviceConsoleViewModel = DeviceConsoleViewModel(
        session = session,
        api = LenscastApiClient(device) { credentials.get(device.id) },
        canControl = canControl,
        settingsDataStore = settingsStore,
        appContext = RuntimeEnvironment.getApplication(),
        scope = scope,
        // IO lanes but parented to the caller's job: an independent
        // SupervisorJob here outlives the test's finally-cancel and leaks
        // every player lane into the rest of the suite's JVM.
        playerScope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO),
        timings = ConsoleTimings(recordingPollMs = 40L),
        whepFactory = { api -> WhepViewer(api, peerFactory = { FailingPeer() }, scope = scope) },
        hlsFactory = { _ -> throw IOException("no ExoPlayer on the JVM") },
        mjpegFactory = { api ->
            MjpegPlayer(
                openStream = { throw IOException("noop mjpeg") },
                pauseMs = { awaitCancellation() },
                scope = CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO),
            )
        },
        audioFactory = { api -> AudioListener(api) },
        talkbackConnect = { api ->
            TalkbackClient(api.sharedEngine())
                .connect(api.talkbackWsUrl(), api.sharedAuthHeaders())
        },
        micFactory = null,
        micGranted = { true },
        toast = { message -> toasts?.add(message) },
        hasStoredCredential = true,
        selfTimerElapsedMs = selfTimerClock,
    )

    // ── awaiting helpers ──

    private suspend fun <T> awaitFlow(
        flow: StateFlow<T>,
        timeoutMs: Long = 10_000,
        condition: (T) -> Boolean,
    ): T {
        var hit: T? = null
        withTimeout(timeoutMs) {
            flow.first { value ->
                hit = value
                condition(value)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return hit as T
    }

    private suspend fun awaitNotice(
        vm: DeviceConsoleViewModel,
        timeoutMs: Long = 10_000,
        condition: (ConsoleEvent.Notice) -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            vm.consoleEvents
                .filterIsInstance<ConsoleEvent.Notice>()
                .first { notice -> condition(notice) }
        }
    }

    /** Drains the request queue until one matches; the stash is named on timeout. */
    private fun awaitRequest(
        timeoutSeconds: Long = 10,
        predicate: (RecordedRequest) -> Boolean,
    ): RecordedRequest {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        val stash = mutableListOf<String>()
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(250, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(request)) return request
            stash += "${request.method} ${request.path}"
        }
        error("expected a matching request within ${timeoutSeconds}s; saw $stash")
    }

    /** DataStore's shared flows re-emit off the store's scope — wait until the value lands. */
    private fun awaitStore(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "store value never landed" }
            Thread.sleep(10)
        }
    }


    // ── 1. cameraState across connection transitions ──

    @Test
    fun `cameraState mirrors the session climb and the stop-imposed idle`() = runBlocking {
        // The probe response is GATED: the CONNECTING beat must be observable
        // before the answer can land — no handler-side delay guessing the
        // observer's ordering.
        val probeAnswered = CountDownLatch(1)
        route("/api/status") {
            probeAnswered.await(10, TimeUnit.SECONDS)
            ok(statusJson())
        }
        route("/api/events") { completingSse(statusFrame(statusJson())) }
        route("/api/detection/events/stream") { completingSse() }
        routeConnectedDefaults()

        val session = connectedSession(coroutineContext.job)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope, session = session)
            // Await the CONNECTING climb first (subscription can precede the
            // session's first transition), then observe the beats IN ORDER
            // with turbine (the DeviceSessionManagerTest idiom) — a passive
            // collector can lose conflated neighbors.
            awaitFlow(vm.cameraState) { it is CameraState.Initializing }
            vm.cameraState.test(30.seconds) {
                assertTrue(awaitItem() is CameraState.Initializing) // the CONNECTING climb
                probeAnswered.countDown() // release the gated probe
                assertTrue(awaitItem() is CameraState.Ready) // the probe's answer
                session.stop()
                assertTrue(awaitItem() is CameraState.Idle) // the stop-imposed idle
            }
        } finally {
            session.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an auth-failed session settles the camera state on the re-pair error`() = runBlocking {
        route("/api/status") { MockResponse().setResponseCode(401).setBody("""{"error":"auth"}""") }

        val session = connectedSession(coroutineContext.job)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope, session = session)
            awaitFlow(vm.cameraState) { it is CameraState.Error }
            assertEquals(AUTH_ERROR_MESSAGE, (vm.cameraState.value as CameraState.Error).message)
            // The 401 probe's terminal verdict: runLink settles AUTH_FAILED
            // through finishTerminal → teardown, which cancels EVERY session
            // job attached to this runBlocking parent — both SSE streams and
            // the child scope carrying the link + detection-lane jobs. A
            // surviving child would park runBlocking's join below forever.
            assertEquals(ConnectionState.AUTH_FAILED, session.connectionState.value)
        } finally {
            // The session's own cleanup mechanism, belt-and-braces: on the
            // failure path (an awaitFlow timeout) the session could still be
            // climbing, and stop() tears its lanes down synchronously; on the
            // happy path the terminal teardown already emptied the scope, so
            // stop() is the no-op teardown + the IDLE reset. The cancelled
            // child scope (a SupervisorJob under runBlocking's job) is joined
            // by the block's own completion; scope.cancel() remains the
            // ViewModel scope's lever.
            session.stop()
            scope.cancel()
        }
    }

    // ── 2. torch: optimistic write, revert, forbidden notice ──

    @Test
    fun `torch quick-setting posts the torch route and sticks the optimistic fact`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            route("/api/camera/torch") { ok() }
            val vm = viewModel(scope)

            vm.updateQuickSetting(QuickSettingType.TORCH, true)
            awaitFlow(vm.settings) { it.torchEnabled }

            val request = awaitRequest { it.path == "/api/camera/torch" }
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("\"enabled\":true"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed torch write reverts the optimistic fact`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The same gating the stream rejection test pins: hold the
            // failure until the optimistic stick is observed, so the
            // flip→revert sequence is proven, not assumed.
            val torchFailed = CountDownLatch(1)
            route("/api/camera/torch") {
                torchFailed.await(10, TimeUnit.SECONDS)
                MockResponse().setResponseCode(500).setBody("""{"error":"x"}""")
            }
            val vm = viewModel(scope)

            vm.updateQuickSetting(QuickSettingType.TORCH, true)
            withTimeout(10_000) {
                while (!vm.settings.value.torchEnabled) delay(20)
            }
            torchFailed.countDown()
            awaitFlow(vm.settings) { !it.torchEnabled }

            assertEquals("/api/camera/torch", awaitRequest { it.path == "/api/camera/torch" }.path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a forbidden torch write reverts and publishes the viewer-permissions notice`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                route("/api/camera/torch") {
                    MockResponse().setResponseCode(403).setBody("""{"error":"role"}""")
                }
                val vm = viewModel(scope)

                vm.updateQuickSetting(QuickSettingType.TORCH, true)
                awaitNotice(vm) { it.resId == R.string.companion_live_forbidden }
                awaitFlow(vm.settings) { !it.torchEnabled }

                assertEquals("/api/camera/torch", awaitRequest { it.path == "/api/camera/torch" }.path)
            } finally {
                scope.cancel()
            }
        }

    // ── 3. zoom: optimistic + status-lane reconciliation, revert ──

    @Test
    fun `zoom posts the zoom route, sticks optimistically, and the demand status read reconciles`() =
        runBlocking {
            // The zoom reply is GATED: the optimistic stick must be observed
            // before the answer can land — the flip→reconcile sequence is
            // proven, not raced (the gated torch failure's pattern).
            val zoomAnswered = CountDownLatch(1)
            // The status answers swing on the SAME gate: until the write
            // settles, every GET (the connect probe, the poll rung) reports
            // the pre-write 1x; after it, the gated answer carries the
            // ACCEPTED 4x. The SSE lane stays at the pre-write frame for the
            // whole test, so reaching 4.0 can only come from a post-write
            // status read — the demand read the write itself fires — never
            // from an SSE update.
            route("/api/status") {
                ok(statusJson(zoomRatio = if (zoomAnswered.count == 0L) 4.0 else 1.0))
            }
            route("/api/events") { completingSse(statusFrame(statusJson())) }
            route("/api/detection/events/stream") { completingSse() }
            route("/api/camera/zoom") {
                zoomAnswered.await(10, TimeUnit.SECONDS)
                ok()
            }
            routeConnectedDefaults()

            val session = connectedSession(coroutineContext.job)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope, session = session)
                awaitFlow(vm.cameraState) { it is CameraState.Ready }

                vm.updateZoom(3.5f)
                awaitFlow(vm.settings) { it.zoomRatio == 3.5f } // the optimistic stick
                val request = awaitRequest { it.path == "/api/camera/zoom" }
                assertTrue(request.body.readUtf8().contains("\"zoomRatio\":3.5"))

                // While the reply is held, the write owns the mirror: the
                // stale 1x frames (the SSE lane's finite frame and its
                // retry-ladder re-dials, the poll rung) cannot stomp the
                // in-flight optimistic value.

                // The write settles: the reply releases, and the write's own
                // DEMAND read (GET /api/status, gated past the settle) is the
                // reconciliation carrier — its fresh frame settles the mirror
                // from the optimistic 3.5x straight to the accepted 4.0x, and
                // the SSE lane's continuing pre-write 1x can never deliver
                // that value.
                zoomAnswered.countDown()
                awaitFlow(vm.settings) { it.zoomRatio == 4.0f }
                Unit
            } finally {
                // The session's lanes are children of runBlocking's job: a
                // live session would park the block's join forever.
                session.stop()
                scope.cancel()
            }
        }

    @Test
    fun `a failed zoom write reverts to the device-reported ratio`() = runBlocking {
        // Hold the failure until the OPTIMISTIC 5x is observed — the same
        // gating the torch revert pins: an ungated 500 could land before the
        // stick and the 1x assertion would pass on the untouched default.
        val zoomFailed = CountDownLatch(1)
        route("/api/camera/zoom") {
            zoomFailed.await(10, TimeUnit.SECONDS)
            MockResponse().setResponseCode(500).setBody("""{"error":"x"}""")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope)
            vm.updateZoom(5f)
            awaitFlow(vm.settings) { it.zoomRatio == 5f } // the optimistic stick, observed while held
            zoomFailed.countDown() // release the 500
            // The failure path rolls the mirror back to the pinned pre-train
            // anchor (1x here — no session status to report another ratio);
            // a failed write runs NO demand status read, only the republish.
            awaitFlow(vm.settings) { it.zoomRatio == 1.0f }
            assertEquals("/api/camera/zoom", awaitRequest { it.path == "/api/camera/zoom" }.path)
        } finally {
            scope.cancel()
        }
    }

    // ── 4. quick-setting PUT: whole section / dedicated-endpoint routing ──

    @Test
    fun `a quick-setting write PUTs the whole camera section and re-reads the doc`() =
        runBlocking {
            // GETs and PUTs counted separately: the confirmation read after
            // the PUT is load-bearing — the published snapshot must carry the
            // device's accepted value from the SECOND GET, so removing the
            // re-read (leaving the pre-PUT document) fails the assertions.
            val settingsGets = AtomicInteger(0)
            val settingsPuts = AtomicInteger(0)
            route("/api/settings") { request ->
                if (request.method == "PUT") {
                    settingsPuts.incrementAndGet()
                    ok()
                } else {
                    val gets = settingsGets.incrementAndGet()
                    // The re-read after the PUT (the second GET) carries a
                    // DISTINCT accepted ISO — one only the confirmation read
                    // can deliver to the published snapshot.
                    ok(settingsJson(iso = if (gets >= 2) 1600 else 400))
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope)
                vm.updateQuickSetting(QuickSettingType.ISO, "800")
                // The published console snapshot carries the confirmation
                // read's accepted value (an optimistic-only echo would never
                // reach 1600).
                awaitFlow(vm.settings) { it.iso == 1600 }
                assertTrue(settingsGets.get() >= 2)
                assertEquals(1, settingsPuts.get())

                val put = awaitRequest { it.path == "/api/settings" && it.method == "PUT" }
                val body = put.body.readUtf8()
                // The wholesale rule: EVERY wire-carried field rides, even the
                // untouched ones — a partial section would reset the omitted
                // siblings to defaults on an older device.
                for (field in listOf(
                    "exposureCompensation", "iso", "focusMode", "whiteBalance", "zoomRatio",
                    "frameRate", "resolution", "stabilization", "hdrMode", "nightVisionMode",
                    "outputRotation", "orientationLocked",
                )) {
                    assertTrue("PUT body missing \"$field\": $body", body.contains("\"$field\""))
                }
                // The nullable-when-default fields ride Moshi's serializeNulls-off
                // omission (absent = the server's default on decode) — the whole
                // section is the mapper's round-trip test's contract; here the
                // wholesale shape is pinned by the twelve carried fields above.
                assertTrue(body.contains("\"iso\":800"))
                // The untouched wire fields ride the FRESH read, not the stale
                // visible snapshot: the snapshot (no doc fetched yet) carries
                // the model default 24 fps, the fresh GET reported 30 — the
                // ISO-only edit must re-PUT the device's 30, never 24.
                assertTrue(body.contains("\"frameRate\":30"))
                // Torch never rides the settings wire (the torch contract).
                assertTrue(!body.contains("\"torchEnabled\""))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `torch and zoom quick-settings ride their dedicated endpoints, never a settings PUT`() =
        runBlocking {
            val settingsHit = AtomicBoolean(false)
            route("/api/settings") {
                settingsHit.set(true)
                ok(settingsJson())
            }
            route("/api/camera/torch") { ok() }
            route("/api/camera/zoom") { ok() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope)

                vm.updateQuickSetting(QuickSettingType.TORCH, true)
                awaitFlow(vm.settings) { it.torchEnabled }
                assertEquals("/api/camera/torch", awaitRequest { it.path == "/api/camera/torch" }.path)

                vm.updateQuickSetting(QuickSettingType.ZOOM, 2f)
                awaitFlow(vm.settings) { it.zoomRatio == 2f }
                assertEquals("/api/camera/zoom", awaitRequest { it.path == "/api/camera/zoom" }.path)

                // A beat for any smuggled PUT to arrive, then the verdict.
                delay(300)
                assertEquals(false, settingsHit.get())
            } finally {
                scope.cancel()
            }
        }

    // ── 5. capture: flash only on success, immediate and self-timer fired ──

    @Test
    fun `shutter press posts capture and flashes only on success`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            route("/api/capture") { ok("""{"success":true,"fileName":"p.jpg"}""") }
            // Pin the mode: the DataStore is process-wide and other tests arm S3.
            settingsStore.saveSelfTimer(SelfTimerMode.OFF)
            // The DISK-backed gate: the shared flow's OFF is the placeholder
            // replay until DataStore's first read lands (the default-first
            // race) — an OFF `.value` is never a hydration signal. disk()
            // suspends until that read lands, so the press below rides a
            // hydrated store.
            assertEquals(SelfTimerMode.OFF, settingsStore.disk(selfTimerPref))
            val vm = viewModel(scope)

            // Subscribe FIRST: the flash is a no-replay SharedFlow emit at
            // fire time — a later subscriber never sees it.
            val flashed = CompletableDeferred<Unit>()
            val collector = launch { vm.captureFlashEvents.first(); flashed.complete(Unit) }
            delay(200) // let the collector subscribe
            vm.onShutterPress() // self-timer OFF → capture now
            withTimeout(10_000) { flashed.await() }

            assertEquals("/api/capture", awaitRequest { it.path == "/api/capture" }.path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed capture flashes nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            route("/api/capture") { MockResponse().setResponseCode(500).setBody("""{"error":"x"}""") }
            // Pin the mode: the DataStore is process-wide and other tests arm
            // S3 — this press must ride the immediate-capture path.
            settingsStore.saveSelfTimer(SelfTimerMode.OFF)
            // The DISK-backed gate (the shared flow's OFF replay is never a
            // hydration signal — the default-first race): disk() suspends
            // until DataStore's first read lands.
            assertEquals(SelfTimerMode.OFF, settingsStore.disk(selfTimerPref))
            val vm = viewModel(scope)

            // Subscribe FIRST: the flash is a no-replay SharedFlow emit — a
            // subscriber joining after the press would miss an earlier event
            // and the nothing-flashed verdict would prove nothing.
            var flashed = false
            val collector = launch { vm.captureFlashEvents.collect { flashed = true } }
            delay(200) // let the collector subscribe
            vm.onShutterPress() // self-timer OFF → capture now → 500

            awaitNotice(vm) { it.resId == R.string.companion_live_control_failed }
            delay(300) // a beat for any (wrong) flash to land
            collector.cancel()
            assertEquals(false, flashed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the self-timer countdown fires the capture POST at the end`() = runBlocking {
        route("/api/capture") { ok() }
        settingsStore.saveSelfTimer(SelfTimerMode.S3)
        awaitStore { settingsStore.selfTimer.value == SelfTimerMode.S3 }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope)

            // Subscribe before the press (no-replay flash), then arm the 3 s countdown.
            val flashed = CompletableDeferred<Unit>()
            val collector = launch { vm.captureFlashEvents.first(); flashed.complete(Unit) }
            delay(200)
            vm.onShutterPress()

            awaitFlow(vm.selfTimerState) { it is CountdownState.Counting }

            withTimeout(30_000) { flashed.await() } // the Fire boundary
            assertEquals("/api/capture", awaitRequest { it.path == "/api/capture" }.path)
            awaitFlow(vm.selfTimerState) { it is CountdownState.Idle }
            Unit
        } finally {
            scope.cancel()
        }
    }

    // ── 6. recording poll mapping + toggle ──

    @Test
    fun `the recording poll maps the wire status and the toggle drives start-stop`() =
        runBlocking {
            val recording = AtomicBoolean(false)
            route("/api/recording/status") {
                ok(
                    if (recording.get()) """{"isRecording":true,"elapsedSeconds":42}"""
                    else """{"isRecording":false,"elapsedSeconds":0}""",
                )
            }
            route("/api/recording/start") {
                recording.set(true)
                ok()
            }
            route("/api/recording/stop") {
                recording.set(false)
                ok()
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = viewModel(scope)
            try {
                vm.setActive(true) // polls (playback rides the noop factories)

                awaitFlow(vm.recordingState) { it is RecordingState.Idle }

                vm.toggleRecording()
                awaitFlow(vm.recordingState) { it is RecordingState.Recording }
                assertEquals("/api/recording/start", awaitRequest { it.path == "/api/recording/start" }.path)
                awaitFlow(vm.recordingElapsedSeconds) { it == 42 }

                // The double-tap guard swallows a toggle issued while the
                // previous write is still landing — the guard is the point.
                // Retry until the stop actually goes out (the state settles
                // Idle from the command's own publish or the poll), then pin
                // the wire verb.
                withTimeout(10_000) {
                    while (vm.recordingState.value !is RecordingState.Idle) {
                        vm.toggleRecording()
                        delay(50)
                    }
                }
                awaitFlow(vm.recordingState) { it is RecordingState.Idle }
                assertEquals("/api/recording/stop", awaitRequest { it.path == "/api/recording/stop" }.path)
            } finally {
                vm.setActive(false)
                scope.cancel()
            }
        }

    // ── 7. stream verdicts: whole-server failure line, in-band disabled ──

    @Test
    fun `a failed whole-server start fills lastServerError and a success clears it`() =
        runBlocking {
            val attempts = AtomicInteger(0)
            route("/api/stream/start") {
                if (attempts.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(500).setBody("""{"error":"x"}""")
                } else {
                    ok("""{"success":true,"isActive":true}""")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope)

                vm.toggleServer()
                awaitFlow(vm.lastServerError) { it != null }
                assertEquals("Failed to start server", vm.lastServerError.value)
                assertEquals("/api/stream/start", awaitRequest { it.path == "/api/stream/start" }.path)

                // Retrying is the natural dismiss: the successful start clears.
                vm.toggleServer()
                awaitFlow(vm.lastServerError) { it == null }
                Unit
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the device's in-band disabled verdict toasts the shared wording without residue`() =
        runBlocking {
            val toasts = CopyOnWriteArrayList<String>()
            // Hold the rejection until the test has SEEN the optimistic flip:
            // releasing it blind would make the flip→revert sequence a guess.
            val webRejected = CountDownLatch(1)
            route("/api/stream/web/start") {
                webRejected.await(10, TimeUnit.SECONDS)
                ok("""{"success":false,"error":"Web streaming is disabled"}""")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope, toasts = toasts)

                vm.toggleWebStreaming()
                // The optimistic flip is observable BEFORE the answer lands.
                withTimeout(10_000) {
                    while (!vm.streamStatus.value.isWebActive) delay(20)
                }
                webRejected.countDown() // release the in-band rejection
                // The mirror reverts (no residue) and the Disabled verdict's
                // wording rides the toast — StreamStartOutcome's, single-homed.
                withTimeout(10_000) {
                    while (vm.streamStatus.value.isWebActive) delay(20)
                }
                withTimeout(10_000) {
                    while (toasts.none { it.contains("disabled in settings") }) delay(50)
                }
            } finally {
                scope.cancel()
            }
        }

    // ── 8. settings composition: doc + live facts ──

    @Test
    fun `settings compose the fetched document with the status lane's live facts`() =
        runBlocking {
            val live = statusJson(torchOn = true, zoomRatio = 2.5, withRanges = true)
            route("/api/status") { ok(live) }
            route("/api/events") { completingSse(statusFrame(live)) }
            route("/api/detection/events/stream") { completingSse() }
            route("/api/settings") { ok(settingsJson(iso = 400)) }
            route("/api/camera/lenses") { ok("""{"lenses":[],"selectedIndex":0}""") }
            route("/api/recording/status") { ok("""{"isRecording":false,"elapsedSeconds":0}""") }

            val session = connectedSession(coroutineContext.job)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val vm = viewModel(scope, session = session)
                val settings = awaitFlow(vm.settings) {
                    it.iso == 400 && it.torchEnabled && it.zoomRatio == 2.5f
                }

                // The document's fields ride the mapper; the live facts ride
                // the status lane (torch true, zoom 2.5 — NOT the doc's 1.0).
                assertEquals(true, settings.torchEnabled)
                assertEquals(2.5f, settings.zoomRatio)
                assertEquals(30, settings.frameRate)
                // The device's live ranges mapped through the mappers —
                // awaited, not read: the settings predicate is already
                // satisfiable when the doc lane's publishSettings composes
                // straight off `session.status.value`, BEFORE the status
                // collector has applied the same snapshot to the range flows
                // (the intermittent 100..3200 mapper-fallback read). The
                // collector is the ranges' only writer, so its application is
                // the end-state marker both lanes must have passed.
                assertEquals(100..1600, awaitFlow(vm.availableIsoRange) { it == (100..1600) })
                assertEquals(-6..6, awaitFlow(vm.availableExposureRange) { it == (-6..6) })
                assertEquals(
                    1f..8f,
                    awaitFlow(vm.availableZoomRange) { it.start == 1f && it.endInclusive == 8f },
                )
            } finally {
                // The session's lanes are children of runBlocking's job: a
                // live session would park the block's join forever.
                session.stop()
                scope.cancel()
            }
        }

    // ── 9. capability verdicts ──

    @Test
    fun `a viewer pairing keeps canControl off while the audio lanes stay on`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = viewModel(scope, canControl = false)
            val caps = vm.capabilities.value
            assertEquals(false, caps.canControl)
            assertEquals(true, caps.talkback) // RoleGatePolicy: the doorbell write is viewer-allowed
            assertEquals(true, caps.liveAudio) // and /audio is a read
            assertEquals(true, caps.selfTimer)
            assertEquals(true, caps.tapToFocus)
            assertEquals(true, caps.pip)
            assertEquals(false, caps.serverPanel)
            assertEquals(false, caps.proToolsOverlays)
            assertEquals(false, caps.spiritLevel)
            // No wire carrier for the capture flash mode — the chip must not
            // render (cycleFlashMode stays a deliberate no-op).
            assertEquals(false, caps.flashControl)
        } finally {
            scope.cancel()
        }
    }
}
