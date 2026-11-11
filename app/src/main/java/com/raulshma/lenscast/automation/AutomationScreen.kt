package com.raulshma.lenscast.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionSpec
import com.raulshma.lenscast.automation.schedule.AutomationParamSpec
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerKind
import com.raulshma.lenscast.automation.schedule.ConditionMode
import com.raulshma.lenscast.automation.schedule.MissedRunPolicy
import com.raulshma.lenscast.automation.schedule.ParamKind
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.settings.FilterChip
import com.raulshma.lenscast.settings.SettingsSection
import com.raulshma.lenscast.settings.SwitchSetting
import com.raulshma.lenscast.ui.components.AutomationTimeField
import com.raulshma.lenscast.ui.components.DayMaskChips
import com.raulshma.lenscast.ui.components.LensCastTopBar
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

// The rule editor's non-UI state — the RuleDraft shape and its rule
// conversion, the rotation-proof Saver, the auto-sentinel verdict, the
// time-window day mask, and the cooldown/window presets — lives in the
// screen's RuleDraft/RuleDraftSaver/RuleEditorState module (same package),
// JVM-tested like the ViewModel companion. This file renders it.

/**
 * The automation screen: the engine card (master switch + rule count), then
 * one card per rule — trigger badge, name, WHEN / ONLY WHEN / THEN summary
 * rows, the last-fired line, and the duplicate/delete overflow menu — or a friendly
 * empty state with the add CTA when no rules exist yet. Rules stay visible
 * (and editable) while the engine is off; a banner explains they will not
 * fire until it is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph
    val viewModel: AutomationViewModel = viewModel(
        factory = AutomationViewModel.Factory(app.cameraService, app.settingsDataStore, app.automationFireJournal)
    )
    val automationEnabled by viewModel.automationEnabled.collectAsState()
    val rules by viewModel.automationRules.collectAsState()
    val canAddRule by viewModel.canAddRule.collectAsState()
    val torchStrengthMaxLevel by viewModel.torchStrengthMaxLevel.collectAsState()
    val lastFiredKeys by viewModel.lastFiredKeys.collectAsState()
    // The "now" anchor for the last-fired buckets: retaken on every journal
    // poll (the screen's own beat), so the Today/Yesterday boundary and the
    // rendered HH:mm stay current instead of frozen into the composition.
    val lastFiredNowMs = remember(lastFiredKeys) { System.currentTimeMillis() }

    var editorDraft by rememberSaveable(stateSaver = RuleDraftSaver) { mutableStateOf<RuleDraft?>(null) }
    var deleteCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteCandidate = rules.firstOrNull { it.id == deleteCandidateId }

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.automation_title),
                onNavigateBack = onNavigateBack,
            )
        },
        floatingActionButton = {
            if (canAddRule) {
                ExtendedFloatingActionButton(
                    onClick = { editorDraft = RuleDraft.new() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.automation_add_rule)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // The extra bottom keeps the last card clear of the FAB.
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.automation_master_section_title)) {
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
                    Text(
                        text = stringResource(
                            R.string.automation_rules_count,
                            rules.size,
                            StreamDefaults.AUTOMATION_MAX_RULES,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            if (rules.isEmpty()) {
                item {
                    AutomationEmptyState(onAddRule = { editorDraft = RuleDraft.new() })
                }
            }

            items(rules, key = { it.id ?: "rule-${it.hashCode()}" }) { rule ->
                val copyName = if (rule.name.isBlank()) {
                    stringResource(R.string.automation_rule_copy)
                } else {
                    stringResource(R.string.automation_rule_copy_of, rule.name)
                }
                AutomationRuleCard(
                    rule = rule,
                    lastFiredKey = rule.id?.let(lastFiredKeys::get),
                    lastFiredNowMs = lastFiredNowMs,
                    onToggleEnabled = { enabled ->
                        rule.id?.let { id -> viewModel.setRuleEnabled(id, enabled) }
                    },
                    onEdit = { editorDraft = RuleDraft.from(rule) },
                    onDuplicate = { viewModel.duplicateRule(rule, copyName) },
                    onDelete = { deleteCandidateId = rule.id },
                )
            }
        }
    }

    deleteCandidate?.let { rule ->
        val displayName = rule.name.ifBlank { stringResource(R.string.automation_rule_unnamed) }
        AlertDialog(
            onDismissRequest = { deleteCandidateId = null },
            title = { Text(stringResource(R.string.automation_delete_confirm_title)) },
            text = { Text(stringResource(R.string.automation_delete_confirm_body, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    rule.id?.let(viewModel::removeRule)
                    deleteCandidateId = null
                }) {
                    Text(
                        text = stringResource(R.string.automation_delete_confirm_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidateId = null }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
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
 * One rule card: the trigger badge, name (or the untitled fallback), the
 * trigger subtitle, WHEN / ONLY WHEN / THEN summary rows, the last-fired
 * line, the per-rule enable switch, and the overflow menu (duplicate /
 * delete-confirm). The whole card is tappable and opens the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    lastFiredKey: Long?,
    lastFiredNowMs: Long,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onEdit,
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            AutomationVisuals.triggerVisual(rule.triggerType.id).icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name.ifBlank { stringResource(R.string.automation_rule_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(AutomationViewModel.triggerTypeLabelRes(rule.triggerType.id)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(modifier = Modifier.height(10.dp))
            // WHEN: the schedule for time rules, the "when …" line for events.
            SummaryRow(
                label = stringResource(R.string.automation_card_when),
                text = whenText(rule),
                tint = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            // ONLY WHEN: one merged line over the conditions.
            if (rule.conditions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                SummaryRow(
                    label = stringResource(R.string.automation_card_only),
                    text = onlyWhenText(rule.conditions, rule.conditionMode.id),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // THEN: the merged action summary (or the missing-actions warning).
            Spacer(modifier = Modifier.height(6.dp))
            SummaryRow(
                label = stringResource(R.string.automation_card_then),
                text = thenText(rule),
                tint = MaterialTheme.colorScheme.tertiary,
                textColor = if (rule.actions.isEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            // IF MISSED: the missed-run policy, only when it deviates from
            // the historical catch-up default (and only for time rules —
            // events cannot miss).
            AutomationViewModel.missedRunSummaryLine(rule)?.let { line ->
                Spacer(modifier = Modifier.height(6.dp))
                SummaryRow(
                    label = stringResource(R.string.automation_card_missed),
                    text = stringResource(line.res, *line.args.toTypedArray()),
                    tint = MaterialTheme.colorScheme.secondary,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The trust signal: when this rule last actually fired (decoded
            // from the journal; omitted when it never has).
            if (lastFiredKey != null) {
                lastFiredText(lastFiredKey, lastFiredNowMs)?.let { text ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The overflow menu: duplicate and delete live behind the kebab —
            // destructive/secondary actions don't compete with the switch.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.automation_rule_menu_cd),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_rule_duplicate_cd)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.automation_delete_rule_cd)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One labeled summary row: a colored WHEN / ONLY WHEN / THEN tag and its text. */
