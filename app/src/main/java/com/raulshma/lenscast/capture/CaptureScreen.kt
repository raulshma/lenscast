package com.raulshma.lenscast.capture

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.capture.model.TimelapseMessage
import com.raulshma.lenscast.capture.model.TimelapseState
import com.raulshma.lenscast.capture.model.timelapseMessage
import com.raulshma.lenscast.gallery.formatDuration
import com.raulshma.lenscast.settings.DropdownSetting
import com.raulshma.lenscast.settings.SettingsSection
import com.raulshma.lenscast.settings.SliderSetting
import com.raulshma.lenscast.settings.SwitchSetting
import com.raulshma.lenscast.settings.dropdownOptionLabel
import com.raulshma.lenscast.ui.components.LensCastTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The capture screen — one screen over the [CaptureSource] seam (this
 * phone's pipeline locally, the paired device's capture routes remotely).
 * Backend differences ride the source's verdicts, never a branch here: the
 * photo/record/interval write affordances gate on
 * [CaptureSource.canCapturePhoto]/[canControlRecording]/[canControlInterval]
 * (a viewer-role pairing reads the truths but drives nothing), and the
 * timelapse section renders only where [CaptureSource.canTimelapse] holds
 * (the wire has no carrier for the local composer pipeline).
 */
@Composable
fun CaptureScreen(
    source: CaptureSource,
    onNavigateBack: () -> Unit,
) {
    val viewModel: CaptureViewModel = viewModel(
        factory = CaptureViewModel.Factory(source)
    )

    val intervalConfig by viewModel.intervalConfig.collectAsState()
    val intervalState by viewModel.intervalState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingConfig by viewModel.recordingConfig.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    // The record affordances' one atomic read: confirmation folded into the
    // state (Unconfirmed instead of a misleading Idle until the remote's
    // first status read lands — locally always confirmed), so the record
    // control's enable gate, look, and press intent answer a single snapshot.
    val recordingSnapshot by viewModel.recordingSnapshot.collectAsState()
    val confirmedRecordingState = (recordingSnapshot as? RecordingSnapshot.Confirmed)?.state
    // The schedule row's whole truth is the pure model: the controller's
    // RecordingState is the armed schedule, the config draft's startTimeMs
    // the picked-but-not-yet-armed input — merged, labeled, and verdict-ed
    // in one place.
    val scheduleRow = scheduleRowUi(recordingState, recordingConfig.startTimeMs)
    // The pure model's labels are English constants (its output is asserted on
    // by tests), so the screen maps them onto resources here; the armed start
    // instant renders through the same HH:mm format the model used.
    val schedulePickerLabel = scheduleRow.pendingStartMs?.let { pendingStartMs ->
        stringResource(
            R.string.capture_scheduled_start,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(pendingStartMs))
        )
    } ?: stringResource(R.string.capture_set_start_time)
    val scheduleMainButtonLabel =
        if (scheduleRow.buttonLabel == SCHEDULED_BUTTON_LABEL) stringResource(R.string.capture_schedule)
        else stringResource(R.string.capture_start_now)

    Scaffold(
        topBar = {
            LensCastTopBar(title = stringResource(R.string.capture_title), onNavigateBack = onNavigateBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The quick actions are device writes both — a viewer-role
            // pairing (or a roster-lost device) reads no section at all.
            if (source.canCapturePhoto || source.canControlRecording) {
                item {
                    SettingsSection(title = stringResource(R.string.capture_section_quick)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (source.canCapturePhoto) {
                                Button(
                                    onClick = { viewModel.capturePhoto() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(stringResource(R.string.capture_photo), modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            // The quick record button renders the snapshot, not
                            // the lossy isRecording: an armed schedule shows the
                            // distinct armed look (Schedule icon on the tertiary
                            // container, "recording scheduled" description)
                            // instead of the plain record affordance. The press
                            // passes the control's intended action, decided from
                            // the same snapshot the look answers — a confirmed
                            // Idle starts (a confirmed idle device starts
                            // normally), a confirmed Scheduled/Recording stops
                            // like a live recording (both armed tiers label the
                            // button Stop), and the unconfirmed placeholder stays
                            // disabled so no press reaches the source blind.
                            if (source.canControlRecording) {
                                Button(
                                    onClick = {
                                        when (confirmedRecordingState) {
                                            is RecordingState.Recording, is RecordingState.Scheduled ->
                                                viewModel.stopRecording()
                                            is RecordingState.Idle -> viewModel.startRecording()
                                            null -> Unit
                                        }
                                    },
                                    enabled = confirmedRecordingState != null,
                                    modifier = Modifier.weight(1f),
                                    colors = when (confirmedRecordingState) {
                                        is RecordingState.Recording -> ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary
                                        )
                                        is RecordingState.Scheduled -> ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                        else -> ButtonDefaults.buttonColors()
                                    }
                                ) {
                                    Icon(
                                        when (confirmedRecordingState) {
                                            is RecordingState.Recording -> Icons.Default.Stop
                                            is RecordingState.Scheduled -> Icons.Default.Schedule
                                            else -> Icons.Default.Videocam
                                        },
                                        contentDescription = if (confirmedRecordingState is RecordingState.Scheduled) {
                                            stringResource(R.string.capture_recording_scheduled_cd)
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        if (confirmedRecordingState is RecordingState.Idle ||
                                            confirmedRecordingState == null
                                        ) {
                                            stringResource(R.string.capture_record)
                                        } else {
                                            stringResource(R.string.capture_stop)
                                        },
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.capture_section_interval)) {
                    // The interval/count/flash controls: editors (wired to
                    // updateIntervalConfig) for a backend whose interval
                    // writes the role allows; the same current values
                    // read-only for everyone else.
                    if (source.canControlInterval) {
                        SliderSetting(
                            title = stringResource(R.string.capture_interval_seconds),
                            value = intervalConfig.intervalSeconds.toFloat(),
                            range = IntervalCapturePolicy.MIN_INTERVAL_SECONDS.toFloat()..IntervalCapturePolicy.MAX_INTERVAL_SECONDS.toFloat(),
                            onValueChange = {
                                viewModel.updateIntervalConfig(
                                    intervalConfig.copy(intervalSeconds = it.toLong())
                                )
                            }
                        )
                        SliderSetting(
                            title = stringResource(R.string.capture_total_captures),
                            value = intervalConfig.totalCaptures.toFloat(),
                            range = 1f..IntervalCapturePolicy.TOTAL_CAPTURES_MAX.toFloat(),
                            onValueChange = {
                                viewModel.updateIntervalConfig(
                                    intervalConfig.copy(totalCaptures = it.toInt())
                                )
                            }
                        )
                        DropdownSetting(
                            title = stringResource(R.string.capture_flash_mode),
                            options = FlashMode.entries.map { it.name },
                            selected = intervalConfig.flashMode.name,
                            onSelect = {
                                viewModel.updateIntervalConfig(
                                    intervalConfig.copy(flashMode = FlashMode.valueOf(it))
                                )
                            }
                        )
                    } else {
                        ReadOnlyValueRow(
                            title = stringResource(R.string.capture_interval_seconds),
                            value = CameraDashboardPolicy.sliderValueLabel(intervalConfig.intervalSeconds.toFloat()),
                        )
                        ReadOnlyValueRow(
                            title = stringResource(R.string.capture_total_captures),
                            value = CameraDashboardPolicy.sliderValueLabel(intervalConfig.totalCaptures.toFloat()),
                        )
                        ReadOnlyValueRow(
                            title = stringResource(R.string.capture_flash_mode),
                            value = dropdownOptionLabel(intervalConfig.flashMode.name),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // The series' tri-state renders for every reader — a
                    // viewer sees whether capture is Running, Stopped, or
                    // Unknown — while the Start/Stop actions stay gated by
                    // the write permission.
                    when (intervalState) {
                        is IntervalCaptureState.Running -> {
                            if (source.canControlInterval) {
                                OutlinedButton(
                                    onClick = { viewModel.stopIntervalCapture() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.tertiary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(stringResource(R.string.capture_stop_interval), modifier = Modifier.padding(start = 8.dp))
                                }
                            } else {
                                Text(
                                    stringResource(R.string.capture_interval_running),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        IntervalCaptureState.Unknown -> {
                            if (source.canControlInterval) {
                                // The WorkManager snapshot (local) / first
                                // status poll (remote) not yet observed:
                                // start disabled-with-progress so the button
                                // is never tappable while the truth is
                                // momentarily unobservable.
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.capture_start_interval), modifier = Modifier.padding(start = 8.dp))
                                }
                            } else {
                                Text(
                                    stringResource(R.string.capture_interval_unknown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IntervalCaptureState.Stopped -> {
                            if (source.canControlInterval) {
                                Button(
                                    onClick = { viewModel.startIntervalCapture(intervalConfig) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(stringResource(R.string.capture_start_interval), modifier = Modifier.padding(start = 8.dp))
                                }
                            } else {
                                Text(
                                    stringResource(R.string.capture_interval_stopped),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (source.canTimelapse) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val timelapseState by viewModel.timelapseState.collectAsState()
                        // The completion handoff's master toggle: when a series
                        // finishes, the shared composer assembles a timelapse
                        // automatically (bounded by the completion policy).
                        val autoTimelapse by viewModel.autoTimelapseEnabled.collectAsState()
                        SwitchSetting(
                            title = stringResource(R.string.capture_auto_timelapse),
                            checked = autoTimelapse,
                            onCheckedChange = { viewModel.setAutoTimelapseEnabled(it) }
                        )
                        OutlinedButton(
                            onClick = { viewModel.assembleTimelapse() },
                            enabled = timelapseState !is TimelapseState.Running,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (timelapseState is TimelapseState.Running) {
                                    stringResource(R.string.capture_assembling)
                                } else {
                                    stringResource(R.string.capture_assemble_timelapse)
                                }
                            )
                        }
                        // The run's outcome, from the state machine's Done: the
                        // pure mapping decides which resource each result maps
                        // onto; Running/Idle render no message line (a Done stops
                        // showing as soon as the next run goes Running).
                        (timelapseState as? TimelapseState.Done)?.let { done ->
                            val message = timelapseMessage(done.result)
                            val text = when (message) {
                                is TimelapseMessage.Saved -> stringResource(
                                    R.string.capture_timelapse_saved,
                                    message.fileName,
                                    message.frameCount,
                                )
                                is TimelapseMessage.NeedSources -> stringResource(
                                    R.string.capture_timelapse_need_photos,
                                    message.minSources,
                                )
                                is TimelapseMessage.Unreadable -> stringResource(
                                    R.string.capture_timelapse_unreadable,
                                    message.detail,
                                )
                                TimelapseMessage.Failed -> stringResource(R.string.capture_timelapse_failed)
                            }
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // The scheduled-recording section is draft-editor + writes both —
            // every control in it drives the recording pipeline, so a
            // viewer-role pairing reads no section at all.
            if (source.canControlRecording) {
                item {
                    SettingsSection(title = stringResource(R.string.capture_section_scheduled)) {
                    SwitchSetting(
                        title = stringResource(R.string.capture_include_audio),
                        checked = recordingConfig.includeAudio,
                        onCheckedChange = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(includeAudio = it)
                            )
                        }
                    )
                    DropdownSetting(
                        title = stringResource(R.string.capture_recording_quality),
                        options = RecordingQuality.entries.map { it.name },
                        selected = recordingConfig.quality.name,
                        onSelect = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(quality = RecordingQuality.valueOf(it))
                            )
                        }
                    )
                    SliderSetting(
                        title = stringResource(R.string.capture_duration),
                        value = recordingConfig.durationSeconds.toFloat(),
                        range = 0f..RecordingConfig.MAX_DURATION_SECONDS.toFloat(),
                        onValueChange = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(durationSeconds = it.toLong())
                            )
                        }
                    )
                    SliderSetting(
                        title = stringResource(R.string.capture_repeat_interval),
                        value = recordingConfig.repeatIntervalSeconds.toFloat(),
                        range = 0f..RecordingConfig.MAX_REPEAT_SECONDS.toFloat(),
                        onValueChange = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(repeatIntervalSeconds = it.toLong())
                            )
                        }
                    )

                    var showTimePicker by remember { mutableStateOf(false) }
                    if (showTimePicker) {
                        ScheduleTimePickerDialog(
                            initialTimeMs = recordingConfig.startTimeMs,
                            onConfirm = { hour, minute ->
                                viewModel.updateRecordingConfig(
                                    recordingConfig.copy(
                                        startTimeMs = RecordingConfig.scheduledStartFor(
                                            hour, minute, System.currentTimeMillis()
                                        )
                                    )
                                )
                                showTimePicker = false
                            },
                            onDismiss = { showTimePicker = false }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                schedulePickerLabel,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (scheduleRow.canCancel) {
                            IconButton(onClick = { viewModel.cancelScheduledRecording() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.capture_clear_schedule_cd))
                            }
                        }
                    }

                    if (isRecording) {
                        Text(
                            text = stringResource(R.string.capture_recording_elapsed, formatDuration(recordingElapsedMs)),
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        OutlinedButton(
                            onClick = { viewModel.stopRecording() },
                            enabled = confirmedRecordingState != null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Icon(Icons.Default.Videocam, null, Modifier.size(18.dp))
                            Text(stringResource(R.string.capture_stop_recording), Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.startScheduledRecording() },
                                enabled = confirmedRecordingState != null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Videocam, null, Modifier.size(18.dp))
                                Text(
                                    scheduleMainButtonLabel,
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
                }
            }


        }
    }
}

/**
 * The read-only value row the capture screen shows a write-gated reader:
 * the same title/value header shape [SliderSetting] uses, without the
 * slider — the viewer sees the current value and no edit affordance.
 */
@Composable
private fun ReadOnlyValueRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    initialTimeMs: Long?,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The picked-but-not-yet-armed time prefills the picker so re-opening it
    // shows what will be scheduled.
    val initial = remember(initialTimeMs) {
        initialTimeMs?.let { java.util.Calendar.getInstance().apply { timeInMillis = it } }
    }
    val timePickerState = rememberTimePickerState(
        initialHour = initial?.get(java.util.Calendar.HOUR_OF_DAY) ?: 0,
        initialMinute = initial?.get(java.util.Calendar.MINUTE) ?: 0,
        is24Hour = true,
    )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text(stringResource(R.string.capture_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.capture_cancel))
            }
        },
        text = {
            TimeInput(state = timePickerState)
        }
    )
}
