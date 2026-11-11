package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.core.StreamDefaults

/*
 * The rule editor's pure state helpers, extracted from AutomationScreen so
 * the editor's small decisions are JVM-testable like the ViewModel
 * companion's — the composables render, this module answers:
 *
 * - isAutoSentinelParam: whether a param row renders the Auto toggle. The
 *   catalog's AutomationParamSpec.autoAtZero is the one authority — the old
 *   hardcoded fallback name-set ("iso", "exposure_time_ms") is gone because
 *   the catalog fully covers it (those are exactly the two autoAtZero
 *   specs), so a name the catalog does not list under the action id simply
 *   has no sentinel row. (The web client keeps its own legacy name-set
 *   fallback for its fetched-catalog misses — that twin stays web-side.)
 * - conditionWindowDaysMask: a time-window condition's persisted day mask,
 *   reusing the Motion Arming Policy bit convention (bit 0 = Monday … bit 6
 *   = Sunday) — one mask semantics, not two.
 * - the cooldown / missed-window preset chips and the cooldown display
 *   ceiling, named here instead of inline literals so the web client's
 *   "exactly the app's presets" contract has one Kotlin home pinned by tests.
 */

/**
 * Whether this param renders the Auto toggle ("0" = the auto sentinel): the
 * catalog's [AutomationParamSpec.autoAtZero] decides, looked up by the
 * action id + param name pair — the same lookup the editor's rows are built
 * from, so a rendered row always finds its own spec. A pair the catalog does
 * not know (a legacy persisted action the sanitizer would have dropped)
 * has no sentinel: false, not a stale name guess.
 */
fun isAutoSentinelParam(typeId: String, spec: AutomationParamSpec): Boolean =
    AutomationActionCatalog.typeFor(typeId)
        ?.params?.firstOrNull { it.name == spec.name }?.autoAtZero
        ?: false

/**
 * A time-window condition's persisted day mask (the sanitize default when
 * absent): the `days_mask` param parsed as an Int, falling back to
 * [StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT] — the same value the
 * condition catalog documents as the param's default, so an absent mask
 * reads as "every day" exactly like a sanitized rule evaluates it.
 */
fun conditionWindowDaysMask(condition: AutomationConditionSpec): Int =
    condition.params["days_mask"]?.toIntOrNull()
        ?: StreamDefaults.MOTION_ARM_DAYS_MASK_DEFAULT

/**
 * The cooldown preset chips (seconds) — the common spans the editor offers
 * before the slider. The default ride-along comes from
 * [StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS] (the same constant
 * the persisted-rule decode folds to), so retuning the default retunes the
 * chips; the list values themselves are the app's stable editor contract
 * (the web client pins the same six spans).
 */
val AUTOMATION_COOLDOWN_PRESET_SECONDS: List<Int> = listOf(
    30,
    StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS,
    300,
    900,
    1_800,
    3_600,
)

/**
 * The missed-window preset chips (minutes) for a time rule's
 * [com.raulshma.lenscast.automation.schedule.MissedRunPolicy.WINDOW]
 * catch-up span — the same preset discipline as [AUTOMATION_COOLDOWN_PRESET_SECONDS],
 * with the default from [StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES].
 */
val AUTOMATION_MISSED_WINDOW_PRESET_MINUTES: List<Int> = listOf(
    5,
    15,
    StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES,
    60,
    180,
)

/**
 * The cooldown slider's display ceiling in seconds: the persisted bound
 * allows a day ([StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS]), but no
 * useful cooldown needs a wider slider than an hour, so a value persisted
 * above this renders pinned at the ceiling.
 */
const val AUTOMATION_COOLDOWN_DISPLAY_MAX_SECONDS = 3_600