@Composable
private fun SummaryRow(
    label: String,
    text: String,
    tint: Color,
    textColor: Color,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            // A floor, not a fixed width: localized tags ("ТОЛЬКО КОГДА")
            // grow the column instead of clipping.
            modifier = Modifier.widthIn(min = 84.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The empty state: what rules are, and the way to the first one. */
@Composable
private fun AutomationEmptyState(onAddRule: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.automation_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.automation_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddRule) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.automation_add_rule))
            }
        }
    }
}

// ── card text assembly ──

/** Resolve rendered summary lines into one comma-joined string. */
@Composable
private fun resolveLines(lines: List<AutomationSummaryLine>): String {
    // A plain loop, not joinToString {}: its transform lambda is not composable.
    val parts = ArrayList<String>()
    for (line in lines) parts.add(stringResource(line.res, *line.args.toTypedArray()))
    return parts.joinToString(separator = ", ")
}

/** The WHEN row: "HH:mm · days" for time rules, the "when …" line for events. */
@Composable
private fun whenText(rule: AutomationRule): String =
    if (rule.triggerType == AutomationTriggerKind.TIME_OF_DAY) {
        ruleScheduleLabel(rule.minuteOfDay, rule.daysMask)
    } else {
        resolveLines(AutomationViewModel.triggerSummaryLines(rule))
    }

/**
 * The ONLY WHEN row: all conditions joined by the mode's localized joiner.
 * [conditionMode] is the string wire id — either the rule's own enum id
 * (`rule.conditionMode.id`) or the draft's mirrored string, so the card and
 * the editor preview share one joiner.
 */
