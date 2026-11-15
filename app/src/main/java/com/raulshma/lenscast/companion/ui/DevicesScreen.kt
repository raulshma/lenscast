package com.raulshma.lenscast.companion.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.ui.components.LensCastTopBar

/**
 * The user-action vocabulary of [DevicesContent] — the stateless screen
 * half's entire write surface, as a data class of lambdas so the Robolectric
 * test can render the content without a ViewModel.
 */
data class DevicesActions(
    val onBack: () -> Unit,
    val onOpenDevice: (deviceId: String) -> Unit,
    val onRefresh: () -> Unit,
    /** POSTs /api/capture on every currently-ONLINE device (web-console parity). */
    val onCaptureAll: () -> Unit,
    /** Arms the remove-confirm dialog for one device id (null = cancel). */
    val onRequestRemove: (deviceId: String?) -> Unit,
    /** The dialog was confirmed for one device id — execute the removal. */
    val onConfirmRemove: (deviceId: String) -> Unit,
    val onAddDevice: () -> Unit,
)

/**
 * The companion mode's devices screen — the roster of paired LensCast
 * cameras, the entry surface of the companion half. Resolves its
 * [DevicesViewModel] off the app graph (the repo's factory idiom), maps the
 * one-shot effects to a snackbar, and renders [DevicesContent] (the
 * stateless half the Robolectric test exercises). The add-device sheet is
 * owned at this level: dismissal returns to the roster, a completed pairing
 * hands the new device's id to [onOpenDevice] so the user lands in its
 * device screen.
 */
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onOpenDevice: (deviceId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph
    val viewModel: DevicesViewModel = viewModel(
        factory = DevicesViewModel.Factory(app.companion),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot effects → snackbar. Strings resolve here (the effects carry
    // the removed device's name / a capture-all verdict, never resource ids).
    val removedTemplate = stringResource(R.string.companion_device_removed)
    val capturedTemplate = stringResource(R.string.companion_capture_all_ok)
    val captureFailedTemplate = stringResource(R.string.companion_capture_all_failed)
    LaunchedEffect(viewModel, removedTemplate, capturedTemplate, captureFailedTemplate) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DevicesEffect.Removed ->
                    snackbarHostState.showSnackbar(removedTemplate.format(effect.deviceName))
                is DevicesEffect.Captured ->
                    snackbarHostState.showSnackbar(
                        (if (effect.ok) capturedTemplate else captureFailedTemplate)
                            .format(effect.deviceName),
                    )
            }
        }
    }

    var showAddSheet by remember { mutableStateOf(false) }

    DevicesContent(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = DevicesActions(
            onBack = onBack,
            onOpenDevice = onOpenDevice,
            onRefresh = viewModel::refresh,
            onCaptureAll = viewModel::captureAll,
            onRequestRemove = viewModel::setPendingRemoval,
            onConfirmRemove = viewModel::remove,
            onAddDevice = { showAddSheet = true },
        ),
    )

    if (showAddSheet) {
        AddDeviceSheet(
            onDismiss = { showAddSheet = false },
            onPaired = { deviceId ->
                showAddSheet = false
                onOpenDevice(deviceId)
            },
        )
    }
}

/**
 * The stateless render half: top bar + refresh, the roster list (one card
 * per paired device) or the empty state, the add FAB, and the
 * remove-confirm dialog (armed via [DevicesUiState.Ready.pendingRemovalId]).
 */
