package com.raulshma.lenscast.streaming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.WatchdogPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the live-streaming session choreography: wake lock, thermal monitoring,
 * battery optimization, camera keep-alive, foreground service and watchdog.
 *
 * One begin()/end() pair per session replaces the per-caller copies that used
 * to live in Web API callers, CameraViewModel and StreamWatchdog. begin() is
 * idempotent — an already-active session is left alone — and end() tears down
 * only when no stream is live, so overlapping web/app sessions are safe.
 * begin/end/recovery are serialized on a mutex: a begin racing an end can
 * never leave a new stream attached to a session that is being torn down.
 * [recover] owns all three watchdog recovery tiers' mechanics — the watchdog
 * keeps only the tier decision, backoff, and verification.
 *
 * The watchdog is resolved lazily because the watchdog itself receives this
 * session for recovery; neither side may touch the other at construction time.
 */
class StreamingSession(
    private val context: Context,
    private val cameraService: CameraService,
    streamingManagerProvider: () -> StreamingManager,
    private val powerManager: PowerManager,
    private val thermalMonitor: ThermalMonitor,
    watchdogProvider: () -> StreamWatchdog,
) {

    private val streamWatchdog: StreamWatchdog by lazy(watchdogProvider)

    // Provider, not a direct reference: the manager constructs the Web API
    // stack (and through it this session) during its own lazy initialization,
    // so touching the manager here would re-enter that initializer.
    private val streamingManager: StreamingManager by lazy(streamingManagerProvider)

    private val active = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var optimizationJob: Job? = null

    val isActive: Boolean get() = active.get()

    /**
     * Attach everything a live stream needs. Call once the stream started
     * successfully; a no-op when a session is already active. On failure the
     * partially-attached pieces are unwound before rethrowing.
     */
    suspend fun begin() {
        lifecycleMutex.withLock {
            if (active.get()) return
            active.set(true)
            var keepAliveAcquired = false
            var foregroundStarted = false
            try {
                powerManager.refreshBatteryState()
                powerManager.acquireWakeLock()
                thermalMonitor.startMonitoring()
                streamingManager.applyBatteryOptimization(powerManager.optimizationResult.value)
                if (!acquireKeepAliveAndRebind()) {
                    // A session without the camera keep-alive is a lie — fail
                    // loudly so the caller can stop the stream it just started.
                    throw IllegalStateException("Camera keep-alive attach timed out")
                }
                keepAliveAcquired = true
                startOptimizationPolling()
                sendForegroundIntent(StreamingService.ACTION_START)
                foregroundStarted = true
                streamWatchdog.startMonitoring()
                Log.d(TAG, "Streaming session begun")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to begin streaming session; unwinding", e)
                unwind(keepAliveAcquired, foregroundStarted)
                active.set(false)
                throw e
            }
        }
    }

    /**
     * Tear the session down. No-op while another output is still live or when
     * no session is active.
     */
    suspend fun end() {
        lifecycleMutex.withLock {
            if (!active.get()) return
            if (streamingManager.isLiveStreaming()) return
            active.set(false)
            stopOptimizationPolling()
            streamWatchdog.stopMonitoring()
            powerManager.releaseWakeLock()
            thermalMonitor.stopMonitoring()
            releaseKeepAliveAndRebind()
            sendForegroundIntent(StreamingService.ACTION_PAUSE)
            Log.d(TAG, "Streaming session ended")
        }
    }

    /**
     * The single owner of every recovery tier's mechanics. The watchdog
     * decides *when* (WatchdogPolicy's tier ladder), applies backoff, and
     * verifies afterwards; this owns *what happens*: the stream-activity
     * snapshot, the camera rebind (through the bounded Main seam), and the
     * conditional web/RTSP restarts.
     *
     * Tiers, per the watchdog's historical semantics:
     *  - SOFT — rebind the camera use cases only.
     *  - MEDIUM — snapshot the live outputs, pause them, restart the server,
     *    rebind, and restart what was live.
     *  - HARD — snapshot, stop streaming entirely, re-initialize the camera,
     *    refresh the disturbed session state, restart streaming and the
     *    outputs that did not come back.
     *
     * False means the tier's hard step failed (server or camera could not
     * restart); the caller verifies stream health either way.
     */
    suspend fun recover(tier: WatchdogPolicy.RecoveryTier): Boolean {
        return when (tier) {
            WatchdogPolicy.RecoveryTier.SOFT -> {
                Log.d(TAG, "Soft recovery: rebinding CameraX use cases")
                rebindCamera().also { bound ->
                    if (!bound) Log.w(TAG, "Soft recovery: camera rebind timed out")
                }
            }

            WatchdogPolicy.RecoveryTier.MEDIUM -> {
                Log.d(TAG, "Medium recovery: restarting streaming server + rebinding camera")
                val webWasActive = streamingManager.isWebStreamActive()
                val rtspWasActive = streamingManager.isRtspRunning.value
                streamingManager.pauseStreaming()
                if (!streamingManager.ensureServerRunning()) {
                    Log.e(TAG, "Medium recovery: failed to restart server")
                    return false
                }
                rebindCamera()
                restartStreamsIfNotYetLive(webWasActive, rtspWasActive)
                true
            }

            WatchdogPolicy.RecoveryTier.HARD -> {
                Log.d(TAG, "Hard recovery: full re-initialization")
                val webWasActive = streamingManager.isWebStreamActive()
                val rtspWasActive = streamingManager.isRtspRunning.value
                streamingManager.stopStreaming()
                val cameraResult = withContext(Dispatchers.Main) {
                    cameraService.initialize()
                }
                if (cameraResult.isFailure) {
                    Log.e(TAG, "Hard recovery: camera re-initialization failed", cameraResult.exceptionOrNull())
                    return false
                }
                // The session stayed active through recovery, so keep-alive and
                // the foreground service are still held; only refresh what the
                // recovery disturbed.
                lifecycleMutex.withLock {
                    if (active.get()) refreshDisturbedState()
                }
                if (!streamingManager.startStreaming()) {
                    Log.e(TAG, "Hard recovery: failed to restart streaming")
                    return false
                }
                restartStreamsIfNotYetLive(webWasActive, rtspWasActive)
                true
            }
        }
    }

    /**
     * The session-attached state a recovery disturbed: battery state, thermal
     * monitoring, and the battery-optimization application, then the camera
     * rebind. Runs under [lifecycleMutex].
     */
    private suspend fun refreshDisturbedState() {
        powerManager.refreshBatteryState()
        thermalMonitor.startMonitoring()
        streamingManager.applyBatteryOptimization(powerManager.optimizationResult.value)
        if (!rebindCamera()) {
            Log.w(TAG, "Post-recovery camera rebind timed out")
        }
    }

    /** Restart only the outputs that were live before the disturbance. */
    private fun restartStreamsIfNotYetLive(webWasActive: Boolean, rtspWasActive: Boolean) {
        if (webWasActive && !streamingManager.isWebStreamActive()) {
            streamingManager.startWebStreaming()
        }
        if (rtspWasActive && !streamingManager.isRtspRunning.value) {
            streamingManager.startRtspStreaming()
        }
    }

    // Best-effort reverse of begin(). Runs when the attach failed midway; the
    // foreground PAUSE is attempted only if the START may have gone through.
    private suspend fun unwind(keepAliveAcquired: Boolean, foregroundStarted: Boolean) {
        stopOptimizationPolling()
        runCatching { streamWatchdog.stopMonitoring() }
        runCatching { powerManager.releaseWakeLock() }
        runCatching { thermalMonitor.stopMonitoring() }
        if (keepAliveAcquired) {
            runCatching {
                withTimeoutOrNull(MAIN_DISPATCH_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        cameraService.releaseKeepAlive()
                    }
                }
            }
        }
        if (foregroundStarted) {
            runCatching { sendForegroundIntent(StreamingService.ACTION_PAUSE) }
        }
    }

    private fun startOptimizationPolling() {
        optimizationJob?.cancel()
        optimizationJob = scope.launch {
            while (true) {
                powerManager.refreshBatteryState()
                streamingManager.applyBatteryOptimization(powerManager.optimizationResult.value)
                delay(OPTIMIZATION_POLL_MS)
            }
        }
    }

    private fun stopOptimizationPolling() {
        optimizationJob?.cancel()
        optimizationJob = null
    }

    private suspend fun onMain(timeoutMs: Long, block: () -> Unit): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) { block() }
        } != null
    }

    /** @return false when the Main thread didn't service the attach in time. */
    private suspend fun acquireKeepAliveAndRebind(): Boolean {
        return onMain(MAIN_DISPATCH_TIMEOUT_MS) {
            cameraService.acquireKeepAlive()
            cameraService.rebindUseCases()
        }
    }

    private suspend fun releaseKeepAliveAndRebind() {
        val completed = onMain(MAIN_DISPATCH_TIMEOUT_MS) {
            cameraService.releaseKeepAlive()
            cameraService.rebindUseCases()
        }
        if (!completed) {
            Log.w(TAG, "Keep-alive release did not complete within ${MAIN_DISPATCH_TIMEOUT_MS}ms")
        }
    }

    private suspend fun rebindCamera(): Boolean {
        return onMain(MAIN_DISPATCH_TIMEOUT_MS) {
            cameraService.rebindUseCases()
        }
    }

    private fun sendForegroundIntent(action: String) {
        val intent = Intent(context, StreamingService::class.java).apply {
            this.action = action
            putExtra(StreamingService.EXTRA_URL, streamingManager.streamUrl.value)
            if (action == StreamingService.ACTION_START) {
                putExtra(StreamingService.EXTRA_AUDIO_ACTIVE, streamingManager.isAudioStreaming.value)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == StreamingService.ACTION_START) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "StreamingSession"
        private const val OPTIMIZATION_POLL_MS = 30_000L
        private const val MAIN_DISPATCH_TIMEOUT_MS = 2_000L
    }
}
