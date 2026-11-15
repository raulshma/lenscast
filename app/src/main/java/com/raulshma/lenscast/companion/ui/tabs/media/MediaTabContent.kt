@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.raulshma.lenscast.companion.ui.tabs.media

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * The user-action vocabulary of [MediaTabContent] — the stateless render
 * half's entire write surface, as a data class of lambdas so the Robolectric
 * test composes the tab without a ViewModel (the [DevicesContentTest]
 * hosting idiom this file mirrors).
 */
data class MediaTabActions(
    val onFilterSelect: (MediaGalleryFilter) -> Unit,
    val onOpenItem: (MediaUiItem) -> Unit,
    /** Long-press multi-select toggle (admin devices only). */
    val onToggleSelect: (itemId: String) -> Unit,
    val onClearSelection: () -> Unit,
    /** Arms (id) or disarms (null) the single-item delete-confirm dialog. */
    val onRequestDelete: (itemId: String?) -> Unit,
    val onRequestBatchDelete: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onCancelDelete: () -> Unit,
    val onCloseViewer: () -> Unit,
    /** The v1 share: the media URL text out through an ACTION_SEND chooser. */
    val onShare: (url: String) -> Unit,
    /** The grid scrolled near its end — fetch the next gallery page. */
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    /** The interval editor's seconds field changed — the raw text lands in the state. */
    val onIntervalDraftChange: (String) -> Unit,
    val onStartInterval: () -> Unit,
    val onStopInterval: () -> Unit,
)

/**
 * The media tab's stateless render half: the tab title, the type-filter
 * chips, the recordings and interval-capture cards, and the paged gallery
 * grid (one LazyVerticalGrid is the tab's single scroll container — the
 * cards ride full-span grid items above the tiles). The in-tab viewer, when
 * [MediaUiState.Ready.viewer] is set, covers the tab; its video surface and
 * the authed thumbnails both live behind the composition-scoped
 * [AuthedBitmapLoader] / [RemoteVideoPlayer] seams.
 *
 * The viewer role (`canManage = false`) hides every destructive control —
 * delete buttons, selection, the confirm dialog's trigger — while keeping
 * the read surface (grid, viewer, share) intact.
 */
