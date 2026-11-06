package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric pass over the REAL [SettingsWebHandler] against the REAL
 * [SettingsDataStore] (Preferences DataStore — see setUp for the shared-file
 * caveat): the
 * write-only-secret contract the web dashboard and the export/import pair
 * depend on.
 *
 * **Covered here** (all real code paths through the Secret wire type): a GET
 * — and with it the export envelope, which wraps the same response —
 * serializes every stored credential blank no matter what the store holds; a
 * PUT with an empty (or absent) secret keeps the stored credential; a PUT
 * presenting a value overwrites it; and the apiToken's hash-and-gate — the
 * one documented exception layered on the same SecretUpdate entry point —
 * arms the token flag only when a usable credential would exist after the
 * update (fails closed on a blank hash), while the hash itself still
 * keep-or-overwrites on the presented plaintext. The pure rule itself is
 * unit-tested in SecretTest; the wire's byte-identity against the checked-in
 * fixtures is DtoContractFixtureTest's job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerSecretTest.PlainApplication::class)
class SettingsWebHandlerSecretTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

    private val responseAdapter = AppJson.moshi.adapter(SettingsResponseDto::class.java)

    /** DataStore's shared flows re-emit off the store's scope — wait until the value lands. */
    private fun awaitUntil(message: String = "condition never became true", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(10)
        }
    }

    /** Plants every stored credential; returns once the store flows carry them. */
    private suspend fun plantAllSecrets() {
        store.saveRtmpUrl("rtmp://user:pass@host/live/stream-key")
        store.saveWhipToken("whip-bearer-token")
        store.saveSrtUrl("srt://u:p@h:1000?streamid=secret-id")
        store.saveMqttPassword("mqtt-pass")
        store.saveBackupWebdavPassword("dav-pass")
        store.saveTelegramBotToken("tg-token")
        store.saveApiTokenHash("stored-token-hash")

        awaitUntil("rtmpUrl never propagated") { store.rtmpUrl.value == "rtmp://user:pass@host/live/stream-key" }
        awaitUntil("whipToken never propagated") { store.whipToken.value == "whip-bearer-token" }
        awaitUntil("srtUrl never propagated") { store.srtUrl.value == "srt://u:p@h:1000?streamid=secret-id" }
        awaitUntil("mqttPassword never propagated") { store.mqttPassword.value == "mqtt-pass" }
        awaitUntil("backupWebdavPassword never propagated") { store.backupWebdavPassword.value == "dav-pass" }
        awaitUntil("telegramBotToken never propagated") { store.telegramBotToken.value == "tg-token" }
        awaitUntil("apiTokenHash never propagated") { store.apiTokenHash.value == "stored-token-hash" }
    }

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        val detectionModelStore = mockk<DetectionModelStore>(relaxed = true)
        every { detectionModelStore.state } returns MutableStateFlow(DetectionModelStore.State.NotDownloaded)
        store = SettingsDataStore(application)
        handler = SettingsWebHandler(
            settingsDataStore = store,
            detectionModelStore = detectionModelStore,
            audioModelStore = mockk<AudioModelStore>(relaxed = true),
        )
        // The production DataStore is a process-wide singleton (the
        // top-level `preferencesDataStore` delegate binds to the first
        // context it sees), so this class's tests share one preferences
        // file in a JVM — the "per-test temp dir" a fresh constructor
        // suggests does not exist. The apiToken gate's precondition is
        // the one a leftover state breaks (an armed hash makes an empty
        // enable legitimately succeed), so every test starts from the
        // gate's defaults.
        runBlocking {
            store.saveApiTokenEnabled(false)
            store.saveApiTokenHash("")
        }
        awaitUntil("gate defaults never propagated") {
            !store.apiTokenEnabled.value && store.apiTokenHash.value == ""
        }
    }

    // ── the GET half: every secret serializes blank ──

    @Test
    fun `get serializes every stored secret blank`() = runBlocking {
        plantAllSecrets()

        val json = handler.get()

        assertTrue("wire JSON should carry \"rtmpUrl\":\"\"", json.contains("\"rtmpUrl\":\"\""))
        assertTrue("wire JSON should carry \"whipToken\":\"\"", json.contains("\"whipToken\":\"\""))
        assertTrue("wire JSON should carry \"srtUrl\":\"\"", json.contains("\"srtUrl\":\"\""))
        assertTrue("wire JSON should carry \"mqttPassword\":\"\"", json.contains("\"mqttPassword\":\"\""))
        assertTrue("wire JSON should carry \"backupWebdavPassword\":\"\"", json.contains("\"backupWebdavPassword\":\"\""))
        assertTrue("wire JSON should carry \"telegramBotToken\":\"\"", json.contains("\"telegramBotToken\":\"\""))
        assertTrue("wire JSON should carry \"apiToken\":\"\"", json.contains("\"apiToken\":\"\""))
        for (leaked in listOf(
            "rtmp://user:pass@host/live/stream-key",
            "whip-bearer-token",
            "srt://u:p@h:1000?streamid=secret-id",
            "mqtt-pass",
            "dav-pass",
            "tg-token",
            // The apiToken's stored form is only ever the hash — it must not
            // ride the wire either.
            "stored-token-hash",
        )) {
            assertFalse("the stored credential leaked onto the wire: $leaked", json.contains(leaked))
        }
        // The configured readout reflects the stored hash, and the decoded
        // response carries no secret.
        val response = responseAdapter.fromJson(json)!!
        assertTrue(response.streaming.apiTokenConfigured)
        assertEquals("", response.streaming.whipToken.value)
        assertEquals("", response.streaming.rtmpUrl.value)
    }

    @Test
    fun `export wraps the same blanked response`() = runBlocking {
        plantAllSecrets()

        val json = handler.export()

        for (leaked in listOf(
            "rtmp://user:pass@host/live/stream-key",
            "whip-bearer-token",
            "srt://u:p@h:1000?streamid=secret-id",
            "mqtt-pass",
            "dav-pass",
            "tg-token",
            "stored-token-hash",
        )) {
            assertFalse("the export leaked a stored credential: $leaked", json.contains(leaked))
        }
        assertTrue(json.contains("\"whipToken\":\"\""))
    }

    // ── the PUT half: empty/absent keeps, a value overwrites ──

    @Test
    fun `put with empty secrets keeps the stored credentials`() = runBlocking {
        plantAllSecrets()

        // The port change is the witness: a present section persists
        // wholesale, so once it lands, the PUT's writes are complete — and
        // the secrets, presented empty, were never touched.
        handler.put(
            """{"streaming":{"port":8099,"whipToken":"","mqttPassword":"",""" +
                """"backupWebdavPassword":"","telegramBotToken":"","rtmpUrl":"","srtUrl":""}}""",
        )
        awaitUntil("port never propagated") { store.streamingPort.value == 8099 }

        assertEquals("an empty PUT wiped the stored rtmpUrl", "rtmp://user:pass@host/live/stream-key", store.rtmpUrl.value)
        assertEquals("an empty PUT wiped the stored whipToken", "whip-bearer-token", store.whipToken.value)
        assertEquals("an empty PUT wiped the stored srtUrl", "srt://u:p@h:1000?streamid=secret-id", store.srtUrl.value)
        assertEquals("an empty PUT wiped the stored mqttPassword", "mqtt-pass", store.mqttPassword.value)
        assertEquals("an empty PUT wiped the stored backupWebdavPassword", "dav-pass", store.backupWebdavPassword.value)
        assertEquals("an empty PUT wiped the stored telegramBotToken", "tg-token", store.telegramBotToken.value)
        assertEquals("an empty PUT wiped the stored apiToken hash", "stored-token-hash", store.apiTokenHash.value)
    }

    @Test
    fun `put with secrets omitted keeps the stored credentials`() = runBlocking {
        plantAllSecrets()

        // Absent fields decode to the empty default — the same keep verdict
        // as an explicit "".
        handler.put("""{"streaming":{"port":8100}}""")
        awaitUntil("port never propagated") { store.streamingPort.value == 8100 }

        assertEquals("rtmp://user:pass@host/live/stream-key", store.rtmpUrl.value)
        assertEquals("whip-bearer-token", store.whipToken.value)
        assertEquals("stored-token-hash", store.apiTokenHash.value)
    }

    @Test
    fun `put round-trips the keep-awake toggle both ways`() = runBlocking {
        // Pin the start state (the class's tests share one DataStore file).
        store.saveKeepAwakeEnabled(true)
        awaitUntil("keepAwakeEnabled start state never propagated") { store.keepAwakeEnabled.value }

        handler.put("""{"streaming":{"port":8102,"keepAwakeEnabled":false}}""")
        awaitUntil("keepAwakeEnabled=off never propagated") { !store.keepAwakeEnabled.value }
        val off = responseAdapter.fromJson(handler.get())!!
        assertFalse("the GET surface kept a stale keep-awake value", off.streaming.keepAwakeEnabled)

        // …and the toggle rides back up the same wholesale-persist path.
        handler.put("""{"streaming":{"port":8103,"keepAwakeEnabled":true}}""")
        awaitUntil("keepAwakeEnabled=on never propagated") { store.keepAwakeEnabled.value }
        val on = responseAdapter.fromJson(handler.get())!!
        assertTrue(on.streaming.keepAwakeEnabled)
    }

    @Test
    fun `put with presented secrets overwrites the stored credentials`() = runBlocking {
        plantAllSecrets()

        handler.put(
            """{"streaming":{"rtmpUrl":"rtmp://n:u2@h2/live/key2","whipToken":"whip2",""" +
                """"srtUrl":"srt://n:p2@h2?streamid=id2","mqttPassword":"mqtt2",""" +
                """"backupWebdavPassword":"dav2","telegramBotToken":"tg2"}}""",
        )
        awaitUntil("rtmpUrl never propagated") { store.rtmpUrl.value == "rtmp://n:u2@h2/live/key2" }
        awaitUntil("whipToken never propagated") { store.whipToken.value == "whip2" }
        awaitUntil("srtUrl never propagated") { store.srtUrl.value == "srt://n:p2@h2?streamid=id2" }
        awaitUntil("mqttPassword never propagated") { store.mqttPassword.value == "mqtt2" }
        awaitUntil("backupWebdavPassword never propagated") { store.backupWebdavPassword.value == "dav2" }
        awaitUntil("telegramBotToken never propagated") { store.telegramBotToken.value == "tg2" }

        // And the response still blanks the fresh credentials.
        val json = handler.get()
        assertTrue(json.contains("\"whipToken\":\"\""))
        assertFalse(json.contains("whip2"))
    }

    // ── the apiToken exception: hash-and-gate ──

    @Test
    fun `enabling with a presented token stores its hash and arms the gate`() = runBlocking {
        handler.put("""{"streaming":{"apiTokenEnabled":true,"apiToken":"fresh-token"}}""")

        awaitUntil("apiTokenHash never propagated") {
            store.apiTokenHash.value == StreamAuthCrypto.sha256Hex("fresh-token")
        }
        awaitUntil("apiTokenEnabled never propagated") { store.apiTokenEnabled.value }
    }

    @Test
    fun `enabling with no token and no stored hash fails closed`() = runBlocking {
        handler.put("""{"streaming":{"apiTokenEnabled":true,"apiToken":""}}""")

        // Give the PUT's writes a chance to land (nothing of the gate's is
        // expected to), then pin the silence.
        Thread.sleep(200)
        assertFalse(store.apiTokenEnabled.value)
        assertEquals("", store.apiTokenHash.value)
    }

    @Test
    fun `enabling with an empty token keeps the armed state on a stored hash`() = runBlocking {
        plantAllSecrets() // plants "stored-token-hash"

        handler.put("""{"streaming":{"apiTokenEnabled":true,"apiToken":""}}""")

        awaitUntil("apiTokenEnabled never propagated") { store.apiTokenEnabled.value }
        assertEquals("stored-token-hash", store.apiTokenHash.value)
    }

    @Test
    fun `disabling always goes through while a presented token still rotates the hash`() = runBlocking {
        plantAllSecrets()

        handler.put("""{"streaming":{"apiTokenEnabled":false,"apiToken":"rotated-token"}}""")

        awaitUntil("apiTokenHash never propagated") {
            store.apiTokenHash.value == StreamAuthCrypto.sha256Hex("rotated-token")
        }
        // Give the enable-flag write its chance to land, then pin: the gate
        // never arms a disabled flag.
        Thread.sleep(200)
        assertFalse(store.apiTokenEnabled.value)
    }
}
