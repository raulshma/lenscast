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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureMode
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.settings.DropdownSetting
import com.raulshma.lenscast.settings.SettingsSection
import com.raulshma.lenscast.settings.SliderSetting
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val viewModel: CaptureViewModel = viewModel(
        factory = CaptureViewModel.Factory(
            context, app.cameraService, app.captureHistoryStore
        )
    )

    val intervalConfig by viewModel.intervalConfig.collectAsState()
    val isIntervalRunning by viewModel.isIntervalRunning.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val captureHistory by viewModel.captureHistory.collectAsState()
    val recordingConfig by viewModel.recordingConfig.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()
    val scheduledStartTime by viewModel.scheduledStartTime.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capture") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsSection(title = "Quick Capture") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.capturePhoto() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Photo", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = { viewModel.toggleRecording() },
                            modifier = Modifier.weight(1f),
                            colors = if (isRecording) ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ) else ButtonDefaults.buttonColors()
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (isRecording) "Stop" else "Record",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Interval Photography") {
                    SliderSetting(
                        title = "Interval (seconds)",
                        value = intervalConfig.intervalSeconds.toFloat(),
                        range = 1f..3600f,
                        onValueChange = {
                            viewModel.updateIntervalConfig(
                                intervalConfig.copy(intervalSeconds = it.toLong())
                            )
                        }
                    )
                    SliderSetting(
                        title = "Total Captures",
                        value = intervalConfig.totalCaptures.toFloat(),
                        range = 1f..1000f,
                        onValueChange = {
                            viewModel.updateIntervalConfig(
                                intervalConfig.copy(totalCaptures = it.toInt())
                            )
                        }
                    )
                    SliderSetting(
                        title = "JPEG Quality",
                        value = intervalConfig.imageQuality.toFloat(),
                        range = 10f..100f,
                        onValueChange = {
                            viewModel.updateIntervalConfig(
                                intervalConfig.copy(imageQuality = it.toInt())
                            )
                        }
                    )
                    DropdownSetting(
                        title = "Capture Mode",
                        options = CaptureMode.entries.map { it.name },
                        selected = intervalConfig.captureMode.name,
                        onSelect = {
                            viewModel.updateIntervalConfig(
                                intervalConfig.copy(captureMode = CaptureMode.valueOf(it))
                            )
                        }
                    )
                    DropdownSetting(
                        title = "Flash Mode",
                        options = FlashMode.entries.map { it.name },
                        selected = intervalConfig.flashMode.name,
                        onSelect = {
                            viewModel.updateIntervalConfig(
                                intervalConfig.copy(flashMode = FlashMode.valueOf(it))
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isIntervalRunning) {
                        OutlinedButton(
                            onClick = { viewModel.stopIntervalCapture() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Stop Interval", modifier = Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startIntervalCapture(intervalConfig) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Start Interval", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Scheduled Recording") {
                    DropdownSetting(
                        title = "Recording Quality",
                        options = RecordingQuality.entries.map { it.name },
                        selected = recordingConfig.quality.name,
                        onSelect = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(quality = RecordingQuality.valueOf(it))
                            )
                        }
                    )
                    SliderSetting(
                        title = "Duration (seconds, 0 = unlimited)",
                        value = recordingConfig.durationSeconds.toFloat(),
                        range = 0f..3600f,
                        onValueChange = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(durationSeconds = it.toLong())
                            )
                        }
                    )
                    SliderSetting(
                        title = "Repeat Interval (seconds, 0 = no repeat)",
                        value = recordingConfig.repeatIntervalSeconds.toFloat(),
                        range = 0f..3600f,
                        onValueChange = {
                            viewModel.updateRecordingConfig(
                                recordingConfig.copy(repeatIntervalSeconds = it.toLong())
                            )
                        }
                    )

                    var showTimePicker by remember { mutableStateOf(false) }
                    if (showTimePicker) {
                        ScheduleTimePickerDialog(
                            onConfirm = { hour, minute ->
                                val cal = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                    if (timeInMillis < System.currentTimeMillis()) {
                                        add(Calendar.DAY_OF_YEAR, 1)
                                    }
                                }
                                viewModel.updateScheduledStartTime(cal.timeInMillis)
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
                                scheduledStartTime?.let {
                                    "Start: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}"
                                } ?: "Set Start Time",
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (scheduledStartTime != null) {
                            IconButton(onClick = { viewModel.updateScheduledStartTime(null) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear schedule")
                            }
                        }
                    }

                    if (isRecording) {
                        Text(
                            text = "Recording: ${formatDuration(recordingElapsedMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { viewModel.toggleRecording() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Videocam, null, Modifier.size(18.dp))
                            Text("Stop Recording", Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.startScheduledRecording() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Videocam, null, Modifier.size(18.dp))
                                Text(
                                    if (scheduledStartTime != null) "Schedule" else "Start Now",
                                    Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Capture History",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (captureHistory.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Clear All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (captureHistory.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "No captures yet. Take a photo or start recording!",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(captureHistory, key = { it.id }) { entry ->
                    CaptureHistoryItem(
                        entry = entry,
                        onDelete = { viewModel.deleteHistoryEntry(entry.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureHistoryItem(
    entry: CaptureHistory,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.type == CaptureType.PHOTO)
                    Icons.Default.PhotoCamera else Icons.Default.Videocam,
                contentDescription = null,
                tint = if (entry.type == CaptureType.PHOTO)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                if (entry.type == CaptureType.VIDEO && entry.durationMs > 0) {
                    Text(
                        text = "Duration: ${entry.durationMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.fileSizeBytes > 0) {
                    Text(
                        text = formatFileSize(entry.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(is24Hour = true)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            TimeInput(state = timePickerState)
        }
    )
}
