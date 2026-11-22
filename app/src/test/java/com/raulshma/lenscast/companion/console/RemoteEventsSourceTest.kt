package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.session.SessionTimings
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The remote event-log backend's rules, against a real MockWebServer (real
 * threads; `runBlocking`, never `runTest`'s virtual clock — the
 * DeviceSessionManagerTest convention, since the session's poll seam and
 * buffer coroutines must actually run):
 *
 * - the DEEP-LINK contract: an un-started session still feeds the source —
 *   the construction poll merges the device's newest page and the mapped
 *   [com.raulshma.lenscast.capture.DetectionEvent]s land NEWEST-FIRST even
 *   though the wire page arrived ascending;
 * - refresh re-pulls the page through the buffer's dedupe (no forks, no
 *   duplicates);
 * - clear wipes the feed only after the device confirms the DELETE — a 500
 *   leaves it standing — and a viewer pairing ([canClear] = false) sends no
 *   DELETE at all;
 * - the LIVE lane: a started session's detection SSE flows through the same
 *   mapped feed, interleaved with a manual poll without duplicating.
 *
 * Plain JUnit — the source touches no Android API.
 */
class RemoteEventsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    /** Path(-prefix) → answer; unmatched routes hang (held-open silent stream). */
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    @Volatile private var clearResponse: MockResponse = ok("""{"success":true}""")

    @Before
    fun setUp() {
        clearResponse = ok("""{"success":true}""")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add("${request.method} ${request.path.orEmpty()}")
                val path = request.path.orEmpty()
                val handler = routes[path]
                    ?: routes.entries.filter { path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }
                        ?.value
                return handler?.invoke(request) ?: hang()
            }
        }
        server.start()
        filesDir = createTempDir("events-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-events",
            name = "Events",
            host = "127.0.0.1",
            port = server.port,
            auth = CompanionAuthKind.TOKEN,
            role = "admin",
            addedAtMs = 0L,
        ).also { roster.upsert(it) }
        credentials.put(device.id, StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
    }

    @After
    fun tearDown() {
        parentScopes.forEach { it.cancel() }
        routes.clear()
        requests.clear()
        // A parked long-lived lane can outlast the shutdown patience after
        // the assertions have landed (the console-test precedent).
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private fun plaintextCipher() = object : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun ok(json: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    private fun hang(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

    private fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    private fun eventJson(id: String, timestampMs: Long, snapshot: String? = null) =
        """{"id":"$id","type":"motion","source":"cam","timestampMs":$timestampMs,""" +
            """"snapshotJpegBase64":${snapshot?.let { "\"$it\"" } ?: "null"},""" +
            """"dispatchedActions":["siren"],"zones":["left"],"labels":["person"],""" +
            """"clipMediaId":null,"clipFileName":null,"url":"#/events"}"""

    /** A GET page; the wire arrives ascending here — the seam must re-sort. */
    private fun detectionPage(vararg eventJsonObjects: String): String =
        "{\"events\":[${eventJsonObjects.joinToString(",")}],\"total\":${eventJsonObjects.size}}"

    private fun detectionFrame(id: String, timestampMs: Long) =
        "data: ${eventJson(id, timestampMs)}"

    private fun routePollPage(json: () -> String) {
        route("/api/detection/events") { _ -> ok(json()) }
    }

    private fun attachedSession(parent: Job): DeviceSession {
        val parentScope = CoroutineScope(parent + Dispatchers.IO)
        parentScopes.add(parentScope)
        val session = DeviceSession(device.id)
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

    private fun newScope(parent: Job): CoroutineScope =
        // Parented to the caller's job, so a leak would hang the runBlocking
        // and fail the test (the console-test note, verbatim).
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    private fun source(parent: Job, session: DeviceSession, canClear: Boolean = true) =
        RemoteEventsSource(
            session = session,
            api = LenscastApiClient(device) { credentials.get(device.id) },
            canClear = canClear,
            scope = newScope(parent),
        )

    @Test
    fun `a deep-linked visit polls the newest page and lands it newest-first`() = runBlocking {
        val parent = Job()
        val session = attachedSession(parent) // deliberately NOT started: no lanes
        routePollPage { detectionPage(eventJson("e1", 100L), eventJson("e2", 200L)) }
        val src = source(parent, session)
        try {
            val events = withTimeout(5.seconds) { src.events.first { it.size == 2 } }
            assertEquals(listOf("e2", "e1"), events.map { it.id })
            assertEquals("motion", events.first().type)
            assertEquals("cam", events.first().source)
            assertEquals(listOf("siren"), events.first().dispatchedActions)
            assertEquals(listOf("left"), events.first().zones)
            assertEquals(listOf("person"), events.first().labels)
            assertTrue(requests.any { it.startsWith("GET /api/detection/events") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `refresh re-pulls the page through the buffer's dedupe`() = runBlocking {
        val parent = Job()
        val session = attachedSession(parent)
        routePollPage { detectionPage(eventJson("e1", 100L)) }
        val src = source(parent, session)
        try {
            withTimeout(5.seconds) { src.events.first { it.size == 1 } }
            // The device logged a new event; the pull must surface it without
            // duplicating the one already held.
            routePollPage { detectionPage(eventJson("e1", 100L), eventJson("e2", 200L)) }
            src.refresh()
            val events = withTimeout(5.seconds) { src.events.first { it.size == 2 } }
            assertEquals(listOf("e2", "e1"), events.map { it.id })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `clear wipes the feed only after the device confirms the DELETE`() = runBlocking {
        val parent = Job()
        val session = attachedSession(parent)
        // One route serves both verbs: GET answers the current poll page,
        // DELETE answers the swappable clear verdict.
        val pageJson = java.util.concurrent.atomic.AtomicReference(detectionPage(eventJson("e1", 100L)))
        route("/api/detection/events") { request ->
            if (request.method == "DELETE") clearResponse else ok(pageJson.get())
        }
        val src = source(parent, session)
        try {
            withTimeout(5.seconds) { src.events.first { it.size == 1 } }
            src.clear()
            withTimeout(5.seconds) { src.events.first { it.isEmpty() } }
            assertTrue(requests.any { it.startsWith("DELETE /api/detection/events") })

            // A device refusal leaves the feed standing (the UI never lies
            // about a wipe that failed): the pull brings the event back,
            // the 500 clear must not remove it.
            clearResponse = MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
            src.refresh()
            withTimeout(5.seconds) { src.events.first { it.size == 1 } }
            src.clear()
            delay(250)
            assertEquals(1, src.events.first().size)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a viewer pairing cannot clear and the source sends no DELETE`() = runBlocking {
        val parent = Job()
        val session = attachedSession(parent)
        routePollPage { detectionPage(eventJson("e1", 100L)) }
        val src = source(parent, session, canClear = false)
        try {
            assertTrue(!src.canClear)
            withTimeout(5.seconds) { src.events.first { it.size == 1 } }
            src.clear()
            delay(250)
            assertEquals(1, src.events.first().size)
            assertTrue(requests.none { it.startsWith("DELETE") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the live SSE lane flows through the same mapped feed beside a manual poll`() = runBlocking {
        val parent = Job()
        val session = attachedSession(parent)
        route("/api/status") { ok(statusJson()) }
        route("/api/events") {
            heldOpenSse("event: status\ndata: ${statusJson()}")
        }
        route("/api/detection/events/stream") {
            heldOpenSse(detectionFrame("e1", 100L), detectionFrame("e2", 200L))
        }
        routePollPage { detectionPage(eventJson("e3", 300L)) }
        val src = source(parent, session)
        session.start()
        try {
            // The SSE frames land through the lane; the construction poll's
            // e3 merges beside them without duplicating.
            val events = withTimeout(15.seconds) { src.events.first { it.size == 3 } }
            assertEquals(listOf("e3", "e2", "e1"), events.map { it.id })
            // And a manual poll on the live session stays deduped.
            src.refresh()
            delay(250)
            assertEquals(3, src.events.first().size)
        } finally {
            session.stop()
            parent.cancel()
        }
    }

    private fun statusJson(): String =
        "{\"streaming\":{\"isActive\":true,\"url\":\"http://x/stream\",\"webStreamingEnabled\":true," +
            "\"webStreamingActive\":true,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"http://x/audio\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"rtsp://x/live\"}," +
            "\"thermal\":\"NORMAL\",\"camera\":\"Ready\"," +
            "\"battery\":{\"level\":50,\"isCharging\":false,\"isPowerSaveMode\":false}," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    /**
     * One SSE response that emits the frames and then HOLDS the stream open
     * (the session test's shape): the body promises more bytes than it sends
     * and KEEP_OPEN pins the socket — a stable CONNECTED for the test's
     * lifetime.
     */
    private fun heldOpenSse(vararg frames: String): MockResponse {
        val body = frames.joinToString("") { "$it\n\n" }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
            .setHeader("Content-Length", (body.length + 4096).toString())
            .setSocketPolicy(SocketPolicy.KEEP_OPEN)
    }
}
