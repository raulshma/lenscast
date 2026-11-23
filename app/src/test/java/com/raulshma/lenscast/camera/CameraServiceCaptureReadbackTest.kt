package com.raulshma.lenscast.camera

import android.app.Application
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import android.os.Looper
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExposureState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.SettableFuture
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CaptureSupport
import com.raulshma.lenscast.camera.model.NightVisionMode
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The read-back's Android half — CameraService's per-frame hook over the pure
 * [com.raulshma.lenscast.camera.model.CaptureReadbackTracker]: a submit arms
 * it, a capture result feeds it, and the line it concludes with is logged.
 * The verdicts, the line format and the sampling rules are pinned in their
 * own JVM suites; this pins the wiring around them.
 *
 * JVM-level limits, deliberately not papered over: the hook is driven by hand
 * with a stub `TotalCaptureResult` (a real one only exists on a device), and
 * the binds run against a stand-in provider — what CameraX does with the use
 * cases inside a real session is device-only code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceCaptureReadbackTest.PlainApplication::class)
class CameraServiceCaptureReadbackTest {

    class PlainApplication : Application()

    private lateinit var service: CameraService

    /** The read-back's frame hook, as registered on the analysis use case. */
    private lateinit var hook: CameraCaptureSession.CaptureCallback

    /** Every bundle handed to the device — one per controls apply. */
    private val submitted = ArrayList<CaptureRequestOptions>()

    @Before
    fun setUp() {
        service = CameraService(RuntimeEnvironment.getApplication(), CaptureOptionsSubmitter { _, bundle ->
            submitted += bundle
            SettableFuture.create<Void?>()
        })
        setServiceField("liveCamera", standInCamera())
        hook = CameraService::class.java.getDeclaredField("captureReadbackCallback").apply {
            isAccessible = true
        }.get(service) as CameraCaptureSession.CaptureCallback
    }

