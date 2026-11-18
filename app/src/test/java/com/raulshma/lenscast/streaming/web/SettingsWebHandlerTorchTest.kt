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
 * changed ISO — must never DEFAULT the stored torch state (the original bug:
 * the rebuild defaulted torchEnabled to false and the Settings Applier killed
 * the LED on every save; issue #16's acknowledged "saving camera settings
 * from the web dashboard resets the torch"). Live-only writers — the web
 * torch route and the deterrence coordinator — diverge from the stored pair
 * by design, and the save re-asserts torch through the applier, so the
 * rebuild converges the stored torchEnabled to the LIVE torch fact: carrying
 * the stale stored value killed a lit LED on every save. The stored strength
 * stays untouched: a live flip drives the device-default brightness and
 * carries no strength intent of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsWebHandlerTorchTest.PlainApplication::class)
class SettingsWebHandlerTorchTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore
    private lateinit var handler: SettingsWebHandler

    /** The injected live torch fact the rebuild converges to; flipped per test. */
    private var liveTorch = false

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
            liveTorchOn = { liveTorch },
        )
    }

    @Test
    fun `camera-section save converges the stored torch to a live lit fact`() = runBlocking {
        liveTorch = true
        store.saveSettings(store.settings.value.copy(torchEnabled = false, torchStrength = 3))
        awaitUntil("torch-off precondition never propagated") { !store.settings.value.torchEnabled }

        // The dashboard's GET-merge-PUT sends the camera section it read —
        // which has no torch fields; the save only means "iso = marker".
        // The marker must be a value the store cannot already hold: the
        // store persists across test methods, and waiting on a stale-but-
        // matching value would assert against the precondition's emission.
        val marker = (store.settings.value.iso ?: 0) + 1
        handler.put("""{"camera":{"iso":$marker,"frameRate":24}}""")

        awaitUntil("saved iso never propagated") { store.settings.value.iso == marker }
        // The LED is lit live while the stored intent says off — a save that
        // carried the stale stored value would kill it via the applier.
        assertEquals(true, store.settings.value.torchEnabled)
        // The live flip carried no strength intent — the stored preference rides.
        assertEquals(3, store.settings.value.torchStrength)
    }

    @Test
    fun `camera-section save converges the stored torch to a live dark fact`() = runBlocking {
        liveTorch = false
        store.saveSettings(store.settings.value.copy(torchEnabled = true, torchStrength = null))
        awaitUntil("torch-on precondition never propagated") { store.settings.value.torchEnabled }

        // Same marker rule as the lit test: wait on a value the store cannot
        // already hold, so the precondition's emission cannot satisfy the wait.
        val marker = (store.settings.value.exposureTime ?: 0L) + 1_000_000L
        handler.put("""{"camera":{"exposureTime":$marker,"frameRate":24}}""")

        awaitUntil("saved exposure never propagated") { store.settings.value.exposureTime == marker }
        assertEquals(false, store.settings.value.torchEnabled)
        assertEquals(null, store.settings.value.torchStrength)
    }

    @Test
    fun `camera-section save keeps an already-converged lit torch and strength`() = runBlocking {
        liveTorch = true
        store.saveSettings(store.settings.value.copy(torchEnabled = true, torchStrength = 3))
        awaitUntil("torch-on precondition never propagated") {
            store.settings.value.torchEnabled && store.settings.value.torchStrength == 3
        }

        val marker = (store.settings.value.iso ?: 0) + 1
        handler.put("""{"camera":{"iso":$marker,"frameRate":24}}""")

        awaitUntil("saved iso never propagated") { store.settings.value.iso == marker }
        assertEquals(true, store.settings.value.torchEnabled)
        assertEquals(3, store.settings.value.torchStrength)
    }
}
