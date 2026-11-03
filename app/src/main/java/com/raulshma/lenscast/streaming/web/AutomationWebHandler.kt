package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.AutomationActionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogActionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogParamDto
import com.raulshma.lenscast.streaming.model.AutomationRuleDto
import com.raulshma.lenscast.streaming.model.AutomationStateDto
import com.raulshma.lenscast.streaming.model.AutomationUpdateRequestDto

/**
 * /api/automation — JSON-in/JSON-out mapping between the Web API DTOs and the
 * Settings Store's automation surface: the master toggle, the time-of-day rule
 * list, and the read-only action catalog.
 *
 * Any authenticated session may GET the document (the /api/settings precedent:
 * viewer reads ride the default gate, only the three admin-only reads are
 * special); writes stay config writes — a viewer session is denied by the role
 * gate and an API token by the token write ladder, exactly like PUT
 * /api/settings. The PUT persists only after
 * [AutomationSchedulePolicy.sanitizeRules] has clamped every rule and dropped
 * every unknown action; the store's own codec sanitizes again behind it, so no
 * writer can persist an out-of-bounds rule.
 */
class AutomationWebHandler(
    private val settingsDataStore: SettingsDataStore,
) {

    private val stateAdapter by lazy { AppJson.moshi.adapter(AutomationStateDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(AutomationUpdateRequestDto::class.java) }

    /** GET /api/automation — the state document plus the catalog the rule editor renders from. */
    suspend fun get(): String = stateAdapter.toJson(buildState())

    /**
     * PUT /api/automation — persists the master toggle and the rule list
     * wholesale (a rule absent from the document is gone, the settings-section
     * convention). Rules map back to the domain with a blank wire id folding
     * to null so the store's decode mints the fire-journal UUID, then pass
     * through [AutomationSchedulePolicy.sanitizeRules] before the savers —
     * the response is a fresh read of the store's flows, so the client sees
     * the sanitized truth (clamped minutes/masks, canonical action params,
     * minted ids) rather than what it sent.
     */
    suspend fun put(body: String): String {
        val request = updateAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid automation JSON")

        val rules = request.rules.map { dto ->
            AutomationRule(
                id = dto.id.takeIf { it.isNotBlank() },
                name = dto.name,
                enabled = dto.enabled,
                triggerType = AutomationRule.TRIGGER_TIME_OF_DAY,
                minuteOfDay = dto.minuteOfDay,
                daysMask = dto.daysMask,
                actions = dto.actions.map { action ->
                    AutomationActionSpec(type = action.type, params = action.params)
                },
            )
        }
        val sanitized = AutomationSchedulePolicy.sanitizeRules(rules)

        settingsDataStore.saveAutomationEnabled(request.enabled)
        settingsDataStore.saveAutomationRules(sanitized)

        return stateAdapter.toJson(buildState())
    }

    private fun buildState(): AutomationStateDto {
        val store = settingsDataStore
        return AutomationStateDto(
            enabled = store.automationEnabled.value,
            rules = store.automationRules.value.map { rule ->
                AutomationRuleDto(
                    id = rule.id ?: "",
                    name = rule.name,
                    enabled = rule.enabled,
                    minuteOfDay = rule.minuteOfDay,
                    daysMask = rule.daysMask,
                    actions = rule.actions.map { action ->
                        AutomationActionDto(type = action.type, params = action.params)
                    },
                )
            },
            // The catalog is device truth, published read-only: the rule
            // editor renders bounds and kinds from it but never sends it back.
            catalog = AutomationActionCatalog.ACTION_TYPES.map { type ->
                AutomationCatalogActionDto(
                    id = type.id,
                    params = type.params.map { param ->
                        AutomationCatalogParamDto(
                            name = param.name,
                            kind = param.kind.name,
                            min = param.min,
                            max = param.max,
                            optional = param.optional,
                            defaultValue = param.defaultValue,
                            autoAtZero = param.autoAtZero,
                        )
                    },
                    catchUp = type.catchUp.name,
                    interruptsStream = type.interruptsStream,
                )
            },
        )
    }
}
