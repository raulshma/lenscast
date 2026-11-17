package com.raulshma.lenscast.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.core.HeartbeatController

/**
 * The alarm heartbeat's delivery trampoline (blueprint P0 item 3,
 * self-healing §3): the manifest-registered target of
 * [HeartbeatController]'s allow-while-idle alarm. Two jobs, in a deliberate
 * order:
 *
 * 1. **Re-arm the next occurrence FIRST** — a one-shot allow-while-idle
 *    alarm has no repeating form, and the cadence must not depend on
 *    anything that happens after this point (a wedged evaluation, a
 *    rejected FGS start, a dying process). The same schedule-before-quit
 *    ordering [AppRelaunch] documents.
 * 2. **Start the foreground service** with
 *    [StreamingService.ACTION_HEARTBEAT] — the start is what recreates a
 *    dead process (an exact alarm carries the documented FGS-start
 *    exemption; restart-reliability §2), and the evaluation runs inside the
 *    service where the graph lives (the receiver itself holds no
 *    collaborators — the BootCompletedReceiver split: transport here,
 *    decisions downstream).
 *
 * **The blueprint deviation, owned.** Blueprint item 3's literal sketch is
 * a `PendingIntent.getForegroundService` aimed at the service; this
 * receiver-first form is the DELIBERATE substitution: a getBroadcast alarm
 * can land here and book the next occurrence BEFORE any FGS start is
 * attempted, so a rejected start (the ungranted-into-dead-process case
 * below) costs exactly one skipped check — never the cadence, which a
 * getForegroundService alarm (whose "re-arm" would only run after the start
 * had succeeded) could not promise. Self-healing §3 sanctions the pair
 * verbatim ("a heartbeat must use `PendingIntent.getForegroundService`/
 * `getBroadcast`"); this is the getBroadcast leg, chosen for that ordering.
 * The class's own degradation story is why the ordering matters.
 *
 * The honest degradation (why the start is caught): without the exact-alarm
 * grant or the battery allowlist, an allow-while-idle alarm still DELIVERS
 * (no permission needed) but carries no foreground-service-start exemption
 * — into a dead process the start can throw
 * `ForegroundServiceStartNotAllowedException`. That failure costs exactly
 * one skipped check: the alarm is already re-armed, and while the process
 * is alive (the wedge-watch case — the common one) the running FGS keeps
 * the app foreground, so the start lands normally. Force-stop is not a
 * degradation case at all: stopped-state apps get no broadcasts, by design
 * (self-healing §2).
 *
 * A sticky restart or the relaunch alarm never passes through here — their
 * PendingIntents target the service directly (ACTION_RELAUNCH); only the
 * heartbeat's own alarm carries this action.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StreamingService.ACTION_HEARTBEAT) return
        val app = context.applicationContext as? AppGraph
        // 1. Cadence first: the next occurrence is booked before anything
        // that can fail below.
        try {
            app?.heartbeatController?.arm()
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat re-arm failed: ${e.javaClass.simpleName}")
        }
        // 2. The FGS start — recreates the process if it was dead; the
        // service's ACTION_HEARTBEAT path promotes the server foreground
        // and hands the verdict to the controller.
        try {
            val serviceIntent = Intent(context, StreamingService::class.java)
                .setAction(StreamingService.ACTION_HEARTBEAT)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // The ungranted-into-dead-process case documented above: one
            // skipped check, sticky restart remains the fallback tier.
            Log.w(TAG, "Heartbeat service start rejected: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "HeartbeatReceiver"
    }
}
