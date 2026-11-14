package com.raulshma.lenscast.streaming

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.raulshma.lenscast.core.ForegroundNotifications

/**
 * Which typed foreground start ran last on the runtime service. Pure
 * bookkeeping — the recorded mode never drives a start/stop decision — it
 * only makes the implicit "which promotion is live" observable (diagnostics,
 * and the hook a future mode-handoff fix needs). [ServerOnly] is the
 * CONNECTED_DEVICE promotion ([ForegroundNotifications.startConnectedDeviceForeground]),
 * [Streaming] the CAMERA/MICROPHONE one ([ForegroundNotifications.startCameraForeground]);
 * both share the one notification slot (the registry in
 * [com.raulshma.lenscast.core.ForegroundNotifications]).
 */
enum class ForegroundMode {
    /** No typed start has succeeded since the last teardown. */
    None,

    /** The last successful typed start was the server-only CONNECTED_DEVICE promotion. */
    ServerOnly,

    /** The last successful typed start was the streaming CAMERA/MICROPHONE promotion. */
    Streaming,
}

/**
 * The app runtime's one foreground service. Two modes share one notification
 * slot:
 *
 * - **server only** - a CONNECTED_DEVICE foreground service while the camera
 *   web server is running with no live output. This is what keeps the process
 *   and its outbound network (webhook/MQTT/Web Push) out of Android's
 *   background-network firewall between streams.
 * - **streaming** - the existing CAMERA/MICROPHONE foreground service owned by
 *   [StreamingSession] while an output is live.
 *
 * One service (rather than one per mode) means one notification, one
 * lifecycle, and no mode-transition race where a server notification and a
 * stream notification can outlive each other.
 */
class StreamingService : Service() {

    /**
     * The last successful typed foreground start, cleared on teardown
     * ([onDestroy] funnels both the self-stop of a failed promotion and the
     * session/server stopService calls). Bookkeeping only: no start/stop
     * decision reads it. Main-thread service callbacks write it; volatile
     * keeps a diagnostic reader honest.
     */
    @Volatile
    private var foregroundMode = ForegroundMode.None

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifications.createChannel(this, SERVER_CHANNEL_ID, "Camera server")
        ForegroundNotifications.createChannel(this, STREAMING_CHANNEL_ID, "Streaming")
        Log.d(TAG, "StreamingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreamingForeground(
                url = intent.getStringExtra(EXTRA_URL),
                includeAudio = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false),
            )
            ACTION_SERVER -> startServerForeground(
                url = intent.getStringExtra(EXTRA_URL),
                includeAudio = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false),
            )
            // A sticky restart has no intent. The server is the process's
            // durable mode; the application re-issues the current mode as
            // soon as the server comes up.
            null -> startServerForeground(url = null, includeAudio = false)
            else -> Log.w(TAG, "Ignoring unknown foreground action: ${intent.action}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The single teardown funnel: stopSelf (a failed promotion's unwind)
        // and stopService (the session end / server-gone paths) both land
        // here, so the recorded mode clears exactly when the notification
        // goes away.
        foregroundMode = ForegroundMode.None
        removeForegroundNotification()
        super.onDestroy()
        Log.d(TAG, "StreamingService destroyed")
    }

    private fun startStreamingForeground(url: String?, includeAudio: Boolean) {
        val promoted = promote(
            channelId = STREAMING_CHANNEL_ID,
            title = "LensCast Streaming",
            message = ForegroundNotifications.streamingMessage(url, includeAudio),
            includeAudio = includeAudio,
            serverMode = false,
        )
        if (!promoted) {
            // The CAMERA/MICROPHONE promotion is denied while the app is not
            // visible — MIUI's while-in-use camera policy throws
            // SecurityException for it (stock API 30+ restricts it too), and
            // this is exactly the locked-mount start: tile, boot resume,
            // automation, the dashboard's remote start. The old behavior —
            // stopSelf — tore the service down entirely, taking the working
            // server-only notification with it and leaving the live stream
            // with no foreground service at all. Fall back to the server-only
            // promotion: the process keeps its foreground standing and the
            // server stays reachable; the session's stall watch and the
            // resume seam own re-asserting the streaming promotion once the
            // policy allows.
            Log.w(TAG, "Streaming foreground promotion denied; holding the server-only promotion")
            promoteServerOnly(url, includeAudio)
            // Under lock this denial is the whole black-screen story: the
            // outputs run but no camera can open. Hand the wake to the
            // lock-bypass shim — a full-screen intent over the keyguard
            // makes the app visible for the seconds the promotion and the
            // camera bind need. Unlocked denials stay with the resume seam.
            runCatching {
                (application as? com.raulshma.lenscast.AppGraph)
                    ?.lockBypassController
                    ?.onStreamingPromotionDenied()
            }.onFailure {
                Log.w(TAG, "Lock bypass dispatch failed: ${it.javaClass.simpleName}")
            }
        }
        Log.d(TAG, "Streaming foreground service started")
    }

    private fun startServerForeground(url: String?, includeAudio: Boolean) {
        promoteServerOnly(url, includeAudio)
        Log.d(TAG, "Server foreground service started")
    }

    /**
     * The server-only promotion — both the direct server start and the
     * denied-streaming-promotion fallback land here, so the two paths cannot
     * drift apart in channel, title, or typing.
     */
    private fun promoteServerOnly(url: String?, includeAudio: Boolean): Boolean =
        promote(
            channelId = SERVER_CHANNEL_ID,
            title = "LensCast Camera Server",
            message = ForegroundNotifications.serverMessage(url),
            includeAudio = includeAudio,
            serverMode = true,
        )

    /**
     * One typed foreground promotion attempt. Returns whether the typed
     * startForeground landed; [foregroundMode] records only landings. On
     * failure the caller decides the fallback — this method never stops the
     * service itself.
     */
    private fun promote(
        channelId: String,
        title: String,
        message: String,
        includeAudio: Boolean,
        serverMode: Boolean,
    ): Boolean {
        val result = runCatching {
            val notification = ForegroundNotifications.build(
                this, channelId, title, message, ongoing = true,
            )
            if (serverMode) {
                ForegroundNotifications.startConnectedDeviceForeground(
                    this,
                    ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
                    notification,
                    includeAudio,
                )
            } else {
                ForegroundNotifications.startCameraForeground(
                    this,
                    ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
                    notification,
                    includeAudio,
                )
            }
            // Record only a start that actually succeeded — the typed
            // startForeground calls above either threw or landed.
            foregroundMode = if (serverMode) ForegroundMode.ServerOnly else ForegroundMode.Streaming
        }
        result.exceptionOrNull()?.let {
            Log.e(TAG, "Foreground promotion failed: ${it.javaClass.simpleName}")
        }
        return result.isSuccess
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_STREAMING"
        const val ACTION_SERVER = "com.raulshma.lenscast.START_SERVER"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_AUDIO_ACTIVE = "stream_audio_active"
        private const val SERVER_CHANNEL_ID = "server_channel"
        private const val STREAMING_CHANNEL_ID = "streaming_channel"
        private const val TAG = "StreamingService"
    }
}
