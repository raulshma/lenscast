package com.raulshma.lenscast.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.dto.LensInfo
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.RemoteAuditEntry
import com.raulshma.lenscast.companion.client.dto.SystemInfo
import com.raulshma.lenscast.companion.console.AuditBlock

/**
 * The diagnostics section's stateless cards — the retired companion System
 * tab's device-info and audit cards, re-homed verbatim (P6 folded them into
 * the remote App-settings surface). Every card renders pure facts off its
 * state object and reports gestures through plain lambdas; no client, no
 * state owner, no side effects. Modifier-first everywhere, stringResource
 * everywhere.
 */

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
