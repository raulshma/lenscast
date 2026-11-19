package com.raulshma.lenscast.companion.ui.tabs.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R

/**
 * The admin controls strip: torch, capture, siren, the web-stream toggle,
 * the zoom slider (bounded by the device's reported range), and manual
 * recording with the elapsed readout. Rendered only for admin-role pairings
 * (`LiveUiState.Ready.canControl`) — viewers never see writes here.
 */
@Composable
fun ControlsStrip(
    state: LiveUiState.Ready,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = actions.onTorchToggle) {
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = stringResource(
                        if (state.torchOn) R.string.companion_torch_off_cd else R.string.companion_torch_on_cd,
                    ),
                    tint = if (state.torchOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = actions.onCapturePhoto) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.companion_capture_cd),
                )
            }
            IconButton(onClick = actions.onSirenToggle) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = stringResource(
                        if (state.sirenActive) R.string.companion_siren_off else R.string.companion_siren_on,
                    ),
                    tint = if (state.sirenActive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onWebStreamToggle) {
                Text(
                    stringResource(
                        if (state.webStreamActive) R.string.companion_stream_stop else R.string.companion_stream_start,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ZoomIn,
                contentDescription = stringResource(R.string.companion_zoom_cd),
                modifier = Modifier.size(20.dp),
            )
            val range = state.zoomRange
            val min = (range?.min ?: DEFAULT_ZOOM_MIN).toFloat()
            val max = (range?.max ?: DEFAULT_ZOOM_MAX).toFloat()
            Slider(
                value = state.zoomRatio.toFloat().coerceIn(min, max),
                onValueChange = { actions.onZoomChange(it.toDouble()) },
                onValueChangeFinished = actions.onZoomCommit,
                valueRange = min..max,
                enabled = state.statusReady,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = actions.onRecordingToggle) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (state.recording?.isRecording == true) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(
                        if (state.recording?.isRecording == true) {
                            R.string.companion_record_stop
                        } else {
                            R.string.companion_record_start
                        },
                    ),
                )
            }
            val recording = state.recording
            if (recording?.isRecording == true) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.companion_live_recording_elapsed,
                        formatElapsed(recording.elapsedSeconds),
                    ),
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private const val DEFAULT_ZOOM_MIN = 1.0
private const val DEFAULT_ZOOM_MAX = 8.0
