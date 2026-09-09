package com.raulshma.lenscast.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.update.UpdateViewModel
import com.raulshma.lenscast.update.model.UpdateState
import android.text.format.DateUtils
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.ui.components.LensCastSectionCard
import com.raulshma.lenscast.ui.components.LensCastTopBar

// Slider spans built FROM the Settings Store's StreamDefaults clamps — the
// one home — so the UI always offers the full persisted range and can never
// drift from the store's coercion (parity-pinned by AppSettingsRangesTest).
internal val webPortSliderRange: ClosedFloatingPointRange<Float> =
    StreamDefaults.WEB_PORT_MIN.toFloat()..StreamDefaults.WEB_PORT_MAX.toFloat()

internal val jpegQualitySliderRange: ClosedFloatingPointRange<Float> =
    StreamDefaults.JPEG_QUALITY_MIN.toFloat()..StreamDefaults.JPEG_QUALITY_MAX.toFloat()

internal val rtspPortSliderRange: ClosedFloatingPointRange<Float> =
    StreamDefaults.RTSP_PORT_MIN.toFloat()..StreamDefaults.RTSP_PORT_MAX.toFloat()

internal val audioBitrateSliderRange: ClosedFloatingPointRange<Float> =
    StreamDefaults.AUDIO_BITRATE_MIN_KBPS.toFloat()..StreamDefaults.AUDIO_BITRATE_MAX_KBPS.toFloat()

