package com.raulshma.lenscast.capture

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.ForegroundNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local detection alerts: a heads-up notification per detection event, the
 * trigger-time snapshot as the big picture, tapping through into the app.
 * Entirely on-device — no cloud push anywhere in the chain. The coordinator
 * claims the "notify" action only when [notify] returns true, so the event
 * feed lists the notification just when one really went out.
 */
class DetectionNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /**
     * Posts one alert. [kind] is the coordinator's event vocabulary
     * (motion / sound / tamper); it picks the notification id, so a burst of
     * the same kind replaces instead of stacking. Returns false when the
     * platform blocks the post (no runtime permission on API 33+).
     */
    fun notify(kind: EventKind, zones: List<String>, snapshotJpegBase64: String?): Boolean {
        if (!willPost()) return false
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zoneSuffix = if (zones.isEmpty()) "" else " · ${zones.joinToString(", ")}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(
                "${kind.wireName.replaceFirstChar { it.uppercase(Locale.getDefault()) }} detected$zoneSuffix",
            )
            .setContentText(
                "LensCast camera flagged an event at " +
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
        snapshotJpegBase64?.let { base64 ->
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let { bitmap ->
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as android.graphics.Bitmap?),
                )
                builder.setLargeIcon(bitmap)
            }
        }
        notificationManager.notify(notificationId(kind), builder.build())
        return true
    }

    /** Whether a post would actually land: the runtime notification permission on 33+. */
    private fun willPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(kind: EventKind): Int = when (kind) {
        EventKind.MOTION -> ForegroundNotifications.DETECTION_MOTION_NOTIFICATION_ID
        EventKind.SOUND -> ForegroundNotifications.DETECTION_SOUND_NOTIFICATION_ID
        EventKind.TAMPER -> ForegroundNotifications.DETECTION_TAMPER_NOTIFICATION_ID
    }

    private fun createChannel() {
        ForegroundNotifications.createChannel(
            context,
            CHANNEL_ID,
            "Detection Alerts",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Motion, sound, and tamper events from the camera",
        )
    }

    companion object {
        private const val CHANNEL_ID = "detection_alerts"
    }
}
