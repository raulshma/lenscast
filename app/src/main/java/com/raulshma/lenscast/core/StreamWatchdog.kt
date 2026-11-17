package com.raulshma.lenscast.core

import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import com.raulshma.lenscast.core.WatchdogPolicy.RecoveryTier
import com.raulshma.lenscast.core.WatchdogPolicy.TickAction
import com.raulshma.lenscast.core.WatchdogPolicy.WatchdogStatus

/**
 * StreamWatchdog — A coroutine-based health monitor that detects streaming failures
 * (camera crashes, server disconnections, frame stalls) and automatically restarts
 * the stream with escalating recovery strategies and exponential backoff.
 *
 * Recovery tiers (mechanics owned by [StreamingSession.recover]):
 *  1. SOFT  — Rebind CameraX use cases (fixes most transient camera glitches)
 *  2. MEDIUM — Restart streaming server + rebind camera
 *  3. HARD  — Full re-initialize: CameraService.initialize() → restart everything
 *
 * This class owns the monitoring loop: the timing, the live reads, the
 * recovery calls, and state publishing. Every decision — health checks, the
 * tick's escalation verdict, the tier ladder, backoff, verification
 * windows — delegates to [WatchdogPolicy].
 *
 * Two arming seams, one loop (blueprint P0 item 2, resolved tension 3):
 * - **Session arming** ([startMonitoring]/[stopMonitoring], called by
 *   [StreamingSession]) runs the full analysis — every [WatchdogPolicy]
 *   branch — and is gated by the user-facing [enabled] setting (default
 *   off; wired from SettingsApplier). With it off, a camera death
 *   mid-stream leaves a zombie: the dashboard reports active while no frame
 *   reaches any client, and nothing ever recovers it.
 * - **Server-liveness arming** ([startServerLivenessWatch]/
 *   [stopServerLivenessWatch], driven by [ServerWatchdogArming] off the
 *   server lifecycle) watches the post-relaunch state every recovery tier
 *   lands in — the server up, no session live. It is deliberately NOT
 *   gated by [enabled]: server liveness must not depend on a settings
 *   screen nobody opened on an unattended mount (the old KDoc's
 *   "enabled by default" claim was false — the field defaults false — and
 *   this split is the honest resolution). In this mode
 *   [WatchdogPolicy.HealthSnapshot.serverOnlyMode] skips the session-scoped
 *   branches while the camera-error and loopback-probe checks stay active;
 *   recovery rides the same tier ladder (a session-less recover restarts
 *   the server under MEDIUM even though its verification, which wants a
 *   live stream, will conservatively fail — the next clean health check is
 *   what confirms the recovery).
 */
