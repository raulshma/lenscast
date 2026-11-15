package com.raulshma.lenscast.companion.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.di.CompanionGraph
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import com.raulshma.lenscast.companion.ui.tabs.system.AuditCard
import com.raulshma.lenscast.companion.ui.tabs.system.AuthSessionsCard
import com.raulshma.lenscast.companion.ui.tabs.system.SettingsCard
import com.raulshma.lenscast.companion.ui.tabs.system.SystemInfoCard
import com.raulshma.lenscast.companion.ui.tabs.system.SystemTabActions
import com.raulshma.lenscast.companion.ui.tabs.system.SystemTabTags
import com.raulshma.lenscast.companion.ui.tabs.system.SystemTabEffect
import com.raulshma.lenscast.companion.ui.tabs.system.SystemTabViewModel
import com.raulshma.lenscast.companion.ui.tabs.system.SystemUiState

/**
 * WAVE-3 — the system tab. The frozen signature (kept verbatim):
 *
 * ```
 * @Composable
 * fun SystemTab(session: DeviceSession, modifier: Modifier = Modifier)
 * ```
 *
 * The wave-0 placeholder body is gone; this file now hosts the tab's real
 * surface: the device info + lenses card, the detection/capture settings
 * editor (admin), the web-session roster card (revoke per session, logout on
 * confirm), and the audit trail card — one [SystemTabViewModel] per device,
 * resolved off the app graph the same way the device screen does
 * ([AppGraph].companion, roster for the pairing-time role, the session's API
 * client). A session whose roster entry is gone (removed on another screen,
 * or a bare test session) renders the graceful fallback shell — title plus
 * the no-device note — never a crash.
 *
 * One-shot effects land on a tab-local snackbar ([Scaffold] inside the tab:
 * the device shell owns the top bar and bottom nav; the tab owns its
 * transient messages). The content half ([SystemTabContent]) is stateless —
 * state + actions in, one LazyColumn of cards out — the seam the Robolectric
 * render test composes directly.
 */
@Composable
fun SystemTab(session: DeviceSession, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.companion_tab_system)
    val context = LocalContext.current
    val graph: CompanionGraph? = (context.applicationContext as? AppGraph)?.companion
    val device = remember(session.deviceId) {
        graph?.roster?.find(session.deviceId)
    }

    if (graph == null || device == null) {
        SystemTabFallback(title = title, modifier = modifier)
        return
    }

    val viewModel: SystemTabViewModel = viewModel(
        key = session.deviceId,
        factory = SystemTabViewModel.Factory(graph, session),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot effects → snackbar. The messages resolve in composition (the
    // effect carries the resource id, never text) into a lookup the
    // collector reads.
    val noticeTexts = rememberNoticeTexts()
    LaunchedEffect(viewModel, noticeTexts) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SystemTabEffect.Notice ->
                    snackbarHostState.showSnackbar(
                        noticeTexts[effect.messageRes].orEmpty(),
                    )
            }
        }
    }

    // The effective role: the device's live answer (the auth card's read)
    // wins over the pairing-time roster snapshot; null on either side means
    // "pre-role pairing" and renders the admin surface (the server's own
    // role gate stays the authority behind every write).
    val ready = state as? SystemUiState.Ready
    val role = ready?.identity?.role ?: device.role

    SystemTabContent(
        state = state,
        role = role,
        snackbarHostState = snackbarHostState,
        actions = SystemTabActions(
            onRetry = viewModel::refresh,
            onEditSettings = viewModel::editSettings,
            onSaveSettings = viewModel::saveSettings,
            onRevertSettings = viewModel::revertSettings,
            onRevokeSession = viewModel::revokeSession,
            onRequestLogout = viewModel::setPendingLogout,
            onConfirmLogout = viewModel::logout,
            onLoadMoreAudit = viewModel::loadMoreAudit,
        ),
        modifier = modifier,
    )
}

/**
 * The stateless render half: the tab title (the shell test pokes its
 * [DeviceScreenTags.TAB_TITLE] tag), then one card per loaded section in a
 * keyed LazyColumn — info, settings, web sessions, audit — each degrading to
 * its own unavailable note, the whole-load-failed retry state, and the
 * logout-confirm dialog.
 */
