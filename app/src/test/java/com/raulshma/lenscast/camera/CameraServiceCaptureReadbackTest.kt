package com.raulshma.lenscast.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CaptureSupport
import com.raulshma.lenscast.camera.model.NightVisionMode
import io.mockk.Called
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The read-back's Android half — CameraService's per-frame hook over the pure
 * tracker: a submit arms it, a capture result feeds it, and the line it
 * concludes with is logged. The verdicts, the line format and the sampling
 * rules are pinned in their own JVM suites; this pins the wiring around
 * them, the submit+arm serialisation, and the bind-time capability read.
 * The full account is CONTEXT.md's "Capture Read-back"; the shared stand-in
 * is [CameraServiceFixture].
 *
 * JVM-level limits, deliberately not papered over: the hook is driven by hand
 * with a stub `TotalCaptureResult` (a real one only exists on a device), and
 * the binds run against a stand-in provider — what CameraX does with the use
 * cases inside a real session is device-only code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceTestApplication::class)
class CameraServiceCaptureReadbackTest {

    private val fixture = CameraServiceFixture()

    private val submitted get() = fixture.submitted

    /** The read-back's frame hook, as registered on the analysis use case. */
    private lateinit var hook: CameraCaptureSession.CaptureCallback

    @Before
    fun setUp() {
        fixture.install()
        hook = fixture.serviceField("captureReadbackCallback")
    }

    private fun setCaptureSupport(support: CaptureSupport) = fixture.setCaptureSupport(support)

    private fun apply(settings: CameraSettings) = fixture.apply(settings)

