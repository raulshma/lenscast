package com.raulshma.lenscast.companion.console

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
 * The diagnostics seam's rules, one MockWebServer round trip per verdict —
 * real threads (`runBlocking`, the RemoteAutomationSourceTest convention):
 *
 * - the beat lands info, lenses, clients, and the first audit page (the tab's
 *   50-entry page size);
 * - `loadMoreAudit` widens the limit and lands the exhaustion rule (a bigger
 *   limit that stops returning more marks the page exhausted);
 * - kick DELETEs the client route and re-reads the roster (admin);
 * - restart rides `?mode=soft` / the bare route, reset-camera its route —
 *   all admin-only: a viewer pairing's maintenance writes fire no request;
 * - export returns the envelope's raw JSON; import posts the picked body
 *   verbatim and reports the acceptance;
 * - a roster-lost source (null client) settles null and no-ops everything.
 */
class RemoteDiagnosticsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    private val auditEntries = AtomicReference(auditJson(50, 120))

    /** The stream-client roster the mock serves; a DELETE drops the kicked id. */
    private val streamClients = AtomicReference(listOf("192.168.1.5"))

    /** The export envelope the mock serves and the export test compares verbatim. */
    private val exportEnvelope =
        """{"schemaVersion":1,"exportedAtMs":1,"app":"lenscast","settings":{"camera":{},"streaming":{}}}"""

    private var kickAnswer = """{"success":true}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = diagnosticsDispatcher()
        server.start()
        filesDir = createTempDir("diagnostics-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-diagnostics",
            name = "Diagnostics",
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

    /** [count] audit entries with [total] the trail's declared size. */
    private fun auditJson(count: Int, total: Int): String =
        (0 until count).joinToString(",", prefix = "{\"entries\":[", postfix = "],\"total\":$total}") { i ->
            "{\"timestampMs\":${1_700_000_000_000L + i},\"action\":\"POST /api/settings\"," +
                "\"detail\":\"\",\"outcome\":\"ok\"}"
        }

    /** The stream-clients roster answer for [httpClients] (the dispatcher's shape). */
    private fun clientsJson(httpClients: List<String>): String {
        val list = httpClients.joinToString(",") { "\"$it\"" }
        return """{"httpClients":[$list],"httpCount":${httpClients.size},"rtspCount":0,"maxHttp":8,"rtspClients":[]}"""
    }

    private fun diagnosticsDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add("${request.method} ${request.path.orEmpty()}")
            request.body?.let { bodies.add(it.readUtf8()) }
            val path = request.path.orEmpty().substringBefore('?')
            return when {
                request.method == "GET" && path == "/api/system" -> ok(systemJson())
                request.method == "GET" && path == "/api/camera/lenses" ->
                    ok("""{"lenses":[{"index":0,"id":"0","label":"Main","focalLength":6.0,"isFront":false,"selected":true}],"selectedIndex":0}""")
                request.method == "GET" && path == "/api/stream/clients" -> ok(clientsJson(streamClients.get()))
                request.method == "GET" && path == "/api/audit" -> ok(auditEntries.get())
                request.method == "DELETE" && path.startsWith("/api/stream/clients/") -> {
                    // The device's own answer: the kicked client is gone, so
                    // every roster read AFTER the delete reflects the removal.
                    streamClients.getAndUpdate { current -> current - path.substringAfterLast('/') }
                    ok(kickAnswer)
                }
                request.method == "POST" && path == "/api/system/restart" -> ok("""{"success":true,"mode":"soft"}""")
                request.method == "POST" && path == "/api/system/reset-camera" -> ok("""{"success":true}""")
                request.method == "GET" && path == "/api/settings/export" -> ok(exportEnvelope)
                request.method == "POST" && path == "/api/settings/import" -> ok("""{"success":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun systemJson(): String = "{" +
        "\"appVersion\":\"0.2.3\",\"deviceModel\":\"Pixel\",\"deviceManufacturer\":\"Google\"," +
        "\"androidVersion\":\"14\",\"sdkInt\":34,\"osUptimeMs\":120000,\"processUptimeMs\":60000," +
        "\"battery\":{\"level\":66,\"isCharging\":true}," +
        "\"storage\":{\"usedBytes\":1,\"quotaBytes\":2,\"freeBytes\":3,\"totalBytes\":4}}"

    private fun newScope(parent: Job): CoroutineScope =
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    private val timings = ConsoleTimings(settingsPollMs = 50)

    private fun source(
        parent: Job,
        api: LenscastApiClient? = LenscastApiClient(device) { credentials.get(device.id) },
        canManage: Boolean = true,
    ) = RemoteDiagnosticsSource(
        api = api,
        canManage = canManage,
        timings = timings,
        scope = newScope(parent),
    )

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

    @Test
    fun `the beat lands info, lenses, clients, and the first audit page`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            val info = withTimeout(5_000) { src.info.first { it != null } }!!
            assertEquals("0.2.3", info.appVersion)
            assertEquals("Google Pixel", "${info.deviceManufacturer} ${info.deviceModel}")
            val lenses = withTimeout(5_000) { src.lenses.first { it != null } }!!
            assertEquals(1, lenses.lenses.size)
            val clients = withTimeout(5_000) { src.clients.first { it != null } }!!
            assertEquals(listOf("192.168.1.5"), clients.httpClients)
            val audit = withTimeout(5_000) { src.audit.first { it != null } }!!
            assertEquals(50, audit.entries.size)
            assertEquals(120, audit.total)
            assertFalse(audit.exhausted)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `loadMoreAudit widens the limit and lands the exhaustion rule`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.audit.first { it != null && it.limit == 50 } }
            // The trail holds 60 entries; a widened page fetches all 60 and
            // the coverage rule (entries >= total) marks it exhausted.
            auditEntries.set(auditJson(60, 60))
            src.loadMoreAudit()
            val widened = withTimeout(5_000) { src.audit.first { it != null && it.limit == 100 } }!!
            assertEquals(60, widened.entries.size)
            assertTrue(widened.exhausted)
            awaitRequest("audit load-more") { it.contains("limit=100") }
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a server-capped widened page lands exhausted`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            // The server's own page cap: the 50-entry first page and the
            // 100-entry widened request return the SAME 50 entries while the
            // trail still claims 120 — the no-more-entries rule (entries <=
            // the previous page's size) must mark the widened page exhausted.
            auditEntries.set(auditJson(50, 120))
            withTimeout(5_000) { src.audit.first { it != null && it.limit == 50 } }
            src.loadMoreAudit()
            val widened = withTimeout(5_000) { src.audit.first { it != null && it.limit == 100 } }!!
            assertEquals(50, widened.entries.size)
            assertEquals(120, widened.total)
            assertTrue(widened.exhausted)
            awaitRequest("audit load-more") { it.contains("limit=100") }
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `kickClient DELETEs the client route and re-reads the roster`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.clients.first { it != null } }
            src.kickClient("192.168.1.5")
            awaitRequest("kick") { it.startsWith("DELETE /api/stream/clients/192.168.1.5") }
            // The mock drops the kicked client when it answers the DELETE, so
            // any roster published AFTER the delete must reflect the removal
            // — a bare non-null roster (the beat's polls land one every 50 ms)
            // would accept a pre-kick publication.
            val after = withTimeout(5_000) {
                src.clients.first { it != null && "192.168.1.5" !in it.httpClients }
            }!!
            assertTrue(after.httpClients.isEmpty())
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `restartApp rides the soft mode or the bare route and resetCamera its route`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            src.restartApp(soft = true)
            awaitRequest("soft restart") { it == "POST /api/system/restart?mode=soft" }
            src.restartApp(soft = false)
            awaitRequest("deep restart") { it == "POST /api/system/restart" }
            src.resetCamera()
            awaitRequest("camera reset") { it == "POST /api/system/reset-camera" }
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a viewer pairing's maintenance actions fire no request`() = runBlocking {
        val parent = Job()
        val src = source(parent, canManage = false)
        try {
            assertFalse(src.canManage)
            // Reads still land — the cards render read-only.
            withTimeout(5_000) { src.clients.first { it != null } }
            requests.clear()
            src.kickClient("192.168.1.5")
            src.restartApp(soft = true)
            src.restartApp(soft = false)
            src.resetCamera()
            val imported = src.importSettings("{}")
            delay(250)
            assertFalse(imported)
            assertTrue(
                "a viewer's maintenance writes must never dial; saw: $requests",
                requests.none { it.startsWith("DELETE") || it.startsWith("POST") },
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `export returns the envelope verbatim and import posts the picked body`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.info.first { it != null } }
            // The export lands as the whole envelope document, verbatim.
            val envelope = src.exportSettings()
            assertEquals(exportEnvelope, envelope)
            val pickedBody = """{"schemaVersion":1,"app":"lenscast"}"""
            val imported = src.importSettings(pickedBody)
            assertTrue(imported)
            awaitRequest("import POST") { it.startsWith("POST /api/settings/import") }
            // The posted body is the picked string, byte for byte.
            assertEquals(pickedBody, awaitBody("import body") { it == pickedBody })
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a roster-lost source settles null and no-ops everything`() = runBlocking {
        val parent = Job()
        val src = source(parent, api = null)
        try {
            delay(150)
            assertNull(src.info.first())
            assertNull(src.audit.first())
            assertNull(src.clients.first())
            src.kickClient("x")
            src.restartApp(soft = true)
            src.resetCamera()
            src.loadMoreAudit()
            assertNull(src.exportSettings())
            assertFalse(src.importSettings("{}"))
            delay(150)
            assertTrue(requests.isEmpty())
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `load-more on an exhausted page is a no-op`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            // A fully-covered trail: the first page (50 asked) comes back
            // complete, so the block lands exhausted and load-more refuses.
            auditEntries.set(auditJson(10, 10))
            withTimeout(5_000) { src.audit.first { it != null && it.exhausted } }
            src.loadMoreAudit()
            delay(300)
            // The widened request is the only wire shape load-more could
            // dial — match it exactly, so any ordinary background audit
            // read (at the current limit) cannot satisfy the check.
            assertFalse(
                "load-more on an exhausted page must not dial; saw: $requests",
                requests.any { it.startsWith("GET /api/audit") && it.contains("limit=100") },
            )
        } finally {
            parent.cancel()
        }
    }
}
