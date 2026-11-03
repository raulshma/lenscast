package com.raulshma.lenscast.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.ParamKind
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.settings.SettingsSection
import com.raulshma.lenscast.settings.SliderSetting
import com.raulshma.lenscast.settings.SwitchSetting
import com.raulshma.lenscast.ui.components.AutomationTimePickerDialog
import com.raulshma.lenscast.ui.components.DayMaskChips
import com.raulshma.lenscast.ui.components.LensCastTopBar

/**
 * The screen-local editing draft of one rule. The editor mutates this shape
 * freely (name typing, param toggles) and only the committed save becomes an
 * [AutomationRule] — the ViewModel's sanitize pass then clamps it into the
 * persisted shape. [id] is null for a rule the store has never seen.
 */
private data class RuleDraft(
    val id: String?,
    val name: String,
    val enabled: Boolean,
    val minuteOfDay: Int,
    val daysMask: Int,
    val actions: List<AutomationActionSpec>,
) {
    fun toRule(): AutomationRule = AutomationRule(
        id = id,
        name = name,
        enabled = enabled,
        minuteOfDay = minuteOfDay,
        daysMask = daysMask,
        actions = actions,
    )

    companion object {
        /** A fresh rule: 08:00, every day, no actions yet. */
        fun new(): RuleDraft = RuleDraft(
            id = null,
            name = "",
            enabled = true,
            minuteOfDay = DEFAULT_MINUTE_OF_DAY,
            daysMask = MotionArmingPolicy.ALL_DAYS_MASK,
            actions = emptyList(),
        )

        fun from(rule: AutomationRule): RuleDraft = RuleDraft(
            id = rule.id,
            name = rule.name,
            enabled = rule.enabled,
            minuteOfDay = rule.minuteOfDay,
            daysMask = rule.daysMask,
            actions = rule.actions,
        )

        private const val DEFAULT_MINUTE_OF_DAY = 8 * 60
    }
}

/**
 * Fallback auto-sentinel names for action ids the catalog does not list
 * (legacy persisted actions): for every listed id the catalog's
 * [AutomationParamSpec.autoAtZero] is the authority.
 */
private val AUTO_SENTINEL_PARAM_NAMES = setOf("iso", "exposure_time_ms")

/**
 * Whether this param renders the Auto toggle ("0" = the auto sentinel): the
 * catalog's [AutomationParamSpec.autoAtZero] decides for every listed action
 * id; an id missing from the catalog (legacy persisted action) keeps the old
 * name-set behavior so its editors still render sanely.
 */
private fun isAutoSentinelParam(typeId: String, spec: AutomationParamSpec): Boolean =
    AutomationActionCatalog.typeFor(typeId)
        ?.params?.firstOrNull { it.name == spec.name }?.autoAtZero
        ?: (spec.name in AUTO_SENTINEL_PARAM_NAMES)

/**
 * The automation screen: the master engine switch, then one card per rule —
 * name, enable switch, time, day summary and per-action summary lines with
 * edit/delete affordances. Rules stay visible (and editable) while the
 * engine is off; a banner explains they will not fire until it is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val viewModel: AutomationViewModel = viewModel(
        factory = AutomationViewModel.Factory(app.cameraService, app.settingsDataStore)
    )
    val automationEnabled by viewModel.automationEnabled.collectAsState()
    val rules by viewModel.automationRules.collectAsState()
    val canAddRule by viewModel.canAddRule.collectAsState()
    val torchStrengthMaxLevel by viewModel.torchStrengthMaxLevel.collectAsState()

    var editorDraft by remember { mutableStateOf<RuleDraft?>(null) }

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.automation_title),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.automation_title)) {
                    SwitchSetting(
                        title = stringResource(R.string.automation_master_enabled),
                        checked = automationEnabled,
                        onCheckedChange = { viewModel.setEnabled(it) }
                    )
                    if (!automationEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // The engine gate: the schedule poller consults the same
                        // master flag, so an off switch silences every rule.
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = stringResource(R.string.automation_engine_off_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { editorDraft = RuleDraft.new() },
                        enabled = canAddRule,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.automation_add_rule),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (!canAddRule) {
                        Text(
                            text = stringResource(
                                R.string.automation_max_rules_reached,
                                StreamDefaults.AUTOMATION_MAX_RULES,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(rules, key = { it.id ?: "rule-${it.hashCode()}" }) { rule ->
                AutomationRuleCard(
                    rule = rule,
                    onToggleEnabled = { enabled ->
                        rule.id?.let { id -> viewModel.setRuleEnabled(id, enabled) }
                    },
                    onEdit = { editorDraft = RuleDraft.from(rule) },
                    onDelete = { rule.id?.let(viewModel::removeRule) },
                )
            }
        }
    }

    editorDraft?.let { draft ->
        AutomationRuleEditor(
            draft = draft,
            torchStrengthMaxLevel = torchStrengthMaxLevel,
            onDraftChange = { editorDraft = it },
            onDismiss = { editorDraft = null },
            onSave = { saved ->
                if (saved.id == null) viewModel.addRule(saved.toRule()) else viewModel.updateRule(saved.toRule())
                editorDraft = null
            },
        )
    }
}

/**
 * One rule card: name (or the untitled fallback), the HH:mm + day summary,
 * per-action summary lines, the per-rule enable switch and the edit/delete
 * affordances.
 */