@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = context.applicationContext as MainApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            app.cameraService, app.settingsDataStore, app.powerManager,
            // The detection section surfaces the on-demand model download.
            app.detectionModelStore,
        )
    )
    val updateViewModel: UpdateViewModel = viewModel(
        factory = UpdateViewModel.Factory(
            app.updateChecker,
            app.updateDownloader,
            app.updateInstaller,
            app.updateNotifier,
            app.settingsDataStore,
        )
    )

    LaunchedEffect(activity) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    val authSettings by viewModel.authSettings.collectAsState()
    val httpsEnabled by viewModel.httpsEnabled.collectAsState()
    val streamingPort by viewModel.streamingPort.collectAsState()
    val webStreamingEnabled by viewModel.webStreamingEnabled.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()
    val showPreview by viewModel.showPreview.collectAsState()
    val streamAudioEnabled by viewModel.streamAudioEnabled.collectAsState()
    val streamAudioBitrateKbps by viewModel.streamAudioBitrateKbps.collectAsState()
    val streamAudioChannels by viewModel.streamAudioChannels.collectAsState()
    val streamAudioEchoCancellation by viewModel.streamAudioEchoCancellation.collectAsState()
    val recordingAudioEnabled by viewModel.recordingAudioEnabled.collectAsState()
    val rtspEnabled by viewModel.rtspEnabled.collectAsState()
    val rtspPort by viewModel.rtspPort.collectAsState()
    val rtspInputFormat by viewModel.rtspInputFormat.collectAsState()
    val adaptiveBitrateEnabled by viewModel.adaptiveBitrateEnabled.collectAsState()
    val mdnsEnabled by viewModel.mdnsEnabled.collectAsState()
    val isIgnoringBatteryOptimizations by viewModel.isIgnoringBatteryOptimizations
    val resumeStreamsOnBoot by viewModel.resumeStreamsOnBoot.collectAsState()
    val continuousRecording by viewModel.continuousRecording.collectAsState()
    val continuousSegmentMinutes by viewModel.continuousSegmentMinutes.collectAsState()

    val updateState by updateViewModel.updateState.collectAsState()
    val autoCheckEnabled by updateViewModel.autoCheckEnabled.collectAsState()
    val lastCheckTime by updateViewModel.lastCheckTime.collectAsState()

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = "App Settings",
                onNavigateBack = onNavigateBack,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (com.raulshma.lenscast.BuildConfig.SELF_UPDATE) item {
                SettingsSection(title = "Updates") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Current Version",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = currentVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (lastCheckTime > 0) {
                        Text(
                            text = "Last checked: ${DateUtils.getRelativeTimeSpanString(lastCheckTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = "Auto-check on App Start",
                        checked = autoCheckEnabled,
                        onCheckedChange = { updateViewModel.setAutoCheckEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when (val state = updateState) {
                        is UpdateState.Idle, is UpdateState.UpToDate -> {
                            OutlinedButton(
                                onClick = { updateViewModel.checkForUpdate() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Check for Updates")
                            }
                            if (state is UpdateState.UpToDate) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "You're on the latest version (latest: ${state.remoteVersion})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        is UpdateState.Checking -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Checking for updates...", style = MaterialTheme.typography.bodySmall)
                        }
                        is UpdateState.UpdateAvailable -> {
                            Text(
                                text = "Update ${state.version} available",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (state.releaseNotes.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.releaseNotes.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { updateViewModel.downloadUpdate() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Download")
                                }
                                OutlinedButton(
                                    onClick = { updateViewModel.dismissUpdate() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                        is UpdateState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Downloading... ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is UpdateState.ReadyToInstall -> {
                            Button(
                                onClick = { updateViewModel.installUpdate(activity) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install Update")
                            }
                        }
                        is UpdateState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { updateViewModel.clearError(); updateViewModel.checkForUpdate() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            item {
                DisplaySettingsSection(
                    showPreview = showPreview,
                    onTogglePreview = { viewModel.updateShowPreview(it) },
                )
            }

            item {
                SettingsSection(title = "Streaming") {
                    SwitchSetting(
                        title = "Enable Web Streaming",
                        checked = webStreamingEnabled,
                        onCheckedChange = { viewModel.updateWebStreamingEnabled(it) }
                    )
                    SliderSetting(
                        title = "Streaming Port",
                        value = streamingPort.toFloat(),
                        range = webPortSliderRange,
                        onValueChange = { viewModel.updateStreamingPort(it.toInt()) }
                    )
                    SliderSetting(
                        title = "JPEG Quality",
                        value = jpegQuality.toFloat(),
                        range = jpegQualitySliderRange,
                        onValueChange = { viewModel.updateJpegQuality(it.toInt()) }
                    )
                    SwitchSetting(
                        title = "Adaptive Bitrate",
                        checked = adaptiveBitrateEnabled,
                        onCheckedChange = { viewModel.updateAdaptiveBitrateEnabled(it) }
                    )
                    SwitchSetting(
                        title = "Network Discovery (mDNS)",
                        checked = mdnsEnabled,
                        onCheckedChange = { viewModel.updateMdnsEnabled(it) }
                    )
                }
            }

            item {
                DetectionSettingsSection(viewModel)
            }

            item {
                SettingsSection(title = "Recording") {
                    SwitchSetting(
                        title = "Continuous Recording",
                        checked = continuousRecording,
                        onCheckedChange = { viewModel.updateContinuousRecording(it) }
                    )
                    if (continuousRecording) {
                        Text(
                            text = "Records NVR-style loop segments back to back; " +
                                "old segments age out through the capture-retention window",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SliderSetting(
                            title = "Segment Length (minutes)",
                            value = continuousSegmentMinutes.toFloat(),
                            range = StreamDefaultsRange.CONTINUOUS_SEGMENT_MINUTES,
                            steps = StreamDefaultsRange.CONTINUOUS_SEGMENT_STEPS,
                            onValueChange = { viewModel.updateContinuousSegmentMinutes(it.toInt()) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Background") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Disable Battery Optimization",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Prevents the system from stopping the app in the background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isIgnoringBatteryOptimizations,
                            onCheckedChange = { viewModel.requestIgnoreBatteryOptimization(activity) }
                        )
                    }
                    SwitchSetting(
                        title = "Resume Streams on Boot",
                        checked = resumeStreamsOnBoot,
                        onCheckedChange = { viewModel.updateResumeStreamsOnBoot(it) }
                    )
                }
            }

            item {
                WatchdogSettingsSection(viewModel)
            }

            item {
                BackupSettingsSection(viewModel)
            }

            item {
                SettingsSection(title = "RTSP Stream") {
                    SwitchSetting(
                        title = "Enable RTSP Streaming",
                        checked = rtspEnabled,
                        onCheckedChange = { viewModel.updateRtspEnabled(it) }
                    )
                    if (rtspEnabled) {
                        SliderSetting(
                            title = "RTSP Port",
                            value = rtspPort.toFloat(),
                            range = rtspPortSliderRange,
                            onValueChange = { viewModel.updateRtspPort(it.toInt()) }
                        )
                        DropdownSetting(
                            title = "RTSP Encoder Input Format",
                            options = RtspInputFormat.entries.map { it.name },
                            selected = rtspInputFormat.name,
                            onSelect = { viewModel.updateRtspInputFormat(it) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Audio") {
                    SwitchSetting(
                        title = "Include Audio in Live Stream",
                        checked = streamAudioEnabled,
                        onCheckedChange = { viewModel.updateStreamAudioEnabled(it) }
                    )
                    // Preferred microphone: blank = platform default.
                    val app = context.applicationContext as MainApplication
                    val audioDevices = remember { app.streamingManager.audioInputDevices() }
                    val selectedAudioDevice by viewModel.audioDeviceId.collectAsState()
                    DropdownSetting(
                        title = "Microphone",
                        options = listOf("Default (auto)") + audioDevices.map { it.second },
                        selected = audioDevices.firstOrNull { it.first.toString() == selectedAudioDevice }?.second
                            ?: "Default (auto)",
                        onSelect = { label ->
                            viewModel.updateAudioDeviceId(
                                audioDevices.firstOrNull { it.second == label }?.first?.toString() ?: ""
                            )
                        }
                    )
                    SwitchSetting(
                        title = "Echo Cancellation & Noise Suppression",
                        checked = streamAudioEchoCancellation,
                        onCheckedChange = { viewModel.updateStreamAudioEchoCancellation(it) }
                    )
                    SliderSetting(
                        title = "Live Audio Bitrate (kbps)",
                        value = streamAudioBitrateKbps.toFloat(),
                        range = audioBitrateSliderRange,
                        onValueChange = { viewModel.updateStreamAudioBitrateKbps(it.toInt()) }
                    )
                    DropdownSetting(
                        title = "Live Audio Channels",
                        options = listOf("Mono", "Stereo"),
                        selected = if (streamAudioChannels == 2) "Stereo" else "Mono",
                        onSelect = { viewModel.updateStreamAudioChannels(if (it == "Stereo") 2 else 1) }
                    )
                    SwitchSetting(
                        title = "Include Audio in Recordings",
                        checked = recordingAudioEnabled,
                        onCheckedChange = { viewModel.updateRecordingAudioEnabled(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Security") {
                    SwitchSetting(
                        title = "HTTPS (Self-Signed Certificate)",
                        checked = httpsEnabled,
                        onCheckedChange = { viewModel.updateHttpsEnabled(it) }
                    )
                    SwitchSetting(
                        title = "Stream Authentication",
                        checked = authSettings.enabled,
                        onCheckedChange = { viewModel.updateAuthEnabled(it) }
                    )
                    if (authSettings.enabled) {
                        OutlinedTextField(
                            value = authSettings.username,
                            onValueChange = { viewModel.updateAuthUsername(it) },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        var passwordText by remember { mutableStateOf("") }
                        val keyboardController = LocalSoftwareKeyboardController.current
                        OutlinedTextField(
                            value = passwordText,
                            onValueChange = { passwordText = it },
                            label = { Text("Password") },
                            placeholder = { Text("Enter new password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (passwordText.isNotEmpty()) {
                                        viewModel.updateAuthPassword(passwordText)
                                        passwordText = ""
                                    }
                                    keyboardController?.hide()
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}
