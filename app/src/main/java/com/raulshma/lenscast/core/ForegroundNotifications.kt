package com.raulshma.lenscast.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Shared scaffolding for the camera-related foreground services (streaming,
 * recording). Both services previously carried identical copies of this.
 */
object ForegroundNotifications {

    fun createChannel(context: Context, channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun build(
        context: Context,
        channelId: String,
        title: String,
        message: String,
        ongoing: Boolean = false,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.raulshma.lenscast.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Promote to foreground with the CAMERA type, plus MICROPHONE when the
     * service captures audio (API 30+; typed startForeground needs API 29+).
     */
    fun startCameraForeground(
        service: Service,
        notificationId: Int,
        notification: Notification,
        includeAudio: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (includeAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            service.startForeground(notificationId, notification, serviceTypes)
        } else {
            service.startForeground(notificationId, notification)
        }
    }
}
