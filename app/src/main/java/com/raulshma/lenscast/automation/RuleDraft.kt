package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults

/**
 * The screen-local editing draft of one rule. The editor mutates this shape
 * freely (name typing, param toggles) and only the committed save becomes an
 * [AutomationRule] — the ViewModel's sanitize pass then clamps it into the
 * persisted shape. [id] is null for a rule the store has never seen.
 *
 * Extracted from AutomationScreen so the draft↔rule conversion (the third
 * field-by-field copy of the rule shape) is testable and reusable: the field
 * list must stay in lockstep with [AutomationRule] — a new rule field is
 * added to the persisted shape, the editor draft, and both conversions here.
 */
data class RuleDraft(
    val id: String?,
    val name: String,
    val enabled: Boolean,
    val triggerType: String,
    val minuteOfDay: Int,
    val daysMask: Int,
    val triggerParams: Map<String, String>,
    val conditions: List<AutomationConditionSpec>,
    val conditionMode: String,
    val cooldownSeconds: Int,
    val missedRunPolicy: String,
    val missedWindowMinutes: Int,
    val actions: List<AutomationActionSpec>,
) {
    /** Whether the editor renders the time field + day chips (vs trigger params). */
    val isTimeTrigger: Boolean get() = triggerType == AutomationTriggerCatalog.TIME_OF_DAY

    fun toRule(): AutomationRule = AutomationRule(
        id = id,
        name = name,
        enabled = enabled,
        triggerType = triggerType,
        minuteOfDay = minuteOfDay,
        daysMask = daysMask,
        triggerParams = triggerParams,
        conditions = conditions,
        conditionMode = conditionMode,
        cooldownSeconds = cooldownSeconds,
        missedRunPolicy = missedRunPolicy,
        missedWindowMinutes = missedWindowMinutes,
        actions = actions,
    )

    companion object {
        /** A fresh rule: 08:00 every day, time trigger, no conditions/actions yet. */
        fun new(): RuleDraft = RuleDraft(
            id = null,
            name = "",
            enabled = true,
            triggerType = AutomationTriggerCatalog.TIME_OF_DAY,
            minuteOfDay = DEFAULT_MINUTE_OF_DAY,
            daysMask = MotionArmingPolicy.ALL_DAYS_MASK,
            triggerParams = emptyMap(),
            conditions = emptyList(),
            conditionMode = AutomationRule.CONDITION_MODE_ALL,
            cooldownSeconds = StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS,
            missedRunPolicy = AutomationRule.MISSED_RUN_CATCH_UP,
            missedWindowMinutes = StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES,
            actions = emptyList(),
        )

        fun from(rule: AutomationRule): RuleDraft = RuleDraft(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            triggerType = rule.triggerType,
            minuteOfDay = rule.minuteOfDay,
            daysMask = rule.daysMask,
            triggerParams = rule.triggerParams,
            conditions = rule.conditions,
            conditionMode = rule.conditionMode,
            cooldownSeconds = rule.cooldownSeconds,
            missedRunPolicy = rule.missedRunPolicy,
            missedWindowMinutes = rule.missedWindowMinutes,
            actions = rule.actions,
        )

        private const val DEFAULT_MINUTE_OF_DAY = 8 * 60
    }
}

/** One ↑/↓ reorder step on an editor spec list: move [index] to [toIndex]. */
internal fun <T> List<T>.moved(index: Int, toIndex: Int): List<T> =
    toMutableList().apply { add(toIndex, removeAt(index)) }
