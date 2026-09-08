package com.raulshma.lenscast.streaming

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.raulshma.lenscast.core.ForegroundNotifications

class StreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifications.createChannel(this, CHANNEL_ID, "Streaming")
        Log.d(TAG, "StreamingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreamingForeground(
                url = intent.getStringExtra(EXTRA_URL),
                includeAudio = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false)
            )
            ACTION_PAUSE -> pauseStreamingForeground(intent.getStringExtra(EXTRA_URL))
        }
        return START_STICKY
    }

    private fun startStreamingForeground(url: String?, includeAudio: Boolean) {
        val message = ForegroundNotifications.streamingMessage(url, includeAudio)
        showForeground(message, includeAudio)
        Log.d(TAG, "Streaming foreground service started")
    }

    private fun pauseStreamingForeground(url: String?) {
        val message = if (!url.isNullOrEmpty()) "Paused - $url" else "Streaming paused"
        showForeground(message, includeAudio = false)
        Log.d(TAG, "Streaming foreground service paused")
    }

    private fun showForeground(message: String, includeAudio: Boolean) {
        val notification = ForegroundNotifications.build(
            this, CHANNEL_ID, "LensCast Streaming", message, ongoing = true
        )
        ForegroundNotifications.startCameraForeground(
            this, ForegroundNotifications.STREAMING_NOTIFICATION_ID, notification, includeAudio
        )
    }

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_STREAMING"
        const val ACTION_PAUSE = "com.raulshma.lenscast.PAUSE_STREAMING"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_AUDIO_ACTIVE = "stream_audio_active"
        private const val CHANNEL_ID = "streaming_channel"
        private const val TAG = "StreamingService"
    }
}
