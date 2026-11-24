package com.raulshma.lenscast.camera

import android.hardware.camera2.CaptureRequest
import android.os.Looper
import android.util.Log
import android.util.Range
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraState
import androidx.camera.core.impl.Config as CameraXConfig
import com.raulshma.lenscast.camera.model.CameraControlPlan
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CaptureSupport
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * The one-bundle contract of the controls apply — issue #25 pinned where it
 * happened: the capture-request options travel in ONE bundle, submitted once
 * per apply, through the [CaptureOptionsSubmitter] seam. The capability fold
 * retires what the bound camera does not advertise, so one unsupported
 * control never costs its neighbours — and never rides as a value the
 * device would ignore. The full account is CONTEXT.md's "Capture Options
 * Submit"; the shared stand-in is [CameraServiceFixture].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceTestApplication::class)
class CameraServiceCaptureBundleTest {

    private val fixture = CameraServiceFixture()

    /** Every bundle handed to the device, in submit order. */
    private val submitted get() = fixture.submitted

    private val submitFutures get() = fixture.submitFutures

    /** The center meterings the applies fired, in order — the force-focus reapply's record. */
    private val meterings get() = fixture.meteringActions

    private fun setCaptureSupport(support: CaptureSupport) = fixture.setCaptureSupport(support)

    private fun apply(settings: CameraSettings) = fixture.apply(settings)

    private fun mainLooper(): ShadowLooper = shadowOf(Looper.getMainLooper())

    private fun serviceLogs(): List<ShadowLog.LogItem> = ShadowLog.getLogsForTag("CameraService")

    /** The apply-time "not supported" reports, in order — empty unless a plan retired something. */
    private fun unsupportedReports(): List<String> =
        serviceLogs().map { it.msg }.filter { it.startsWith("Capture options not supported") }

    @Before
    fun setUp() {
        fixture.install()
    }

    // ── bundle readers ──

    /**
     * A bundle's Camera2 key names, read off `listOptions()`: every option's
     * token is its own [CaptureRequest.Key], so a key the bundle does not
     * carry is simply absent from this set.
     */
    private fun androidx.camera.camera2.interop.CaptureRequestOptions.keyNames(): Set<String> =
        listOptions().mapNotNull { (it.token as? CaptureRequest.Key<*>)?.name }.toSet()

    /** The bundle as key name → value (`retrieveOption`), for whole-bundle comparisons. */
    private fun androidx.camera.camera2.interop.CaptureRequestOptions.byKey(): Map<String, Any?> = listOptions().associate { option ->
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

        // A lens without OIS: stabilization defaults to on, so OIS-on is
        // requested on every lens whether or not the lens has it.
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
    fun `manual white balance on a lens without manual post-processing goes out as automatic`() {
        setCaptureSupport(CaptureSupport(manualPostProcessing = false))

        apply(CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 4200))

        // Manual white balance is AWB OFF driven by the color-correction
        // gains; without MANUAL_POST_PROCESSING the gains have nothing to
        // drive and bare AWB OFF would be white balance OFF at the default
        // gains — so the whole request falls back to automatic.
        assertEquals(1, submitted.size)
        val bundle = submitted.single()
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE))
        assertFalse(CaptureRequest.COLOR_CORRECTION_MODE.name in bundle.keyNames())
        assertFalse(CaptureRequest.COLOR_CORRECTION_GAINS.name in bundle.keyNames())
        assertFalse(CaptureRequest.COLOR_CORRECTION_TRANSFORM.name in bundle.keyNames())
        assertEquals(
            listOf("Capture options not supported by this camera: ${CameraControlPlan.UNSUPPORTED_MANUAL_WHITE_BALANCE}"),
            unsupportedReports(),
        )
    }

    @Test
    fun `a manual exposure a lens cannot hold in AE goes out without the sensor keys`() {
        // AE OFF is not advertised: the HAL would ignore SENSOR_* under its
        // automatic exposure, and the read-back would compare against values
        // the device never runs — so the fold retires the pair with AE.
        setCaptureSupport(
            CaptureSupport(aeModes = setOf(CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)),
        )

        apply(CameraSettings(iso = 3200, exposureTime = 100_000_000L, nightVisionMode = NightVisionMode.ON))

        assertEquals(1, submitted.size)
        val bundle = submitted.single()
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE))
        assertFalse(sensorSensitivity in bundle.keyNames())
        assertFalse(sensorExposureTime in bundle.keyNames())
        // Everything the camera does advertise stands: the night scene, the
        // fps range, AWB.
        assertEquals(CaptureRequest.CONTROL_SCENE_MODE_NIGHT, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE))
        assertEquals(Range(10, 10), bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, bundle.getCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE))
        assertEquals(
            listOf(
                "Capture options not supported by this camera: " +
                    "${CameraControlPlan.UNSUPPORTED_AE_MODE}, ${CameraControlPlan.UNSUPPORTED_MANUAL_EXPOSURE}",
            ),
            unsupportedReports(),
        )
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
    fun `each post-bind rung is one submit and one forced metering`() {
        apply(CameraSettings(iso = 800))
        assertEquals(1, submitted.size)
        // The first (cold) apply establishes the metering — and a later
        // apply with unchanged focus fields must not re-fire it (pinned
        // separately below); the rungs are forced re-applies and do.
        assertEquals(1, meterings.size)

        // The bind's deferred ladder, on the main looper the rungs ride.
        CameraService::class.java.getDeclaredMethod("schedulePostBindControlsApply").apply {
            isAccessible = true
        }.invoke(fixture.service)
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
        // A rung re-asserts the applied settings verbatim — and forces the
        // metering with it.
        assertEquals(submitted[0].byKey(), submitted[1].byKey())
        assertEquals(submitted[0].byKey(), submitted[2].byKey())
        assertEquals(3, meterings.size)
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
        assertTrue(fixture.service.cameraOpenObserved.value)
        // The re-assert is forced: the metering fires with it.
        assertEquals(1, meterings.size)

        // LiveData re-delivers the current value: not an edge, no re-assert
        // (a re-assert carries a force-focus apply).
        cameraState.value = CameraState.create(CameraState.Type.OPEN)
        assertEquals(1, submitted.size)
        assertEquals(1, meterings.size)
    }

    @Test
    fun `an OPEN from a camera a newer bind replaced does not re-assert`() {
        observeCameraOpenState()
        mainLooper().idle()
        // A newer bind landed before this camera's open was observed.
        fixture.setLiveCamera(mockk<Camera>(relaxed = true))

        cameraState.value = CameraState.create(CameraState.Type.OPEN)

        assertEquals(0, submitted.size)
        assertEquals(0, meterings.size)
    }

    @Test
    fun `a plain re-apply of unchanged focus fields does not re-fire the metering`() {
        apply(CameraSettings(iso = 800))
        assertEquals(1, meterings.size)

        // The settings path applies on every slider tick (twice, editor and
        // Applier): a write that does not move the focus-relevant fields
        // must not cancel a deliberate tap-to-focus with a fresh metering.
        apply(CameraSettings(iso = 1600))

        assertEquals(2, submitted.size)
        assertEquals(1, meterings.size)
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

    private val cameraState get() = fixture.cameraState

    private fun observeCameraOpenState() {
        CameraService::class.java.getDeclaredMethod("observeCameraOpenState", Camera::class.java).apply {
            isAccessible = true
        }.invoke(fixture.service, fixture.camera)
    }
}
