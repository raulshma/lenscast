package com.raulshma.lenscast.companion.ui.tabs.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.dto.DetectionStats
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.companion.client.sse.EventStreamBuffer
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.ui.ConnectionChipColors
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import com.raulshma.lenscast.companion.ui.chipColor
import com.raulshma.lenscast.companion.ui.labelRes
import java.util.Locale

/**
 * The events tab's stateless render half — ViewModel-free (the actions are
 * lambdas, so previews and compose tests host it directly), strict MVI on
 * the outside: everything on screen reads from [state], every interaction
 * goes through [EventsActions].
 */
data class EventsActions(
    val onRefresh: () -> Unit,
    val onRequestClear: () -> Unit,
    val onDismissClear: () -> Unit,
    val onConfirmClear: () -> Unit,
    val onTestFire: () -> Unit,
)

@Composable
fun EventsContent(
    state: EventsUiState,
    actions: EventsActions,
    modifier: Modifier = Modifier,
) {
    when (state) {
        EventsUiState.Loading -> EventsLoadingContent(modifier = modifier)
        is EventsUiState.Ready -> EventsReadyContent(state, actions, modifier)
    }
}

/** The brief pre-first-emission flash: the title plus a determinate-nothing spinner. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EventsLoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.companion_tab_events),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(DeviceScreenTags.TAB_TITLE),
        )
        LoadingIndicator(modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun EventsReadyContent(
    state: EventsUiState.Ready,
    actions: EventsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Header: the tab title (the shell test's swap-per-tab assertion
        // pokes this tag), the live chip, the manual refresh affordance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.companion_tab_events),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag(DeviceScreenTags.TAB_TITLE),
            )
            LiveIndicator(state.connection)
            IconButton(onClick = actions.onRefresh, enabled = !state.busy) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.companion_events_refresh_cd),
                )
            }
        }

        state.stats?.let { fetched ->
            StatsStrip(stats = fetched, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (state.events.isEmpty()) {
            EventsEmptyState(modifier = Modifier.weight(1f))
        } else {
            EventFeed(
                events = state.events,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.isAdmin) {
            AdminActionRow(state = state, actions = actions)
        }
    }

    if (state.pendingClearConfirm) {
        ClearConfirmDialog(state = state, actions = actions)
    }
}

/**
 * The live indicator: the theme's primary pair while the session's link is
 * CONNECTED (live = primary, the migration's semantic rule), otherwise the
 * shared connection-state pill (same palette the top bar's chip uses).
 */
@Composable
private fun LiveIndicator(connection: ConnectionState, modifier: Modifier = Modifier) {
    val colors = if (connection == ConnectionState.CONNECTED) {
        ConnectionChipColors(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        connection.chipColor()
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.content, CircleShape),
            )
            Text(
                text = if (connection == ConnectionState.CONNECTED) {
                    stringResource(R.string.companion_events_live)
                } else {
                    stringResource(connection.labelRes())
                },
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
        }
    }
}

/** The stats strip: today (24 h) / 7 days / all-time counts as three chips. */
@Composable
private fun StatsStrip(stats: DetectionStats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip(
            label = stringResource(R.string.companion_stats_24h),
            value = stats.last24h.values.sum(),
            modifier = Modifier.weight(1f),
        )
        StatChip(
            label = stringResource(R.string.companion_stats_7d),
            value = stats.last7d.values.sum(),
            modifier = Modifier.weight(1f),
        )
        StatChip(
            label = stringResource(R.string.companion_stats_total),
            value = stats.totalEvents,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatChip(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The feed: the session buffer's snapshot as-is — NEWEST-FIRST arrives that
 * way, the LazyColumn renders top-down, no reversing. Keys ride the buffer's
 * own identity ([com.raulshma.lenscast.companion.client.sse.EventStreamBuffer.eventKey]:
 * the id when present, the timestamp fallback otherwise — a raw `event.id`
 * key would crash on a pre-id server's empty-id events colliding).
 */
@Composable
private fun EventFeed(events: List<RemoteDetectionEvent>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = events,
            key = { EventStreamBuffer.eventKey(it) },
            contentType = { "event" },
        ) { event ->
            EventCard(event = event)
        }
    }
}

@Composable
private fun EventCard(event: RemoteDetectionEvent, modifier: Modifier = Modifier) {
    // The age stamps at composition; a new event (or any recomposition of
    // the item) refreshes it — no per-second ticker in v1.
    val age = remember(event.id, event.timestampMs) { eventAge(event.timestampMs, System.currentTimeMillis()) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TypeBadge(type = event.type)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.source,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = age.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The type badge: one stadium per event type, color-coded, label uppercase. */
@Composable
private fun TypeBadge(type: String, modifier: Modifier = Modifier) {
    val (container, content) = when (type) {
        "motion" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "sound" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "tamper" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = type.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The quiet middle: no events in the session's feed yet. */
@Composable
private fun EventsEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.companion_events_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The admin-only tail: the test-fire and the destructive clear-all. */
@Composable
private fun AdminActionRow(
    state: EventsUiState.Ready,
    actions: EventsActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = actions.onTestFire,
            enabled = !state.busy,
        ) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.companion_events_test_fire))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = actions.onRequestClear,
            enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.companion_events_clear))
        }
    }
}

@Composable
private fun ClearConfirmDialog(
    state: EventsUiState.Ready,
    actions: EventsActions,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = actions.onDismissClear,
        modifier = modifier,
        title = { Text(stringResource(R.string.companion_events_clear_confirm_title)) },
        text = { Text(stringResource(R.string.companion_events_clear_confirm_body)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmClear, enabled = !state.busy) {
                Text(stringResource(R.string.companion_events_clear_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissClear) {
                Text(stringResource(R.string.companion_cancel))
            }
        },
    )
}
