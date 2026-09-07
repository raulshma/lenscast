package com.raulshma.lenscast.streaming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
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
 *
 * The watchdog is resolved lazily because the watchdog itself receives this
 * session for recovery; neither side may touch the other at construction time.
 */
class StreamingSession(
    private val context: Context,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val powerManager: PowerManager,
    private val thermalMonitor: ThermalMonitor,
    watchdogProvider: () -> StreamWatchdog,
) {

    private val streamWatchdog: StreamWatchdog by lazy(watchdogProvider)

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
     * Re-attach the environment after a watchdog recovery. The session stays
     * active — keep-alive and the foreground service were never released — so
     * only the state the recovery disturbed gets refreshed.
     */
    suspend fun refreshAfterRecovery() {
        lifecycleMutex.withLock {
            if (!active.get()) return
            powerManager.refreshBatteryState()
            thermalMonitor.startMonitoring()
            streamingManager.applyBatteryOptimization(powerManager.optimizationResult.value)
            if (!rebindCamera()) {
                Log.w(TAG, "Post-recovery camera rebind timed out")
            }
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
