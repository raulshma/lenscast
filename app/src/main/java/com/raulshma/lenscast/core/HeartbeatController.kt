package com.raulshma.lenscast.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.raulshma.lenscast.streaming.HeartbeatReceiver
import com.raulshma.lenscast.streaming.ServerForegroundController
import com.raulshma.lenscast.streaming.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the heartbeat publishes for the telemetry surface (blueprint item 8,
 * wave 3 wires it into `GET /api/system`): whether the alarm is armed, the
 * consecutive-miss bookkeeping, and the last verdict the policy returned.
 */
data class HeartbeatState(
    val armed: Boolean = false,
    val consecutiveMisses: Int = 0,
    val lastAction: WatchdogAlarmPolicy.Action? = null,
    val lastTier: WatchdogAlarmPolicy.EscalationTier? = null,
)

/**
 * The alarm heartbeat: the out-of-process liveness tier (blueprint P0 item
 * 3, self-healing §3). The in-process watchdog dies with the process and
 * wedges with it; this is the periodic external poke that observes both. It
 * is a runtime shell in the [AppRelaunch] shape — every decision delegates
 * to [WatchdogAlarmPolicy], and the health verdict itself is Wave 1's seam
 * reused, not re-implemented: [healthCheck] is wired to
 * [StreamWatchdog.evaluateOnce] (the same snapshot the monitor loop builds,
 * loopback probe included — and the same read supplies the snapshot's
 * server-only mode, so the verdict and the mode the ladder shift keys on
 * cannot disagree).
 *
 * **Alarm mechanics.** The PendingIntent targets a manifest-registered
 * broadcast receiver ([com.raulshma.lenscast.streaming.HeartbeatReceiver])
 * — never an `AlarmManager.OnAlarmListener`, whose alarms the system
 * cancels whenever no components of the app are running (self-healing §3).
 *
 * **The deliberate deviation from blueprint item 3's literal mechanism.**
 * The sketch says `PendingIntent.getForegroundService`; the landed delivery
 * is `getBroadcast` → HeartbeatReceiver → `startForegroundService`, on
 * purpose and owned here: the receiver re-arms the NEXT occurrence FIRST,
 * so the cadence never depends on anything that happens after it — a
 * wedged evaluation, or (the decisive case) an FGS start REJECTED for lack
 * of the exact-alarm exemption would otherwise cost the whole schedule,
 * not one skipped check. The one-shot allow-while-idle alarm has no
 * repeating form, so a lost arm is a lost heartbeat forever. Self-healing
 * §3 sanctions exactly this pair ("a heartbeat must use
 * `PendingIntent.getForegroundService`/`getBroadcast`") — the receiver leg
 * is that second form, chosen for its schedule-before-quit ordering (the
 * same ordering [AppRelaunch] documents); the service start it chains
 * still recreates a dead process whenever the exemption is held, which is
 * item 3's goal, not its literal.
 *
 * The interval is [INTERVAL_MS] — 15 minutes, on purpose: it is the doze
 * throttle's own floor ("when in low-power idle modes this duration may be
 * significantly longer, such as 15 minutes" — the allow-while-idle javadoc),
 * the largest interval that still PROMISES doze delivery, and at 4/hour it
 * sits comfortably under the ~7/hour allow-while-idle dispatch budget doze
 * grants a background app (limits doc §4.1). Every alarm is one-shot and
 * re-armed at each fire — allow-while-idle has no repeating form.
 *
 * **Exactness, re-evaluated per arm** (the grant can appear and disappear):
 * `setExactAndAllowWhileIdle` only when the exact grant is actually held —
 * `AlarmManager.canScheduleExactAlarms()` on API 31+ (the manifest-declared
 * `SCHEDULE_EXACT_ALARM`: install-granted on 31-32, a Settings toggle on
 * 33+, also implied by the battery allowlist — restart-reliability §1.3),
 * and unconditionally below 31 where exact alarms need no permission (the
 * [AppRelaunch] precedent). Ungranted, the default is plain
 * `setAndAllowWhileIdle` — no permission, still doze-delivered. The honest
 * limit: an inexact alarm carries NO foreground-service-start exemption
 * (restart-reliability §2), so on an ungranted, non-allowlisted device a
 * fire into a dead process may deliver yet fail to start the service — the
 * receiver catches that throw and the OS sticky restart (blueprint resolved
 * tension 1) remains the fallback tier. While the process is alive — the
 * wedge-watch case — the running FGS keeps the app foreground and the same
 * inexact fire starts the service normally.
 *
 * **Arming seam.** The server lifecycle, not the session (wave 1's seam):
 * [start] collects the same `StreamingManager.isServerRunning` flow
 * [ServerWatchdogArming] bridges, arms while the server is up, and
 * disarms on stop — through the one shared grace-windowed bridge
 * ([GraceWindowedArming], fire-time re-check included), because the
 * restart-bounce race handling must be written once, not once per leg.
 * There is deliberately no session-scope arming: one alarm, one owner
 * (the server is the process's durable mode — the state every
 * death-and-relaunch tier lands in).
 */
