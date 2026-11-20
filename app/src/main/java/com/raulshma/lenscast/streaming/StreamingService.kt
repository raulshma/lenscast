package com.raulshma.lenscast.streaming

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.raulshma.lenscast.core.ForegroundNotifications
import com.raulshma.lenscast.core.ReliabilityRelay

/**
 * Which typed foreground start ran last on the runtime service. Bookkeeping
 * with exactly one decision reader: the heartbeat branch ([StreamingService]
 * `ACTION_HEARTBEAT`) skips its server-only re-promotion while [Streaming]
 * is live — re-typing the shared-slot FGS mid-stream would sever while-in-use
 * camera access — so the recorded mode is what keeps a live camera stream's
 * typed standing observable and untouchable. [ServerOnly] is the
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
     * session/server stopService calls). One decision reads it — the
     * heartbeat branch's streaming guard, which must not re-type a live
     * camera stream's FGS — and no start path does. Main-thread service
     * callbacks write it; volatile keeps a diagnostic reader honest.
     */
    @Volatile
    private var foregroundMode = ForegroundMode.None

    /**
     * The ONE cast the service makes into the app: the narrow
     * [ReliabilityRelay] (implemented by the composition root), never the
     * full graph. Every collaborator entry — the heartbeat's evaluation,
     * the death-recovery aftermath with its relaunch re-arm, the
     * denied-promotion wake, the swipe-away record — is one of the relay's
     * four deliveries, so what each lever does stays at the composition
     * root and a test harness can implement the interface to drive the
     * branches the old `as? AppGraph` pulls silently no-op'd. The relay's
     * contract is failure tolerance (implementations wrap and log their
     * own), so these dispatches are bare.
     */
    private val reliabilityRelay: ReliabilityRelay? by lazy {
        application as? ReliabilityRelay
    }

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
                rearmPersistedOutputs = false,
            )
            // A sticky restart has no intent; the dashboard restart's relaunch
            // (AppRelaunch) arrives as ACTION_RELAUNCH. Both are the same
            // recovery: the server is the process's durable mode; the
            // application re-issues the current mode as soon as the server
            // comes up — and re-arms the persisted-enabled outputs (the
            // dashboard's App-Restart promise), which only THIS branch does.
            null, ACTION_RELAUNCH -> {
                if (intent != null) Log.d(TAG, "Relaunched by the restart alarm")
                startServerForeground(url = null, includeAudio = false, rearmPersistedOutputs = true)
            }
            // The alarm heartbeat's delivery (HeartbeatReceiver): the FGS
            // start recreated the process if it was dead, and this start
            // must land a foreground promotion or the timeout kills us. The
            // promotion is the same server-only recovery landing the
            // relaunch takes; the liveness evaluation + escalation then run
            // on the controller (HeartbeatController — the verdict comes
            // from WatchdogAlarmPolicy), launched off the main thread.
            //
            // THE STREAMING GUARD: when the CAMERA/MICROPHONE promotion is
            // the one currently live ([foregroundMode] == Streaming), a
            // live camera stream is running — and every 15-minute heartbeat
            // must NOT re-promote. A CONNECTED_DEVICE-typed startForeground
            // on the shared notification id re-types the FGS and severs
            // while-in-use camera access on Android 14+ (the typed grant
            // follows the LAST typed start). So mid-stream the heartbeat
            // skips the promotion entirely — and the server-only funnel's
            // FSI-resume evaluation with it — and goes straight to the
            // evaluation through the relay: that half is read-only plus
            // tier escalation, and a SOFT/MEDIUM verdict mid-stream is the
            // existing session-recovery path, in-spec.
            ACTION_HEARTBEAT -> {
                Log.d(TAG, "Heartbeat alarm fired (mode=$foregroundMode)")
                if (foregroundMode != ForegroundMode.Streaming) {
                    startServerForeground(url = null, includeAudio = false, rearmPersistedOutputs = false)
                }
                reliabilityRelay?.onHeartbeatDelivered()
            }
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

    /**
     * Record-only on the swipe-away (blueprint P0 item 6): stock Android
     * keeps a foreground service through a task removal, so there is nothing
     * to recover or restart here — and restarting would fight an explicit
     * user stop, the wrong default. The record matters because OEM task
     * managers (MIUI et al.) kill the process after the swipe: without the
     * flag, the sticky restart, the boot resume, and the future heartbeat
     * tier would all resurrect a stream the user deliberately killed. The
     * flag's lifetime rule lives in [StreamStateJournal] — it gates exactly
     * one boot and is cleared by the next explicit start, so it never blocks
     * resume forever. The record goes through the relay
     * ([ReliabilityRelay.onUserSwipedAway]); losing the write only means one
     * boot cycle may resume what the user killed — the journal's own
     * documented failure tolerance, wrapped at the composition root.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        reliabilityRelay?.onUserSwipedAway()
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
            reliabilityRelay?.onStreamingPromotionDenied()
        }
        Log.d(TAG, "Streaming foreground service started")
    }

    private fun startServerForeground(url: String?, includeAudio: Boolean, rearmPersistedOutputs: Boolean) {
        promoteServerOnly(url, includeAudio)
        // The death-recovery aftermath (blueprint P1 item 11 + the App-Restart
        // re-arm), through the relay: this method is the funnel every
        // death-and-relaunch recovery lands through — the relaunch alarm
        // (ACTION_RELAUNCH), the sticky restart's null intent, the heartbeat
        // delivery (ACTION_HEARTBEAT), and the dashboard's server start.
        // Whether the FSI prompt fires is the pure FsiResumePolicy's call
        // (opt-in flag, journal's pre-death live-output record, the
        // swipe-kill stand-down, the once-per-death marker); the persisted-
        // output re-arm rides along ONLY on the restart recoveries
        // ([rearmPersistedOutputs] — the routing fact this branch set owns).
        // A denied streaming promotion never reaches here — that path holds
        // a live session and belongs to the lock-bypass wake. Both levers,
        // and their failure tolerance, live at the composition root.
        reliabilityRelay?.onDeathRecoveryLanded(rearmPersistedOutputs)
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
        const val ACTION_RELAUNCH = "com.raulshma.lenscast.RELAUNCH_FROM_RESTART_ALARM"

        /**
         * The alarm heartbeat's delivery action (HeartbeatReceiver → this
         * service): the FGS start that recreates a dead process and the
         * liveness evaluation's landing point.
         */
        const val ACTION_HEARTBEAT = "com.raulshma.lenscast.HEARTBEAT_ALARM"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_AUDIO_ACTIVE = "stream_audio_active"
        private const val SERVER_CHANNEL_ID = "server_channel"
        private const val STREAMING_CHANNEL_ID = "streaming_channel"
        private const val TAG = "StreamingService"
    }
}
