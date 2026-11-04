package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import java.util.TimeZone
import com.raulshma.lenscast.streaming.model.AutomationActionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogActionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogConditionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogParamDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogTriggerDto
import com.raulshma.lenscast.streaming.model.AutomationConditionDto
import com.raulshma.lenscast.streaming.model.AutomationRuleDto
import com.raulshma.lenscast.streaming.model.AutomationStateDto
import com.raulshma.lenscast.streaming.model.AutomationUpdateRequestDto

/**
 * /api/automation — JSON-in/JSON-out mapping between the Web API DTOs and the
 * Settings Store's automation surface: the master toggle, the rule list (each
 * with `lastFiredAt` decoded from the shared fire journal), and the three
 * read-only catalogs (actions, triggers, conditions) the rule editor renders
 * from.
 *
 * Any authenticated session may GET the document (the /api/settings precedent:
 * viewer reads ride the default gate, only the three admin-only reads are
 * special); writes stay config writes — a viewer session is denied by the role
 * gate and an API token by the token write ladder, exactly like PUT
 * /api/settings. The PUT persists only after
 * [AutomationSchedulePolicy.sanitizeRules] has clamped every rule, dropped
 * every unknown action/condition, and folded every unknown trigger to
 * `time_of_day`; the store's own codec sanitizes again behind it, so no
 * writer can persist an out-of-bounds rule.
 */
class AutomationWebHandler(
    private val settingsDataStore: SettingsDataStore,
    private val fireJournal: AutomationFireJournal,
) {

    private val stateAdapter by lazy { AppJson.moshi.adapter(AutomationStateDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(AutomationUpdateRequestDto::class.java) }

    /**
     * PUT /api/automation — persists the master toggle and the rule list
     * wholesale (a rule absent from the document is gone, the settings-section
     * convention). Rules map back to the domain with a blank wire id minting
     * the fire-journal UUID right here, at the boundary — the same convention
     * as the in-app save path (the ViewModel mints its drafts too): whatever
     * identity lands on disk must be the one the journal keys on. They then
     * pass through [AutomationSchedulePolicy.sanitizeRules] before the
     * savers — the response is a fresh disk read, not the shared flows (which
     * update on a collector's beat and once raced this very write), so the
     * client sees the persisted sanitized truth (clamped minutes/masks/
     * cooldowns/missed-run windows, folded condition modes and missed-run
     * policies, canonical trigger/condition/action params, minted ids)
     * rather than what it sent.
     */
    suspend fun put(body: String): String {
        val request = updateAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid automation JSON")

        val rules = request.rules.map { dto ->
            AutomationRule(
                id = dto.id.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                name = dto.name,
                enabled = dto.enabled,
                triggerType = dto.triggerType,
                minuteOfDay = dto.minuteOfDay,
                daysMask = dto.daysMask,
                triggerParams = dto.triggerParams,
                conditions = dto.conditions.map { condition ->
                    AutomationConditionSpec(type = condition.type, params = condition.params)
                },
                conditionMode = dto.conditionMode,
                cooldownSeconds = dto.cooldownSeconds,
                missedRunPolicy = dto.missedRunPolicy,
                missedWindowMinutes = dto.missedWindowMinutes,
                actions = dto.actions.map { action ->
                    AutomationActionSpec(type = action.type, params = action.params)
                },
            )
        }
        val sanitized = AutomationSchedulePolicy.sanitizeRules(rules)

        settingsDataStore.saveAutomationEnabled(request.enabled)
        settingsDataStore.saveAutomationRules(sanitized)

        // Answer with a fresh disk read: the DataStore write completed, but
        // the shared StateFlows re-decode on a collector's beat — reading
        // them here once raced the save and echoed the pre-write rules.
        return stateAdapter.toJson(
            buildState(
                enabled = settingsDataStore.automationEnabledFromDisk(),
                rules = settingsDataStore.automationRulesFromDisk(),
                journal = fireJournal.load(),
            ),
        )
    }

    /** GET /api/automation — the state document plus the catalogs the rule editor renders from. */
    suspend fun get(): String {
        val state = stateAdapter.toJson(
            buildState(
                enabled = settingsDataStore.automationEnabledFromDisk(),
                rules = settingsDataStore.automationRulesFromDisk(),
                journal = fireJournal.load(),
            ),
        )
        return state
    }

    private fun buildState(
        enabled: Boolean,
        rules: List<AutomationRule>,
        journal: Map<String, Long>,
    ): AutomationStateDto {
        return AutomationStateDto(
            enabled = enabled,
            rules = rules.map { rule ->
                AutomationRuleDto(
                    id = rule.id ?: "",
                    name = rule.name,
                    enabled = rule.enabled,
                    triggerType = rule.triggerType,
                    minuteOfDay = rule.minuteOfDay,
                    daysMask = rule.daysMask,
                    triggerParams = rule.triggerParams,
                    conditions = rule.conditions.map { condition ->
                        AutomationConditionDto(type = condition.type, params = condition.params)
                    },
                    conditionMode = rule.conditionMode,
                    cooldownSeconds = rule.cooldownSeconds,
                    missedRunPolicy = rule.missedRunPolicy,
                    missedWindowMinutes = rule.missedWindowMinutes,
                    actions = rule.actions.map { action ->
                        AutomationActionDto(type = action.type, params = action.params)
                    },
                    // "Last ran" as clients see it: the journal's occurrence
                    // key decoded to a wall-clock instant (null = never
                    // fired, so the key stays off the wire).
                    lastFiredAt = rule.id?.let(journal::get)
                        ?.let { AutomationSchedulePolicy.decodeOccurrenceKey(it, TimeZone.getDefault()) },
                )
            },
            // The catalogs are device truth, published read-only: the rule
            // editor renders bounds and kinds from them but never sends them
            // back.
            catalog = AutomationActionCatalog.ACTION_TYPES.map { type ->
                AutomationCatalogActionDto(
                    id = type.id,
                    params = type.params.map(::paramDto),
                    catchUp = type.catchUp.name,
                    interruptsStream = type.interruptsStream,
                )
            },
            triggers = AutomationTriggerCatalog.ACTION_TYPES.map { type ->
                AutomationCatalogTriggerDto(
                    id = type.id,
                    params = type.params.map(::paramDto),
                )
            },
            conditions = AutomationConditionCatalog.CONDITIONS.map { type ->
                AutomationCatalogConditionDto(
                    id = type.id,
                    params = type.params.map(::paramDto),
                )
            },
        )
    }

    private fun paramDto(param: AutomationParamSpec) =
        AutomationCatalogParamDto(
            name = param.name,
            kind = param.kind.name,
            min = param.min,
            max = param.max,
            optional = param.optional,
            defaultValue = param.defaultValue,
            autoAtZero = param.autoAtZero,
        )
}
