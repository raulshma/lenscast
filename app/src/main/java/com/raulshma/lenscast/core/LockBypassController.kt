package com.raulshma.lenscast.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.streaming.LockBypassActivity
import com.raulshma.lenscast.streaming.StreamingService

/**
 * The locked-mount camera wake. Android's while-in-use camera policy denies
 * a camera open (and the CAMERA-typed foreground promotion) to an app that
 * is not visible — exactly the state of a locked device receiving a remote
 * stream start from the dashboard. The sanctioned way to become visible
 * without the user unlocking is the full-screen intent: a high-importance
 * notification whose [NotificationCompat.Builder.setFullScreenIntent]
 * launches [LockBypassActivity] over the keyguard, where the promotion and
 * the camera bind land legitimately (the same visibility contract a
 * stream-started-before-locking already benefits from), after which the
 * activity dismisses itself and the camera stays with the running
 * CAMERA-typed foreground service.
 *
 * This controller owns the posting policy: only when the keyguard is
 * actually locked, only when the full-screen-intent permission is usable
 * (Android 14+ can revoke it — Play installs of non-calling apps), and
 * throttled so a denied-promotion retry loop cannot spam the screen. The
 * denied-promotion fallback in [StreamingService] is the trigger — that is
 * the exact code path a locked-mount start lands on.
 */
class LockBypassController(
    private val context: Context,
    private val keyguardMonitor: KeyguardMonitor,
) {

    private var lastAttemptAtMs = 0L

    /**
     * Called when the streaming foreground promotion was denied. Posts the
     * wake notification when the state warrants it; a silent no-op otherwise
     * (unlocked — the resume seam owns that case; no FSI permission — the
     * blocked-by-lock status flag owns the truth instead; throttled).
     */
    fun onStreamingPromotionDenied() {
        if (!keyguardMonitor.isKeyguardLocked.value) {
            // Not a locked-mount denial (a background start on an unlocked
            // device) — the resume seam heals it on the next visit.
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Lock bypass unavailable: POST_NOTIFICATIONS not granted")
            return
        }
        if (!NotificationManagerCompat.from(context).canUseFullScreenIntent()) {
            // Android 14+ Play-installed apps can lose the full-screen intent
            // permission; without it the notification would only show as a
            // heads-up banner, so don't wake-pretend — the dashboard's
            // blocked-by-lock state is the honest surface.
            Log.w(TAG, "Lock bypass unavailable: full-screen intents not permitted")
            return
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastAttemptAtMs < MIN_ATTEMPT_INTERVAL_MS) {
                Log.d(TAG, "Lock bypass throttled (attempted ${now - lastAttemptAtMs}ms ago)")
                return
            }
            lastAttemptAtMs = now
        }
        post()
    }

    /** Removes the wake notification — the activity's dismissal epilogue. */
    fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(ForegroundNotifications.LOCK_BYPASS_NOTIFICATION_ID) }
    }

    private fun post() {
        runCatching {
            ForegroundNotifications.createChannel(
                context,
                ForegroundNotifications.LOCK_BYPASS_CHANNEL_ID,
                "Camera wake",
                importance = android.app.NotificationManager.IMPORTANCE_HIGH,
                description = "Wakes the camera over the lock screen when a stream is started remotely",
            )
            // Full-screen target: the over-keyguard shim. Content target: the
            // main activity — the fallback tap path when the FSI itself is
            // demoted, so the manual tap still lands the resume seam.
            val fullScreenIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, LockBypassActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, ForegroundNotifications.LOCK_BYPASS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(context.getString(com.raulshma.lenscast.R.string.lock_bypass_title))
                .setContentText(context.getString(com.raulshma.lenscast.R.string.lock_bypass_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreenIntent, true)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context)
                .notify(ForegroundNotifications.LOCK_BYPASS_NOTIFICATION_ID, notification)
            Log.d(TAG, "Lock bypass notification posted")
        }.onFailure {
            Log.w(TAG, "Lock bypass notification failed: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "LockBypassController"

        /**
         * The retry floor between wake attempts: a watchdog pass that keeps
         * failing under lock re-lands on the denied promotion repeatedly, and
         * each pass must not re-launch the shim. One wake per episode plus a
         * re-arm window is enough — the shim itself heals within seconds.
         */
        const val MIN_ATTEMPT_INTERVAL_MS = 30_000L
    }
}
