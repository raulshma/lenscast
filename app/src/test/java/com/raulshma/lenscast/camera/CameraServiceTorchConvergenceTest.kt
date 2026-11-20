package com.raulshma.lenscast.camera

import android.app.Application
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.TorchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The torch-touch verdict of [CameraService.applySettings] — the single
 * owner since the per-writer convergence protocol (the deleted
 * `CameraSettings.convergeTorch` plus the editor/web/automation verdicts,
 * fixes 22fe040, e84b36d, 565a690; issue #16) collapsed into the one seam
 * that sees both the previously applied snapshot and the new write:
 *
 * - an untouched torch pair converges the stored intent to the live LED
 *   fact through one persist, and [CameraService.torchState] stays exactly
 *   as it was — the #16 repro (a wholesale save must not move the LED);
 * - an explicit torch write passes through verbatim (no convergence write);
 * - the first apply of a process is the cold-start one: the stored intent
 *   commits as written, which is what re-lights the LED after a restart.
 *
 * JVM-level limits, deliberately not papered over: with no bound camera the
 * controls apply is a no-op, so these tests pin the verdict and the
 * persisted document — the LED driving itself (applyCameraControls' torch
 * branch, the post-rebind re-assert, the torchDrivenOn arbitration) is
 * unchanged code exercised only on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceTorchConvergenceTest.PlainApplication::class)
class CameraServiceTorchConvergenceTest {

    class PlainApplication : Application()

    private lateinit var service: CameraService

    /** Every document the convergence persisted, in order. */
    private val persistedDocuments = ArrayList<CameraSettings>()

    /** The live LED fact, driven through the same reflective seam the torch observation publishes through. */
    @Suppress("UNCHECKED_CAST")
    private fun setLiveTorchFact(on: Boolean) {
        val field = CameraService::class.java.getDeclaredField("_torchState")
        field.isAccessible = true
        val flow = field.get(service) as MutableStateFlow<TorchState>
        flow.value = if (on) TorchState.On(null) else TorchState.Off
    }

    @Suppress("UNCHECKED_CAST")
    private fun liveTorchFact(): TorchState {
        val field = CameraService::class.java.getDeclaredField("_torchState")
        field.isAccessible = true
        return (field.get(service) as MutableStateFlow<TorchState>).value
    }

    @Before
    fun setUp() {
        service = CameraService(RuntimeEnvironment.getApplication())
        service.setSettingsPersister { persistedDocuments.add(it) }
    }

    @Test
    fun `an untouched write converges the stored intent to the live lit fact`() = runBlocking {
        // Cold start commits the stored dark intent verbatim.
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 3, iso = 100))
        assertTrue(persistedDocuments.isEmpty())

        // A live-only writer (web torch route, deterrence) lights the LED
        // without persisting; the next wholesale write only means iso = 200.
        setLiveTorchFact(on = true)
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 3, iso = 200))

        // One converged document — the live fact, the strength intent and
        // the write's own field, nothing else moved.
        assertEquals(listOf(CameraSettings(torchEnabled = true, torchStrength = 3, iso = 200)), persistedDocuments)
        // The verdict converged instead of driving the raw value: the LED
        // fact is exactly where it was.
        assertEquals(TorchState.On(null), liveTorchFact())
    }

    @Test
    fun `an untouched write converges the stored intent to the live dark fact`() = runBlocking {
        // Stored intent says lit; the LED is dark. A non-torch write must
        // not re-light it.
        service.applySettings(CameraSettings(torchEnabled = true))
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true, zoomRatio = 2.5f))

        assertEquals(false, persistedDocuments.single().torchEnabled)
        assertEquals(2.5f, persistedDocuments.single().zoomRatio)
    }

    @Test
    fun `an untouched write with nothing to converge persists nothing`() = runBlocking {
        // The at-rest common case: stored intent already agrees with the LED.
        service.applySettings(CameraSettings(torchEnabled = false))
        service.applySettings(CameraSettings(torchEnabled = false, frameRate = 30))

        assertTrue(persistedDocuments.isEmpty())
    }

    @Test
    fun `an explicit torch write passes through verbatim with no convergence write`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = false)

        // The in-app toggle: intent moves, the verdict must not rewrite it —
        // the writer's document IS the torch verdict.
        service.applySettings(CameraSettings(torchEnabled = true, torchStrength = 5))

        assertTrue(persistedDocuments.isEmpty())
    }

    @Test
    fun `a strength-only write touches torch and passes through verbatim`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = true)

        // The strength level is half the torch pair: moving it is a torch
        // write even though the enabled flag stayed put.
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 5))

        assertTrue(persistedDocuments.isEmpty())
    }

    @Test
    fun `the first apply commits the stored intent without converging`() = runBlocking {
        // Cold start: no previously applied snapshot exists, so intent that
        // disagrees with the (dark) LED still commits as written — the
        // restart contract, a reboot re-lights from the persisted pair.
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true))

        assertTrue(persistedDocuments.isEmpty())
    }

    @Test
    fun `an explicit torch intent applies verbatim even when the pair already matches the snapshot`() = runBlocking {
        // The issue #21 world: a convergence wrote the requested value into
        // the applied snapshot while a live-only writer left the LED
        // opposite. The pair diff reads "untouched" — only the declared
        // intent keeps this write a torch request: verbatim commit, no
        // convergence write, the request never swallowed.
        service.applySettings(CameraSettings(torchEnabled = true))
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true), torchIntent = true)

        assertTrue(persistedDocuments.isEmpty())
    }

    @Test
    fun `an explicit torch-off intent stays verbatim through a matching snapshot`() = runBlocking {
        // The mirror direction: the stored/snapshot pair says dark while a
        // live-only writer lit the LED. A declared false intent is a
        // request, not a carry — it must not fold into a convergence that
        // would restate the lit fact.
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = true)

        service.applySettings(CameraSettings(torchEnabled = false), torchIntent = false)

        assertTrue(persistedDocuments.isEmpty())
    }
}
