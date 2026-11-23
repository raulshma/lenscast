package com.raulshma.lenscast.camera

import android.app.Application
import android.hardware.camera2.CaptureRequest
import android.os.Looper
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ExposureState
import androidx.camera.core.ZoomState
import androidx.camera.core.impl.Config as CameraXConfig
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.SettableFuture
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CaptureSupport
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import io.mockk.every
import io.mockk.mockk
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * The one-bundle contract of the controls apply — issue #25 pinned where it
 * happened. 0.2.3 submitted its capture-request options in five to eight
 * per-group `setCaptureRequestOptions` calls, and each call REPLACED the
 * session's whole option set, so only the last group reached the device:
 * manual ISO/exposure and night vision silently stopped working while
 * white balance (the last group) and zoom, torch and exposure compensation
 * (CameraX's native APIs) carried on.
 *
 * The device write rides the [CaptureOptionsSubmitter] seam, so a recording
 * submitter shows exactly what a settings apply hands the camera: how many
 * times, and which Camera2 keys each bundle carries.
 *
 * JVM-level limits, deliberately not papered over: the bound camera is a
 * mock standing in as `liveCamera`, and the capability lists
 * (`captureSupport`) are planted through the same reflective seam rather
 * than read at a real bind — the bind-time query itself is device-only code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceCaptureBundleTest.PlainApplication::class)
class CameraServiceCaptureBundleTest {

    class PlainApplication : Application()

    private lateinit var service: CameraService

    /** Every bundle handed to the device, in submit order. */
    private val submitted = ArrayList<CaptureRequestOptions>()

    /** The future each submit handed back — completed by the failure-visibility test. */
    private val submitFutures = ArrayList<SettableFuture<Void?>>()

    private val cameraInfo = mockk<CameraInfo>(relaxed = true)
    private val cameraControl = mockk<CameraControl>(relaxed = true)
    private val camera = mockk<Camera>(relaxed = true)

    /** The bound camera's open-state LiveData — empty until a test publishes a state. */
    private val cameraState = MutableLiveData<CameraState>()

