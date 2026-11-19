package com.raulshma.lenscast.companion.ui.tabs.system

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.dto.AuthSessionEntry
import com.raulshma.lenscast.companion.client.dto.LensInfo
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.RemoteAuditEntry
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.client.dto.SystemInfo

/**
 * The system tab's stateless cards — every card renders pure facts off its
 * state block and reports gestures through the [SystemTabActions] lambdas;
 * no client, no state owner, no side effects (the Robolectric render test
 * composes them through [SystemTabContent] with seeded state). Modifier-
 * first everywhere, stringResource everywhere.
 */

/** Bounded stepper ranges + steps, mirroring the device's own save clamps. */
private val PostRollRange = 0..120
private val CooldownRange = 1..300
private val ArmMinuteRange = 0..1_439
private const val ARM_MINUTE_STEP = 15
private val RetentionDaysRange = 0..365
private val StorageQuotaRange = 100..32_768
private const val STORAGE_QUOTA_STEP = 100
private const val PERCENT_MAX = 100

// ── Device info card ──

/**
 * The diagnostics snapshot: identity (version/model/Android), uptime, the
 * battery detail, the storage summary, and the lens list with the active
 * lens marked — exactly what GET /api/system + GET /api/camera/lenses
 * answered.
 */
@Composable
internal fun SystemInfoCard(
    info: SystemInfo,
    lenses: LensesResponse?,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.companion_system_info_title), modifier = modifier) {
        InfoRow(stringResource(R.string.companion_system_info_app_version), info.appVersion)
        InfoRow(
            stringResource(R.string.companion_system_info_model),
            "${info.deviceManufacturer} ${info.deviceModel}",
        )
        InfoRow(
            stringResource(R.string.companion_system_info_android),
            "${info.androidVersion} (API ${info.sdkInt})",
        )
        InfoRow(
            stringResource(R.string.companion_system_info_uptime),
            DateUtils.formatElapsedTime(info.osUptimeMs / 1000),
        )
        val batteryLine = buildString {
            append(stringResource(R.string.companion_system_info_battery_percent, info.battery.level))
            if (info.battery.isCharging) {
                append(" · ")
                append(stringResource(R.string.companion_system_info_battery_charging))
            }
            info.battery.temperatureTenthsC?.let { tenths ->
                append(" · ")
                append(tenths / 10.0)
                append("°C")
            }
        }
        InfoRow(stringResource(R.string.companion_system_info_battery), batteryLine)
        val context = LocalContext.current
        InfoRow(
            stringResource(R.string.companion_system_info_storage),
            stringResource(
                R.string.companion_system_info_storage_used_of,
                Formatter.formatFileSize(context, info.storage.usedBytes),
                Formatter.formatFileSize(context, info.storage.totalBytes),
            ),
        )
        val lensList = lenses?.lenses.orEmpty()
        if (lensList.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.companion_system_info_lenses),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lensList.forEach { lens -> LensRow(lens) }
        }
    }
}

/** One lens row: label, focal length, and the Active badge when selected. */
@Composable
private fun LensRow(lens: LensInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = lens.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${lens.focalLength}mm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lens.selected) {
            Spacer(modifier = Modifier.width(8.dp))
            ActiveBadge()
        }
    }
}

/** The Active badge on the selected lens row. */
@Composable
private fun ActiveBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = stringResource(R.string.companion_system_info_lens_active),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── Settings editor card ──

/**
 * The detection/capture settings editor. [canEdit] false (viewer) renders
 * the same values read-only with the viewer banner and no save/revert row;
 * the save row only exists for an admin with a dirty draft. All controls are
 * disabled while a save round is in flight.
 */