@Composable
internal fun MediaTabContent(
    state: MediaUiState,
    actions: MediaTabActions,
    modifier: Modifier = Modifier,
    mediaEngine: OkHttpClient? = null,
    authHeaders: Map<String, String> = emptyMap(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val loader = remember(mediaEngine, authHeaders) {
        mediaEngine?.let { AuthedBitmapLoader(it, authHeaders) }
    }
    Box(modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabTitle(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when (state) {
                is MediaUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                is MediaUiState.Ready -> ReadyBody(
                    ready = state,
                    actions = actions,
                    loader = loader,
                )
            }
        }
        if (state is MediaUiState.Ready && state.viewer != null) {
            ViewerOverlay(
                target = state.viewer,
                loader = loader,
                mediaEngine = mediaEngine,
                authHeaders = authHeaders,
                canManage = state.canManage,
                actions = actions,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The tab's title element — the shell test's [DeviceScreenTags.TAB_TITLE] hook. */
@Composable
private fun TabTitle(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.companion_tab_media),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.testTag(DeviceScreenTags.TAB_TITLE),
    )
}

/** The Ready state's body: one grid, full-span header items, then the tiles. */
@Composable
private fun ReadyBody(
    ready: MediaUiState.Ready,
    actions: MediaTabActions,
    loader: AuthedBitmapLoader?,
) {
    val gridState = rememberLazyGridState()

    // Load-more on scroll end: fires when the last visible tile comes within
    // the threshold of the list's end. The VM re-entrancy-guards, so the
    // snapshot firing repeatedly costs nothing until a page actually lands.
    LaunchedEffect(gridState, ready.items.size, ready.hasMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { actions.onLoadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
            FilterChipsRow(
                selected = ready.filter,
                onSelect = actions.onFilterSelect,
            )
        }
        if (ready.failedSections.isNotEmpty()) {
            item(key = "errors", span = { GridItemSpan(maxLineSpan) }, contentType = "errors") {
                ErrorBanner(
                    sections = ready.failedSections,
                    onRetry = actions.onRetry,
                )
            }
        }
        item(key = "recordings", span = { GridItemSpan(maxLineSpan) }, contentType = "recordings-card") {
            RecordingsCard(
                recording = ready.recording,
                sessions = ready.sessions,
                canManage = ready.canManage,
                busy = ready.busy,
                onStart = actions.onStartRecording,
                onStop = actions.onStopRecording,
            )
        }
        item(key = "interval", span = { GridItemSpan(maxLineSpan) }, contentType = "interval-card") {
            IntervalCard(
                interval = ready.interval,
                canManage = ready.canManage,
                busy = ready.busy,
                draft = ready.intervalDraft,
                draftValid = ready.intervalDraftValid,
                onDraftChange = actions.onIntervalDraftChange,
                onStart = actions.onStartInterval,
                onStop = actions.onStopInterval,
            )
        }
        if (ready.selection.isNotEmpty()) {
            item(key = "selection", span = { GridItemSpan(maxLineSpan) }, contentType = "selection-bar") {
                SelectionBar(
                    count = ready.selection.size,
                    canManage = ready.canManage,
                    busy = ready.busy,
                    onClear = actions.onClearSelection,
                    onDelete = actions.onRequestBatchDelete,
                )
            }
        }
        if (ready.items.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                Text(
                    text = stringResource(
                        if (ready.filter == MediaGalleryFilter.ALL) {
                            R.string.companion_media_empty
                        } else {
                            R.string.companion_media_empty_filtered
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }
        items(
            items = ready.items,
            key = { it.id },
            contentType = { it.type },
        ) { item ->
            MediaTile(
                item = item,
                selecting = ready.selection.isNotEmpty(),
                selected = item.id in ready.selection,
                canManage = ready.canManage,
                busy = ready.busy,
                loader = loader,
                onOpen = { actions.onOpenItem(item) },
                onToggleSelect = { actions.onToggleSelect(item.id) },
                onDelete = { actions.onRequestDelete(item.id) },
                onShare = { actions.onShare(item.mediaUrl) },
            )
        }
    }

    if (ready.pendingDelete != null) {
        DeleteConfirmDialog(
            pending = ready.pendingDelete,
            busy = ready.busy,
            onConfirm = actions.onConfirmDelete,
            onCancel = actions.onCancelDelete,
        )
    }
}

/** The gallery type filter chips: all / photos / videos. */
@Composable
private fun FilterChipsRow(
    selected: MediaGalleryFilter,
    onSelect: (MediaGalleryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MediaGalleryFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes())) },
            )
        }
    }
}

/** Chip labels resolve here so the render stays resource-only. */
private fun MediaGalleryFilter.labelRes(): Int = when (this) {
    MediaGalleryFilter.ALL -> R.string.companion_media_filter_all
    MediaGalleryFilter.PHOTOS -> R.string.companion_media_filter_photos
    MediaGalleryFilter.VIDEOS -> R.string.companion_media_filter_videos
}

/** The per-section load-failure banner with the retry affordance. */
@Composable
private fun ErrorBanner(
    sections: Set<MediaSection>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            sections.forEach { section ->
                Text(
                    text = stringResource(section.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.companion_media_retry))
            }
        }
    }
}

private fun MediaSection.messageRes(): Int = when (this) {
    MediaSection.GALLERY -> R.string.companion_media_error_gallery
    MediaSection.RECORDINGS -> R.string.companion_media_error_recordings
    MediaSection.INTERVAL -> R.string.companion_media_error_interval
}

/** The recording status + start/stop (admin) + the compact session list. */
@Composable
private fun RecordingsCard(
    recording: MediaRecordingUi,
    sessions: List<MediaSessionUi>,
    canManage: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.companion_recordings_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recording.isRecording) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.companion_record_active),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.companion_media_elapsed,
                                formatElapsed(recording.elapsedSeconds),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (canManage) {
                        Button(onClick = onStop, enabled = !busy) {
                            Text(stringResource(R.string.companion_record_stop))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.companion_media_not_recording),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (canManage) {
                        Button(onClick = onStart, enabled = !busy) {
                            Text(stringResource(R.string.companion_record_start))
                        }
                    }
                }
            }
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.companion_media_sessions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sessions.take(MAX_SESSION_ROWS).forEach { session ->
                        SessionRow(session)
                    }
                }
            }
        }
    }
}

