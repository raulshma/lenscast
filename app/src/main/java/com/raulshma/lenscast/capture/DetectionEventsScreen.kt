package com.raulshma.lenscast.capture

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.ui.components.LensCastTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The navigation route for this screen (the detection alerts' tap-through target). */
const val DETECTION_EVENTS_ROUTE = "detection-events"

/**
 * The detection-event log — the app-side twin of the dashboard's event feed:
 * type-filtered newest-first list with the trigger snapshot, the dispatched
 * actions, the zone/ML labels, and the linked clip's file name. One screen
 * over the [EventsSource] seam (this phone's [DetectionEventStore] locally,
 * the paired device's session feed remotely): the list, its order contract,
 * the filter chips, and the row rendering are identical for both — only the
 * Clear affordance differs by verdict ([EventsSource.canClear] hides it for
 * a viewer pairing; [EventsSource.clearNeedsConfirm] arms the permanent-wipe
 * dialog for the remote log).
 */
@Composable
fun DetectionEventsScreen(
    source: EventsSource,
    onNavigateBack: () -> Unit,
) {
    // The active filter as the EventKind itself (null = ALL): the chips toggle
    // enum values and the list match on the kind's wire name.
    var filter by remember { mutableStateOf<EventKind?>(null) }
    var pendingClearConfirm by remember { mutableStateOf(false) }

    val events by source.events.collectAsState()

    if (pendingClearConfirm) {
        AlertDialog(
            onDismissRequest = { pendingClearConfirm = false },
            title = { Text(stringResource(R.string.companion_events_clear_confirm_title)) },
            text = { Text(stringResource(R.string.companion_events_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearConfirm = false
                    source.clear()
                }) {
                    Text(
                        stringResource(R.string.companion_events_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearConfirm = false }) {
                    Text(stringResource(R.string.gallery_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.detection_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    stringResource(R.string.detection_filter_all) to null,
                    stringResource(R.string.detection_filter_motion) to EventKind.MOTION,
                    stringResource(R.string.detection_filter_sound) to EventKind.SOUND,
                    stringResource(R.string.detection_filter_tamper) to EventKind.TAMPER,
                ).forEach { (label, kind) ->
                    Surface(
                        onClick = { filter = kind },
                        color = if (filter == kind) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filter == kind) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (source.canClear) {
                    TextButton(onClick = {
                        // Local: one tap, as always (the log is app-private).
                        // Remote: the wipe empties the device's persisted log
                        // permanently — the confirm dialog guards it first.
                        if (source.clearNeedsConfirm) pendingClearConfirm = true else source.clear()
                    }) {
                        Text(stringResource(R.string.detection_clear))
                    }
                }
            }

            val filtered = remember(events, filter) {
                val active = filter
                events.filter { active == null || it.type == active.wireName }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.detection_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 4.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { event ->
                        DetectionEventRow(event)
                    }
                }
            }
        }
    }
}

/** One event: snapshot thumbnail, kind, time, zone/ML labels, actions, clip name. */
@Composable
private fun DetectionEventRow(event: DetectionEvent) {
    val timestampFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val bitmap = remember(event.id, event.snapshotJpegBase64) {
                event.snapshotJpegBase64?.let { decodeSnapshot(it) }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.detection_snapshot_cd, event.type),
                    modifier = Modifier
                        .size(width = 84.dp, height = 64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(width = 84.dp, height = 64.dp),
                ) {}
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.type.replaceFirstChar { it.uppercase(Locale.getDefault()) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = timestampFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.zones.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detection_zones, event.zones.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.labels.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detection_ml_labels, event.labels.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.dispatchedActions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detection_actions, event.dispatchedActions.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                event.clipFileName?.let { clip ->
                    Text(
                        text = stringResource(R.string.detection_clip, clip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun decodeSnapshot(base64: String): Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
