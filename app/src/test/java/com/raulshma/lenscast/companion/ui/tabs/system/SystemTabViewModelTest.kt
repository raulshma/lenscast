package com.raulshma.lenscast.companion.ui.tabs.system

import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The system tab's ViewModel rules, one MockWebServer round trip at a time:
 * the parallel section load (info + lenses + settings + identity + audit,
 * each landing independently), the viewer role's read-only gating (the
 * admin-only session roster is never fetched), the settings save as the
 * GET-merge-PUT chain (the PUT body carries the FULL merged streaming
 * section, camera omitted, secrets blank — the web console's wire shape),
 * the revert-on-failure semantics, the session revoke verdicts (200 / 401 /
 * 403), logout, and the audit limit paging.
 *
 * Real threads everywhere (runBlocking + the repo's plain state-flow
 * awaits — no virtual time, no sleeps): requests route through a recording
 * path dispatcher so the parallel burst's order never matters.
 */
class SystemTabViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: RecordingPathDispatcher
    private lateinit var filesDir: File
    private lateinit var credentials: CompanionCredentialStore

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = RecordingPathDispatcher()
        server.dispatcher = dispatcher
        server.start()
        filesDir = createTempDir("system-tab-vm")
        credentials = CompanionCredentialStore(filesDir, PlaintextCipher)
    }

    @After
    fun tearDown() {
        server.shutdown()
        filesDir.deleteRecursively()
    }

    private object PlaintextCipher : com.raulshma.lenscast.companion.roster.CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    /** Routes by `METHOD path` (query included) with per-path response queues. */
    private class RecordingPathDispatcher : Dispatcher() {
        data class Seen(val key: String, val body: String?)

        val requests = CopyOnWriteArrayList<Seen>()
        private val routes = HashMap<String, ArrayDeque<MockResponse>>()

        fun enqueue(key: String, response: MockResponse) {
            synchronized(routes) {
                routes.getOrPut(key) { ArrayDeque() }.addLast(response)
            }
        }

        fun times(key: String): Int = requests.count { it.key == key }

        fun body(key: String): String? = requests.firstOrNull { it.key == key }?.body

        override fun dispatch(request: RecordedRequest): MockResponse {
            val key = "${request.method} ${request.path.orEmpty()}"
            requests.add(Seen(key, request.body.readUtf8()))
            val queue = synchronized(routes) { routes[key] } ?: return fallback()
            return synchronized(queue) { queue.removeFirstOrNull() } ?: fallback()
        }

        private fun fallback(): MockResponse =
            MockResponse().setResponseCode(500).setBody("{\"error\":\"unrouted\"}")
    }

    // ── Fixture documents (the web console's own contract pins) ──

    private fun contract(name: String): String =
        listOf(File("../web/contract/$name"), File("web/contract/$name"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("Contract fixture $name not found")

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** Routes the full admin refresh burst: all seven endpoints. */
    private fun routeAdminRefresh(
        sessionsBody: String = defaultSessions,
        sessionRole: String = "admin",
    ) {
        dispatcher.enqueue("GET /api/system", ok(contract("system.json")))
        dispatcher.enqueue("GET /api/camera/lenses", ok(contract("lenses.json")))
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":true}"""))
        dispatcher.enqueue(
            "GET /api/auth/session",
            ok("""{"authenticated":true,"role":"$sessionRole"}"""),
        )
        dispatcher.enqueue("GET /api/auth/sessions", ok(sessionsBody))
        dispatcher.enqueue("GET /api/audit?limit=50", ok(contract("audit-log.json")))
    }

    private val defaultSessions =
        """{"sessions":[{"tokenPrefix":"abcd1234","expiresAtMs":1893456000000,"role":"admin"}],"count":1}"""

    private fun auditJson(count: Int, total: Int): String {
        val entries = (0 until count).joinToString(",") { i ->
            """{"timestampMs":${1_788_825_000_000L - i * 1_000L},"action":"PUT /api/settings","detail":"","outcome":"ok"}"""
        }
        return """{"entries":[$entries],"total":$total}"""
    }

    private fun viewModel(rosterRole: String? = SystemTabViewModel.ROLE_ADMIN, scope: CoroutineScope) =
        SystemTabViewModel(
            api = LenscastApiClient(testDevice()) { credentials.get(testDevice().id) },
            rosterRole = rosterRole,
            scope = scope,
        )

    private fun testDevice() = CompanionDevice(
        id = "dev-1",
        name = "Garage",
        host = "127.0.0.1",
        port = server.port,
        auth = CompanionAuthKind.TOKEN,
        role = "admin",
        addedAtMs = 0L,
    )

    /** Parks until every section of the state has landed (or failed). */
    private suspend fun awaitReady(vm: SystemTabViewModel): SystemUiState.Ready = withTimeout(10_000) {
        var hit: SystemUiState.Ready? = null
        vm.state.first { current ->
            val ready = current as? SystemUiState.Ready ?: return@first false
            if (ready.info != null && ready.settings != null &&
                ready.identity != null && ready.audit != null
            ) {
                hit = ready
                true
            } else {
                false
            }
        }
        requireNotNull(hit)
    }

    /** Parks until the effect channel carries a notice with [resId]; returns Unit so test methods stay void. */
    private suspend fun awaitNotice(vm: SystemTabViewModel, resId: Int) {
        withTimeout(10_000) {
            vm.effects.first { effect ->
                effect is SystemTabEffect.Notice && effect.messageRes == resId
            }
            Unit
        }
    }

    // ── Initial load ──

    @Test
    fun `initial load lands all four cards`() = runBlocking {
        routeAdminRefresh()
        val vm = viewModel(scope = CoroutineScope(coroutineContext))

        val ready = awaitReady(vm)
        assertEquals("1.9.0", ready.info?.appVersion)
        assertEquals("Pixel 8", ready.info?.deviceModel)
        assertEquals(3, ready.lenses?.lenses?.size)
        assertEquals(50, ready.settings?.document?.streaming?.motionSensitivityPercent)
        assertTrue(ready.identity?.authenticated == true)
        assertEquals("admin", ready.identity?.role)
        assertEquals(listOf("abcd1234"), ready.identity?.sessions?.map { it.tokenPrefix })
        assertEquals(2, ready.audit?.entries?.size)
        assertEquals(2, ready.audit?.total)
        assertTrue(ready.audit?.exhausted == true) // the fixture trail is fully covered
        assertFalse(ready.failed)
        // Newest first: the audit fixture opens with PUT /api/settings.
        assertEquals("PUT /api/settings", ready.audit?.entries?.firstOrNull()?.action)
    }

    @Test
    fun `viewer sessions never fetch the admin-only session roster`() = runBlocking {
        dispatcher.enqueue("GET /api/system", ok(contract("system.json")))
        dispatcher.enqueue("GET /api/camera/lenses", ok(contract("lenses.json")))
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":true}"""))
        dispatcher.enqueue("GET /api/auth/session", ok("""{"authenticated":true,"role":"viewer"}"""))
        dispatcher.enqueue("GET /api/audit?limit=50", ok(contract("audit-log.json")))
        val vm = viewModel(rosterRole = SystemTabViewModel.ROLE_VIEWER, scope = CoroutineScope(coroutineContext))

        val ready = awaitReady(vm)
        assertNull(ready.identity?.sessions) // never fetched, not merely empty
        assertTrue(dispatcher.requests.none { it.key.contains("/api/auth/sessions") })
        // Settings still read (the card renders read-only for viewers).
        assertTrue(ready.settings != null)
    }

    // ── Settings save: the GET-merge-PUT chain ──

    @Test
    fun `save merges the draft onto a fresh GET and PUTs the full streaming section`() = runBlocking {
        routeAdminRefresh()
        // The save's own GET (the merge base), then the PUT answer.
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("PUT /api/settings", ok("""{"success":true}"""))
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.editSettings { draft ->
            draft.copy(motionSensitivityPercent = 80, mlDetectionEnabled = true)
        }
        vm.saveSettings()
        awaitNotice(vm, R.string.companion_system_settings_saved)

        val put = requireNotNull(dispatcher.body("PUT /api/settings"))
        val update = requireNotNull(
            AppJson.moshi.adapter(SettingsUpdate::class.java).fromJson(put),
        )
        // Camera omitted: the PUT never touches the camera section.
        assertNull(update.camera)
        val streaming = requireNotNull(update.streaming)
        // The draft's edits ride…
        assertEquals(80, streaming.motionSensitivityPercent)
        assertTrue(streaming.mlDetectionEnabled)
        // …and everything else is the FRESH document's values (the wholesale
        // section echo — a hand-built partial body would reset them).
        assertEquals(8080, streaming.port)
        assertEquals(70, streaming.jpegQuality)
        assertEquals("720p", streaming.rtspResolution)
        assertEquals("homeassistant", streaming.mqttDiscoveryPrefix)
        assertEquals(2_048, streaming.storageQuotaMb)
        // Secrets ride blank: the save never echoes a stored credential.
        assertEquals("", streaming.rtmpUrl)
        assertEquals("", streaming.apiToken)

        val block = requireNotNull((vm.state.value as? SystemUiState.Ready)?.settings)
        assertNull(block.draft)
        assertFalse(block.saving)
        assertEquals(80, block.document.streaming.motionSensitivityPercent)
    }

    @Test
    fun `a failed save reverts the draft and effects the revert notice`() = runBlocking {
        routeAdminRefresh()
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("PUT /api/settings", MockResponse().setResponseCode(500).setBody("{}"))
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.editSettings { it.copy(motionSensitivityPercent = 90) }
        vm.saveSettings()
        awaitNotice(vm, R.string.companion_system_settings_save_failed)

        val block = requireNotNull((vm.state.value as? SystemUiState.Ready)?.settings)
        assertNull(block.draft) // reverted to the confirmed document
        assertFalse(block.saving)
        assertEquals(50, block.document.streaming.motionSensitivityPercent)
    }

    @Test
    fun `viewer saves never reach the wire`() = runBlocking {
        dispatcher.enqueue("GET /api/system", ok(contract("system.json")))
        dispatcher.enqueue("GET /api/camera/lenses", ok(contract("lenses.json")))
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":true}"""))
        dispatcher.enqueue("GET /api/auth/session", ok("""{"authenticated":true,"role":"viewer"}"""))
        dispatcher.enqueue("GET /api/audit?limit=50", ok(contract("audit-log.json")))
        val vm = viewModel(rosterRole = SystemTabViewModel.ROLE_VIEWER, scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        // A viewer's edit intent is ignored (no draft, no save, no requests).
        vm.editSettings { it.copy(motionSensitivityPercent = 99) }
        vm.saveSettings()

        val block = requireNotNull((vm.state.value as? SystemUiState.Ready)?.settings)
        assertNull(block.draft)
        assertEquals(0, dispatcher.times("PUT /api/settings"))
        assertEquals(1, dispatcher.times("GET /api/settings")) // the load only
    }

    // ── Auth sessions: revoke verdicts + logout ──

    @Test
    fun `revoke 200 confirms and reloads the session roster`() = runBlocking {
        routeAdminRefresh()
        dispatcher.enqueue("DELETE /api/auth/sessions/abcd1234", ok("""{"success":true}"""))
        // The post-revoke identity reload re-reads the trio.
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":true}"""))
        dispatcher.enqueue("GET /api/auth/session", ok("""{"authenticated":true,"role":"admin"}"""))
        dispatcher.enqueue(
            "GET /api/auth/sessions",
            ok("""{"sessions":[{"tokenPrefix":"efgh5678","expiresAtMs":1893456000000,"role":"viewer"}],"count":1}"""),
        )
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.revokeSession("abcd1234")
        awaitNotice(vm, R.string.companion_system_session_revoked)

        // The reload landed: the roster now carries the surviving session.
        withTimeout(10_000) {
            vm.state.first { current ->
                (current as? SystemUiState.Ready)?.identity?.sessions?.map { it.tokenPrefix } ==
                    listOf("efgh5678")
            }
        }
        assertEquals(1, dispatcher.times("DELETE /api/auth/sessions/abcd1234"))
    }

    @Test
    fun `revoke 401 effects the re-pair notice`() = runBlocking {
        routeAdminRefresh()
        dispatcher.enqueue(
            "DELETE /api/auth/sessions/abcd1234",
            MockResponse().setResponseCode(401).setBody("""{"error":"auth"}"""),
        )
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.revokeSession("abcd1234")
        awaitNotice(vm, R.string.companion_system_error_auth)
    }

    @Test
    fun `revoke 403 effects the forbidden notice`() = runBlocking {
        routeAdminRefresh()
        dispatcher.enqueue(
            "DELETE /api/auth/sessions/abcd1234",
            MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""),
        )
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.revokeSession("abcd1234")
        awaitNotice(vm, R.string.companion_system_error_forbidden)
    }

    @Test
    fun `logout drops the session and reloads the identity signed-out`() = runBlocking {
        routeAdminRefresh()
        dispatcher.enqueue("POST /api/auth/logout", ok("""{"success":true}"""))
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":true}"""))
        dispatcher.enqueue("GET /api/auth/session", ok("""{"authenticated":false}"""))
        val vm = viewModel(scope = CoroutineScope(coroutineContext))
        awaitReady(vm)

        vm.setPendingLogout(true)
        vm.logout()
        awaitNotice(vm, R.string.companion_system_logged_out)

        withTimeout(10_000) {
            vm.state.first { current ->
                (current as? SystemUiState.Ready)?.identity?.authenticated == false
            }
        }
        // Signed-out reload: no roster fetch (the guard needs a live session).
        assertEquals(1, dispatcher.times("GET /api/auth/sessions"))
        assertTrue((vm.state.value as? SystemUiState.Ready)?.identity?.pendingLogout == false)
    }

    // ── Audit paging ──

    @Test
    fun `audit paging widens the limit and marks the page exhausted when covered`() = runBlocking {
        dispatcher.enqueue("GET /api/system", ok(contract("system.json")))
        dispatcher.enqueue("GET /api/camera/lenses", ok(contract("lenses.json")))
        dispatcher.enqueue("GET /api/settings", ok(contract("settings.json")))
        dispatcher.enqueue("GET /api/auth/status", ok("""{"required":false}"""))
        dispatcher.enqueue("GET /api/auth/session", ok("""{"authenticated":true,"role":"admin"}"""))
        dispatcher.enqueue("GET /api/audit?limit=50", ok(auditJson(count = 50, total = 60)))
        val vm = viewModel(scope = CoroutineScope(coroutineContext))

        withTimeout(10_000) {
            vm.state.first { current ->
                (current as? SystemUiState.Ready)?.audit?.entries?.size == 50
            }
        }
        // The whole burst settles before widening, so no other request can
        // land after the load-more call (keeps the tail assertion exact).
        awaitReady(vm)
        val first = requireNotNull((vm.state.value as? SystemUiState.Ready)?.audit)
        assertFalse(first.exhausted) // 50 of 60

        dispatcher.enqueue("GET /api/audit?limit=100", ok(auditJson(count = 60, total = 60)))
        vm.loadMoreAudit()

        withTimeout(10_000) {
            vm.state.first { current ->
                (current as? SystemUiState.Ready)?.audit?.entries?.size == 60
            }
        }
        val second = requireNotNull((vm.state.value as? SystemUiState.Ready)?.audit)
        assertTrue(second.exhausted) // the trail is covered
        assertFalse(second.loadingMore)
        assertEquals(100, second.limit)
        assertEquals(60, second.total)
        // Newest first: entry 0 carries the largest timestamp.
        assertTrue(second.entries.first().timestampMs > second.entries.last().timestampMs)
        // Each page was fetched exactly once, the wider page last (the
        // sections all landed before load-more ran, so nothing else can be
        // in flight).
        assertEquals(1, dispatcher.times("GET /api/audit?limit=50"))
        assertEquals(1, dispatcher.times("GET /api/audit?limit=100"))
        assertEquals("GET /api/audit?limit=100", dispatcher.requests.last().key)
    }

    @Test
    fun `a dead transport lands the failed state with the retry affordance`() = runBlocking {
        server.shutdown()
        val vm = viewModel(scope = CoroutineScope(coroutineContext))

        withTimeout(10_000) {
            vm.state.first { current ->
                (current as? SystemUiState.Ready)?.failed == true
            }
        }
        assertEquals(true, (vm.state.value as? SystemUiState.Ready)?.failed)
    }
}
