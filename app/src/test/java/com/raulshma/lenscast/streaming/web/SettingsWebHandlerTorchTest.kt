package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.data.SettingsDataStore
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
 * The torch-preservation contract of the settings PUT: the web camera
 * section carries no torch fields, so a dashboard camera-section save — even
 * one that only changed ISO — must never flip the stored torch state. Before
 * the fix the PUT's rebuild defaulted torchEnabled to false and persisted
 * it, and the Settings Applier killed the LED on every save (issue #16's
 * acknowledged "saving camera settings from the web dashboard resets the
 * torch").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerTorchTest.PlainApplication::class)
class SettingsWebHandlerTorchTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

    private val detectionModelStore = mockk<DetectionModelStore>(relaxed = true) {
        every { state } returns MutableStateFlow(DetectionModelStore.State.NotDownloaded)
    }

    /** DataStore's shared flows re-emit off the store's scope — wait until the value lands. */
    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(10)
        }
    }

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
    fun `camera-section save keeps the stored torch on and strength`() = runBlocking {
        store.saveSettings(
            store.settings.value.copy(torchEnabled = true, torchStrength = 3)
        )
        awaitUntil("torch-on precondition never propagated") {
            store.settings.value.torchEnabled && store.settings.value.torchStrength == 3
        }

        // The dashboard's GET-merge-PUT sends the camera section it read —
        // which has no torch fields; the save only means "iso = 800".
        handler.put("""{"camera":{"iso":800,"frameRate":24}}""")

        awaitUntil("saved iso never propagated") { store.settings.value.iso == 800 }
        assertEquals(true, store.settings.value.torchEnabled)
        assertEquals(3, store.settings.value.torchStrength)
    }

    @Test
    fun `camera-section save keeps the stored torch off`() = runBlocking {
        store.saveSettings(store.settings.value.copy(torchEnabled = false, torchStrength = null))
        awaitUntil("torch-off precondition never propagated") { !store.settings.value.torchEnabled }

        handler.put("""{"camera":{"exposureTime":100000000,"frameRate":24}}""")

        awaitUntil("saved exposure never propagated") { store.settings.value.exposureTime == 100_000_000L }
        assertEquals(false, store.settings.value.torchEnabled)
        assertEquals(null, store.settings.value.torchStrength)
    }
}
