package com.raulshma.lenscast.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.dto.StreamClientsInfo
import com.raulshma.lenscast.companion.console.MaintenanceStatus
import com.raulshma.lenscast.companion.console.SettingsDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The remote App-settings screen's folded diagnostics section (P6): the
 * retired companion System tab's cards — device info, connected clients +
 * kick, the audit trail — plus the device maintenance actions (soft/deep
 * restart, reset camera) and the settings export/import pair, all rendered
 * from the one [SettingsDiagnostics] seam. Rendered ONLY when the route
 * carries a paired device (the local App settings surface never gains this
 * section — parity is the local surface, this is the folded remote-only
 * addition).
 *
 * The info and audit cards are the tab's composables re-homed
 * ([SystemInfoCard], [AuditCard] in this package) with the tab's
 * single-homed strings; only the maintenance/backup affordances (which the
 * tab never had) carry new copy. Confirm semantics follow the tab's dialogs:
 * destructive actions arm an [AlertDialog], the kick row reuses the pairing
 * lane's confirm copy. The settings-export flow is NOT owned here — the
 * caller holds it ([rememberRemoteSettingsExport] at the settings
 * destination) and passes it in, because this whole section is a LazyColumn
 * item whose disposal during scrolling would tear down a flow declared
 * inside it mid-fetch or mid-picker.
 */
