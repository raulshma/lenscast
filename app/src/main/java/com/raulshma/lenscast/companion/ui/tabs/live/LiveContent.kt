package com.raulshma.lenscast.companion.ui.tabs.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.companion.ui.DeviceScreenTags

/**
 * The live tab's stateless render half: title, rung/phase chips + manual
 * cycle, the video stage (the injectable [videoSurface] slot — the real
 * implementation hosts the rung's AndroidView, tests stub it), the admin
 * controls strip, the audio row, and the clients card. Everything renders
 * from [LiveUiState] and speaks only through [LiveActions], exactly like the
 * DevicesScreen content the Robolectric tests compose without a ViewModel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveContent(
    state: LiveUiState,
    runtime: LiveRuntime,
    actions: LiveActions,
    modifier: Modifier = Modifier,
    videoSurface: @Composable (LiveUiState.Ready, LiveRuntime, Modifier) -> Unit = ::DefaultVideoSurface,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(LiveTags.LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.companion_tab_live),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(DeviceScreenTags.TAB_TITLE),
            )
        }
        when (val current = state) {
            is LiveUiState.Ready -> {
                item(key = "stage", contentType = "stage") {
                    Column {
                        RungHeader(current, actions)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(MaterialTheme.shapes.large),
                        ) {
                            videoSurface(current, runtime, Modifier.fillMaxSize())
                            if (current.rungPhase == RungPhase.CONNECTING) {
                                LoadingIndicator(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(56.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (current.canControl) {
                    item(key = "controls", contentType = "controls") {
                        ControlsStrip(
                            state = current,
                            actions = actions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(LiveTags.CONTROLS),
                        )
                    }
                }
                item(key = "audio", contentType = "audio") {
                    AudioRow(
                        state = current,
                        actions = actions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(LiveTags.AUDIO_ROW),
                    )
                }
                item(key = "clients-head", contentType = "clients") {
                    ClientsCardHeader(current)
                }
                items(
                    items = current.clients,
                    key = { "client-" + it.id },
                    contentType = { "client-row" },
                ) { row ->
                    ClientRow(row = row, onKick = { actions.onRequestKick(row.id) })
                }
            }
            is LiveUiState.Unavailable -> {
                item(key = "unavailable") {
                    Text(
                        text = stringResource(R.string.companion_device_missing_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                }
            }
        }
    }

    // The kick confirmation — armed from the client row's overflow.
    if (state is LiveUiState.Ready && state.pendingKickId != null) {
        val pending = state.clients.firstOrNull { it.id == state.pendingKickId }
        val label = pending?.label ?: state.pendingKickId.orEmpty()
        AlertDialog(
            onDismissRequest = { actions.onRequestKick(null) },
            text = {
                Text(stringResource(R.string.companion_live_kick_confirm_body, label))
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmKick) {
                    Text(stringResource(R.string.companion_live_kick_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.onRequestKick(null) }) {
                    Text(stringResource(R.string.companion_cancel))
                }
            },
        )
    }
}

/** The rung chip, the phase chip, and the manual cycle affordance. */
@Composable
private fun RungHeader(state: LiveUiState.Ready, actions: LiveActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RungChip(state.rung, Modifier.testTag(LiveTags.RUNG_CHIP))
        PhaseChip(state.rungPhase, Modifier.testTag(LiveTags.PHASE_CHIP))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = actions.onEnterPip) {
            Icon(
                Icons.Filled.PictureInPictureAlt,
                contentDescription = stringResource(R.string.companion_pip_cd),
            )
        }
        IconButton(onClick = actions.onCycleRung) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.companion_cycle_rung_cd),
            )
        }
    }
}

/** Which transport the ladder is on. */
@Composable
private fun RungChip(rung: PlaybackRung, modifier: Modifier = Modifier) {
    val labelRes = when (rung) {
        PlaybackRung.WHEP -> R.string.companion_rung_whep
        PlaybackRung.HLS -> R.string.companion_rung_hls
        PlaybackRung.MJPEG -> R.string.companion_rung_mjpeg
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        // The tag rides the Text (not the Surface) so the semantics node
        // carries the chip's text — the test rules assert the label on it.
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The active rung's live verdict (web console's preview status pill). */
@Composable
private fun PhaseChip(phase: RungPhase, modifier: Modifier = Modifier) {
    val (labelRes, colors) = when (phase) {
        RungPhase.CONNECTING ->
            R.string.companion_rung_connecting to (
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                )
        RungPhase.LIVE ->
            R.string.companion_connected to (
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                )
        RungPhase.FAILED ->
            R.string.companion_rung_failed to (
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                )
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.first,
        contentColor = colors.second,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLargeEmphasized,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The clients card's header (title + empty copy; rows render as list items). */
@Composable
private fun ClientsCardHeader(state: LiveUiState.Ready, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().testTag(LiveTags.CLIENTS_CARD)) {
        Text(
            text = stringResource(R.string.companion_clients_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        if (state.clients.isEmpty()) {
            Text(
                text = stringResource(R.string.companion_clients_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** One connected client: label, kind (+ since when reported), kick affordance. */
@Composable
private fun ClientRow(row: StreamClientRow, onKick: () -> Unit, modifier: Modifier = Modifier) {
    val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            val kindRes = when (row.kind) {
                StreamClientRow.ClientKind.WEB -> R.string.companion_live_client_web
                StreamClientRow.ClientKind.RTSP -> R.string.companion_live_client_rtsp
            }
            val since = row.connectedAtMs?.let {
                stringResource(R.string.companion_live_client_since, timeFormat.format(java.util.Date(it)))
            }
            Text(
                text = if (since != null) {
                    stringResource(kindRes) + " · " + since
                } else {
                    stringResource(kindRes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onKick) {
            Icon(
                Icons.Filled.LinkOff,
                contentDescription = stringResource(R.string.companion_kick_cd, row.label),
            )
        }
    }
}