    /** A capture result reporting the given values; null means the device did not report the key. */
    private fun captureResult(
        aeMode: Int? = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity: Int? = 3200,
        sensorExposureTimeNs: Long? = 100_000_000L,
        sceneMode: Int? = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
        fpsRange: android.util.Range<Int>? = null,
    ): TotalCaptureResult = mockk {
        every { get(CaptureResult.CONTROL_AE_MODE) } answers { aeMode }
        every { get(CaptureResult.SENSOR_SENSITIVITY) } answers { sensorSensitivity }
        every { get(CaptureResult.SENSOR_EXPOSURE_TIME) } answers { sensorExposureTimeNs }
        every { get(CaptureResult.CONTROL_SCENE_MODE) } answers { sceneMode }
        every { get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE) } answers { fpsRange }
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
        setCaptureSupport(CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_DISABLED)))
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
    fun `an armed hook reads exactly the five read-back keys`() {
        armManualNight()

        val result = captureResult()
        frame(result)

        verify(exactly = 1) { result.get(CaptureResult.CONTROL_AE_MODE) }
        verify(exactly = 1) { result.get(CaptureResult.SENSOR_SENSITIVITY) }
        verify(exactly = 1) { result.get(CaptureResult.SENSOR_EXPOSURE_TIME) }
        verify(exactly = 1) { result.get(CaptureResult.CONTROL_SCENE_MODE) }
        verify(exactly = 1) { result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE) }
        confirmVerified(result)
    }

    @Test
    fun `an fps range mismatch is logged at timeout when device clamps fps`() {
        apply(CameraSettings(frameRate = 60))

        val clamped = captureResult(
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
            sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
            fpsRange = android.util.Range(30, 30),
        )
        frame(clamped)
        assertTrue(readbackLines().isEmpty())

        ShadowSystemClock.advanceBy(Duration.ofMillis(10_000))
        frame(clamped)

        assertEquals(
            listOf(
                "Camera controls readback: MISMATCH requested AE=ON scene=DISABLED fps=[60..60] " +
                    "| device AE=ON scene=DISABLED fps=[30..30]",
            ),
            readbackLines().map { it.msg },
        )
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
        assertEquals("Camera controls readback: disabled — the capture-result hook failed", lines.single().msg)
        assertTrue(lines.single().throwable is IllegalStateException)

        // Off for good: later submits do not arm, so a healthy frame is not
        // read and nothing more is logged.
        apply(CameraSettings(iso = 800))
        val healthy = captureResult()
        frame(healthy)
        verify { healthy wasNot Called }
        assertEquals(1, readbackLines().size)
    }

    // ── submit and arm are one step ──

    @Test
    fun `a submit and its arm are serialised - the armed expectation is the last submitted bundle`() {
        // Apply A parks INSIDE its submit; apply B must not be able to slip
        // its own submit+arm past it, or the tracker ends up armed with A's
        // values while the device is running B's.
        val aParked = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val parked = AtomicBoolean(false)
        fixture.onSubmitInterceptors += { _ ->
            if (parked.compareAndSet(false, true)) {
                aParked.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
            }
        }

        val applyA = thread {
            fixture.apply(CameraSettings(iso = 800, exposureTime = 41_666_666L))
        }
        assertTrue("A reached its submit", aParked.await(5, TimeUnit.SECONDS))

        val applyB = thread {
            fixture.apply(CameraSettings(iso = 1600, exposureTime = 41_666_666L))
        }
        // Whatever B does, it must not submit while A holds the step: give
        // it every chance to rush through before A is released.
        Thread.sleep(300)
        assertEquals("B waited for A's submit+arm", 1, submitted.size)

        releaseA.countDown()
        applyA.join(5_000)
        applyB.join(5_000)
        assertFalse("A finished", applyA.isAlive)
        assertFalse("B finished", applyB.isAlive)
        assertEquals(2, submitted.size)

        // The armed expectation is B's — the LAST submitted bundle: a frame
        // reporting exactly B's values is a confirmation. Had the tracker
        // been left armed with A's interleaved bundle, this frame would sit
        // outside A's tolerance (ISO 1600 vs 800) and conclude nothing.
        frame(
            captureResult(
                sensorSensitivity = 1600,
                sensorExposureTimeNs = 41_666_666L,
                sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
            ),
        )
        assertEquals(
            listOf("Camera controls readback: ok (AE=OFF ISO=1600 exp=41666666ns scene=DISABLED)"),
            readbackLines().map { it.msg },
        )
    }

    // ── the bind-time capability read ──

    @Test
    fun `a failing capability query at bind keeps the previous support lists`() {
        val known = CaptureSupport(sceneModes = setOf(7))
        fixture.setCaptureSupport(known)
        fixture.plantStandardProvider()
        fixture.service.setLifecycleOwner(mockk<androidx.lifecycle.LifecycleOwner>(relaxed = true))

        // The stand-in camera has no Camera2 interop info, so the bind-time
        // query cannot read a camera id: the failure must leave the previous
        // lists standing, not empty them into "unsupported".
        val bound = bindUseCases()

        assertTrue(bound)
        assertEquals(known, fixture.serviceField<CaptureSupport>("captureSupport"))
        assertTrue(
            "the failure is logged",
            ShadowLog.getLogsForTag("CameraService")
                .any { it.msg.startsWith("Failed to query capture support") },
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ── the registration ──

    @Test
    fun `the recording bind re-binds the analysis use case that carries the read-back hook`() {
        fixture.plantStandardProvider()
        fixture.service.setLifecycleOwner(mockk<androidx.lifecycle.LifecycleOwner>(relaxed = true))

        bindUseCases()
        assertTrue(fixture.service.bindRecording(mockk<VideoCapture<Recorder>>(relaxed = true)))

        // The standard bind built the analysis use case with the hook, and
        // the recording bind handed CameraX that very instance again.
        assertEquals(2, fixture.binds.size)
        val analyses = fixture.binds.map { it.filterIsInstance<ImageAnalysis>() }
        assertEquals(listOf(1, 1), analyses.map { it.size })
        val analysis = analyses[0].single()
        assertSame(analysis, analyses[1].single())
        assertTrue(analyses[1].single() in fixture.binds[1])
        // The hook is an option of the use case's own config — what every
        // bind re-reads — and the config is immutable: still there after the
        // standard bind, so the recording bind carries it too.
        assertTrue(useCaseConfigCarries(analysis, hook))
        // The probe discriminates: an analysis use case built without the
        // interop call does not carry the hook.
        assertFalse(useCaseConfigCarries(ImageAnalysis.Builder().build(), hook))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun bindUseCases(): Boolean {
        val provider = fixture.serviceField<ProcessCameraProvider>("cameraProvider")
        val owner = fixture.service.getEffectiveLifecycleOwner()!!
        return CameraService::class.java
            .getDeclaredMethod("bindUseCases", ProcessCameraProvider::class.java, androidx.lifecycle.LifecycleOwner::class.java)
            .apply { isAccessible = true }
            .invoke(fixture.service, provider, owner) as Boolean
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
