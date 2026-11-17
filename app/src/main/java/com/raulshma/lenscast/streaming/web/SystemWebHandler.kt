package com.raulshma.lenscast.streaming.web

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.raulshma.lenscast.camera.model.CameraErrorPolicy
import com.raulshma.lenscast.camera.model.CameraResetResult
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.CrashRelaunchPolicy
import com.raulshma.lenscast.core.CrashRelaunchPolicy.Verdict
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.SoftRestartLadder
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.streaming.model.BatteryDetailDto
import com.raulshma.lenscast.streaming.model.CameraResetResponse
import com.raulshma.lenscast.streaming.model.CrashRelaunchTelemetryDto
import com.raulshma.lenscast.streaming.model.HeartbeatTelemetryDto
import com.raulshma.lenscast.streaming.model.StorageInfoDto
import com.raulshma.lenscast.streaming.model.SystemInfoResponseDto
import com.raulshma.lenscast.streaming.model.SystemRestartResponse

/**
 * /api/system — the read-only diagnostics snapshot for a headless phone:
 * build/device identity, uptimes, and the battery/storage facts the status
 * endpoint deliberately leaves coarse. Every read degrades to a safe value —
 * a missing sticky battery intent or an unavailable volume stat must never
 * 500 the triage page. Beyond the hardware facts it also carries the
 * reliability grants 24/7 operation depends on (blueprint P0-B item 7 — the
 * tinyCam get_status posture: every state the overnight run relies on is
 * dashboard-visible, prior-art §1.3; wave 4 completes the item with the
 * crash-relaunch loop guard's and the heartbeat's live states). The writes
 * are POST /api/system/
 * restart (issue #18's dashboard App-Restart button): the restart callback
 * schedules the AlarmManager relaunch and quits past a settle, so the OK
 * below still flushes to the dashboard before the teardown begins — and the
 * typed pre-check (item 8) refuses BEFORE the seam when the relaunch grant
 * is missing, so a dashboard tap can never darken a phone it cannot bring
 * back; `?mode=soft` (wave 4, item 9) runs the in-process soft-restart
 * ladder instead, and POST /api/system/reset-camera (wave 4, item 10) is
 * the bounded camera-only recovery that never stops the server.
 */
