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
 * The watchdog is disabled by default and must be explicitly enabled via settings.
 */
class StreamWatchdog(
    private val cameraService: CameraService,
    streamingManagerProvider: () -> StreamingManager,
    private val streamingSession: StreamingSession,
) {

    // Resolved on first use, never during construction: the manager builds
    // the Web API stack in its constructor, which constructs this watchdog,
    // so a direct manager reference here would re-enter the manager's
    // in-progress lazy initializer (recursion → OOM crash at launch).
    private val streamingManager: StreamingManager by lazy(streamingManagerProvider)

    // ── Configuration ──

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
    private var monitorJob: Job? = null

    private val consecutiveFailures = AtomicInteger(0)
    private val totalRecoveries = AtomicInteger(0)

    @Volatile
    private var lastProcessedFrameCount = 0
    @Volatile
    private var lastRtspAcceptedFrames = 0L
    @Volatile
    private var lastFrameCheckTimeMs = 0L
    @Volatile
    private var lastRecoveryTimestamp = 0L
    @Volatile
    private var lastFailureReason: String? = null

    // Track whether we were previously in an active streaming state
    @Volatile
    private var wasStreamingActive = false

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
     * Start the watchdog monitoring loop. Called when streaming begins.
     * No-op if watchdog is disabled or already monitoring.
     */
    fun startMonitoring() {
        if (!enabled) {
            Log.d(TAG, "startMonitoring: watchdog is disabled, skipping")
            return
        }

        if (monitorJob?.isActive == true) {
            Log.d(TAG, "startMonitoring: already monitoring")
            return
        }

        consecutiveFailures.set(0)
        lastProcessedFrameCount = streamingManager.processedFrames.value
        lastRtspAcceptedFrames = streamingManager.getRtspHealth().acceptedFrames
        lastFrameCheckTimeMs = System.currentTimeMillis()
        wasStreamingActive = true
        lastFailureReason = null

        monitorJob = scope.launch {
            Log.d(TAG, "Watchdog monitoring started (interval=${checkIntervalSeconds}s, maxRetries=$maxRetries)")
            updateState(WatchdogStatus.MONITORING)

            while (true) {
                delay(checkIntervalSeconds * 1000L)

                if (!enabled) {
                    Log.d(TAG, "Watchdog disabled during monitoring, stopping")
                    break
                }

                val failureReason = checkStreamHealth()

                if (failureReason != null) {
                    Log.w(TAG, "Health check failed: $failureReason")
                    lastFailureReason = failureReason
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
                            lastRecoveryTimestamp = System.currentTimeMillis()
                            consecutiveFailures.set(0)
                            lastProcessedFrameCount = streamingManager.processedFrames.value
                            lastRtspAcceptedFrames = try {
                                streamingManager.getRtspHealth().acceptedFrames
                            } catch (_: Exception) {
                                lastRtspAcceptedFrames
                            }
                            lastFrameCheckTimeMs = System.currentTimeMillis()
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
                            lastFailureReason = null
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
     * Stop the watchdog monitoring loop. Called when streaming ends.
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        wasStreamingActive = false
        consecutiveFailures.set(0)
        lastFailureReason = null
        updateState(WatchdogStatus.IDLE)
        Log.d(TAG, "Watchdog monitoring stopped")
    }

    // ── Health Checks ──

    /**
     * Evaluates stream health. Returns a failure reason string if unhealthy, null if OK.
     * The verdict and messages come from [WatchdogPolicy]; this method owns the
     * live input collection and the frame-tracking side effects.
     */
    private fun checkStreamHealth(): String? {
        val cameraState = cameraService.cameraState.value
        val rtsp = try {
            streamingManager.getRtspHealth()
        } catch (_: Exception) {
            null
        }
        val snapshot = WatchdogPolicy.HealthSnapshot(
            cameraError = cameraState is CameraState.Error,
            cameraErrorMessage = (cameraState as? CameraState.Error)?.message,
            wasStreamingActive = wasStreamingActive,
            serverRunning = streamingManager.isServerRunning.value,
            liveStreaming = streamingManager.isLiveStreaming(),
            clientCount = streamingManager.clientCount.value,
            processedFrames = streamingManager.processedFrames.value,
            lastProcessedFrameCount = lastProcessedFrameCount,
            lastFrameCheckTimeMs = lastFrameCheckTimeMs,
            nowMs = System.currentTimeMillis(),
            rtspActive = streamingManager.isRtspRunning.value,
            rtspPlayingClients = rtsp?.playingClients ?: 0,
            rtspHealthy = rtsp?.healthy ?: true,
            rtspAcceptedFrames = rtsp?.acceptedFrames ?: 0L,
            lastRtspAcceptedFrames = lastRtspAcceptedFrames,
        )

        val reason = WatchdogPolicy.evaluate(snapshot)
        if (reason != null) {
            return WatchdogPolicy.failureMessage(reason, snapshot)
        }

        WatchdogPolicy.updatedTracking(snapshot)?.let { tracking ->
            lastProcessedFrameCount = tracking.processedFrameCount
            lastRtspAcceptedFrames = snapshot.rtspAcceptedFrames
            lastFrameCheckTimeMs = tracking.frameCheckTimeMs
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
        val framesBeforeWait = if (spec.measureFrames) streamingManager.processedFrames.value else 0
        if (spec.measureFrames) {
            delay(WatchdogPolicy.RECOVERY_VERIFICATION_WINDOW_MS)
        }
        val framesAfterWait = if (spec.measureFrames) streamingManager.processedFrames.value else 0

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
        val currentStatus = status ?: when {
            !enabled -> WatchdogStatus.IDLE
            monitorJob?.isActive == true -> WatchdogStatus.MONITORING
            else -> WatchdogStatus.IDLE
        }
        _state.value = WatchdogState(
            enabled = enabled,
            status = currentStatus,
            consecutiveFailures = consecutiveFailures.get(),
            totalRecoveries = totalRecoveries.get(),
            lastRecoveryTimestamp = lastRecoveryTimestamp,
            lastFailureReason = lastFailureReason,
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