@Composable
fun DevicesContent(
    state: DevicesUiState,
    snackbarHostState: SnackbarHostState,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.companion_title),
                onNavigateBack = actions.onBack,
                actions = {
                    // The capture-all volley: only meaningful once rows carry
                    // probe verdicts — inert while the volley is in flight.
                    if (state is DevicesUiState.Ready) {
                        IconButton(
                            onClick = actions.onCaptureAll,
                            enabled = !state.capturingAll,
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.companion_capture_all_cd),
                            )
                        }
                    }
                    IconButton(onClick = actions.onRefresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.companion_refresh_cd),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAddDevice,
                icon = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.companion_add_cd),
                    )
                },
                text = { Text(stringResource(R.string.companion_add)) },
            )
        },
    ) { padding ->
        when (val current = state) {
            DevicesUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            DevicesUiState.Empty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.companion_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ExtendedFloatingActionButton(
                        onClick = actions.onAddDevice,
                        icon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.companion_add_cd),
                            )
                        },
                        text = { Text(stringResource(R.string.companion_add)) },
                    )
                }
            }

            is DevicesUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = current.rows,
                        key = { it.device.id },
                        contentType = { "device-card" },
                    ) { row ->
                        DeviceCard(
                            row = row,
                            onOpen = { actions.onOpenDevice(row.device.id) },
                            onRequestRemove = { actions.onRequestRemove(row.device.id) },
                        )
                    }
                }
            }
        }
    }

    // The remove confirmation — armed from the card's overflow, confirmed
    // against the snapshot the state still carries.
    if (state is DevicesUiState.Ready && state.pendingRemovalId != null) {
        val pending = state.rows.firstOrNull { it.device.id == state.pendingRemovalId }
        if (pending != null) {
            AlertDialog(
                onDismissRequest = { actions.onRequestRemove(null) },
                title = { Text(stringResource(R.string.companion_remove)) },
                text = {
                    Text(
                        stringResource(
                            R.string.companion_remove_confirm,
                            pending.device.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { actions.onConfirmRemove(pending.device.id) }) {
                        Text(stringResource(R.string.companion_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { actions.onRequestRemove(null) }) {
                        Text(stringResource(R.string.companion_cancel))
                    }
                },
            )
        }
    }
}

/** One paired-device card: identity, badges, probe chip, overflow menu. */
@Composable
private fun DeviceCard(
    row: DeviceRow,
    onOpen: () -> Unit,
    onRequestRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hostPort(row.device),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TlsBadge(device = row.device)
                    RoleBadge(role = row.device.role)
                    if (row.device.lastSeenMs != null) {
                        Text(
                            text = stringResource(
                                R.string.companion_last_seen,
                                relativeLastSeen(System.currentTimeMillis(), row.device.lastSeenMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ProbeChip(status = row.status)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(
                            R.string.companion_device_options_cd,
                            row.device.name,
                        ),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.companion_remove)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRequestRemove()
                        },
                    )
                }
            }
        }
    }
}

/** The trust badge: pinned (QR/TOFU digest held) or TOFU (TLS, nothing held). */
@Composable
private fun TlsBadge(device: CompanionDevice, modifier: Modifier = Modifier) {
    val pinned = !device.tlsFingerprint.isNullOrBlank()
    if (!pinned && !device.useTls) return // plain HTTP: no trust story to show
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (pinned) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (pinned) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = stringResource(
                    if (pinned) R.string.companion_tls_pinned else R.string.companion_tls_tofu,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The paired role badge, or the unknown-role placeholder. */
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

/** The reachability chip the refresh probe drives. */
@Composable
private fun ProbeChip(status: DeviceProbeStatus, modifier: Modifier = Modifier) {
    // The label rides the existing `── Companion mode ──` connection-state
    // strings; the palette stays theme-semantic (online = primary, probing =
    // tertiary, offline/unauthorized = error — the re-pair cue reads as a
    // fault, matching ConnectionUi's treatment).
    val labelRes = when (status) {
        DeviceProbeStatus.PROBING -> R.string.companion_connecting
        DeviceProbeStatus.ONLINE -> R.string.companion_connected
        DeviceProbeStatus.OFFLINE -> R.string.companion_offline
        DeviceProbeStatus.UNAUTHORIZED -> R.string.companion_auth_failed
    }
    val colors = when (status) {
        DeviceProbeStatus.ONLINE ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DeviceProbeStatus.PROBING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DeviceProbeStatus.OFFLINE, DeviceProbeStatus.UNAUTHORIZED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = colors.first,
        contentColor = colors.second,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (status == DeviceProbeStatus.PROBING) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** `host:port`, IPv6-bracketed the way the client dials it. */
private fun hostPort(device: CompanionDevice): String {
    val host = if (device.host.contains(':') && !device.host.startsWith("[")) {
        "[${device.host}]"
    } else {
        device.host
    }
    return "$host:${device.port}"
}

/**
 * The relative last-seen line, minute granularity — the same
 * [DateUtils] shape the app-settings screen uses for its update stamp.
 */
internal fun relativeLastSeen(nowMs: Long, lastSeenMs: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(lastSeenMs, nowMs, DateUtils.MINUTE_IN_MILLIS)
