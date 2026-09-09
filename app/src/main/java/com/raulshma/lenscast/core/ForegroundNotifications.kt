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
import androidx.work.ForegroundInfo

/**
 * The one foreground-notification registry: the notification IDs for every
 * foreground producer (streaming, recording, interval capture, update) plus
 * the shared notification scaffolding — channel creation, the standard
 * builder, both camera promotion variants (Service.startForeground and
 * WorkManager's ForegroundInfo), and the pure body-line decisions. The IDs
 * live here so two producers can never claim the same slot (streaming and
 * interval capture previously both used 1002 and replaced each other's
 * notification when they ran concurrently).
 */
object ForegroundNotifications {

    // One distinct notification slot per foreground producer.
    const val RECORDING_NOTIFICATION_ID = 1001
    const val STREAMING_NOTIFICATION_ID = 1002
    const val UPDATE_NOTIFICATION_ID = 1003
    const val INTERVAL_CAPTURE_NOTIFICATION_ID = 1004

    /**
     * Detection-alert notifications live above every foreground slot and are
     * sub-numbered per event type (motion/sound/tamper), so a burst of one
     * kind replaces itself and never collides with a producer slot.
     */
    const val DETECTION_NOTIFICATION_BASE = 2000
    const val DETECTION_MOTION_NOTIFICATION_ID = DETECTION_NOTIFICATION_BASE + 1
    const val DETECTION_SOUND_NOTIFICATION_ID = DETECTION_NOTIFICATION_BASE + 2
    const val DETECTION_TAMPER_NOTIFICATION_ID = DETECTION_NOTIFICATION_BASE + 3

    fun createChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int = NotificationManager.IMPORTANCE_LOW,
        description: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, importance)
            description?.let { channel.description = it }
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
     * The streaming notification's body line: names the stream URL when one
     * was given, falls back to the generic feed line otherwise, and each
     * variant gains the audio clause when the microphone is live.
     */
    fun streamingMessage(url: String?, includeAudio: Boolean): String =
        if (!url.isNullOrEmpty()) {
            if (includeAudio) "Streaming video and audio to $url" else "Streaming to $url"
        } else {
            if (includeAudio) "Streaming camera feed with audio" else "Streaming camera feed"
        }

    /**
     * The interval-capture progress line: which photo of the series is being
     * taken this tick ([completedCaptures] is the count already done, the
     * notification shows the one in flight). Without a known total it stays
     * generic.
     */
    fun intervalCaptureMessage(completedCaptures: Int, totalCaptures: Int): String =
        if (totalCaptures > 0) {
            "Capturing photo ${completedCaptures + 1} of $totalCaptures"
        } else {
            "Capturing interval photo"
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

    /**
     * The WorkManager twin of [startCameraForeground]: a camera-typed
     * [ForegroundInfo] for Worker.setForeground on Q+, plain on older APIs.
     * Workers here never capture audio, so no microphone type.
     */
    fun buildCameraForegroundInfo(
        notificationId: Int,
        notification: Notification,
    ): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
}
