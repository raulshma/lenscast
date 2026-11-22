package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
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
import java.util.concurrent.atomic.AtomicBoolean
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
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The remote automation backend's rules, one MockWebServer round trip per
 * verdict — real threads (`runBlocking`, the RemoteGallerySourceTest
 * convention):
 *
 * - the document poll lands on subscription, with the wire-vocabulary rule
 *   ids (`time_of_day`, `motion_detected`, `any`, `window`) intact — the
 *   AutomationRuleJson adapters' proof, since a plain-AppJson decode would
 *   fail right here;
 * - `lastFiredKeys` rides the document's `lastFiredAt` decoration;
 * - every write is a whole-document PUT whose answer — the persisted
 *   sanitized truth — becomes the new state, with the toggle merged against
 *   the last known document;
 * - the Automation Test arms via the wire route and re-fetches the document
 *   (the armed id lands immediately), restore likewise;
 * - a read-only role's writes fire no request at all;
 * - a failed write leaves the last known document for the beat;
 * - the roster-lost device settles at the local defaults.
 */
class RemoteAutomationSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    /** The document the dispatcher serves for GET (and answers PUT with, unless overridden). */
    private val docJson = AtomicReference(automationDocJson())

    /** The PUT answer override; null serves the current document. */
    private val putAnswerJson = AtomicReference<String?>(null)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = automationDispatcher()
        server.start()
        filesDir = createTempDir("automation-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-automation",
            name = "Automation",
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

    /**
     * The device's automation document, wire-vocabulary ids verbatim (the
     * contract fixture's shape). The read-only `limits`/`catalog` blocks ride
     * along and decode-ignore — the client mirrors only what it consumes.
     */
    private fun automationDocJson(armedRuleId: String? = null): String {
        val armedField = "\"testArmedRuleId\":" +
            (armedRuleId?.let { "\"$it\"" } ?: "null") + ","
        return "{" +
            "\"enabled\":true," +
            "\"rules\":[" +
            "{\"id\":\"rule-time\",\"name\":\"Morning torch\",\"enabled\":true," +
            "\"triggerType\":\"time_of_day\",\"minuteOfDay\":420,\"daysMask\":31," +
            "\"triggerParams\":{}," +
            "\"conditions\":[{\"type\":\"charging\",\"params\":{\"charging\":\"true\"}}]," +
            "\"conditionMode\":\"all\",\"cooldownSeconds\":60," +
            "\"missedRunPolicy\":\"window\",\"missedWindowMinutes\":30," +
            "\"actions\":[{\"type\":\"torch\",\"params\":{\"enabled\":\"true\"}}]," +
            "\"lastFiredAt\":1793862000000}," +
            "{\"id\":\"rule-motion\",\"name\":\"Night motion\",\"enabled\":false," +
            "\"triggerType\":\"motion_detected\",\"minuteOfDay\":0,\"daysMask\":127," +
            "\"triggerParams\":{},\"conditions\":[],\"conditionMode\":\"any\"," +
            "\"cooldownSeconds\":30,\"missedRunPolicy\":\"catch_up\",\"missedWindowMinutes\":60," +
            "\"actions\":[{\"type\":\"siren\",\"params\":{\"enabled\":\"true\"}}]}]," +
            armedField +
            "\"limits\":{\"maxRules\":12}," +
            "\"catalog\":[],\"triggers\":[],\"conditions\":[]}"
    }

    /**
     * The device lane: GETs answer the current document; the test arm/restore
     * POSTs flip the document (as the device's own state would) before
     * answering — so the source's post-write refresh reads the armed truth.
     */
    private fun automationDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add("${request.method} ${request.path.orEmpty()}")
            request.body?.let { bodies.add(it.readUtf8()) }
            val path = request.path.orEmpty().substringBefore('?')
            return when {
                request.method == "GET" && path == "/api/automation" -> ok(docJson.get())
                request.method == "PUT" && path == "/api/automation" -> {
                    // A PUT's answer — the persisted sanitized truth — IS the
                    // device's new document: store it so subsequent GETs read
                    // the persisted state (a poll must not undo an
                    // acknowledged write by re-serving the pre-write document).
                    val answer = putAnswerJson.get() ?: docJson.get()
                    docJson.set(answer)
                    ok(answer)
                }
                request.method == "POST" && path == "/api/automation/test" -> {
                    docJson.set(automationDocJson(armedRuleId = "rule-time"))
                    ok("""{"success":true,"armedRuleId":"rule-time"}""")
                }
                request.method == "POST" && path == "/api/automation/test/restore" -> {
                    docJson.set(automationDocJson())
                    ok("""{"success":true}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun newScope(parent: Job): CoroutineScope =
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    /** The shrunken beat: the document poll lands in milliseconds. */
    private val timings = ConsoleTimings(automationPollMs = 50)

    /**
     * A parked beat: the interval outlives every test timeout, so after the
     * subscription's first fetch no poll can fire — the tests below use it
     * to prove a document change lands ONLY through the round trip that
     * caused it (a poll would otherwise reconcile it within one beat).
     */
    private val parkedTimings = ConsoleTimings(automationPollMs = 60_000)

    private fun source(
        parent: Job,
        api: LenscastApiClient? = LenscastApiClient(device) { credentials.get(device.id) },
        canEdit: Boolean = true,
        timings: ConsoleTimings = this.timings,
    ) = RemoteAutomationSource(
        api = api,
        canEdit = canEdit,
        timings = timings,
        scope = newScope(parent),
    )

    /**
     * Waits for a wire request matching [predicate] — the write round trips
     * run on the source's scope, so a plain list read would race them.
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

    @Test
    fun `the document poll lands on subscription with the wire-vocabulary rules intact`() =
        runBlocking {
            val parent = Job()
            val src = source(parent)
            try {
                val rules = withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
                assertEquals(listOf("rule-time", "rule-motion"), rules.map { it.id })
                // The vocabulary ids decoded through the wire adapters — not
                // folded to the unknown fallback, not a crash.
                assertEquals(AutomationTriggerKind.TIME_OF_DAY, rules[0].triggerType)
                assertEquals(AutomationTriggerKind.MOTION_DETECTED, rules[1].triggerType)
                assertEquals("window", rules[0].missedRunPolicy.id)
                // Wait on the value arriving, not the current snapshot — the
                // fetched document carries enabled=true.
                assertTrue(withTimeout(5_000) { src.automationEnabled.first { it } })

                // The read-only `lastFiredAt` decoration lands in the map the
                // cards decode (the threshold pass-through).
                val fired = withTimeout(5_000) { src.lastFiredKeys.first { it.isNotEmpty() } }
                assertEquals(mapOf("rule-time" to 1_793_862_000_000L), fired)
                assertNull(withTimeout(5_000) { src.armedTestRuleId.first { it == null } })
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `saveRules PUTs the whole document with the toggle merged in`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
            // The PUT's answer is the persisted sanitized truth — serve a
            // one-rule document so the test sees the answer become the new
            // state (not the request echo).
            putAnswerJson.set(
                "{" +
                    "\"enabled\":true,\"rules\":[" +
                    "{\"id\":\"rule-only\",\"name\":\"Only\",\"enabled\":true," +
                    "\"triggerType\":\"time_of_day\",\"minuteOfDay\":60,\"daysMask\":127," +
                    "\"triggerParams\":{},\"conditions\":[],\"conditionMode\":\"all\"," +
                    "\"cooldownSeconds\":30,\"missedRunPolicy\":\"catch_up\",\"missedWindowMinutes\":60," +
                    "\"actions\":[{\"type\":\"stream\",\"params\":{\"enabled\":\"true\"}}]}]}",
            )
            src.saveRules(listOf(AutomationRule(id = "rule-only", name = "Only")))
            // The PUT's answer — the persisted sanitized truth — becomes the
            // new state: match on the response-decoded minuteOfDay (60), a
            // value only the response carries (the request's rule rides the
            // constructor defaults), not on the shared rule id the request
            // body echoes too.
            val persisted = withTimeout(5_000) {
                src.automationRules.first { it.singleOrNull()?.minuteOfDay == 60 }
            }
            assertEquals("rule-only", persisted.single().id)
            awaitRequest("automation PUT") { it.startsWith("PUT /api/automation") }
            val body = awaitBody("automation PUT") { it.contains("\"id\":\"rule-only\"") }
            // The last known document's toggle merged in; the new rules rode whole.
            assertTrue(body.contains("\"enabled\":true"))
            // The read-only decoration never rides a write.
            assertFalse(body.contains("lastFiredAt"))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `setEnabled PUTs the flipped toggle over the last known rules`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
            // The device answers with the persisted (now disabled) document.
            putAnswerJson.set(automationDocJson().replaceFirst("\"enabled\":true", "\"enabled\":false"))
            src.setEnabled(false)
            val body = awaitBody("automation PUT") { it.contains("\"rules\"") }
            assertTrue(body.contains("\"enabled\":false"))
            // The rules half merged from the document (the PUT carries both).
            assertTrue(body.contains("\"id\":\"rule-time\""))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `runRuleTest POSTs the rule id and the armed id lands from the refreshed document`() =
        runBlocking {
            val parent = Job()
            // The beat is parked: no poll may observe the dispatcher's
            // armed-document flip — the armed id below can only arrive
            // through the arm's own refresh, so the assertion fails if the
            // command stops re-fetching.
            val src = source(parent, timings = parkedTimings)
            try {
                withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
                src.runRuleTest("rule-time")
                awaitRequest("automation test arm") { it.startsWith("POST /api/automation/test") }
                // The arm's body named the rule.
                assertTrue(awaitBody("automation test arm") { it.contains("ruleId") }
                    .contains("rule-time"))
                // The dispatcher flipped the document (armed) before answering;
                // the source's post-write refresh reads the armed id.
                assertEquals(
                    "rule-time",
                    withTimeout(5_000) { src.armedTestRuleId.first { it != null } },
                )
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `restoreRuleTest rides the restore route and clears the armed id`() = runBlocking {
        docJson.set(automationDocJson(armedRuleId = "rule-time"))
        val parent = Job()
        // Parked beat, same rule as the arm test: the cleared armed id can
        // only arrive through the restore's own refresh.
        val src = source(parent, timings = parkedTimings)
        try {
            withTimeout(5_000) { src.armedTestRuleId.first { it == "rule-time" } }
            src.restoreRuleTest()
            awaitRequest("automation test restore") {
                it.startsWith("POST /api/automation/test/restore")
            }
            // The dispatcher flipped the document back (nothing armed) before
            // answering; the source's post-write refresh reads it.
            assertNull(withTimeout(5_000) { src.armedTestRuleId.first { it == null } })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a read-only role's writes fire no request`() = runBlocking {
        val parent = Job()
        val src = source(parent, canEdit = false)
        try {
            // Reads still land — the rules render as the device runs them.
            withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
            assertFalse(src.canEdit)
            src.setEnabled(false)
            src.saveRules(emptyList())
            src.runRuleTest("rule-time")
            src.restoreRuleTest()
            delay(250)
            // The beats keep GETting; verdict by verb, not count.
            assertTrue(requests.none { it.startsWith("POST") || it.startsWith("PUT") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a roster-lost source settles at the local defaults and no-ops every write`() =
        runBlocking {
            val parent = Job()
            val src = source(parent, api = null)
            try {
                delay(150) // the beat has nowhere to dial
                assertFalse(src.automationEnabled.first())
                assertTrue(src.automationRules.first().isEmpty())
                assertTrue(src.lastFiredKeys.first().isEmpty())
                assertNull(src.armedTestRuleId.first())
                src.setEnabled(true)
                src.saveRules(emptyList())
                src.runRuleTest("x")
                src.restoreRuleTest()
                delay(150)
                assertTrue(requests.isEmpty())
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `a failed write leaves the last known document for the beat`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        val getsHeld = CountDownLatch(1)
        try {
            withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
            // PUTs 403 (the viewer-role denial the device would answer with);
            // GETs hold behind a latch — a successful poll must not be able
            // to restore the rules while the failed write's verdict is
            // checked, so the retention below proves the failure handling
            // preserved them independently of polling.
            val putDenied = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path.orEmpty()}")
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        request.method == "PUT" && path == "/api/automation" -> {
                            putDenied.countDown()
                            MockResponse().setResponseCode(403)
                        }
                        else -> {
                            getsHeld.await()
                            ok(docJson.get())
                        }
                    }
                }
            }
            val write = src.saveRules(emptyList())
            // Synchronize on the failed PUT itself: the denial landed before
            // any rule-retention verdict is read.
            assertTrue(putDenied.await(5_000, TimeUnit.MILLISECONDS))
            // The write handle is the observable end of the failed PUT's
            // response handling — it completes (exceptionally) only after the
            // source has settled the denial, so awaiting its failure removes
            // any reliance on timing before the retention check.
            val verdict = runCatching { withTimeout(5_000) { write.await() } }
            assertFalse("the denied write must surface a failure", verdict.isSuccess)
            // The device's truth (two rules) still renders — no optimistic wipe.
            assertEquals(2, src.automationRules.first().size)
            // Release the reads; the beat reconciles with the device's truth.
            val requestsBeforeRelease = requests.size
            getsHeld.countDown()
            withTimeout(5_000) {
                while (requests.size == requestsBeforeRelease) delay(20)
            }
            assertEquals(2, src.automationRules.first().size)
        } finally {
            parent.cancel()
            // Un-block any dispatcher thread still held, for a clean shutdown.
            getsHeld.countDown()
        }
    }

    @Test
    fun `a write whose answer is lost after persistence makes the next save reconcile`() =
        runBlocking {
            val parent = Job()
            val src = source(parent)
            val firstPutDropped = CountDownLatch(1)
            val firstPut = AtomicBoolean(false)
            try {
                withTimeout(5_000) { src.automationRules.first { it.isNotEmpty() } }
                // The ambiguous failure: the device ACCEPTS the toggle PUT
                // (persists enabled=false), then the connection dies before
                // any answer — the source's in-memory document still says
                // enabled=true. Every PUT dies, so the follow-up save's PUT
                // body (recorded before the drop) is the verdict.
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add("${request.method} ${request.path.orEmpty()}")
                        request.body?.let { bodies.add(it.readUtf8()) }
                        val path = request.path.orEmpty().substringBefore('?')
                        return when {
                            request.method == "PUT" && path == "/api/automation" -> {
                                if (firstPut.compareAndSet(false, true)) {
                                    docJson.set(
                                        automationDocJson()
                                            .replaceFirst("\"enabled\":true", "\"enabled\":false"),
                                    )
                                    firstPutDropped.countDown()
                                }
                                MockResponse()
                                    .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            }
                            else -> ok(docJson.get())
                        }
                    }
                }
                src.setEnabled(false)
                assertTrue(firstPutDropped.await(5_000, TimeUnit.MILLISECONDS))
                // The next write must reconcile the authoritative document
                // before merging: the saved rules ride whole, but the toggle
                // half merges from the device's committed false — not the
                // stale in-memory true.
                val save = src.saveRules(listOf(AutomationRule(id = "rule-new", name = "New", enabled = false)))
                val body = awaitBody("the reconciled save PUT") {
                    it.contains("\"id\":\"rule-new\"") && it.contains("\"enabled\":false")
                }
                assertFalse("the stale toggle must not ride the save", body.contains("\"enabled\":true"))
                // The save's own PUT died the same way: the failure surfaces
                // on the handle (the beat reconciles the persisted truth).
                assertFalse(
                    "the dropped save must surface a failure",
                    runCatching { withTimeout(5_000) { save.await() } }.isSuccess,
                )
            } finally {
                parent.cancel()
            }
        }

    // ── the pure decoration ──

    @Test
    fun `lastFiredKeys keeps only ided fired rules`() {
        val rules = listOf(
            AutomationRule(id = "a", lastFiredAt = 1_793_862_000_000L),
            AutomationRule(id = "b", lastFiredAt = null),
            AutomationRule(id = null, lastFiredAt = 5L),
            AutomationRule(id = "c", lastFiredAt = 42L),
        )
        assertEquals(mapOf("a" to 1_793_862_000_000L, "c" to 42L), rules.lastFiredKeys())
    }
}
