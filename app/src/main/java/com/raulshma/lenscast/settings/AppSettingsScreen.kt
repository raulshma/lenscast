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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.R
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtmp.RtmpStatus
import com.raulshma.lenscast.streaming.rtmp.RtmpUrl
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

internal val storageQuotaSliderRange: ClosedFloatingPointRange<Float> =
    StreamDefaults.STORAGE_QUOTA_MB_MIN.toFloat()..StreamDefaults.STORAGE_QUOTA_MB_MAX.toFloat()

@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenEventLog: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = context.applicationContext as MainApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            app.cameraService, app.settingsDataStore, app.powerManager,
            // The detection section surfaces the on-demand model downloads.
            app.detectionModelStore,
            app.audioModelStore,
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
    val rtmpEnabled by viewModel.rtmpEnabled.collectAsState()
    val rtmpUrl by viewModel.rtmpUrl.collectAsState()
    val adaptiveBitrateEnabled by viewModel.adaptiveBitrateEnabled.collectAsState()
    val mdnsEnabled by viewModel.mdnsEnabled.collectAsState()
    val isIgnoringBatteryOptimizations by viewModel.isIgnoringBatteryOptimizations
    val resumeStreamsOnBoot by viewModel.resumeStreamsOnBoot.collectAsState()
    val continuousRecording by viewModel.continuousRecording.collectAsState()
    val continuousSegmentMinutes by viewModel.continuousSegmentMinutes.collectAsState()
    val ecoIdleFpsEnabled by viewModel.ecoIdleFpsEnabled.collectAsState()
    val storageQuotaMb by viewModel.storageQuotaMb.collectAsState()
    val mediaEncryptionEnabled by viewModel.mediaEncryptionEnabled.collectAsState()

    val updateState by updateViewModel.updateState.collectAsState()
    val autoCheckEnabled by updateViewModel.autoCheckEnabled.collectAsState()
    val lastCheckTime by updateViewModel.lastCheckTime.collectAsState()

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.settings_app_title),
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
                SettingsSection(title = stringResource(R.string.settings_section_updates)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_current_version),
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
                            text = stringResource(
                                R.string.settings_last_check,
                                DateUtils.getRelativeTimeSpanString(lastCheckTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_auto_check),
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
                                Text(stringResource(R.string.settings_check_updates))
                            }
                            if (state is UpdateState.UpToDate) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_up_to_date, state.remoteVersion),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        is UpdateState.Checking -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.settings_checking), style = MaterialTheme.typography.bodySmall)
                        }
                        is UpdateState.UpdateAvailable -> {
                            Text(
                                text = stringResource(R.string.settings_update_available, state.version),
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
                                    Text(stringResource(R.string.settings_download))
                                }
                                OutlinedButton(
                                    onClick = { updateViewModel.dismissUpdate() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.settings_dismiss))
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
                                text = stringResource(R.string.settings_downloading, (state.progress * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is UpdateState.ReadyToInstall -> {
                            Button(
                                onClick = { updateViewModel.installUpdate(activity) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_install_update))
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
                                Text(stringResource(R.string.settings_retry))
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
                SettingsSection(title = stringResource(R.string.settings_section_streaming)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_enable_web_streaming),
                        checked = webStreamingEnabled,
                        onCheckedChange = { viewModel.updateWebStreamingEnabled(it) }
                    )
                    SliderSetting(
                        title = stringResource(R.string.settings_streaming_port),
                        value = streamingPort.toFloat(),
                        range = webPortSliderRange,
                        onValueChange = { viewModel.updateStreamingPort(it.toInt()) }
                    )
                    SliderSetting(
                        title = stringResource(R.string.settings_jpeg_quality),
                        value = jpegQuality.toFloat(),
                        range = jpegQualitySliderRange,
                        onValueChange = { viewModel.updateJpegQuality(it.toInt()) }
                    )
                    SwitchSetting(
                        title = stringResource(R.string.settings_adaptive_bitrate),
                        checked = adaptiveBitrateEnabled,
                        onCheckedChange = { viewModel.updateAdaptiveBitrateEnabled(it) }
                    )
                    SwitchSetting(
                        title = stringResource(R.string.settings_eco_idle),
                        checked = ecoIdleFpsEnabled,
                        onCheckedChange = { viewModel.updateEcoIdleFpsEnabled(it) }
                    )
                    if (ecoIdleFpsEnabled) {
                        Text(
                            text = stringResource(R.string.settings_eco_idle_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SwitchSetting(
                        title = stringResource(R.string.settings_mdns),
                        checked = mdnsEnabled,
                        onCheckedChange = { viewModel.updateMdnsEnabled(it) }
                    )
                }
            }

            item {
                DetectionSettingsSection(
                    viewModel,
                    onOpenEventLog = onOpenEventLog,
                )
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_recording)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_continuous_recording),
                        checked = continuousRecording,
                        onCheckedChange = { viewModel.updateContinuousRecording(it) }
                    )
                    if (continuousRecording) {
                        Text(
                            text = stringResource(R.string.settings_continuous_recording_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SliderSetting(
                            title = stringResource(R.string.settings_segment_length),
                            value = continuousSegmentMinutes.toFloat(),
                            range = StreamDefaultsRange.CONTINUOUS_SEGMENT_MINUTES,
                            steps = StreamDefaultsRange.CONTINUOUS_SEGMENT_STEPS,
                            onValueChange = { viewModel.updateContinuousSegmentMinutes(it.toInt()) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                    SliderSetting(
                        title = stringResource(R.string.settings_storage_quota),
                        value = storageQuotaMb.toFloat(),
                        range = storageQuotaSliderRange,
                        steps = StreamDefaultsRange.STORAGE_QUOTA_STEPS,
                        onValueChange = { viewModel.updateStorageQuotaMb(it.toInt()) }
                    )
                    Text(
                        text = stringResource(R.string.settings_storage_quota_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_encrypt_captures),
                        checked = mediaEncryptionEnabled,
                        onCheckedChange = { viewModel.updateMediaEncryptionEnabled(it) }
                    )
                    Text(
                        text = stringResource(R.string.settings_encrypt_captures_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_background)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_disable_battery_opt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_disable_battery_opt_description),
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
                        title = stringResource(R.string.settings_resume_on_boot),
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
                SettingsSection(title = stringResource(R.string.settings_section_rtsp)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_enable_rtsp),
                        checked = rtspEnabled,
                        onCheckedChange = { viewModel.updateRtspEnabled(it) }
                    )
                    if (rtspEnabled) {
                        SliderSetting(
                            title = stringResource(R.string.settings_rtsp_port),
                            value = rtspPort.toFloat(),
                            range = rtspPortSliderRange,
                            onValueChange = { viewModel.updateRtspPort(it.toInt()) }
                        )
                        DropdownSetting(
                            title = stringResource(R.string.settings_rtsp_input_format),
                            options = RtspInputFormat.entries.map { it.name },
                            selected = rtspInputFormat.name,
                            onSelect = { viewModel.updateRtspInputFormat(it) }
                        )
                    }
                }
            }

            item {
                // The RTMP push output: the enable gate arms it, the URL is the
                // push target (rtmp[s]://host/app/stream-key — the stream key
                // rides the URL and is never displayed back), and the status
                // line mirrors the publisher's live lifecycle, including the
                // readable refusal/error text.
                val rtmpStatus by app.streamingManager.rtmpStatus.collectAsState()
                SettingsSection(title = stringResource(R.string.settings_section_rtmp)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_enable_rtmp),
                        checked = rtmpEnabled,
                        onCheckedChange = { viewModel.updateRtmpEnabled(it) }
                    )
                    if (rtmpEnabled) {
                        OutlinedTextField(
                            value = rtmpUrl,
                            onValueChange = { viewModel.updateRtmpUrl(it) },
                            label = { Text(stringResource(R.string.settings_rtmp_push_url)) },
                            placeholder = { Text(stringResource(R.string.settings_rtmp_url_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (rtmpUrl.isNotBlank() && RtmpUrl.parse(rtmpUrl) == null) {
                            Text(
                                stringResource(R.string.settings_rtmp_url_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val statusText = when (val s = rtmpStatus) {
                            is RtmpStatus.Error -> stringResource(
                                R.string.settings_rtmp_status,
                                stringResource(R.string.settings_rtmp_status_error, s.message)
                            )
                            else -> stringResource(R.string.settings_rtmp_status, s.wireName)
                        }
                        Text(
                            statusText,
                            color = if (rtmpStatus is RtmpStatus.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.settings_rtmp_h264_note),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_audio)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_stream_audio),
                        checked = streamAudioEnabled,
                        onCheckedChange = { viewModel.updateStreamAudioEnabled(it) }
                    )
                    // Preferred microphone: blank = platform default.
                    val app = context.applicationContext as MainApplication
                    val audioDevices = remember { app.streamingManager.audioInputDevices() }
                    val selectedAudioDevice by viewModel.audioDeviceId.collectAsState()
                    val defaultAudioLabel = stringResource(R.string.settings_audio_default)
                    DropdownSetting(
                        title = stringResource(R.string.settings_microphone),
                        options = listOf(defaultAudioLabel) + audioDevices.map { it.second },
                        selected = audioDevices.firstOrNull { it.first.toString() == selectedAudioDevice }?.second
                            ?: defaultAudioLabel,
                        onSelect = { label ->
                            viewModel.updateAudioDeviceId(
                                audioDevices.firstOrNull { it.second == label }?.first?.toString() ?: ""
                            )
                        }
                    )
                    SwitchSetting(
                        title = stringResource(R.string.settings_echo_cancellation),
                        checked = streamAudioEchoCancellation,
                        onCheckedChange = { viewModel.updateStreamAudioEchoCancellation(it) }
                    )
                    SliderSetting(
                        title = stringResource(R.string.settings_audio_bitrate),
                        value = streamAudioBitrateKbps.toFloat(),
                        range = audioBitrateSliderRange,
                        onValueChange = { viewModel.updateStreamAudioBitrateKbps(it.toInt()) }
                    )
                    val monoLabel = stringResource(R.string.settings_mono)
                    val stereoLabel = stringResource(R.string.settings_stereo)
                    DropdownSetting(
                        title = stringResource(R.string.settings_audio_channels),
                        options = listOf(monoLabel, stereoLabel),
                        selected = if (streamAudioChannels == 2) stereoLabel else monoLabel,
                        onSelect = { viewModel.updateStreamAudioChannels(if (it == stereoLabel) 2 else 1) }
                    )
                    SwitchSetting(
                        title = stringResource(R.string.settings_recording_audio),
                        checked = recordingAudioEnabled,
                        onCheckedChange = { viewModel.updateRecordingAudioEnabled(it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_security)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_https),
                        checked = httpsEnabled,
                        onCheckedChange = { viewModel.updateHttpsEnabled(it) }
                    )
                    SwitchSetting(
                        title = stringResource(R.string.settings_stream_auth),
                        checked = authSettings.enabled,
                        onCheckedChange = { viewModel.updateAuthEnabled(it) }
                    )
                    if (authSettings.enabled) {
                        OutlinedTextField(
                            value = authSettings.username,
                            onValueChange = { viewModel.updateAuthUsername(it) },
                            label = { Text(stringResource(R.string.settings_username)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        var passwordText by remember { mutableStateOf("") }
                        val keyboardController = LocalSoftwareKeyboardController.current
                        OutlinedTextField(
                            value = passwordText,
                            onValueChange = { passwordText = it },
                            label = { Text(stringResource(R.string.settings_password)) },
                            placeholder = { Text(stringResource(R.string.settings_password_hint)) },
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
