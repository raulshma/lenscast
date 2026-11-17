package com.raulshma.lenscast.companion.session

import app.cash.turbine.test
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.core.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The device session's live-link contract, against a real MockWebServer
 * (real threads; `runBlocking` + Turbine, never `runTest`'s virtual clock —
 * the same stance as SseEventSourceTest, because OkHttp's SSE reader threads
 * and the session's ladder coroutines must actually run for the state
 * machine to move): the IDLE→CONNECTING→CONNECTED climb with the probe and
 * the SSE fixture decoding into the conflated status lane, the `transport`
 * notice's session-local scheme rebuild (asserted through the lane-open /
 * probe intent seams — an https dial cannot handshake against a plaintext
 * server, so the intent is the observable), SSE death falling to
 * RECONNECTING with the poll fallback keeping the status warm, the 401
 * terminal, detection dedupe/poll-merge/cap, and the stop contract (both
 * sockets closed, no coroutine leak — the session scope is a child of
 * runBlocking's job, so a leak would hang the run and fail the test).
 *
 * Determinism: the session's [SessionTimings] are shrunk to tens of
 * milliseconds instead of virtualizing time, and the server answers through
 * a route dispatcher whose unmatched routes "hang" ([SocketPolicy.NO_RESPONSE]
 * — a held-open silent stream, which is what an accepting-but-silent device
 * looks like to the session's no-timeout SSE engine).
 */
class DeviceSessionManagerTest {

    private companion object {
        const val DEVICE_ID = "dev-1"
        const val STATUS_SSE_PATH = "/api/events"
        const val DETECTION_SSE_PATH = "/api/detection/events/stream"
        const val DETECTION_POLL_PREFIX = "/api/detection/events"
    }

    private lateinit var server: MockWebServer
    private var rosterDir: File = Files.createTempDirectory("lenscast-session").toFile()
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore

    /** Path(-prefix) → answer; unmatched routes hang (held-open silent stream). */
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    /** The parent scopes this test created; cancelled in tearDown (leak mop-up). */
    private val parentScopes = mutableListOf<CoroutineScope>()

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
        roster = DeviceRosterStore(rosterDir, AppJson)
        credentials = CompanionCredentialStore(rosterDir, plaintextCipher())
        credentials.put(DEVICE_ID, StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-test"))
        roster.upsert(device(port = server.port))
    }

    @After
    fun tearDown() {
        parentScopes.forEach { it.cancel() }
        routes.clear()
        server.shutdown()
        rosterDir.deleteRecursively()
    }

    // ── Fixture helpers ──

    private fun plaintextCipher() = object : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun device(port: Int) = CompanionDevice(
        id = DEVICE_ID,
        name = "Desk phone",
        host = "127.0.0.1",
        port = port,
        useTls = false,
        auth = CompanionAuthKind.TOKEN,
        addedAtMs = 1_700_000_000_000L,
    )

    /**
     * NO_RESPONSE: the socket is accepted and then silence — exactly what an
     * SSE lane reads while a device holds the connection without emitting
     * (the session's derived engine has no read/call timeout, so this is a
     * stable "open, silent stream").
     */
    private fun hang(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

    private fun ok(json: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    private fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    /** A minimal-but-complete GET /api/status payload (the battery level drives the assertions). */
    private fun statusJson(battery: Int): String =
        "{\"streaming\":{\"isActive\":true,\"url\":\"http://x/stream\",\"webStreamingEnabled\":true," +
            "\"webStreamingActive\":true,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"http://x/audio\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"rtsp://x/live\"}," +
            "\"thermal\":\"NORMAL\",\"camera\":\"Ready\"," +
            "\"battery\":{\"level\":$battery,\"isCharging\":false,\"isPowerSaveMode\":false}," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    private fun statusFrame(battery: Int) = "event: status\ndata: ${statusJson(battery)}"

    private fun transportFrame(scheme: String) = "event: transport\ndata: {\"scheme\":\"$scheme\"}"

    private fun detectionFrame(id: String, timestampMs: Long) =
        "data: {\"id\":\"$id\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":$timestampMs}"

    /** A GET /api/detection/events page from raw event JSON objects. */
    private fun detectionPage(vararg eventJsonObjects: String): String =
        "{\"events\":[${eventJsonObjects.joinToString(",")}],\"total\":${eventJsonObjects.size}}"

    /**
     * One SSE response that emits the frames and then HOLDS the stream open:
     * the body promises more bytes than it sends (explicit Content-Length)
     * and KEEP_OPEN pins the socket, so the no-timeout SSE engine sits
     * reading forever — a stable CONNECTED for the test's lifetime.
     */
    private fun heldOpenSse(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { "$it\n\n" }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
            .setHeader("Content-Length", (body.length + 4096).toString())
            .setSocketPolicy(SocketPolicy.KEEP_OPEN)
    }

    /** A completing SSE response: the frames end, then the device's lifetime-close shape. */
    private fun completingSse(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { "$it\n\n" }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    /**
     * The manager, parented DIRECTLY on runBlocking's job: every session
     * coroutine is a child the join waits on, so a leaked coroutine hangs
     * the run and fails the test, and a crashed one cancels the run loudly.
     * A separate `SupervisorJob(parent)` must NOT be inserted here — it is
     * itself a child job that ignores its children's completion and never
     * ends until cancelled, which parks runBlocking's join forever even
     * after every session coroutine has finished.
     */
    private fun newManager(parent: Job): DeviceSessionManager {
        val scope = CoroutineScope(parent + Dispatchers.IO)
        parentScopes.add(scope)
        return DeviceSessionManager(roster, credentials, scope)
    }

    /** Ladder/poll timings shrunk to test scale (the [SessionTimings] determinism seam). */
    private fun fastTimings(statusSseRetryMs: Long = 40L): SessionTimings = SessionTimings(
        tickMs = 20L,
        statusSseRetryMs = statusSseRetryMs,
        detectionPollMs = 60L,
        detectionSseRetryMs = 120L,
        ladderBackoffMs = listOf(20L, 30L, 40L),
        ladderGiveUpAtCapTier = 3,
        // A wrong-scheme dial (TLS against the plaintext mock) must fail in
        // milliseconds, not the production 3 s connect budget the ladder
        // would otherwise pay per rung.
        dialConnectTimeoutMs = 250L,
    )

    private fun DeviceSession.fast(statusSseRetryMs: Long = 40L): DeviceSession = apply {
        timings = fastTimings(statusSseRetryMs)
    }

    // ── 1. The connect climb + status SSE decoding ──

    @Test
    fun `start climbs IDLE to CONNECTING to CONNECTED and the status SSE fixture decodes into the flow`() =
        runBlocking {
            route("/api/status") { ok(statusJson(87)) }
            route(STATUS_SSE_PATH) { heldOpenSse(statusFrame(55)) }
            route(DETECTION_SSE_PATH) { heldOpenSse() } // open, silent detection lane: no interference

            val manager = newManager(coroutineContext.job)
            val session = manager.session(DEVICE_ID).fast()

            session.status.test(30.seconds) {
                assertNull(awaitItem()) // before start: no snapshot yet
                session.connectionState.test(30.seconds) {
                    assertEquals(ConnectionState.IDLE, awaitItem())
                    session.start()
                    assertEquals(ConnectionState.CONNECTING, awaitItem())
                    assertEquals(ConnectionState.CONNECTED, awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
                // The probe's snapshot landed first, then the SSE push
                // superseded it in place — the conflated lane, exactly the
                // web's status signal.
                assertEquals(87, awaitItem()?.battery?.level)
                assertEquals(55, awaitItem()?.battery?.level)
                cancelAndIgnoreRemainingEvents()
            }
            session.stop()
        }

    // ── 2. The transport switch (lane URL-intent seam) ──

    @Test
    fun `transport notice rebuilds both lanes onto the announced scheme without mutating the roster`() =
        runBlocking {
            route("/api/status") { ok(statusJson(87)) }
            route(STATUS_SSE_PATH) {
                // Every dial carries the notice: under suite load a one-shot
                // fixture can be lost to a reader race (the retry loop then
                // re-dials into a notice-less hang forever — the DIAG-TRANSPORT
                // print caught exactly that), so the notice re-fires on each
                // re-dial until the https rebuild wins. The https re-dials
                // never reach this dispatcher at all — they fail in TLS.
                completingSse(statusFrame(55), transportFrame("https"))
            }
            route(DETECTION_SSE_PATH) { heldOpenSse() }

            val manager = newManager(coroutineContext.job)
            val session = manager.session(DEVICE_ID).fast()
            val openedUrls = Collections.synchronizedList(mutableListOf<Pair<LaneKind, String>>())
            session.laneOpenListener = { kind, url -> openedUrls.add(kind to url) }

            session.start()
            // 15s: the https intents land in milliseconds on a healthy JVM;
            // the budget is slack for suite-load scheduling, not a fix for a
            // saturated JVM (the old 60 s edition absorbed the per-method
            // MainApplication leaks — see test-resources/robolectric.properties).
            // A real regression still fails, just bounded.
            try {
                withTimeout(15.seconds) {
                    // BOTH rebuilds must land here — the detection lane's
                    // re-dial trails the status lane's, and under suite-load
                    // the gap has stretched past an immediate assert.
                    while (openedUrls.none { it.first == LaneKind.STATUS && it.second.startsWith("https://") } ||
                        openedUrls.none { it.first == LaneKind.DETECTION && it.second.startsWith("https://") }
                    ) {
                        delay(10)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // TEMP DIAGNOSTIC
                println(
                    "DIAG-TRANSPORT state=" + session.connectionState.value +
                        " status=" + session.status.value?.battery?.level +
                        " opened=" + openedUrls.toList(),
                )
                throw e
            }
            // The first dials were plain http on the exact SSE routes...
            assertTrue(
                openedUrls.any { it.first == LaneKind.STATUS && it.second.startsWith("http://") && it.second.endsWith(STATUS_SSE_PATH) },
            )
            assertTrue(
                openedUrls.any { it.first == LaneKind.DETECTION && it.second.startsWith("http://") && it.second.endsWith(DETECTION_SSE_PATH) },
            )
            // ...and the rebuild re-dialed BOTH lanes onto the announced scheme.
            assertTrue(openedUrls.any { it.first == LaneKind.STATUS && it.second.startsWith("https://") })
            assertTrue(openedUrls.any { it.first == LaneKind.DETECTION && it.second.startsWith("https://") })
            // The roster entry keeps its pairing-time snapshot — the override is session-local.
            assertEquals(false, roster.find(DEVICE_ID)?.useTls)
            session.stop()
        }

    @Test
    fun `the scheme override survives a restart and a dead announced scheme exhausts to OFFLINE`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) }
        route(STATUS_SSE_PATH) { completingSse(statusFrame(55), transportFrame("https")) }
        route(DETECTION_SSE_PATH) { heldOpenSse() }

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()
        val probeBases = Collections.synchronizedList(mutableListOf<String>())
        session.probeListener = { base -> probeBases.add(base) }

        session.start()
        // 60s: https-gated like the other waits in this file (see the
        // transport test's note) — milliseconds when healthy, unbounded under
        // full-suite worker contention.
        withTimeout(60.seconds) {
            while (probeBases.none { it.startsWith("https://") }) delay(10)
        }
        session.stop()

        // Restart dials the OVERRIDE (the device announced its live scheme),
        // not the stale roster snapshot — and since the plaintext test server
        // cannot complete a TLS handshake, the ladder exhausts to OFFLINE.
        probeBases.clear()
        session.start()
        // Same budget rationale as the transport test's wait above.
        withTimeout(15.seconds) {
            while (probeBases.none { it.startsWith("https://") }) delay(10)
        }
        assertEquals(ConnectionState.OFFLINE, session.connectionState.first { it == ConnectionState.OFFLINE })
        // Every restarted dial rode the override — not one fell back to the
        // roster's plaintext snapshot. (The TLS bytes still land on the
        // plaintext MockWebServer as malformed requests, so a requestCount
        // freeze would pin the wrong thing; the probe intent is the
        // observable here.)
        assertTrue(probeBases.isNotEmpty())
        assertTrue(probeBases.all { it.startsWith("https://") })
        session.stop()
    }

    // ── 3. SSE death → RECONNECTING, poll fallback warms the status ──

    @Test
    fun `status SSE death lands RECONNECTING while the poll fallback keeps the status warm`() = runBlocking {
        val statusDials = AtomicInteger()
        route("/api/status") {
            // First dial: the probe (87). Every later dial: a poll (91) —
            // the poll-fallback refresh the test waits for.
            if (statusDials.incrementAndGet() == 1) ok(statusJson(87)) else ok(statusJson(91))
        }
        route(STATUS_SSE_PATH) { completingSse(statusFrame(55)) } // lifetime-close shape: frames, then end
        route(DETECTION_SSE_PATH) { heldOpenSse() }

        val manager = newManager(coroutineContext.job)
        // statusSseRetryMs beyond the test's lifetime: no reopen attempt
        // fires, so the lane stays honestly down (no half-healthy stamp-up
        // window to race the assertions against).
        val session = manager.session(DEVICE_ID).fast(statusSseRetryMs = 30_000L)
        session.start()

        withTimeout(15.seconds) {
            while (session.connectionState.value != ConnectionState.RECONNECTING) delay(10)
            // The STATUS rung of the poll ladder fired while the SSE read
            // dead — the conflated lane moved to the polled snapshot.
            while (session.status.value?.battery?.level != 91) delay(10)
        }
        delay(300)
        assertEquals(ConnectionState.RECONNECTING, session.connectionState.value)
        assertEquals(91, session.status.value?.battery?.level)
        session.stop()
    }

    // ── 4. The 401 terminal ──

    @Test
    fun `a 401 probe lands AUTH_FAILED with no retries`() = runBlocking {
        route("/api/status") {
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Authentication required\"}")
        }
        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()

        session.connectionState.test(30.seconds) {
            assertEquals(ConnectionState.IDLE, awaitItem())
            session.start()
            assertEquals(ConnectionState.CONNECTING, awaitItem())
            assertEquals(ConnectionState.AUTH_FAILED, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // No retry: the server fails closed, so the session settles instead
        // of hammering the route.
        delay(300)
        assertEquals(1, server.requestCount)
        session.stop()
    }

    // ── 5. Detection SSE: dedupe + newest-first ──

    @Test
    fun `detection SSE frames dedupe by id and render newest-first`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) }
        route(DETECTION_SSE_PATH) {
            heldOpenSse(
                detectionFrame("e1", 1_000),
                detectionFrame("e2", 2_000),
                detectionFrame("e2", 2_000), // duplicate id + timestamp: one entry
                detectionFrame("e3", 3_000),
            )
        }
        route(DETECTION_POLL_PREFIX) { ok(detectionPage()) } // never polled while the SSE is healthy

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()
        session.start()

        withTimeout(15.seconds) {
            while (session.detectionEvents.value.size != 3) delay(10)
        }
        assertEquals(listOf("e3", "e2", "e1"), session.detectionEvents.value.map { it.id }) // NEWEST-FIRST
        session.stop()
    }

    // ── 6. Poll refresh merges without dupes; the 200 cap holds ──

    @Test
    fun `poll refresh merges without dupes and the 200 cap holds`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) }
        route(DETECTION_SSE_PATH) {
            completingSse(
                detectionFrame("e1", 1_000),
                detectionFrame("e2", 2_000),
                detectionFrame("e2", 2_000),
                detectionFrame("e3", 3_000),
            )
        }
        val serveBigPage = AtomicBoolean(false)
        route(DETECTION_POLL_PREFIX) {
            if (!serveBigPage.get()) {
                // The degraded lane's immediate first poll: a replayed backlog
                // page carrying an OLDER event plus a duplicate of an event
                // the SSE already delivered.
                ok(
                    detectionPage(
                        "{\"id\":\"e2\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":2000}",
                        "{\"id\":\"e0\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":500}",
                    ),
                )
            } else {
                // A later poll: 205 fresh (newer) events — the cap's feed.
                val bigPage = (0..204).map { i ->
                    "{\"id\":\"big-$i\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":${10_000 + i}}"
                }
                ok(detectionPage(*bigPage.toTypedArray()))
            }
        }

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()
        session.start()

        // SSE feed + first poll merged: four distinct events, newest-first,
        // the duplicate collapsed (the e2 poll copy did not duplicate the e2
        // the SSE delivered).
        withTimeout(15.seconds) {
            while (session.detectionEvents.value.size != 4) delay(10)
        }
        assertEquals(listOf("e3", "e2", "e1", "e0"), session.detectionEvents.value.map { it.id })

        // The big page: 205 incoming events merge into a feed capped at 200,
        // oldest (lowest-timestamp) falling off, newest at the top.
        serveBigPage.set(true)
        withTimeout(15.seconds) {
            while (session.detectionEvents.value.size != 200) delay(10)
        }
        val feed = session.detectionEvents.value
        assertEquals(200, feed.size)
        assertEquals("big-204", feed.first().id)
        assertFalse(feed.any { it.id == "e3" }) // the oldest entries fell off the cap
        session.stop()
    }

    // ── 6b. The wave-4 on-demand seams: manual poll + clear ──

    @Test
    fun `manualDetectionPoll merges the page without the session started and clearDetectionFeed wipes it`() =
        runBlocking {
            route(DETECTION_POLL_PREFIX) {
                ok(
                    detectionPage(
                        "{\"id\":\"e1\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":1000}",
                        "{\"id\":\"e2\",\"type\":\"sound\",\"source\":\"cam\",\"timestampMs\":2000}",
                    ),
                )
            }

            val manager = newManager(coroutineContext.job)
            // The session is never started: the manual poll runs purely on the
            // caller's context — exactly the events tab's refresh path.
            val session = manager.session(DEVICE_ID)
            assertTrue(session.detectionEvents.value.isEmpty())

            session.manualDetectionPoll()

            assertEquals(listOf("e2", "e1"), session.detectionEvents.value.map { it.id })

            session.clearDetectionFeed()
            assertTrue(session.detectionEvents.value.isEmpty())
        }

    @Test
    fun `manualDetectionPoll on a roster-less session no-ops without throwing`() = runBlocking {
        val session = DeviceSession("no-roster-dev").also {
            it.attach(roster, credentials, CoroutineScope(coroutineContext.job))
        }
        // "no-roster-dev" has no roster entry — requireClient() is null.
        session.manualDetectionPoll()
        assertTrue(session.detectionEvents.value.isEmpty())
        session.clearDetectionFeed() // idempotent on an empty buffer
        assertTrue(session.detectionEvents.value.isEmpty())
    }

    // ── 7. Stop: sockets closed, no leak, idempotent ──

    @Test(timeout = 60_000)
    fun `stop mid-stream closes both SSE lanes, leaks no coroutines, and is idempotent`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) }
        route(STATUS_SSE_PATH) { hang() } // held-open silent streams: sockets live until stop()
        route(DETECTION_SSE_PATH) { hang() }

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()
        session.start()

        withTimeout(15.seconds) {
            while (server.requestCount < 3) delay(10) // probe + status SSE + detection SSE all dialed
            while (session.connectionState.value != ConnectionState.CONNECTED) delay(10)
        }

        session.stop()
        session.stop() // idempotent: the second call must be a no-op, not a crash
        assertEquals(ConnectionState.IDLE, session.connectionState.value)

        // No lane re-dials after the teardown: the request count freezes.
        val requestsAfterStop = server.requestCount
        delay(300)
        assertEquals(requestsAfterStop, server.requestCount)
        // Implicit third assertion: runBlocking completing at all proves the
        // session scope (a child of runBlocking's job) finished unwinding —
        // a leaked lane coroutine would hang this test forever.
    }

    // ── 8. Stop/restart cycles ──

    @Test
    fun `stop before start is safe and a stopped session restarts cleanly`() = runBlocking {
        val statusDials = AtomicInteger()
        route("/api/status") { if (statusDials.incrementAndGet() == 1) ok(statusJson(87)) else ok(statusJson(92)) }
        route(STATUS_SSE_PATH) { heldOpenSse(statusFrame(55)) }
        route(DETECTION_SSE_PATH) { heldOpenSse() }

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID).fast()
        session.stop() // never started: a settled no-op, not a crash
        assertEquals(ConnectionState.IDLE, session.connectionState.value)

        session.start()
        assertEquals(ConnectionState.CONNECTED, session.connectionState.first { it == ConnectionState.CONNECTED })
        session.stop()
        assertEquals(ConnectionState.IDLE, session.connectionState.value)

        // The second run is a fresh link: a second probe dial, and the fresh
        // SSE push supersedes the fresh probe.
        session.start()
        assertEquals(ConnectionState.CONNECTED, session.connectionState.first { it == ConnectionState.CONNECTED })
        withTimeout(15.seconds) {
            while (server.requestCount < 6) delay(10) // run 1: probe + 2 SSE lanes; run 2: the same again
        }
        assertEquals(55, session.status.first { it != null && it.battery.level == 55 }?.battery?.level)
        session.stop()
    }

    // ── 9. The manager registry ──

    @Test
    fun `the manager caches one session per device and release stops then drops`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) }
        route(STATUS_SSE_PATH) { heldOpenSse() }
        route(DETECTION_SSE_PATH) { heldOpenSse() }

        val manager = newManager(coroutineContext.job)
        val session = manager.session(DEVICE_ID)
        assertSame("re-entry must ride the SAME live session", session, manager.session(DEVICE_ID))
        assertEquals(listOf(session), manager.active())

        session.start()
        assertEquals(ConnectionState.CONNECTED, session.connectionState.first { it == ConnectionState.CONNECTED })

        manager.release(DEVICE_ID)
        assertEquals("release stops the session", ConnectionState.IDLE, session.connectionState.value)
        assertTrue(manager.active().isEmpty())

        // The cache is really dropped: the next session() is a new, unstarted instance.
        val fresh = manager.session(DEVICE_ID)
        assertNotSame(session, fresh)
        assertEquals(ConnectionState.IDLE, fresh.connectionState.value)
        fresh.stop()
    }

    // ── 10. The roster-missing fail-fast ──

    @Test
    fun `a device missing from the roster fails fast to OFFLINE without dialing`() = runBlocking {
        route("/api/status") { ok(statusJson(87)) } // reachable — the ghost session must NOT dial it

        val manager = newManager(coroutineContext.job)
        val ghost = manager.session("ghost")
        ghost.timings = SessionTimings(tickMs = 20L, ladderBackoffMs = listOf(20L), ladderGiveUpAtCapTier = 1)

        ghost.connectionState.test(30.seconds) {
            assertEquals(ConnectionState.IDLE, awaitItem())
            ghost.start()
            assertEquals(ConnectionState.CONNECTING, awaitItem())
            assertEquals(ConnectionState.OFFLINE, awaitItem()) // fail fast: no probe possible
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, server.requestCount) // no network call, no ladder churn
        manager.release("ghost")
    }
}