class HeartbeatController(
    private val context: Context,
    private val isServerRunning: StateFlow<Boolean>,
    /**
     * One health read: the verdict AND the snapshot's server-only mode, in
     * the same value so they cannot disagree — wired to
     * [StreamWatchdog.evaluateOnce] in production (the mode is the
     * decision's ladder-shift input; a verdict alone would escalate
     * server-only misses into the session-only SOFT tier, a camera rebind
     * with no session to rebind, and waste the interval).
     */
    private val healthCheck: suspend () -> HealthRead,
    /** The in-process recovery lever — wired to `StreamingSession.recover` in production. */
    private val recover: suspend (WatchdogPolicy.RecoveryTier) -> Unit,
    /**
     * The soft-restart ladder (wave 4's SOFT_RESTART tier, blueprint P1 item
     * 9) — the SAME [SoftRestartLadder] instance the dashboard's
     * `?mode=soft` route runs, one implementation with two entry points.
     */
    private val softRestart: suspend () -> Unit = {},
    /** The death-and-relaunch lever — wired to `AppGraph.restartApplication` in production. */
    private val relaunch: () -> Unit,
    /** The user-stopped intent flag — wired to `StreamStateJournal.load().userStopped` in production. */
    private val userStopped: () -> Boolean,
    private val maxMisses: Int = WatchdogAlarmPolicy.DEFAULT_MAX_MISSES,
    /** The down-leg grace; the same window [ServerForegroundController] measured restarts inside. */
    private val stopGraceMs: Long = ServerForegroundController.SERVER_STOP_GRACE_MS,
) {

    /** One health read's answer: the verdict plus the mode it was taken in. */
    data class HealthRead(val healthy: Boolean, val serverOnlyMode: Boolean)

    // Set by [start]; a fired-alarm evaluation landing before wiring has
    // nothing to run on — the alarm cannot be armed before start anyway.
    private var scope: CoroutineScope? = null

    private val misses = AtomicInteger(0)

    private val _state = MutableStateFlow(HeartbeatState())
    /** Telemetry for wave 3 (`GET /api/system`): armed, misses, last verdict. */
    val state: StateFlow<HeartbeatState> = _state.asStateFlow()

    /**
     * The shared grace-windowed arm/disarm bridge (the ServerWatchdogArming
     * leg runs the same class): up leg → [arm], a stop that survives the
     * grace AND the fire-time re-check → [disarm]. Its scope is wired by
     * [start]; a stop landing before that disarms immediately.
     */
    private val armingBridge = GraceWindowedArming(
        isServerRunning = isServerRunning,
        stopGraceMs = stopGraceMs,
        arm = ::arm,
        disarm = ::disarm,
    )

    /** One-shot wiring for the application scope (the [ServerWatchdogArming] shape). */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        armingBridge.start(scope)
    }

    /**
     * Schedule the next one-shot heartbeat. Idempotent — a re-arm replaces
     * the prior alarm (`FLAG_UPDATE_CURRENT`). Called by the bridge on
     * server-up and by the receiver at every fire (before the FGS start, so
     * a rejected start still costs nothing but one skipped check).
     */
    fun arm() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            Log.e(TAG, "AlarmManager unavailable; the heartbeat will not fire")
            return
        }
        val pending = heartbeatPendingIntent()
        val triggerAtMs = SystemClock.elapsedRealtime() + INTERVAL_MS
        // Exactness per arm: below 31 exact needs no permission; on 31+ it
        // needs the grant, which the battery allowlist can also provide —
        // re-read every time, the grant moves.
        val exactGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exactGranted) {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pending)
        }
        _state.update { it.copy(armed = true) }
        Log.d(TAG, "Heartbeat armed in ${INTERVAL_MS}ms (exact=$exactGranted)")
    }

    /** Cancel the pending heartbeat (the server stopped past the grace). */
    private fun disarm() {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        manager?.cancel(heartbeatPendingIntent())
        _state.update { it.copy(armed = false) }
        misses.set(0)
        Log.d(TAG, "Heartbeat disarmed")
    }

    /**
     * One fired alarm, launched on the controller scope by the
     * [com.raulshma.lenscast.streaming.HeartbeatReceiver]→service path: the
     * service's `ACTION_HEARTBEAT` start calls this after its server-only
     * promotion. Guards on the server flow (a stale fire while disarmed
     * must not evaluate or escalate), evaluates the health snapshot through
     * the seam, and executes [WatchdogAlarmPolicy]'s verdict.
     */
    fun onHeartbeatFired() {
        val target = scope
        if (target == null) {
            Log.w(TAG, "Heartbeat fired before start(); skipping evaluation")
            return
        }
        target.launch { runHeartbeat() }
    }

    private suspend fun runHeartbeat() {
        if (!isServerRunning.value) {
            // Stale fire (a stop raced the delivery, or the disarmed path):
            // the flow owns arming, so there is nothing to watch yet.
            Log.d(TAG, "Heartbeat fired with the server down; skipping")
            return
        }
        val read = try {
            healthCheck()
        } catch (e: Exception) {
            // A health seam that throws is itself an unanswered process.
            // The mode is unknown — the session-bearing default keeps the
            // pre-existing ladder for the one fire where the read failed.
            Log.w(TAG, "Heartbeat health check threw: ${e.javaClass.simpleName}")
            HealthRead(healthy = false, serverOnlyMode = false)
        }
        val miss = if (read.healthy) 0 else misses.incrementAndGet()
        val decision = WatchdogAlarmPolicy.decide(
            healthy = read.healthy,
            consecutiveMisses = miss,
            maxMisses = maxMisses,
            userStopped = try {
                userStopped()
            } catch (_: Exception) {
                false
            },
            serverOnlyMode = read.serverOnlyMode,
        )
        _state.update {
            it.copy(
                armed = true,
                consecutiveMisses = if (decision.action == WatchdogAlarmPolicy.Action.RESET) 0 else miss,
                lastAction = decision.action,
                lastTier = decision.tier,
            )
        }
        Log.d(TAG, "Heartbeat verdict: miss=$miss action=${decision.action} tier=${decision.tier}")
        when (decision.action) {
            WatchdogAlarmPolicy.Action.RESET -> misses.set(0)
            WatchdogAlarmPolicy.Action.STAND_DOWN -> Unit
            WatchdogAlarmPolicy.Action.ESCALATE -> when (decision.tier) {
                // The two session-recovery tiers ride the same
                // StreamingSession.recover lever — one parameterized path;
                // only the mapped tier differs (and in server-only mode the
                // policy never hands out SOFT — there is no session to
                // rebind, so MEDIUM's server restart is the first tier).
                WatchdogAlarmPolicy.EscalationTier.SOFT,
                WatchdogAlarmPolicy.EscalationTier.MEDIUM,
                -> runCatching {
                    recover(recoveryTierFor(decision.tier))
                }.onFailure { Log.w(TAG, "Session recovery threw: ${it.javaClass.simpleName}") }

                // Wave 4's deepest in-process tier (blueprint P1 item 9): the
                // full soft-restart ladder under the surviving FGS — the only
                // automated tier left that preserves the while-in-use camera
                // standing before the ladder gives the process up.
                WatchdogAlarmPolicy.EscalationTier.SOFT_RESTART -> runCatching {
                    softRestart()
                }.onFailure { Log.w(TAG, "Soft restart threw: ${it.javaClass.simpleName}") }

                // The top tier — the death-and-relaunch (T4/T5). The quit
                // ladder plus the exact-alarm relaunch is what makes it
                // deterministic; a bare schedule could not end a wedged
                // process.
                WatchdogAlarmPolicy.EscalationTier.RELAUNCH,
                null,
                -> runCatching {
                    relaunch()
                }.onFailure { Log.w(TAG, "Relaunch threw: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** The session-recovery mapping for the ladder's two session tiers (the heartbeat's `recover` seam tier). */
    private fun recoveryTierFor(tier: WatchdogAlarmPolicy.EscalationTier): WatchdogPolicy.RecoveryTier =
        when (tier) {
            WatchdogAlarmPolicy.EscalationTier.SOFT -> WatchdogPolicy.RecoveryTier.SOFT
            WatchdogAlarmPolicy.EscalationTier.MEDIUM -> WatchdogPolicy.RecoveryTier.MEDIUM
            else -> throw IllegalArgumentException("$tier has no session-recovery mapping")
        }

    private fun heartbeatPendingIntent(): PendingIntent {
        // Explicit component + action, the AppRelaunch shape: only this
        // app's alarm delivers here (the receiver is exported=false), and
        // the action is what the service switches on. getBroadcast, not
        // getForegroundService — the receiver-first re-arm ordering the
        // class KDoc owns (blueprint item 3's deviation).
        val intent = Intent(context, HeartbeatReceiver::class.java)
            .setAction(StreamingService.ACTION_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "HeartbeatController"

        private const val REQUEST_CODE = 1

        /**
         * 15 minutes — the doze floor: the allow-while-idle javadoc's own
         * "significantly longer, such as 15 minutes" low-power dispatch
         * spacing is the largest interval that still promises doze
         * delivery, and 4/hour fits the ~7/hour while-idle budget doze
         * grants (self-healing §3; limits doc §4.1).
         */
        const val INTERVAL_MS = 15 * 60_000L
    }
}
