package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.RecordingSnapshot
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The remote capture backend's rules, one MockWebServer round trip per
 * verdict — real threads (`runBlocking`, the RemoteGallerySourceTest
 * convention; no virtual clock, and the poll coroutines must actually run):
 *
 * - interval: the status poll lands on subscription (Unknown → the wire
 *   tri-state), start/stop ride the wire routes with the draft's fields, and
 *   a running-then-stopped wire flips the tri-state on the beat;
 * - recording: the status poll's isScheduled/scheduledStartTimeMs arms the
 *   Scheduled state, a scheduled start POSTs the draft (startTimeMs
 *   included) on /api/recording/start, the control's intended stop stops a
 *   live state (the intended start rides the same start verb from a
 *   confirmed idle), and the schedule cancel rides its own cancel-schedule
 *   verb (schedule-only — a live recording's stop stays on
 *   /api/recording/stop);
 * - gates: a read-only pairing's writes fire no request at all;
 * - the roster-lost device settles idle and no-ops every mutation.
 *
 * Plain JUnit — the source touches no Android API; Robolectric would only
 * slow the suite down.
 */
class RemoteCaptureSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    /** The interval-status answer the dispatcher serves; tests flip it. */
    private val intervalStatusJson = AtomicReference("""{"isRunning":false,"completedCaptures":0}""")

    /** The recording-status answer the dispatcher serves; tests flip it. */
    private val recordingStatusJson = AtomicReference(
        """{"isRecording":false,"elapsedSeconds":0,"isScheduled":false,"scheduledStartTimeMs":null}""",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add("${request.method} ${request.path.orEmpty()}")
                request.body?.let { bodies.add(it.readUtf8()) }
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/api/capture/interval/status" -> ok(intervalStatusJson.get())
                    path == "/api/capture/interval/start" -> ok("""{"success":true}""")
                    path == "/api/capture/interval/stop" -> ok("""{"success":true}""")
                    path == "/api/recording/status" -> ok(recordingStatusJson.get())
                    path == "/api/recording/start" -> ok("""{"success":true}""")
                    path == "/api/recording/stop" -> ok("""{"success":true}""")
                    path == "/api/recording/cancel-schedule" -> ok("""{"success":true}""")
                    path == "/api/capture" -> ok("""{"success":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        filesDir = createTempDir("capture-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-capture",
            name = "Capture",
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
        requests.clear()
        bodies.clear()
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private fun plaintextCipher() = object : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun ok(json: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    private fun newScope(parent: Job): CoroutineScope =
        // IO lanes but parented to the caller's job, so a leak would hang the
        // runBlocking and fail the test (the console-test note, verbatim).
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    /** The shrunken beat: polls land in milliseconds, not the 5 s defaults. */
    private val timings = ConsoleTimings(recordingPollMs = 50, capturePollMs = 50)

    private fun source(
        parent: Job,
        api: LenscastApiClient? = LenscastApiClient(device) { credentials.get(device.id) },
        canCapturePhoto: Boolean = true,
        canControlRecording: Boolean = true,
        canControlInterval: Boolean = true,
    ) = RemoteCaptureSource(
        api = api,
        canCapturePhoto = canCapturePhoto,
        canControlRecording = canControlRecording,
        canControlInterval = canControlInterval,
        timings = timings,
        scope = newScope(parent),
    )

    /**
     * Waits for a wire request matching [predicate] — the write round trips
     * run on the source's scope, so a plain list read would race them (a
     * request list is not a flow; poll it on the test's thread).
     */
    private suspend fun awaitRequest(
        what: String,
        timeoutMs: Long = 5_000,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            requests.firstOrNull(predicate)?.let { return it }
            delay(20)
        }
        throw AssertionError("no $what request within ${timeoutMs}ms; saw: $requests")
    }

    /** [awaitRequest] over the recorded bodies. */
    private suspend fun awaitBody(
        what: String,
        timeoutMs: Long = 5_000,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            bodies.lastOrNull(predicate)?.let { return it }
            delay(20)
        }
        throw AssertionError("no $what body within ${timeoutMs}ms; saw: $bodies")
    }

    /**
     * Waits for the recording snapshot to confirm into a state matching
     * [predicate] and returns it — the atomic Confirmed read (never the
     * Unconfirmed placeholder) the screen's control answers.
     */
    private suspend fun awaitRecordingState(
        src: RemoteCaptureSource,
        what: String,
        predicate: (RecordingState) -> Boolean,
    ): RecordingState {
        val snapshot = withTimeout(5_000) {
            src.recordingSnapshot.first {
                it is RecordingSnapshot.Confirmed && predicate(it.state)
            }
        }
        return (snapshot as RecordingSnapshot.Confirmed).state
    }

    // ── interval ──

    @Test
    fun `the interval status poll lands on subscription and start and stop ride the wire`() =
        runBlocking {
            intervalStatusJson.set("""{"isRunning":true,"completedCaptures":7}""")
            val parent = Job()
            val src = source(parent)
            try {
                val running = withTimeout(5_000) {
                    src.intervalState.first { it is IntervalCaptureState.Running }
                }
                assertEquals(7, (running as IntervalCaptureState.Running).snapshot.completedCaptures)

                // Start carries the draft's fields on the wire route.
                src.startIntervalCapture(
                    IntervalCaptureConfig(intervalSeconds = 30, totalCaptures = 50, flashMode = FlashMode.AUTO),
                )
                awaitRequest("interval start") { it.startsWith("POST /api/capture/interval/start") }
                val startBody = awaitBody("interval start") { it.contains("intervalSeconds") }
                assertTrue(startBody.contains("\"intervalSeconds\":30"))
                assertTrue(startBody.contains("\"totalCaptures\":50"))
                assertTrue(startBody.contains("\"flashMode\":\"AUTO\""))

                // Stop rides its route; the post-write re-fetch reads the
                // stopped truth and the tri-state follows.
                intervalStatusJson.set("""{"isRunning":false,"completedCaptures":50}""")
                src.stopIntervalCapture()
                awaitRequest("interval stop") { it.startsWith("POST /api/capture/interval/stop") }
                val stopped = withTimeout(5_000) {
                    src.intervalState.first { it is IntervalCaptureState.Stopped }
                }
                assertTrue(stopped is IntervalCaptureState.Stopped)
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `a running-then-stopped wire flips the tri-state on the next beat`() = runBlocking {
        // Begin running: the subscription's first read lands the Running
        // snapshot.
        intervalStatusJson.set("""{"isRunning":true,"completedCaptures":1}""")
        val parent = Job()
        val src = source(parent)
        try {
            val running = withTimeout(5_000) { src.intervalState.first { it is IntervalCaptureState.Running } }
            assertTrue(running is IntervalCaptureState.Running)
            // Polling alone carries the transition: the fixture flips to
            // stopped (no stopIntervalCapture call) and the next beat's read
            // lands Stopped.
            intervalStatusJson.set("""{"isRunning":false,"completedCaptures":1}""")
            val stopped = withTimeout(5_000) { src.intervalState.first { it is IntervalCaptureState.Stopped } }
            assertTrue(stopped is IntervalCaptureState.Stopped)
        } finally {
            parent.cancel()
        }
    }

    // ── recording (manual + scheduled wire path) ──

    @Test
    fun `the wire's armed schedule lands as the Scheduled state on the poll`() = runBlocking {
        recordingStatusJson.set(
            """{"isRecording":false,"elapsedSeconds":0,"isScheduled":true,"scheduledStartTimeMs":1788825600000}""",
        )
        val parent = Job()
        val src = source(parent)
        try {
            val scheduled = awaitRecordingState(src, "Scheduled") { it is RecordingState.Scheduled }
            assertEquals(1_788_825_600_000L, (scheduled as RecordingState.Scheduled).startAtMs)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a scheduled start POSTs the whole draft including the future start time`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            awaitRecordingState(src, "idle") { it == RecordingState.Idle }
            // A day out — safely future whatever instant the suite runs at,
            // so the POST genuinely exercises the future-start (Scheduled)
            // case a stale hardcoded stamp would silently stop covering.
            val startAt = System.currentTimeMillis() + 86_400_000L
            src.startScheduledRecording(
                RecordingConfig(
                    durationSeconds = 120,
                    repeatIntervalSeconds = 60,
                    quality = RecordingQuality.LOW,
                    includeAudio = false,
                    startTimeMs = startAt,
                ),
            )
            awaitRequest("recording start") { it.startsWith("POST /api/recording/start") }
            val body = awaitBody("recording start") { it.contains("startTimeMs") }
            assertTrue(body.contains("\"durationSeconds\":120"))
            assertTrue(body.contains("\"repeatIntervalSeconds\":60"))
            assertTrue(body.contains("\"quality\":\"LOW\""))
            assertTrue(body.contains("\"includeAudio\":false"))
            assertTrue(body.contains("\"startTimeMs\":$startAt"))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the stop intent stops a live recording and the start intent starts a confirmed idle one`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            recordingStatusJson.set("""{"isRecording":true,"elapsedSeconds":9}""")
            awaitRecordingState(src, "Recording") { it is RecordingState.Recording }
            // The control's intended stop, passed from its confirmed
            // Recording snapshot.
            src.stopRecording()
            awaitRequest("recording stop") { it.startsWith("POST /api/recording/stop") }

            recordingStatusJson.set("""{"isRecording":false,"elapsedSeconds":0}""")
            awaitRecordingState(src, "idle") { it == RecordingState.Idle }
            // The intended start from the confirmed Idle snapshot — a
            // confirmed idle device starts normally.
            src.startRecording(RecordingConfig(durationSeconds = 10))
            awaitRequest("recording start") { it.startsWith("POST /api/recording/start") }
            assertTrue(awaitBody("recording start") { it.contains("durationSeconds") }
                .contains("\"durationSeconds\":10"))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the schedule cancel rides its own cancel-schedule verb`() = runBlocking {
        recordingStatusJson.set(
            """{"isRecording":false,"elapsedSeconds":0,"isScheduled":true,"scheduledStartTimeMs":1788825600000}""",
        )
        val parent = Job()
        val src = source(parent)
        try {
            awaitRecordingState(src, "Scheduled") { it is RecordingState.Scheduled }
            src.cancelScheduledRecording()
            // The schedule-only verb: cancelling a pending start must NOT
            // touch a live recording, so it cannot ride /api/recording/stop.
            val cancel = awaitRequest("schedule cancel") {
                it.startsWith("POST /api/recording/cancel-schedule")
            }
            assertTrue(cancel.startsWith("POST /api/recording/cancel-schedule"))
            // The cancel succeeded (the dispatcher answers success): the
            // wire truth flips to idle and the post-command refresh lands
            // the source out of Scheduled. (An assertEquals tail also keeps
            // the runBlocking lambda's inferred type Unit — a bare
            // awaitRecordingState would make the test method non-void and
            // JUnit would reject the class.)
            recordingStatusJson.set("""{"isRecording":false,"elapsedSeconds":0,"isScheduled":false,"scheduledStartTimeMs":null}""")
            assertEquals(RecordingState.Idle, awaitRecordingState(src, "idle after cancel") { it == RecordingState.Idle })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a live recording publishes its elapsed milliseconds`() = runBlocking {
        recordingStatusJson.set("""{"isRecording":true,"elapsedSeconds":15}""")
        val parent = Job()
        val src = source(parent)
        try {
            assertEquals(
                15_000L,
                withTimeout(5_000) { src.recordingElapsedMs.first { it > 0L } },
            )
        } finally {
            parent.cancel()
        }
    }

    // ── photo ──

    @Test
    fun `capturePhoto rides the one-shot route`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            src.capturePhoto()
            val photo = awaitRequest("capture photo") {
                it.startsWith("POST ") && !it.contains("/api/capture/")
            }
            // The route is exactly /api/capture, not an interval subpath.
            assertEquals("POST /api/capture", photo)
        } finally {
            parent.cancel()
        }
    }

    // ── gates ──

    @Test
    fun `a read-only pairing's writes fire no request`() = runBlocking {
        val parent = Job()
        val src = source(
            parent,
            canCapturePhoto = false,
            canControlRecording = false,
            canControlInterval = false,
        )
        try {
            // The reads still land (the truths render read-only)…
            withTimeout(5_000) { src.intervalState.first { it is IntervalCaptureState.Stopped } }
            awaitRecordingState(src, "idle") { it == RecordingState.Idle }
            // …then every gated write is a source-side no-op: no wire write
            // appears (the beats keep GETting, so verdict by verb, not count).
            src.capturePhoto()
            src.startIntervalCapture(IntervalCaptureConfig())
            src.stopIntervalCapture()
            src.startRecording(RecordingConfig())
            src.stopRecording()
            src.startScheduledRecording(RecordingConfig())
            src.cancelScheduledRecording()
            delay(250)
            assertTrue(requests.none { it.startsWith("POST") || it.startsWith("PUT") || it.startsWith("DELETE") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the timelapse surface is wire-degraded hidden and inert`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            awaitRecordingState(src, "idle") { it == RecordingState.Idle }
            assertFalse(src.canTimelapse)
            assertNull(src.includeAudioDefault.first())
            assertEquals(
                com.raulshma.lenscast.capture.model.TimelapseState.Idle,
                src.timelapseState.first(),
            )
            src.assembleTimelapse()
            src.setAutoTimelapseEnabled(true)
            delay(150)
            assertTrue(requests.none { it.startsWith("POST") || it.startsWith("PUT") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a roster-lost source settles idle and no-ops every mutation`() = runBlocking {
        val parent = Job()
        val src = source(parent, api = null)
        try {
            delay(150) // the beats have nowhere to dial
            assertTrue(src.intervalState.first() is IntervalCaptureState.Unknown)
            // No confirmed read can ever land: the snapshot settles on the
            // Unconfirmed placeholder (not a misleading Idle) — the control
            // stays disabled and its intended start/stop no-ops.
            assertEquals(RecordingSnapshot.Unconfirmed, src.recordingSnapshot.first())
            src.capturePhoto()
            src.startIntervalCapture(IntervalCaptureConfig())
            src.startRecording(RecordingConfig())
            src.stopRecording()
            delay(150)
            assertTrue(requests.isEmpty())
        } finally {
            parent.cancel()
        }
    }
}
