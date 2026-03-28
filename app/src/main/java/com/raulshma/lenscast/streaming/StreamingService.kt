package com.raulshma.lenscast.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.raulshma.lenscast.MainActivity

class StreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "StreamingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreamingForeground(intent.getStringExtra(EXTRA_URL))
            ACTION_PAUSE -> pauseStreamingForeground(intent.getStringExtra(EXTRA_URL))
            ACTION_STOP -> stopStreamingForeground()
        }
        return START_STICKY
    }

    private fun startStreamingForeground(url: String?) {
        val message = if (!url.isNullOrEmpty()) "Streaming to $url" else "Streaming camera feed"
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Streaming foreground service started")
    }

    private fun stopStreamingForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Streaming foreground service stopped")
    }

    private fun pauseStreamingForeground(url: String?) {
        val message = if (!url.isNullOrEmpty()) "Paused - $url" else "Streaming paused"
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Streaming foreground service paused")
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LensCast Streaming")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LensCast Streaming")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_STREAMING"
        const val ACTION_PAUSE = "com.raulshma.lenscast.PAUSE_STREAMING"
        const val ACTION_STOP = "com.raulshma.lenscast.STOP_STREAMING"
        const val EXTRA_URL = "stream_url"
        private const val CHANNEL_ID = "streaming_channel"
        private const val NOTIFICATION_ID = 1002
        private const val TAG = "StreamingService"
    }
}
