package com.raulshma.lenscast.camera

import android.app.Application
import android.os.Looper
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState as CameraXCameraState
import androidx.camera.core.ExposureState
import androidx.camera.core.TorchState as CameraXTorchState
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.Futures
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.TorchState
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The torch-touch verdict and rebind preservation of [CameraService]:
 *
 * - an untouched torch pair converges the stored intent to the live LED
 *   fact through one persist when a bound, settled camera exists, and
 *   [CameraService.torchState] stays as it was (issue #16);
 * - an explicit torch write passes through verbatim (no convergence write);
 * - the first apply of a process is the cold-start one: the stored intent
 *   commits as written, which is what re-lights the LED after a restart;
 * - an unbound camera or a mid-rebind settle window (`torchSettlePending`)
 *   never wipes a persisted `torchEnabled = true` down to `false`;
 * - live-only `setTorchEnabled` flips survive `rebindUseCases()` (such as
 *   stream start/stop) rather than snapping back to stale persisted settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceTorchConvergenceTest.PlainApplication::class)
class CameraServiceTorchConvergenceTest {

    class PlainApplication : Application()

    private lateinit var service: CameraService
    private lateinit var camera: Camera
    private lateinit var cameraInfo: CameraInfo
    private lateinit var cameraControl: CameraControl
    private lateinit var torchLiveData: MutableLiveData<Int>
    private lateinit var cameraStateLiveData: MutableLiveData<CameraXCameraState>

    /** Every torch fact the convergence persisted, in order. */
    private val persistedFacts = ArrayList<Boolean>()

    /** Tracks the simulated hardware torch transitions driven via [CameraControl.enableTorch]. */
    private val drivenTorchCalls = ArrayList<Boolean>()

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

    private fun setLiveCamera(cam: Camera?) {
        val field = CameraService::class.java.getDeclaredField("liveCamera")
        field.isAccessible = true
        field.set(service, cam)
    }

    @Before
    fun setUp() {
        persistedFacts.clear()
        drivenTorchCalls.clear()
        torchLiveData = MutableLiveData(CameraXTorchState.OFF)
        cameraStateLiveData = MutableLiveData()
        cameraInfo = mockk(relaxed = true)
        cameraControl = mockk(relaxed = true)
        camera = mockk(relaxed = true)
        every { camera.cameraInfo } returns cameraInfo
        every { camera.cameraControl } returns cameraControl
        every { cameraInfo.hasFlashUnit() } returns false
        every { cameraInfo.torchState } returns torchLiveData
        every { cameraInfo.cameraState } returns cameraStateLiveData
        every { cameraInfo.zoomState } returns MutableLiveData<ZoomState>()
        every { cameraInfo.exposureState } returns mockk<ExposureState>(relaxed = true).also { state ->
            every { state.exposureCompensationRange } returns android.util.Range(-12, 12)
            every { state.exposureCompensationIndex } returns 0
        }

        service = CameraService(RuntimeEnvironment.getApplication())
        service.setTorchFactPersister { persistedFacts.add(it) }
        setLiveCamera(camera)
    }

    private fun plantFlashEnabledRebindableProvider(): ProcessCameraProvider {
        every { cameraInfo.hasFlashUnit() } returns true
        every { cameraControl.enableTorch(any()) } answers {
            val on = firstArg<Boolean>()
            drivenTorchCalls += on
            torchLiveData.value = if (on) CameraXTorchState.ON else CameraXTorchState.OFF
            Futures.immediateFuture(null)
        }
        val provider = mockk<ProcessCameraProvider>(relaxed = true)
        every { provider.bindToLifecycle(any(), any(), *anyVararg<UseCase>()) } answers {
            // A fresh CameraX bind resets hardware torch state to OFF.
            torchLiveData.value = CameraXTorchState.OFF
            camera
        }
        every {
            provider.bindToLifecycle(any(), any(), any<androidx.camera.core.SessionConfig>())
        } answers {
            torchLiveData.value = CameraXTorchState.OFF
            camera
        }
        CameraService::class.java.getDeclaredField("cameraProvider").apply {
            isAccessible = true
            set(service, provider)
        }
        service.setLifecycleOwner(mockk<LifecycleOwner>(relaxed = true))
        service.acquireKeepAlive()
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        return provider
    }

