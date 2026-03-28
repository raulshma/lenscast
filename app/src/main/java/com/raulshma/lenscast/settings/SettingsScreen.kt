package com.raulshma.lenscast.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            app.cameraService, app.settingsDataStore, app.streamingManager
        )
    )

    val settings by viewModel.settings.collectAsState()
    val zoomRange by viewModel.availableZoomRange.collectAsState()
    val exposureRange by viewModel.availableExposureRange.collectAsState()
    val authSettings by viewModel.authSettings.collectAsState()
    val streamingPort by viewModel.streamingPort.collectAsState()
    val jpegQuality by viewModel.jpegQuality.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetToDefaults() }) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsSection(title = "Exposure") {
                    SliderSetting(
                        title = "Exposure Compensation",
                        value = settings.exposureCompensation.toFloat(),
                        range = exposureRange.start.toFloat()..exposureRange.endInclusive.toFloat(),
                        onValueChange = { viewModel.updateExposure(it.toInt()) }
                    )
                    DropdownSetting(
                        title = "ISO",
                        options = listOf("Auto", "100", "200", "400", "800", "1600", "3200"),
                        selected = settings.iso?.toString() ?: "Auto",
                        onSelect = { viewModel.updateIso(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Focus") {
                    DropdownSetting(
                        title = "Focus Mode",
                        options = FocusMode.entries.map { it.name },
                        selected = settings.focusMode.name,
                        onSelect = { viewModel.updateFocusMode(it) }
                    )
                    if (settings.focusMode == FocusMode.MANUAL) {
                        SliderSetting(
                            title = "Focus Distance",
                            value = settings.focusDistance ?: 0f,
                            range = 0f..10f,
                            onValueChange = { viewModel.updateFocusDistance(it) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "White Balance") {
                    DropdownSetting(
                        title = "White Balance",
                        options = WhiteBalance.entries.map { it.name },
                        selected = settings.whiteBalance.name,
                        onSelect = { viewModel.updateWhiteBalance(it) }
                    )
                    if (settings.whiteBalance == WhiteBalance.MANUAL) {
                        SliderSetting(
                            title = "Color Temperature (K)",
                            value = (settings.colorTemperature ?: 5500).toFloat(),
                            range = 2000f..9000f,
                            onValueChange = { viewModel.updateColorTemperature(it.toInt()) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Lens") {
                    SliderSetting(
                        title = "Zoom",
                        value = settings.zoomRatio,
                        range = zoomRange,
                        onValueChange = { viewModel.updateZoom(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Capture") {
                    DropdownSetting(
                        title = "Resolution",
                        options = Resolution.entries.map { it.name },
                        selected = settings.resolution.name,
                        onSelect = { viewModel.updateResolution(it) }
                    )
                    SliderSetting(
                        title = "Frame Rate",
                        value = settings.frameRate.toFloat(),
                        range = 15f..60f,
                        onValueChange = { viewModel.updateFrameRate(it.toInt()) }
                    )
                    DropdownSetting(
                        title = "HDR",
                        options = HdrMode.entries.map { it.name },
                        selected = settings.hdrMode.name,
                        onSelect = { viewModel.updateHdrMode(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Video") {
                    SwitchSetting(
                        title = "Image Stabilization",
                        checked = settings.stabilization,
                        onCheckedChange = { viewModel.updateStabilization(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Scene") {
                    DropdownSetting(
                        title = "Scene Mode",
                        options = listOf("OFF", "FACE_DETECTION", "NIGHT", "HDR", "SUNSET", "FIREWORKS"),
                        selected = settings.sceneMode ?: "OFF",
                        onSelect = { viewModel.updateSceneMode(it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Streaming") {
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
                        OutlinedTextField(
                            value = authSettings.password,
                            onValueChange = { viewModel.updateAuthPassword(it) },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (value == value.toInt().toFloat()) "${value.toInt()}" else String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun DropdownSetting(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    label = option.replace("_", " "),
                    selected = option == selected,
                    onClick = { onSelect(option) }
                )
            }
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
