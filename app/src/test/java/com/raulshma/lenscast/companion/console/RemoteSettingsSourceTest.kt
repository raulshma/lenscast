package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuthConfigInfo
import com.raulshma.lenscast.companion.client.dto.RemoteCameraSettings
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import com.squareup.moshi.JsonAdapter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The settings screens' remote backend's rules, one MockWebServer round trip
 * per verdict — real threads (`runBlocking`, the RemoteAutomationSourceTest
 * convention; Robolectric is here only for [SettingsDataStore], the
 * viewer-local lanes' store — the DeviceConsoleViewModelTest pattern):
 *
 * - the document poll lands and every DEVICE-side lane maps from the doc
 *   (the streaming section field-for-field, the camera section through the
 *   RemoteSettingsMapper, the status ranges through the shared mappers);
 * - a streaming write is the whole-section GET-merge-PUT (the untouched
 *   siblings ride the fresh doc, the PUT's merged answer becomes confirmed
 *   state, and a failed write leaves the doc for the beat);
 * - a camera write PUTs the camera section whole with the streaming section
 *   OMITTED (null = untouched server-side), and a zoom-only change rides
 *   /api/camera/zoom instead (the mapper's exclusion contract);
 * - Reset PUTs the default camera section, streaming untouched;
 * - a viewer pairing's device writes fire no write request at all;
 * - the PARTITION: the viewer-local lanes (theming, viewfinder aids) read
 *   and write the LOCAL store only — their writes dial nothing and the
 *   device document never moves them;
 * - the auth section rides /api/auth/config (enabled + username mapped off
 *   the redacted read);
 * - the detection-model fetch rides the ml-model route and refreshes the doc.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteSettingsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice
    private lateinit var settingsStore: SettingsDataStore

    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    /** The document the dispatcher serves for GET (accepted PUTs merge into it). */
    private val docJson = AtomicReference(settingsDocJson())

    /** The device's redacted auth-config read (accepted auth PUTs merge into it). */
    private val authConfigJson = AtomicReference(authConfigFixtureJson())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = settingsDispatcher()
        server.start()
        filesDir = createTempDir("settings-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-settings",
            name = "Settings",
            host = "127.0.0.1",
            port = server.port,
            auth = CompanionAuthKind.TOKEN,
            role = "admin",
            addedAtMs = 0L,
        ).also { roster.upsert(it) }
        credentials.put(device.id, StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
        settingsStore = SettingsDataStore(RuntimeEnvironment.getApplication())
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
     * The device's settings document: a NON-default streaming section (so a
     * wholesale PUT that dropped siblings would fail the test loudly) and a
     * non-default camera section.
     */
    private fun settingsDocJson(): String {
        val streaming = StreamingSettings(
            port = 9100,
            jpegQuality = 88,
            motionDetectionEnabled = true,
            motionSensitivityPercent = 61,
            mlDetectionEnabled = true,
            mlMinScorePercent = 72,
            mlIncludePerson = false,
            tamperDetectionEnabled = true,
            watchdogEnabled = true,
            watchdogMaxRetries = 9,
            rtspEnabled = true,
            rtspPort = 9554,
            mdnsEnabled = false,
            httpsEnabled = true,
            keepAwakeEnabled = false,
            soundDetectionEnabled = true,
            soundThresholdPercent = 44,
            storageQuotaMb = 8_192,
            continuousRecording = true,
            continuousSegmentMinutes = 22,
            hlsDvrSegments = 60,
            ecoIdleFpsEnabled = true,
        )
        val camera = RemoteCameraSettings(iso = 1600, resolution = "UHD_4K", frameRate = 30)
        return AppJson.moshi.adapter(AllSettings::class.java).toJson(
            AllSettings(camera = camera, streaming = streaming),
        )
    }

    /** The device's auth-config GET answer (the redacted read, the viewer pair on). */
    private fun authConfigFixtureJson() =
        "{\"enabled\":true,\"username\":\"admin\",\"password\":\"\",\"viewerEnabled\":true," +
            "\"viewerUsername\":\"viewer\"}"

    /**
     * The device lane: GETs answer the doc; an accepted PUT PERSISTS — the
     * sent sections merge into [docJson] / [authConfigJson] (the device's
     * sent-key merge) before the answer leaves — so the beat's next GET
     * reads the write back; status carries the ranges.
     */
    private fun settingsDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add("${request.method} ${request.path.orEmpty()}")
            val body = request.body?.readUtf8().orEmpty()
            val path = request.path.orEmpty().substringBefore('?')
            val response = when {
                request.method == "GET" && path == "/api/settings" -> ok(docJson.get())
                request.method == "PUT" && path == "/api/settings" -> ok(persistSettingsPut(body))
                request.method == "POST" && path == "/api/settings/ml-model/download" ->
                    ok("""{"success":true}""")
                request.method == "POST" && path == "/api/settings/audio-model/download" ->
                    ok("""{"success":true}""")
                request.method == "POST" && path == "/api/camera/zoom" -> ok("""{"success":true}""")
                request.method == "GET" && path == "/api/status" -> ok(statusJson())
                request.method == "GET" && path == "/api/auth/config" -> ok(authConfigJson.get())
                request.method == "PUT" && path == "/api/auth/config" ->
                    ok(persistAuthConfigPut(body))
                else -> MockResponse().setResponseCode(404)
            }
            // Recorded only once this PUT's persistence (if any) has landed:
            // an observed body means a subsequent GET already serves the
            // merged document.
            bodies.add(body)
            return response
        }
    }

    /**
     * The accepted settings PUT persists (the device's section merge —
     * [SettingsWebHandler.mergeSection] mirrored onto the companion's own
     * wire adapters): each present section's raw JSON keys overlay the
     * stored section, so the beat's next GET answers the merged document
     * instead of the pre-PUT fixture. A malformed body keeps the stored
     * document (the verdict is success either way — the tests only ever
     * send what the typed client builds).
     */
    private fun persistSettingsPut(body: String): String {
        runCatching {
            val raw = AppJson.moshi.adapter(Any::class.java).fromJson(body) as? Map<*, *>
                ?: return@runCatching
            val all = AppJson.moshi.adapter(AllSettings::class.java)
            val stored = all.fromJson(docJson.get()) ?: return@runCatching
            docJson.set(
                all.toJson(
                    AllSettings(
                        camera = mergeSection(
                            AppJson.moshi.adapter(RemoteCameraSettings::class.java),
                            stored.camera,
                            raw["camera"],
                        ),
                        streaming = mergeSection(
                            AppJson.moshi.adapter(StreamingSettings::class.java),
                            stored.streaming,
                            raw["streaming"],
                        ),
                    ),
                ),
            )
        }
        return """{"success":true}"""
    }

    /**
     * The accepted auth-config PUT persists: the sent admin pair and the
     * sent viewer pair (a null field = keep the stored one) merge into the
     * served config, so the beat's next GET reads the write back. The stored
     * hashes never echo — the GET keeps answering the blank password.
     */
    private fun persistAuthConfigPut(body: String): String {
        runCatching {
            val adapter = AppJson.moshi.adapter(AuthConfigInfo::class.java)
            val sent = adapter.fromJson(body) ?: return@runCatching
            val stored = adapter.fromJson(authConfigJson.get()) ?: return@runCatching
            authConfigJson.set(
                adapter.toJson(
                    stored.copy(
                        enabled = sent.enabled,
                        username = sent.username ?: stored.username,
                        viewerEnabled = sent.viewerEnabled ?: stored.viewerEnabled,
                        viewerUsername = sent.viewerUsername ?: stored.viewerUsername,
                    ),
                ),
            )
        }
        return """{"success":true}"""
    }

    /**
     * The device's section merge ([SettingsWebHandler.mergeSection]): the
     * incoming section's raw JSON keys overlay the stored section's value
     * tree — omitted keys keep the stored value, an explicit null overlays
     * as null — then the section's own adapter re-decodes the tree.
     */
    private fun <T : Any> mergeSection(adapter: JsonAdapter<T>, stored: T, incoming: Any?): T {
        if (incoming !is Map<*, *>) return stored
        val merged = adapter.toJsonValue(stored) as MutableMap<Any?, Any?>
        for ((key, value) in incoming) merged[key] = value
        return adapter.fromJsonValue(merged) ?: stored
    }

    /** The status read the ranges lane maps (DeviceStatus's required fields + ranges). */
    private fun statusJson(): String = "{" +
        "\"streaming\":{\"isActive\":true,\"url\":\"http://x/stream\",\"webStreamingEnabled\":true," +
        "\"webStreamingActive\":true,\"clientCount\":0,\"audioEnabled\":true,\"audioUrl\":\"/audio\"," +
        "\"rtspEnabled\":false,\"rtspStreamingActive\":false,\"rtspUrl\":\"\"}," +
        "\"thermal\":\"NORMAL\"," +
        "\"battery\":{\"level\":80,\"isCharging\":false,\"isPowerSaveMode\":false}," +
        "\"camera\":\"ready\",\"torchOn\":false,\"sirenActive\":false,\"zoomRatio\":1.0," +
        "\"zoomRange\":{\"min\":1.0,\"max\":6.0}," +
        "\"isoRange\":{\"min\":100.0,\"max\":1600.0}," +
        "\"exposureCompensationRange\":{\"min\":-6.0,\"max\":6.0}}"

    private fun newScope(parent: Job): CoroutineScope =
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    /** The shrunken beat: the document poll lands in milliseconds. */
    private val timings = ConsoleTimings(settingsPollMs = 50)

    private fun source(
        parent: Job,
        api: LenscastApiClient? = LenscastApiClient(device) { credentials.get(device.id) },
        canEdit: Boolean = true,
    ) = RemoteSettingsSource(
        api = api,
        canEdit = canEdit,
        localSettings = settingsStore,
        timings = timings,
        scope = newScope(parent),
    )

    /** A fresh client for the device — the read-back GETs the writes must survive. */
    private fun readBackClient(): LenscastApiClient =
        LenscastApiClient(device) { credentials.get(device.id) }

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

    /**
     * Waits for a document-derived flow to reach [expected] — the stateIn
     * chains start at the wire defaults, so a plain `first()` would race the
     * doc's map landing (the conflation trap; the value only ever moves
     * toward the doc here, so the predicate always terminates).
     */
    private suspend fun <T> awaitFlow(expected: T, flow: StateFlow<T>): T =
        withTimeout(5_000) { flow.first { it == expected } }

    // ── the document poll + the device-side mapping ──

    @Test
    fun `the document poll lands and every device-side lane maps from the doc`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            assertEquals(9100, awaitFlow(9100, src.streamingPort))
            assertEquals(88, awaitFlow(88, src.jpegQuality))
            assertEquals(true, awaitFlow(true, src.motionDetectionEnabled))
            assertEquals(61, awaitFlow(61, src.motionSensitivityPercent))
            assertEquals(true, awaitFlow(true, src.mlDetectionEnabled))
            assertEquals(72, awaitFlow(72, src.mlMinScorePercent))
            assertEquals(false, awaitFlow(false, src.mlIncludePerson))
            assertEquals(true, awaitFlow(true, src.tamperDetectionEnabled))
            assertEquals(true, awaitFlow(true, src.watchdogEnabled))
            assertEquals(9, awaitFlow(9, src.watchdogMaxRetries))
            assertEquals(true, awaitFlow(true, src.rtspEnabled))
            assertEquals(9554, awaitFlow(9554, src.rtspPort))
            assertEquals(false, awaitFlow(false, src.mdnsEnabled))
            assertEquals(true, awaitFlow(true, src.httpsEnabled))
            assertEquals(false, awaitFlow(false, src.keepAwakeEnabled))
            assertEquals(true, awaitFlow(true, src.soundDetectionEnabled))
            assertEquals(44, awaitFlow(44, src.soundThresholdPercent))
            assertEquals(8192, awaitFlow(8192, src.storageQuotaMb))
            assertEquals(true, awaitFlow(true, src.continuousRecording))
            assertEquals(22, awaitFlow(22, src.continuousSegmentMinutes))
            assertEquals(60, awaitFlow(60, src.hlsDvrSegments))
            assertEquals(true, awaitFlow(true, src.ecoIdleFpsEnabled))
            // The camera section maps through the shared mapper.
            val settings = withTimeout(5_000) { src.settings.first { it.iso == 1600 } }
            assertEquals(30, settings.frameRate)
            // The status lane mapped the live ranges (the shared mappers).
            assertEquals(
                100..1600,
                withTimeout(5_000) { src.availableIsoRange.first { it == 100..1600 } },
            )
            assertEquals(1f..6f, src.availableZoomRange.first().start..src.availableZoomRange.first().endInclusive)
            assertEquals(-6..6, src.availableExposureRange.first())
            // The redacted auth read mapped (its flow drives the same beat).
            val auth = withTimeout(5_000) { src.authSettings.first { it.enabled } }
            assertEquals("admin", auth.username)
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a streaming write GET-merge-PUTs the whole section and adopts the merged answer`() =
        runBlocking {
            val parent = Job()
            val src = source(parent)
            try {
                withTimeout(5_000) { src.jpegQuality.first { it == 88 } }
                src.updateMotionDetectionEnabled(false)
                val put = awaitBody("settings PUT") { it.contains("\"port\":9100") }
                // The edited field rode the fresh document's siblings — a
                // wholesale section, not a hand-built partial.
                assertTrue(put.contains("\"jpegQuality\":88"))
                assertTrue(put.contains("\"motionDetectionEnabled\":false"))
                assertTrue(put.contains("\"mlMinScorePercent\":72"))
                // The merged doc became confirmed state without waiting a beat.
                assertFalse(withTimeout(5_000) { src.motionDetectionEnabled.first { !it } })
                // The device kept the write: a subsequent GET reads it back —
                // persistence, not just this write's own adoption.
                assertFalse(readBackClient().getSettings().streaming.motionDetectionEnabled)
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `a camera write PUTs the camera section whole and omits the streaming section`() =
        runBlocking {
            val parent = Job()
            val src = source(parent)
            try {
                withTimeout(5_000) { src.settings.first { it.iso == 1600 } }
                src.updateQuickSetting(QuickSettingType.ISO, "3200")
                val put = awaitBody("camera-section PUT") { it.contains("\"camera\"") }
                assertTrue(put.contains("\"iso\":3200"))
                assertTrue(put.contains("\"resolution\":\"UHD_4K\""))
                // The streaming section is null on the update (omitted from
                // the wire) — the device's streaming config is untouched.
                assertFalse(put.contains("\"streaming\""))
                assertEquals(
                    3200,
                    withTimeout(5_000) { src.settings.first { it.iso == 3200 } }.iso,
                )
                // The device kept the camera section: a subsequent GET reads
                // it back — persistence, not just this write's own adoption.
                assertEquals(3200, readBackClient().getSettings().camera.iso)
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `a zoom-only change rides the zoom endpoint and never PUTs settings`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.settings.first { it.iso == 1600 } }
            bodies.clear()
            requests.clear()
            src.updateQuickSetting(QuickSettingType.ZOOM, 3.0f)
            awaitRequest("zoom POST") { it.startsWith("POST /api/camera/zoom") }
            delay(200)
            assertTrue(
                "a zoom-only edit must not emit a settings PUT; saw: $requests",
                requests.none { it.startsWith("PUT /api/settings") },
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `reset PUTs the default camera section with streaming untouched`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.settings.first { it.iso == 1600 } }
            src.resetToDefaults()
            val put = awaitBody("reset PUT") { it.contains("\"camera\"") }
            // The app defaults came through (the doc's non-defaults gone).
            assertTrue(put.contains("\"resolution\":\"FHD_1080P\""))
            assertFalse(put.contains("\"iso\":1600"))
            // The cleared ISO rides as an EXPLICIT JSON null — the camera
            // section is written with serializeNulls ON and the server merges
            // at the sent-key level, so an omitted key would keep the stored
            // iso=1600 instead of resetting it.
            assertTrue(put.contains("\"iso\":null"))
            // The streaming section is null on the update — untouched.
            assertFalse(put.contains("\"streaming\""))
            // The device kept the reset: a subsequent GET reads the defaults
            // back (and the streaming section it never received stays).
            val readBack = readBackClient().getSettings()
            assertEquals("FHD_1080P", readBack.camera.resolution)
            assertNull(readBack.camera.iso)
            assertEquals(9100, readBack.streaming.port)
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a viewer pairing's device writes fire no write request`() = runBlocking {
        val parent = Job()
        val src = source(parent, canEdit = false)
        try {
            // Reads still land — the screen renders the device's truth.
            withTimeout(5_000) { src.jpegQuality.first { it == 88 } }
            assertFalse(src.canEdit)
            src.updateMotionDetectionEnabled(false)
            src.updateJpegQuality(10)
            src.updateQuickSetting(QuickSettingType.ISO, "3200")
            src.resetToDefaults()
            src.downloadDetectionModel()
            src.updateAuthEnabled(false)
            delay(250)
            // The beats keep GETting; the verdict is by verb.
            assertTrue(requests.none { it.startsWith("PUT") || it.startsWith("POST") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `viewer-local lanes ride the local store and never dial the device`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            // The viewer-local flows read THIS phone's store (never the doc).
            settingsStore.saveDynamicThemingEnabled(false)
            settingsStore.savePeakingEnabled(true)
            assertFalse(withTimeout(5_000) { src.dynamicThemingEnabled.first { !it } })
            assertTrue(withTimeout(5_000) { src.peakingEnabled.first { it } })
            assertTrue(requests.isEmpty())
            // Their writes flip the local store and dial nothing.
            requests.clear()
            src.updateDynamicThemingEnabled(true)
            src.updatePeakingEnabled(false)
            src.updateShowPreview(false)
            src.updateGridStyle("GRID_3X3")
            withTimeout(5_000) { src.dynamicThemingEnabled.first { it } }
            withTimeout(5_000) { src.peakingEnabled.first { !it } }
            delay(150)
            assertTrue(
                "viewer-local writes must never dial; saw: $requests",
                requests.isEmpty(),
            )
            // …and the device document never carried these lanes' writes.
            assertTrue(bodies.none { it.contains("dynamicTheming") })
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a failed streaming write leaves the last known document for the beat`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        // The gate that parks the 403 until the test has observed the
        // optimistic echo; released again in the finally so a failed
        // assertion cannot hang the write (or the server shutdown).
        val release403 = CountDownLatch(1)
        try {
            withTimeout(5_000) { src.jpegQuality.first { it == 88 } }
            // Serve motion OFF first, so the final assertion cannot pass
            // merely because the document already carried it enabled.
            docJson.set(
                settingsDocJson().replace(
                    "\"motionDetectionEnabled\":true",
                    "\"motionDetectionEnabled\":false",
                ),
            )
            withTimeout(5_000) { src.motionDetectionEnabled.first { !it } }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path.orEmpty()}")
                    request.body?.let { bodies.add(it.readUtf8()) }
                    val path = request.path.orEmpty().substringBefore('?')
                    return if (request.method == "PUT" && path == "/api/settings") {
                        // The 403 stays parked on the gate until the test has
                        // observed the optimistic echo — the write is still in
                        // flight, so the failure handling cannot outrun the
                        // assertions, and the release below is what actually
                        // delivers the failure. (The timeout only bounds a
                        // failed run's wait; the finally releases regardless.)
                        release403.await(10, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(403)
                    } else {
                        ok(docJson.get())
                    }
                }
            }
            src.updateMotionDetectionEnabled(true)
            // The attempted write really left, carrying the edit (and its 403
            // answer is what the gate is withholding below).
            awaitBody("settings PUT with the edit") {
                it.contains("\"motionDetectionEnabled\":true")
            }
            // The optimistic echo renders while the write is in flight —
            // gated, so this observation cannot race the 403's arrival.
            assertTrue(withTimeout(5_000) { src.motionDetectionEnabled.first { it } })
            release403.countDown()
            // Only the failure handling — the catch dropping the failed edit
            // from the optimistic overlay and re-reading the device — can
            // publish motion=false again: while the edit is pending, the
            // overlay renders true no matter how many polls land.
            assertFalse(withTimeout(5_000) { src.motionDetectionEnabled.first { !it } })
        } finally {
            release403.countDown()
            parent.cancel()
        }
    }

    @Test
    fun `the detection-model fetch rides the ml-model route and refreshes the doc`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.jpegQuality.first { it == 88 } }
            src.downloadDetectionModel()
            awaitRequest("ml-model POST") { it.startsWith("POST /api/settings/ml-model/download") }
            // The response-only model state maps off the doc's fields —
            // flip the served doc's own mlModelState token.
            docJson.set(
                settingsDocJson().replace(
                    "\"mlModelState\":\"not_downloaded\"",
                    "\"mlModelState\":\"ready\"",
                ),
            )
            assertEquals(
                com.raulshma.lenscast.capture.ml.DetectionModelStore.State.Ready::class,
                withTimeout(5_000) {
                    src.detectionModelState.first { it is com.raulshma.lenscast.capture.ml.DetectionModelStore.State.Ready }
                }::class,
            )
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a roster-lost source settles at the wire defaults and no-ops every write`() = runBlocking {
        val parent = Job()
        val src = source(parent, api = null)
        try {
            delay(150) // the beat has nowhere to dial
            assertEquals(8080, src.streamingPort.first())
            assertEquals(70, src.jpegQuality.first())
            assertNull(src.authSettings.first().takeIf { it.enabled })
            src.updateMotionDetectionEnabled(true)
            src.updateJpegQuality(10)
            src.resetToDefaults()
            src.updateDynamicThemingEnabled(true) // viewer-local: still works
            withTimeout(5_000) { src.dynamicThemingEnabled.first { it } }
            delay(150)
            assertTrue(requests.isEmpty())
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the auth writes ride the auth-config route with the viewer pair preserved`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.authSettings.first { it.enabled } }
            src.updateAuthUsername("newadmin")
            awaitRequest("auth-config PUT") { it.startsWith("PUT /api/auth/config") }
            val body = awaitBody("auth-config PUT") { it.contains("newadmin") }
            // The unchanged passwords stay out (null = keep the stored hashes)
            // and the ENABLED viewer pair is echoed whole (omitting it would
            // disable it server-side — the typed client's contract): both the
            // enabled bit and the username the redacted read reported ride.
            assertFalse(body.contains("\"password\""))
            assertTrue(body.contains("\"viewerEnabled\":true"))
            assertTrue(body.contains("\"viewerUsername\":\"viewer\""))
            withTimeout(5_000) { src.authSettings.first { it.username == "newadmin" } }
            // The device kept the credentials: a subsequent GET reads them
            // back — the admin username AND the preserved viewer pair.
            val readBack = readBackClient().authConfig()
            assertEquals("newadmin", readBack.username)
            assertEquals("viewer", readBack.viewerUsername)
            Unit
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a focus-distance clear rides the PUT as an explicit null`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.settings.first { it.iso == 1600 } }
            // The device stores a manual focus distance…
            docJson.set(settingsDocJson().replace("\"iso\":1600", "\"iso\":1600,\"focusDistance\":5.5"))
            withTimeout(5_000) { src.settings.first { it.focusDistance == 5.5f } }
            // …and a null update must CLEAR it: the key rides as an explicit
            // JSON null — the server merges a section at the sent-key level,
            // so an omitted key would keep the stored distance — while the
            // untouched camera siblings travel whole.
            src.updateFocusDistance(null)
            val put = awaitBody("focus-distance clear PUT") { it.contains("\"focusDistance\":null") }
            assertTrue(put.contains("\"iso\":1600"))
            assertTrue(put.contains("\"frameRate\":30"))
            // The device accepted: its next GET answers the cleared distance,
            // and the confirmed document adopted it.
            docJson.set(docJson.get().replace("\"focusDistance\":5.5", "\"focusDistance\":null"))
            assertNull(withTimeout(5_000) { src.settings.first { it.focusDistance == null } }.focusDistance)
        } finally {
            parent.cancel()
        }
    }
}
