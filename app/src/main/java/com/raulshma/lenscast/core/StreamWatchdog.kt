package com.raulshma.lenscast.core

import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.streaming.StreamingManager
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * StreamWatchdog — A coroutine-based health monitor that detects streaming failures
 * (camera crashes, server disconnections, frame stalls) and automatically restarts
 * the stream with escalating recovery strategies and exponential backoff.
 *
 * Recovery tiers:
 *  1. SOFT  — Rebind CameraX use cases (fixes most transient camera glitches)
 *  2. MEDIUM — Restart streaming server + rebind camera
 *  3. HARD  — Full re-initialize: CameraService.initialize() → restart everything
 *
 * The watchdog is disabled by default and must be explicitly enabled via settings.
 */
class StreamWatchdog(
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val powerManager: PowerManager,
    private val thermalMonitor: ThermalMonitor,
) {

    // ── Configuration ──

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var maxRetries: Int = DEFAULT_MAX_RETRIES
        private set

    @Volatile
    var checkIntervalSeconds: Int = DEFAULT_CHECK_INTERVAL_SECONDS
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
        maxRetries = value.coerceIn(1, 20)
    }

    fun setCheckIntervalSeconds(value: Int) {
        checkIntervalSeconds = value.coerceIn(MIN_CHECK_INTERVAL_SECONDS, MAX_CHECK_INTERVAL_SECONDS)
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

                    val recoveryTier = when {
                        failures <= 2 -> RecoveryTier.SOFT
                        failures <= 4 -> RecoveryTier.MEDIUM
                        else -> RecoveryTier.HARD
                    }

                    updateState(WatchdogStatus.RECOVERING)
                    val backoffMs = calculateBackoff(failures)
                    Log.d(TAG, "Attempting $recoveryTier recovery (attempt $failures/$maxRetries, backoff ${backoffMs}ms)")

                    delay(backoffMs)

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

    /**
     * Reset the watchdog state (e.g., after user manually resolves an issue).
     */
    fun reset() {
        consecutiveFailures.set(0)
        lastFailureReason = null
        if (monitorJob?.isActive == true) {
            updateState(WatchdogStatus.MONITORING)
        } else {
            updateState(WatchdogStatus.IDLE)
        }
    }

    // ── Health Checks ──

    /**
     * Evaluates stream health. Returns a failure reason string if unhealthy, null if OK.
     */
    private fun checkStreamHealth(): String? {
        // 1. Camera state check
        val cameraState = cameraService.cameraState.value
        if (cameraState is CameraState.Error) {
            return "Camera error: ${cameraState.message}"
        }

        // 2. Streaming should be active but server isn't running
        if (wasStreamingActive && !streamingManager.isServerRunning.value) {
            return "Streaming server stopped unexpectedly"
        }

        // 3. Stream was active but is no longer live
        if (wasStreamingActive && !streamingManager.isLiveStreaming()) {
            return "Stream stopped unexpectedly"
        }

        // 4. Frame stall detection — only when clients are connected
        if (streamingManager.clientCount.value > 0) {
            val currentFrameCount = streamingManager.processedFrames.value
            val now = System.currentTimeMillis()
            val elapsed = now - lastFrameCheckTimeMs

            if (elapsed >= FRAME_STALL_THRESHOLD_MS && currentFrameCount == lastProcessedFrameCount) {
                return "Frame delivery stalled (no frames for ${elapsed / 1000}s)"
            }

            // Update tracking if frames are flowing
            if (currentFrameCount != lastProcessedFrameCount) {
                lastProcessedFrameCount = currentFrameCount
                lastFrameCheckTimeMs = now
            }
        } else {
            // No clients — reset frame tracking to avoid false positives once clients reconnect
            lastProcessedFrameCount = streamingManager.processedFrames.value
            lastFrameCheckTimeMs = System.currentTimeMillis()
        }

        return null
    }

    // ── Recovery Logic ──

    private suspend fun attemptRecovery(tier: RecoveryTier): Boolean {
        return try {
            when (tier) {
                RecoveryTier.SOFT -> softRecovery()
                RecoveryTier.MEDIUM -> mediumRecovery()
                RecoveryTier.HARD -> hardRecovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "$tier recovery threw exception", e)
            false
        }
    }

    /**
     * Tier 1: Rebind CameraX use cases. Cheapest fix for transient camera glitches.
     */
    private suspend fun softRecovery(): Boolean {
        Log.d(TAG, "Soft recovery: rebinding CameraX use cases")

        withContext(Dispatchers.Main) {
            cameraService.rebindUseCases()
        }

        // Wait a moment and check if frames start flowing again
        delay(RECOVERY_VERIFICATION_DELAY_MS)
        val framesBeforeWait = streamingManager.processedFrames.value
        delay(RECOVERY_VERIFICATION_WINDOW_MS)
        val framesAfterWait = streamingManager.processedFrames.value

        val success = framesAfterWait > framesBeforeWait ||
                streamingManager.clientCount.value == 0 // No clients = can't verify, assume OK

        if (success) {
            Log.d(TAG, "Soft recovery succeeded (frames: $framesBeforeWait → $framesAfterWait)")
        }
        return success
    }

    /**
     * Tier 2: Restart streaming server + rebind camera.
     */
    private suspend fun mediumRecovery(): Boolean {
        Log.d(TAG, "Medium recovery: restarting streaming server + rebinding camera")

        // Track which streams were active
        val webWasActive = streamingManager.isWebStreamActive()
        val rtspWasActive = streamingManager.isRtspRunning.value

        // Stop everything
        streamingManager.pauseStreaming()

        // Restart server
        val serverStarted = streamingManager.ensureServerRunning()
        if (!serverStarted) {
            Log.e(TAG, "Medium recovery: failed to restart server")
            return false
        }

        // Rebind camera
        withContext(Dispatchers.Main) {
            cameraService.rebindUseCases()
        }

        // Restart the streams that were previously active
        if (webWasActive) {
            streamingManager.startWebStreaming()
        }
        if (rtspWasActive) {
            streamingManager.startRtspStreaming()
        }

        // Verify
        delay(RECOVERY_VERIFICATION_DELAY_MS)
        val isLive = streamingManager.isLiveStreaming()

        if (isLive) {
            Log.d(TAG, "Medium recovery succeeded")
        }
        return isLive
    }

    /**
     * Tier 3: Full re-initialization of camera and streaming pipelines.
     */
    private suspend fun hardRecovery(): Boolean {
        Log.d(TAG, "Hard recovery: full re-initialization")

        // Track which streams were active
        val webWasActive = streamingManager.isWebStreamActive()
        val rtspWasActive = streamingManager.isRtspRunning.value

        // Stop everything
        streamingManager.stopStreaming()

        // Re-initialize camera
        val cameraResult = withContext(Dispatchers.Main) {
            cameraService.initialize()
        }

        if (cameraResult.isFailure) {
            Log.e(TAG, "Hard recovery: camera re-initialization failed", cameraResult.exceptionOrNull())
            return false
        }

        // Re-acquire keep-alive and rebind
        withContext(Dispatchers.Main) {
            cameraService.acquireKeepAlive()
            cameraService.rebindUseCases()
        }

        // Refresh power and thermal state
        powerManager.refreshBatteryState()
        thermalMonitor.startMonitoring()
        streamingManager.thermalMonitor = thermalMonitor

        // Restart streaming
        val started = streamingManager.startStreaming()
        if (!started) {
            Log.e(TAG, "Hard recovery: failed to restart streaming")
            return false
        }

        // Restart specific streams if needed
        if (webWasActive && !streamingManager.isWebStreamActive()) {
            streamingManager.startWebStreaming()
        }
        if (rtspWasActive && !streamingManager.isRtspRunning.value) {
            streamingManager.startRtspStreaming()
        }

        // Verify with a longer window for hard recovery
        delay(RECOVERY_VERIFICATION_DELAY_MS * 2)
        val isLive = streamingManager.isLiveStreaming()
        val cameraReady = cameraService.cameraState.value is CameraState.Ready

        val success = isLive && cameraReady
        if (success) {
            Log.d(TAG, "Hard recovery succeeded")
        }
        return success
    }

    // ── Helpers ──

    private fun calculateBackoff(attempt: Int): Long {
        val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(6))
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

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

    private enum class RecoveryTier {
        SOFT,   // Rebind use cases
        MEDIUM, // Restart server + rebind
        HARD,   // Full re-init
    }

    companion object {
        private const val TAG = "StreamWatchdog"

        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_CHECK_INTERVAL_SECONDS = 5
        const val MIN_CHECK_INTERVAL_SECONDS = 3
        const val MAX_CHECK_INTERVAL_SECONDS = 30

        private const val FRAME_STALL_THRESHOLD_MS = 15_000L
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val RECOVERY_VERIFICATION_DELAY_MS = 2_000L
        private const val RECOVERY_VERIFICATION_WINDOW_MS = 3_000L
    }
}
