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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.EventKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.raulshma.lenscast.ui.components.LensCastTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The navigation route for this screen (the detection alerts' tap-through target). */
const val DETECTION_EVENTS_ROUTE = "detection-events"

/**
 * The on-device detection-event log — the app-side twin of the dashboard's
 * event feed: type-filtered newest-first list with the trigger snapshot, the
 * dispatched actions, the zone/ML labels, and the linked clip's file name.
 * Reads the shared [DetectionEventStore]; live updates arrive through the
 * same flow the SSE stream tails, and Clear goes through the store like the
 * web route does.
 */
@Composable
fun DetectionEventsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = (context.applicationContext as MainApplication).detectionEventStore
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf(store.events()) }

    // Live tail: every record or clip-link update re-reads the store (the
    // flow carries the event, but the filter needs the whole list anyway).
    LaunchedEffect(Unit) {
        store.eventsFlow.collect {
            events = store.events()
        }
    }

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = "Detection Events",
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
                    "All" to null,
                    "Motion" to EventKind.MOTION.wireName,
                    "Sound" to EventKind.SOUND.wireName,
                    "Tamper" to EventKind.TAMPER.wireName,
                ).forEach { (label, kind) ->
                    Surface(
                        onClick = { filter = kind },
                        color = if (filter == kind) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
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
                TextButton(onClick = {
                    // clear() persists the emptied log — disk I/O belongs off
                    // the UI thread; the reload re-reads whatever landed.
                    scope.launch(Dispatchers.IO) {
                        store.clear()
                        events = store.events()
                    }
                }) {
                    Text("Clear")
                }
            }

            val filtered = remember(events, filter) {
                events.filter { filter == null || it.type == filter }
            }
            if (filtered.isEmpty()) {
                Text(
                    text = "No detection events yet",
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
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                    contentDescription = "${event.type} snapshot",
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
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = timestampFormat.format(Date(event.timestampMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (event.zones.isNotEmpty()) {
                    Text(
                        text = "Zones: ${event.zones.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.labels.isNotEmpty()) {
                    Text(
                        text = "ML: ${event.labels.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.dispatchedActions.isNotEmpty()) {
                    Text(
                        text = "Actions: ${event.dispatchedActions.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                event.clipFileName?.let { clip ->
                    Text(
                        text = "Clip: $clip",
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