@Composable
internal fun SettingsCard(
    shown: StreamingSettings,
    canEdit: Boolean,
    saving: Boolean,
    dirty: Boolean,
    onEdit: (transform: (StreamingSettings) -> StreamingSettings) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = canEdit && !saving
    SectionCard(
        title = stringResource(R.string.companion_system_settings_title),
        modifier = modifier,
        headerTail = {
            if (!canEdit) {
                Text(
                    text = stringResource(R.string.companion_system_settings_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        GroupLabel(stringResource(R.string.companion_system_group_motion))
        SwitchRow(
            label = stringResource(R.string.companion_system_motion_enabled),
            checked = shown.motionDetectionEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(motionDetectionEnabled = !it.motionDetectionEnabled) } },
        )
        SliderRow(
            label = stringResource(R.string.companion_system_motion_sensitivity),
            value = shown.motionSensitivityPercent,
            valueRange = 1f..PERCENT_MAX.toFloat(),
            enabled = enabled,
            onValue = { percent -> onEdit { it.copy(motionSensitivityPercent = percent) } },
        )
        SwitchRow(
            label = stringResource(R.string.companion_system_motion_recording),
            checked = shown.motionRecordingEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(motionRecordingEnabled = !it.motionRecordingEnabled) } },
        )
        StepperRow(
            label = stringResource(R.string.companion_system_motion_post_roll),
            value = shown.motionPostRollSeconds,
            range = PostRollRange,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(motionPostRollSeconds = value) } },
        )
        StepperRow(
            label = stringResource(R.string.companion_system_motion_cooldown),
            value = shown.motionCooldownSeconds,
            range = CooldownRange,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(motionCooldownSeconds = value) } },
        )
        SwitchRow(
            label = stringResource(R.string.companion_system_motion_arm),
            checked = shown.motionArmScheduleEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(motionArmScheduleEnabled = !it.motionArmScheduleEnabled) } },
        )
        if (shown.motionArmScheduleEnabled) {
            StepperRow(
                label = stringResource(R.string.companion_system_motion_arm_start),
                value = shown.motionArmStartMinute,
                range = ArmMinuteRange,
                step = ARM_MINUTE_STEP,
                enabled = enabled,
                onChange = { value -> onEdit { it.copy(motionArmStartMinute = value) } },
            )
            StepperRow(
                label = stringResource(R.string.companion_system_motion_arm_end),
                value = shown.motionArmEndMinute,
                range = ArmMinuteRange,
                step = ARM_MINUTE_STEP,
                enabled = enabled,
                onChange = { value -> onEdit { it.copy(motionArmEndMinute = value) } },
            )
            DayChipsRow(
                label = stringResource(R.string.companion_system_motion_arm_days),
                mask = shown.motionArmDaysMask,
                enabled = enabled,
                onMask = { mask -> onEdit { it.copy(motionArmDaysMask = mask) } },
            )
        }

        GroupLabel(stringResource(R.string.companion_system_group_sound))
        SwitchRow(
            label = stringResource(R.string.companion_system_sound_enabled),
            checked = shown.soundDetectionEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(soundDetectionEnabled = !it.soundDetectionEnabled) } },
        )
        SliderRow(
            label = stringResource(R.string.companion_system_sound_threshold),
            value = shown.soundThresholdPercent,
            valueRange = 0f..PERCENT_MAX.toFloat(),
            enabled = enabled,
            onValue = { percent -> onEdit { it.copy(soundThresholdPercent = percent) } },
        )
        SwitchRow(
            label = stringResource(R.string.companion_system_sound_adaptive),
            checked = shown.soundAdaptiveNoiseFloor,
            enabled = enabled,
            onChecked = { onEdit { it.copy(soundAdaptiveNoiseFloor = !it.soundAdaptiveNoiseFloor) } },
        )
        SwitchRow(
            label = stringResource(R.string.companion_system_sound_recording),
            checked = shown.soundRecordingEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(soundRecordingEnabled = !it.soundRecordingEnabled) } },
        )
        StepperRow(
            label = stringResource(R.string.companion_system_sound_cooldown),
            value = shown.soundCooldownSeconds,
            range = CooldownRange,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(soundCooldownSeconds = value) } },
        )

        GroupLabel(stringResource(R.string.companion_system_group_ml))
        SwitchRow(
            label = stringResource(R.string.companion_system_ml_enabled),
            checked = shown.mlDetectionEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(mlDetectionEnabled = !it.mlDetectionEnabled) } },
        )
        if (shown.mlDetectionEnabled) {
            SliderRow(
                label = stringResource(R.string.companion_system_ml_min_score),
                value = shown.mlMinScorePercent,
                valueRange = 0f..PERCENT_MAX.toFloat(),
                enabled = enabled,
                onValue = { percent -> onEdit { it.copy(mlMinScorePercent = percent) } },
            )
            SwitchRow(
                label = stringResource(R.string.companion_system_ml_person),
                checked = shown.mlIncludePerson,
                enabled = enabled,
                onChecked = { onEdit { it.copy(mlIncludePerson = !it.mlIncludePerson) } },
            )
            SwitchRow(
                label = stringResource(R.string.companion_system_ml_pets),
                checked = shown.mlIncludePets,
                enabled = enabled,
                onChecked = { onEdit { it.copy(mlIncludePets = !it.mlIncludePets) } },
            )
            SwitchRow(
                label = stringResource(R.string.companion_system_ml_vehicles),
                checked = shown.mlIncludeVehicles,
                enabled = enabled,
                onChecked = { onEdit { it.copy(mlIncludeVehicles = !it.mlIncludeVehicles) } },
            )
        }

        GroupLabel(stringResource(R.string.companion_system_group_tamper))
        SwitchRow(
            label = stringResource(R.string.companion_system_tamper_enabled),
            checked = shown.tamperDetectionEnabled,
            enabled = enabled,
            onChecked = { onEdit { it.copy(tamperDetectionEnabled = !it.tamperDetectionEnabled) } },
        )

        GroupLabel(stringResource(R.string.companion_system_group_storage))
        StepperRow(
            label = stringResource(R.string.companion_system_capture_retention),
            value = shown.captureRetentionDays,
            range = RetentionDaysRange,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(captureRetentionDays = value) } },
        )
        StepperRow(
            label = stringResource(R.string.companion_system_event_retention),
            value = shown.eventRetentionDays,
            range = RetentionDaysRange,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(eventRetentionDays = value) } },
        )
        StepperRow(
            label = stringResource(R.string.companion_system_storage_quota),
            value = shown.storageQuotaMb,
            range = StorageQuotaRange,
            step = STORAGE_QUOTA_STEP,
            enabled = enabled,
            onChange = { value -> onEdit { it.copy(storageQuotaMb = value) } },
        )

        // The save row exists only for an admin with unsaved edits (viewer
        // sessions render the read-only banner instead; nothing dirty means
        // there is nothing to save).
        if (canEdit && (dirty || saving)) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) {
                    Text(
                        text = stringResource(R.string.companion_system_settings_saving),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(onClick = onRevert) {
                        Text(stringResource(R.string.companion_system_settings_revert))
                    }
                    TextButton(onClick = onSave) {
                        Text(
                            stringResource(R.string.companion_system_settings_dirty_save),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** The arm-schedule day mask: seven chips, bit 0 = Monday … bit 6 = Sunday. */
@Composable
private fun DayChipsRow(
    label: String,
    mask: Int,
    enabled: Boolean,
    onMask: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayNames = stringArrayResource(R.array.companion_system_day_names)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        dayNames.forEachIndexed { bit, name ->
            FilterChip(
                selected = mask and (1 shl bit) != 0,
                onClick = { onMask(mask xor (1 shl bit)) },
                label = { Text(name) },
                enabled = enabled,
            )
        }
    }
}

// ── Auth sessions card ──

/**
 * The web-session card: who this app is signed in as (role badge), the
 * live session roster (admin), a revoke button per session, and the
 * sign-out affordance. [canManage] false (viewer) hides the roster and
 * revoke buttons; the server stays the real gate behind both.
 */
@Composable
internal fun AuthSessionsCard(
    identity: IdentityBlock,
    canManage: Boolean,
    onRevokeSession: (String) -> Unit,
    onRequestLogout: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.companion_system_auth_title), modifier = modifier) {
        when {
            !identity.authRequired -> {
                Text(
                    text = stringResource(R.string.companion_system_auth_not_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !identity.authenticated -> {
                Text(
                    text = stringResource(R.string.companion_system_auth_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.companion_system_auth_signed_in),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    RoleBadge(identity.role)
                    TextButton(
                        onClick = { onRequestLogout(true) },
                        enabled = !identity.busy,
                    ) {
                        Text(stringResource(R.string.companion_system_logout))
                    }
                }
                val sessions = identity.sessions.orEmpty()
                if (identity.sessions == null) {
                    // Admin sessions: the roster fetch failed or has not
                    // landed — say so rather than rendering an empty list.
                    if (canManage) {
                        Text(
                            text = stringResource(R.string.companion_system_auth_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.companion_system_sessions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            canRevoke = canManage,
                            revokeEnabled = !identity.busy,
                            onRevoke = { onRevokeSession(session.tokenPrefix) },
                        )
                    }
                }
            }
        }
    }
}

/** One live session: token prefix, role badge, expiry, and the revoke button. */
@Composable
private fun SessionRow(
    session: AuthSessionEntry,
    canRevoke: Boolean,
    revokeEnabled: Boolean,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.tokenPrefix,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    session.expiresAtMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RoleBadge(session.role)
        if (canRevoke) {
            TextButton(onClick = onRevoke, enabled = revokeEnabled) {
                Text(stringResource(R.string.companion_system_session_revoke))
            }
        }
    }
}

/** The role badge — the same vocabulary the roster card renders. */
@Composable
private fun RoleBadge(role: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(
                when (role) {
                    "admin" -> R.string.companion_role_admin
                    "viewer" -> R.string.companion_role_viewer
                    else -> R.string.companion_role_unknown
                },
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── Audit card ──

/** The audit trail page: newest-first entries, then the load-more affordance. */
@Composable
internal fun AuditCard(
    block: AuditBlock,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.companion_system_audit_title), modifier = modifier) {
        if (block.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.companion_system_audit_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        block.entries.forEach { entry -> AuditRow(entry) }
        if (!block.exhausted) {
            TextButton(
                onClick = onLoadMore,
                enabled = !block.loadingMore,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    stringResource(
                        if (block.loadingMore) {
                            R.string.companion_system_audit_loading
                        } else {
                            R.string.companion_system_audit_load_more
                        },
                    ),
                )
            }
        }
    }
}

/** One audited action: action verb, detail, outcome tint, relative time. */
@Composable
private fun AuditRow(entry: RemoteAuditEntry, modifier: Modifier = Modifier) {
    val failed = entry.outcome == "error"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.action,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (entry.detail.isNotEmpty()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = relativeAuditTime(entry.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (failed) {
            Text(
                text = entry.outcome,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Minute-granularity relative stamp — the roster's last-seen idiom. */
internal fun relativeAuditTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

// ── Shared primitives ──

/** The titled card shell every section renders inside. */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    headerTail: @Composable () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.weight(1f, fill = false),
                )
                headerTail()
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** A group heading inside the settings card. */
@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 10.dp),
    )
}

/** Label/value line for the info card. */
@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLargeEmphasized,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A labeled switch row — the editor's boolean control. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = { onChecked() }, enabled = enabled)
    }
}

/** A labeled percent slider — the editor's 0..100 control. */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt()) },
            valueRange = valueRange,
            enabled = enabled,
        )
    }
}

/** A labeled −/value/+ stepper — the editor's bounded integer control. */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = enabled && value - step >= range.first,
        ) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(48.dp),
        )
        IconButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = enabled && value + step <= range.last,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
