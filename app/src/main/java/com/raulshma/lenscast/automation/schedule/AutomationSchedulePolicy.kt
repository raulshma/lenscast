package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults

/**
 * The pure decision half of the time-of-day automation engine — which rules
 * are due, what a rule's sanitized action list looks like, and how one
 * catalog action folds into the current camera settings. No Android imports:
 * the poller/executor (which owns the clock, the fire journal, and the
 * runtime seams) consults this object every tick, so every verdict is
 * JVM-testable and the shell stays thin — the same split as
 * [com.raulshma.lenscast.automation.AutomationIntentPolicy].
 *
 * Time model: a rule's occurrence is identified by
 * `epochDay * [StreamDefaults.MINUTES_PER_DAY] + minuteOfDay`, a single
 * monotonic minute counter that makes "did today's occurrence already fire?"
 * one integer comparison against the journal instead of a date+time dance
 * across midnight.
 */
object AutomationSchedulePolicy {

    /**
     * Dedup identity of one firing: [epochDay] * [StreamDefaults.MINUTES_PER_DAY]
     * + [AutomationRule.minuteOfDay]. The journal stores the key of the last
     * fired occurrence per rule id; a rule fires only when its key is strictly
     * newer than the journaled one.
     */
    fun occurrenceKey(rule: AutomationRule, epochDay: Long): Long =
        epochDay * StreamDefaults.MINUTES_PER_DAY + rule.minuteOfDay

    /**
     * One rule's firing: the rule plus the actions still allowed to run.
     */
    data class FiredRule(val rule: AutomationRule, val actions: List<AutomationActionSpec>)

