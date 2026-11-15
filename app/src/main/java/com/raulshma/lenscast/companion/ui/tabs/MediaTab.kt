package com.raulshma.lenscast.companion.ui.tabs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import com.raulshma.lenscast.companion.ui.tabs.media.MediaEffect
import com.raulshma.lenscast.companion.ui.tabs.media.MediaTabActions
import com.raulshma.lenscast.companion.ui.tabs.media.MediaTabContent
import com.raulshma.lenscast.companion.ui.tabs.media.MediaViewModel

/**
 * The media tab: the remote device's capture library (paged gallery with
 * type filters, authed thumbnails, in-tab viewer, share, admin delete),
 * the recording status card with its session list, and the interval-capture
 * card — the web dashboard's media console in companion form.
 *
 * Composition root: the tab resolves the device's roster snapshot off the
 * app graph (the [DeviceScreen] idiom — the [DeviceSession] itself carries
 * only the id and the live lanes) and owns the [MediaViewModel] through its
 * factory. One [LenscastApiClient] is built here and shared by BOTH the
 * ViewModel's JSON lane and the tab's media lane (thumbnails + player ride
 * the same engine and live auth headers), so the tab adds exactly one engine
 * to the process.
 *
 * No roster entry (device removed from another screen mid-session, or a bare
 * test session): the tab renders its title — the shell test's
 * [DeviceScreenTags.TAB_TITLE] hook, which must survive every state — and
 * the missing-device explanation, never a crash.
 */
@Composable
fun MediaTab(session: DeviceSession, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? AppGraph
    val companion = app?.companion
    val device = remember(session.deviceId) { companion?.roster?.find(session.deviceId) }

    if (companion == null || device == null) {
        MediaTabUnavailable(modifier = modifier)
        return
    }

    val api = remember(device) { companion.apiClient(device) }
    val viewModel: MediaViewModel = viewModel(
        factory = MediaViewModel.Factory(api, canManage = device.role != VIEWER_ROLE),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot effects → snackbar; the strings resolve here (effects carry
    // verdicts, never resources), the same shape DevicesScreen uses.
    val deletedTemplate = stringResource(R.string.companion_media_deleted)
    val forbiddenText = stringResource(R.string.companion_role_readonly_banner)
    val authText = stringResource(R.string.companion_auth_failed)
    val failedText = stringResource(R.string.companion_media_action_failed)
    LaunchedEffect(viewModel, deletedTemplate, forbiddenText, authText, failedText) {
        viewModel.effects.collect { effect ->
            val message = when (effect) {
                is MediaEffect.Deleted -> deletedTemplate.format(effect.count)
                MediaEffect.Forbidden -> forbiddenText
                MediaEffect.AuthRequired -> authText
                MediaEffect.ActionFailed -> failedText
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.companion_media_share_chooser)
    val mediaEngine = remember(device) { api.sharedEngine() }
    val authHeaders = remember(device) { api.sharedAuthHeaders() }

    MediaTabContent(
        state = state,
        actions = MediaTabActions(
            onFilterSelect = viewModel::setFilter,
            onOpenItem = viewModel::openViewer,
            onToggleSelect = viewModel::toggleSelect,
            onClearSelection = viewModel::clearSelection,
            onRequestDelete = viewModel::requestDelete,
            onRequestBatchDelete = viewModel::requestBatchDelete,
            onConfirmDelete = viewModel::confirmPendingDelete,
            onCancelDelete = viewModel::cancelPendingDelete,
            onCloseViewer = viewModel::closeViewer,
            onShare = { url -> shareUrl(context, url, shareChooserTitle) },
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::refresh,
            onStartRecording = viewModel::startRecording,
            onStopRecording = viewModel::stopRecording,
            onIntervalDraftChange = viewModel::setIntervalDraft,
            onStartInterval = viewModel::startInterval,
            onStopInterval = viewModel::stopInterval,
        ),
        modifier = modifier,
        mediaEngine = mediaEngine,
        authHeaders = authHeaders,
        snackbarHostState = snackbarHostState,
    )
}

/** The degraded no-roster-entry state: the title hook plus an explanation. */
@Composable
private fun MediaTabUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.companion_tab_media),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(DeviceScreenTags.TAB_TITLE),
        )
        Text(
            text = stringResource(R.string.companion_device_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

/** The v1 share: the media URL out through a plain-text ACTION_SEND chooser. */
private fun shareUrl(context: Context, url: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** A viewer-role pairing gets no destructive controls anywhere in the tab. */
private const val VIEWER_ROLE = "viewer"
