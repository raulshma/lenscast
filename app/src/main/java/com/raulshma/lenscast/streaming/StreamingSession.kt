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
 * It also owns the runtime foreground service's server-only mode: when the
 * camera web server is up with no live output, [ensureServerForeground] keeps
 * the service (and therefore outbound alert delivery) out of Android's
 * background-network firewall without holding the camera or a wake lock.
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
    // Serializes the foreground-service mode handoff with the session's
    // active bit. begin() and the server-state controller can otherwise
    // interleave a server promotion after a stream promotion, downgrading a
    // live stream to the camera-free CONNECTED_DEVICE type.
    private val foregroundModeLock = Any()
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var optimizationJob: Job? = null

    val isActive: Boolean get() = active.get()

    /**
     * Attach everything a live stream needs. Call once the stream started
     * successfully. When a session is already active (a second output
     * starting late) only the foreground-service type is re-asserted, so a
     * newly audio-carrying output always runs under the MICROPHONE type. On
     * failure the partially-attached pieces are unwound before rethrowing.
     */
    suspend fun begin() {
        lifecycleMutex.withLock {
            if (active.get()) {
                // A second output started late (startBoth starts web, begins,
                // then starts RTSP and begins again): re-assert the foreground
                // service type so a newly audio-carrying output is never
                // captured without the MICROPHONE type.
                sendForegroundIntent(StreamingService.ACTION_START)
                Log.d(TAG, "Streaming session foreground type re-asserted for late output start")
                return
            }
            synchronized(foregroundModeLock) { active.set(true) }
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
                synchronized(foregroundModeLock) { active.set(false) }
                throw e
            }
        }
    }

    /**
     * The resume seam: while a session is live, re-assert its streaming
     * foreground promotion and re-arm the watchdog. A start that happened
     * while the app was not visible (tile, boot resume, automation on a
     * locked mount) could not take the CAMERA/MICROPHONE typed promotion —
     * the while-in-use camera policy denies it — and the stall watch may
     * have exhausted its retries into FAILED while the camera stayed denied.
     * The moment the app is visible again both heal: the typed promotion
     * lands, and a fresh watchdog pass rebinds the camera the policy just
     * allowed back.
     */
    fun reassertStreamingForegroundIfActive() {
        // Read-then-send under the same lock the end path flips the bit
        // under: a re-assert racing a session end must never promote a
        // streaming notification over a torn-down session.
        val sent = synchronized(foregroundModeLock) {
            if (active.get()) {
                sendForegroundIntent(StreamingService.ACTION_START)
                true
            } else {
                false
            }
        }
        if (!sent) return
        Log.d(TAG, "Streaming session foreground re-asserted on resume")
        runCatching { streamWatchdog.startMonitoring() }
    }

    /**
     * Re-asserts the foreground service's type after a mic-capture verdict
     * flip the stream lifecycle did not cause - arming sound detection starts
     * a headless capture, disarming stops it, and the service must carry (or
     * drop) the MICROPHONE type accordingly. With no live session, re-asserts
     * the server-only mode when the server is up so a headless capture still
     * gets the type.
     */
    suspend fun refreshForegroundMicVerdict() {
        lifecycleMutex.withLock {
            if (active.get()) {
                sendForegroundIntent(StreamingService.ACTION_START)
                Log.d(TAG, "Foreground mic verdict re-asserted")
            } else if (streamingManager.isServerRunning.value) {
                // Sound detection can hold the mic with no stream session;
                // server mode must gain/lose the MICROPHONE type just like a
                // live stream does.
                ensureServerForeground()
                Log.d(TAG, "Server foreground mic verdict re-asserted")
            }
        }
    }

    /**
     * Promote the runtime foreground service in server-only mode. No-op while
     * a stream session is active: that mode already owns the one notification
     * slot and carries the stronger camera/microphone types.
     *
     * Best-effort by design. Android can deny a foreground-service start from
     * the background (or from a background app-start); the server keeps
     * running and the next visible resume retries through
     * [ServerForegroundController.sync]. The caller is never crashed by a
     * policy denial here.
     */
    fun ensureServerForeground() {
        synchronized(foregroundModeLock) {
            if (active.get()) return
            ensureServerForegroundLocked()
        }
    }

    private fun ensureServerForegroundLocked() {
        runCatching { sendForegroundIntent(StreamingService.ACTION_SERVER) }
            .onFailure {
                Log.w(TAG, "Server foreground start deferred: ${it.javaClass.simpleName}")
            }
    }

    /**
     * Remove the server-only notification when the server is gone. No-op while
     * a stream session is active; [end] owns that handoff.
     */
    fun stopServerForegroundIfIdle() {
        synchronized(foregroundModeLock) {
            if (active.get()) return
            stopForegroundServiceLocked()
        }
    }

    private fun stopForegroundServiceLocked() {
        context.stopService(Intent(context, StreamingService::class.java))
    }

    /**
     * Tear the session down. No-op while another output is still live. When no
     * session is active, a stopped server still drops the server-only
     * foreground service.
     */
    suspend fun end() {
        lifecycleMutex.withLock {
            if (!active.get()) {
                if (!streamingManager.isServerRunning.value) stopServerForegroundIfIdle()
                return
            }
            if (streamingManager.isLiveStreaming()) return
            // Keep the session active through teardown so a concurrent server
            // sync cannot promote server mode over a still-live stream.
            stopOptimizationPolling()
            streamWatchdog.stopMonitoring()
            powerManager.releaseWakeLock()
            thermalMonitor.stopMonitoring()
            releaseKeepAliveAndRebind()
            // Publish the session end and the new foreground mode atomically.
            synchronized(foregroundModeLock) {
                active.set(false)
                if (streamingManager.isServerRunning.value) {
                    ensureServerForegroundLocked()
                } else {
                    stopForegroundServiceLocked()
                }
            }
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
    // foreground handoff is attempted only if the START may have gone through.
    // If the server is still up, fall back to server mode instead of dropping
    // the process back to the background-network firewall.
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
            runCatching {
                if (streamingManager.isServerRunning.value) {
                    sendForegroundIntent(StreamingService.ACTION_SERVER)
                } else {
                    context.stopService(Intent(context, StreamingService::class.java))
                }
            }
        }
    }

    private fun startOptimizationPolling() {
        optimizationJob?.cancel()
        optimizationJob = scope.launch {
            while (true) {
                powerManager.refreshBatteryState()
                // A live session must never outlive its wake lock: the renew
                // call no-ops until the lock is halfway to its timeout, and
                // re-acquires if it was lost — a suspended CPU stalls the
                // frame pump and every detection path with it.
                powerManager.renewWakeLock()
                streamingManager.applyBatteryOptimization(powerManager.optimizationResult.value)
                // A live session never gives up on its camera. A watchdog
                // that exhausted its retries sits in FAILED with a dead
                // loop; on ROMs whose while-in-use camera policy denies
                // recovery while the app is backgrounded, every pass fails
                // until the policy relents (screen unlocked, app visible).
                // Re-arm on each poll so the ladder keeps running at this
                // cadence for as long as the session lives — the unattended
                // mount has no one to restart the stream by hand.
                if (!streamWatchdog.isMonitoring) streamWatchdog.startMonitoring()
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
            when (action) {
                StreamingService.ACTION_START -> {
                    // Either live output capturing the mic needs the service's
                    // MICROPHONE type - the old web-only flag left RTSP-only
                    // streaming capturing without it, which the OS silences. The
                    // RTMP push taps the same AAC path and the WHIP push owns its
                    // dedicated AudioRecord, so both count the same way; the
                    // shared capture also covers the sound-detection hold, which
                    // keeps the mic up with no stream consumers at all.
                    putExtra(
                        StreamingService.EXTRA_AUDIO_ACTIVE,
                        streamingManager.isSharedMicCapturing() ||
                            streamingManager.isRtspAudioActive() ||
                            streamingManager.isRtmpAudioActive() ||
                            streamingManager.isWhipAudioActive(),
                    )
                }
                StreamingService.ACTION_SERVER -> {
                    // Server mode is camera-free; only a headless sound-detection
                    // hold can legitimately need the MICROPHONE type here.
                    if (streamingManager.isSharedMicCapturing()) {
                        putExtra(StreamingService.EXTRA_AUDIO_ACTIVE, true)
                    }
                }
            }
        }
        val promote = action == StreamingService.ACTION_START || action == StreamingService.ACTION_SERVER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && promote) {
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
