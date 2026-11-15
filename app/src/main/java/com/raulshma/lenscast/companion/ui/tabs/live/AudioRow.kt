package com.raulshma.lenscast.companion.ui.tabs.live

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.ConnectionState

/**
 * The audio row: the live-listen chip (the [AudioListener] lane) and the
 * push-to-talk hold button. The hold is disabled unless the session's
 * connection chip reads CONNECTED — talking to a device the link has lost
 * silently buffers into a dead socket.
 */
@Composable
fun AudioRow(
    state: LiveUiState.Ready,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.listening,
                onClick = actions.onListenToggle,
                label = {
                    Text(
                        stringResource(
                            if (state.listening) R.string.companion_stop_audio else R.string.companion_listen_audio,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            if (state.talkbackActive) {
                Text(
                    text = stringResource(R.string.companion_talkback_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        PressHoldButton(
            enabled = state.connection == ConnectionState.CONNECTED,
            onStart = actions.onTalkStart,
            onEnd = actions.onTalkEnd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(
                        if (state.talkbackActive) R.string.companion_talkback_stop else R.string.companion_talkback_start,
                    ),
                )
            }
        }
    }
}

/**
 * A button that reports press-and-hold: [onStart] on the down event,
 * [onEnd] on release or cancel — the push-to-talk gesture. The press
 * interactions arrive through the standard [MutableInteractionSource] the
 * Material button already drives.
 */
@Composable
fun PressHoldButton(
    enabled: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // One hold at a time: the press flag collapses the double-report a
    // cancelled gesture can produce (Press → Cancel without Release).
    var pressed by remember { mutableStateOf(false) }
    Button(
        onClick = { /* the gesture is press/release — no click action */ },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
    ) {
        content()
    }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (!pressed) {
                        pressed = true
                        onStart()
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (pressed) {
                        pressed = false
                        onEnd()
                    }
                }
                else -> Unit
            }
        }
    }
    // Safety net: if the composable leaves composition mid-hold, end the talk.
    DisposableEffect(interactionSource) {
        onDispose {
            if (pressed) onEnd()
        }
    }
}
