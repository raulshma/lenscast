package com.raulshma.lenscast.companion.ui.tabs.media

import app.cash.turbine.test
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
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
 * The media tab's ViewModel rules, one MockWebServer round trip per verdict:
 * the four-init-read parallelism lands one Ready (gallery page 1 + recording
 * status + session list + interval status, per-path routed so the concurrent
 * requests' FIFO answers stay deterministic), gallery paging accumulates and
 * the type filter re-queries with the `type` param, delete/batch-delete map
 * 200 → removed + `Deleted(n)` / 401 → `AuthRequired` / 403 → `Forbidden`
 * (nothing removed), recordings and interval capture start/stop re-poll
 * their status, and a failed section names itself in `failedSections` until
 * a retry lands.
 *
 * Real threads throughout: `runBlocking` with the VM scoped to the test's
 * job (the DevicesViewModelTest pattern — no virtual time, no sleeps; every
 * verdict is awaited off the state flow or the effects channel, with a
 * bounded `withTimeout`).
 */
class MediaViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var device: CompanionDevice

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        device = CompanionDevice(
            id = "dev-1",
            name = "Garage",
            host = "127.0.0.1",
            port = server.port,
            useTls = false,
            auth = CompanionAuthKind.TOKEN,
            addedAtMs = 0L,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Fixture JSON (inline — the wire shapes the DTOs decode) ──

    private fun galleryItem(
        id: String,
        type: String = "PHOTO",
        name: String = "$id.jpg",
    ) = "{\"id\":\"$id\",\"type\":\"$type\",\"fileName\":\"$name\",\"timestamp\":1700000000000," +
        "\"fileSizeBytes\":1024,\"durationMs\":0,\"favorite\":false,\"thumbnailUrl\":\"/t/$id\"," +
        "\"url\":\"/api/media/$id\",\"downloadUrl\":\"/api/media/$id?download=1\"}"

    private fun galleryJson(items: List<String>, total: Int, page: Int, hasMore: Boolean) =
        "{\"items\":[${items.joinToString(",")}],\"total\":$total,\"page\":$page,\"pageSize\":30,\"hasMore\":$hasMore}"

    private fun recordingJson(isRecording: Boolean, elapsedSeconds: Int = 0) =
        "{\"isRecording\":$isRecording,\"elapsedSeconds\":$elapsedSeconds}"

    private fun sessionsJson(vararg entries: String) =
        "{\"sessions\":[${entries.joinToString(",")}]}"

    private fun sessionJson(id: String, startMs: Long, endMs: Long, trigger: String) =
        "{\"id\":\"$id\",\"startMs\":$startMs,\"endMs\":$endMs,\"trigger\":\"$trigger\"}"

    private fun intervalJson(isRunning: Boolean, completed: Int) =
        "{\"isRunning\":$isRunning,\"completedCaptures\":$completed}"

    private val commandJson = "{\"success\":true}"

    // ── Deterministic per-path dispatch (the four init reads race FIFO) ──

    private fun routing(routes: (RecordedRequest) -> MockResponse?) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            routes(request) ?: MockResponse().setResponseCode(404).setBody("{}")
    }

    /** The standard four-section answers; the caller overrides slices. */
    private fun defaultRoutes(
        gallery: (pagePath: String) -> MockResponse,
        recordingStatus: () -> MockResponse = { ok(recordingJson(isRecording = false)) },
        sessions: () -> MockResponse = { ok(sessionsJson()) },
        intervalStatus: () -> MockResponse = { ok(intervalJson(isRunning = false, completed = 0)) },
    ) = routing { request ->
        val path = request.path.orEmpty()
        when {
            path.startsWith("/api/gallery") -> gallery(path)
            path.startsWith("/api/recording/status") -> recordingStatus()
            path.startsWith("/api/recording/start") -> ok(commandJson)
            path.startsWith("/api/recording/stop") -> ok(commandJson)
            path.startsWith("/api/recordings/sessions") -> sessions()
            path.startsWith("/api/capture/interval/status") -> intervalStatus()
            path.startsWith("/api/capture/interval/start") -> ok(commandJson)
            path.startsWith("/api/capture/interval/stop") -> ok(commandJson)
            request.method == "DELETE" -> ok(commandJson)
            path.startsWith("/api/media/batch-delete") -> ok(commandJson)
            else -> null
        }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun error(code: Int) = MockResponse().setResponseCode(code).setBody("{\"error\":\"x\"}")

    /** Drains the recorded requests that have already arrived. */
    private fun recordedPaths(): List<String> {
        val expected = server.requestCount
        return (0 until expected).mapNotNull { server.takeRequest(5, TimeUnit.SECONDS)?.path }
    }

    // ── The VM under test ──

    /**
     * The VM under test — the scope attaches to the CALLING runBlocking's job
     * (suspend, so `coroutineContext` is the test's event loop, never a
     * nested runBlocking's) and the init burst keeps runBlocking parked until
     * every verdict lands.
     */
    private suspend fun viewModel(canManage: Boolean = true): MediaViewModel = MediaViewModel(
        api = LenscastApiClient(device) { null },
        canManage = canManage,
        scope = CoroutineScope(coroutineContext),
    )

    /** Parks on the state flow until a Ready state satisfies [predicate]. */
    private suspend fun awaitReady(
        vm: MediaViewModel,
        predicate: (MediaUiState.Ready) -> Boolean,
    ): MediaUiState.Ready = withTimeout(10_000) {
        var hit: MediaUiState.Ready? = null
        vm.state.first { current ->
            val ready = current as? MediaUiState.Ready ?: return@first false
            if (predicate(ready)) {
                hit = ready
                true
            } else {
                false
            }
        }
        requireNotNull(hit) { "state flow closed without the expected Ready state" }
    }

    // ── Init ──

    @Test
    fun `init loads gallery, recording status, sessions and interval in parallel`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { ok(galleryJson(listOf(galleryItem("a1"), galleryItem("a2", type = "VIDEO", name = "b.mp4")), total = 2, page = 1, hasMore = false)) },
            recordingStatus = { ok(recordingJson(isRecording = true, elapsedSeconds = 42)) },
            sessions = {
                ok(
                    // Unordered on the wire: the tab sorts latest first.
                    sessionsJson(
                        sessionJson("s1", startMs = 1_000L, endMs = 2_000L, trigger = "manual"),
                        sessionJson("s2", startMs = 5_000L, endMs = 6_000L, trigger = "motion"),
                    ),
                )
            },
            intervalStatus = { ok(intervalJson(isRunning = true, completed = 5)) },
        )
        val vm = viewModel()

        val ready = awaitReady(vm) { it.items.size == 2 && it.sessions.size == 2 && it.interval.isRunning && it.recording.isRecording }
        assertEquals(listOf("a1", "a2"), ready.items.map { it.id })
        assertEquals(2, ready.total)
        assertTrue(!ready.hasMore)
        assertEquals(42, ready.recording.elapsedSeconds)
        assertEquals(listOf("s2", "s1"), ready.sessions.map { it.id })
        assertEquals(5, ready.interval.completedCaptures)
        assertTrue(ready.canManage)

        val paths = recordedPaths()
        setOf(
            // The wire page index is 0-based: the first page rides with no
            // page param at all (the web client's exact contract) — page=1
            // is the SECOND page, which the real server answers with an
            // empty items array under total=2.
            "/api/gallery?pageSize=30",
            "/api/recording/status",
            "/api/recordings/sessions",
            "/api/capture/interval/status",
        ).forEach { expected -> assertTrue("missing $expected in $paths", expected in paths) }
    }

    @Test
    fun `viewer role pairing cannot manage but still loads`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { ok(galleryJson(listOf(galleryItem("a1")), total = 1, page = 1, hasMore = false)) },
        )
        val vm = viewModel(canManage = false)
        awaitReady(vm) { it.items.size == 1 }
        val ready = awaitReady(vm) { !it.canManage }
        assertTrue(ready.canManage == false)
    }

    // ── Gallery paging ──

    @Test
    fun `gallery paging accumulates pages`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { path ->
                if (path.contains("page=")) {
                    ok(galleryJson(listOf(galleryItem("a4"), galleryItem("a5")), total = 5, page = 1, hasMore = false))
                } else {
                    // The first page rides with no page param (0-based wire).
                    ok(galleryJson(listOf(galleryItem("a1"), galleryItem("a2"), galleryItem("a3")), total = 5, page = 0, hasMore = true))
                }
            },
        )
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 3 && it.hasMore }

        vm.loadMore()
        val ready = awaitReady(vm) { it.items.size == 5 }
        assertTrue(!ready.hasMore)
        assertEquals(5, ready.total)
        // The second page rode 0-indexed page=1.
        val galleryPaths = recordedPaths().filter { it.startsWith("/api/gallery") }
        assertTrue(galleryPaths.any { it.contains("page=1") })
    }

    @Test
    fun `loadMore is re-entrancy guarded`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { path ->
                if (path.contains("page=")) {
                    ok(galleryJson(listOf(galleryItem("a2")), total = 2, page = 1, hasMore = false))
                } else {
                    ok(galleryJson(listOf(galleryItem("a1")), total = 2, page = 0, hasMore = true))
                }
            },
        )
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 1 && it.hasMore }

        vm.loadMore()
        vm.loadMore() // while the first load-more fetch is in flight — must not double-fetch
        awaitReady(vm) { it.items.size == 2 && !it.hasMore }
        val pageTwos = recordedPaths().count { it.startsWith("/api/gallery") && it.contains("page=1") }
        assertEquals(1, pageTwos)
    }

    @Test
    fun `type filter re-queries with the type param and restarts paging`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { path ->
                if (path.contains("type=PHOTO")) {
                    ok(galleryJson(listOf(galleryItem("p1")), total = 1, page = 1, hasMore = false))
                } else {
                    ok(galleryJson(listOf(galleryItem("a1"), galleryItem("v1", type = "VIDEO", name = "v.mp4")), total = 2, page = 1, hasMore = false))
                }
            },
        )
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 && it.filter == MediaGalleryFilter.ALL }

        vm.setFilter(MediaGalleryFilter.PHOTOS)
        val ready = awaitReady(vm) { it.filter == MediaGalleryFilter.PHOTOS && it.items.map { item -> item.id } == listOf("p1") }
        assertEquals(MediaGalleryFilter.PHOTOS, ready.filter)
        val photoPaths = recordedPaths().filter { it.startsWith("/api/gallery") }
        // The restarted first page rides 0-indexed: type + pageSize, no page param.
        assertTrue("/api/gallery?type=PHOTO&pageSize=30" in photoPaths)
    }

    // ── Viewer state ──

    @Test
    fun `open and close viewer drive the viewer target`() = runBlocking<Unit> {
        server.dispatcher = defaultRoutes(
            gallery = { ok(galleryJson(listOf(galleryItem("a1"), galleryItem("v1", type = "VIDEO", name = "v.mp4")), total = 2, page = 1, hasMore = false)) },
        )
        val vm = viewModel()
        val ready = awaitReady(vm) { it.items.size == 2 }
        assertNull(ready.viewer)

        vm.openViewer(ready.items[1])
        val open = awaitReady(vm) { it.viewer != null }
        assertEquals("v1", open.viewer?.id)
        assertTrue(open.viewer?.isVideo == true)

        vm.closeViewer()
        awaitReady(vm) { it.viewer == null }
    }

    // ── Delete verdicts ──

    private fun deleteRoutes(
        deleteStatus: Int,
        batchStatus: Int = 200,
        gallery: (path: String) -> MockResponse = { ok(galleryJson(listOf(galleryItem("a1"), galleryItem("a2")), total = 2, page = 1, hasMore = false)) },
    ) = routing { request ->
        val path = request.path.orEmpty()
        when {
            path.startsWith("/api/gallery") -> gallery(path)
            path.startsWith("/api/recording/status") -> ok(recordingJson(false))
            path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
            path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
            request.method == "DELETE" -> error(deleteStatus)
            path.startsWith("/api/media/batch-delete") -> error(batchStatus)
            else -> null
        }
    }

    @Test
    fun `single delete 200 removes the item and emits Deleted`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 200)
        val vm = viewModel()
        val ready = awaitReady(vm) { it.items.size == 2 }

        vm.requestDelete("a1")
        val armed = awaitReady(vm) { it.pendingDelete is MediaPendingDelete.Single }
        assertEquals("a1", (armed.pendingDelete as MediaPendingDelete.Single).id)

        vm.confirmPendingDelete()
        val after = awaitReady(vm) { it.items.size == 1 && it.pendingDelete == null }
        assertEquals(listOf("a2"), after.items.map { it.id })
        vm.effects.test {
            assertEquals(MediaEffect.Deleted(1), awaitItem())
        }
    }

    @Test
    fun `single delete 401 emits AuthRequired and keeps the items`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 401)
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 }

        vm.requestDelete("a1")
        awaitReady(vm) { it.pendingDelete != null }
        vm.confirmPendingDelete()
        val after = awaitReady(vm) { it.pendingDelete == null }
        assertEquals(2, after.items.size)
        vm.effects.test {
            assertEquals(MediaEffect.AuthRequired, awaitItem())
        }
    }

    @Test
    fun `single delete 403 emits Forbidden and keeps the items`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 403)
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 }

        vm.requestDelete("a1")
        awaitReady(vm) { it.pendingDelete != null }
        vm.confirmPendingDelete()
        val after = awaitReady(vm) { it.pendingDelete == null }
        assertEquals(2, after.items.size)
        vm.effects.test {
            assertEquals(MediaEffect.Forbidden, awaitItem())
        }
    }

    @Test
    fun `batch delete 200 removes the selection and emits Deleted`() = runBlocking<Unit> {
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(listOf(galleryItem("a1"), galleryItem("a2"), galleryItem("a3")), total = 3, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                path.startsWith("/api/media/batch-delete") -> ok("{\"success\":true,\"deleted\":[\"a1\",\"a3\"]}")
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 3 }

        vm.toggleSelect("a1")
        vm.toggleSelect("a3")
        vm.requestBatchDelete()
        val armed = awaitReady(vm) { it.pendingDelete is MediaPendingDelete.Batch }
        assertEquals(listOf("a1", "a3"), (armed.pendingDelete as MediaPendingDelete.Batch).ids)

        vm.confirmPendingDelete()
        val after = awaitReady(vm) { it.items.size == 1 && it.selection.isEmpty() }
        assertEquals(listOf("a2"), after.items.map { it.id })
        vm.effects.test {
            assertEquals(MediaEffect.Deleted(2), awaitItem())
        }
    }

    @Test
    fun `batch delete 403 emits Forbidden and keeps the selection`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 200, batchStatus = 403)
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 }

        vm.toggleSelect("a1")
        vm.requestBatchDelete()
        awaitReady(vm) { it.pendingDelete != null }
        vm.confirmPendingDelete()
        val after = awaitReady(vm) { it.pendingDelete == null }
        assertEquals(setOf("a1"), after.selection)
        assertEquals(2, after.items.size)
        vm.effects.test {
            assertEquals(MediaEffect.Forbidden, awaitItem())
        }
    }

    @Test
    fun `batch delete 401 emits AuthRequired`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 200, batchStatus = 401)
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 }

        vm.toggleSelect("a1")
        vm.requestBatchDelete()
        awaitReady(vm) { it.pendingDelete != null }
        vm.confirmPendingDelete()
        awaitReady(vm) { it.pendingDelete == null }
        vm.effects.test {
            assertEquals(MediaEffect.AuthRequired, awaitItem())
        }
    }

    @Test
    fun `cancelPendingDelete disarms the dialog without deleting`() = runBlocking<Unit> {
        server.dispatcher = deleteRoutes(deleteStatus = 200)
        val vm = viewModel()
        awaitReady(vm) { it.items.size == 2 }

        vm.requestDelete("a1")
        awaitReady(vm) { it.pendingDelete != null }
        vm.cancelPendingDelete()
        val after = awaitReady(vm) { it.pendingDelete == null }
        assertEquals(2, after.items.size)
    }

    // ── Recordings ──

    @Test
    fun `start recording posts and re-polls the running status`() = runBlocking<Unit> {
        var recording = recordingJson(false)
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recording)
                path.startsWith("/api/recording/start") -> {
                    recording = recordingJson(true, elapsedSeconds = 7)
                    ok(commandJson)
                }
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.sessions.isEmpty() } // all four init reads landed

        vm.startRecording()
        val ready = awaitReady(vm) { it.recording.isRecording && it.recording.elapsedSeconds == 7 }
        assertTrue(ready.recording.isRecording)
        assertTrue(recordedPaths().any { it == "/api/recording/start" })
    }

    @Test
    fun `stop recording posts and re-polls the idle status`() = runBlocking<Unit> {
        var recording = recordingJson(true, elapsedSeconds = 30)
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recording)
                path.startsWith("/api/recording/stop") -> {
                    recording = recordingJson(false)
                    ok(commandJson)
                }
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.recording.isRecording }

        vm.stopRecording()
        awaitReady(vm) { !it.recording.isRecording }
        assertTrue(recordedPaths().any { it == "/api/recording/stop" })
    }

    // ── Interval capture ──

    @Test
    fun `start interval posts the default cadence and re-polls the status`() = runBlocking<Unit> {
        var interval = intervalJson(false, 0)
        var startBody: String? = null
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(interval)
                path.startsWith("/api/capture/interval/start") -> {
                    // Capture the wire body: the default draft is the contract here.
                    startBody = request.body.readUtf8()
                    interval = intervalJson(true, completed = 3)
                    ok(commandJson)
                }
                else -> null
            }
        }
        val vm = viewModel()
        vm.startInterval()
        awaitReady(vm) { it.interval.isRunning && it.interval.completedCaptures == 3 }
        assertEquals("{\"intervalSeconds\":60}", startBody)
    }

    @Test
    fun `an edited draft rides the interval start wire body`() = runBlocking<Unit> {
        var startBody: String? = null
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(true, completed = 1))
                path.startsWith("/api/capture/interval/start") -> {
                    startBody = request.body.readUtf8()
                    ok(commandJson)
                }
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.interval.isRunning }

        vm.setIntervalDraft("90")
        val armed = awaitReady(vm) { it.intervalDraft == "90" && it.intervalDraftValid }
        assertTrue(armed.intervalDraftValid)

        vm.startInterval()
        awaitReady(vm) { !it.busy }
        assertEquals("{\"intervalSeconds\":90}", startBody)
    }

    @Test
    fun `an out-of-range draft invalidates and blocks the start dial`() = runBlocking<Unit> {
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                path.startsWith("/api/capture/interval/start") -> ok(commandJson) // must never fire
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.intervalDraft == "60" }

        vm.setIntervalDraft("9999")
        val invalid = awaitReady(vm) { it.intervalDraft == "9999" }
        assertTrue(!invalid.intervalDraftValid)

        vm.startInterval() // the invalid draft is a silent no-dial

        awaitReady(vm) { !it.busy }
        assertTrue(recordedPaths().none { it.startsWith("/api/capture/interval/start") })
        Unit
    }

    @Test
    fun `start interval 403 emits Forbidden and leaves the card idle`() = runBlocking<Unit> {
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                path.startsWith("/api/capture/interval/start") -> error(403)
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.sessions.isEmpty() }

        vm.startInterval()
        val ready = awaitReady(vm) { !it.busy }
        assertTrue(!ready.interval.isRunning)
        vm.effects.test {
            assertEquals(MediaEffect.Forbidden, awaitItem())
        }
    }

    @Test
    fun `stop interval posts and re-polls the idle status`() = runBlocking<Unit> {
        var interval = intervalJson(true, completed = 9)
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(interval)
                path.startsWith("/api/capture/interval/stop") -> {
                    interval = intervalJson(false, completed = 9)
                    ok(commandJson)
                }
                else -> null
            }
        }
        val vm = viewModel()
        awaitReady(vm) { it.interval.isRunning }

        vm.stopInterval()
        awaitReady(vm) { !it.interval.isRunning }
        assertTrue(recordedPaths().any { it == "/api/capture/interval/stop" })
    }

    // ── Section failure + retry ──

    @Test
    fun `failed gallery names the section and a retry lands the page`() = runBlocking<Unit> {
        var galleryFails = true
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") ->
                    if (galleryFails) {
                        error(500)
                    } else {
                        ok(galleryJson(listOf(galleryItem("a1")), total = 1, page = 1, hasMore = false))
                    }
                path.startsWith("/api/recording/status") -> ok(recordingJson(false))
                path.startsWith("/api/recordings/sessions") -> ok(sessionsJson())
                path.startsWith("/api/capture/interval/status") -> ok(intervalJson(false, 0))
                else -> null
            }
        }
        val vm = viewModel()
        val failed = awaitReady(vm) { MediaSection.GALLERY in it.failedSections }
        assertTrue(failed.items.isEmpty())

        galleryFails = false
        vm.refresh()
        val recovered = awaitReady(vm) { it.items.size == 1 && MediaSection.GALLERY !in it.failedSections }
        assertEquals(1, recovered.total)
    }

    @Test
    fun `failed recordings and interval name their sections`() = runBlocking<Unit> {
        server.dispatcher = routing { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/api/gallery") -> ok(galleryJson(emptyList(), total = 0, page = 1, hasMore = false))
                path.startsWith("/api/recording/status") -> error(500)
                path.startsWith("/api/recordings/sessions") -> error(500)
                path.startsWith("/api/capture/interval/status") -> error(500)
                else -> null
            }
        }
        val vm = viewModel()
        val ready = awaitReady(vm) {
            MediaSection.RECORDINGS in it.failedSections && MediaSection.INTERVAL in it.failedSections
        }
        assertTrue(MediaSection.GALLERY !in ready.failedSections)
    }
}
