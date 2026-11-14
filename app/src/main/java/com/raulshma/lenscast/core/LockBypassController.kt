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
 * This controller owns the Android reads (keyguard truth, POST_NOTIFICATIONS,
 * the full-screen-intent permission), the attempt bookkeeping, and the
 * posting; the decision ladder itself lives in [LockBypassPolicy] and is
 * JVM-tested there. Two triggers call [requestWake]: the denied-promotion
 * fallback in [StreamingService] and the session's denied bind under lock
 * ([com.raulshma.lenscast.streaming.StreamingSession]) — both are exact
 * locked-mount paths where the app needs to become visible.
 */
class LockBypassController(
    private val context: Context,
    private val keyguardMonitor: KeyguardMonitor,
) {

    /** The last wake's [SystemClock.elapsedRealtime]; negative = no attempt yet. */
    private var lastAttemptAtMs = -1L

    /**
     * Called when a locked-mount camera need was denied — the streaming
     * foreground promotion or the camera bind itself. Posts the wake
     * notification when [LockBypassPolicy] warrants it; a silent no-op
     * otherwise (unlocked — the resume seam owns that case; no FSI
     * permission — the blocked-by-lock status flag owns the truth instead;
     * throttled). The keyguard truth is re-read first: a process alive since
     * before the screen went off may hold a stale flag if a broadcast was
     * missed, and the wake's whole premise is the lock.
     */
    fun requestWake() {
        keyguardMonitor.refresh()
        val verdict = LockBypassPolicy.resolve(
            isKeyguardLocked = keyguardMonitor.isKeyguardLocked.value,
            postNotificationsGranted = postNotificationsGranted(),
            canUseFullScreenIntent = NotificationManagerCompat.from(context).canUseFullScreenIntent(),
            nowMs = SystemClock.elapsedRealtime(),
            lastAttemptAtMs = lastAttemptAtMs,
        )
        if (verdict != LockBypassPolicy.Verdict.POST) {
            Log.d(TAG, "Lock bypass skipped: $verdict")
            when (verdict) {
                LockBypassPolicy.Verdict.SKIP_NO_POST_NOTIFICATIONS ->
                    Log.w(TAG, "Lock bypass unavailable: POST_NOTIFICATIONS not granted")
                LockBypassPolicy.Verdict.SKIP_NO_FULL_SCREEN_INTENT ->
                    // Android 14+ Play-installed apps can lose the full-screen
                    // intent permission; without it the notification would only
                    // show as a heads-up banner, so don't wake-pretend — the
                    // dashboard's blocked-by-lock state is the honest surface.
                    Log.w(TAG, "Lock bypass unavailable: full-screen intents not permitted")
                else -> Unit
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Re-check under the lock: the verdict read the throttle outside any
        // synchronization, and a denied promotion and a denied bind can race
        // here from different dispatchers. Same floor, atomic advance; the
        // unset sentinel (-1) always passes — the first wake is never held.
        synchronized(this) {
            if (lastAttemptAtMs >= 0 && now - lastAttemptAtMs < LockBypassPolicy.MIN_ATTEMPT_INTERVAL_MS) {
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

    /** POST_NOTIFICATIONS is a runtime permission only from API 33; before it, posting needs no grant. */
    private fun postNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
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
    }
}
