package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults

/**
 * The pure decision half of the automation engine — which rules are due,
 * whether a rule's preconditions hold, what a rule's sanitized shape looks
 * like, and how one catalog action folds into the current camera settings.
 * No Android imports: the poller/executor (which owns the clock, the fire
 * journal, and the runtime seams) consults this object every tick and every
 * event, so every verdict is JVM-testable and the shell stays thin — the
 * same split as [com.raulshma.lenscast.automation.AutomationIntentPolicy].
 *
 * Two evaluation paths share this object, one per trigger kind:
 *
 * - **Time** ([dueRules]) runs on the 30 s poll tick. A time rule's
 *   occurrence is identified by
 *   `epochDay * [StreamDefaults.MINUTES_PER_DAY] + minuteOfDay`, a single
 *   monotonic minute counter that makes "did today's occurrence already
 *   fire?" one integer comparison against the journal instead of a
 *   date+time dance across midnight.
 * - **Event** ([eventDue]) runs when the wiring layer delivers an
 *   [AutomationEvent]. An event rule's dedup identity is the wall-clock
 *   epoch millisecond of its last firing, and re-firing is gated by the
 *   rule's cooldown rather than by once-per-day occurrence keys.
 *
 * The journal ([AutomationFireJournal], `Map<String, Long>` per rule id)
 * carries both kinds of values; [EVENT_JOURNAL_THRESHOLD] separates them, so
 * a rule whose trigger kind the user changed never has its old record read
 * as the other kind (and vice versa).
 */
object AutomationSchedulePolicy {

    /**
     * Journal values below this are time-occurrence keys (bounded by
     * `epochDay * 1440 + 1439` — under ~3e7 for centuries); values at or
     * above it are event-fire epoch milliseconds (~1.8e12 today). Each
     * evaluation path only reads the values of its own kind, so switching a
     * rule's trigger kind leaves the stale record of the other kind inert
     * instead of blocking every future fire.
     */
    const val EVENT_JOURNAL_THRESHOLD = 1_000_000_000L

    /**
     * Dedup identity of one time firing: [epochDay] *
     * [StreamDefaults.MINUTES_PER_DAY] + [AutomationRule.minuteOfDay]. The
     * journal stores the key of the last fired occurrence per rule id; a
     * time rule fires only when its key is strictly newer than the journaled
     * one.
     */
    fun occurrenceKey(rule: AutomationRule, epochDay: Long): Long =
        epochDay * StreamDefaults.MINUTES_PER_DAY + rule.minuteOfDay

    /**
     * One rule's firing: the rule plus the actions still allowed to run.
     */
    data class FiredRule(val rule: AutomationRule, val actions: List<AutomationActionSpec>)

