package com.raulshma.lenscast.streaming

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.BatteryOptimizationResult
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.WatchdogPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.concurrent.thread

/**
 * Choreography pass over the real [StreamingSession] — the single owner of
 * the live-stream begin()/end()/recover(tier) ladder. Collaborators are
 * mockk seams; the [Context] is Robolectric's real application context so
 * the foreground-service intents are captured whole (actions AND extras,
 * which is where the late-output microphone re-assert actually lives) and
 * `Dispatchers.Main` resolves to the Robolectric main looper.
 *
 * **Not coverable here:** the camera re-initialization FAILURE branch of
 * `recover(HARD)` (a stubbed `initialize()` result delivered through
 * Robolectric's Main-looper dispatcher never reaches the caller — the restart
 * seam's failure is pinned instead), the 30 s battery-optimization polling
 * loop's periodic re-application (real-time delay, no injectable scheduler —
 * the loop's launch path is exercised, its cadence is not) and the OS-level
 * foreground-service type enforcement (device-only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingSessionTest {

    private val appContext: Application = RuntimeEnvironment.getApplication()

    private val optimizationResult =
        MutableStateFlow(BatteryOptimizationResult(suggestedJpegQuality = 70, batteryLevel = 80, isPowerSaveMode = false, message = ""))
    private val streamUrl = MutableStateFlow("http://0.0.0.0:8080")
    private val isAudioStreaming = MutableStateFlow(false)
    private val isRtspRunning = MutableStateFlow(false)

    private val cameraService: CameraService = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val streamWatchdog: StreamWatchdog = mockk(relaxed = true)

    /**
     * The streaming-restart answer, read at call time. (Suspend answers
     * dispatched through Robolectric's Main looper do not reach the caller
     * reliably — a stubbed [CameraService.initialize] result never lands — so
     * the HARD recovery's failure path is pinned on this seam, whose call
     * stays on the caller's dispatcher.)
     */
    private var startStreamingResult = true

    private lateinit var session: StreamingSession

    @Before
    fun setUp() {
        coEvery { cameraService.initialize() } returns Result.success(Unit)
        every { streamingManager.streamUrl } returns streamUrl
        every { streamingManager.isAudioStreaming } returns isAudioStreaming
        every { streamingManager.isRtspRunning } returns isRtspRunning
        every { streamingManager.isLiveStreaming() } returns false
        every { streamingManager.isWebStreamActive() } returns false
        every { streamingManager.isRtspAudioActive() } returns false
        every { streamingManager.isRtmpAudioActive() } returns false
        every { streamingManager.isWhipAudioActive() } returns false
        every { streamingManager.ensureServerRunning() } returns true
        every { streamingManager.startStreaming() } answers { startStreamingResult }
        every { powerManager.optimizationResult } returns optimizationResult
        session = StreamingSession(
            context = appContext,
            cameraService = cameraService,
            streamingManagerProvider = { streamingManager },
            powerManager = powerManager,
            thermalMonitor = thermalMonitor,
            watchdogProvider = { streamWatchdog },
        )
    }

    // ── helpers ──

    /**
     * Runs the suspend [block] on a worker thread while the test thread
     * (Robolectric's main thread) idles the main looper, so the session's
     * bounded `Dispatchers.Main` seams are serviced without deadlocking.
     */
    private fun <T> runWithMainPumped(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        thread(isDaemon = true) {
            try {
                result.complete(runBlocking { block() })
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        val mainLooper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (!result.isCompleted && System.currentTimeMillis() < deadline) {
            mainLooper.idle()
            Thread.sleep(5)
        }
        mainLooper.idle()
        check(result.isCompleted) { "Session block never completed" }
        return runBlocking { result.await() }
    }

    private fun startedServiceIntents(): List<Intent> =
        shadowOf(appContext).allStartedServices

    // ── begin ──

    @Test
    fun `begin attaches the full environment and a second late begin only re-asserts the foreground service`() {
        runWithMainPumped { session.begin() }
        runWithMainPumped { session.begin() }

        assertTrue(session.isActive)
        // Exactly one full attach…
        verify(exactly = 1) { powerManager.acquireWakeLock() }
        verify(exactly = 1) { thermalMonitor.startMonitoring() }
        verify(exactly = 1) { streamWatchdog.startMonitoring() }
        verify(exactly = 1) { cameraService.acquireKeepAlive() }
        // …and two foreground START intents (the second output's re-assert).
        val starts = startedServiceIntents().filter { it.action == StreamingService.ACTION_START }
        assertEquals(2, starts.size)
    }

    @Test
    fun `second begin after an audio output went live re-asserts the microphone foreground type`() {
        runWithMainPumped { session.begin() }

        // The RTSP output starts late, already carrying its audio track.
        every { streamingManager.isRtspAudioActive() } returns true
        runWithMainPumped { session.begin() }

        val intents = startedServiceIntents().filter { it.action == StreamingService.ACTION_START }
        assertEquals(2, intents.size)
        assertFalse(
            "the first (web-only) begin must not claim the microphone",
            intents[0].getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, true),
        )
        assertTrue(
            "the late audio-carrying output must re-assert the MICROPHONE type",
            intents[1].getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, false),
        )
        // Idempotency: still no second full attach.
        verify(exactly = 1) { powerManager.acquireWakeLock() }
        verify(exactly = 1) { cameraService.acquireKeepAlive() }
    }

    @Test
    fun `begin failure unwinds every partially attached piece and stays inactive`() {
        every { streamWatchdog.startMonitoring() } throws IllegalStateException("watchdog exploded")

        val failure = runCatching { runWithMainPumped { session.begin() } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(session.isActive)
        // The attach had reached the foreground START, so the unwind answers it.
        verify(exactly = 1) { powerManager.acquireWakeLock() }
        verify(exactly = 1) { powerManager.releaseWakeLock() }
        verify(exactly = 1) { thermalMonitor.stopMonitoring() }
        verify(exactly = 1) { streamWatchdog.stopMonitoring() }
        verify(exactly = 1) { cameraService.releaseKeepAlive() }
        val last = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_PAUSE, last.action)
    }

    // ── end ──

    @Test
    fun `end tears down only when no stream is live and is a no-op afterwards`() {
        runWithMainPumped { session.begin() }

        // Another output still live: end must not touch anything.
        every { streamingManager.isLiveStreaming() } returns true
        runWithMainPumped { session.end() }
        assertTrue(session.isActive)
        verify(exactly = 0) { powerManager.releaseWakeLock() }
        verify(exactly = 0) { streamWatchdog.stopMonitoring() }

        // Everything stopped: full teardown.
        every { streamingManager.isLiveStreaming() } returns false
        runWithMainPumped { session.end() }
        assertFalse(session.isActive)
        verify(exactly = 1) { powerManager.releaseWakeLock() }
        verify(exactly = 1) { thermalMonitor.stopMonitoring() }
        verify(exactly = 1) { streamWatchdog.stopMonitoring() }
        verify(exactly = 1) { cameraService.releaseKeepAlive() }
        assertEquals(StreamingService.ACTION_PAUSE, startedServiceIntents().last().action)

        // A second end with no active session is a no-op.
        runWithMainPumped { session.end() }
        verify(exactly = 1) { powerManager.releaseWakeLock() }
        assertEquals(StreamingService.ACTION_PAUSE, startedServiceIntents().last().action)
    }

    // ── recover tiers ──

    @Test
    fun `soft recovery rebinds the camera use cases only`() {
        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.SOFT) }

        assertTrue(recovered)
        verify(exactly = 1) { cameraService.rebindUseCases() }
        verify(exactly = 0) { streamingManager.pauseStreaming() }
        verify(exactly = 0) { streamingManager.ensureServerRunning() }
        verify(exactly = 0) { streamingManager.stopStreaming() }
        coVerify(exactly = 0) { cameraService.initialize() }
    }

    @Test
    fun `medium recovery restarts the server rebinds and restores only what went dark`() {
        // Web was live before the disturbance and is dark after the pause;
        // RTSP was live and stayed up (the manager re-attached it on restart).
        every { streamingManager.isWebStreamActive() } returnsMany listOf(true, false)
        isRtspRunning.value = true

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertTrue(recovered)
        verify(exactly = 1) { streamingManager.pauseStreaming() }
        verify(exactly = 1) { streamingManager.ensureServerRunning() }
        verify(exactly = 1) { cameraService.rebindUseCases() }
        verify(exactly = 1) { streamingManager.startWebStreaming() }
        verify(exactly = 0) { streamingManager.startRtspStreaming() }
        coVerify(exactly = 0) { cameraService.initialize() }
    }

    @Test
    fun `medium recovery restarts an output the pause killed`() {
        every { streamingManager.isWebStreamActive() } returns false
        isRtspRunning.value = true
        every { streamingManager.pauseStreaming() } answers { isRtspRunning.value = false }

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertTrue(recovered)
        // rtspWasActive snapped true before the pause, the flow reads false
        // after → the session restarts it.
        verify(exactly = 1) { streamingManager.startRtspStreaming() }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
    }

    @Test
    fun `medium recovery reports failure when the server cannot restart`() {
        every { streamingManager.ensureServerRunning() } returns false

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertFalse(recovered)
        verify(exactly = 0) { cameraService.rebindUseCases() }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
    }

    @Test
    fun `hard recovery re-initializes the camera and refreshes the disturbed session state`() {
        runWithMainPumped { session.begin() }
        verify(exactly = 1) { thermalMonitor.startMonitoring() }
        // RTSP is live when the disturbance hits; HARD stops streaming outright
        // and the output goes down with it.
        isRtspRunning.value = true
        every { streamingManager.stopStreaming() } answers { isRtspRunning.value = false }
        every { streamingManager.isWebStreamActive() } returnsMany listOf(true, false)

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) }

        assertTrue(recovered)
        coVerify(exactly = 1) { cameraService.initialize() }
        verify(exactly = 1) { streamingManager.stopStreaming() }
        verify(exactly = 1) { streamingManager.startStreaming() }
        // The refresh of what the recovery disturbed: battery state, thermal
        // monitoring, battery-optimization application, camera rebind.
        verify(exactly = 2) { thermalMonitor.startMonitoring() }
        verify(atLeast = 2) { powerManager.refreshBatteryState() }
        verify(atLeast = 2) { streamingManager.applyBatteryOptimization(any()) }
        verify(atLeast = 2) { cameraService.rebindUseCases() }
        // Both outputs were live before and dark after → both restored.
        verify(exactly = 1) { streamingManager.startWebStreaming() }
        verify(exactly = 1) { streamingManager.startRtspStreaming() }
    }

    @Test
    fun `hard recovery reports failure when streaming cannot restart`() {
        startStreamingResult = false

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) }

        assertFalse(recovered)
        // The camera re-init ran before the restart failed, but the killed
        // outputs were left down for the watchdog's verification to retry.
        coVerify(exactly = 1) { cameraService.initialize() }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
        verify(exactly = 0) { streamingManager.startRtspStreaming() }
    }

    @Test
    fun `all three tiers complete without an active session`() {
        // The watchdog may fire recovery while the session was never begun
        // (stream started before the session attach): no begin-path pieces may
        // be torn down or refreshed, and every tier still answers.
        assertTrue(runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.SOFT) })
        assertTrue(runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) })
        assertTrue(runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) })
        verify(exactly = 0) { powerManager.releaseWakeLock() }
        verify(exactly = 0) { cameraService.releaseKeepAlive() }
    }

    // ── foreground intent payload ──

    @Test
    fun `foreground start carries the stream url and every mic-capturing output's verdict`() {
        every { streamingManager.isAudioStreaming.value } returns true
        every { streamingManager.isRtmpAudioActive() } returns true
        runWithMainPumped { session.begin() }

        val intent = startedServiceIntents().first()
        assertEquals(StreamingService.ACTION_START, intent.action)
        assertEquals("http://0.0.0.0:8080", intent.getStringExtra(StreamingService.EXTRA_URL))
        assertTrue(intent.getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, false))
    }

    @Test
    fun `foreground pause carries no audio claim`() {
        runWithMainPumped { session.begin() }
        every { streamingManager.isLiveStreaming() } returns false
        runWithMainPumped { session.end() }

        val pause = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_PAUSE, pause.action)
        assertFalse(pause.hasExtra(StreamingService.EXTRA_AUDIO_ACTIVE))
    }
}
