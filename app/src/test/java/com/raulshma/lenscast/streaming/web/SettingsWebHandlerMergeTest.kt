package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingPref
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.rtspBasicAuthCompatPref
import com.raulshma.lenscast.data.rtspEnabledPref
import com.raulshma.lenscast.data.rtspPortPref
import com.raulshma.lenscast.data.streamingPortPref
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings PUT's section-level merge and the response's fresh-disk
 * answer. A section that omits a field keeps the stored value — the old
 * wholesale replace silently reset every omitted sibling to its DTO default
 * (a curl `{"streaming":{"rtspBasicAuthCompat":true}}` switched RTSP off),
 * while the dashboard's GET-merge-PUT full sections behave exactly as
 * before. An explicit JSON null still overlays as null and resets an
 * optional field. And because GET and PUT answer from one fresh file pass
 * ([SettingsDataStore.readFresh]) instead of the shared flows' lagging
 * collector beat, a read landing right after a save never echoes pre-write
 * state — no await-until dance anywhere in this file on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerMergeTest.PlainApplication::class)
class SettingsWebHandlerMergeTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

    private val detectionModelStore = mockk<DetectionModelStore>(relaxed = true) {
        every { state } returns MutableStateFlow(DetectionModelStore.State.NotDownloaded)
    }

    /** The committed disk value of one setting — the same seam the handler answers from. */
    private suspend fun <T> stored(pref: SettingPref<T>): T = store.readFresh(pref.decode)

    private val responseAdapter = AppJson.moshi.adapter(SettingsResponseDto::class.java)
    private val updateAdapter = AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java)

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        store = SettingsDataStore(application)
        handler = SettingsWebHandler(
            settingsDataStore = store,
            detectionModelStore = detectionModelStore,
            audioModelStore = mockk<AudioModelStore>(relaxed = true),
        )
    }

    @Test
    fun `partial streaming put keeps stored siblings`() = runBlocking {
        store.saveRtspEnabled(true)
        store.saveStreamingPort(8081)

        handler.put("""{"streaming":{"rtspBasicAuthCompat":true}}""")

        assertEquals(true, stored(rtspEnabledPref))
        assertEquals(8081, stored(streamingPortPref))
        assertEquals(true, stored(rtspBasicAuthCompatPref))
    }

    @Test
    fun `partial camera put applies only the sent field`() = runBlocking {
        store.saveSettings(
            store.settingsFromDisk().copy(iso = 100, exposureTime = 250_000_000L, torchStrength = 3),
        )

        handler.put("""{"camera":{"iso":800}}""")

        val saved = store.settingsFromDisk()
        assertEquals(800, saved.iso)
        assertEquals(250_000_000L, saved.exposureTime)
        assertEquals(3, saved.torchStrength)
    }

    @Test
    fun `explicit null iso resets to auto while siblings hold`() = runBlocking {
        store.saveSettings(store.settingsFromDisk().copy(iso = 800, exposureTime = 250_000_000L))

        handler.put("""{"camera":{"iso":null}}""")

        val saved = store.settingsFromDisk()
        assertNull(saved.iso)
        assertEquals(250_000_000L, saved.exposureTime)
    }

    @Test
    fun `get answers from disk right after a put`() = runBlocking {
        handler.put("""{"camera":{"iso":1234}}""")
        handler.put("""{"streaming":{"rtspPort":8555}}""")

        val doc = responseAdapter.fromJson(handler.get())
        assertEquals(1234, doc?.camera?.iso)
        assertEquals(8555, doc?.streaming?.rtspPort)
    }

    @Test
    fun `dashboard full-section round trip still applies wholesale`() = runBlocking {
        store.saveRtspEnabled(true)
        store.saveRtspBasicAuthCompat(true)

        // The dashboard's GET-merge-PUT: read the full document, flip one
        // field, send both sections back complete.
        val doc = responseAdapter.fromJson(handler.get()) ?: throw AssertionError("GET returned no document")
        val update = SettingsUpdateRequestDto(
            camera = doc.camera,
            streaming = doc.streaming?.copy(rtspEnabled = false),
        )
        handler.put(updateAdapter.toJson(update))

        assertEquals(false, stored(rtspEnabledPref))
        // The full section round-tripped verbatim: every carried value —
        // here the compat toggle — survives the save.
        assertEquals(true, stored(rtspBasicAuthCompatPref))
        val carriedPort = doc.streaming?.port ?: throw AssertionError("GET document carried no port")
        assertEquals(carriedPort, stored(streamingPortPref))
    }
}
