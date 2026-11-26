package com.raulshma.lenscast.camera

import android.os.Looper
import android.util.Range
import androidx.camera.core.CameraState as CameraXCameraState
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.raulshma.lenscast.camera.model.CameraErrorPolicy
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.CaptureSupport
import com.raulshma.lenscast.camera.model.SessionDemand
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle and bind-time session tests for [CameraService]:
 * - [CameraService.onActivityResume] recovery from [CameraErrorPolicy.Verdict.POLICY_DENIED]
 *   across cleared preview demand, keep-alive demand, and active preview demand, while
 *   leaving [CameraErrorPolicy.Verdict.REBOOT_REQUIRED] standing.
 * - Frame-rate changes in [CameraService.applySettings] rebinding through
 *   [SessionConfig] with the folded fps range, and fallback to the legacy vararg
 *   `bindToLifecycle` when all `SessionConfig` attempts throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CameraServiceTestApplication::class)
class CameraServiceLifecycleTest {

    private val fixture = CameraServiceFixture()

    @Before
    fun setUp() {
        fixture.install()
        fixture.plantStandardProvider()
        fixture.service.setLifecycleOwner(mockk<LifecycleOwner>(relaxed = true))
    }

    private fun setCameraState(state: CameraState) {
        @Suppress("UNCHECKED_CAST")
        val flow = fixture.serviceField<kotlinx.coroutines.flow.MutableStateFlow<CameraState>>("_cameraState")
        flow.value = state
    }

    private fun setErrorRebindAttempts(attempts: Int) {
        fixture.serviceField<AtomicInteger>("errorRebindAttempts").set(attempts)
    }

    private fun errorRebindAttempts(): Int =
        fixture.serviceField<AtomicInteger>("errorRebindAttempts").get()

    private fun setSessionDemand(demand: SessionDemand) {
        fixture.setServiceField("sessionDemand", demand)
    }