    /**
     * Time rules due right now with their filtered action lists.
     *
     * A rule is due when: the master automation toggle [enabled] is on, the
     * rule itself is a [AutomationTriggerCatalog.TIME_OF_DAY] rule (event
     * rules ride [eventDue] on their own cues — their minuteOfDay field is
     * meaningless, and evaluating it here would fire every event rule at
     * midnight), the rule is enabled, its day mask covers the snapshot's
     * ISO day ([MotionArmingPolicy.isArmedDay] convention), its
     * preconditions hold ([AutomationConditionPolicy.conditionsMet]), its
     * occurrence key for the snapshot's epoch day is strictly newer than the
     * journaled occurrence key for its id (equal or older = already fired;
     * journal values of the *event* kind are not occurrence keys and don't
     * block), and the occurrence time has actually arrived (the key is not
     * in the future — explicit even though the journal comparison alone
     * would rarely admit a future key, so the "why is it not firing" story
     * never depends on journal state).
     *
     * Preconditions-unmet is deliberately NOT a journal write: the rule
     * stays due on the next tick and fires the moment its conditions become
     * met (later the same day), which is what "ONLY WHEN" should mean for a
     * scheduled rule.
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
        snapshot: AutomationSensorSnapshot,
        journal: Map<String, Long>,
        graceMs: Long,
    ): List<FiredRule> {
        if (!enabled) return emptyList()
        val currentKey = snapshot.epochDay * StreamDefaults.MINUTES_PER_DAY + snapshot.minuteOfDay
        val fired = ArrayList<FiredRule>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.triggerType != AutomationRule.TRIGGER_TIME_OF_DAY) continue
            if (!MotionArmingPolicy.isArmedDay(rule.daysMask, snapshot.isoDayIndex)) continue
            if (!AutomationConditionPolicy.conditionsMet(rule, snapshot)) continue
            val key = occurrenceKey(rule, snapshot.epochDay)
            val journaled = rule.id?.let(journal::get)
                ?.takeIf { it < EVENT_JOURNAL_THRESHOLD }
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
     * Event rules due right now for [event], with their full action lists —
     * an event fires in the present, so there is no lateness and no
     * catch-up filter (every action rides, ONESHOT included).
     *
     * A rule is due when: the master toggle [enabled] is on, the rule is
     * enabled and has an id (a null-id rule could never be journaled —
     * unreachable through the store, which mints ids — and would re-fire on
     * every matching event), its trigger matches the event
     * ([triggerMatches]), its preconditions hold, and its cooldown has
     * elapsed: the journaled value for its id — only *event-kind* journal
     * values (≥ [EVENT_JOURNAL_THRESHOLD]) count; a stale occurrence key
     * from the rule's life as a time trigger is not a cooldown record — must
     * be older than [AutomationRule.cooldownSeconds] ago from [nowMs]. A
     * cooldown of 0 admits every event, including two in the same
     * millisecond.
     */
    fun eventDue(
        rules: List<AutomationRule>,
        enabled: Boolean,
        event: AutomationEvent,
        snapshot: AutomationSensorSnapshot,
        journal: Map<String, Long>,
        nowMs: Long,
    ): List<FiredRule> {
        if (!enabled) return emptyList()
        val fired = ArrayList<FiredRule>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.id == null) continue
            if (!triggerMatches(rule.triggerType, rule.triggerParams, event)) continue
            if (!AutomationConditionPolicy.conditionsMet(rule, snapshot)) continue
            // Only event-kind journal values gate the cooldown; no entry (or
            // a stale occurrence key from the rule's life as a time trigger)
            // means "never fired" — due.
            val lastEventFire = journal[rule.id]?.takeIf { it >= EVENT_JOURNAL_THRESHOLD }
            if (lastEventFire != null && nowMs - lastEventFire < rule.cooldownSeconds * 1000L) continue
            fired.add(FiredRule(rule, rule.actions))
        }
        return fired
    }

    /**
     * Whether [event] matches a rule's trigger: the trigger kind must be the
     * event's kind, and for parameterized triggers the event's payload must
     * match the configured parameter. [AutomationEvent.BatteryLevelChanged]
     * matches on a threshold *crossing*: with `when_below = true` the level
     * must move from above the threshold to at-or-below it (so a device
     * holding steady at 21% with threshold 20 stays quiet and 21→20 fires
     * exactly once), with `when_below = false` the mirror-image climb. A
     * null previous level (first read of the process) cannot establish a
     * crossing and never fires; [AutomationRule.TRIGGER_TIME_OF_DAY] matches
     * no event — its fires come only from [dueRules].
     */
    fun triggerMatches(
        triggerType: String,
        triggerParams: Map<String, String>,
        event: AutomationEvent,
    ): Boolean {
        val bool = { name: String ->
            AutomationParamValues.boolValue(
                AutomationTriggerCatalog.typeFor(triggerType)?.params ?: emptyList(),
                triggerParams,
                name,
            )
        }
        return when (event) {
            is AutomationEvent.AppStarted -> triggerType == AutomationTriggerCatalog.APP_STARTED
            is AutomationEvent.MotionDetected -> triggerType == AutomationTriggerCatalog.MOTION_DETECTED
            is AutomationEvent.SoundDetected -> triggerType == AutomationTriggerCatalog.SOUND_DETECTED
            is AutomationEvent.TamperDetected -> triggerType == AutomationTriggerCatalog.TAMPER_DETECTED
            is AutomationEvent.ChargingChanged ->
                triggerType == AutomationTriggerCatalog.CHARGING_CHANGED && bool("charging") == event.charging
            is AutomationEvent.BatteryLevelChanged ->
                triggerType == AutomationTriggerCatalog.BATTERY_LEVEL && batteryCrossed(triggerType, triggerParams, event)
            is AutomationEvent.StreamingChanged ->
                triggerType == AutomationTriggerCatalog.STREAMING_CHANGED && bool("streaming") == event.streaming
            is AutomationEvent.ViewersChanged ->
                triggerType == AutomationTriggerCatalog.VIEWERS_CHANGED && bool("connected") == event.connected
        }
    }

    /** The battery threshold-crossing verdict described in [triggerMatches]. */
    private fun batteryCrossed(
        triggerType: String,
        triggerParams: Map<String, String>,
        event: AutomationEvent.BatteryLevelChanged,
    ): Boolean {
        val previous = event.previous ?: return false
        val specs = AutomationTriggerCatalog.typeFor(triggerType)?.params ?: emptyList()
        val threshold = AutomationParamValues.intValue(specs, triggerParams, "level") ?: return false
        val whenBelow = AutomationParamValues.boolValue(specs, triggerParams, "when_below")
        return if (whenBelow) {
            previous > threshold && event.current <= threshold
        } else {
            previous <= threshold && event.current > threshold
        }
    }

    /**
     * Clamp one rule into its persisted/usable shape: name trimmed;
     * [AutomationRule.triggerType] kept only when it is a catalog kind and
     * reset to time-of-day otherwise (an unknown future trigger written by a
     * newer app version degrades to a time trigger instead of firing on the
     * wrong cue); [AutomationRule.triggerParams] sanitized against the
     * trigger catalog, an unknown-key payload degrading to the schema's
     * documented defaults; minute into 0..[StreamDefaults.MINUTES_PER_DAY]-1;
     * day mask into
     * [StreamDefaults.MOTION_ARM_DAYS_MASK_MIN]..[StreamDefaults.MOTION_ARM_DAYS_MASK_MAX]
     * (a zero mask would disarm the rule silently — the enabled toggle
     * already expresses that, same reasoning as the arm-days store clamp);
     * conditions sanitized through the condition catalog (unknown conditions
     * dropped) then capped at
     * [StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE]; mode folded to
     * ALL unless it is exactly ANY; cooldown clamped into the
     * [StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS]..[StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS]
     * span; and the action list sanitized through the catalog (unknown
     * actions dropped) then capped at
     * [StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE] — the cap applies to
     * the sanitized list, so the rule keeps its max *executable* actions
     * rather than losing valid ones to unknown noise ahead of them. The id
     * is preserved untouched — identity is not the sanitizer's to change.
     */
    fun sanitizeRule(rule: AutomationRule): AutomationRule {
        val triggerType = if (AutomationTriggerCatalog.typeFor(rule.triggerType) != null) {
            rule.triggerType
        } else {
            AutomationRule.TRIGGER_TIME_OF_DAY
        }
        val triggerParams = AutomationTriggerCatalog.sanitizeParams(triggerType, rule.triggerParams)
            ?: AutomationTriggerCatalog.sanitizeParams(triggerType, emptyMap())
            ?: emptyMap()
        return rule.copy(
            name = rule.name.trim(),
            triggerType = triggerType,
            minuteOfDay = rule.minuteOfDay.coerceIn(0, StreamDefaults.MINUTES_PER_DAY - 1),
            daysMask = rule.daysMask.coerceIn(
                StreamDefaults.MOTION_ARM_DAYS_MASK_MIN,
                StreamDefaults.MOTION_ARM_DAYS_MASK_MAX,
            ),
            triggerParams = triggerParams,
            conditions = rule.conditions
                .mapNotNull(AutomationConditionCatalog::sanitizeCondition)
                .take(StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE),
            conditionMode = if (rule.conditionMode == AutomationRule.CONDITION_MODE_ANY) {
                AutomationRule.CONDITION_MODE_ANY
            } else {
                AutomationRule.CONDITION_MODE_ALL
            },
            cooldownSeconds = rule.cooldownSeconds.coerceIn(
                StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS,
                StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS,
            ),
            actions = rule.actions
                .mapNotNull(AutomationActionCatalog::sanitizeAction)
                .take(StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE),
        )
    }

    /**
     * Sanitize a whole rule list, capped at [StreamDefaults.AUTOMATION_MAX_RULES]
     * entries. Rules with a null id pass through as-is: minting identities is
     * the writers' job (the store decode mints on read, the web handler and
     * the in-app ViewModel mint at save, and the store's serializer persists
     * an id for any writer that sent none), so a pure function stays
     * deterministic.
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
