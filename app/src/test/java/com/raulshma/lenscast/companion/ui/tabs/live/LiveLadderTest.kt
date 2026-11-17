package com.raulshma.lenscast.companion.ui.tabs.live

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.companion.playback.PlayerLadder
import com.raulshma.lenscast.companion.playback.RetryBudget
import com.raulshma.lenscast.companion.playback.WhepPeer
import com.raulshma.lenscast.companion.playback.WhepPeerLinkState
import com.raulshma.lenscast.companion.playback.WhepViewer
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The live tab's ladder wiring, driven through VM STATE only (no ladder
 * internals poked): WHEP's failure demotes to HLS, HLS's construction failure
 * demotes to MJPEG; the manual cycle wraps MJPEG → WHEP and is cooldown-blind
 * (the web's cyclePlayerMode semantics); a reconnect's `reset(WHEP)` skips
 * rungs inside their post-demote cooldown, and after the cooldown expires the
 * reset picks WHEP again.
 *
 * Determinism: everything runs on REAL threads (runBlocking — never virtual
 * time beside OkHttp threads), and time comes from seams — a fake
 * millisecond clock behind [PlayerLadder]'s `nowMs`, the viewers' `pauseMs`
 * (advances the clock), and the MJPEG budget's tiny backoff — so the budget
 * exhausts in microseconds of wall time without a real sleep. The VM's own
 * terminal-rung retry parks in `awaitCancellation` instead, keeping the test
 * loop-free; the ladder decision the parked coroutine guards is asserted via
 * the cycle/reset paths instead.
 */
class LiveLadderTest {

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: LadderDispatcher
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var sessions: DeviceSessionManager

    // The session manager's own scope — held separately so tearDown can
    // cancel every lane the manager (and its viewers' hold loops) parked on
    // Default, instead of leaking them into the rest of the suite's JVM.
    private val sessionsScope = CoroutineScope(SupervisorJob())

    /** The fake clock the ladder, the viewers and the MJPEG budget share. */
    private val clock = AtomicLong(0L)

    /** The peer every viewer mints; flipped by the scenarios. */
    private val peer = FakePeer()

    private val statusJson =
        "{\"streaming\":{\"isActive\":false,\"url\":\"\",\"webStreamingEnabled\":false," +
            "\"webStreamingActive\":false,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"\"},\"thermal\":\"NORMAL\"," +
            "\"battery\":{\"level\":50,\"isCharging\":false,\"isPowerSaveMode\":false},\"camera\":\"ok\"," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    /** The WHEP answer body (any SDP-looking text the wire verdict accepts). */
    private val answerSdp = "v=0\no=- 1 1 IN IP4 127.0.0.1\ns=LensCast\n"

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = LadderDispatcher()
        server.dispatcher = dispatcher
        server.start()
        filesDir = createTempDir("live-ladder")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, PlaintextCipher)
        roster.upsert(
            CompanionDevice(
                id = "dev-1",
                name = "Garage",
                host = "127.0.0.1",
                port = server.port,
                auth = CompanionAuthKind.TOKEN,
                role = "viewer", // no recording poll noise in this suite
                addedAtMs = 0L,
            ),
        )
        credentials.put("dev-1", StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
        sessions = DeviceSessionManager(roster, credentials, sessionsScope)
    }

    @After
    fun tearDown() {
        sessions.release("dev-1") // stops the session and its parked lanes
        // Cancel the manager scope too: a viewer that reached Live parks in
        // the hold loop forever by design — release() ends the session's
        // lanes, but anything the ladder still spun up dies here.
        sessionsScope.cancel()
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private object PlaintextCipher : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    /**
     * The fake peer: open() throws while [FakePeer.failOpen] is set (no
     * network), otherwise returns a canned SDP and the link reads CONNECTED —
     * the viewer's watchdog passes straight through to Live.
     */
    private class FakePeer : WhepPeer {
        @Volatile var failOpen = true

        override fun attachRenderer(renderer: org.webrtc.SurfaceViewRenderer?) = Unit

        override suspend fun open(): String {
            if (failOpen) throw IOException("peer refused the offer")
            return "v=0\noffer\n"
        }

        override fun acceptAnswer(answerSdp: String) = Unit
        override fun hasFirstFrameSignal(): Boolean = !failOpen
        override fun linkState(): WhepPeerLinkState = WhepPeerLinkState.CONNECTED

        override fun close() = Unit
    }

    /**
     * Path-matched answers: /api/status serves a valid status snapshot on
     * EVERY poll (the session's periodic poll must keep succeeding — a hung
     * status read times out on real-time OkHttp budgets and flips the session
     * to RECONNECTING, whose recovery `reset(WHEP)` stomps the ladder under
     * test), /api/stream/clients serves the empty roster, /whep serves the
     * queued 201 SDP answer. Only the session's SSE lanes hang open-but-silent
     * (they have no read budget and must just stay open). Bodies are passed
     * in — the dispatcher is a nested class and cannot see the test's
     * constants.
     */
    private class LadderDispatcher : Dispatcher() {

        private val status = ArrayDeque<MockResponse>()
        private val whep = ArrayDeque<MockResponse>()

        /** The last status body — re-served for every poll past the queue. */
        @Volatile
        private var statusBody: String = ""

        @Synchronized
        fun enqueueStatus(body: String) {
            statusBody = body
            status.addLast(MockResponse().setResponseCode(200).setBody(body))
        }

        @Synchronized
        fun enqueueWhepAnswer(body: String) {
            whep.addLast(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Location", "/whep/test-session")
                    .setBody(body),
            )
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return when {
                path.startsWith("/api/status") -> synchronized(this) {
                    status.removeFirstOrNull()
                        ?: MockResponse().setResponseCode(200).setBody(statusBody)
                }
                path.startsWith("/api/stream/clients") -> MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        "{\"httpClients\":[],\"httpCount\":0,\"rtspCount\":0," +
                            "\"maxHttp\":4,\"rtspClients\":[]}",
                    )
                path.startsWith("/whep") -> synchronized(this) {
                    whep.removeFirstOrNull() ?: hang()
                }
                else -> hang()
            }
        }

        private fun hang(): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("retry: 1000\n\n")
            // 10s, not 60: every parked read holds an OkHttp dispatcher
            // permit (5 per host by default), and a 60 s body delay pinned
            // them for the whole budget — a post-cycle WHEP could queue
            // behind the parked lanes and burn awaitReady's tail (the
            // observed 90 s stall). The retry header re-dials a lapsed hang
            // within a second, so the silent-lane shape survives the
            // shorter hold; the permits just circulate.
            .setBodyDelay(10_000, TimeUnit.MILLISECONDS)
    }

    private suspend fun awaitReady(
        vm: LiveViewModel,
        timeoutMs: Long = 30_000, // seconds-scale when healthy: the ladder's budgets are sub-second seams; slack only for suite-load scheduling
        condition: (LiveUiState.Ready) -> Boolean,
    ): LiveUiState.Ready {
        var hit: LiveUiState.Ready? = null
        try {
            withTimeout(timeoutMs) {
                vm.state.first { current ->
                    val ready = current as? LiveUiState.Ready ?: return@first false
                    hit = ready
                    condition(ready)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // TEMP DIAGNOSTIC: where did the chain stall?
            println("LADDER-DIAG last state=${hit ?: vm.state.value} clock=${clock.get()} " +
                "threads=${Thread.getAllStackTraces().entries.count { it.key.name.contains("okhttp") }}")
            throw e
        }
        return checkNotNull(hit)
    }

    @Test
    fun `failures demote whep to hls to mjpeg through the vm state`() = runBlocking {
        // WHEP fails locally (peer refuses the offer); HLS construction throws
        // on the JVM; MJPEG's budget exhausts against a failing transport.
        dispatcher.enqueueStatus(statusJson)
        val session = sessions.session("dev-1")
        session.start()
        withTimeout(30_000) { session.connectionState.first { it == ConnectionState.CONNECTED } } // 30s: the SSE session connect stretches past 15s under a loaded suite JVM

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = buildViewModel(scope, session)
            vm.setActive(true)

            // Two demotes land the terminal rung; its budget exhausts quickly.
            val ready = awaitReady(vm) { it.rung == PlaybackRung.MJPEG && it.rungPhase == RungPhase.FAILED }
            assertEquals(PlaybackRung.MJPEG, ready.rung)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the manual cycle wraps mjpeg to whep and ignores cooldowns`() = runBlocking {
        dispatcher.enqueueStatus(statusJson)
        val session = sessions.session("dev-1")
        session.start()
        withTimeout(30_000) { session.connectionState.first { it == ConnectionState.CONNECTED } } // 30s: the SSE session connect stretches past 15s under a loaded suite JVM

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = buildViewModel(scope, session)
            vm.setActive(true)
            awaitReady(vm) { it.rung == PlaybackRung.MJPEG && it.rungPhase == RungPhase.FAILED }

            // WHEP is inside its post-demote cooldown (the fake clock never
            // advanced past it) — the cycle is cooldown-blind by contract, and
            // the succeeding peer + queued answer take the rung live.
            peer.failOpen = false
            dispatcher.enqueueWhepAnswer(answerSdp)
            vm.onCycleRung()

            val ready = awaitReady(vm) { it.rung == PlaybackRung.WHEP && it.rungPhase == RungPhase.LIVE }
            assertEquals(PlaybackRung.WHEP, ready.rung)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reconnect reset skips cooled rungs until their cooldown expires`() = runBlocking {
        dispatcher.enqueueStatus(statusJson)
        val session = sessions.session("dev-1")
        session.start()
        withTimeout(30_000) { session.connectionState.first { it == ConnectionState.CONNECTED } } // 30s: the SSE session connect stretches past 15s under a loaded suite JVM

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = buildViewModel(scope, session)
            vm.setActive(true)
            // The initial demote chain: WHEP and HLS cool down, MJPEG fails.
            awaitReady(vm) { it.rung == PlaybackRung.MJPEG && it.rungPhase == RungPhase.FAILED }

            // Reconnect #1 — the cooldown-honored case. A succeeding peer and
            // a queued answer are armed BEFORE the reconnect: had reset(WHEP)
            // ignored the cooling stamps, the rung would land WHEP/LIVE and
            // stay there. The observed verdict is MJPEG/FAILED — stable, so
            // this await doubles as the negative proof (it would time out on
            // the cooldown-blind misbehavior).
            peer.failOpen = false
            dispatcher.enqueueWhepAnswer(answerSdp)
            session.stop()
            dispatcher.enqueueStatus(statusJson)
            session.start()
            withTimeout(30_000) { session.connectionState.first { it == ConnectionState.CONNECTED } } // 30s: the SSE session connect stretches past 15s under a loaded suite JVM
            awaitReady(vm) {
                it.connection == ConnectionState.CONNECTED &&
                    it.rung == PlaybackRung.MJPEG &&
                    it.rungPhase == RungPhase.FAILED
            }

            // Reconnect #2, past the cooldown: reset(WHEP) now picks WHEP and
            // the armed answer takes it live — through VM state alone.
            clock.addMilliseconds(cooldownMs + 1_000)
            dispatcher.enqueueWhepAnswer(answerSdp)
            session.stop()
            dispatcher.enqueueStatus(statusJson)
            session.start()
            withTimeout(30_000) { session.connectionState.first { it == ConnectionState.CONNECTED } } // 30s: the SSE session connect stretches past 15s under a loaded suite JVM
            val ready = awaitReady(vm) { it.rung == PlaybackRung.WHEP && it.rungPhase == RungPhase.LIVE }
            assertEquals(PlaybackRung.WHEP, ready.rung)
        } finally {
            scope.cancel()
        }
    }

    // ── wiring ──

    private val cooldownMs = 30_000L

    private fun buildViewModel(scope: CoroutineScope, session: DeviceSession): LiveViewModel {
        val api = LenscastApiClient(checkNotNull(roster.find("dev-1"))) { credentials.get("dev-1") }
        return LiveViewModel(
            session = session,
            api = api,
            canControl = false,
            hasStoredCredential = true,
            scope = scope,
            playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ladder = PlayerLadder(
                start = PlaybackRung.WHEP,
                nowMs = { clock.get() },
                cooldownMs = cooldownMs,
            ),
            timings = LiveTimings(clientsRefreshMs = 3_600_000),
            // The VM's terminal-rung retry parks instead of looping — the
            // ladder decisions under test are the demote/cycle/reset paths.
            pauseMs = { awaitCancellation() },
            whepFactory = { apiClient ->
                WhepViewer(
                    apiClient,
                    peerFactory = { peer },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    clockMs = { clock.get() },
                    pauseMs = { clock.addMilliseconds(it) },
                )
            },
            hlsFactory = { _ -> throw IOException("no ExoPlayer on the JVM") },
            mjpegFactory = { _ ->
                com.raulshma.lenscast.companion.playback.MjpegPlayer(
                    openStream = { throw IOException("mjpeg refused") },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    clockMs = { clock.get() },
                    pauseMs = { clock.addMilliseconds(it) },
                    budget = RetryBudget(maxErrors = 2, backoffStepMs = 10),
                )
            },
            audioFactory = { apiClient -> com.raulshma.lenscast.companion.playback.AudioListener(apiClient) },
            talkbackConnect = { apiClient ->
                com.raulshma.lenscast.companion.client.ws.TalkbackClient(apiClient.sharedEngine())
                    .connect(apiClient.talkbackWsUrl(), apiClient.sharedAuthHeaders())
            },
            micFactory = null,
        )
    }

    private fun AtomicLong.addMilliseconds(ms: Long) {
        addAndGet(ms)
    }
}
