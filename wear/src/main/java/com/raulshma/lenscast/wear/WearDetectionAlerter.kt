package com.raulshma.lenscast.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The side-effect half of the detection alerts, the seam the pure controller
 * calls when [WearAlertPolicy] surfaces a new event: one haptic double-pulse
 * plus a heads-up notification that opens the remote. The phone's own
 * DetectionNotifier is the pattern — permission-gated on API 33+, one
 * notification slot that the newest event replaces instead of stacking a
 * burst, entirely on-device.
 *
 * The controller stays Android-free by depending on [DetectionAlerter] only;
 * MainActivity injects this implementation.
 */
fun interface DetectionAlerter {
    /** Called on the main thread once per new event (the newest of a burst). */
    fun onNewEvent(event: WearDetectionEvent)
}

/** The event type → title resource; unknown wire names degrade to generic. */
internal fun eventTitleRes(type: String): Int = when (type) {
    "motion" -> R.string.alert_title_motion
    "sound" -> R.string.alert_title_sound
    "tamper" -> R.string.alert_title_tamper
    else -> R.string.alert_title_generic
}

class WearDetectionAlerter(private val context: Context) : DetectionAlerter {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun onNewEvent(event: WearDetectionEvent) {
        vibrate()
        postNotification(event)
    }

    /** A short double pulse — unmistakable on the wrist without being a siren. */
    private fun vibrate() {
        val vibrator = vibratorOrNull() ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
    }

    /** Vibrator via VibratorManager on API 31+, the legacy service below. */
    private fun vibratorOrNull(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Posts the alert — silently a no-op when the runtime permission is missing. */
    private fun postNotification(event: WearDetectionEvent) {
        if (!willPost()) return
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = WearAlertPolicy.detailLine(event)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(context.getString(eventTitleRes(event.type)))
            .setContentText(detail.ifBlank { context.getString(R.string.alert_tap_open) })
            .setCategory(Notification.CATEGORY_ALARM)
            .setWhen(event.timestampMs)
            .setShowWhen(true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /** Whether a post would actually land: the runtime notification permission on 33+. */
    private fun willPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.alert_channel_description)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "detection_alerts"

        /** One slot: the newest event replaces whatever it held (no burst stack). */
        const val NOTIFICATION_ID = 2001
        private val VIBRATION_PATTERN = longArrayOf(0, 150, 120, 150)
    }
}