    @Test
    fun `onActivityResume clears POLICY_DENIED and resets attempts when no demand is active`() {
        setErrorRebindAttempts(2)
        setCameraState(
            CameraState.Error(
                "Camera disabled by policy (while-in-use denial)",
                CameraErrorPolicy.Verdict.POLICY_DENIED,
            ),
        )

        fixture.service.onActivityResume()

        assertEquals(CameraState.Ready, fixture.service.cameraState.value)
        assertEquals(0, errorRebindAttempts())
        assertTrue(fixture.binds.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `onActivityResume rebinds keep-alive session on POLICY_DENIED when preview is not requested`() {
        fixture.service.acquireKeepAlive()
        setErrorRebindAttempts(1)
        setCameraState(
            CameraState.Error(
                "Camera disabled by policy (while-in-use denial)",
                CameraErrorPolicy.Verdict.POLICY_DENIED,
            ),
        )

        fixture.service.onActivityResume()

        assertEquals(1, fixture.binds.size)
        assertEquals(CameraState.Ready, fixture.service.cameraState.value)
        assertEquals(0, errorRebindAttempts())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `onActivityResume rebinds preview on POLICY_DENIED when preview is requested`() {
        setSessionDemand(SessionDemand(previewRequested = true, isActivityForeground = false))
        setErrorRebindAttempts(3)
        setCameraState(
            CameraState.Error(
                "Camera disabled by policy (while-in-use denial)",
                CameraErrorPolicy.Verdict.POLICY_DENIED,
            ),
        )

        fixture.service.onActivityResume()

        assertEquals(1, fixture.binds.size)
        assertEquals(CameraState.Ready, fixture.service.cameraState.value)
        assertEquals(0, errorRebindAttempts())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `onActivityResume does not clear REBOOT_REQUIRED error`() {
        val fatal = CameraState.Error(
            CameraService.CAMERA_FATAL_MESSAGE,
            CameraErrorPolicy.Verdict.REBOOT_REQUIRED,
        )
        setCameraState(fatal)

        fixture.service.onActivityResume()

        assertEquals(fatal, fixture.service.cameraState.value)
        assertTrue(fixture.binds.isEmpty())
    }

    private fun applyOffMain(settings: CameraSettings) {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                fixture.service.applySettings(settings)
                done.complete(Unit)
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        val looper = shadowOf(Looper.getMainLooper())
        while (!done.isCompleted) {
            looper.idle()
            Thread.sleep(5)
        }
        runBlocking { done.await() }
    }

    @Test
    fun `applySettings changing frameRate rebinds via SessionConfig with folded fps range`() {
        fixture.service.acquireKeepAlive()
        fixture.setCaptureSupport(
            CaptureSupport(
                targetFpsRanges = setOf(
                    CaptureSupport.FpsRange(30, 30),
                    CaptureSupport.FpsRange(30, 60),
                ),
            ),
        )

        // Seed initial bound settings at 24 fps without triggering a rebind (24 == default).
        applyOffMain(CameraSettings(frameRate = 24))
        assertTrue(fixture.sessionConfigs.isEmpty())

        // Changing to 60 fps triggers a rebind using SessionConfig with the folded [30, 60] range.
        applyOffMain(CameraSettings(frameRate = 60))

        assertEquals(1, fixture.sessionConfigs.size)
        assertEquals(Range(30, 60), fixture.sessionConfigs.single().frameRateRange)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `bindLargestCompatible falls back to legacy vararg bind when SessionConfig binds throw`() {
        val provider = fixture.serviceField<ProcessCameraProvider>("cameraProvider")
        every {
            provider.bindToLifecycle(any(), any(), any<SessionConfig>())
        } throws IllegalArgumentException("SessionConfig frame rate rejected by mock HAL")

        fixture.service.acquireKeepAlive()
        val bound = fixture.service.rebindUseCases()

        assertTrue(bound)
        assertEquals(CameraState.Ready, fixture.service.cameraState.value)
        assertEquals(1, fixture.binds.size)
        assertTrue(fixture.sessionConfigs.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `startPreview ignores stale pre-bind POLICY_DENIED replay but publishes fresh denial`() {
        val staleError = CameraXCameraState.create(
            CameraXCameraState.Type.CLOSED,
            CameraXCameraState.StateError.create(CameraXCameraState.ERROR_CAMERA_DISABLED),
        )
        fixture.cameraState.value = staleError

        fixture.service.startPreview(mockk<PreviewView>(relaxed = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CameraState.Ready, fixture.service.cameraState.value)

        val freshError = CameraXCameraState.create(
            CameraXCameraState.Type.CLOSED,
            CameraXCameraState.StateError.create(CameraXCameraState.ERROR_CAMERA_DISABLED),
        )
        fixture.cameraState.value = freshError
        shadowOf(Looper.getMainLooper()).idle()

        val state = fixture.service.cameraState.value
        assertTrue(state is CameraState.Error)
        assertEquals(CameraErrorPolicy.Verdict.POLICY_DENIED, (state as CameraState.Error).recovery)
    }

    @Test
    fun `rebindUseCases re-enumerates and binds available camera when selected lens disappears`() {
        val provider = fixture.serviceField<ProcessCameraProvider>("cameraProvider")
        val remainingInfo = mockk<androidx.camera.core.CameraInfo>(relaxed = true)
        every { remainingInfo.lensFacing } returns androidx.camera.core.CameraSelector.LENS_FACING_BACK
        every { provider.availableCameraInfos } returns listOf(remainingInfo)

        // Plant a selector that filters everything out (stale camera ID dropped by HAL)
        // followed by a relaxed bind once re-enumeration selects an available lens.
        var attempts = 0
        every { provider.bindToLifecycle(any(), any(), any<SessionConfig>()) } answers {
            attempts++
            if (attempts == 1) throw IllegalArgumentException("No available camera can be found")
            fixture.sessionConfigs += thirdArg<SessionConfig>()
            fixture.binds += thirdArg<SessionConfig>().useCases
            fixture.camera
        }

        fixture.service.acquireKeepAlive()
        val bound = fixture.service.rebindUseCases()

        assertTrue(bound)
        assertEquals(CameraState.Ready, fixture.service.cameraState.value)
        assertTrue(attempts >= 2)
    }
}
