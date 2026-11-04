package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.core.StreamDefaults
import com.squareup.moshi.JsonClass

/**
 * One automation rule as the user configured it: "WHEN <trigger> [and ONLY
 * WHEN <conditions>] run <actions>". Persisted through the Settings Store's
 * Moshi codec (see `SettingsDataStore.AutomationRuleJson`), which guarantees
 * a total decode — a malformed payload degrades to an empty list and every
 * field folds to its default — and hands every rule through
 * [AutomationSchedulePolicy.sanitizeRules] before it is trusted, so the
 * defaults here are decode-fallbacks, not trusted wire values.
 *
 * The class is a plain wire/persistence shape on purpose: all decisions
 * (when a rule is due, whether its conditions hold, what its actions mean)
 * live in the pure [AutomationSchedulePolicy], [AutomationTriggerCatalog],
 * [AutomationConditionCatalog] and [AutomationActionCatalog], keeping the
 * whole scheduling surface JVM-testable.
 *
 * @param id Stable identity, used as the fire-journal key so an occurrence
 *   fires once. Absent only on freshly constructed values; the store
 *   decode mints a fresh UUID for it (a rule without an id could never be
 *   journaled and would re-fire on every poll tick or event).
 * @param name Free-form display label; trimmed, never interpreted.
 * @param enabled Per-rule kill switch, independent of the master
 *   automation toggle.
 * @param triggerType Which cue fires the rule — an
 *   [AutomationTriggerCatalog] id. The historically-first kind is
 *   [TRIGGER_TIME_OF_DAY]; the sanitize step resets anything outside the
 *   catalog to it, so an unknown future trigger written by a newer app
 *   version degrades to a time trigger instead of firing on the wrong cue
 *   or being dropped wholesale.
 * @param minuteOfDay The [TRIGGER_TIME_OF_DAY] fire minute, local wall-clock
 *   0..1439; sanitized into that span before use. Kept top-level (rather
 *   than inside [triggerParams]) so the persisted shape of the original
 *   time-only rules never migrates.
 * @param daysMask The [TRIGGER_TIME_OF_DAY] day mask: one bit per ISO day
 *   index (bit 0 = Monday … bit 6 = Sunday), the
 *   [com.raulshma.lenscast.capture.MotionArmingPolicy] convention the arm
 *   schedule already persists — one mask semantics, not two. Clamped to
 *   1..127 (at least one day) by the sanitize step.
 * @param triggerParams Parameters for non-time triggers (e.g.
 *   `battery_level`'s threshold), sanitized against the trigger catalog.
 * @param conditions Preconditions that must hold when the trigger fires
 *   (the "ONLY WHEN" clause); each validated against the
 *   [AutomationConditionCatalog] before use.
 * @param conditionMode How multiple [conditions] combine —
 *   [CONDITION_MODE_ALL] (every condition must hold) or
 *   [CONDITION_MODE_ANY] (at least one); anything else folds to ALL.
 * @param cooldownSeconds Minimum seconds between two firings of this rule,
 *   enforced from the persisted fire journal. Bounds and default live in
 *   [com.raulshma.lenscast.core.StreamDefaults]' AUTOMATION_COOLDOWN_*.
 *   Consulted only by event triggers — a time rule's once-per-day occurrence
 *   key is its own dedupe.
 * @param missedRunPolicy What happens when a [TRIGGER_TIME_OF_DAY] rule's
 *   scheduled moment passes without a firing — the device was off, or the
 *   ONLY WHEN conditions were unmet then became met later the same day.
 *   [MISSED_RUN_CATCH_UP] (the default) keeps the historical behavior:
 *   stateful actions fire however late, one-shot actions within the global
 *   grace. [MISSED_RUN_SKIP] means "at the scheduled time or not at all".
 *   [MISSED_RUN_WINDOW] bounds the catch-up to [missedWindowMinutes] past the
 *   scheduled minute and lets every action ride inside it. Anything else
 *   folds to CATCH_UP. Only time rules can miss — events fire in the present.
 * @param missedWindowMinutes The [MISSED_RUN_WINDOW] catch-up span in
 *   minutes, the same wall-clock-minute space the occurrence keys measure
 *   lateness in; clamped by the sanitize step. Meaningless in the other two
 *   modes.
 * @param actions The ordered action list; each entry is validated against
 *   the [AutomationActionCatalog] before execution.
 */
@JsonClass(generateAdapter = true)
data class AutomationRule(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val triggerType: String = TRIGGER_TIME_OF_DAY,
    val minuteOfDay: Int = 0,
    val daysMask: Int = 127,
    val triggerParams: Map<String, String> = emptyMap(),
    val conditions: List<AutomationConditionSpec> = emptyList(),
    val conditionMode: String = CONDITION_MODE_ALL,
    val cooldownSeconds: Int = StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS,
    val missedRunPolicy: String = MISSED_RUN_CATCH_UP,
    val missedWindowMinutes: Int = StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES,
    val actions: List<AutomationActionSpec> = emptyList(),
) {
    companion object {
        /** The time-of-day trigger kind — the original (and fallback) kind. */
        const val TRIGGER_TIME_OF_DAY = AutomationTriggerCatalog.TIME_OF_DAY

        /** Every [condition] must hold for the rule to fire. */
        const val CONDITION_MODE_ALL = "all"

        /** At least one [condition] must hold for the rule to fire. */
        const val CONDITION_MODE_ANY = "any"

        /**
         * Missed-run policy: fire however late the tick is (stateful
         * actions; one-shots within the global grace) — the default.
         */
        const val MISSED_RUN_CATCH_UP = "catch_up"

        /** Missed-run policy: at the scheduled time or not at all. */
        const val MISSED_RUN_SKIP = "skip"

        /**
         * Missed-run policy: fire late only within [missedWindowMinutes] of
         * the scheduled minute; every action rides inside the window.
         */
        const val MISSED_RUN_WINDOW = "window"
    }
}

/**
 * One precondition inside an [AutomationRule]: a catalog condition id plus
 * its parameters — the same shape discipline as [AutomationActionSpec].
 * Everything is a string on the wire so the persisted shape never needs
 * schema versioning when a condition gains a parameter — validity and typing
 * are the [AutomationConditionCatalog]'s job at sanitize time.
 *
 * @param type Catalog id, e.g. `"time_window"`; an id outside the catalog
 *   makes the whole spec invalid (sanitize drops the condition).
 * @param params Raw parameter values, all serialized as strings. Keys must
 *   match the catalog's param names and values are clamped to the param's
 *   documented bounds — never trusted as-is.
 */
@JsonClass(generateAdapter = true)
data class AutomationConditionSpec(
    val type: String = "",
    val params: Map<String, String> = emptyMap(),
)

/**
 * One step inside an [AutomationRule]: a catalog action id plus its
 * parameters. Everything is a string on the wire so the persisted shape
 * never needs schema versioning when an action gains a parameter — validity
 * and typing are the [AutomationActionCatalog]'s job at sanitize time.
 *
 * @param type Catalog id, e.g. `"torch"`; an id outside the catalog makes
 *   the whole spec invalid (sanitize drops the action).
 * @param params Raw parameter values, all serialized as strings. Keys must
 *   match the catalog's param names and values are clamped to the param's
 *   documented bounds — never trusted as-is.
 */
@JsonClass(generateAdapter = true)
data class AutomationActionSpec(
    val type: String = "",
    val params: Map<String, String> = emptyMap(),
)
