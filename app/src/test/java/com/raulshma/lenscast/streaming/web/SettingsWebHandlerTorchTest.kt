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
 * The torch contract of the settings PUT: the web camera section carries no
 * torch fields, so a dashboard camera-section save — even one that only
 * changed ISO — leaves the stored torch pair EXACTLY as it was (issue #16's
 * acknowledged "saving camera settings from the web dashboard resets the
 * torch"). The rebuild used to converge the stored pair to the live LED
 * fact writer-side (fix 22fe040); the verdict now lives in
 * CameraService.applySettings, which converges this save's untouched torch
 * pair to the live fact when the applier applies it — so this handler's
 * only job is to not disturb the pair: enabled and strength carry over
 * verbatim. The LED behavior itself (save lands, LED unchanged, stored
 * intent converged) is pinned at the applier level in
 * CameraServiceTorchConvergenceTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerTorchTest.PlainApplication::class)
class SettingsWebHandlerTorchTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

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
            detectionModelStore = mockk<DetectionModelStore>(relaxed = true) {
                every { state } returns MutableStateFlow(DetectionModelStore.State.NotDownloaded)
            },
            audioModelStore = mockk<AudioModelStore>(relaxed = true),
        )
    }

    @Test
    fun `camera-section save carries a stored dark torch intent and strength verbatim`() = runBlocking {
        store.saveSettings(store.settings.value.copy(torchEnabled = false, torchStrength = 3))
        awaitUntil("torch-off precondition never propagated") {
            !store.settings.value.torchEnabled && store.settings.value.torchStrength == 3
        }

        // The dashboard's GET-merge-PUT sends the camera section it read —
        // which has no torch fields; the save only means "iso = marker".
        // The marker must be a value the store cannot already hold: the
        // store persists across test methods, and waiting on a stale-but-
        // matching value would assert against the precondition's emission.
        val marker = (store.settings.value.iso ?: 0) + 1
        handler.put("""{"camera":{"iso":$marker,"frameRate":24}}""")

        awaitUntil("saved iso never propagated") { store.settings.value.iso == marker }
        // Dark intent survives the save verbatim — convergence to the live
        // fact is the applier's verdict, never the writer's.
        assertEquals(false, store.settings.value.torchEnabled)
        assertEquals(3, store.settings.value.torchStrength)
    }

    @Test
    fun `camera-section save carries a stored lit torch intent verbatim`() = runBlocking {
        store.saveSettings(store.settings.value.copy(torchEnabled = true, torchStrength = null))
        awaitUntil("torch-on precondition never propagated") { store.settings.value.torchEnabled }

        // Same marker rule as the dark test: wait on a value the store
        // cannot already hold, so the precondition's emission cannot satisfy
        // the wait.
        val marker = (store.settings.value.exposureTime ?: 0L) + 1_000_000L
        handler.put("""{"camera":{"exposureTime":$marker,"frameRate":24}}""")

        awaitUntil("saved exposure never propagated") { store.settings.value.exposureTime == marker }
        assertEquals(true, store.settings.value.torchEnabled)
        assertEquals(null, store.settings.value.torchStrength)
    }
}
