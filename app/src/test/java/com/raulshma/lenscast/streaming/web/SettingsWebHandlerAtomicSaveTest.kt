package com.raulshma.lenscast.streaming.web

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingPref
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.jpegQualityPref
import com.raulshma.lenscast.data.overlaySettingsPref
import com.raulshma.lenscast.data.rtspBasicAuthCompatPref
import com.raulshma.lenscast.data.rtspEnabledPref
import com.raulshma.lenscast.data.rtspPortPref
import com.raulshma.lenscast.data.streamingPortPref
import com.raulshma.lenscast.data.watchdogEnabledPref
import com.raulshma.lenscast.data.watchdogMaxRetriesPref
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings PUT's atomic section save: a streaming PUT persists the
 * touched fields in ONE DataStore transaction
 * ([SettingsDataStore.saveEntries]) instead of one transaction per field.
 * The store here is the real file-backed DataStore with no
 * transaction-count seam, so atomicity is pinned through its observable
 * consequences (the same read-back discipline as
 * [SettingsWebHandlerMergeTest], no await-until dance): exactly the touched
 * fields' keys change (a whole-preference-map delta), untouched keys keep
 * their prior stored values — not the descriptor defaults — an empty section
 * writes nothing at all, and the per-field coercion (the rtspPort clamp)
 * survives the section path unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerAtomicSaveTest.PlainApplication::class)
class SettingsWebHandlerAtomicSaveTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

    private val detectionModelStore = mockk<DetectionModelStore>(relaxed = true) {
        every { state } returns MutableStateFlow(DetectionModelStore.State.NotDownloaded)
    }

    /** The committed disk value of one setting — the same seam the handler answers from. */
    private suspend fun <T> stored(pref: SettingPref<T>): T = store.readFresh(pref.decode)

    /** A stable copy of every persisted preference key and stored value. */
    private suspend fun snapshot(): Map<Preferences.Key<*>, Any> =
        store.readFresh { it.asMap().toMap() }

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        store = SettingsDataStore(application)
        handler = SettingsWebHandler(
            settingsDataStore = store,
            detectionModelStore = detectionModelStore,
            audioModelStore = mockk<AudioModelStore>(relaxed = true),
        )
        // Settle the one-time RTSP-TLS seeding before any snapshot: with the
        // key planted, the migration's edit is a permanent no-op, so the
        // whole-map deltas below can never observe it mid-test.
        runBlocking { store.saveRtspTlsEnabled(false) }
    }

    @Test
    fun `partial streaming put persists exactly the touched keys`() = runBlocking {
        // Prior stored values on fields the PUT will NOT touch — planted
        // non-default so a silent reset to the descriptor default would be
        // visible in the read-back.
        store.saveWatchdogEnabled(false)
        store.saveJpegQuality(55)
        store.saveRtspEnabled(true)
        val before = snapshot()

        handler.put("""{"streaming":{"port":8090,"rtspBasicAuthCompat":true}}""")

        // Exactly the touched fields' stored keys changed — two sent, two
        // persisted — and nothing else moved, not even by a re-encode.
        // (Explicit map mutation, not mapOf(key to v): the DataStore Key
        // type carries its own infix `to` that shadows the stdlib one.)
        val expected = HashMap<Preferences.Key<*>, Any>(before)
        expected[intPreferencesKey("streaming_port")] = 8090
        expected[stringPreferencesKey("rtsp_basic_auth_compat")] = "true"
        assertEquals(expected, snapshot())
        // Untouched keys keep their prior stored values, not the defaults.
        assertEquals(false, stored(watchdogEnabledPref))
        assertEquals(55, stored(jpegQualityPref))
        assertEquals(true, stored(rtspEnabledPref))
    }

    @Test
    fun `a touched overlay field persists the composite and untouched siblings keep stored`() = runBlocking {
        store.saveOverlaySettings(
            store.readFresh(overlaySettingsPref.decode).copy(enabled = false, customText = "kept-text"),
        )
        store.saveWatchdogMaxRetries(9)

        handler.put("""{"streaming":{"overlayEnabled":true}}""")

        // One touched overlay wire field lands the whole composite, while
        // its untouched siblings (here customText) ride their stored values.
        val overlay = stored(overlaySettingsPref)
        assertEquals(true, overlay.enabled)
        assertEquals("kept-text", overlay.customText)
        // An untouched key outside the composite kept too.
        assertEquals(9, stored(watchdogMaxRetriesPref))
    }

    @Test
    fun `an empty streaming section persists nothing`() = runBlocking {
        store.saveStreamingPort(8077)
        val before = snapshot()

        handler.put("""{"streaming":{}}""")

        // No savable key sent — no entry, no write, not even a re-encode.
        assertEquals(before, snapshot())
    }

    @Test
    fun `section put clamps a bounded field through the atomic save`() = runBlocking {
        handler.put("""{"streaming":{"rtspPort":70000}}""")
        assertEquals(StreamDefaults.RTSP_PORT_MAX, stored(rtspPortPref))

        handler.put("""{"streaming":{"rtspPort":1}}""")
        assertEquals(StreamDefaults.RTSP_PORT_MIN, stored(rtspPortPref))
    }
}
