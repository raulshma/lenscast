package com.raulshma.lenscast.automation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One rendered action-summary line: the string resource plus its positional
 * string args. The pure half of [AutomationViewModel.actionSummaryLines] —
 * the screen resolves it with `stringResource(res, *args.toTypedArray())`,
 * JVM tests assert the resource ids and args without touching Android.
 */
data class AutomationSummaryLine(
    @StringRes val res: Int,
    val args: List<String> = emptyList(),
)

/**
 * Write-side ViewModel for the automation (schedule-rules) screen. Persisted
 * values are exposed straight from the Settings Store — the store is the
 * single source of truth and there is no mirror state to drift, the same
 * contract as [com.raulshma.lenscast.settings.SettingsViewModel]. The only
 * derived read is [canAddRule], so the screen can grey the add button out at
 * [StreamDefaults.AUTOMATION_MAX_RULES].
 *
 * Every write funnels through [AutomationSchedulePolicy.sanitizeRules] before
 * it reaches the store, so an edit is clamped into the persisted/usable shape
 * (name trimmed, minute/mask bounds, action list sanitized and capped) with
 * no second gate to bypass. Pure rule-list transforms and the display-string
 * builders live in the [Companion] and are unit-tested directly.
 */
class AutomationViewModel(
    private val cameraService: CameraService,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    // Read-side state straight from the Settings Store — no per-ViewModel
    // stateIn, no retyped defaults.
    val automationEnabled: StateFlow<Boolean> = settingsDataStore.automationEnabled
    val automationRules: StateFlow<List<AutomationRule>> = settingsDataStore.automationRules

    /** The bound camera's multi-level flash ceiling (null = no levels / unsupported). */
    val torchStrengthMaxLevel: StateFlow<Int?> = cameraService.torchStrengthMaxLevel

    /** False once the rule list is at the cap — the screen greys the add button out. */
    val canAddRule: StateFlow<Boolean> = settingsDataStore.automationRules
        .map { it.size < StreamDefaults.AUTOMATION_MAX_RULES }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEnabled(enabled: Boolean) = save { settingsDataStore.saveAutomationEnabled(enabled) }

    fun addRule(rule: AutomationRule) =
        persistRules(withRuleAdded(settingsDataStore.automationRules.value, rule))

    fun updateRule(rule: AutomationRule) =
        persistRules(withRuleUpdated(settingsDataStore.automationRules.value, rule))

    fun removeRule(ruleId: String) =
        persistRules(withRuleRemoved(settingsDataStore.automationRules.value, ruleId))

    fun setRuleEnabled(ruleId: String, enabled: Boolean) =
        persistRules(withRuleEnabled(settingsDataStore.automationRules.value, ruleId, enabled))

    /** The single persist seam: every rules write sanitizes, then saves. */
    private fun persistRules(rules: List<AutomationRule>) = save {
        settingsDataStore.saveAutomationRules(AutomationSchedulePolicy.sanitizeRules(rules))
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    class Factory(
        private val cameraService: CameraService,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AutomationViewModel(cameraService, settingsDataStore) as T
        }
    }

    companion object {

        // ── pure rule-list transforms (JVM-tested) ──

        /**
         * Append [rule] unless the list is already at [StreamDefaults.AUTOMATION_MAX_RULES].
         * An id-less rule (a fresh draft) gets a minted UUID — a rule without
         * an id could never be journaled by the schedule engine.
         */
        fun withRuleAdded(rules: List<AutomationRule>, rule: AutomationRule): List<AutomationRule> =
            if (rules.size >= StreamDefaults.AUTOMATION_MAX_RULES) rules
            else rules + rule.copy(id = rule.id ?: UUID.randomUUID().toString())

        /** Replace the entry with [rule]'s id; unknown or id-less rules are no-ops. */
        fun withRuleUpdated(rules: List<AutomationRule>, rule: AutomationRule): List<AutomationRule> {
            val id = rule.id ?: return rules
            if (rules.none { it.id == id }) return rules
            return rules.map { if (it.id == id) rule else it }
        }

        fun withRuleRemoved(rules: List<AutomationRule>, ruleId: String): List<AutomationRule> =
            rules.filterNot { it.id == ruleId }

        fun withRuleEnabled(
            rules: List<AutomationRule>,
            ruleId: String,
            enabled: Boolean,
        ): List<AutomationRule> = rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }

        /**
         * One canonical param write on an action spec: a null/empty value
         * removes the key (absent optional param = "leave unchanged"), any
         * other value is stored verbatim — callers pass already-canonical
         * strings from [canonicalIntValue]/[canonicalFloatValue].
         */
        fun withActionParam(
            action: AutomationActionSpec,
            name: String,
            value: String?,
        ): AutomationActionSpec {
            val params = action.params.toMutableMap()
            if (value.isNullOrEmpty()) params.remove(name) else params[name] = value
            return action.copy(params = params)
        }

        /**
         * The params a freshly picked action starts with: every required param
         * at its catalog default, optional ones absent (their absence IS the
         * "leave unchanged" semantics the executor honors).
         */
        fun defaultActionParams(typeId: String): Map<String, String> =
            AutomationActionCatalog.typeFor(typeId)?.params
                ?.filterNot { it.optional }
                ?.associate { it.name to it.defaultValue }
                ?: emptyMap()

        /**
         * INT slider value → canonical wire string (whole number, no
         * decimals). Delegates to the catalog's canonical INT rendering —
         * one renderer, so what the editor shows is what the sanitizer
         * accepts. A slider only produces finite floats, so the catalog's
         * null (unparseable/non-finite raw) cannot occur.
         */
        fun canonicalIntValue(value: Float): String =
            requireNotNull(AutomationActionCatalog.canonicalIntValue(value.toString())) {
                "slider INT values are finite: $value"
            }.toString()

        /**
         * FLOAT slider value → canonical wire string: rounded to one decimal,
         * integral values losing the trailing ".0" (so a saved "1" matches the
         * documented default "1"). Delegates to the catalog's canonical FLOAT
         * rendering for the same single-renderer reason as [canonicalIntValue].
         */
        fun canonicalFloatValue(value: Float): String =
            requireNotNull(AutomationActionCatalog.canonicalFloatValue(value.toString())) {
                "slider FLOAT values are finite: $value"
            }

        /**
         * The catalog's display ceiling for torch strength: the torch action's
         * strength param max. The catalog owns the bound — a missing or
         * unbounded torch.strength is a contract break the catalog test
         * catches, not something to paper over with a magic literal here.
         */
        private val torchStrengthCatalogMax: Int =
            requireNotNull(
                AutomationActionCatalog.typeFor("torch")
                    ?.params?.firstOrNull { it.name == "strength" }?.max?.toInt(),
            ) { "catalog must declare a bounded torch.strength max" }

        /**
         * The torch strength slider's 1..max span: the device's multi-level
         * flash ceiling capped by the catalog's display bound. With no device
         * verdict yet the full catalog span shows.
         */
        fun torchStrengthBounds(deviceMaxLevel: Int?): IntRange =
            1..minOf(torchStrengthCatalogMax, deviceMaxLevel ?: torchStrengthCatalogMax).coerceAtLeast(1)

        /**
         * Whether the strength editor renders at all: only a KNOWN verdict of
         * unsupported/single-level (max <= 1) hides it — a null verdict means
         * the camera has not reported yet, so the editor shows and rides the
         * catalog span from [torchStrengthBounds] until the device answers.
         */
        fun showsTorchStrengthEditor(deviceMaxLevel: Int?): Boolean =
            deviceMaxLevel == null || deviceMaxLevel > 1

        fun clampTorchStrength(value: Int, deviceMaxLevel: Int?): Int =
            value.coerceIn(torchStrengthBounds(deviceMaxLevel))

        // ── display helpers (JVM-tested) ──

        /** "HH:mm" over a 24-hour clock from a minute-of-day, clamped into 0..1439. */
        fun formatMinuteOfDay(minuteOfDay: Int): String {
            val minute = minuteOfDay.coerceIn(0, StreamDefaults.MINUTES_PER_DAY - 1)
            return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60)
        }

        fun isEveryDay(daysMask: Int): Boolean = daysMask == MotionArmingPolicy.ALL_DAYS_MASK

        /** The armed ISO day indices (0 = Monday … 6 = Sunday) in a day mask. */
        fun armedDays(daysMask: Int): List<Int> =
            (0..6).filter { MotionArmingPolicy.isArmedDay(daysMask, it) }

        // ── action display strings (id → resource, JVM-tested) ──

        /** Action id → pretty picker/editor label; unknown ids fall to the unknown line. */
        private val ACTION_TYPE_LABELS: Map<String, Int> = mapOf(
            "camera_exposure" to R.string.automation_action_camera_exposure,
            "camera_ev" to R.string.automation_action_camera_ev,
            "camera_zoom" to R.string.automation_action_camera_zoom,
            "camera_fps" to R.string.automation_action_camera_fps,
            "camera_lens" to R.string.automation_action_camera_lens,
            "torch" to R.string.automation_action_torch,
            "stream" to R.string.automation_action_stream,
            "capture_photo" to R.string.automation_action_capture_photo,
            "recording" to R.string.automation_action_recording,
            "siren" to R.string.automation_action_siren,
            "detection" to R.string.automation_action_detection,
        )

        /**
         * Param name → editor row label. Two names read differently per action
         * type ("index" is a lens index or an EV step; "enabled" names a
         * different device per action), so those carry "typeId/paramName"
         * entries in [PARAM_LABEL_OVERRIDES] that win over the bare-name fall
         * back here.
         */
        private val PARAM_LABELS: Map<String, Int> = mapOf(
            "iso" to R.string.automation_param_iso,
            "exposure_time_ms" to R.string.automation_param_exposure_time_ms,
            "index" to R.string.automation_param_ev_index,
            "ratio" to R.string.automation_param_zoom_ratio,
            "fps" to R.string.automation_param_fps,
            "strength" to R.string.automation_param_strength,
            "duration_seconds" to R.string.automation_param_duration_seconds,
            "motion_enabled" to R.string.automation_param_motion,
            "sound_enabled" to R.string.automation_param_sound,
            "enabled" to R.string.automation_param_enabled,
        )

        /** Type-specific param-label overrides, keyed "typeId/paramName". */
        private val PARAM_LABEL_OVERRIDES: Map<String, Int> = mapOf(
            "camera_lens/index" to R.string.automation_param_lens_index,
            "torch/enabled" to R.string.automation_param_torch,
            "stream/enabled" to R.string.automation_param_stream,
            "recording/enabled" to R.string.automation_param_recording,
            "siren/enabled" to R.string.automation_param_siren,
        )

        /** The pretty picker/editor label for one catalog action id. */
        fun actionTypeLabelRes(typeId: String): Int =
            ACTION_TYPE_LABELS[typeId] ?: R.string.automation_action_unknown

        /** The editor row label for one catalog param. */
        fun paramLabelRes(typeId: String, paramName: String): Int =
            PARAM_LABEL_OVERRIDES["$typeId/$paramName"]
                ?: PARAM_LABELS[paramName]
                ?: R.string.automation_param_enabled

        /**
         * The rule-card summary lines for one action, built purely from the
         * catalog id and its params — the screen resolves them through
         * resources; tests pin the ids. One line per meaningful setting (an
         * exposure action with both ISO and exposure time renders two lines;
         * a disabled torch folds its strength away).
         */
        fun actionSummaryLines(type: String, params: Map<String, String>): List<AutomationSummaryLine> {
            val lines = ArrayList<AutomationSummaryLine>()
            when (type) {
                "camera_exposure" -> {
                    // "0" is the auto sentinel for both params (catalog contract).
                    params["iso"]?.let { raw ->
                        if (raw == "0") lines.add(AutomationSummaryLine(R.string.automation_summary_iso_auto))
                        else lines.add(AutomationSummaryLine(R.string.automation_summary_iso, listOf(raw)))
                    }
                    params["exposure_time_ms"]?.let { raw ->
                        if (raw == "0") lines.add(AutomationSummaryLine(R.string.automation_summary_exposure_auto))
                        else lines.add(AutomationSummaryLine(R.string.automation_summary_exposure, listOf(raw)))
                    }
                }
                "camera_ev" -> params["index"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_ev, listOf(it)))
                }
                "camera_zoom" -> params["ratio"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_zoom, listOf(it)))
                }
                "camera_fps" -> params["fps"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_fps, listOf(it)))
                }
                "camera_lens" -> params["index"]?.let {
                    lines.add(AutomationSummaryLine(R.string.automation_summary_lens, listOf(it)))
                }
                "torch" -> {
                    if (params["enabled"] != "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_torch_on))
                        params["strength"]?.let {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_torch_strength, listOf(it)))
                        }
                    } else {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_torch_off))
                    }
                }
                "stream" -> lines.add(
                    if (params["enabled"] != "false") {
                        AutomationSummaryLine(R.string.automation_summary_stream_start)
                    } else {
                        AutomationSummaryLine(R.string.automation_summary_stream_stop)
                    },
                )
                "capture_photo" -> lines.add(AutomationSummaryLine(R.string.automation_summary_photo))
                "recording" -> {
                    if (params["enabled"] == "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_recording_stop))
                    } else {
                        // "0" (or absent) = unbounded — renders as a plain start.
                        val duration = params["duration_seconds"]?.toIntOrNull() ?: 0
                        if (duration > 0) {
                            lines.add(
                                AutomationSummaryLine(R.string.automation_summary_recording_duration, listOf(duration.toString())),
                            )
                        } else {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_recording_start))
                        }
                    }
                }
                "siren" -> {
                    if (params["enabled"] == "false") {
                        lines.add(AutomationSummaryLine(R.string.automation_summary_siren_stop))
                    } else {
                        val duration = params["duration_seconds"]?.toIntOrNull() ?: 0
                        if (duration > 0) {
                            lines.add(
                                AutomationSummaryLine(R.string.automation_summary_siren_duration, listOf(duration.toString())),
                            )
                        } else {
                            lines.add(AutomationSummaryLine(R.string.automation_summary_siren))
                        }
                    }
                }
                "detection" -> {
                    params["motion_enabled"]?.let { raw ->
                        lines.add(
                            if (raw == "true") {
                                AutomationSummaryLine(R.string.automation_summary_motion_on)
                            } else {
                                AutomationSummaryLine(R.string.automation_summary_motion_off)
                            },
                        )
                    }
                    params["sound_enabled"]?.let { raw ->
                        lines.add(
                            if (raw == "true") {
                                AutomationSummaryLine(R.string.automation_summary_sound_on)
                            } else {
                                AutomationSummaryLine(R.string.automation_summary_sound_off)
                            },
                        )
                    }
                }
                else -> lines.add(AutomationSummaryLine(R.string.automation_action_unknown))
            }
            return lines
        }
    }
}
