package com.raulshma.lenscast.settings

import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.ui.components.LensCastSectionCard
import com.raulshma.lenscast.ui.components.LensCastTopBar

@Composable
fun CameraSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAppSettings: () -> Unit = {},
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

    val settings by viewModel.settings.collectAsState()
    val zoomRange by viewModel.availableZoomRange.collectAsState()
    val exposureRange by viewModel.availableExposureRange.collectAsState()
    val showPreview by viewModel.showPreview.collectAsState()

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = "Camera Settings",
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = onNavigateToAppSettings) {
                        Text("App Settings")
                    }
                    TextButton(onClick = { viewModel.resetToDefaults() }) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
                    }
                },
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
                SettingsSection(title = "Night Vision / IR") {
                    DropdownSetting(
                        title = "Mode",
                        options = NightVisionMode.entries.map { it.name },
                        selected = settings.nightVisionMode.name,
                        onSelect = { viewModel.updateNightVisionMode(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (settings.nightVisionMode) {
                            NightVisionMode.ON -> "Forces night scene mode with maximum exposure and reduced frame rate for best low-light performance."
                            NightVisionMode.AUTO -> "Automatically adapts to lighting conditions using night portrait mode with auto flash."
                            NightVisionMode.OFF -> "Standard camera behavior without low-light enhancements."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    LensCastSectionCard(title = title) {
        content()
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (value == value.toInt().toFloat()) "${value.toInt()}" else String.format("%.1f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
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
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
