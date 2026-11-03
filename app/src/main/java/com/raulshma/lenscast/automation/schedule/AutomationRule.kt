package com.raulshma.lenscast.automation.schedule

import com.squareup.moshi.JsonClass

/**
 * One time-of-day automation rule as the user configured it: "on these days
 * at this minute, run these actions". Persisted through the Settings Store's
 * Moshi codec (see `SettingsDataStore.AutomationRuleJson`), which guarantees
 * a total decode — a malformed payload degrades to an empty list and every
 * field folds to its default — and hands every rule through
 * [AutomationSchedulePolicy.sanitizeRules] before it is trusted, so the
 * defaults here are decode-fallbacks, not trusted wire values.
 *
 * The class is a plain wire/persistence shape on purpose: all decisions
 * (when a rule is due, what its actions mean) live in the pure
 * [AutomationSchedulePolicy] and [AutomationActionCatalog], keeping the
 * whole scheduling surface JVM-testable.
 *
 * @param id Stable identity, used as the fire-journal key so an occurrence
 *   fires once per day. Absent only on freshly constructed values; the store
 *   decode mints a fresh UUID for it (a rule without an id could never be
 *   journaled and would re-fire on every poll tick).
 * @param name Free-form display label; trimmed, never interpreted.
 * @param enabled Per-rule kill switch, independent of the master
 *   automation toggle.
 * @param triggerType Reserved discriminator for future trigger kinds (e.g.
 *   "detection_event"). Only [TRIGGER_TIME_OF_DAY] is defined today; the
 *   sanitize step resets anything else to it, so an unknown future trigger
 *   written by a newer app version degrades to a time trigger instead of
 *   firing on the wrong cue or being dropped wholesale.
 * @param minuteOfDay Local wall-clock minute 0..1439; sanitized into that
 *   span before use.
 * @param daysMask One bit per ISO day index (bit 0 = Monday … bit 6 =
 *   Sunday), the [com.raulshma.lenscast.capture.MotionArmingPolicy]
 *   convention the arm schedule already persists — one mask semantics, not
 *   two. Clamped to 1..127 (at least one day) by the sanitize step.
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
    val actions: List<AutomationActionSpec> = emptyList(),
) {
    companion object {
        /** The only trigger kind defined today (see [triggerType]). */
        const val TRIGGER_TIME_OF_DAY = "time_of_day"
    }
}

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
