package com.raulshma.lenscast.companion.ui.tabs.events

import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The events tab's ViewModel rules, one MockWebServer round trip per verdict:
 * the stats fixture decodes into the strip's counts, the clear-all verdicts
 * map 200 / success=false / 401 / 403 to their notices (with a stats
 * re-fetch after success), the test-fire's answer surfaces verbatim, a
 * manual refresh re-fetches the stats, and the viewer role gates both
 * destructive actions off before any request fires.
 *
 * Threading: real threads end to end (`runBlocking`, never virtual time).
 * The VM's scope parents on a SupervisorJob under runBlocking's job — the
 * Eagerly stateIn collector never completes on its own, so every test's
 * finally cancels the scope to let runBlocking finish (no parked joins, no
 * leaks). Effects queue through Channel(BUFFERED) and awaits park on the
 * state flow — no sleeps. Request-order note: MockWebServer hands recorded
 * requests back FIFO, so every test that asserts a later request's path
 * first consumes the init stats request.
 */
class EventsViewModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** GET /api/detection/stats fixture — every field the DTO carries. */
    private val statsJson =
        "{\"last24h\":{\"motion\":3,\"sound\":1},\"last7d\":{\"motion\":9,\"sound\":2}," +
            "\"allTime\":{\"motion\":40,\"sound\":7},\"perDay\":[{\"day\":\"2026-11-14\",\"count\":4}]," +
            "\"totalEvents\":47,\"topZones\":[{\"label\":\"door\",\"count\":5}]," +
            "\"topLabels\":[{\"label\":\"person\",\"count\":12}]}"

    private fun statsOk() = MockResponse().setResponseCode(200).setBody(statsJson)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** The session under test — a bare session: feed empty, link IDLE (no lanes start). */
    private fun session() = DeviceSession("dev-1")

    private fun client(): LenscastApiClient = LenscastApiClient(
        CompanionDevice(
            id = "dev-1",
            name = "Garage",
            host = "127.0.0.1",
            port = server.port,
            auth = CompanionAuthKind.TOKEN,
            addedAtMs = 0L,
        ),
    ) { null }

    /**
     * Runs [block] with a VM whose scope parents on runBlocking's job; the
     * finally-cancel is what lets runBlocking finish (the stateIn collector
     * would otherwise park the join forever).
     */
    private fun vmTest(
        isAdmin: Boolean = true,
        block: suspend (EventsViewModel) -> Unit,
    ): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob(coroutineContext.job))
        try {
            block(EventsViewModel(session(), client(), isAdmin, scope))
        } finally {
            scope.cancel()
        }
    }

    /**
     * Parks on the state flow until Ready and [predicate] both hold. The
     * predicate is the LAST parameter so call sites can use the trailing
     * lambda form.
     */
    private suspend fun awaitReady(
        vm: EventsViewModel,
        timeoutMs: Long = 10_000,
        predicate: (EventsUiState.Ready) -> Boolean,
    ): EventsUiState.Ready {
        var hit: EventsUiState.Ready? = null
        withTimeout(timeoutMs) {
            vm.state.first { current ->
                val ready = current as? EventsUiState.Ready
                val match = ready != null && predicate(ready)
                if (match) hit = ready
                match
            }
        }
        return checkNotNull(hit) { "state never reached the expected Ready state" }
    }

    /**
     * Parks until the init stats fetch has LANDED (stats non-null). Not the
     * first Ready emission — that one carries stats=null (the stateIn seed
     * resolves instantly), and a test proceeding past it can outrun the init
     * GET on the wire, letting a later POST consume the stats FIFO answer.
     */
    private suspend fun awaitInitStats(vm: EventsViewModel): EventsUiState.Ready =
        awaitReady(vm) { it.stats != null }

    /** Parks (bounded) until the server has received [count] requests. */
    private suspend fun awaitRequestCount(count: Int, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (server.requestCount < count) delay(10)
        }
    }

    /** The next recorded request, bounded — MockWebServer's timed take is nullable. */
    private fun nextRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request arrived within 5s" }

    /** Consumes the init stats request (always request 1) once its response has landed. */
    private fun takeInitStatsRequest() {
        assertEquals("/api/detection/stats", nextRequest().path)
    }

    @Test
    fun `the stats fixture decodes into the strip's counts`() = vmTest { vm ->
        server.enqueue(statsOk())

        val ready = awaitReady(vm) { it.stats != null }

        assertEquals("/api/detection/stats", server.takeRequest().path)
        val stats = checkNotNull(ready.stats)
        assertEquals(47, stats.totalEvents)
        assertEquals(4, stats.last24h.values.sum())
        assertEquals(11, stats.last7d.values.sum())
        assertEquals(3, stats.last24h["motion"])
        assertEquals("person", stats.topLabels.single().label)
        assertEquals("door", stats.topZones.single().label)
        assertEquals(4, stats.perDay.single().count)
        // A bare session feeds IDLE + an empty feed into the same emission.
        assertEquals(ConnectionState.IDLE, ready.connection)
        assertTrue(ready.events.isEmpty())
    }

    @Test
    fun `a 200 clear-all confirms via notice and re-fetches the stats`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        takeInitStatsRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
        server.enqueue(statsOk()) // post-clear re-fetch

        vm.setPendingClearConfirm(true)
        vm.confirmClear()

        val effect = vm.effects.first()
        assertTrue(effect is EventsEffect.Notice)
        assertEquals(R.string.companion_events_cleared, (effect as EventsEffect.Notice).resId)
        val delete = nextRequest()
        assertEquals("/api/detection/events", delete.path)
        assertEquals("DELETE", delete.method)
        awaitRequestCount(3)
        assertEquals("/api/detection/stats", server.takeRequest().path)
        awaitReady(vm) { !it.busy && !it.pendingClearConfirm }
    }

    @Test
    fun `a 401 clear-all lands the sign-in notice`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        takeInitStatsRequest()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""))

        vm.setPendingClearConfirm(true)
        vm.confirmClear()

        val effect = vm.effects.first()
        assertEquals(R.string.companion_events_signin_required, (effect as EventsEffect.Notice).resId)
        // No stats re-fetch after the failure: exactly two requests total.
        awaitRequestCount(2)
        awaitReady(vm) { !it.busy }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 403 clear-all lands the forbidden notice`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        takeInitStatsRequest()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))

        vm.setPendingClearConfirm(true)
        vm.confirmClear()

        val effect = vm.effects.first()
        assertEquals(R.string.companion_events_forbidden, (effect as EventsEffect.Notice).resId)
        awaitRequestCount(2)
        awaitReady(vm) { !it.busy }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a success=false clear payload lands the clear-failed notice`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        takeInitStatsRequest()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false,"error":"busy"}"""))

        vm.setPendingClearConfirm(true)
        vm.confirmClear()

        val effect = vm.effects.first()
        assertEquals(R.string.companion_events_clear_failed, (effect as EventsEffect.Notice).resId)
    }

    @Test
    fun `the test-fire surfaces the dispatched sinks`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        takeInitStatsRequest()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"dispatchedActions":["siren","log"]}"""),
        )

        vm.testFire()

        val effect = vm.effects.first()
        assertTrue(effect is EventsEffect.TestFired)
        val fired = effect as EventsEffect.TestFired
        assertTrue(fired.success)
        assertEquals(listOf("siren", "log"), fired.dispatchedActions)
        val fire = nextRequest()
        assertEquals("/api/detection/test", fire.path)
        assertEquals("POST", fire.method)
        awaitReady(vm) { !it.busy }
    }

    @Test
    fun `a test-fire success without sinks surfaces the empty verdict`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"dispatchedActions":[]}"""))

        vm.testFire()

        val effect = vm.effects.first()
        assertTrue(effect is EventsEffect.TestFired)
        val fired = effect as EventsEffect.TestFired
        assertTrue(fired.success)
        assertTrue(fired.dispatchedActions.isEmpty())
    }

    @Test
    fun `a test-fire failure surfaces the failed verdict`() = vmTest { vm ->
        server.enqueue(statsOk()) // init stats
        awaitInitStats(vm)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":false}"""))

        vm.testFire()

        val effect = vm.effects.first()
        assertTrue(effect is EventsEffect.TestFired)
        assertFalse((effect as EventsEffect.TestFired).success)
    }

    @Test
    fun `manual refresh re-fetches the stats`() = vmTest { vm ->
        server.enqueue(statsOk()) // init
        awaitReady(vm) { it.stats != null }
        takeInitStatsRequest()
        server.enqueue(statsOk()) // the refresh

        vm.refresh()

        awaitRequestCount(2)
        assertEquals("/api/detection/stats", server.takeRequest().path)
        awaitReady(vm) { it.stats != null }
    }

    @Test
    fun `viewer role gates both destructive actions before any request`() =
        vmTest(isAdmin = false) { vm ->
            server.enqueue(statsOk()) // init stats — the only request allowed
            awaitInitStats(vm)
            takeInitStatsRequest()

            vm.setPendingClearConfirm(true)
            vm.confirmClear()
            vm.testFire()

            // Both calls no-op'd synchronously (the guard runs before any
            // launch), so the wire saw nothing beyond the init stats fetch.
            assertEquals(1, server.requestCount)
            val ready = awaitInitStats(vm)
            assertFalse(ready.isAdmin)
            assertFalse(ready.busy)
        }

    // ── Wave-4 seams: the manual poll + the clear-wipes-feed wiring ──

    /**
     * A session ATTACHED to real stores dialed against the mock server —
     * never started (no lanes), so the wire sees only what the VM (and the
     * test's own seam calls) dial. Roster + credentials live in a temp dir;
     * the stores' plaintext cipher is the test idiom
     * [com.raulshma.lenscast.companion.ui.DevicesViewModelTest] uses.
     */
    private fun attachedSession(dir: java.io.File): DeviceSession {
        val roster = com.raulshma.lenscast.companion.roster.DeviceRosterStore(
            dir,
            com.raulshma.lenscast.core.AppJson,
        )
        val credentials = com.raulshma.lenscast.companion.roster.CompanionCredentialStore(
            dir,
            PlaintextCredentialsCipher,
        )
        roster.upsert(
            com.raulshma.lenscast.companion.model.CompanionDevice(
                id = "dev-1",
                name = "Garage",
                host = "127.0.0.1",
                port = server.port,
                auth = CompanionAuthKind.TOKEN,
                addedAtMs = 0L,
            ),
        )
        credentials.put(
            "dev-1",
            com.raulshma.lenscast.companion.model.StoredCredentials(CompanionAuthKind.TOKEN, token = "t"),
        )
        return DeviceSession("dev-1").also {
            it.attach(roster, credentials, CoroutineScope(SupervisorJob()))
        }
    }

    private object PlaintextCredentialsCipher : com.raulshma.lenscast.companion.roster.CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    /** One poll-page event, the wire shape the DTO decodes. */
    private fun eventJson(id: String, timestampMs: Long) =
        "{\"id\":\"$id\",\"type\":\"motion\",\"source\":\"cam\",\"timestampMs\":$timestampMs}"

    private fun eventsPage(vararg events: String) =
        "{\"events\":[${events.joinToString(",")}],\"total\":${events.size}}"

    /**
     * RE-SERVING dispatcher (the suite-load rule: idempotent payloads on
     * every request, never a one-shot body plus a hang fallback).
     */
    private fun detectionRoutes() = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.startsWith("/api/detection/stats") -> statsOk()
                path.startsWith("/api/detection/events") && request.method == "DELETE" ->
                    MockResponse().setResponseCode(200).setBody("""{"success":true}""")
                path.startsWith("/api/detection/events") ->
                    ok(eventsPage(eventJson("e1", 1_700_000_000_000L), eventJson("e2", 1_700_000_100_000L)))
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
    }

    @Test
    fun `a successful clear wipes the session's in-memory feed`() = runBlocking {
        server.dispatcher = detectionRoutes()
        val dir = createTempDir("events-vm-attached")
        try {
            val session = attachedSession(dir)
            val scope = CoroutineScope(SupervisorJob(coroutineContext.job))
            try {
                val vm = EventsViewModel(session, client(), isAdmin = true, scope = scope)
                // Seed the feed through the manual-poll seam itself: one GET
                // /api/detection/events merges the page into the buffer.
                session.manualDetectionPoll()
                val seeded = awaitReady(vm) { it.events.size == 2 }
                assertEquals(listOf("e2", "e1"), seeded.events.map { it.id }) // newest first

                vm.setPendingClearConfirm(true)
                vm.confirmClear()

                val effect = vm.effects.first()
                assertEquals(R.string.companion_events_cleared, (effect as EventsEffect.Notice).resId)
                // The wire saw the DELETE (FIFO note: the seeding poll's GET
                // sits ahead of it — drain by method); the buffer is wiped in
                // memory, so the feed empties immediately, no poll needed.
                val delete = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
                    .firstOrNull { it.method == "DELETE" }
                assertEquals("/api/detection/events", delete?.path)
                awaitReady(vm) { it.events.isEmpty() && !it.busy }
            } finally {
                scope.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }

    @Test
    fun `manual refresh also kicks the session's detection poll`() = runBlocking {
        server.dispatcher = detectionRoutes()
        val dir = createTempDir("events-vm-attached")
        try {
            val session = attachedSession(dir)
            val scope = CoroutineScope(SupervisorJob(coroutineContext.job))
            try {
                val vm = EventsViewModel(session, client(), isAdmin = true, scope = scope)
                awaitInitStats(vm)

                vm.refresh()

                // The refresh fires the stats re-fetch AND the session's
                // manual detection poll (concurrent launches — FIFO order on
                // the wire is scheduler luck, so drain and assert by path).
                awaitRequestCount(3)
                val paths = generateSequence { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
                    .toList()
                    .map { it.path.orEmpty().substringBefore('?') }
                assertTrue("/api/detection/events" in paths)
                assertTrue("/api/detection/stats" in paths)
                // The polled page merged into the session feed.
                awaitReady(vm) { it.events.size == 2 }
            } finally {
                scope.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }
}
