package com.raulshma.lenscast.camera

import android.app.Application
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ExposureState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.UseCase
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CaptureSupport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/** The plain application both CameraService Robolectric suites pin in @Config — see robolectric.properties. */
class CameraServiceTestApplication : Application()

/**
 * The shared Robolectric stand-in for the two [CameraService] suites (the
 * one-bundle and the read-back tests): a bound camera with no flash unit, a
 * recording [CaptureOptionsSubmitter] that records every bundle, and the
 * reflective field seams the suites plant state through. Install it in
 * @Before; a test only adds what makes it different.
 *
 * JVM-level limits, deliberately not papered over: the bound camera is a
 * mock standing in as `liveCamera`, and the capability lists
 * (`captureSupport`) are planted through the same reflective seam rather
 * than read at a real bind — the bind-time query itself is device-only code.
 */
internal class CameraServiceFixture {

    /** Every bundle handed to the device, in submit order. */
    val submitted = ArrayList<CaptureRequestOptions>()

    /** The future each submit handed back — completed by the failure-visibility tests. */
    val submitFutures = ArrayList<SettableFuture<Void?>>()

    /** Every center AF/AE metering the applies fired, in order (the force-focus reapply's record). */
    val meteringActions = ArrayList<FocusMeteringAction>()

    /** The bound camera's open-state LiveData — empty until a test publishes a state. */
    val cameraState = MutableLiveData<CameraState>()

    val cameraInfo = mockk<CameraInfo>(relaxed = true)
    val cameraControl = mockk<CameraControl>(relaxed = true)
    val camera = mockk<Camera>(relaxed = true)

    /** Every standard bind's use-case list, in bind order — what CameraX was handed. */
    val binds = ArrayList<List<UseCase>>()

    /** Runs inside the submit seam after a bundle is recorded — a concurrency test's parking spot. */
    val onSubmitInterceptors = ArrayList<(CaptureRequestOptions) -> Unit>()

    lateinit var service: CameraService
        private set

    fun install() {
        every { camera.cameraInfo } returns cameraInfo
        every { camera.cameraControl } returns cameraControl
        every { cameraInfo.hasFlashUnit() } returns false
        // CameraX types zoomState @NonNull: an empty LiveData is the "no
        // live zoom state yet" stand-in, so the zoom clamp falls open to the
        // persistence ceiling exactly as it does before the first state.
        every { cameraInfo.zoomState } returns MutableLiveData<ZoomState>()
        every { cameraInfo.cameraState } returns cameraState
        every { cameraInfo.exposureState } returns mockk<ExposureState>(relaxed = true).also { state ->
            every { state.exposureCompensationRange } returns Range(-12, 12)
            every { state.exposureCompensationIndex } returns 0
        }
        // The metering reapply leaves a record: which applies forced it and
        // which skipped it (FocusApplyPolicy's verdict, visible).
        every { cameraControl.startFocusAndMetering(any()) } answers {
            meteringActions += firstArg<FocusMeteringAction>()
            mockk<ListenableFuture<FocusMeteringResult>>(relaxed = true)
        }
        service = CameraService(RuntimeEnvironment.getApplication(), CaptureOptionsSubmitter { _, bundle ->
            submitted += bundle
            onSubmitInterceptors.forEach { it(bundle) }
            SettableFuture.create<Void?>().also { submitFutures += it }
        })
        setLiveCamera(camera)
    }

    /** Plants a stand-in provider whose binds record into [binds] and return [camera]. */
    fun plantStandardProvider(): ProcessCameraProvider {
        val provider = mockk<ProcessCameraProvider>(relaxed = true)
        every { provider.bindToLifecycle(any(), any(), *anyVararg<UseCase>()) } answers {
            @Suppress("UNCHECKED_CAST")
            binds += when (val third = args[2]) {
                is Array<*> -> third.filterIsInstance<UseCase>()
                is Collection<*> -> third.filterIsInstance<UseCase>()
                else -> emptyList()
            }
            camera
        }
        setServiceField("cameraProvider", provider)
        return provider
    }

    fun setServiceField(name: String, value: Any) {
        CameraService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    fun <T> serviceField(name: String): T =
        CameraService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as T

    fun setLiveCamera(cam: Camera) = setServiceField("liveCamera", cam)

    fun setCaptureSupport(support: CaptureSupport) = setServiceField("captureSupport", support)

    fun apply(settings: CameraSettings) = runBlocking { service.applySettings(settings) }

    companion object {
        /** The Camera2 key NAME of [key] — bundle readers key on names. */
        fun keyName(key: CaptureRequest.Key<*>): String = key.name
    }
}