class StreamWatchdog(
    private val cameraService: CameraService,
    streamingManagerProvider: () -> StreamingManager,
    private val streamingSession: StreamingSession,
    /**
     * The loopback `/api/system` liveness probe (blueprint P0 item 5) — a
     * lambda seam so JVM tests inject a fake and the production
     * [LoopbackServerProbe] keeps all HTTP here. Returns whether the server
     * answered; null = probe not applicable (the caller skips it).
     */
    private val loopbackProbe: () -> Boolean? = { null },
) {

    // Resolved on first use, never during construction: the manager builds
    // the Web API stack in its constructor, which constructs this watchdog,
    // so a direct manager reference here would re-enter the manager's
    // in-progress lazy initializer (recursion → OOM crash at launch).
    private val streamingManager: StreamingManager by lazy(streamingManagerProvider)

    // ── Configuration ──

    /**
     * The user-facing watchdog setting (default false, wired from
     * SettingsApplier): it gates the SESSION analysis only — turning it off
     * stops a session-armed loop (handing the loop to the server-liveness
     * baseline when the server survives) but never disarms the
     * server-liveness baseline itself.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var maxRetries: Int = StreamDefaults.WATCHDOG_MAX_RETRIES
        private set

    @Volatile
    var checkIntervalSeconds: Int = StreamDefaults.WATCHDOG_CHECK_INTERVAL_SECONDS
        private set

    // ── State ──

    private val _state = MutableStateFlow(WatchdogState())
    val state: StateFlow<WatchdogState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // @Volatile: the session poll reads [isMonitoring] off the lifecycle
    // lock, so a loop launch/cancel inside it must be visible to that read.
    @Volatile
    private var monitorJob: Job? = null

    /**
     * Serializes the monitor loop's lifecycle: begin(), the session poll's
     * re-arm and the resume seam can race a check-then-launch on
     * [monitorJob], and two concurrent loops double every health check and
     * double every recovery — observed on-device as two interleaved
     * "Attempting MEDIUM recovery" ladders healing one stream.
     */
    private val lifecycleLock = Any()

    private val consecutiveFailures = AtomicInteger(0)
    private val totalRecoveries = AtomicInteger(0)

    /**
     * The whole tracking cluster as one immutable snapshot
     * ([WatchdogPolicy.TrackingSnapshot]) — the frame-stall baselines, the
     * last recovery timestamp, and the exposed failure reason — swapped
     * wholesale at every transition. The consecutive-failure and
     * total-recovery counters above stay AtomicIntegers deliberately: they
     * are the loop's tick bookkeeping, not tracking state (the policy type's
     * doc records the split). The known [updateState] quirk — a null-status
     * refresh re-deriving IDLE over FAILED — is untouched here.
     */
    @Volatile
    private var tracking = WatchdogPolicy.TrackingSnapshot()

    // Track whether we were previously in an active streaming state
    @Volatile
    private var wasStreamingActive = false

    /**
     * Who armed the monitor loop. The session seam and the server lifecycle
     * both funnel into one loop; the owner decides what a stop request may
     * tear down (a session end must not kill the server-liveness watch, and
     * vice versa a server stop must not kill a live session's watch) and
     * which health-check mode [checkStreamHealth] runs. @Volatile: the loop
     * reads it off the lifecycle lock every tick.
     */
    private enum class LoopOwner { NONE, SESSION, SERVER }

    @Volatile
    private var loopOwner = LoopOwner.NONE

    // ── Public API ──

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            stopMonitoring()
        }
        updateState()
    }

    fun setMaxRetries(value: Int) {
        maxRetries = value.coerceIn(StreamDefaults.WATCHDOG_MAX_RETRIES_MIN, StreamDefaults.WATCHDOG_MAX_RETRIES_MAX)
    }

    fun setCheckIntervalSeconds(value: Int) {
        checkIntervalSeconds = value.coerceIn(
            StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS,
            StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS,
        )
    }

    /**
     * Start the watchdog monitoring loop — the SESSION arming seam, called
     * when streaming begins. No-op if the watchdog is disabled or the
     * session's loop is already running; a running server-liveness loop is
     * replaced (the session's full analysis strictly covers it).
     */
    fun startMonitoring() = synchronized(lifecycleLock) {
        if (!enabled) {
            Log.d(TAG, "startMonitoring: watchdog is disabled, skipping")
            return
        }

        if (loopOwner == LoopOwner.SESSION && monitorJob?.isActive == true) {
            Log.d(TAG, "startMonitoring: already monitoring")
            return
        }

        cancelMonitorLoopLocked()
        armMonitorLoopLocked(LoopOwner.SESSION)
    }

    /**
     * Stop the watchdog monitoring loop — the SESSION seam's teardown. The
     * server-liveness loop is NOT this call's to stop ([stopServerLivenessWatch]
     * owns that): when the session ends but the server survives — the exact
     * handoff a stream stop performs — the loop is handed to the liveness
     * baseline instead of going dark, so the post-stream server-only state
     * stays watched by construction.
     */
    // Block bodies: the bare `return` guards inside need a declared Unit
    // return type — an expression body would infer Int off the trailing
    // Log.d and reject the early return.
    fun stopMonitoring() {
        synchronized(lifecycleLock) {
            if (loopOwner != LoopOwner.SESSION) {
                Log.d(TAG, "stopMonitoring: no session-armed loop to stop")
                return
            }

            cancelMonitorLoopLocked()
            wasStreamingActive = false
            consecutiveFailures.set(0)
            tracking = tracking.copy(failureReason = null)
            if (streamingManager.isServerRunning.value) {
                armMonitorLoopLocked(LoopOwner.SERVER)
            } else {
                updateState(WatchdogStatus.IDLE)
            }
            Log.d(TAG, "Watchdog monitoring stopped")
        }
    }

    /**
     * Arm the server-liveness baseline — the SERVER seam (blueprint P0 item
     * 2), driven by [ServerWatchdogArming] observing the server lifecycle.
     * Deliberately NOT gated by [enabled]: this baseline (camera error +
     * loopback probe; every session-scoped branch skipped via
     * [WatchdogPolicy.HealthSnapshot.serverOnlyMode]) is the unattended
     * floor that must not depend on a settings screen nobody opened. No-op
     * while any loop is already running (a session's watch covers more).
     */
    fun startServerLivenessWatch() = synchronized(lifecycleLock) {
        if (monitorJob?.isActive == true && loopOwner != LoopOwner.NONE) {
            return
        }
        cancelMonitorLoopLocked()
        armMonitorLoopLocked(LoopOwner.SERVER)
    }

    /**
     * Disarm the server-liveness baseline (the server stopped). Never stops
     * a session-armed loop — the session seam owns that teardown.
     */
    fun stopServerLivenessWatch() {
        synchronized(lifecycleLock) {
            if (loopOwner != LoopOwner.SERVER) {
                return
            }
            cancelMonitorLoopLocked()
            wasStreamingActive = false
            updateState(WatchdogStatus.IDLE)
            Log.d(TAG, "Server-liveness watch stopped")
        }
    }

    private fun cancelMonitorLoopLocked() {
        monitorJob?.cancel()
        monitorJob = null
        loopOwner = LoopOwner.NONE
    }

    /** The one loop-launch path for both seams: reset the tick bookkeeping, record the owner, launch. */
    private fun armMonitorLoopLocked(owner: LoopOwner) {
        consecutiveFailures.set(0)
        tracking = WatchdogPolicy.TrackingSnapshot(
            processedFrameCount = streamingManager.processedFrames.value,
            cameraFrameCount = streamingManager.cameraFrameCount(),
            rtspAcceptedFrames = streamingManager.getRtspHealth().acceptedFrames,
            frameCheckTimeMs = System.currentTimeMillis(),
            recoveryTimestamp = tracking.recoveryTimestamp,
            failureReason = null,
        )
        wasStreamingActive = owner == LoopOwner.SESSION
        loopOwner = owner

        monitorJob = scope.launch {
            Log.d(TAG, "Watchdog monitoring started (mode=$owner, interval=${checkIntervalSeconds}s, maxRetries=$maxRetries)")
            updateState(WatchdogStatus.MONITORING)

            while (true) {
                delay(checkIntervalSeconds * 1000L)

                // The user-facing setting gates the session analysis only;
                // the server-liveness baseline runs until its seam stops it.
                if (!enabled && loopOwner == LoopOwner.SESSION) {
                    Log.d(TAG, "Watchdog disabled during monitoring, stopping")
                    break
                }

                val failureReason = checkStreamHealth()

                if (failureReason != null) {
                    Log.w(TAG, "Health check failed: $failureReason")
                    tracking = tracking.copy(failureReason = failureReason)
                    consecutiveFailures.incrementAndGet()
                }

                val tick = WatchdogPolicy.nextTick(
                    failureReason = failureReason,
                    consecutiveFailures = consecutiveFailures.get(),
                    maxRetries = maxRetries,
                )

                when (tick.action) {
                    TickAction.RECOVER -> tick.tier?.let { recoveryTier ->
                        tick.status?.let(::updateState)
                        Log.d(TAG, "Attempting $recoveryTier recovery (attempt ${consecutiveFailures.get()}/$maxRetries, backoff ${tick.backoffMs}ms)")

                        delay(tick.backoffMs)

                        val recovered = attemptRecovery(recoveryTier)
                        val outcome = WatchdogPolicy.nextTick(
                            failureReason = failureReason,
                            consecutiveFailures = consecutiveFailures.get(),
                            maxRetries = maxRetries,
                            recoverySucceeded = recovered,
                        )

                        if (outcome.action == TickAction.RESET) {
                            Log.d(TAG, "Recovery successful after $recoveryTier attempt")
                            totalRecoveries.incrementAndGet()
                            consecutiveFailures.set(0)
                            val acceptedFrames = try {
                                streamingManager.getRtspHealth().acceptedFrames
                            } catch (_: Exception) {
                                tracking.rtspAcceptedFrames
                            }
                            tracking = tracking.copy(
                                processedFrameCount = streamingManager.processedFrames.value,
                                cameraFrameCount = streamingManager.cameraFrameCount(),
                                rtspAcceptedFrames = acceptedFrames,
                                frameCheckTimeMs = System.currentTimeMillis(),
                                recoveryTimestamp = System.currentTimeMillis(),
                            )
                        } else {
                            Log.w(TAG, "$recoveryTier recovery failed")
                        }
                        outcome.status?.let(::updateState)
                    }

                    TickAction.FAIL -> {
                        Log.e(TAG, "Max retries ($maxRetries) exhausted. Watchdog entering FAILED state.")
                        tick.status?.let(::updateState)
                        break
                    }

                    TickAction.RESET -> {
                        Log.d(TAG, "Stream healthy again, resetting failure count")
                        consecutiveFailures.set(0)
                        if (tick.clearsFailureReason) {
                            tracking = tracking.copy(failureReason = null)
                        }
                        tick.status?.let(::updateState)
                    }

                    TickAction.CONTINUE -> {
                        // Clean, healthy tick — nothing to publish.
                    }
                }
            }
        }

        updateState()
    }

    /**
     * Whether the monitor loop is currently running. A watchdog that
     * exhausted its retries is in FAILED with a dead loop — the session's
     * poll reads this to keep re-arming it for as long as the stream lives
     * (an unattended mount has no one to restart the stream by hand).
     */
    val isMonitoring: Boolean
        get() = monitorJob?.isActive == true

    // ── Health Checks ──

    /**
     * The live input collection for one health evaluation — the snapshot
     * [WatchdogPolicy.evaluate] consumes. Shared by the monitor loop's tick
     * ([checkStreamHealth]) and the out-of-process heartbeat's one-shot
     * ([evaluateOnce]), so the two observers cannot drift: the same loop's
     * mode derivation (SERVER ⇒ [WatchdogPolicy.HealthSnapshot.serverOnlyMode]
     * skips the session-scoped branches), the same loopback probe (run on
     * IO with the seam's own short timeouts so a wedged server cannot hang
     * the caller; skipped with null when the server flag is already down,
     * where it would only restate SERVER_STOPPED's evidence).
     */
    private suspend fun collectSnapshot(): WatchdogPolicy.HealthSnapshot {
        val cameraState = cameraService.cameraState.value
        val rtsp = try {
            streamingManager.getRtspHealth()
        } catch (_: Exception) {
            null
        }
        val serverRunning = streamingManager.isServerRunning.value
        val serverAnswered = if (serverRunning) {
            withContext(Dispatchers.IO) {
                // Any throw inside the seam is itself an unanswered server.
                runCatching { loopbackProbe() }.getOrDefault(false)
            }
        } else {
            null
        }
        return WatchdogPolicy.HealthSnapshot(
            cameraError = cameraState is CameraState.Error,
            cameraErrorMessage = (cameraState as? CameraState.Error)?.message,
            wasStreamingActive = wasStreamingActive,
            serverRunning = serverRunning,
            liveStreaming = streamingManager.isLiveStreaming(),
            clientCount = streamingManager.clientCount.value,
            processedFrames = streamingManager.processedFrames.value,
            lastProcessedFrameCount = tracking.processedFrameCount,
            cameraFrames = streamingManager.cameraFrameCount(),
            lastCameraFrames = tracking.cameraFrameCount,
            lastFrameCheckTimeMs = tracking.frameCheckTimeMs,
            nowMs = System.currentTimeMillis(),
            rtspActive = streamingManager.isRtspRunning.value,
            rtspPlayingClients = rtsp?.playingClients ?: 0,
            rtspHealthy = rtsp?.healthy ?: true,
            rtspAcceptedFrames = rtsp?.acceptedFrames ?: 0L,
            lastRtspAcceptedFrames = tracking.rtspAcceptedFrames,
            serverOnlyMode = loopOwner == LoopOwner.SERVER,
            serverAnswered = serverAnswered,
        )
    }

    /**
     * The alarm heartbeat's one-shot read (blueprint P0 item 3): the raw
     * [WatchdogPolicy] verdict plus the snapshot's `serverOnlyMode`, from
     * the ONE [collectSnapshot] pass so the verdict and the mode cannot
     * disagree — the mode is the decision's server-only shift input
     * ([WatchdogAlarmPolicy.decide]: the SOFT tier is a session's camera
     * rebind, and server-only sessions do not exist, so its ladder starts
     * one tier down). Deliberately WITHOUT the loop's tracking side effects
     * — a heartbeat read that re-origin'd the stall baselines would mask the
     * very stall the loop is counting toward; the heartbeat observes, only
     * the loop advances the baselines.
     */
    data class OneShotVerdict(
        /** The policy verdict; null = healthy (the heartbeat's reset case). */
        val stall: WatchdogPolicy.StallReason?,
        /** Whether the snapshot was taken in the server-only (no-session) mode. */
        val serverOnlyMode: Boolean,
    )

    suspend fun evaluateOnce(): OneShotVerdict {
        val snapshot = collectSnapshot()
        return OneShotVerdict(
            stall = WatchdogPolicy.evaluate(snapshot),
            serverOnlyMode = snapshot.serverOnlyMode,
        )
    }

    /**
     * Evaluates stream health. Returns a failure reason string if unhealthy, null if OK.
     * The verdict and messages come from [WatchdogPolicy]; this method owns the
     * frame-tracking side effects on top of the shared [collectSnapshot] read.
     */
    private suspend fun checkStreamHealth(): String? {
        val snapshot = collectSnapshot()

        val reason = WatchdogPolicy.evaluate(snapshot)
        if (reason != null) {
            return WatchdogPolicy.failureMessage(reason, snapshot)
        }

        WatchdogPolicy.updatedTracking(snapshot)?.let { update ->
            tracking = tracking.copy(
                processedFrameCount = update.processedFrameCount,
                cameraFrameCount = update.cameraFrameCount,
                rtspAcceptedFrames = snapshot.rtspAcceptedFrames,
                frameCheckTimeMs = update.frameCheckTimeMs,
            )
        }
        return null
    }

    // ── Recovery Logic ──

    /**
     * The session performs the tier's mechanics ([StreamingSession.recover]
     * owns the rebind, the attach choreography, and the conditional stream
     * restarts); the watchdog owns the timing around them — backoff before
     * (in the loop) and the verification window after.
     */
    private suspend fun attemptRecovery(tier: RecoveryTier): Boolean {
        return try {
            val recovered = streamingSession.recover(tier)
            if (!recovered) return false
            verifyRecovery(tier)
        } catch (e: Exception) {
            Log.e(TAG, "$tier recovery threw exception", e)
            false
        }
    }

    /**
     * Post-recovery health verification — one parameterized pass over the
     * tier's [WatchdogPolicy.verificationSpecFor] window: wait the spec's
     * delay, measure the frame counters across the observation window only
     * when the spec says so, and let [WatchdogPolicy.verificationSuccess]
     * render the verdict.
     */
    private suspend fun verifyRecovery(tier: RecoveryTier): Boolean {
        val spec = WatchdogPolicy.verificationSpecFor(tier)

        delay(spec.delayMs)
        // The camera-frame counter, not processedFrames: the M-JPEG pipeline
        // only processes for real viewers, so a headless stream's rebind
        // would never verify against processed frames. Camera frames flow
        // (or don't) regardless of who is watching.
        val framesBeforeWait = if (spec.measureFrames) streamingManager.cameraFrameCount() else 0L
        if (spec.measureFrames) {
            delay(WatchdogPolicy.RECOVERY_VERIFICATION_WINDOW_MS)
        }
        val framesAfterWait = if (spec.measureFrames) streamingManager.cameraFrameCount() else 0L

        val success = WatchdogPolicy.verificationSuccess(
            tier = tier,
            framesAdvanced = spec.measureFrames && framesAfterWait > framesBeforeWait,
            clientCount = streamingManager.clientCount.value,
            isLive = streamingManager.isLiveStreaming(),
            cameraReady = cameraService.cameraState.value is CameraState.Ready,
        )

        if (success) {
            when (tier) {
                RecoveryTier.SOFT -> Log.d(TAG, "Soft recovery succeeded (frames: $framesBeforeWait → $framesAfterWait)")
                RecoveryTier.MEDIUM -> Log.d(TAG, "Medium recovery succeeded")
                RecoveryTier.HARD -> Log.d(TAG, "Hard recovery succeeded")
            }
        }
        return success
    }

    // ── Helpers ──

    private fun updateState(status: WatchdogStatus? = null) {
        // A running loop IS monitoring, even with the user setting off — the
        // server-liveness baseline legitimately runs that way (the setting
        // gates the session analysis only).
        val currentStatus = status ?: when {
            monitorJob?.isActive == true -> WatchdogStatus.MONITORING
            else -> WatchdogStatus.IDLE
        }
        _state.value = WatchdogState(
            enabled = enabled,
            status = currentStatus,
            consecutiveFailures = consecutiveFailures.get(),
            totalRecoveries = totalRecoveries.get(),
            lastRecoveryTimestamp = tracking.recoveryTimestamp,
            lastFailureReason = tracking.failureReason,
        )
    }

    // ── Models ──

    data class WatchdogState(
        val enabled: Boolean = false,
        val status: WatchdogStatus = WatchdogStatus.IDLE,
        val consecutiveFailures: Int = 0,
        val totalRecoveries: Int = 0,
        val lastRecoveryTimestamp: Long = 0,
        val lastFailureReason: String? = null,
    )

    companion object {
        private const val TAG = "StreamWatchdog"

        const val MIN_CHECK_INTERVAL_SECONDS = StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS
        const val MAX_CHECK_INTERVAL_SECONDS = StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS
    }
}