@Composable
fun RemoteDiagnosticsSection(
    diagnostics: SettingsDiagnostics,
    export: RemoteSettingsExport,
    modifier: Modifier = Modifier,
) {
    val info by diagnostics.info.collectAsState()
    val lenses by diagnostics.lenses.collectAsState()
    val clients by diagnostics.clients.collectAsState()
    val audit by diagnostics.audit.collectAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── Device info (the System tab's card) ──
        val currentInfo = info
        if (currentInfo == null) {
            SettingsSection(title = stringResource(R.string.companion_system_info_title)) {
                Text(
                    text = stringResource(R.string.companion_system_info_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SystemInfoCard(info = currentInfo, lenses = lenses)
        }

        // ── Connected clients + kick ──
        val currentClients = clients
        SettingsSection(title = stringResource(R.string.companion_clients_title)) {
            if (currentClients == null) {
                Text(
                    text = stringResource(R.string.companion_clients_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ClientsRows(currentClients, canKick = diagnostics.canManage) { id ->
                    diagnostics.kickClient(id)
                }
            }
        }

        // ── Audit trail (the System tab's card + paging) ──
        val currentAudit = audit
        SettingsSection(title = stringResource(R.string.companion_system_audit_title)) {
            if (currentAudit == null) {
                Text(
                    text = stringResource(R.string.companion_system_audit_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AuditCard(block = currentAudit, onLoadMore = { diagnostics.loadMoreAudit() })
            }
        }

        // ── Maintenance: restart/reset — device-mutating, admin-only ──
        if (diagnostics.canManage) {
            MaintenanceCard(
                diagnostics = diagnostics,
                canScheduleExactAlarms = currentInfo?.canScheduleExactAlarms,
            )
            ConfigBackupCard(diagnostics, export)
        }
    }
}

/** The client rosters: one HTTP viewer per row, then the RTSP sessions. */
@Composable
private fun ClientsRows(
    clients: StreamClientsInfo,
    canKick: Boolean,
    onKick: (String) -> Unit,
) {
    var pendingKick by remember { mutableStateOf<String?>(null) }

    if (clients.httpClients.isEmpty() && clients.rtspClients.isEmpty()) {
        Text(
            text = stringResource(R.string.companion_clients_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        clients.httpClients.forEach { address ->
            ClientRow(label = address, canKick = canKick) { pendingKick = address }
        }
        clients.rtspClients.forEach { rtsp ->
            ClientRow(
                label = "${rtsp.remoteAddress} · ${rtsp.transport}",
                canKick = false, // the kick route drops HTTP viewers only
            )
        }
    }

    // The kick confirmation — the live tab's single-homed copy and flow
    // (armed from the row, fired on confirm).
    val kickTarget = pendingKick
    if (kickTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingKick = null },
            title = { Text(stringResource(R.string.companion_kick_cd, kickTarget)) },
            text = { Text(stringResource(R.string.companion_live_kick_confirm_body, kickTarget)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingKick = null
                    onKick(kickTarget)
                }) {
                    Text(stringResource(R.string.companion_live_kick_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKick = null }) {
                    Text(stringResource(R.string.companion_cancel))
                }
            },
        )
    }
}

/** One connected client: the label and, for HTTP viewers, the kick button. */
@Composable
private fun ClientRow(label: String, canKick: Boolean, onKick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (canKick) {
            TextButton(onClick = onKick) {
                Text(stringResource(R.string.companion_live_kick_yes))
            }
        }
    }
}

/** The restart/reset maintenance card with the tab's arm-then-confirm flow. */
@Composable
private fun MaintenanceCard(
    diagnostics: SettingsDiagnostics,
    canScheduleExactAlarms: Boolean?,
) {
    var pendingAction by remember { mutableStateOf<MaintenanceAction?>(null) }
    val status by diagnostics.maintenanceStatus.collectAsState()

    SettingsSection(title = stringResource(R.string.settings_diagnostics_maintenance_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { pendingAction = MaintenanceAction.SOFT_RESTART }) {
                Text(stringResource(R.string.settings_diagnostics_restart_soft))
            }
            OutlinedButton(onClick = { pendingAction = MaintenanceAction.FULL_RESTART }) {
                Text(stringResource(R.string.settings_diagnostics_restart_full))
            }
            OutlinedButton(onClick = { pendingAction = MaintenanceAction.RESET_CAMERA }) {
                Text(stringResource(R.string.settings_diagnostics_reset_camera))
            }
        }
        if (canScheduleExactAlarms == false) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_diagnostics_exact_alarm_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val current = status) {
            is MaintenanceStatus.RestartSent -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (current.soft) {
                            R.string.settings_diagnostics_soft_restart_sent
                        } else {
                            R.string.settings_diagnostics_restart_sent
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MaintenanceStatus.RestartDeniedExactAlarm -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_diagnostics_restart_denied_exact_alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is MaintenanceStatus.RestartFailed -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = current.detail
                        ?: stringResource(R.string.settings_diagnostics_restart_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            MaintenanceStatus.ResetCameraSent -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_diagnostics_reset_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is MaintenanceStatus.ResetCameraFailed -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = current.detail
                        ?: stringResource(R.string.settings_diagnostics_reset_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            null -> Unit
        }
    }

    // The confirm dialog: the tab's arm-then-confirm semantics (the action
    // fires only off the dialog's confirm button).
    val pending = pendingAction
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(pending.titleRes)) },
            text = { Text(stringResource(pending.bodyRes)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    when (pending) {
                        MaintenanceAction.SOFT_RESTART -> diagnostics.restartApp(soft = true)
                        MaintenanceAction.FULL_RESTART -> diagnostics.restartApp(soft = false)
                        MaintenanceAction.RESET_CAMERA -> diagnostics.resetCamera()
                    }
                }) {
                    Text(stringResource(R.string.companion_system_settings_dirty_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.companion_cancel))
                }
            },
        )
    }
}

/** One armed maintenance action's copy (title and body). */
private enum class MaintenanceAction(
    val titleRes: Int,
    val bodyRes: Int,
) {
    SOFT_RESTART(
        R.string.settings_diagnostics_soft_restart_confirm_title,
        R.string.settings_diagnostics_soft_restart_confirm_body,
    ),
    FULL_RESTART(
        R.string.settings_diagnostics_restart_confirm_title,
        R.string.settings_diagnostics_restart_confirm_body,
    ),
    RESET_CAMERA(
        R.string.settings_diagnostics_reset_confirm_title,
        R.string.settings_diagnostics_reset_confirm_body,
    ),
}

/**
 * The settings-export flow, held by the settings DESTINATION
 * ([rememberRemoteSettingsExport]) rather than the diagnostics card: the
 * card is a LazyColumn item whose disposal during scrolling would cancel a
 * card-scoped fetch coroutine and unregister the document picker's result
 * callback mid-flow — the destination's composition slot survives the
 * scroll, so a started export keeps running and the picker's answer still
 * lands. Also single-flight: [inFlight] spans fetch → picker → write, so a
 * second tap can never race the active export or replace its held payload.
 */
class RemoteSettingsExport internal constructor(
    /** True from fetch start until the write lands (or the flow fails/cancels) — the Export button's disabled state. */
    val inFlight: Boolean,
    /** The note the backup card renders (the export flow writes it; the card's import flow reports through [setNote]). */
    val noteRes: Int?,
    /** The shared note line's setter — the card-side import flow's report path. */
    val setNote: (Int?) -> Unit,
    /** Arms the export (single-flight): fetch the envelope, then open the document picker. */
    val start: () -> Unit,
)

/**
 * The destination-held export state: the picker, the coroutine, and the
 * held payload all live at the CALLER's composition level (see
 * [RemoteSettingsExport] for why).
 */
@Composable
fun rememberRemoteSettingsExport(diagnostics: SettingsDiagnostics): RemoteSettingsExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteRes by remember { mutableStateOf<Int?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    // The fetched envelope held for the saver: CreateDocument's callback
    // fires after the user picks the destination, so the JSON must survive
    // the round-trip through the picker — including the activity's death
    // while the picker is front, hence saveable (a plain `remember` lost the
    // payload on recreation and silently dropped the export). Cleared once
    // written or dropped.
    var pendingExportJson by rememberSaveable { mutableStateOf<String?>(null) }

    // The export flow: fetch the envelope's JSON first (a failed fetch must
    // not open the saver), then hand it to CreateDocument — the document is
    // written to a location the user picked through a content URI, producing
    // a real JSON file the import action's document picker can open again.
    // The share sheet's EXTRA_TEXT copy was not a reusable document: it rode
    // on the receiving app to materialize a file and could not be fed back
    // to the import picker as-is.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            // Picker cancelled: drop the held payload, re-arm the button.
            pendingExportJson = null
            inFlight = false
            return@rememberLauncherForActivityResult
        }
        val json = pendingExportJson
        pendingExportJson = null
        if (json == null) {
            // The payload did not survive to the answer (the app process
            // died behind the picker): say so — the failed note is the
            // retry path — instead of silently returning and leaving the
            // picked URI unwritten.
            noteRes = R.string.settings_diagnostics_export_failed
            inFlight = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // The write rides Dispatchers.IO like the import's read; runCatching
            // keeps the failed-write → failure-note path and `use` closes the
            // stream even when the write throws.
            noteRes = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.buffered()?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("No output stream for the picked document")
                }
            }.fold(
                onSuccess = { R.string.settings_diagnostics_export_done },
                onFailure = { R.string.settings_diagnostics_export_failed },
            )
            inFlight = false
        }
    }

    return RemoteSettingsExport(
        inFlight = inFlight,
        noteRes = noteRes,
        setNote = { noteRes = it },
        start = {
            // Single-flight: a tap while one export is active (fetch, picker,
            // or write) must not race it or replace the held payload.
            if (!inFlight) {
                inFlight = true
                scope.launch {
                    val json = diagnostics.exportSettings()
                    if (json == null) {
                        noteRes = R.string.settings_diagnostics_export_failed
                        inFlight = false
                    } else {
                        pendingExportJson = json
                        exportLauncher.launch("lenscast-settings.json")
                    }
                }
            }
        },
    )
}

/** The settings export/import pair (the dashboard's ConfigBackup twin). */
@Composable
private fun ConfigBackupCard(
    diagnostics: SettingsDiagnostics,
    export: RemoteSettingsExport,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The import flow: the document picker hands back a content URI; the
    // JSON rides the diagnostics seam's import route verbatim. (The export
    // flow it pairs with lives destination-side — see RemoteSettingsExport;
    // both report through its one shared note line.)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // The whole open-read-close rides Dispatchers.IO — the content
            // resolver's file I/O must not sit on the composing main thread;
            // runCatching keeps the failed-open → failure-note path and
            // `use` closes the stream even when the read throws.
            val json = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
            }.getOrNull()
            export.setNote(
                if (json != null && diagnostics.importSettings(json)) {
                    R.string.settings_diagnostics_import_done
                } else {
                    R.string.settings_diagnostics_import_failed
                }
            )
        }
    }

    SettingsSection(title = stringResource(R.string.settings_diagnostics_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = export.start,
                enabled = !export.inFlight,
            ) {
                Text(stringResource(R.string.settings_diagnostics_export))
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                Text(stringResource(R.string.settings_diagnostics_import))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_diagnostics_backup_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        export.noteRes?.let { res ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
