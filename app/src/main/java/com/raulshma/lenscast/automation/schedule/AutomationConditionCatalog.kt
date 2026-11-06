package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.core.StreamDefaults

/**
 * One automation precondition a rule may require: its id, parameter schema,
 * and the published picker decorations ([group] and [icon] — see
 * [AutomationIconVocabulary] for the icon kind's closed meaning) — the
 * condition-side twin of [AutomationActionType]. Conditions are evaluated
 * against an [AutomationSensorSnapshot] at fire time; the verdicts live in
 * [AutomationConditionPolicy], the schema lives here.
 */
data class AutomationConditionType(
    val id: String,
    val params: List<AutomationParamSpec>,
    /** The picker group this entry lists under — a semantic id, never renamed. */
    val group: String,
    /** The published icon kind (a closed vocabulary; see [AutomationIconVocabulary]). */
    val icon: String,
)

/**
 * The catalog of what an automation rule may demand before firing (the
 * "ONLY WHEN" clause): ids and param schemas single-sourced here, the same
 * discipline as [AutomationActionCatalog] and [AutomationTriggerCatalog] —
 * additive only, verdicts dispatched at the policy seam, exact id strings
 * pinned by the catalog test.
 *
 * Boolean conditions read their single BOOL param as "the state must equal
 * this" (`charging.charging = "false"` demands the device be *un*plugged);
 * numeric-range conditions take optional bounds where an absent bound means
 * "unbounded on that side" and both-absent means the condition is trivially
 * met. GET /api/automation also publishes each entry's picker `group` and
 * `icon` kind, so both editors group and badge the entries from this one
 * source.
 */
object AutomationConditionCatalog {

    // Wire ids — pinned by AutomationConditionCatalogTest, never renamed.
    // Declared before CONDITIONS: object properties initialize in
    // declaration order, and the catalog list reads these constants.
    const val TIME_WINDOW = "time_window"
    const val CHARGING = "charging"
    const val BATTERY_LEVEL = "battery_level"
    const val POWER_SAVE = "power_save"
    const val WIFI_CONNECTED = "wifi_connected"
    const val STREAMING = "streaming"
    const val VIEWERS = "viewers"
    const val TORCH = "torch"
    const val RECORDING = "recording"
    const val THERMAL = "thermal"
    const val MOTION_DETECTION = "motion_detection"

    // Picker group ids — semantic, wire-published (the catalog DTO's
    // `group`), never renamed; the labels live on the clients (string
    // resources / i18n keys keyed off the id).
    const val GROUP_TIME = "time"
    const val GROUP_POWER = "power"
    const val GROUP_CONNECTIVITY = "connectivity"
    const val GROUP_DEVICE_STATE = "device_state"