    @Test
    fun `an untouched write converges the stored intent to the live lit fact`() = runBlocking {
        // Cold start commits the stored dark intent verbatim.
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 3, iso = 100))
        assertTrue(persistedFacts.isEmpty())

        // A live-only writer (web torch route, deterrence) lights the LED
        // without persisting; the next wholesale write only means iso = 200.
        setLiveTorchFact(on = true)
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 3, iso = 200))

        // One converged fact — the live LED state and nothing else: the
        // write's own fields land through the collector's normal apply, the
        // convergence persist carries the fact only.
        assertEquals(listOf(true), persistedFacts)
        // The verdict converged instead of driving the raw value: the LED
        // fact is exactly where it was.
        assertEquals(TorchState.On(null), liveTorchFact())
    }

    @Test
    fun `an untouched write converges the stored intent to the live dark fact`() = runBlocking {
        // Stored intent says lit; the LED is dark on a settled bound camera.
        // A non-torch write must not re-light it.
        service.applySettings(CameraSettings(torchEnabled = true))
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true, zoomRatio = 2.5f))

        assertEquals(listOf(false), persistedFacts)
    }

    @Test
    fun `an untouched write with nothing to converge persists nothing`() = runBlocking {
        // The at-rest common case: stored intent already agrees with the LED.
        service.applySettings(CameraSettings(torchEnabled = false))
        service.applySettings(CameraSettings(torchEnabled = false, frameRate = 30))

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `an explicit torch write passes through verbatim with no convergence write`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = false)

        // The in-app toggle: intent moves, the verdict must not rewrite it —
        // the writer's document IS the torch verdict.
        service.applySettings(CameraSettings(torchEnabled = true, torchStrength = 5))

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `a strength-only write touches torch and passes through verbatim`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = true)

        // The strength level is half the torch pair: moving it is a torch
        // write even though the enabled flag stayed put.
        service.applySettings(CameraSettings(torchEnabled = false, torchStrength = 5))

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `the first apply commits the stored intent without converging`() = runBlocking {
        // Cold start: no previously applied snapshot exists, so intent that
        // disagrees with the (dark) LED still commits as written — the
        // restart contract, a reboot re-lights from the persisted pair.
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true))

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `an explicit torch intent applies verbatim even when the pair already matches the snapshot`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = true))
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true), torchIntent = true)

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `an explicit torch-off intent stays verbatim through a matching snapshot`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = false))
        setLiveTorchFact(on = true)

        service.applySettings(CameraSettings(torchEnabled = false), torchIntent = false)

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `an untouched write does not wipe persisted torchEnabled when no camera is bound`() = runBlocking {
        service.applySettings(CameraSettings(torchEnabled = true))
        setLiveCamera(null)
        setLiveTorchFact(on = false)

        service.applySettings(CameraSettings(torchEnabled = true, iso = 400))

        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `an untouched write during rebind settle window does not extinguish persisted torch`() = runBlocking {
        plantFlashEnabledRebindableProvider()
        service.applySettings(CameraSettings(torchEnabled = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isTorchOn())

        // Rebind resets CameraX torchState to OFF until the 400 ms post-bind rung fires.
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.isTorchOn())

        // An unrelated write landing inside the settle window must NOT wipe torchEnabled=false.
        service.applySettings(CameraSettings(torchEnabled = true, iso = 800))
        assertTrue(persistedFacts.isEmpty())

        // Once the post-bind rung settles, the torch lights right back up.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertTrue(service.isTorchOn())
    }

    @Test
    fun `live-only setTorchEnabled on survives stream rebind without snapping back to persisted off`() = runBlocking {
        plantFlashEnabledRebindableProvider()
        service.applySettings(CameraSettings(torchEnabled = false))
        shadowOf(Looper.getMainLooper()).idle()

        // Web API / deterrence lights the torch without persisting.
        assertTrue(service.setTorchEnabled(true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isTorchOn())

        // Turning stream on/off triggers rebindUseCases() + post-bind rungs.
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))

        assertTrue(service.isTorchOn())
        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `live-only setTorchEnabled off stays off across stream rebind even when persisted intent is on`() = runBlocking {
        plantFlashEnabledRebindableProvider()
        service.applySettings(CameraSettings(torchEnabled = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isTorchOn())

        // Web API / SET_TORCH turns the torch off live without persisting.
        assertTrue(service.setTorchEnabled(false))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.isTorchOn())

        // Rebinding (stream on/off) must NOT re-light the torch from stale persisted intent.
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))

        assertFalse(service.isTorchOn())
        assertTrue(persistedFacts.isEmpty())
    }

    @Test
    fun `unbinding all demand clears live-only override so a later new session does not inherit it`() = runBlocking {
        plantFlashEnabledRebindableProvider()
        service.applySettings(CameraSettings(torchEnabled = false))
        assertTrue(service.setTorchEnabled(true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isTorchOn())

        // Release keep-alive with no preview -> Unbind -> clearBoundUseCases().
        service.releaseKeepAlive()
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.isTorchOn())

        // Starting a fresh stream session later keeps persisted dark intent.
        service.acquireKeepAlive()
        assertTrue(service.rebindUseCases())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertFalse(service.isTorchOn())
    }
}