class SystemWebHandler(
    private val context: Context,
    private val powerManager: PowerManager,
    private val captureHistoryStore: CaptureHistoryStore,
    /** The app's restart seam (AppGraph.restartApplication); invoked after the response is built. */
    private val restartApplication: () -> Unit = {},
    /** The bound camera's torch strength ceiling (CameraService.torchStrengthMaxLevel); null when unknown. */
    private val torchStrengthMaxLevel: () -> Int? = { null },
    /**
     * AlarmManager's exact-alarm answer (canScheduleExactAlarms): true with
     * SCHEDULE_EXACT_ALARM granted OR the app on the power-save exemption
     * list — the battery allowlist folds into the platform check
     * (restart-reliability §1.3, the AlarmManager javadoc verbatim). Null
     * below API 31, where exact alarms need no permission and there is no
     * grant model to report. This is the same condition AppRelaunch's
     * setExactAndAllowWhileIdle enforces, surfaced as a seam so the pre-check
     * and the snapshot read one truth.
     */
    private val canScheduleExactAlarms: () -> Boolean? = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms()
            }.getOrNull()
        } else {
            null
        }
    },
    /**
     * The in-process soft-restart ladder (wave 4, blueprint P1 item 9) — the
     * SAME [SoftRestartLadder] instance the heartbeat's SOFT_RESTART tier
     * runs: one implementation with two entry points. Suspend because the
     * ladder awaits its stops and the server re-arm. The default is the
     * HONEST ABSENCE (the `{ null }` telemetry precedent, in the ladder's
     * failure shape): a graph that forgot to wire the seam must answer a
     * typed failure — never the success it did not earn, which a mis-wired
     * production graph would have reported forever.
     */
    private val softRestart: suspend () -> SoftRestartLadder.Result = {
        SoftRestartLadder.Result.StepFailed(
            step = SOFT_RESTART_STEP_NOT_WIRED,
            detail = "soft restart seam not wired",
        )
    },
    /**
     * The bounded manual camera-rebind lever (wave 4, blueprint P1 item 10)
     * — wired to `CameraService.resetCamera` in production: the same
     * [com.raulshma.lenscast.camera.model.CameraErrorPolicy] budget the
     * error observer spends, never a bypass. The default is the HONEST
     * ABSENCE: `CAMERAX_HANDLES` is the verdict vocabulary's own
     * "no app-side lever" answer and `bound = null` is [CameraResetResult]'s
     * documented "no rebind was attempted" — a mis-wired graph reports
     * failure, not a rebind success nothing performed.
     */
    private val resetCameraLever: suspend () -> CameraResetResult = {
        CameraResetResult(CameraErrorPolicy.Verdict.CAMERAX_HANDLES, bound = null)
    },
    /**
     * The crash-relaunch loop guard's live telemetry (blueprint item 7's
     * deferred half, wave 4): wired to the crash marker journal + policy in
     * production; null keeps the field off the wire (the torchStrengthMaxLevel
     * precedent for not-yet-wired facts).
     */
    private val crashRelaunchTelemetry: () -> CrashRelaunchTelemetryDto? = { null },
    /**
     * The alarm heartbeat's live telemetry (blueprint item 7's deferred half,
     * wave 4): wired to HeartbeatController.state in production; null keeps
     * the field off the wire.
     */
    private val heartbeatTelemetry: () -> HeartbeatTelemetryDto? = { null },
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(SystemInfoResponseDto::class.java) }
    private val restartAdapter by lazy { AppJson.moshi.adapter(SystemRestartResponse::class.java) }
    private val resetCameraAdapter by lazy { AppJson.moshi.adapter(CameraResetResponse::class.java) }

    fun get(): String {
        val response = SystemInfoResponseDto(
            appVersion = appVersion(),
            deviceModel = Build.MODEL ?: "",
            deviceManufacturer = Build.MANUFACTURER ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            osUptimeMs = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L),
            processUptimeMs = processUptimeMs(),
            battery = batteryDetail(),
            storage = storageInfo(),
            torchStrengthMaxLevel = torchStrengthMaxLevel(),
            canScheduleExactAlarms = canScheduleExactAlarms(),
            isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(),
            isDeviceInDozeMode = powerManager.isDeviceInDozeMode(),
            batteryOptimizationState = batteryOptimizationState(),
            crashRelaunch = crashRelaunchTelemetry(),
            heartbeat = heartbeatTelemetry(),
        )
        return responseAdapter.toJson(response)
    }

    /**
     * POST /api/system/restart — pre-check the relaunch grant, then schedule
     * the relaunch, run the quit teardown, answer OK. Blueprint P0-B item 8
     * (restart-reliability §5 option D, §1.1): ungranted, AppRelaunch's
     * schedule used to throw into the router's catch-all AFTER the seam had
     * already queued the quit — a bare `{"success":false}` on the wire while
     * the phone went dark (the Nokia fresh-install no-op). The typed refusal
     * answers instead: no quit, no alarm, the camera keeps running, and the
     * dashboard gets a machine-readable reason to localize.
     *
     * `?mode=soft` (wave 4, blueprint P1 item 9) runs the in-process
     * soft-restart ladder instead — the quit ladder minus process death, the
     * only automated tier that preserves the foreground-started FGS's
     * while-in-use camera standing (resolved tension 2). The answer is built
     * AFTER the ladder completes, so the dashboard reads the real outcome
     * (success, or the failed step in the typed-refusal shape). The plain
     * route, `?mode=process`, and any unknown mode keep the deep-restart
     * semantics byte-for-byte (the legacy success shape carries no `mode`),
     * so existing clients and the automation token path are unaffected. The
     * soft path needs no exact-alarm pre-check — nothing is scheduled,
     * nothing dies.
     */
    suspend fun restart(mode: String? = null): String {
        if (mode == RESTART_MODE_SOFT) {
            return restartAdapter.toJson(softRestartResponse())
        }
        // The seam is named restartApplication because a member named restart()
        // shadows a same-named `restart` property: the old body's restart()
        // call resolved back into this member and StackOverflowed before the
        // OK could flush or the relaunch could arm.
        if (canScheduleExactAlarms() == false) {
            // Only a definitive false refuses: null means below API 31 (no
            // permission exists — AppRelaunch's exact alarms are unguarded
            // there) or an unreadable AlarmManager, both of which proceed.
            return restartAdapter.toJson(
                SystemRestartResponse(
                    success = false,
                    reason = RESTART_REASON_EXACT_ALARM_NOT_GRANTED,
                    hint = RESTART_HINT_EXACT_ALARM_NOT_GRANTED,
                ),
            )
        }
        restartApplication()
        return restartAdapter.toJson(SystemRestartResponse())
    }

    /**
     * The soft ladder's typed answer: `{"success":true,"mode":"soft"}` when
     * the server re-armed, otherwise the wave-3 refusal shape with the failed
     * ladder step in [SystemRestartResponse.step] — never a silent 200 on a
     * ladder that did not land.
     */
    private suspend fun softRestartResponse(): SystemRestartResponse = when (val result = softRestart()) {
        is SoftRestartLadder.Result.Restarted -> SystemRestartResponse(success = true, mode = RESTART_MODE_SOFT)
        is SoftRestartLadder.Result.StepFailed -> SystemRestartResponse(
            success = false,
            mode = RESTART_MODE_SOFT,
            reason = RESTART_REASON_SOFT_RESTART_FAILED,
            step = result.step,
            hint = SOFT_RESTART_HINT_PREFIX + result.detail,
        )
    }

    /**
     * POST /api/system/reset-camera (wave 4, blueprint P1 item 10) — the
     * targeted camera-subsystem recovery the market was missing (only
     * tobi01001/IP_Cam ships it): one bounded `rebindUseCases` lever, the
     * SAME budget the error observer spends — the HTTP path must not bypass
     * what wave 1 wired. Distinct from the soft restart: the server NEVER
     * stops. The answer is fully typed ([CameraResetResponse]): the verdict
     * that drove it (`REBIND` on success; `REBOOT_REQUIRED` /
     * `WAIT_CAMERA_ADDED` surfaced when the policy says so;
     * `CAMERAX_HANDLES` when the bounded budget is spent), plus a
     * machine-readable reason for every non-rebind answer.
     */
    suspend fun resetCamera(): String {
        val result = resetCameraLever()
        return resetCameraAdapter.toJson(
            when {
                result.verdict == CameraErrorPolicy.Verdict.REBIND && result.bound != false ->
                    CameraResetResponse(success = true, verdict = VERDICT_REBIND)
                result.verdict == CameraErrorPolicy.Verdict.REBIND ->
                    // The lever fired and the bind did not land (the locked
                    // mount's while-in-use denial is the headline): the
                    // attempt was spent, so say so — not a silent success.
                    CameraResetResponse(
                        success = false,
                        verdict = VERDICT_REBIND,
                        reason = RESET_REASON_REBIND_DENIED,
                        hint = RESET_HINT_REBIND_DENIED,
                    )
                result.verdict == CameraErrorPolicy.Verdict.REBOOT_REQUIRED ->
                    CameraResetResponse(
                        success = false,
                        verdict = VERDICT_REBOOT_REQUIRED,
                        reason = RESET_REASON_REBOOT_REQUIRED,
                        hint = RESET_HINT_REBOOT_REQUIRED,
                    )
                result.verdict == CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED ->
                    CameraResetResponse(
                        success = false,
                        verdict = VERDICT_WAIT_CAMERA_ADDED,
                        reason = RESET_REASON_CAMERA_REMOVED,
                        hint = RESET_HINT_CAMERA_REMOVED,
                    )
                else ->
                    // CAMERAX_HANDLES on the manual path means the budget is
                    // spent (decideResetCamera's only route there): report
                    // rather than loop.
                    CameraResetResponse(
                        success = false,
                        verdict = VERDICT_CAMERAX_HANDLES,
                        reason = RESET_REASON_BUDGET_SPENT,
                        hint = RESET_HINT_BUDGET_SPENT,
                    )
            },
        )
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Ms since this process started; 0 below API 24 (no start stamp exists). */
    private fun processUptimeMs(): Long = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // The start stamp lives in the uptime timebase, so the other side
            // of the subtraction must be elapsedRealtime — wall-clock now
            // would read as ~56 years of process uptime.
            (SystemClock.elapsedRealtime() - Process.getStartUptimeMillis()).coerceAtLeast(0L)
        } else {
            0L
        }
    }.getOrDefault(0L)

    /**
     * The battery's extended facts ride the BATTERY_CHANGED sticky intent —
     * the platform's one source for temperature, voltage, and health. Absent
     * extras stay null; the level/charging pair comes from the PowerManager
     * so the two endpoints can never disagree. The charge-life properties
     * (counter, current, cycles) come from BatteryManager and stay null
     * when the hardware does not report them.
     */
    private fun batteryDetail(): BatteryDetailDto {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        fun intExtra(name: String): Int? = intent?.takeIf { it.hasExtra(name) }?.getIntExtra(name, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return BatteryDetailDto(
            level = powerManager.batteryLevel.value ?: PowerManager.UNKNOWN_BATTERY_FALLBACK_PERCENT,
            isCharging = powerManager.isChargingNow(),
            temperatureTenthsC = intExtra(BatteryManager.EXTRA_TEMPERATURE),
            voltageMillivolts = intExtra(BatteryManager.EXTRA_VOLTAGE),
            health = batteryHealthName(intExtra(BatteryManager.EXTRA_HEALTH)),
            batteryChargeCounterMah = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.let { it / 1_000 }, // The platform reports microampere-hours; the wire unit is mAh.
            batteryCurrentMicroAmps = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            batteryCycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The cycle-count property constant is hidden from the public
                // SDK jar (BatteryManager, API 34+); the value is stable.
                batteryProperty(5)
            } else {
                null
            },
        )
    }

    /** One BatteryManager property; null when unsupported (the platform's MIN_VALUE answer). */
    private fun batteryProperty(property: Int): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return runCatching { manager.getIntProperty(property) }
            .getOrNull()
            ?.takeIf { it != Integer.MIN_VALUE }
    }

    private fun batteryHealthName(health: Int?): String? = when (health) {
        null -> null
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }

    /** App-volume stats through StatFs; a failed stat reports zeros, not an error. */
    private fun storageInfo(): StorageInfoDto = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        StorageInfoDto(
            usedBytes = captureHistoryStore.totalBytes(),
            quotaBytes = captureHistoryStore.quotaBytes(),
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrDefault(
        StorageInfoDto(
            usedBytes = captureHistoryStore.totalBytes(),
            quotaBytes = captureHistoryStore.quotaBytes(),
            freeBytes = 0L,
            totalBytes = 0L,
        ),
    )

    /**
     * The per-app battery state (blueprint P0-B item 7), derived per
     * android-14-15-background-limits.md §4.2 — the Settings > Battery triad
     * where the states are mutually exclusive:
     *
     *  - UNRESTRICTED: the app is on the battery-optimization allowlist
     *    (isIgnoringBatteryOptimizations) — that allowlist IS the
     *    Unrestricted state, whose documented profile is "alarms: no
     *    execution limits; network: unrestricted".
     *  - RESTRICTED: ActivityManager.isBackgroundRestricted() (API 28+) —
     *    the Restricted state's own signal, whose documented profile
     *    "fully prevents an app from running in the background": no FGS
     *    launches, an existing FGS demoted out of the foreground, alarms
     *    untriggered.
     *  - OPTIMIZED: neither — the platform default, bucket-driven.
     *
     * The allowlist read answers first because the states are exclusive: a
     * user-set Restricted clears the allowlist, so RESTRICTED can only be
     * reached when the app is genuinely not Unrestricted; reading it second
     * only hardens the verdict against OEM state inconsistencies.
     */
    private fun batteryOptimizationState(): String = when {
        powerManager.isIgnoringBatteryOptimizations() -> BATTERY_STATE_UNRESTRICTED
        isBackgroundRestricted() -> BATTERY_STATE_RESTRICTED
        else -> BATTERY_STATE_OPTIMIZED
    }

    /** The Restricted battery state's own signal; false below API 28 (no mechanics). */
    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching { activityManager.isBackgroundRestricted }.getOrDefault(false)
    }

    companion object {
        /** The batteryOptimizationState wire enum (the Settings > Battery per-app triad, limits doc §4.2). */
        const val BATTERY_STATE_UNRESTRICTED = "UNRESTRICTED"
        const val BATTERY_STATE_OPTIMIZED = "OPTIMIZED"
        const val BATTERY_STATE_RESTRICTED = "RESTRICTED"

        /** The machine-readable restart refusal (blueprint item 8); the dashboard maps it to a localized message. */
        const val RESTART_REASON_EXACT_ALARM_NOT_GRANTED = "exact_alarm_not_granted"

        /** The `mode` query values of POST /api/system/restart (blueprint item 9): soft is the in-process ladder, process the deep restart. */
        const val RESTART_MODE_SOFT = "soft"
        const val RESTART_MODE_PROCESS = "process"

        /** The soft ladder's failure reason (blueprint item 9): the typed refusal names the failed [step]. */
        const val RESTART_REASON_SOFT_RESTART_FAILED = "soft_restart_failed"

        /**
         * The step name an UNWIRED [softRestart] seam reports (the honest
         * default's shape): not a ladder step — a marker that the graph
         * never wired the ladder, so a mis-wired production build reads as
         * a typed failure instead of a success nothing performed.
         */
        const val SOFT_RESTART_STEP_NOT_WIRED = "not_wired"

        /** The device-side fallback prefix for a soft step failure; the dashboard's localized message wins. */
        const val SOFT_RESTART_HINT_PREFIX = "Soft restart failed at "

        // ── POST /api/system/reset-camera wire names (blueprint item 10) ──

        /** The CameraErrorPolicy verdict wire names the `verdict` field carries. */
        const val VERDICT_REBIND = "REBIND"
        const val VERDICT_CAMERAX_HANDLES = "CAMERAX_HANDLES"
        const val VERDICT_REBOOT_REQUIRED = "REBOOT_REQUIRED"
        const val VERDICT_WAIT_CAMERA_ADDED = "WAIT_CAMERA_ADDED"

        const val RESET_REASON_REBIND_DENIED = "rebind_denied"
        const val RESET_REASON_BUDGET_SPENT = "rebind_budget_spent"
        const val RESET_REASON_REBOOT_REQUIRED = "reboot_required"
        const val RESET_REASON_CAMERA_REMOVED = "camera_removed"

        const val RESET_HINT_REBIND_DENIED =
            "The camera rebind was attempted but the bind did not land (for example the device is locked). Unlock the device or retry."
        const val RESET_HINT_BUDGET_SPENT =
            "The camera rebind budget is spent without an observed recovery. Reboot the device if the camera stays down."
        const val RESET_HINT_REBOOT_REQUIRED = "Camera fatal error — reboot the device."
        const val RESET_HINT_CAMERA_REMOVED =
            "The camera hardware was removed (unplugged). Reconnect it; LensCast rebinds when it returns."

        // ── crashRelaunch telemetry wire names (the deferred item-7 half) ──

        /** The crash-relaunch loop guard's state wire enum, derived from [CrashRelaunchPolicy.decide]. */
        const val CRASH_STATE_ARM = "ARM"
        const val CRASH_STATE_BACKOFF = "BACKOFF"
        const val CRASH_STATE_GIVE_UP = "GIVE_UP"

        /**
         * The crash counter's telemetry verdict: the same ladder the crash
         * handler acts on ([CrashRelaunchPolicy.decide]), rendered in the
         * wire names — a count of 0/1 is ARM (fresh guard or a one-off crash;
         * the next crash relaunches at base delay), 2..max is BACKOFF, past
         * the max GIVE_UP.
         */
        fun crashRelaunchStateName(crashCount: Int): String = when (CrashRelaunchPolicy.decide(crashCount)) {
            Verdict.GIVE_UP_STICKY_ONLY -> CRASH_STATE_GIVE_UP
            Verdict.BACKOFF -> CRASH_STATE_BACKOFF
            Verdict.ARM -> CRASH_STATE_ARM
        }

        /**
         * The device-side fallback text riding [RESTART_REASON_EXACT_ALARM_NOT_GRANTED]:
         * the dashboard overrides it with its own localized message for the
         * reason, so this stays an English constant — a courtesy for
         * non-dashboard API consumers, not the UI string.
         */
        const val RESTART_HINT_EXACT_ALARM_NOT_GRANTED =
            "Grant LensCast the Alarms & reminders permission in the device settings, then retry the restart."
    }
}
