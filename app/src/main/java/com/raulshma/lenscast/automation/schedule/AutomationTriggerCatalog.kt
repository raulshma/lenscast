package com.raulshma.lenscast.automation.schedule

/**
 * One runtime cue an automation rule can fire on — the event vocabulary the
 * wiring layer emits and the schedule policy matches against trigger types.
 * Pure data: everything the policy needs to decide a match is a field here.
 *
 * Detection events carry no payload on purpose: the rule engine fires on
 * "motion happened", not on the motion's delta or zones (those drive the
 * detection coordinator's own pipeline). Power/streaming events carry the
 * new state so the matching trigger's parameter can be compared against it,
 * and [BatteryLevelChanged] carries both readings because its trigger is a
 * threshold *crossing* — without the previous level a crossing cannot be
 * established.
 */
sealed interface AutomationEvent {
    /** Process start; fired once per process by the scheduler itself. */
    data object AppStarted : AutomationEvent

    /** The motion detector fired (post-gate: ML objects, cooldown, arming). */
    data object MotionDetected : AutomationEvent

    /** The sound detector fired. */
    data object SoundDetected : AutomationEvent

    /** Tamper monitor fired (e.g. charger unplugged while streaming). */
    data object TamperDetected : AutomationEvent

    /** Charger connected ([charging] = true) or disconnected. */
    data class ChargingChanged(val charging: Boolean) : AutomationEvent

    /** Battery level moved between distinct integer percents. */
    data class BatteryLevelChanged(val previous: Int?, val current: Int) : AutomationEvent

    /** Streaming started ([streaming] = true) or stopped. */
    data class StreamingChanged(val streaming: Boolean) : AutomationEvent

    /** Web viewer count crossed the zero boundary in either direction. */
    data class ViewersChanged(val connected: Boolean) : AutomationEvent
}

/**
 * One automation trigger a rule may fire on: its id and parameter schema —
 * the trigger-side twin of [AutomationActionType]. Triggers carry no
 * catch-up class (catch-up is a *firing* concept and event triggers fire in
 * the present, never late) and no stream-interrupt flag (they do not run).
 */
data class AutomationTriggerType(
    val id: String,
    val params: List<AutomationParamSpec>,
)

/**
 * The catalog of what an automation rule may fire on: ids and param schemas
 * single-sourced here, the same discipline as [AutomationActionCatalog] —
 * additive only (ids are persisted forever), matching and param validation
 * dispatched at the policy seam, exact id strings pinned by the catalog test.
 *
 * The [TIME_OF_DAY] entry is special: its two "parameters" (the fire minute
 * and the day mask) predate this catalog and persist as the rule's top-level
 * [AutomationRule.minuteOfDay]/[AutomationRule.daysMask] fields, so its
 * params list is empty and neither the editor nor the sanitizer reads
 * `triggerParams` for it. Every other entry reads its parameters from
 * [AutomationRule.triggerParams].
 */
object AutomationTriggerCatalog {

    // Wire ids — pinned by AutomationTriggerCatalogTest, never renamed.
    // Declared before ACTION_TYPES: object properties initialize in
    // declaration order, and the catalog list reads these constants.
    const val TIME_OF_DAY = "time_of_day"
    const val MOTION_DETECTED = "motion_detected"
    const val SOUND_DETECTED = "sound_detected"
    const val TAMPER_DETECTED = "tamper_detected"
    const val CHARGING_CHANGED = "charging_changed"
    const val BATTERY_LEVEL = "battery_level"
    const val STREAMING_CHANGED = "streaming_changed"
    const val VIEWERS_CHANGED = "viewers_changed"
    const val APP_STARTED = "app_started"

    val ACTION_TYPES: List<AutomationTriggerType> = listOf(
        // Params live on the rule's minuteOfDay/daysMask fields (persistence
        // compatibility with the original time-only rules), so the schema
        // here is empty and the editor renders its dedicated time inputs.
        AutomationTriggerType(id = TIME_OF_DAY, params = emptyList()),
        AutomationTriggerType(id = MOTION_DETECTED, params = emptyList()),
        AutomationTriggerType(id = SOUND_DETECTED, params = emptyList()),
        AutomationTriggerType(id = TAMPER_DETECTED, params = emptyList()),
        AutomationTriggerType(
            id = CHARGING_CHANGED,
            params = listOf(
                AutomationParamSpec(
                    name = "charging",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
        ),
        AutomationTriggerType(
            id = BATTERY_LEVEL,
            params = listOf(
                AutomationParamSpec(
                    name = "level",
                    kind = ParamKind.INT,
                    min = 1.0,
                    max = 99.0,
                    optional = false,
                    defaultValue = "20",
                ),
                AutomationParamSpec(
                    name = "when_below",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
        ),
        AutomationTriggerType(
            id = STREAMING_CHANGED,
            params = listOf(
                AutomationParamSpec(
                    name = "streaming",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
        ),
        AutomationTriggerType(
            id = VIEWERS_CHANGED,
            params = listOf(
                AutomationParamSpec(
                    name = "connected",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
        ),
        AutomationTriggerType(id = APP_STARTED, params = emptyList()),
    )

    private val byId: Map<String, AutomationTriggerType> = ACTION_TYPES.associateBy { it.id }

    /** The catalog entry for [id], or null for anything unlisted. */
    fun typeFor(id: String): AutomationTriggerType? = byId[id]

    /**
     * Validate and canonicalize one trigger's params map. Returns null when
     * the trigger id is not in the catalog or an unknown param name is
     * present — the same "not a shape this build understands" verdict as
     * [AutomationActionCatalog.sanitizeAction]. Otherwise every present
     * value is parsed, clamped, and re-rendered canonically; absent required
     * params fold to their documented default, and [TIME_OF_DAY]'s empty
     * schema canonicalizes any input down to an empty map (its real
     * parameters live on the rule's own fields).
     */
    fun sanitizeParams(triggerId: String, raw: Map<String, String>): Map<String, String>? {
        val type = typeFor(triggerId) ?: return null
        val known = type.params.associateBy { it.name }
        if (raw.keys.any { it !in known }) return null
        val sanitized = LinkedHashMap<String, String>()
        for (param in type.params) {
            val canonical = AutomationParamValues.canonicalValue(param, raw[param.name])
            if (canonical != null) sanitized[param.name] = canonical
        }
        return sanitized
    }
}
