package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.capture.MinuteWindow
import com.raulshma.lenscast.capture.MotionArmingPolicy

/**
 * The pure condition verdicts of the automation engine: does one
 * [AutomationConditionSpec] hold against an [AutomationSensorSnapshot], and
 * does a rule's whole precondition clause hold under its ALL/ANY mode. No
 * Android imports — the scheduler consults this on every tick and every
 * event, so each verdict is JVM-testable and the shell stays thin, the same
 * split as [AutomationSchedulePolicy].
 *
 * Boolean conditions read their BOOL param as "the snapshot state must equal
 * this", so the same condition id serves both polarities (`charging.charging
 * = "false"` demands the device be unplugged). Range conditions
 * (battery/viewers) take optional bounds; an absent bound is unbounded on
 * that side and both absent means trivially met. Unknown state (a null
 * battery level) is never guessed: the condition reads *not met*.
 */
object AutomationConditionPolicy {

    /**
     * Whether one condition holds right now. An unknown condition id reads
     * false — sanitize drops those upstream, so this is the last gate and
     * failing closed is the safe direction.
     */
    fun isMet(condition: AutomationConditionSpec, snapshot: AutomationSensorSnapshot): Boolean {
        val type = AutomationConditionCatalog.typeFor(condition.type) ?: return false
        val params = condition.params
        val specs = type.params
        val bool = { name: String -> AutomationParamValues.boolValue(specs, params, name) }
        val int = { name: String -> AutomationParamValues.intValue(specs, params, name) }
        return when (condition.type) {
            AutomationConditionCatalog.TIME_WINDOW -> {
                // The day mask reuses the arm-schedule bit convention; the
                // window reuses the shared midnight-wrapping ladder, where a
                // degenerate (start == end) window covers nothing — an
                // affirmative "inside this window" has no "never restricts"
                // reading the way the arm schedule's does.
                if (!MotionArmingPolicy.isArmedDay(int("days_mask") ?: return false, snapshot.isoDayIndex)) {
                    return false
                }
                val start = int("start_minute") ?: return false
                val end = int("end_minute") ?: return false
                MinuteWindow.covers(start, end, snapshot.minuteOfDay)
            }
            AutomationConditionCatalog.CHARGING -> snapshot.charging == bool("charging")
            AutomationConditionCatalog.BATTERY_LEVEL -> {
                val level = snapshot.batteryLevel ?: return false
                val min = int("min_level")
                val max = int("max_level")
                (min == null || level >= min) && (max == null || level <= max)
            }
            AutomationConditionCatalog.POWER_SAVE -> snapshot.powerSave == bool("enabled")
            AutomationConditionCatalog.WIFI_CONNECTED -> snapshot.wifiConnected == bool("connected")
            AutomationConditionCatalog.STREAMING -> snapshot.streamingActive == bool("active")
            AutomationConditionCatalog.VIEWERS -> {
                val min = int("min_count")
                val max = int("max_count")
                (min == null || snapshot.viewerCount >= min) && (max == null || snapshot.viewerCount <= max)
            }
            AutomationConditionCatalog.TORCH -> snapshot.torchOn == bool("on")
            AutomationConditionCatalog.RECORDING -> snapshot.recordingActive == bool("active")
            AutomationConditionCatalog.THERMAL -> snapshot.thermalNormal == bool("normal")
            AutomationConditionCatalog.MOTION_DETECTION ->
                snapshot.motionDetectionEnabled == bool("enabled")
            else -> false
        }
    }

    /**
     * Whether a rule's whole precondition clause holds: every condition
     * (ALL), at least one (ANY), or trivially true when the rule carries no
     * conditions — "no preconditions" must mean "fires", under either mode
     * (an empty ANY would otherwise read vacuously false and silently
     * disable the rule).
     */
    fun conditionsMet(rule: AutomationRule, snapshot: AutomationSensorSnapshot): Boolean {
        if (rule.conditions.isEmpty()) return true
        return when (rule.conditionMode) {
            AutomationRule.CONDITION_MODE_ANY -> rule.conditions.any { isMet(it, snapshot) }
            else -> rule.conditions.all { isMet(it, snapshot) }
        }
    }
}