    val CONDITIONS: List<AutomationConditionType> = listOf(
        AutomationConditionType(
            id = TIME_WINDOW,
            params = listOf(
                AutomationParamSpec(
                    name = "start_minute",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = StreamDefaults.MINUTES_PER_DAY - 1.0,
                    optional = false,
                    defaultValue = "0",
                ),
                AutomationParamSpec(
                    name = "end_minute",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = StreamDefaults.MINUTES_PER_DAY - 1.0,
                    optional = false,
                    defaultValue = "1439",
                ),
                AutomationParamSpec(
                    name = "days_mask",
                    kind = ParamKind.INT,
                    min = StreamDefaults.MOTION_ARM_DAYS_MASK_MIN.toDouble(),
                    max = StreamDefaults.MOTION_ARM_DAYS_MASK_MAX.toDouble(),
                    optional = false,
                    defaultValue = "127",
                ),
            ),
            group = GROUP_TIME,
            icon = AutomationIconVocabulary.TIME_WINDOW,
        ),
        AutomationConditionType(
            id = CHARGING,
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
            group = GROUP_POWER,
            icon = AutomationIconVocabulary.POWER,
        ),
        AutomationConditionType(
            id = BATTERY_LEVEL,
            params = listOf(
                AutomationParamSpec(
                    name = "min_level",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = 100.0,
                    optional = true,
                    defaultValue = "",
                ),
                AutomationParamSpec(
                    name = "max_level",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = 100.0,
                    optional = true,
                    defaultValue = "",
                ),
            ),
            group = GROUP_POWER,
            icon = AutomationIconVocabulary.BATTERY,
        ),
        AutomationConditionType(
            id = POWER_SAVE,
            params = listOf(
                AutomationParamSpec(
                    name = "enabled",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "false",
                ),
            ),
            group = GROUP_POWER,
            icon = AutomationIconVocabulary.BATTERY_SAVER,
        ),
        AutomationConditionType(
            id = WIFI_CONNECTED,
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
            group = GROUP_CONNECTIVITY,
            icon = AutomationIconVocabulary.WIFI,
        ),
        AutomationConditionType(
            id = STREAMING,
            params = listOf(
                AutomationParamSpec(
                    name = "active",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
            group = GROUP_CONNECTIVITY,
            icon = AutomationIconVocabulary.STREAM,
        ),
        AutomationConditionType(
            id = VIEWERS,
            params = listOf(
                AutomationParamSpec(
                    name = "min_count",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = 99.0,
                    optional = true,
                    defaultValue = "",
                ),
                AutomationParamSpec(
                    name = "max_count",
                    kind = ParamKind.INT,
                    min = 0.0,
                    max = 99.0,
                    optional = true,
                    defaultValue = "",
                ),
            ),
            group = GROUP_CONNECTIVITY,
            icon = AutomationIconVocabulary.PEOPLE,
        ),
        AutomationConditionType(
            id = TORCH,
            params = listOf(
                AutomationParamSpec(
                    name = "on",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
            group = GROUP_DEVICE_STATE,
            icon = AutomationIconVocabulary.LIGHTBULB,
        ),
        AutomationConditionType(
            id = RECORDING,
            params = listOf(
                AutomationParamSpec(
                    name = "active",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
            group = GROUP_DEVICE_STATE,
            icon = AutomationIconVocabulary.RECORD,
        ),
        AutomationConditionType(
            id = THERMAL,
            params = listOf(
                AutomationParamSpec(
                    name = "normal",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
            group = GROUP_DEVICE_STATE,
            icon = AutomationIconVocabulary.THERMOSTAT,
        ),
        AutomationConditionType(
            id = MOTION_DETECTION,
            params = listOf(
                AutomationParamSpec(
                    name = "enabled",
                    kind = ParamKind.BOOL,
                    min = null,
                    max = null,
                    optional = false,
                    defaultValue = "true",
                ),
            ),
            group = GROUP_DEVICE_STATE,
            icon = AutomationIconVocabulary.RADAR,
        ),
    )

    private val byId: Map<String, AutomationConditionType> = CONDITIONS.associateBy { it.id }

    /** The catalog entry for [id], or null for anything unlisted. */
    fun typeFor(id: String): AutomationConditionType? = byId[id]

    /**
     * Validate and canonicalize one condition spec — the same ladder as
     * [AutomationActionCatalog.sanitizeAction]: null when the condition id
     * is unknown or an unknown param name is present; otherwise every value
     * parsed, clamped, and canonically re-rendered, absent required params
     * folded to their documented default, absent optional params left
     * absent.
     */
    fun sanitizeCondition(spec: AutomationConditionSpec): AutomationConditionSpec? {
        val type = typeFor(spec.type) ?: return null
        val known = type.params.associateBy { it.name }
        if (spec.params.keys.any { it !in known }) return null
        val sanitized = LinkedHashMap<String, String>()
        for (param in type.params) {
            val canonical = AutomationParamValues.canonicalValue(param, spec.params[param.name])
            if (canonical != null) sanitized[param.name] = canonical
        }
        return AutomationConditionSpec(type = spec.type, params = sanitized)
    }
}
