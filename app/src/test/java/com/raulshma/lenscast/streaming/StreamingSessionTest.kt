package com.raulshma.lenscast.streaming

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraErrorPolicy
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.core.BatteryOptimizationResult
import com.raulshma.lenscast.core.KeyguardMonitor
import com.raulshma.lenscast.core.LockBypassController
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.SoftRestartLadder
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.WatchdogPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val isServerRunning = MutableStateFlow(true)

    private val cameraService: CameraService = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk(relaxed = true)
    private val powerManager: PowerManager = mockk(relaxed = true)
    private val thermalMonitor: ThermalMonitor = mockk(relaxed = true)
    private val streamWatchdog: StreamWatchdog = mockk(relaxed = true)
    private val keyguardLocked = MutableStateFlow(false)
    private val keyguardMonitor: KeyguardMonitor = mockk(relaxed = true)
    private val lockBypassController: LockBypassController = mockk(relaxed = true)

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
        every { cameraService.rebindUseCases() } returns true
        every { streamingManager.streamUrl } returns streamUrl
        every { streamingManager.isAudioStreaming } returns isAudioStreaming
        every { streamingManager.isServerRunning } returns isServerRunning
        every { streamingManager.isSharedMicCapturing() } returns false
        every { streamingManager.isLiveStreaming() } returns false
        every { streamingManager.snapshotLiveOutputs() } returns liveOutputs()
        every { streamingManager.microphoneTypeNeeded() } returns false
        every { streamingManager.ensureServerRunning() } returns true
        every { streamingManager.startStreaming() } answers { startStreamingResult }
        every { powerManager.optimizationResult } returns optimizationResult
        every { keyguardMonitor.isKeyguardLocked } returns keyguardLocked
        session = StreamingSession(
            context = appContext,
            cameraService = cameraService,
            streamingManagerProvider = { streamingManager },
            powerManager = powerManager,
            thermalMonitor = thermalMonitor,
            keyguardMonitor = keyguardMonitor,
            lockBypassController = lockBypassController,
            watchdogProvider = { streamWatchdog },
        )
    }

    // ── helpers ──

    /** A pre-disturbance live-output snapshot; the manager owns the inventory. */
    private fun liveOutputs(
        web: Boolean = false,
        rtsp: Boolean = false,
        rtmp: Boolean = false,
        srt: Boolean = false,
        whip: Boolean = false,
    ) = StreamingManager.LiveOutputs(web, rtsp, rtmp, srt, whip)

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

    /** Robolectric keeps the stop queue across tests; drain it before asserting. */
    private fun drainStoppedServices() {
        while (shadowOf(appContext).nextStoppedService != null) {
            // drain
        }
    }

    /** Retries a mockk [verify] until the async loop's first tick has landed. */
    private fun verifyUntil(deadlineMs: Long = 10_000, block: () -> Unit) {
        val deadline = System.currentTimeMillis() + deadlineMs
        var lastFailure: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (e: AssertionError) {
                lastFailure = e
                Thread.sleep(10)
            }
        }
        throw lastFailure ?: AssertionError("verification never landed")
    }

    // ── begin ──

    @Test
    fun `begin attaches the full environment and a second late begin only re-asserts the foreground service`() {
        // After begin() starts the watchdog the poll's isMonitoring read
        // answers "monitoring" and never re-arms — model that here so the
        // exact-once pin below is the production-true count, not an artifact
        // of the relaxed mock answering "not monitoring" forever.
        every { streamWatchdog.isMonitoring } returns true
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

        // An audio-carrying output starts late; the manager's mic verdict flips.
        every { streamingManager.microphoneTypeNeeded() } returns true
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
        // The server is still up, so the failed stream falls back to server
        // mode instead of dropping the process to background network.
        val last = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_SERVER, last.action)
    }

    @Test
    fun `begin's poll loop renews the wake lock on its first tick`() {
        runWithMainPumped { session.begin() }
        // The optimization poll's launch path runs one tick immediately —
        // the cadence itself stays real-time (see the header note) — and the
        // tick must re-arm the streaming lock before its 60-minute budget
        // can lapse (renew no-ops or release+re-acquires inside PowerManager).
        verifyUntil { verify(exactly = 1) { powerManager.renewWakeLock() } }
    }

    // ── blocked-by-lock (the locked-mount start) ──

    @Test
    fun `begin under lock with a denied bind flags blocked-by-lock and keeps the session`() {
        // The while-in-use camera policy's shape: the Main hop completes but
        // the rebind is denied — exactly the locked-mount dashboard start.
        every { cameraService.rebindUseCases() } returns false
        keyguardLocked.value = true

        runWithMainPumped { session.begin() }

        // The locked-mount contract: the session stands (streaming on,
        // server-only foreground, heal on visibility), but the status wire
        // carries the blocked truth instead of a silent black screen.
        assertTrue(session.isActive)
        verify(exactly = 1) { streamingManager.setCameraBlockedByLock(true) }
    }

    @Test
    fun `a denied bind under lock also asks the lock bypass for the wake`() {
        // The promotion can land while the open still gets denied — the wake
        // is what makes the bind legitimate, so the denied bind re-arms the
        // same request the denied promotion would; the controller's policy
        // ladder decides whether a post is warranted.
        every { cameraService.rebindUseCases() } returns false
        keyguardLocked.value = true

        runWithMainPumped { session.begin() }

        verify(exactly = 1) { lockBypassController.requestWake() }
    }

    @Test
    fun `a denied bind on an unlocked device does not claim lock blocking`() {
        every { cameraService.rebindUseCases() } returns false
        keyguardLocked.value = false

        runWithMainPumped { session.begin() }

        assertTrue(session.isActive)
        verify(exactly = 0) { streamingManager.setCameraBlockedByLock(true) }
        verify(exactly = 0) { lockBypassController.requestWake() }
    }

    @Test
    fun `begin with a successful bind clears any stale blocked-by-lock flag`() {
        keyguardLocked.value = false

        runWithMainPumped { session.begin() }

        verify(exactly = 1) { streamingManager.setCameraBlockedByLock(false) }
    }

    @Test
    fun `unlock heal rebind is bind-acceptance only — the flag waits for the observed open`() {
        // Start blocked: the denied bind under lock flags the session, the
        // exact state the unlock heal exists to recover from.
        every { cameraService.rebindUseCases() } returns false
        keyguardLocked.value = true
        runWithMainPumped { session.begin() }
        verify(atLeast = 1) { streamingManager.setCameraBlockedByLock(true) }

        // The user unlocks; the heal rebinds. Acceptance alone is not an
        // open: a landed bind's claimed Ready must not clear a verdict the
        // camera-state watch observed — only the watch's observed OPEN may.
        every { cameraService.rebindUseCases() } returns true
        // Drop the blocked-phase call recordings, keep the stubs.
        clearMocks(cameraService, streamingManager, answers = false)
        runWithMainPumped { session.rebindOnUnlock() }

        verify(exactly = 1) { cameraService.rebindUseCases() }
        verify(exactly = 0) { streamingManager.setCameraBlockedByLock(false) }
        coVerify(exactly = 0) { cameraService.releaseKeepAlive() }
    }

    @Test
    fun `the camera-state watch clears the blocked flag only on an observed open`() {
        val cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
        val openObserved = MutableStateFlow(false)
        every { cameraService.cameraState } returns cameraState
        every { cameraService.cameraOpenObserved } returns openObserved
        every { cameraService.rebindUseCases() } returns false
        keyguardLocked.value = true

        runWithMainPumped { session.begin() }
        verify(atLeast = 1) { streamingManager.setCameraBlockedByLock(true) }
        // Drop the blocked-phase recordings, keep the stubs and the flows.
        clearMocks(streamingManager, answers = false)

        // A bind's claimed Ready with no observed open must not clear…
        cameraState.value = CameraState.Ready
        Thread.sleep(200)
        verify(exactly = 0) { streamingManager.setCameraBlockedByLock(false) }

        // …the open landing is what completes the verdict.
        openObserved.value = true

        verifyUntil { verify(exactly = 1) { streamingManager.setCameraBlockedByLock(false) } }
    }

    @Test
    fun `the camera-state watch flags blocked-by-lock on a policy-denied error under lock`() {
        // The bind accepted but the open was denied later: CameraService's
        // error observer publishes CameraState.Error carrying the typed
        // CameraErrorPolicy.POLICY_DENIED verdict for ERROR_CAMERA_DISABLED —
        // the typed channel, never the human-readable message, is the
        // blocked-by-lock signal (the wake ladder and throttle make the
        // re-request safe).
        val cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
        val openObserved = MutableStateFlow(false)
        every { cameraService.cameraState } returns cameraState
        every { cameraService.cameraOpenObserved } returns openObserved
        keyguardLocked.value = true

        runWithMainPumped { session.begin() }
        clearMocks(streamingManager, lockBypassController, answers = false)

        cameraState.value = CameraState.Error(
            "Camera disabled by policy (while-in-use denial)",
            CameraErrorPolicy.Verdict.POLICY_DENIED,
        )

        verifyUntil {
            verify(exactly = 1) { streamingManager.setCameraBlockedByLock(true) }
            verify(exactly = 1) { lockBypassController.requestWake() }
        }
    }

    @Test
    fun `a generic camera error is not a lock-blocking verdict`() {
        // Only POLICY_DENIED means the device policy denied the open — any
        // other verdict (here CAMERAX_HANDLES, CameraX's own retry class)
        // must not claim lock blocking or spend a wake request.
        val cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
        val openObserved = MutableStateFlow(false)
        every { cameraService.cameraState } returns cameraState
        every { cameraService.cameraOpenObserved } returns openObserved
        keyguardLocked.value = true

        runWithMainPumped { session.begin() }

        cameraState.value = CameraState.Error("Camera error, code 4", CameraErrorPolicy.Verdict.CAMERAX_HANDLES)
        Thread.sleep(200)
        verify(exactly = 0) { streamingManager.setCameraBlockedByLock(true) }
        verify(exactly = 0) { lockBypassController.requestWake() }
    }

    @Test
    fun `unlock heal is a no-op without a live session`() {
        runWithMainPumped { session.rebindOnUnlock() }

        verify(exactly = 0) { cameraService.rebindUseCases() }
        verify(exactly = 0) { streamingManager.setCameraBlockedByLock(any()) }
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
        // The server outlives the stream: hand the one foreground slot back
        // to server mode instead of pausing it.
        assertEquals(StreamingService.ACTION_SERVER, startedServiceIntents().last().action)

        // A second end with no active session is a no-op while the server
        // still runs.
        runWithMainPumped { session.end() }
        verify(exactly = 1) { powerManager.releaseWakeLock() }
        assertEquals(StreamingService.ACTION_SERVER, startedServiceIntents().last().action)
    }

    @Test
    fun `end stops the runtime foreground service when the server is already down`() {
        isServerRunning.value = false
        runWithMainPumped { session.begin() }
        drainStoppedServices()

        runWithMainPumped { session.end() }

        val stopped = shadowOf(appContext).nextStoppedService
        assertEquals(StreamingService::class.java.name, stopped?.component?.className)
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
    fun `medium recovery restarts the server rebinds and restores what went dark`() {
        // The session hands the manager's pre-disturbance snapshot straight
        // back after the restart steps — no per-output reads or starts here.
        val live = liveOutputs(web = true, rtsp = true)
        every { streamingManager.snapshotLiveOutputs() } returns live

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertTrue(recovered)
        verifyOrder {
            streamingManager.snapshotLiveOutputs()
            streamingManager.pauseStreaming()
            streamingManager.ensureServerRunning()
            cameraService.rebindUseCases()
            streamingManager.restoreLiveOutputs(live)
        }
        verify(exactly = 1) { streamingManager.restoreLiveOutputs(live) }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
        verify(exactly = 0) { streamingManager.startRtspStreaming() }
        coVerify(exactly = 0) { cameraService.initialize() }
    }

    @Test
    fun `medium recovery restores a push-only stream the pause killed`() {
        // The bug pin: pause stops all five outputs, so a live RTMP push with
        // no web/RTSP must ride the snapshot back instead of dying into a
        // false escalation.
        val live = liveOutputs(rtmp = true)
        every { streamingManager.snapshotLiveOutputs() } returns live

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertTrue(recovered)
        verify(exactly = 1) { streamingManager.pauseStreaming() }
        verify(exactly = 1) { streamingManager.restoreLiveOutputs(live) }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
        verify(exactly = 0) { streamingManager.startRtspStreaming() }
    }

    @Test
    fun `medium recovery reports failure when the server cannot restart`() {
        every { streamingManager.ensureServerRunning() } returns false

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) }

        assertFalse(recovered)
        verify(exactly = 0) { cameraService.rebindUseCases() }
        verify(exactly = 0) { streamingManager.restoreLiveOutputs(any()) }
    }

    @Test
    fun `a soft-restart ladder run waits for an in-flight medium recovery instead of interleaving`() {
        // The 0.2.3-beta.1 on-device repro (the restart × recovery race
        // 7775db8's grace-window serialization cannot cover): the ladder ran
        // beside a MEDIUM recovery parked mid-leg, the recovery restarted the
        // server behind the ladder's back, and the ladder's startServer then
        // found the port unusable — StepFailed(startServer), server dark on
        // every port, every later start killed by the stale stop leg. The
        // choreography lock is the fix; this pins its ordering: while the
        // recovery holds the lock inside its server-restart leg, the ladder
        // must not take a single step, and every recovery leg must land
        // before the ladder's re-arm runs.
        val parkedInRecoveryRestart = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val order = ConcurrentLinkedQueue<String>()
        var verdict: SoftRestartLadder.Result? = null
        val live = liveOutputs(web = true)
        every { streamingManager.snapshotLiveOutputs() } answers { order.add("snapshot"); live }
        every { streamingManager.pauseStreaming() } answers { order.add("pause") }
        every { streamingManager.ensureServerRunning() } answers {
            order.add("restart")
            parkedInRecoveryRestart.countDown()
            releaseRecovery.await()
            true
        }
        every { cameraService.rebindUseCases() } answers { order.add("rebindRecovery"); true }
        every { streamingManager.restoreLiveOutputs(live) } answers { order.add("restore") }

        runWithMainPumped {
            coroutineScope {
                val recovery = launch(Dispatchers.IO) {
                    assertTrue(session.recover(WatchdogPolicy.RecoveryTier.MEDIUM))
                }
                assertTrue(parkedInRecoveryRestart.await(10, TimeUnit.SECONDS))

                val ladder = launch(Dispatchers.IO) {
                    val softRestart = SoftRestartLadder(
                        stopRecording = { order.add("stopRecording") },
                        stopServer = { order.add("stopServer") },
                        // The production wiring: the lock's non-locking half,
                        // because the whole run already holds the lock.
                        rebindCamera = {
                            order.add("rebindCamera")
                            session.recoverLocked(WatchdogPolicy.RecoveryTier.SOFT)
                        },
                        startServer = { order.add("startServer"); StreamStartOutcome.Started },
                    )
                    verdict = session.runChoreographed { softRestart.run() }
                }

                // The ladder must PARK on the choreography lock: no step may
                // run while the recovery still holds the lock mid-restart.
                repeat(30) {
                    Thread.sleep(10)
                    assertTrue(
                        "ladder stepped while the recovery still held the choreography lock",
                        order.none { it.startsWith("stop") || it.startsWith("rebindCamera") || it.startsWith("start") },
                    )
                }

                releaseRecovery.countDown()
                recovery.join()
                ladder.join()
            }
        }

        assertEquals(SoftRestartLadder.Result.Restarted, verdict)
        assertEquals(
            listOf(
                "snapshot", "pause", "restart", "rebindRecovery", "restore",
                "stopRecording", "stopServer", "rebindCamera", "rebindRecovery", "startServer",
            ),
            order.toList(),
        )
    }

    @Test
    fun `hard recovery re-initializes the camera and refreshes the disturbed session state`() {
        runWithMainPumped { session.begin() }
        verify(exactly = 1) { thermalMonitor.startMonitoring() }
        val live = liveOutputs(web = true, rtsp = true)
        every { streamingManager.snapshotLiveOutputs() } returns live

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
        // startStreaming restarts the enabled outputs; the restore retries
        // only what did not come back — the session starts nothing itself.
        verify(exactly = 1) { streamingManager.restoreLiveOutputs(live) }
        verify(exactly = 0) { streamingManager.startWebStreaming() }
        verify(exactly = 0) { streamingManager.startRtspStreaming() }
    }

    @Test
    fun `hard recovery snapshots before the stop and restores after the streaming restart`() {
        val live = liveOutputs(rtmp = true, whip = true)
        every { streamingManager.snapshotLiveOutputs() } returns live

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) }

        assertTrue(recovered)
        coVerifyOrder {
            streamingManager.snapshotLiveOutputs()
            streamingManager.stopStreaming()
            cameraService.initialize()
            streamingManager.startStreaming()
            streamingManager.restoreLiveOutputs(live)
        }
    }

    @Test
    fun `an all-five-live snapshot round-trips through recovery`() {
        val live = liveOutputs(web = true, rtsp = true, rtmp = true, srt = true, whip = true)
        every { streamingManager.snapshotLiveOutputs() } returns live

        assertTrue(runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.MEDIUM) })
        assertTrue(runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) })

        verify(exactly = 2) { streamingManager.restoreLiveOutputs(live) }
    }

    @Test
    fun `hard recovery reports failure when streaming cannot restart`() {
        startStreamingResult = false

        val recovered = runWithMainPumped { session.recover(WatchdogPolicy.RecoveryTier.HARD) }

        assertFalse(recovered)
        // The camera re-init ran before the restart failed, but the killed
        // outputs were left down for the watchdog's verification to retry.
        coVerify(exactly = 1) { cameraService.initialize() }
        verify(exactly = 0) { streamingManager.restoreLiveOutputs(any()) }
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
    fun `foreground start carries the stream url and the mic verdict fact`() {
        every { streamingManager.isAudioStreaming.value } returns true
        every { streamingManager.microphoneTypeNeeded() } returns true
        runWithMainPumped { session.begin() }

        val intent = startedServiceIntents().first()
        assertEquals(StreamingService.ACTION_START, intent.action)
        assertEquals("http://0.0.0.0:8080", intent.getStringExtra(StreamingService.EXTRA_URL))
        assertTrue(intent.getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, false))
    }

    @Test
    fun `foreground start claims the microphone exactly when the mic verdict fact reads true`() {
        // False half: no output wants the mic — no claim, and the session
        // reads the one fact instead of reconstructing the per-output OR.
        runWithMainPumped { session.begin() }
        assertFalse(
            startedServiceIntents().last()
                .getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, true),
        )
        verify(exactly = 0) { streamingManager.isRtspAudioActive() }
        verify(exactly = 0) { streamingManager.isRtmpAudioActive() }
        verify(exactly = 0) { streamingManager.isWhipAudioActive() }

        // True half: the fact flips, the re-asserted START carries the claim.
        every { streamingManager.microphoneTypeNeeded() } returns true
        runWithMainPumped { session.begin() }
        assertTrue(
            startedServiceIntents().last()
                .getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, false),
        )
    }

    @Test
    fun `foreground server handoff carries no audio claim`() {
        runWithMainPumped { session.begin() }
        every { streamingManager.isLiveStreaming() } returns false
        runWithMainPumped { session.end() }

        val handoff = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_SERVER, handoff.action)
        assertFalse(handoff.hasExtra(StreamingService.EXTRA_AUDIO_ACTIVE))
    }

    // �� server-only mode ��

    @Test
    fun `server foreground starts without a stream session and carries the url`() {
        session.ensureServerForeground()

        val intent = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_SERVER, intent.action)
        assertEquals("http://0.0.0.0:8080", intent.getStringExtra(StreamingService.EXTRA_URL))
        assertFalse(intent.hasExtra(StreamingService.EXTRA_AUDIO_ACTIVE))
    }

    @Test
    fun `server foreground is a no-op while a stream session is active`() {
        runWithMainPumped { session.begin() }
        val before = startedServiceIntents().size

        session.ensureServerForeground()

        assertEquals(before, startedServiceIntents().size)
    }

    @Test
    fun `server foreground carries the headless sound-detection mic verdict`() {
        every { streamingManager.isSharedMicCapturing() } returns true

        session.ensureServerForeground()

        val intent = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_SERVER, intent.action)
        assertTrue(intent.getBooleanExtra(StreamingService.EXTRA_AUDIO_ACTIVE, false))
    }

    @Test
    fun `server foreground ignores the stream mic verdict and keys on the shared capture only`() {
        // Server mode is camera-free: an output-side mic verdict must not
        // leak into the headless handoff — only the sound-detection hold may.
        every { streamingManager.microphoneTypeNeeded() } returns true

        session.ensureServerForeground()

        val intent = startedServiceIntents().last()
        assertEquals(StreamingService.ACTION_SERVER, intent.action)
        assertFalse(intent.hasExtra(StreamingService.EXTRA_AUDIO_ACTIVE))
    }

    @Test
    fun `server foreground stops when the server stops with no stream`() {
        drainStoppedServices()

        session.stopServerForegroundIfIdle()

        val stopped = shadowOf(appContext).nextStoppedService
        assertEquals(StreamingService::class.java.name, stopped?.component?.className)
    }

    @Test
    fun `server foreground is not stopped by an idle server transition while a stream is active`() {
        runWithMainPumped { session.begin() }
        drainStoppedServices()

        session.stopServerForegroundIfIdle()

        assertNull(shadowOf(appContext).nextStoppedService)
    }
}
