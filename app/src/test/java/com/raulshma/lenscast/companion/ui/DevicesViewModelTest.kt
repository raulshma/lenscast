package com.raulshma.lenscast.companion.ui

import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import com.raulshma.lenscast.core.AppJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
 * The devices roster's ViewModel rules, one MockWebServer round trip per
 * verdict: 200 → ONLINE, 401 → UNAUTHORIZED, any other non-2xx → OFFLINE,
 * dead transport → OFFLINE. Also pinned: the remove flow tears down all
 * three stores (roster + credentials + session cache) and confirms via
 * effect, the empty roster lands the Empty state, the confirm-dialog id
 * rides the state, and roster order survives the probe burst.
 *
 * VM launches queue behind the test block on runBlocking's event loop, so
 * every probe verdict is awaited off the state flow ([awaitRows]) — no
 * sleeps, no flake.
 */
class DevicesViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var sessions: DeviceSessionManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        filesDir = createTempDir("devices-vm")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, PlaintextCipher)
        sessions = DeviceSessionManager(roster, credentials, CoroutineScope(SupervisorJob()))
    }

    @After
    fun tearDown() {
        server.shutdown()
        filesDir.deleteRecursively()
    }

    private object PlaintextCipher : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    /** A minimal but valid GET /api/status answer for the probes to decode. */
    private val statusJson =
        "{\"streaming\":{\"isActive\":false,\"url\":\"\",\"webStreamingEnabled\":false," +
            "\"webStreamingActive\":false,\"clientCount\":0,\"audioEnabled\":false,\"audioUrl\":\"\"," +
            "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"\"},\"thermal\":\"NORMAL\"," +
            "\"battery\":{\"level\":50,\"isCharging\":false,\"isPowerSaveMode\":false},\"camera\":\"ok\"," +
            "\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0}"

    private fun ok() = MockResponse().setResponseCode(200).setBody(statusJson)

    private fun seedDevice(id: String = "dev-1"): CompanionDevice = CompanionDevice(
        id = id,
        name = "Garage",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        addedAtMs = 0L,
    ).also { roster.upsert(it) }

    /**
     * The VM under test — scope attaches to the calling runBlocking's Job
     * so the probe burst keeps runBlocking parked until every verdict lands.
     */
    private fun viewModel(scope: CoroutineScope): DevicesViewModel = DevicesViewModel(
        roster = roster,
        credentials = credentials,
        sessions = sessions,
        apiClient = { device -> LenscastApiClient(device) { credentials.get(device.id) } },
        scope = scope,
    )

    /** Parks on the state flow until the single row reaches [expected]. */
    private suspend fun awaitRowStatus(
        vm: DevicesViewModel,
        expected: DeviceProbeStatus,
    ): DeviceRow = withTimeout(10_000) {
        var hit: DeviceRow? = null
        vm.state.first { current ->
            val row = (current as? DevicesUiState.Ready)?.rows?.singleOrNull()
            hit = row
            row?.status == expected
        }
        hit!!
    }

    /** Parks until every row's status has reached one of [expected]. */
    private suspend fun awaitRowStatuses(
        vm: DevicesViewModel,
        expected: Set<DeviceProbeStatus>,
    ): List<DeviceRow> = withTimeout(10_000) {
        var hit: List<DeviceRow> = emptyList()
        vm.state.first { current ->
            val rows = (current as? DevicesUiState.Ready)?.rows ?: return@first false
            hit = rows
            rows.map { it.status }.toSet() == expected
        }
        hit
    }

    @Test
    fun `empty roster lands the Empty state without probes`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DevicesUiState.Empty, vm.state.value)
    }

    @Test
    fun `a 200 status probe reads ONLINE`() = runBlocking {
        server.enqueue(ok())
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))

        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)
        assertEquals("/api/status", server.takeRequest().path)
    }

    @Test
    fun `a 401 probe reads UNAUTHORIZED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))

        assertEquals(DeviceProbeStatus.UNAUTHORIZED, awaitRowStatus(vm, DeviceProbeStatus.UNAUTHORIZED).status)
    }

    @Test
    fun `a 500 probe reads OFFLINE`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))

        assertEquals(DeviceProbeStatus.OFFLINE, awaitRowStatus(vm, DeviceProbeStatus.OFFLINE).status)
    }

    @Test
    fun `a dead transport reads OFFLINE`() = runBlocking {
        server.shutdown()
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))

        assertEquals(DeviceProbeStatus.OFFLINE, awaitRowStatus(vm, DeviceProbeStatus.OFFLINE).status)
    }

    @Test
    fun `manual refresh re-probes`() = runBlocking {
        server.enqueue(ok())
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        vm.refresh()
        assertEquals(DeviceProbeStatus.UNAUTHORIZED, awaitRowStatus(vm, DeviceProbeStatus.UNAUTHORIZED).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `remove clears roster credentials session and confirms via effect`() = runBlocking {
        // The init-refresh probe rides this queued answer so the test never
        // parks on a live socket.
        server.enqueue(ok())
        seedDevice()
        credentials.put("dev-1", StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
        val session = sessions.session("dev-1") // cache a live session
        assertEquals(listOf(session), sessions.active())

        val vm = viewModel(CoroutineScope(coroutineContext))
        awaitRowStatus(vm, DeviceProbeStatus.ONLINE)
        vm.remove("dev-1")

        assertNull(roster.find("dev-1"))
        assertNull(credentials.get("dev-1"))
        assertTrue("session cache dropped", sessions.active().isEmpty())
        assertEquals(DevicesUiState.Empty, vm.state.value)
        val effect = vm.effects.first() // Channel(BUFFERED): the effect is queued
        assertEquals("Garage", (effect as DevicesEffect.Removed).deviceName)
    }

    @Test
    fun `the confirm dialog id rides the state and cancels to null`() = runBlocking {
        server.enqueue(ok())
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        awaitRowStatus(vm, DeviceProbeStatus.ONLINE)

        vm.setPendingRemoval("dev-1")
        assertEquals("dev-1", (vm.state.value as DevicesUiState.Ready).pendingRemovalId)
        vm.setPendingRemoval(null)
        assertNull((vm.state.value as DevicesUiState.Ready).pendingRemovalId)
        assertFalse((vm.state.value as DevicesUiState.Ready).rows.isEmpty())
    }

    @Test
    fun `roster order is preserved across probes`() = runBlocking {
        server.enqueue(ok())
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        seedDevice("dev-1")
        roster.upsert(seedDevice("dev-2").copy(name = "Porch"))
        val vm = viewModel(CoroutineScope(coroutineContext))

        // Both probes launch concurrently (MAX_CONCURRENT_PROBES = 4), so
        // which device lands which of the two FIFO-queued MockWebServer
        // answers is scheduler luck — a per-device expectation here would be
        // a coin flip. The invariant this test pins is roster order (the
        // per-device verdict mapping has its own single-device tests above),
        // so park until both verdicts are in, then assert order survived.
        val rows = awaitRowStatuses(vm, setOf(DeviceProbeStatus.ONLINE, DeviceProbeStatus.OFFLINE))
        assertEquals(listOf("dev-1", "dev-2"), rows.map { it.device.id })
        assertEquals(
            setOf(DeviceProbeStatus.ONLINE, DeviceProbeStatus.OFFLINE),
            rows.map { it.status }.toSet(),
        )
    }

    // ── Capture-all ──

    /**
     * A route dispatcher whose payloads RE-SERVE on every request (suite-load
     * rule: a one-shot body plus a hang fallback loses races under load — a
     * hung status read trips OkHttp's real read timeout).
     */
    private fun liveRoutes(
        statusAnswer: () -> MockResponse = { ok() },
        captureAnswer: () -> MockResponse = { MockResponse().setResponseCode(200).setBody("""{"success":true}""") },
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/api/status") -> statusAnswer()
                path.startsWith("/api/capture") -> captureAnswer()
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    /** Parks until the capture-all volley has fully wound down. */
    private suspend fun awaitVolleyDone(vm: DevicesViewModel) {
        withTimeout(10_000) {
            vm.state.first { current ->
                current !is DevicesUiState.Ready || !current.capturingAll
            }
        }
    }

    @Test
    fun `capture-all fires on the online row, reports the verdict and unwinds the flag`() = runBlocking {
        server.dispatcher = liveRoutes()
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)

        vm.captureAll()

        val effect = vm.effects.first()
        assertTrue(effect is DevicesEffect.Captured)
        assertEquals("Garage", (effect as DevicesEffect.Captured).deviceName)
        assertTrue(effect.ok)
        awaitVolleyDone(vm)
        // FIFO note: the init probe's request sits ahead of the volley's —
        // drain until the capture POST arrives (its effect already did).
        val capture = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .firstOrNull { it.path == "/api/capture" }
        assertEquals("/api/capture", capture?.path)
        assertEquals("POST", capture?.method)
    }

    @Test
    fun `capture-all skips rows that are not online`() = runBlocking {
        server.dispatcher = liveRoutes(statusAnswer = { MockResponse().setResponseCode(500).setBody("{}") })
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DeviceProbeStatus.OFFLINE, awaitRowStatus(vm, DeviceProbeStatus.OFFLINE).status)

        vm.captureAll()

        awaitVolleyDone(vm)
        // Only the init probe ever hit the wire — the empty-target volley
        // returns before launching anything, so no capture can arrive later.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `capture-all is re-entrancy guarded`() = runBlocking {
        server.dispatcher = liveRoutes()
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)

        vm.captureAll()
        vm.captureAll() // the volley's flag flips synchronously — this call no-ops

        awaitVolleyDone(vm)
        val captures = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .toList()
            .filter { it.path == "/api/capture" }
        assertEquals(1, captures.size)
    }

    @Test
    fun `a failed capture volley member reports ok=false`() = runBlocking {
        server.dispatcher = liveRoutes(captureAnswer = { MockResponse().setResponseCode(500).setBody("{}") })
        seedDevice()
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)

        vm.captureAll()

        val effect = vm.effects.first()
        assertTrue(effect is DevicesEffect.Captured)
        assertFalse((effect as DevicesEffect.Captured).ok)
        awaitVolleyDone(vm)
    }

    @Test
    fun `refreshIfRosterChanged reloads when a new device was persisted and skips when unchanged`() = runBlocking {
        val vm = viewModel(CoroutineScope(coroutineContext))
        assertEquals(DevicesUiState.Empty, vm.state.value)

        // Unchanged empty roster does not re-probe.
        vm.refreshIfRosterChanged()
        assertEquals(DevicesUiState.Empty, vm.state.value)
        assertEquals(0, server.requestCount)

        // A newly paired device persisted by AddDeviceSheet appears after refreshIfRosterChanged.
        server.enqueue(ok())
        seedDevice("dev-new")
        vm.refreshIfRosterChanged()

        assertEquals(DeviceProbeStatus.ONLINE, awaitRowStatus(vm, DeviceProbeStatus.ONLINE).status)
        assertEquals(1, server.requestCount)

        // Unchanged populated roster does not re-probe either.
        vm.refreshIfRosterChanged()
        assertEquals(1, server.requestCount)
    }
}