    private fun setServiceField(name: String, value: Any) {
        CameraService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun setLiveCamera(cam: Camera) = setServiceField("liveCamera", cam)

    private fun setCaptureSupport(support: CaptureSupport) = setServiceField("captureSupport", support)

    private fun apply(settings: CameraSettings) = runBlocking { service.applySettings(settings) }

    private fun mainLooper(): ShadowLooper = shadowOf(Looper.getMainLooper())

    private fun serviceLogs(): List<ShadowLog.LogItem> = ShadowLog.getLogsForTag("CameraService")

    /** The apply-time "not supported" reports, in order — empty unless a plan retired something. */
    private fun unsupportedReports(): List<String> =
        serviceLogs().map { it.msg }.filter { it.startsWith("Capture options not supported") }

    @Before
    fun setUp() {
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
        service = CameraService(RuntimeEnvironment.getApplication(), CaptureOptionsSubmitter { _, bundle ->
            submitted += bundle
            SettableFuture.create<Void?>().also { submitFutures += it }
        })
        setLiveCamera(camera)
    }

    // ── bundle readers ──

    /**
     * A bundle's Camera2 key names, read off `listOptions()`: every option's
     * token is its own [CaptureRequest.Key], so a key the bundle does not
     * carry is simply absent from this set.
     */
    private fun CaptureRequestOptions.keyNames(): Set<String> =
        listOptions().mapNotNull { (it.token as? CaptureRequest.Key<*>)?.name }.toSet()

    /** The bundle as key name → value (`retrieveOption`), for whole-bundle comparisons. */
    private fun CaptureRequestOptions.byKey(): Map<String, Any?> = listOptions().associate { option ->
        @Suppress("UNCHECKED_CAST")
        (option.token as CaptureRequest.Key<*>).name to retrieveOption(option as CameraXConfig.Option<Any?>)
    }

    private val fpsRange get() = CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE.name
    private val sensorSensitivity get() = CaptureRequest.SENSOR_SENSITIVITY.name
    private val sensorExposureTime get() = CaptureRequest.SENSOR_EXPOSURE_TIME.name
    private val opticalStabilization get() = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE.name
    private val videoStabilization get() = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE.name

    /** The keys every plan carries, whatever the settings. */
    private val alwaysPresentKeys = setOf(
        fpsRange,
        videoStabilization,
        opticalStabilization,
        CaptureRequest.CONTROL_SCENE_MODE.name,
        CaptureRequest.CONTROL_AE_MODE.name,
        CaptureRequest.CONTROL_AWB_MODE.name,
    )

    // ── one submit, every control ──

    @Test
    fun `manual exposure with night vision travels in exactly one bundle`() {
        apply(
            CameraSettings(
                iso = 3200,
                exposureTime = 100_000_000L,
                nightVisionMode = NightVisionMode.ON,
                whiteBalance = WhiteBalance.AUTO,
                focusMode = FocusMode.AUTO,
            ),
        )

        // One settings apply is one submit — not one per control group.
        assertEquals(1, submitted.size)
        val bundle = submitted.single()
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(3200, bundle.getCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY))
        assertEquals(100_000_000L, bundle.getCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME))
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))
        // Night vision's 10..15 range, capped to the 100 ms shutter's frame period.
        assertEquals(Range(10, 10), bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE))
        // Night vision ON locks nothing: the explicit AE unlock rides along.
        assertEquals(false, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK))

        // A non-manual focus plan leaves the AF keys out of the bundle.
        assertFalse(CaptureRequest.CONTROL_AF_MODE.name in bundle.keyNames())
        assertFalse(CaptureRequest.LENS_FOCUS_DISTANCE.name in bundle.keyNames())
        assertTrue(alwaysPresentKeys.all { it in bundle.keyNames() })
    }

    @Test
    fun `returning to defaults submits one bundle without the sensor keys`() {
        apply(CameraSettings(iso = 3200, exposureTime = 100_000_000L, nightVisionMode = NightVisionMode.ON))

        apply(CameraSettings())

        // The way back is the same single write: AE back on and the scene
        // mode reset in the bundle — and the sensor keys simply ABSENT,
        // which is what returns the sensor to automatic exposure under
        // replace semantics.
        assertEquals(2, submitted.size)
        val bundle = submitted[1]
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))
        assertFalse(sensorSensitivity in bundle.keyNames())
        assertFalse(sensorExposureTime in bundle.keyNames())
        assertEquals(Range(24, 24), bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
    }

    @Test
    fun `every manual control rides the one bundle`() {
        apply(
            CameraSettings(
                iso = 800,
                focusMode = FocusMode.MANUAL,
                focusDistance = 2f,
                whiteBalance = WhiteBalance.MANUAL,
                colorTemperature = 5000,
            ),
        )

        val bundle = submitted.single()
        assertEquals(
            alwaysPresentKeys + setOf(
                CaptureRequest.CONTROL_AE_LOCK.name,
                sensorSensitivity,
                sensorExposureTime,
                CaptureRequest.COLOR_CORRECTION_MODE.name,
                CaptureRequest.COLOR_CORRECTION_GAINS.name,
                CaptureRequest.COLOR_CORRECTION_TRANSFORM.name,
                CaptureRequest.CONTROL_AF_MODE.name,
                CaptureRequest.LENS_FOCUS_DISTANCE.name,
            ),
            bundle.keyNames(),
        )
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE))
        assertEquals(2f, bundle.getCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE))
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE))
        assertEquals(
            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            bundle.getCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE),
        )
    }

    // ── capability-aware: one unadvertised value never costs its neighbours ──

    @Test
    fun `an unadvertised scene mode drops only the scene request`() {
        // The camera lists no NIGHT scene mode: the manual exposure it was
        // paired with must still reach the device, in the same submit.
        setCaptureSupport(CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_DISABLED)))

        apply(CameraSettings(iso = 3200, exposureTime = 100_000_000L, nightVisionMode = NightVisionMode.ON))

        assertEquals(1, submitted.size)
        val bundle = submitted.single()
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_DISABLED, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertEquals(3200, bundle.getCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY))
        assertEquals(100_000_000L, bundle.getCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME))
        assertEquals(Range(10, 10), bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
        // The retired control is named once per apply, and nothing else is.
        assertEquals(listOf("Capture options not supported by this camera: scene mode"), unsupportedReports())
    }

    @Test
    fun `unadvertised optical stabilization switches OIS off and leaves every other key alone`() {
        val settings = CameraSettings(iso = 800, exposureTime = 20_000_000L)
        apply(settings)
        val unrestricted = submitted.single().byKey()
        assertTrue(unsupportedReports().isEmpty())

        // A lens without OIS — the common case behind the per-group split:
        // stabilization defaults to on, so OIS-on is requested on every lens.
        setCaptureSupport(
            CaptureSupport(opticalStabilizationModes = setOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)),
        )

        apply(settings)

        val bundle = submitted[1].byKey()
        assertEquals(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF, bundle[opticalStabilization])
        // Every other key — AE, the sensor pair, the fps range — is the
        // unrestricted bundle's, value for value.
        assertEquals(unrestricted - opticalStabilization, bundle - opticalStabilization)
        // Video stabilization is a separate list and was never restricted.
        assertEquals(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON, bundle[videoStabilization])
        assertEquals(listOf("Capture options not supported by this camera: optical stabilization"), unsupportedReports())
    }

    @Test
    fun `a fully advertised camera logs nothing as unsupported`() {
        setCaptureSupport(
            CaptureSupport(
                opticalStabilizationModes = setOf(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                ),
            ),
        )

        apply(CameraSettings(iso = 800))

        assertTrue(unsupportedReports().isEmpty())
    }

    // ── the re-assert rungs ──

    @Test
    fun `each post-bind rung is one submit`() {
        apply(CameraSettings(iso = 800))
        assertEquals(1, submitted.size)

        // The bind's deferred ladder, on the main looper the rungs ride.
        CameraService::class.java.getDeclaredMethod("schedulePostBindControlsApply").apply {
            isAccessible = true
        }.invoke(service)
        val looper = mainLooper()
        looper.idle()
        // Scheduled, not fired: the settle window is still open.
        assertEquals(1, submitted.size)

        looper.idleFor(Duration.ofMillis(399))
        assertEquals("the 400 ms rung has not fired yet", 1, submitted.size)
        looper.idleFor(Duration.ofMillis(2))
        assertEquals("the 400 ms rung", 2, submitted.size)
        looper.idleFor(Duration.ofMillis(1098))
        assertEquals("the 1500 ms rung has not fired yet", 2, submitted.size)
        looper.idleFor(Duration.ofMillis(2))
        assertEquals("the 1500 ms rung", 3, submitted.size)
        // A rung re-asserts the applied settings verbatim.
        assertEquals(submitted[0].byKey(), submitted[1].byKey())
        assertEquals(submitted[0].byKey(), submitted[2].byKey())
    }

    @Test
    fun `a first observed OPEN re-asserts the controls and a re-stated OPEN does not`() {
        observeCameraOpenState()
        mainLooper().idle() // the posted attach lands; the camera has not opened yet
        assertEquals(0, submitted.size)

        // The camera opens LATER than every timed rung: the observed OPEN is
        // the first moment the session is known to take controls.
        cameraState.value = CameraState.create(CameraState.Type.OPENING)
        assertEquals(0, submitted.size)
        cameraState.value = CameraState.create(CameraState.Type.OPEN)
        assertEquals(1, submitted.size)
        assertTrue(service.cameraOpenObserved.value)

        // LiveData re-delivers the current value: not an edge, no re-assert
        // (a re-assert carries a force-focus apply).
        cameraState.value = CameraState.create(CameraState.Type.OPEN)
        assertEquals(1, submitted.size)
    }

    @Test
    fun `an OPEN from a camera a newer bind replaced does not re-assert`() {
        observeCameraOpenState()
        mainLooper().idle()
        // A newer bind landed before this camera's open was observed.
        setLiveCamera(mockk<Camera>(relaxed = true))

        cameraState.value = CameraState.create(CameraState.Type.OPEN)

        assertEquals(0, submitted.size)
    }

    // ── failure visibility ──

    @Test
    fun `a canceled submit logs at debug and a refused one at warn`() {
        apply(CameraSettings(iso = 800))
        val canceled = submitFutures.single()
        // Superseded by a newer submit, or the session closed under it.
        canceled.setException(CameraControl.OperationCanceledException("superseded"))
        val canceledLine = serviceLogs().single { it.msg.startsWith("Capture options submit") }
        assertEquals(Log.DEBUG, canceledLine.type)
        assertTrue(canceledLine.msg.contains("canceled"))

        ShadowLog.clear()
        apply(CameraSettings(iso = 1600))
        submitFutures.last().setException(IllegalStateException("device refused the request"))
        val failedLine = serviceLogs().single { it.msg.startsWith("Capture options submit") }
        assertEquals(Log.WARN, failedLine.type)
        assertEquals("Capture options submit failed", failedLine.msg)
        assertTrue(failedLine.throwable is IllegalStateException)
    }

    // ── helpers ──

    private fun observeCameraOpenState() {
        CameraService::class.java.getDeclaredMethod("observeCameraOpenState", Camera::class.java).apply {
            isAccessible = true
        }.invoke(service, camera)
    }
}
