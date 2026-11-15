package com.raulshma.lenscast.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * The app-restart relaunch: registers the launcher intent on AlarmManager so
 * it survives the process death that [com.raulshma.lenscast.MainApplication.quitApplication]
 * deliberately causes. Every in-memory way back (handlers, coroutines, the
 * composition root) dies with the process, so the OS alarm is the only
 * mechanism that can bring the app back; the caller schedules this BEFORE
 * entering the quit ladder. The launcher intent comes from PackageManager —
 * the same ACTION_MAIN entry the home screen uses, so the restart lands on
 * the real front door, not a synthetic launch.
 *
 * The alarm rides `ELAPSED_REALTIME_WAKEUP` + `setAndAllowWhileIdle`: it
 * wakes the CPU (not the screen — a headless phone restarted from its
 * dashboard must come back serving without lighting up) and needs no exact-
 * alarm permission; while the device idles the platform's per-app throttle
 * may defer the fire by a few minutes, which a restart tolerates.
 */
object AppRelaunch {

    private const val TAG = "AppRelaunch"

    /**
     * The relaunch delay: comfortably past the quit ladder's teardown grace
     * AND the restart request's response flush — the alarm must never fire
     * while the dying process still holds the socket the dashboard's OK is
     * travelling on.
     */
    internal const val RELAUNCH_DELAY_MS = 2_000L

    /** Register the relaunch. Idempotent per scheduling — a re-schedule replaces the prior intent. */
    fun schedule(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent == null) {
            Log.e(TAG, "No launch intent for ${context.packageName}; restart will not relaunch")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            Log.e(TAG, "AlarmManager unavailable; restart will not relaunch")
            return
        }
        manager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS,
            pending,
        )
        Log.d(TAG, "Relaunch scheduled in ${RELAUNCH_DELAY_MS}ms")
    }
}