    private fun setServiceField(name: String, value: Any) {
        CameraService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    /** A bound camera with no flash unit, no zoom state and a usable exposure state. */
    private fun standInCamera(): Camera {
        val info = mockk<CameraInfo>(relaxed = true)
        every { info.hasFlashUnit() } returns false
        every { info.zoomState } returns MutableLiveData<ZoomState>()
        every { info.exposureState } returns mockk<ExposureState>(relaxed = true).also { state ->
            every { state.exposureCompensationRange } returns Range(-12, 12)
            every { state.exposureCompensationIndex } returns 0
        }
        return mockk<Camera>(relaxed = true).also { cam ->
            every { cam.cameraInfo } returns info
            every { cam.cameraControl } returns mockk<CameraControl>(relaxed = true)
        }
    }

    private fun apply(settings: CameraSettings) = runBlocking { service.applySettings(settings) }

    /** A capture result reporting the given values; null means the device did not report the key. */
    private fun captureResult(
        aeMode: Int? = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity: Int? = 3200,
        sensorExposureTimeNs: Long? = 100_000_000L,
        sceneMode: Int? = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
    ): TotalCaptureResult = mockk {
        every { get(CaptureResult.CONTROL_AE_MODE) } answers { aeMode }
        every { get(CaptureResult.SENSOR_SENSITIVITY) } answers { sensorSensitivity }
        every { get(CaptureResult.SENSOR_EXPOSURE_TIME) } answers { sensorExposureTimeNs }
        every { get(CaptureResult.CONTROL_SCENE_MODE) } answers { sceneMode }
    }

    /** What a camera still running its defaults reports. */
    private fun stillAutomatic() = captureResult(
        aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
        sensorSensitivity = 100,
        sensorExposureTimeNs = 41_666_666L,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
    )

    /** One frame reaching the hook, as Camera2Interop delivers it. */
    private fun frame(result: TotalCaptureResult) {
        hook.onCaptureCompleted(
            mockk<CameraCaptureSession>(relaxed = true),
            mockk<CaptureRequest>(),
            result,
        )
    }

    private fun readbackLines(): List<ShadowLog.LogItem> =
        ShadowLog.getLogsForTag("CameraService").filter { it.msg.startsWith("Camera controls readback:") }

    private fun armManualNight() {
        apply(CameraSettings(iso = 3200, exposureTime = 100_000_000L, nightVisionMode = NightVisionMode.ON))
        assertEquals("one apply is one submit", 1, submitted.size)
    }

    // ── the hook ──

    @Test
    fun `every submit arms the read-back and the next matching frame confirms it once`() {
        armManualNight()

        frame(captureResult())

        assertEquals(
            listOf("Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=NIGHT)"),
            readbackLines().map { it.msg },
        )
        assertEquals(listOf(Log.INFO), readbackLines().map { it.type })

        // Confirmed and disarmed: the frames behind the confirmation log
        // nothing, whatever they report.
        frame(stillAutomatic())
        frame(captureResult())
        assertEquals(1, readbackLines().size)
    }

    @Test
    fun `a device running something else is reported when the window closes`() {
        armManualNight()

        // Right behind the submit the camera may still report the previous
        // request: nothing is logged yet.
        frame(stillAutomatic())
        assertTrue(readbackLines().isEmpty())

        ShadowSystemClock.advanceBy(Duration.ofMillis(10_000))
        frame(stillAutomatic())

        assertEquals(listOf(Log.WARN), readbackLines().map { it.type })
        assertEquals(
            listOf(
                "Camera controls readback: MISMATCH requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                    "| device AE=ON ISO=100 exp=41666666ns scene=DISABLED",
            ),
            readbackLines().map { it.msg },
        )
    }

    @Test
    fun `the read-back checks the values that were submitted, not the ones requested`() {
        // A lens without the NIGHT scene mode: the plan retired it and the
        // bundle that went out carries DISABLED — so DISABLED is a
        // confirmation, not a mismatch.
        setServiceField("captureSupport", CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_DISABLED)))
        armManualNight()

        frame(captureResult(sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED))