/** One compact session line: `<day time> · <trigger label>`. */
@Composable
private fun SessionRow(session: MediaSessionUi) {
    val context = LocalContext.current
    val whenText = remember(session.startMs) {
        DateUtils.formatDateTime(
            context,
            session.startMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
    Text(
        text = "$whenText · ${triggerLabel(session.trigger)}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The trigger's localized label; an unknown trigger (newer firmware
 * vocabulary) falls back to its raw wire word rather than lying.
 */
@Composable
private fun triggerLabel(trigger: String): String = when (trigger) {
    "manual" -> stringResource(R.string.companion_media_trigger_manual)
    "motion" -> stringResource(R.string.companion_media_trigger_motion)
    "sound" -> stringResource(R.string.companion_media_trigger_sound)
    "continuous" -> stringResource(R.string.companion_media_trigger_continuous)
    "scheduled" -> stringResource(R.string.companion_media_trigger_scheduled)
    "interval" -> stringResource(R.string.companion_media_trigger_interval)
    else -> trigger
}

/**
 * The interval-capture status + the seconds editor + start/stop (admin).
 * The editor is the web console's interval field: a number input validated
 * against the device's accepted range (the raw draft and its validity both
 * ride the ViewModel state, so the render stays stateless).
 */
@Composable
private fun IntervalCard(
    interval: MediaIntervalUi,
    canManage: Boolean,
    busy: Boolean,
    draft: String,
    draftValid: Boolean,
    onDraftChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.companion_media_interval_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (interval.isRunning) {
                        stringResource(
                            R.string.companion_media_interval_running,
                            interval.completedCaptures,
                        )
                    } else {
                        stringResource(R.string.companion_media_interval_idle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canManage && !interval.isRunning) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = { Text(stringResource(R.string.companion_media_interval_seconds)) },
                        isError = !draftValid,
                        supportingText = if (!draftValid) {
                            { Text(stringResource(R.string.companion_media_interval_seconds_range)) }
                        } else {
                            null
                        },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(),
                    )
                }
            }
            if (canManage) {
                if (interval.isRunning) {
                    Button(onClick = onStop, enabled = !busy) {
                        Text(stringResource(R.string.companion_media_interval_stop))
                    }
                } else {
                    Button(onClick = onStart, enabled = !busy && draftValid) {
                        Text(stringResource(R.string.companion_media_interval_start))
                    }
                }
            }
        }
    }
}

/** The multi-select bar: count, clear, delete-selected. */
@Composable
private fun SelectionBar(
    count: Int,
    canManage: Boolean,
    busy: Boolean,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.companion_media_select_count, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.companion_media_clear_selection_cd),
                )
            }
            Button(onClick = onDelete, enabled = canManage && !busy) {
                Text(stringResource(R.string.companion_media_delete))
            }
        }
    }
}

/** One gallery tile: thumbnail, type badge, share/delete actions. */
@Composable
private fun MediaTile(
    item: MediaUiItem,
    selecting: Boolean,
    selected: Boolean,
    canManage: Boolean,
    busy: Boolean,
    loader: AuthedBitmapLoader?,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteLabel = stringResource(R.string.companion_delete_cd, item.fileName)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = { if (canManage) onToggleSelect() },
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AuthedMediaImage(
                url = item.mediaUrl,
                loader = loader,
                isVideo = item.isVideo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    if (item.isVideo && item.durationMs > 0) {
                        Text(
                            text = formatElapsed((item.durationMs / 1000L).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.companion_share_cd),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (canManage && !selecting) {
                    IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = deleteLabel,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The armed delete-confirm dialog (single or batch). */
@Composable
private fun DeleteConfirmDialog(
    pending: MediaPendingDelete,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.companion_media_delete)) },
        text = {
            when (pending) {
                is MediaPendingDelete.Single ->
                    Text(stringResource(R.string.companion_media_delete_confirm_single, pending.fileName))
                is MediaPendingDelete.Batch ->
                    Text(stringResource(R.string.companion_media_delete_confirm_batch, pending.ids.size))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.companion_media_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.companion_cancel))
            }
        },
    )
}

/** The in-tab viewer: a top bar (close/share/delete) over the media surface. */
@Composable
private fun ViewerOverlay(
    target: MediaViewerTarget,
    loader: AuthedBitmapLoader?,
    mediaEngine: OkHttpClient?,
    authHeaders: Map<String, String>,
    canManage: Boolean,
    actions: MediaTabActions,
    modifier: Modifier = Modifier,
) {
    val deleteLabel = stringResource(R.string.companion_delete_cd, target.fileName)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onCloseViewer) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.companion_media_viewer_close_cd),
                )
            }
            Text(
                text = target.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.onShare(target.url) }) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.companion_share_cd),
                )
            }
            if (canManage) {
                IconButton(onClick = { actions.onRequestDelete(target.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = deleteLabel,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (target.isVideo) {
                if (mediaEngine != null) {
                    RemoteVideoPlayer(
                        url = target.url,
                        engine = mediaEngine,
                        authHeaders = authHeaders,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AuthedMediaImage(
                    url = target.url,
                    loader = loader,
                    isVideo = false,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    thumbnail = false,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
    }
}

/** `MM:SS` (or `H:MM:SS` past an hour) — elapsed and clip durations. */
internal fun formatElapsed(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

/** The grid scrolls this close to its end before the next page fires. */
private const val LOAD_MORE_THRESHOLD = 4

/** Session rows shown before the card says "and the rest" — compact by design. */
private const val MAX_SESSION_ROWS = 5
