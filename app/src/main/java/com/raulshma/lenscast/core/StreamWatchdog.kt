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
 * This class owns the monitoring loop: health checks ([WatchdogPolicy]), the
 * tier decision, backoff, verification delays, and state publishing.
 *
 * The watchdog is disabled by default and must be explicitly enabled via settings.
 */
class StreamWatchdog(
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) {

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
                    val failures = consecutiveFailures.incrementAndGet()

                    if (failures > maxRetries) {
                        Log.e(TAG, "Max retries ($maxRetries) exhausted. Watchdog entering FAILED state.")
                        updateState(WatchdogStatus.FAILED)
                        break
                    }

                    val recoveryTier = WatchdogPolicy.tierFor(failures)

                    updateState(WatchdogStatus.RECOVERING)
                    val backoffDelayMs = backoffMs(failures)
                    Log.d(TAG, "Attempting $recoveryTier recovery (attempt $failures/$maxRetries, backoff ${backoffDelayMs}ms)")

                    delay(backoffDelayMs)

                    val recovered = attemptRecovery(recoveryTier)

                    if (recovered) {
                        Log.d(TAG, "Recovery successful after $recoveryTier attempt")
                        totalRecoveries.incrementAndGet()
                        lastRecoveryTimestamp = System.currentTimeMillis()
                        consecutiveFailures.set(0)
                        lastProcessedFrameCount = streamingManager.processedFrames.value
                        lastFrameCheckTimeMs = System.currentTimeMillis()
                        updateState(WatchdogStatus.MONITORING)
                    } else {
                        Log.w(TAG, "$recoveryTier recovery failed")
                        updateState(WatchdogStatus.COOLDOWN)
                    }
                } else {
                    // Healthy — reset failure tracking
                    if (consecutiveFailures.get() > 0) {
                        Log.d(TAG, "Stream healthy again, resetting failure count")
                        consecutiveFailures.set(0)
                        lastFailureReason = null
                        updateState(WatchdogStatus.MONITORING)
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
        )

        val reason = WatchdogPolicy.evaluate(snapshot)
        if (reason != null) {
            return WatchdogPolicy.failureMessage(reason, snapshot)
        }

        WatchdogPolicy.updatedTracking(snapshot)?.let { tracking ->
            lastProcessedFrameCount = tracking.processedFrameCount
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
     * Post-recovery health verification, per tier. The watchdog owns the
     * timing — the tier's delay, plus SOFT's before/after frame reads across
     * the observation window — and the verdict is
     * [WatchdogPolicy.verificationSuccess].
     */
    private suspend fun verifyRecovery(tier: RecoveryTier): Boolean = when (tier) {
        RecoveryTier.SOFT -> {
            // Wait a moment and check if frames start flowing again
            delay(WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS)
            val framesBeforeWait = streamingManager.processedFrames.value
            delay(WatchdogPolicy.RECOVERY_VERIFICATION_WINDOW_MS)
            val framesAfterWait = streamingManager.processedFrames.value

            val success = WatchdogPolicy.verificationSuccess(
                tier = tier,
                framesAdvanced = framesAfterWait > framesBeforeWait,
                clientCount = streamingManager.clientCount.value,
                isLive = streamingManager.isLiveStreaming(),
                cameraReady = cameraService.cameraState.value is CameraState.Ready,
            )

            if (success) {
                Log.d(TAG, "Soft recovery succeeded (frames: $framesBeforeWait → $framesAfterWait)")
            }
            success
        }

        RecoveryTier.MEDIUM -> {
            delay(WatchdogPolicy.RECOVERY_VERIFICATION_DELAY_MS)
            val success = WatchdogPolicy.verificationSuccess(
                tier = tier,
                framesAdvanced = false,
                clientCount = streamingManager.clientCount.value,
                isLive = streamingManager.isLiveStreaming(),
                cameraReady = cameraService.cameraState.value is CameraState.Ready,
            )

            if (success) {
                Log.d(TAG, "Medium recovery succeeded")
            }
            success
        }

        RecoveryTier.HARD -> {
            // Verify with a longer window for hard recovery
            delay(WatchdogPolicy.HARD_VERIFICATION_DELAY_MS)
            val success = WatchdogPolicy.verificationSuccess(
                tier = tier,
                framesAdvanced = false,
                clientCount = streamingManager.clientCount.value,
                isLive = streamingManager.isLiveStreaming(),
                cameraReady = cameraService.cameraState.value is CameraState.Ready,
            )

            if (success) {
                Log.d(TAG, "Hard recovery succeeded")
            }
            success
        }
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

    enum class WatchdogStatus {
        IDLE,          // Not monitoring (streaming not active or watchdog disabled)
        MONITORING,    // Actively checking health
        RECOVERING,    // Recovery in progress
        FAILED,        // Max retries exhausted — operator intervention needed
        COOLDOWN,      // Waiting before next retry attempt
    }

    companion object {
        private const val TAG = "StreamWatchdog"

        const val MIN_CHECK_INTERVAL_SECONDS = StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS
        const val MAX_CHECK_INTERVAL_SECONDS = StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS

        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        /** Pure exponential backoff with a 6-attempt doubling cap — exposed for tests. */
        fun backoffMs(attempt: Int): Long {
            val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(6))
            return backoff.coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