@Composable
private fun onlyWhenText(conditions: List<AutomationConditionSpec>, conditionMode: String): String {
    val joiner = if (conditionMode == ConditionMode.ANY.id) {
        stringResource(R.string.automation_join_or)
    } else {
        stringResource(R.string.automation_join_and)
    }
    val parts = ArrayList<String>()
    for (condition in conditions) parts.add(conditionText(condition))
    return parts.joinToString(separator = " $joiner ")
}

/** One condition's summary, including the time window's localized day suffix. */
@Composable
private fun conditionText(condition: AutomationConditionSpec): String {
    val summary = resolveLines(AutomationViewModel.conditionSummaryLines(condition))
    if (condition.type == AutomationConditionCatalog.TIME_WINDOW &&
        !AutomationViewModel.isEveryDay(conditionWindowDaysMask(condition))
    ) {
        return "$summary · ${conditionWindowDaysLabel(condition)}"
    }
    return summary
}

/** The THEN row: the merged action summary, or the missing-actions warning. */
@Composable
private fun thenText(rule: AutomationRule): String =
    if (rule.actions.isEmpty()) {
        stringResource(R.string.automation_rule_no_actions)
    } else {
        val parts = ArrayList<String>()
        for (action in rule.actions) {
            parts.add(resolveLines(AutomationViewModel.actionSummaryLines(action.type, action.params)))
        }
        parts.joinToString(separator = ", ")
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

/** "Mon, Wed" over a time-window condition's day mask (composable labels). */
@Composable
private fun conditionWindowDaysLabel(condition: AutomationConditionSpec): String {
    val labels = ArrayList<String>()
    for (day in AutomationViewModel.armedDays(conditionWindowDaysMask(condition))) labels.add(dayLabel(day))
    return labels.joinToString(", ")
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

/** The card's "Last ran …" line for a journal occurrence key, null when undecodable. */
@Composable
private fun lastFiredText(lastFiredKey: Long, nowMs: Long): String? {
    val decoded = remember(lastFiredKey) {
        AutomationSchedulePolicy.decodeOccurrenceKey(lastFiredKey, TimeZone.getDefault())
    } ?: return null
    val label = AutomationViewModel.lastFiredLabel(
        decoded,
        nowMs,
        TimeZone.getDefault(),
    ) ?: return null
    return when (label) {
        is AutomationLastFiredLabel.Today ->
            stringResource(
                R.string.automation_last_fired_today,
                AutomationViewModel.formatMinuteOfDay(label.minuteOfDay),
            )
        is AutomationLastFiredLabel.Yesterday ->
            stringResource(
                R.string.automation_last_fired_yesterday,
                AutomationViewModel.formatMinuteOfDay(label.minuteOfDay),
            )
        is AutomationLastFiredLabel.Earlier -> {
            // java.text's localized MEDIUM date is the minSdk-23 equivalent of
            // DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) — java.time
            // is API 26+ (see GalleryDates for the convention).
            val date = remember(label.epochMillis) {
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(label.epochMillis))
            }
            stringResource(
                R.string.automation_last_fired_on,
                date,
                AutomationViewModel.formatMinuteOfDay(label.minuteOfDay),
            )
        }
    }
}

/**
 * Which catalog picker dialog the rule editor currently shows. Exactly one
 * may be open at a time — the editor's [Picker]-typed state makes the
 * exclusivity structural (the dialogs render from one state value, so
 * opening one closes the others) instead of relying on UI flow. `null` =
 * no picker open.
 */
private enum class Picker {
    /** The THEN action picker (`automation_editor_add_action`). */
    Action,

    /** The WHEN trigger picker (`automation_editor_trigger_title`). */
    Trigger,

    /** The ONLY WHEN condition picker (`automation_editor_add_condition`). */
    Condition,
}

/**
 * The rule editor: a full-screen dialog holding the name, the WHEN trigger
 * (grouped picker + time field / day chips / event params + cooldown
 * presets), the ONLY WHEN conditions and THEN actions (each with reorder and
 * remove affordances), and a live preview of the whole rule. Nothing
 * persists until Save; closing with unsaved edits asks first, and the draft
 * survives rotation through [RuleDraftSaver].
 */
@Composable
private fun AutomationRuleEditor(
    draft: RuleDraft,
    torchStrengthMaxLevel: Int?,
    onDraftChange: (RuleDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit,
) {
    // The editor's four-phase machine: Closed / Open·clean / Open·dirty /
    // Open·dirty·confirming-discard. The draft rides the parent's saveable
    // editorDraft, so the dirty and confirming flags are saveable too —
    // they are the draft's shadow, and a configuration change must never
    // split truth from dirtiness. (Plain remember here reset `modified` on
    // rotation while the draft came back dirty: attemptClose then dismissed
    // silently and destroyed exactly the edits the discard guard exists to
    // protect.) NOT keyed on draft: update() rewrites the draft on every
    // edit, which would reset a remember(draft) flag back to false and make
    // the discard guard unreachable. Reset happens naturally when the editor
    // leaves composition (editorDraft = null).
    var modified by rememberSaveable { mutableStateOf(false) }
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    // The ONE picker slot: the former three show*Picker booleans could only
    // stay exclusive through the UI flow (no dialog offered a path to open a
    // second one); the enum makes that exclusivity structural — one state
    // value selects at most one dialog. Keyed on draft like the booleans
    // were: a new draft identity resets the slot to closed. (showDiscardConfirm
    // stays a separate boolean — it is a modal over the editor, not a picker.)
    var activePicker by rememberSaveable(draft) { mutableStateOf<Picker?>(null) }

    fun update(next: RuleDraft) {
        modified = true
        onDraftChange(next)
    }

    fun attemptClose() {
        if (modified) showDiscardConfirm = true else onDismiss()
    }

    Dialog(
        onDismissRequest = { attemptClose() },
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
                IconButton(onClick = { attemptClose() }) {
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
                // Save stays reachable but gated by the ViewModel's one
                // validation verdict; the reason renders at the THEN section.
                Button(
                    onClick = { onSave(draft) },
                    enabled = AutomationViewModel.isValidDraft(draft.toRule()),
                ) {
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
                    onValueChange = { update(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.automation_editor_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchSetting(
                    title = stringResource(R.string.automation_editor_enabled),
                    checked = draft.enabled,
                    onCheckedChange = { update(draft.copy(enabled = it)) },
                )
                // ── WHEN (the trigger) ──
                SectionHeader(
                    icon = AutomationVisuals.triggerVisual(draft.triggerType).icon,
                    title = stringResource(R.string.automation_editor_trigger_title),
                    tint = MaterialTheme.colorScheme.primary,
                )
                // The whole trigger row is tappable and opens the grouped
                // picker: icon, label, description, and the change hint.
                Surface(
                    onClick = { activePicker = Picker.Trigger },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val visual = AutomationVisuals.triggerVisual(draft.triggerType)
                        Icon(
                            visual.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(AutomationViewModel.triggerTypeLabelRes(draft.triggerType)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(visual.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(R.string.automation_editor_change_trigger),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (draft.isTimeTrigger) {
                    AutomationTimeField(
                        label = stringResource(R.string.automation_editor_time_label),
                        minuteOfDay = draft.minuteOfDay,
                        onPicked = { minute -> update(draft.copy(minuteOfDay = minute)) },
                    )
                    DayMaskChips(
                        daysMask = draft.daysMask,
                        onToggleDay = { isoDay ->
                            update(draft.copy(daysMask = MotionArmingPolicy.toggleDay(draft.daysMask, isoDay)))
                        },
                        title = stringResource(R.string.automation_editor_days_title),
                    )
                    // Event rules render the cooldown instead — the trigger
                    // kinds split their timing knobs: time rules own the
                    // missed-run policy, events the re-fire cooldown.
                    MissedRunEditor(
                        policy = draft.missedRunPolicy,
                        windowMinutes = draft.missedWindowMinutes,
                        onPolicyChange = { update(draft.copy(missedRunPolicy = it)) },
                        onWindowChange = { update(draft.copy(missedWindowMinutes = it)) },
                    )
                } else {
                    // Event triggers render their catalog param editors, then
                    // the re-fire cooldown presets + slider.
                    (AutomationTriggerCatalog.typeFor(draft.triggerType)?.params ?: emptyList()).forEach { spec ->
                        AutomationParamEditor(
                            typeId = draft.triggerType,
                            spec = spec,
                            value = draft.triggerParams[spec.name],
                            torchStrengthMaxLevel = null,
                            onChange = { next ->
                                update(
                                    draft.copy(
                                        triggerParams = AutomationViewModel.withSpecParam(draft.triggerParams, spec.name, next),
                                    ),
                                )
                            },
                            labelFor = AutomationViewModel::triggerParamLabelRes,
                        )
                    }
                    CooldownEditor(
                        cooldownSeconds = draft.cooldownSeconds,
                        onChange = { update(draft.copy(cooldownSeconds = it)) },
                    )
                }
                // ── ONLY WHEN (the preconditions) ──
                SectionHeader(
                    icon = Icons.Default.FilterList,
                    title = stringResource(R.string.automation_editor_conditions_title),
                    tint = MaterialTheme.colorScheme.secondary,
                    count = conditionCountLabel(draft.conditions.size),
                )
                if (draft.conditions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.automation_editor_no_conditions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SwitchSetting(
                        title = stringResource(R.string.automation_editor_match_any),
                        checked = draft.conditionMode == ConditionMode.ANY.id,
                        onCheckedChange = { any ->
                            update(
                                draft.copy(
                                    conditionMode = if (any) {
                                        ConditionMode.ANY.id
                                    } else {
                                        ConditionMode.ALL.id
                                    },
                                ),
                            )
                        },
                    )
                    Text(
                        text = stringResource(R.string.automation_editor_match_any_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                draft.conditions.forEachIndexed { index, condition ->
                    AutomationConditionEditorCard(
                        condition = condition,
                        onChange = { next ->
                            update(
                                draft.copy(conditions = draft.conditions.toMutableList().also { it[index] = next }),
                            )
                        },
                        onRemove = {
                            update(
                                draft.copy(conditions = draft.conditions.filterIndexed { i, _ -> i != index }),
                            )
                        },
                        onMoveUp = {
                            update(draft.copy(conditions = draft.conditions.moved(index, index - 1)))
                        },
                        onMoveDown = {
                            update(draft.copy(conditions = draft.conditions.moved(index, index + 1)))
                        },
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.conditions.lastIndex,
                    )
                }
                OutlinedButton(
                    onClick = { activePicker = Picker.Condition },
                    enabled = draft.conditions.size < StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.automation_editor_add_condition),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // ── THEN (the actions) ──
                SectionHeader(
                    icon = Icons.Default.PlayArrow,
                    title = stringResource(R.string.automation_editor_actions_title),
                    tint = MaterialTheme.colorScheme.tertiary,
                    count = actionCountLabel(draft.actions.size),
                )
                draft.actions.forEachIndexed { index, action ->
                    AutomationActionEditorCard(
                        action = action,
                        torchStrengthMaxLevel = torchStrengthMaxLevel,
                        onChange = { next ->
                            update(
                                draft.copy(actions = draft.actions.toMutableList().also { it[index] = next }),
                            )
                        },
                        onRemove = {
                            update(
                                draft.copy(actions = draft.actions.filterIndexed { i, _ -> i != index }),
                            )
                        },
                        onMoveUp = {
                            update(draft.copy(actions = draft.actions.moved(index, index - 1)))
                        },
                        onMoveDown = {
                            update(draft.copy(actions = draft.actions.moved(index, index + 1)))
                        },
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.actions.lastIndex,
                    )
                }
                OutlinedButton(
                    onClick = { activePicker = Picker.Action },
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
                if (!AutomationViewModel.isValidDraft(draft.toRule())) {
                    // Why Save is disabled — say it where the actions go.
                    Text(
                        text = stringResource(R.string.automation_editor_need_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                AutomationPreviewCard(draft = draft)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.automation_editor_discard_title)) },
            text = { Text(stringResource(R.string.automation_editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDismiss()
                }) {
                    Text(
                        text = stringResource(R.string.automation_editor_discard_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.automation_cancel))
                }
            },
        )
    }

    // The three pickers render from the ONE slot: exactly one branch can be
    // active, so opening one structurally closes the others.
    when (activePicker) {
        Picker.Action -> AutomationOptionPickerDialog(
            title = stringResource(R.string.automation_editor_add_action),
            groups = actionPickerGroups(),
            selectedId = null,
            onPick = { typeId ->
                activePicker = null
                update(
                    draft.copy(
                        actions = draft.actions +
                            AutomationActionSpec(typeId, AutomationViewModel.defaultActionParams(typeId)),
                    ),
                )
            },
            onDismiss = { activePicker = null },
        )
        Picker.Trigger -> AutomationOptionPickerDialog(
            title = stringResource(R.string.automation_editor_trigger_title),
            groups = triggerPickerGroups(),
            selectedId = draft.triggerType,
            onPick = { typeId ->
                activePicker = null
                update(
                    draft.copy(
                        triggerType = typeId,
                        triggerParams = AutomationViewModel.defaultTriggerParams(typeId),
                    ),
                )
            },
            onDismiss = { activePicker = null },
        )
        Picker.Condition -> AutomationOptionPickerDialog(
            title = stringResource(R.string.automation_editor_add_condition),
            groups = conditionPickerGroups(),
            selectedId = null,
            onPick = { typeId ->
                activePicker = null
                update(
                    draft.copy(
                        conditions = draft.conditions +
                            AutomationConditionSpec(typeId, AutomationViewModel.defaultConditionParams(typeId)),
                    ),
                )
            },
            onDismiss = { activePicker = null },
        )
        null -> Unit
    }
}

/** "n / max" caption for a section header, in the shared monospace style. */
@Composable
private fun conditionCountLabel(count: Int): String =
    "${count} / ${StreamDefaults.AUTOMATION_MAX_CONDITIONS_PER_RULE}"

/** [conditionCountLabel] for actions. */
@Composable
private fun actionCountLabel(count: Int): String =
    "${count} / ${StreamDefaults.AUTOMATION_MAX_ACTIONS_PER_RULE}"

/** A colored, iconed section header (WHEN / ONLY WHEN / THEN) with an optional count. */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    tint: Color,
    count: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        count?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The editor's live preview: the whole rule read back as WHEN / ONLY / THEN rows. */
@Composable
private fun AutomationPreviewCard(draft: RuleDraft) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.automation_editor_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryRow(
                label = stringResource(R.string.automation_card_when),
                text = whenText(draft.toRule()),
                tint = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            SummaryRow(
                label = stringResource(R.string.automation_card_only),
                text = if (draft.conditions.isEmpty()) {
                    stringResource(R.string.automation_editor_preview_always)
                } else {
                    onlyWhenText(draft.conditions, draft.conditionMode)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryRow(
                label = stringResource(R.string.automation_card_then),
                text = if (draft.actions.isEmpty()) {
                    stringResource(R.string.automation_editor_need_action)
                } else {
                    thenText(draft.toRule())
                },
                tint = MaterialTheme.colorScheme.tertiary,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            AutomationViewModel.missedRunSummaryLine(draft.toRule())?.let { line ->
                SummaryRow(
                    label = stringResource(R.string.automation_card_missed),
                    text = stringResource(line.res, *line.args.toTypedArray()),
                    tint = MaterialTheme.colorScheme.secondary,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── pickers ──

/** One selectable row in a grouped picker: catalog id, label, description, icon. */
private data class PickerOption(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
)

/** The trigger picker's groups, resolved to localized labels/descriptions. */
@Composable
private fun triggerPickerGroups(): List<Pair<String, List<PickerOption>>> =
    pickerGroups(
        AutomationVisuals.TRIGGER_GROUPS,
        AutomationViewModel::triggerTypeLabelRes,
        AutomationVisuals::triggerVisual,
    )

/** The condition picker's groups, resolved to localized labels/descriptions. */
@Composable
private fun conditionPickerGroups(): List<Pair<String, List<PickerOption>>> =
    pickerGroups(
        AutomationVisuals.CONDITION_GROUPS,
        AutomationViewModel::conditionTypeLabelRes,
        AutomationVisuals::conditionVisual,
    )

/** The action picker's groups, resolved to localized labels/descriptions. */
@Composable
private fun actionPickerGroups(): List<Pair<String, List<PickerOption>>> =
    pickerGroups(
        AutomationVisuals.ACTION_GROUPS,
        AutomationViewModel::actionTypeLabelRes,
        AutomationVisuals::actionVisual,
    )

/** The shared resolver behind the three catalog pickers: one group shape. */
@Composable
private fun pickerGroups(
    groups: List<AutomationVisuals.PickerGroup>,
    labelRes: (String) -> Int,
    visual: (String) -> AutomationVisuals.EntryVisual,
): List<Pair<String, List<PickerOption>>> =
    groups.map { group ->
        stringResource(group.titleRes) to group.ids.map { id ->
            val entry = visual(id)
            PickerOption(
                id = id,
                label = stringResource(labelRes(id)),
                description = stringResource(entry.descriptionRes),
                icon = entry.icon,
            )
        }
    }

/**
 * The grouped catalog picker: one header per group, then rows with the entry
 * icon, its label and one-line description, and a check on the current
 * selection. Replaces the old flat TextButton lists — an id unknown to the
 * visuals still renders (generic look), so device-truth catalogs can grow
 * without this dialog hiding entries.
 */
@Composable
private fun AutomationOptionPickerDialog(
    title: String,
    groups: List<Pair<String, List<PickerOption>>>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                groups.forEach { (groupTitle, options) ->
                    Text(
                        text = groupTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    options.forEach { option ->
                        val selected = option.id == selectedId
                        Surface(
                            onClick = { onPick(option.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    option.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

// ── spec editors (conditions / actions) ──

/**
 * One condition inside the editor: the pretty type label, reorder and remove
 * affordances, and one param editor per catalog param — the time window's
 * bounds render as tappable time fields (not minute sliders) and its day
 * mask as chips.
 */
@Composable
private fun AutomationConditionEditorCard(
    condition: AutomationConditionSpec,
    onChange: (AutomationConditionSpec) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    val type = AutomationConditionCatalog.typeFor(condition.type)
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
                Icon(
                    AutomationVisuals.conditionVisual(condition.type).icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(AutomationViewModel.conditionTypeLabelRes(condition.type)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ReorderButtons(onMoveUp, onMoveDown, canMoveUp, canMoveDown)
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.automation_editor_remove_condition_cd),
                    )
                }
            }
            (type?.params ?: emptyList()).forEach { spec ->
                if (condition.type == AutomationConditionCatalog.TIME_WINDOW && spec.name == "days_mask") {
                    // The day mask renders as chips, not a raw number slider.
                    DayMaskChips(
                        daysMask = conditionWindowDaysMask(condition),
                        onToggleDay = { isoDay ->
                            onChange(
                                AutomationViewModel.withConditionParam(
                                    condition,
                                    "days_mask",
                                    MotionArmingPolicy.toggleDay(conditionWindowDaysMask(condition), isoDay).toString(),
                                ),
                            )
                        },
                        title = stringResource(R.string.automation_editor_days_title),
                    )
                    return@forEach
                }
                if (condition.type == AutomationConditionCatalog.TIME_WINDOW &&
                    (spec.name == "start_minute" || spec.name == "end_minute")
                ) {
                    // The window bounds are wall-clock times now — a tap
                    // opens the time picker instead of dialing raw minutes.
                    AutomationTimeField(
                        label = stringResource(AutomationViewModel.conditionParamLabelRes(condition.type, spec.name)),
                        minuteOfDay = condition.params[spec.name]?.toIntOrNull()
                            ?: if (spec.name == "start_minute") 0 else StreamDefaults.MINUTES_PER_DAY - 1,
                        onPicked = { minute ->
                            onChange(AutomationViewModel.withConditionParam(condition, spec.name, minute.toString()))
                        },
                    )
                    if (spec.name == "end_minute") {
                        // The window wraps midnight when start > end — say so
                        // once, after both bounds have rendered.
                        Text(
                            text = stringResource(R.string.automation_editor_window_wrap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@forEach
                }
                AutomationParamEditor(
                    typeId = condition.type,
                    spec = spec,
                    value = condition.params[spec.name],
                    torchStrengthMaxLevel = null,
                    onChange = { next ->
                        onChange(AutomationViewModel.withConditionParam(condition, spec.name, next))
                    },
                    labelFor = AutomationViewModel::conditionParamLabelRes,
                )
            }
        }
    }
}

/**
 * One action inside the editor: the pretty type label, the stream-interrupt
 * warning for lens switches, reorder and remove affordances, and one param
 * editor per catalog param (the torch strength editor only when the device
 * reports multi-level flash).
 */
@Composable
private fun AutomationActionEditorCard(
    action: AutomationActionSpec,
    torchStrengthMaxLevel: Int?,
    onChange: (AutomationActionSpec) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
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
                Icon(
                    AutomationVisuals.actionVisual(action.type).icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(AutomationViewModel.actionTypeLabelRes(action.type)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                ReorderButtons(onMoveUp, onMoveDown, canMoveUp, canMoveDown)
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

/** The spec card's up/down reorder pair; disabled (not hidden) at the ends. */
@Composable
private fun ReorderButtons(
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.automation_editor_move_up_cd),
        )
    }
    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.automation_editor_move_down_cd),
        )
    }
}

/**
 * The event-rule cooldown editor: preset chips (30 s … 1 h) for the common
 * spans, a snapping slider for anything else, and a humanized readout —
 * replacing the wall of raw seconds. The persisted bound allows a day; the
 * display ceiling stays at an hour (no useful cooldown needs a wider
 * slider), and a value persisted above it renders pinned at the ceiling.
 */
@Composable
private fun CooldownEditor(
    cooldownSeconds: Int,
    onChange: (Int) -> Unit,
) {
    val maxSeconds = AUTOMATION_COOLDOWN_DISPLAY_MAX_SECONDS
    val presets = AUTOMATION_COOLDOWN_PRESET_SECONDS
    val maxF = maxSeconds.toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.automation_editor_cooldown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = humanizedDuration(cooldownSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { seconds ->
                FilterChip(
                    label = humanizedDuration(seconds),
                    selected = cooldownSeconds == seconds,
                    onClick = { onChange(seconds) },
                )
            }
        }
        // 30-second stops: fine enough to be precise, coarse enough to grab.
        val current = cooldownSeconds.coerceIn(0, maxSeconds).toFloat()
        Slider(
            value = current,
            onValueChange = { value ->
                onChange(AutomationViewModel.canonicalIntValue(value).toInt())
            },
            valueRange = 0f..maxF,
            steps = (maxSeconds / 30) - 1,
        )
    }
}

/** A localized humanized duration ("45 s" / "5 min" / "2 h"). */
@Composable
private fun humanizedDuration(seconds: Int): String =
    when (val label = AutomationViewModel.humanizeDuration(seconds)) {
        is AutomationDurationLabel.Seconds ->
            stringResource(R.string.automation_duration_seconds, label.count)
        is AutomationDurationLabel.Minutes ->
            stringResource(R.string.automation_duration_minutes, label.count)
        is AutomationDurationLabel.Hours ->
            stringResource(R.string.automation_duration_hours, label.count)
    }

/**
 * The time-rule missed-run editor: the three policy chips — always catch up
 * (the historical default), skip ("at the scheduled time or not at all"),
 * within a window — and, in window mode, the catch-up preset chips with a
 * humanized readout. The mirror knob of the event rules'
 * [CooldownEditor]: only time rules can miss a run, only events re-fire.
 */
@Composable
private fun MissedRunEditor(
    policy: String,
    windowMinutes: Int,
    onPolicyChange: (String) -> Unit,
    onWindowChange: (Int) -> Unit,
) {
    val presets = AUTOMATION_MISSED_WINDOW_PRESET_MINUTES
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.automation_editor_missed_run),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.automation_editor_missed_run_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                label = stringResource(R.string.automation_missed_catch_up),
                selected = policy == MissedRunPolicy.CATCH_UP.id,
                onClick = { onPolicyChange(MissedRunPolicy.CATCH_UP.id) },
            )
            FilterChip(
                label = stringResource(R.string.automation_missed_skip),
                selected = policy == MissedRunPolicy.SKIP.id,
                onClick = { onPolicyChange(MissedRunPolicy.SKIP.id) },
            )
            FilterChip(
                label = stringResource(R.string.automation_missed_window),
                selected = policy == MissedRunPolicy.WINDOW.id,
                onClick = { onPolicyChange(MissedRunPolicy.WINDOW.id) },
            )
        }
        if (policy == MissedRunPolicy.WINDOW.id) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.automation_editor_missed_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = humanizedDuration(windowMinutes * 60),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { minutes ->
                    FilterChip(
                        label = humanizedDuration(minutes * 60),
                        selected = windowMinutes == minutes,
                        onClick = { onWindowChange(minutes) },
                    )
                }
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
 *
 * [labelFor] selects the label source per catalog (action, trigger, or
 * condition overrides); the editor body is catalog-agnostic.
 */
@Composable
private fun AutomationParamEditor(
    typeId: String,
    spec: AutomationParamSpec,
    value: String?,
    torchStrengthMaxLevel: Int?,
    onChange: (String?) -> Unit,
    labelFor: (typeId: String, paramName: String) -> Int = AutomationViewModel::paramLabelRes,
) {
    val label = stringResource(labelFor(typeId, spec.name))
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
 * The numeric slider row (title + live monospace value). INT sliders snap to
 * whole numbers via steps; FLOAT values round to one decimal. Canonical
 * strings come from the ViewModel's
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = canonicalFor(spec, current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = current,
            onValueChange = { value ->
                onChange(canonicalFor(spec, value))
            },
            valueRange = minF..maxF,
            steps = steps,
        )
    }
}

/** Canonical wire string for a slider value per the spec's param kind. */
private fun canonicalFor(spec: AutomationParamSpec?, value: Float): String =
    if (spec == null || spec.kind == ParamKind.INT) {
        AutomationViewModel.canonicalIntValue(value)
    } else {
        AutomationViewModel.canonicalFloatValue(value)
    }
