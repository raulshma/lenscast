package com.raulshma.lenscast.data

import android.app.Application
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.TorchStrengthPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The field-level camera-settings write ([SettingsDataStore.updateCameraSettings],
 * issue #25): the torch-fact convergence's write seam. The transform must see
 * the DISK truth — never the shared flow's placeholder-default replay — and
 * the result commits inside one DataStore edit, so only the transformed field
 * moves and every sibling field the flow never saw survives. The store rides
 * the shared `parkedDispatcher()` fixture (real DataStore, real file; the
 * share-in collectors never run, so the flow stays at its descriptor default
 * for the whole test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SettingsDataStoreUpdateCameraSettingsTest.PlainApplication::class)
class SettingsDataStoreUpdateCameraSettingsTest {

    class PlainApplication : Application()

    private lateinit var store: SettingsDataStore

    @Before
    fun setUp() {
        store = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(SupervisorJob() + parkedDispatcher()),
        )
    }

    @Test
    fun `the transform folds one field into the disk truth and every sibling survives`() = runBlocking {
        // Disk truth with a non-default camera field the (parked) flow never saw.
        val seeded = CameraSettings(zoomRatio = 2.5f, iso = 3200, torchEnabled = false)
        store.saveSettings(seeded)

        var transformSaw: CameraSettings? = null
        store.updateCameraSettings { current ->
            transformSaw = current
            current.copy(torchEnabled = true)
        }

        // The transform's base was the disk truth, not the flow's placeholder.
        assertEquals(seeded, transformSaw)
        // One field moved; the siblings ride through bit-for-bit.
        assertEquals(
            CameraSettings(zoomRatio = 2.5f, iso = 3200, torchEnabled = true),
            store.settingsFromDisk(),
        )
    }

    @Test
    fun `the write encodes through the descriptor so its clamp discipline applies`() = runBlocking {
        store.saveSettings(CameraSettings(torchEnabled = true, torchStrength = 3))

        // The convergence fact's write is a plain copy transform, but the
        // entry path is the descriptor's own encode — an out-of-span value a
        // transform produced still clamps exactly as the whole-document
        // saver would.
        store.updateCameraSettings { it.copy(torchStrength = 999) }

        assertEquals(
            TorchStrengthPolicy.MAX_REQUESTED_LEVEL,
            store.settingsFromDisk().torchStrength,
        )
    }

    @Test
    fun `repeated field writes converge without disturbing each other`() = runBlocking {
        store.saveSettings(CameraSettings(iso = 100, frameRate = 30))

        // The milliseconds-apart pair from the issue #25 repro: a second
        // camera write landing right after the torch convergence — the
        // field-level base is re-read per write, so neither clobbers the
        // other.
        store.updateCameraSettings { it.copy(torchEnabled = true) }
        store.updateCameraSettings { it.copy(iso = 800) }

        val disk = store.settingsFromDisk()
        assertEquals(true, disk.torchEnabled)
        assertEquals(800, disk.iso)
        assertEquals(30, disk.frameRate)
    }
}
