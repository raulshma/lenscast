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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.ui.components.LensCastSectionCard
import com.raulshma.lenscast.ui.components.LensCastTopBar

@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = context.applicationContext as MainApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            app.cameraService, app.settingsDataStore, app.streamingManager, app.powerManager
        )
    )

    LaunchedEffect(activity) {
        viewModel.activityRef = activity
        viewModel.refreshBatteryOptimizationStatus()
    }

    val authSettings by viewModel.authSettings.collectAsState()
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
            item {
                SettingsSection(title = "Display") {
                    SwitchSetting(
                        title = "Show Camera Preview",
                        checked = showPreview,
                        onCheckedChange = { viewModel.updateShowPreview(it) }
                    )
                }
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
                        range = 1024f..65535f,
                        onValueChange = { viewModel.updateStreamingPort(it.toInt()) }
                    )
                    SliderSetting(
                        title = "JPEG Quality",
                        value = jpegQuality.toFloat(),
                        range = 10f..100f,
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
                            onCheckedChange = { viewModel.requestIgnoreBatteryOptimization() }
                        )
                    }
                }
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
                            range = 1024f..65535f,
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
                    SwitchSetting(
                        title = "Echo Cancellation & Noise Suppression",
                        checked = streamAudioEchoCancellation,
                        onCheckedChange = { viewModel.updateStreamAudioEchoCancellation(it) }
                    )
                    SliderSetting(
                        title = "Live Audio Bitrate (kbps)",
                        value = streamAudioBitrateKbps.toFloat(),
                        range = 32f..320f,
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