    /**
     * Rules due right now with their filtered action lists.
     *
     * A rule is due when: the master automation toggle [enabled] is on, the
     * rule itself is enabled, its day mask covers [isoDayIndex] (the
     * [MotionArmingPolicy.isArmedDay] ISO bit convention), its occurrence key
     * for [epochDay] is strictly newer than the journaled key for its id
     * (equal or older = already fired), and the occurrence time has actually
     * arrived (the key is not in today's future — explicit even though the
     * journal comparison alone would rarely admit a future key, so the
     * "why is it not firing" story never depends on journal state).
     *
     * Catch-up: STATEFUL actions (setting convergence) are always included
     * when the rule fires, no matter how late the tick is; ONESHOT actions
     * (moment effects like a photo) only when the rule is within [graceMs]
     * of its scheduled instant. Lateness is measured in the same wall-clock
     * minute space as the occurrence keys — `currentKey - key` is the
     * minutes-late count — not against a real epoch clock: a wall-clock
     * comparison would smear the window by the zone offset on non-UTC
     * devices, since the occurrence is a local-time figure. A rule whose
     * whole action list is filtered away is dropped entirely — it never
     * fires partially, and it is not journaled, so its one-shot can still
     * catch up on a later tick within grace.
     */
    fun dueRules(
        rules: List<AutomationRule>,
        enabled: Boolean,
        minuteOfDay: Int,
        epochDay: Long,
        isoDayIndex: Int,
        journal: Map<String, Long>,
        graceMs: Long,
    ): List<FiredRule> {
        if (!enabled) return emptyList()
        val currentKey = epochDay * StreamDefaults.MINUTES_PER_DAY + minuteOfDay
        val fired = ArrayList<FiredRule>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (!MotionArmingPolicy.isArmedDay(rule.daysMask, isoDayIndex)) continue
            val key = occurrenceKey(rule, epochDay)
            val journaled = rule.id?.let(journal::get)
            if (journaled != null && key <= journaled) continue
            // Time arrived: today's occurrence must not still be ahead of us.
            if (key > currentKey) continue
            // Non-negative by the check above: the occurrence is never in
            // the future when lateness is computed.
            val minutesLate = currentKey - key
            val actions = rule.actions.filter { action ->
                when (AutomationActionCatalog.typeFor(action.type)?.catchUp) {
                    // Unknown action ids have no catch-up class — they cannot
                    // be executed, so they cannot catch up either. (Sanitizing
                    // upstream should have dropped them; this is the last gate.)
                    CatchUpClass.STATEFUL -> true
                    CatchUpClass.ONESHOT -> minutesLate * 60_000L <= graceMs
                    null -> false
                }
            }
            if (actions.isEmpty()) continue
            fired.add(FiredRule(rule, actions))
        }
        return fired
    }

    /**
     * Clamp one rule into its persisted/usable shape: minute into
     * 0..[StreamDefaults.MINUTES_PER_DAY]-1, day mask into
     * [StreamDefaults.MOTION_ARM_DAYS_MASK_MIN]..[StreamDefaults.MOTION_ARM_DAYS_MASK_MAX]
     * (a zero mask would disarm the rule silently — the enabled toggle
     * already expresses that, same reasoning as the arm-days store clamp),
     * name trimmed, [AutomationRule.triggerType] kept only when it is the
     * one defined kind and reset to it otherwise, and the action list
     * sanitized through the catalog (unknown actions dropped) then capped at
     * [StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE]. The cap applies to
     * the sanitized list, so the rule keeps its max *executable* actions
     * rather than losing valid ones to unknown noise ahead of them. The id
     * is preserved untouched — identity is not the sanitizer's to change.
     */
    fun sanitizeRule(rule: AutomationRule): AutomationRule = rule.copy(
        name = rule.name.trim(),
        triggerType = if (rule.triggerType == AutomationRule.TRIGGER_TIME_OF_DAY) {
            rule.triggerType
        } else {
            AutomationRule.TRIGGER_TIME_OF_DAY
        },
        minuteOfDay = rule.minuteOfDay.coerceIn(0, StreamDefaults.MINUTES_PER_DAY - 1),
        daysMask = rule.daysMask.coerceIn(
            StreamDefaults.MOTION_ARM_DAYS_MASK_MIN,
            StreamDefaults.MOTION_ARM_DAYS_MASK_MAX,
        ),
        actions = rule.actions
            .mapNotNull(AutomationActionCatalog::sanitizeAction)
            .take(StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE),
    )

    /**
     * Sanitize a whole rule list, capped at [StreamDefaults.AUTOMATION_MAX_RULES]
     * entries. Rules with a null id pass through as-is: minting identities is
     * the store decode's job (it does so before calling this), so a pure
     * function stays deterministic.
     */
    fun sanitizeRules(rules: List<AutomationRule>): List<AutomationRule> =
        rules.take(StreamDefaults.AUTOMATION_MAX_RULES).map(::sanitizeRule)

    /**
     * Merge ONE catalog action into the current [CameraSettings]. Handles the
     * camera_* settings the camera pipeline consumes directly: only the exact
     * "0" auto sentinels fold to null (the class's "driver default" shape),
     * `exposure_time_ms` converts to nanoseconds, and `torch.strength` is
     * applied only when the optional parameter is present. Per the executor's
     * "absent/malformed = leave unchanged" contract, an absent optional param
     * AND an unparseable value both leave that field untouched — a malformed
     * value must never collapse to the auto sentinel (that would silently
     * hand a manual setting back to the driver). Non-camera types
     * (stream/recording/siren/detection/capture_photo) come back unchanged;
     * the executor routes them to their own seams, not the camera settings.
     * Malformed values cannot reach here through the store path (the catalog
     * sanitizer clamps or folds them upstream), but the parsers stay
     * null-safe so a hand-built action degrades to "no change" instead of a
     * crash.
     */
    fun mergeCameraSettings(current: CameraSettings, action: AutomationActionSpec): CameraSettings {
        val params = action.params
        when (action.type) {
            "camera_exposure" -> {
                var merged = current
                // ONLY the exact "0" sentinel is the auto sentinel → null
                // (driver auto); any other parseable value is the manual
                // ISO, and an unparseable one leaves the field unchanged.
                params["iso"]?.let { raw ->
                    if (raw == "0") {
                        merged = merged.copy(iso = null)
                    } else {
                        raw.toIntOrNull()?.let { iso -> merged = merged.copy(iso = iso) }
                    }
                }
                // "0" is auto → null; a parseable value is milliseconds →
                // nanoseconds; an unparseable one leaves the field unchanged.
                params["exposure_time_ms"]?.let { raw ->
                    if (raw == "0") {
                        merged = merged.copy(exposureTime = null)
                    } else {
                        raw.toDoubleOrNull()?.let { ms ->
                            merged = merged.copy(exposureTime = (ms * 1_000_000).toLong())
                        }
                    }
                }
                return merged
            }
            "camera_ev" -> params["index"]?.toIntOrNull()?.let { index ->
                return current.copy(exposureCompensation = index)
            }
            "camera_zoom" -> params["ratio"]?.toFloatOrNull()?.let { ratio ->
                return current.copy(zoomRatio = ratio)
            }
            "camera_fps" -> params["fps"]?.toIntOrNull()?.let { fps ->
                return current.copy(frameRate = fps)
            }
            "torch" -> {
                var merged = current
                when (params["enabled"]) {
                    "true" -> merged = merged.copy(torchEnabled = true)
                    "false" -> merged = merged.copy(torchEnabled = false)
                    // absent/unknown: leave the current torch state alone
                }
                // Strength applies only when the optional param is present:
                // absent means "device default", and clobbering a strength
                // the user set elsewhere is not this action's business.
                params["strength"]?.toIntOrNull()?.let { strength ->
                    merged = merged.copy(torchStrength = strength)
                }
                return merged
            }
        }
        // stream, capture_photo, recording, siren, detection, unknown ids —
        // and any camera action whose param failed to parse — come back
        // unchanged; the executor routes non-camera types to their own seams.
        return current
    }
}