@Composable
internal fun SystemTabContent(
    state: SystemUiState,
    role: String?,
    snackbarHostState: SnackbarHostState,
    actions: SystemTabActions,
    modifier: Modifier = Modifier,
) {
    val isViewer = role == SystemTabViewModel.ROLE_VIEWER
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = stringResource(R.string.companion_tab_system),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(DeviceScreenTags.TAB_TITLE),
            )
            when (val current = state) {
                SystemUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                is SystemUiState.Ready -> {
                    if (current.failed && !current.refreshing) {
                        FailedState(onRetry = actions.onRetry)
                    } else {
                        ReadyCards(current, isViewer, actions)
                    }
                }
            }
        }
    }

    // The logout confirmation — armed from the auth card, confirmed against
    // the VM (the revoke buttons fire immediately; only logout confirms).
    val identity = (state as? SystemUiState.Ready)?.identity
    if (identity?.pendingLogout == true) {
        AlertDialog(
            onDismissRequest = { actions.onRequestLogout(false) },
            title = { Text(stringResource(R.string.companion_system_logout)) },
            text = { Text(stringResource(R.string.companion_system_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmLogout) {
                    Text(stringResource(R.string.companion_system_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.onRequestLogout(false) }) {
                    Text(stringResource(R.string.companion_cancel))
                }
            },
        )
    }
}

/** The four cards, one LazyColumn item each (keyed + content-typed). */
@Composable
private fun ReadyCards(
    state: SystemUiState.Ready,
    isViewer: Boolean,
    actions: SystemTabActions,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SystemTabTags.CARDS_LIST),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "info", contentType = "system-card") {
            val info = state.info
            if (info == null) {
                UnavailableNote(stringResource(R.string.companion_system_info_unavailable))
            } else {
                SystemInfoCard(info = info, lenses = state.lenses)
            }
        }
        item(key = "settings", contentType = "system-card") {
            val block = state.settings
            if (block == null) {
                UnavailableNote(stringResource(R.string.companion_system_settings_unavailable))
            } else {
                SettingsCard(
                    shown = block.draft ?: block.document.streaming,
                    canEdit = !isViewer,
                    saving = block.saving,
                    dirty = block.draft != null,
                    onEdit = actions.onEditSettings,
                    onSave = actions.onSaveSettings,
                    onRevert = actions.onRevertSettings,
                )
            }
        }
        item(key = "auth", contentType = "system-card") {
            val identity = state.identity
            if (identity == null) {
                UnavailableNote(stringResource(R.string.companion_system_auth_unavailable))
            } else {
                AuthSessionsCard(
                    identity = identity,
                    canManage = !isViewer,
                    onRevokeSession = actions.onRevokeSession,
                    onRequestLogout = actions.onRequestLogout,
                )
            }
        }
        item(key = "audit", contentType = "system-card") {
            val audit = state.audit
            if (audit == null) {
                UnavailableNote(stringResource(R.string.companion_system_audit_unavailable))
            } else {
                AuditCard(block = audit, onLoadMore = actions.onLoadMoreAudit)
            }
        }
    }
}

/** One section's degradation note (the card did not land this round). */
@Composable
private fun UnavailableNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center,
    )
}

/** The nothing-landed state: the load-failed verdict plus the retry affordance. */
@Composable
private fun FailedState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.companion_system_error_load),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.companion_system_retry))
        }
    }
}

/**
 * The graceful no-roster-entry shell: the tab title (the shell's title test
 * still finds its tag) plus the no-device note — a device removed from the
 * roster mid-session renders explained, not crashed.
 */
@Composable
private fun SystemTabFallback(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(DeviceScreenTags.TAB_TITLE),
        )
        Text(
            text = stringResource(R.string.companion_system_no_device),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The effect → snackbar string table, resolved in composition so the
 * collector (a non-composable lambda) only reads it. Every notice id the VM
 * effects carries maps to its text.
 */
@Composable
private fun rememberNoticeTexts(): Map<Int, String> = mapOf(
    R.string.companion_system_settings_saved to
        stringResource(R.string.companion_system_settings_saved),
    R.string.companion_system_settings_save_failed to
        stringResource(R.string.companion_system_settings_save_failed),
    R.string.companion_system_session_revoked to
        stringResource(R.string.companion_system_session_revoked),
    R.string.companion_system_logged_out to
        stringResource(R.string.companion_system_logged_out),
    R.string.companion_system_error_forbidden to
        stringResource(R.string.companion_system_error_forbidden),
    R.string.companion_system_error_auth to
        stringResource(R.string.companion_system_error_auth),
    R.string.companion_system_error_network to
        stringResource(R.string.companion_system_error_network),
    R.string.companion_system_error_http to
        stringResource(R.string.companion_system_error_http),
)
