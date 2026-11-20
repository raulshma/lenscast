package com.raulshma.lenscast.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.raulshma.lenscast.streaming.StreamingService

/**
 * The app-restart relaunch: registers the streaming service on AlarmManager so
 * it survives the process death that [com.raulshma.lenscast.MainApplication.quitApplication]
 * deliberately causes. Every in-memory way back (handlers, coroutines, the
 * composition root) dies with the process, so the OS alarm is the only
 * mechanism that can bring the app back; the caller schedules this BEFORE
 * entering the quit ladder.
 *
 * The relaunch targets [StreamingService] (ACTION_RELAUNCH → the server-only
 * recovery the sticky-restart path already takes), NOT the launcher activity:
 * Android 15 hard-blocks background ACTIVITY launches whose sender is a dead
 * process — verified on-device that neither the PendingIntent creator opt-in
 * (ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) nor the
 * SYSTEM_ALERT_WINDOW exemption rescues the launch (BAL result 102,
 * callingUidProcState NONEXISTENT). Foreground-SERVICE starts are a different
 * gate with different exemptions, and the restart acceptance needs the
 * dashboard back, not a visible activity — a headless phone restarted from
 * its dashboard must come back serving without lighting up. Once the service
 * starts, the application's resume seam re-issues the current mode exactly as
 * after a sticky restart or boot.
 *
 * The alarm rides `setExactAndAllowWhileIdle` under SCHEDULE_EXACT_ALARM:
 * the exact-alarm grant is the one foreground-service start exemption that
 * survives the quit ladder's process death (it keys off the alarm type and
 * the uid-level permission, not the dead creator's process state — overlay
 * and the inexact alarm were both verified blocked on-device). Below API 31
 * exact alarms need no permission; on 31–32 the declared permission is
 * granted at install; on 33+ it is a one-time "Alarms & reminders" Settings
 * toggle. Ungranted, the schedule throws before anything quits — the restart
 * endpoint reports the missing grant and the camera keeps running, so a
 * dashboard tap can never darken a phone it cannot bring back. The alarm is
 * a one-shot — a restart that fails still leaves nothing armed.
 *
 * This object also owns the relaunch-pacing constants — its own delay plus
 * the two quit-ladder timings [com.raulshma.lenscast.MainApplication] reads
 * — because they are one ordering, not three numbers: the invariant lives in
 * the [init] check below, enforced in code rather than prose.
 */
object AppRelaunch {

    private const val TAG = "AppRelaunch"

    /**
     * The settle before a restart's quit ladder — past the triggering
     * response's flush, so the dashboard's OK reaches the socket before the
     * teardown begins ([MainApplication.restartApplication] delays on it).
     */
    const val RESTART_RESPONSE_FLUSH_MS = 300L

    /**
     * The teardown window between the stop ladder and the process kill
     * ([MainApplication.quitApplication] delays on it before exitProcess).
     */
    const val QUIT_TEARDOWN_GRACE_MS = 500L

    /**
     * The relaunch delay: comfortably past the quit ladder's teardown grace
     * AND the restart request's response flush — the alarm must never fire
     * while the dying process still holds the socket the dashboard's OK is
     * travelling on. Enforced, not prose: the [init] check fails fast if a
     * constant edit breaks the ordering.
     */
    internal const val RELAUNCH_DELAY_MS = 2_000L

    init {
        check(RELAUNCH_DELAY_MS > RESTART_RESPONSE_FLUSH_MS + QUIT_TEARDOWN_GRACE_MS) {
            "RELAUNCH_DELAY_MS (${RELAUNCH_DELAY_MS}ms) must exceed RESTART_RESPONSE_FLUSH_MS + " +
                "QUIT_TEARDOWN_GRACE_MS (${RESTART_RESPONSE_FLUSH_MS + QUIT_TEARDOWN_GRACE_MS}ms): " +
                "the relaunch alarm must not fire while the dying process still holds " +
                "the socket the dashboard's OK response is travelling on"
        }
    }

    /**
     * Register the relaunch. Idempotent per scheduling — a re-schedule
     * replaces the prior intent. [delayMs] defaults to [RELAUNCH_DELAY_MS]
     * (the dashboard-restart pacing); the crash handler passes the crash
     * loop's backoff ([CrashRelaunchPolicy.relaunchDelayMs]) so a forming
     * loop does not spin at the 2-second cadence.
     */
    fun schedule(context: Context, delayMs: Long = RELAUNCH_DELAY_MS) {
        val serviceIntent = Intent(context, StreamingService::class.java)
            .setAction(StreamingService.ACTION_RELAUNCH)
        val pending = PendingIntent.getForegroundService(
            context,
            0,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            Log.e(TAG, "AlarmManager unavailable; restart will not relaunch")
            return
        }
        manager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pending,
        )
        Log.d(TAG, "Relaunch scheduled in ${delayMs}ms (service-first)")
    }
}