        assertEquals(
            listOf("Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=DISABLED)"),
            readbackLines().map { it.msg },
        )
    }

    @Test
    fun `a later submit is confirmed against its own plan`() {
        apply(CameraSettings(iso = 800, exposureTime = 100_000_000L))
        // The device sits on values this plan did not send: no confirmation.
        frame(captureResult(sensorSensitivity = 3200, sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED))
        assertTrue(readbackLines().isEmpty())

        // A submit carrying the device's values re-arms and confirms.
        apply(CameraSettings(iso = 3200, exposureTime = 100_000_000L))
        frame(captureResult(sensorSensitivity = 3200, sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED))

        assertEquals(
            listOf("Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=DISABLED)"),
            readbackLines().map { it.msg },
        )
    }

    @Test
    fun `an unarmed hook is a no-op - it neither reads the result nor logs`() {
        // Per frame, forever: with nothing armed the capture result is not
        // even opened.
        val result = captureResult()

        frame(result)
        frame(result)

        assertTrue(readbackLines().isEmpty())
        verify { result wasNot Called }
    }

    @Test
    fun `an armed hook reads exactly the four read-back keys`() {
        armManualNight()

        val result = captureResult()
        frame(result)

        verify(exactly = 1) { result.get(CaptureResult.CONTROL_AE_MODE) }
        verify(exactly = 1) { result.get(CaptureResult.SENSOR_SENSITIVITY) }
        verify(exactly = 1) { result.get(CaptureResult.SENSOR_EXPOSURE_TIME) }
        verify(exactly = 1) { result.get(CaptureResult.CONTROL_SCENE_MODE) }
        confirmVerified(result)
    }

    @Test
    fun `a hook that throws switches the read-back off for the process with one line`() {
        apply(CameraSettings(iso = 3200))
        val broken = mockk<TotalCaptureResult> {
            every { get(CaptureResult.CONTROL_AE_MODE) } throws IllegalStateException("no camera2 result")
        }

        frame(broken)

        val lines = readbackLines()
        assertEquals(1, lines.size)
        assertEquals(Log.WARN, lines.single().type)
        assertTrue(lines.single().msg.startsWith("Camera controls readback: disabled"))
        assertTrue(lines.single().throwable is IllegalStateException)

        // Off for good: later submits do not arm, so a healthy frame is not
        // read and nothing more is logged.
        apply(CameraSettings(iso = 800))
        val healthy = captureResult()
        frame(healthy)
        verify { healthy wasNot Called }
        assertEquals(1, readbackLines().size)
    }

    // ── the registration ──

    @Test
    fun `the recording bind re-binds the analysis use case that carries the read-back hook`() {
        // A stand-in provider that records every use case it is handed. The
        // camera it returns is a bare stand-in: the capability queries in
        // the bind are each caught and logged, as on a device whose
        // characteristics cannot be read.
        val binds = ArrayList<List<UseCase>>()
        val provider = mockk<ProcessCameraProvider>(relaxed = true)
        every { provider.bindToLifecycle(any(), any(), *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            binds += when (val third = args[2]) {
                is Array<*> -> third.filterIsInstance<UseCase>()
                is Collection<*> -> third.filterIsInstance<UseCase>()
                else -> emptyList()
            }
            standInCamera()
        }
        setServiceField("cameraProvider", provider)
        val owner = mockk<LifecycleOwner>(relaxed = true)
        service.setLifecycleOwner(owner)

        bindUseCases(provider, owner)
        assertTrue(service.bindRecording(mockk<VideoCapture<Recorder>>(relaxed = true)))

        // The standard bind built the analysis use case with the hook, and
        // the recording bind handed CameraX that very instance again.
        assertEquals(2, binds.size)
        val analyses = binds.map { it.filterIsInstance<ImageAnalysis>() }
        assertEquals(listOf(1, 1), analyses.map { it.size })
        val analysis = analyses[0].single()
        assertSame(analysis, analyses[1].single())
        assertTrue(analyses[1].single() in binds[1])
        // The hook is an option of the use case's own config — what every
        // bind re-reads — and the config is immutable: still there after the
        // standard bind, so the recording bind carries it too.
        assertTrue(useCaseConfigCarries(analysis, hook))
        // The probe discriminates: an analysis use case built without the
        // interop call does not carry the hook.
        assertFalse(useCaseConfigCarries(ImageAnalysis.Builder().build(), hook))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bindUseCases(provider: ProcessCameraProvider, owner: LifecycleOwner) {
        CameraService::class.java
            .getDeclaredMethod("bindUseCases", ProcessCameraProvider::class.java, LifecycleOwner::class.java)
            .apply { isAccessible = true }
            .invoke(service, provider, owner)
    }

    /**
     * Whether [useCase]'s own config holds [candidate] as an option value.
     * Camera2Interop stores a session capture callback as a config option of
     * the use case, which is what every bind re-reads — the hook being among
     * the built use case's options is what lets it outlive a rebind. The
     * lookup is by identity and through reflection: the config types are
     * CameraX-internal, and the only fact pinned here is "the object is
     * there".
     */
    private fun useCaseConfigCarries(useCase: UseCase, candidate: Any): Boolean {
        val config = UseCase::class.java.getDeclaredField("mUseCaseConfig").apply { isAccessible = true }.get(useCase)
        val configClass = Class.forName("androidx.camera.core.impl.Config")
        val options = configClass.getMethod("listOptions").invoke(config) as Set<*>
        val retrieve = configClass.getMethod("retrieveOption", Class.forName("androidx.camera.core.impl.Config\$Option"))
        return options.any { option -> runCatching { retrieve.invoke(config, option) === candidate }.getOrDefault(false) }
    }
}