@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name.ifBlank { stringResource(R.string.automation_rule_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = ruleScheduleLabel(rule.minuteOfDay, rule.daysMask),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (rule.actions.isEmpty()) {
                Text(
                    text = stringResource(R.string.automation_rule_no_actions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rule.actions.forEach { action ->
                    AutomationViewModel.actionSummaryLines(action.type, action.params).forEach { line ->
                        Text(
                            text = stringResource(line.res, *line.args.toTypedArray()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.automation_edit_rule_cd),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.automation_delete_rule_cd),
                    )
                }
            }
        }
    }
}

/** "HH:mm · Every day" or "HH:mm · Mon, Wed" for a rule card. */
@Composable
private fun ruleScheduleLabel(minuteOfDay: Int, daysMask: Int): String {
    val dayText = if (AutomationViewModel.isEveryDay(daysMask)) {
        stringResource(R.string.automation_days_every_day)
    } else {
        // A plain loop, not joinToString {}: the label resolver is composable.
        val labels = ArrayList<String>()
        for (day in AutomationViewModel.armedDays(daysMask)) labels.add(dayLabel(day))
        labels.joinToString(", ")
    }
    return "${AutomationViewModel.formatMinuteOfDay(minuteOfDay)} · $dayText"
}

@Composable
private fun dayLabel(isoDayIndex: Int): String = when (isoDayIndex) {
    0 -> stringResource(R.string.detection_day_mon)
    1 -> stringResource(R.string.detection_day_tue)
    2 -> stringResource(R.string.detection_day_wed)
    3 -> stringResource(R.string.detection_day_thu)
    4 -> stringResource(R.string.detection_day_fri)
    5 -> stringResource(R.string.detection_day_sat)
    else -> stringResource(R.string.detection_day_sun)
}

/**
 * The rule editor: a full-screen dialog (the content is too tall for an alert)
 * holding the name field, the time picker trigger, the day chips, the rule
 * enable switch, and the action list — each action with its own param editors
 * driven by the catalog's [AutomationParamSpec]s. Sub-dialogs (time picker,
 * action picker) open over it; nothing persists until [onSave].
 */
@Composable
private fun AutomationRuleEditor(
    draft: RuleDraft,
    torchStrengthMaxLevel: Int?,
    onDraftChange: (RuleDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showActionPicker by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.automation_editor_close_cd),
                    )
                }
                Text(
                    text = stringResource(
                        if (draft.id == null) R.string.automation_editor_new_title else R.string.automation_editor_edit_title
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSave(draft) }) {
                    Text(stringResource(R.string.automation_editor_save))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.automation_editor_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.automation_editor_time_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = AutomationViewModel.formatMinuteOfDay(draft.minuteOfDay),
                            modifier = Modifier.padding(start = 4.dp),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                DayMaskChips(
                    daysMask = draft.daysMask,
                    onToggleDay = { isoDay ->
                        onDraftChange(draft.copy(daysMask = MotionArmingPolicy.toggleDay(draft.daysMask, isoDay)))
                    },
                    title = stringResource(R.string.automation_editor_days_title),
                )
                SwitchSetting(
                    title = stringResource(R.string.automation_editor_enabled),
                    checked = draft.enabled,
                    onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                )
                Text(
                    text = stringResource(R.string.automation_editor_actions_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                draft.actions.forEachIndexed { index, action ->
                    AutomationActionEditorCard(
                        action = action,
                        torchStrengthMaxLevel = torchStrengthMaxLevel,
                        onChange = { next ->
                            onDraftChange(
                                draft.copy(actions = draft.actions.toMutableList().also { it[index] = next }),
                            )
                        },
                        onRemove = {
                            onDraftChange(
                                draft.copy(actions = draft.actions.filterIndexed { i, _ -> i != index }),
                            )
                        },
                    )
                }
                OutlinedButton(
                    onClick = { showActionPicker = true },
                    enabled = draft.actions.size < StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.automation_editor_add_action),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showTimePicker) {
        AutomationTimePickerDialog(
            initialMinuteOfDay = draft.minuteOfDay,
            onConfirm = { minute ->
                showTimePicker = false
                onDraftChange(draft.copy(minuteOfDay = minute))
            },
            onDismiss = { showTimePicker = false },
        )
    }
    if (showActionPicker) {
        AutomationActionPickerDialog(
            onPick = { typeId ->
                showActionPicker = false
                onDraftChange(
                    draft.copy(
                        actions = draft.actions +
                            AutomationActionSpec(typeId, AutomationViewModel.defaultActionParams(typeId)),
                    ),
                )
            },
            onDismiss = { showActionPicker = false },
        )
    }
}

/**
 * One action inside the editor: the pretty type label, the stream-interrupt
 * warning for lens switches, and one param editor per catalog param (the
 * torch strength editor only when the device reports multi-level flash).
 */
@Composable
private fun AutomationActionEditorCard(
    action: AutomationActionSpec,
    torchStrengthMaxLevel: Int?,
    onChange: (AutomationActionSpec) -> Unit,
    onRemove: () -> Unit,
) {
    val type = AutomationActionCatalog.typeFor(action.type)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(AutomationViewModel.actionTypeLabelRes(action.type)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.automation_editor_remove_action_cd),
                    )
                }
            }
            if (type?.interruptsStream == true) {
                Text(
                    text = stringResource(R.string.automation_editor_interrupts_stream),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            (type?.params ?: emptyList()).forEach { spec ->
                // Hidden only when the device is KNOWN to be single-level or
                // unsupported; a null verdict (camera not yet bound) keeps the
                // editor on the catalog span.
                if (action.type == "torch" && spec.name == "strength" &&
                    !AutomationViewModel.showsTorchStrengthEditor(torchStrengthMaxLevel)
                ) {
                    return@forEach
                }
                AutomationParamEditor(
                    typeId = action.type,
                    spec = spec,
                    value = action.params[spec.name],
                    torchStrengthMaxLevel = torchStrengthMaxLevel,
                    onChange = { next ->
                        onChange(AutomationViewModel.withActionParam(action, spec.name, next))
                    },
                )
            }
        }
    }
}

