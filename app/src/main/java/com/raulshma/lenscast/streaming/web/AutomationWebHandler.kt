package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationRuleJson
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import java.util.TimeZone
import com.raulshma.lenscast.streaming.model.AutomationCatalogActionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogConditionDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogParamDto
import com.raulshma.lenscast.streaming.model.AutomationCatalogTriggerDto
import com.raulshma.lenscast.streaming.model.AutomationLimitsDto
import com.raulshma.lenscast.streaming.model.AutomationStateDto
import com.raulshma.lenscast.streaming.model.AutomationUpdateRequestDto

/**
 * /api/automation — JSON-in/JSON-out mapping between the ONE automation rule
 * declaration (the domain [AutomationRule], which IS the wire shape) and the
 * Settings Store's automation surface: the master toggle, the rule list (each
 * decorated with a read-only `lastFiredAt` decoded from the shared fire
 * journal), the three read-only catalogs (actions, triggers, conditions —
 * now carrying their picker `group` and `icon` kinds), and the `limits`
 * block that publishes the editor bounds/defaults/presets from
 * [StreamDefaults]' automation constants — the editor contract as device
 * truth, so clients stop hand-mirroring it.
 *
 * Any authenticated session may GET the document (the /api/settings precedent:
 * viewer reads ride the default gate, only the three admin-only reads are
 * special); writes stay config writes — a viewer session is denied by the role
 * gate and an API token by the token write ladder, exactly like PUT
 * /api/settings. The PUT persists only after
 * [AutomationSchedulePolicy.sanitizeRules] has clamped every rule, dropped
 * every unknown action/condition, folded every unknown trigger to
 * `time_of_day`, reset every smuggled `lastFiredAt`, and minted a UUID for
 * every null/blank id; the store's own codec sanitizes again behind it, so no
 * writer can persist an out-of-bounds rule.
 */
class AutomationWebHandler(
    private val settingsDataStore: SettingsDataStore,
    private val fireJournal: AutomationFireJournal,
) {

    // The rule graph's adapters ride [AutomationRuleJson] — AppJson plus the
    // vocabulary enums' wire-id adapters — so the embedded AutomationRules
    // encode their triggerType/conditionMode/missedRunPolicy as the exact
    // canonical string ids the wire has always carried. Plain AppJson would
    // emit the enums' NAMES here, a wire break against the contract fixture
    // and every persisted document.
    private val stateAdapter by lazy { AutomationRuleJson.moshi.adapter(AutomationStateDto::class.java) }
    private val updateAdapter by lazy { AutomationRuleJson.moshi.adapter(AutomationUpdateRequestDto::class.java) }

    /**
     * PUT /api/automation — persists the master toggle and the rule list
     * wholesale (a rule absent from the document is gone, the settings-section
     * convention). The body decodes straight into the domain rules; a blank
     * (the web editor's new-rule shape) or absent wire id mints the
     * fire-journal UUID right here, at the boundary — the same convention as
     * the in-app save path (the ViewModel mints its drafts too): whatever
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

        val sanitized = AutomationSchedulePolicy.sanitizeRules(
            request.rules.map { rule ->
                rule.copy(
                    id = rule.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                )
            },
        )

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

    companion object {

        /**
         * The one GET-shaped state mapping — shared verbatim by the handler
         * and the contract fixture test (web/contract/automation.json pins
         * THIS function's output, so the checked-in fixture describes the
         * wire by construction, not by a parallel hand mapping).
         *
         * Rules pass through as themselves; the only edit is the
         * [AutomationRule.lastFiredAt] decoration: "last ran" as clients see
         * it, the journal's occurrence key decoded to a wall-clock instant
         * (null = never fired, so the key stays off the wire). Persisted
         * rules always carry minted, non-blank ids (the store's invariant),
         * so a null id never serializes in a response.
         */
        internal fun buildState(
            enabled: Boolean,
            rules: List<AutomationRule>,
            journal: Map<String, Long>,
            zone: TimeZone = TimeZone.getDefault(),
        ): AutomationStateDto {
            return AutomationStateDto(
                enabled = enabled,
                rules = rules.map { rule ->
                    rule.copy(
                        lastFiredAt = rule.id?.let(journal::get)
                            ?.let { AutomationSchedulePolicy.decodeOccurrenceKey(it, zone) },
                    )
                },
                // The editor contract's published bounds: StreamDefaults is
                // the ONE source, the clients consume the document.
                limits = AutomationLimitsDto(
                    maxRules = StreamDefaults.AUTOMATION_MAX_RULES,
                    maxActionsPerRule = StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE,
                    maxConditionsPerRule = StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE,
                    cooldownMinSeconds = StreamDefaults.AUTOMATION_COOLDOWN_MIN_SECONDS,
                    cooldownMaxSeconds = StreamDefaults.AUTOMATION_COOLDOWN_MAX_SECONDS,
                    cooldownDefaultSeconds = StreamDefaults.AUTOMATION_COOLDOWN_DEFAULT_SECONDS,
                    cooldownPresetSeconds = StreamDefaults.AUTOMATION_COOLDOWN_PRESET_SECONDS,
                    missedWindowMinMinutes = StreamDefaults.AUTOMATION_MISSED_WINDOW_MIN_MINUTES,
                    missedWindowMaxMinutes = StreamDefaults.AUTOMATION_MISSED_WINDOW_MAX_MINUTES,
                    missedWindowDefaultMinutes = StreamDefaults.AUTOMATION_MISSED_WINDOW_DEFAULT_MINUTES,
                    missedWindowPresetMinutes = StreamDefaults.AUTOMATION_MISSED_WINDOW_PRESET_MINUTES,
                ),
                // The catalogs are device truth, published read-only: the rule
                // editor renders bounds, groups and icon kinds from them but
                // never sends them back.
                catalog = AutomationActionCatalog.ACTION_TYPES.map { type ->
                    AutomationCatalogActionDto(
                        id = type.id,
                        params = type.params.map(::paramDto),
                        catchUp = type.catchUp.name,
                        interruptsStream = type.interruptsStream,
                        group = type.group,
                        icon = type.icon,
                    )
                },
                triggers = AutomationTriggerCatalog.ACTION_TYPES.map { type ->
                    AutomationCatalogTriggerDto(
                        id = type.id,
                        params = type.params.map(::paramDto),
                        group = type.group,
                        icon = type.icon,
                    )
                },
                conditions = AutomationConditionCatalog.CONDITIONS.map { type ->
                    AutomationCatalogConditionDto(
                        id = type.id,
                        params = type.params.map(::paramDto),
                        group = type.group,
                        icon = type.icon,
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
}