/**
 * The param editor for one [AutomationParamSpec]:
 * - BOOL: a switch row; an optional bool (the detection gates) first gets a
 *   compact configure switch, unset = absent param = "leave unchanged".
 * - Auto-sentinel numerics (camera_exposure ISO / exposure time): configure
 *   switch, then an Auto toggle mapping onto "0", then the manual slider.
 * - Other optional numerics (torch strength, durations): configure switch,
 *   then the slider; duration rows note the "0 = no limit" sentinel.
 * - Required numerics: the slider directly.
 */
@Composable
private fun AutomationParamEditor(
    typeId: String,
    spec: AutomationParamSpec,
    value: String?,
    torchStrengthMaxLevel: Int?,
    onChange: (String?) -> Unit,
) {
    val label = stringResource(AutomationViewModel.paramLabelRes(typeId, spec.name))
    when (spec.kind) {
        ParamKind.BOOL -> {
            if (spec.optional) {
                ConfigureSwitchRow(
                    label,
                    configured = value != null,
                    onConfigured = { on -> onChange(if (on) "true" else null) },
                )
                if (value != null) {
                    SwitchSetting(
                        title = stringResource(R.string.automation_param_enabled),
                        checked = value == "true",
                        onCheckedChange = { onChange(if (it) "true" else "false") },
                    )
                }
            } else {
                SwitchSetting(
                    title = label,
                    checked = value != "false",
                    onCheckedChange = { onChange(if (it) "true" else "false") },
                )
            }
        }
        ParamKind.INT, ParamKind.FLOAT -> {
            if (isAutoSentinelParam(typeId, spec)) {
                ConfigureSwitchRow(
                    label,
                    configured = value != null,
                    onConfigured = { configured -> onChange(if (configured) "0" else null) },
                )
                if (value != null) {
                    AutoManualParamEditor(spec = spec, value = value, label = label, onChange = onChange)
                }
            } else if (spec.optional) {
                ConfigureSwitchRow(
                    label,
                    configured = value != null,
                    onConfigured = { configured ->
                        // Configuring seeds the param at its documented floor
                        // (0 = the no-limit sentinel for durations, 1 for strength).
                        onChange(if (configured) canonicalFor(spec, (spec.min ?: 0.0).toFloat()) else null)
                    },
                )
                if (value != null) {
                    if (spec.name == "strength") {
                        TorchStrengthEditor(value = value, torchStrengthMaxLevel, label = label, onChange = onChange)
                    } else {
                        AutomationNumericSlider(
                            spec = spec,
                            rawValue = value,
                            deviceMaxLevel = null,
                            label = label,
                            minOverride = null,
                            onChange = onChange,
                        )
                    }
                    if (spec.name == "duration_seconds") {
                        Text(
                            text = stringResource(R.string.automation_editor_duration_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                AutomationNumericSlider(
                    spec = spec,
                    rawValue = value ?: spec.defaultValue,
                    deviceMaxLevel = null,
                    label = label,
                    minOverride = null,
                    onChange = { onChange(it) },
                )
            }
        }
    }
}

/**
 * The compact optional-param gate: the param label with a switch whose off
 * state removes the param entirely (the executor reads absent optional params
 * as "leave unchanged").
 */
@Composable
private fun ConfigureSwitchRow(
    label: String,
    configured: Boolean,
    onConfigured: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = configured, onCheckedChange = onConfigured)
    }
}

/**
 * The camera_exposure auto-sentinel editor: an Auto switch ("0" = auto) plus
 * the manual slider (floored at 1 — manual 0 would mean auto). The last
 * manual value is remembered so toggling auto off restores it.
 */
@Composable
private fun AutoManualParamEditor(
    spec: AutomationParamSpec,
    value: String,
    label: String,
    onChange: (String?) -> Unit,
) {
    val isAuto = value == "0"
    var lastManual by remember(spec.name) { mutableStateOf<Float?>(null) }
    SwitchSetting(
        title = stringResource(R.string.automation_editor_auto),
        checked = isAuto,
        onCheckedChange = { auto ->
            if (auto) {
                onChange("0")
            } else {
                val manual = lastManual ?: (spec.min ?: 0.0).toFloat().coerceAtLeast(1f)
                onChange(canonicalFor(spec, manual))
            }
        },
    )
    if (!isAuto) {
        AutomationNumericSlider(
            spec = spec,
            rawValue = value,
            deviceMaxLevel = null,
            label = label,
            minOverride = (spec.min ?: 0.0).toFloat().coerceAtLeast(1f),
            onChange = { next ->
                lastManual = next.toFloatOrNull()
                onChange(next)
            },
        )
    }
}

/**
 * The torch strength editor: the slider rides the device's multi-level flash
 * ceiling ([AutomationViewModel.torchStrengthBounds] — the catalog's 1..8
 * display bound capped by the live device verdict), and a strength persisted
 * above the device's ceiling is clamped down the moment the editor opens.
 */
@Composable
private fun TorchStrengthEditor(
    value: String,
    torchStrengthMaxLevel: Int?,
    label: String,
    onChange: (String?) -> Unit,
) {
    val raw = value.toIntOrNull() ?: 1
    val clamped = AutomationViewModel.clampTorchStrength(raw, torchStrengthMaxLevel)
    if (clamped != raw) {
        LaunchedEffect(value, torchStrengthMaxLevel) {
            onChange(AutomationViewModel.canonicalIntValue(clamped.toFloat()))
        }
    }
    AutomationNumericSlider(
        spec = null,
        rawValue = clamped.toString(),
        deviceMaxLevel = torchStrengthMaxLevel,
        label = label,
        minOverride = 1f,
        onChange = { onChange(it) },
    )
}

/**
 * The numeric slider row (the shared settings SliderSetting — title + live
 * monospace value). INT sliders snap to whole numbers via steps; FLOAT values
 * round to one decimal. Canonical strings come from the ViewModel's
 * [AutomationViewModel.canonicalIntValue]/[AutomationViewModel.canonicalFloatValue],
 * so what is shown is what persists.
 */
@Composable
private fun AutomationNumericSlider(
    spec: AutomationParamSpec?,
    rawValue: String,
    deviceMaxLevel: Int?,
    label: String,
    minOverride: Float?,
    onChange: (String) -> Unit,
) {
    val minF = minOverride ?: ((spec?.min ?: 0.0).toFloat())
    val maxF = if (deviceMaxLevel != null) {
        AutomationViewModel.torchStrengthBounds(deviceMaxLevel).last.toFloat()
    } else {
        (spec?.max ?: 100.0).toFloat()
    }
    val isInt = spec == null || spec.kind == ParamKind.INT
    val steps = if (isInt) ((maxF - minF).toInt() - 1).coerceAtLeast(0) else 0
    val current = (rawValue.toFloatOrNull() ?: minF).coerceIn(minF, maxF)
    SliderSetting(
        title = label,
        value = current,
        range = minF..maxF,
        steps = steps,
        onValueChange = { value ->
            onChange(canonicalFor(spec, value))
        },
    )
}

/** Canonical wire string for a slider value per the spec's param kind. */
private fun canonicalFor(spec: AutomationParamSpec?, value: Float): String =
    if (spec == null || spec.kind == ParamKind.INT) {
        AutomationViewModel.canonicalIntValue(value)
    } else {
        AutomationViewModel.canonicalFloatValue(value)
    }

/** The action picker: every catalog type, pretty-labeled, in contract order. */
@Composable
private fun AutomationActionPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_editor_add_action)) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AutomationActionCatalog.ACTION_TYPES.forEach { type ->
                    TextButton(
                        onClick = { onPick(type.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(AutomationViewModel.actionTypeLabelRes(type.id)),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}
