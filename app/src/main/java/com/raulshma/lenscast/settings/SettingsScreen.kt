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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditor
import com.raulshma.lenscast.camera.model.QuickSettingRanges
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.camera.model.chipLabel
import com.raulshma.lenscast.ui.components.LensCastSectionCard
import com.raulshma.lenscast.ui.components.LensCastTopBar

/**
 * The settings screen's dropdown wiring, read off the Quick Setting Catalog's
 * chips editors: the exact option list (and the exact selected-name string
 * the catalog's write transform parses back) that the camera screen's sheet
 * offers, resolved against the device's live ranges. Labels render through
 * the catalog's default chip-label rule — the sheet's night-vision display
 * names stay the camera screen's.
 */
internal fun chipOptions(type: QuickSettingType, ranges: QuickSettingRanges): List<String> =
    chipsEditor(type).options(ranges)

/** The catalog's selected-name for the current settings — what the write path parses back. */
internal fun chipSelected(type: QuickSettingType, settings: CameraSettings): String =
    chipsEditor(type).selected(settings)

private fun chipsEditor(type: QuickSettingType): QuickSettingEditor.Chips =
    QuickSettingCatalog.descriptorFor(type).editor as QuickSettingEditor.Chips

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
            app.cameraService, app.settingsDataStore, app.powerManager,
            app.detectionModelStore,
        )
    )

    LaunchedEffect(activity) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    val settings by viewModel.settings.collectAsState()
    val zoomRange by viewModel.availableZoomRange.collectAsState()
    val exposureRange by viewModel.availableExposureRange.collectAsState()
    val isoRange by viewModel.availableIsoRange.collectAsState()
    val deviceRanges = remember(isoRange, zoomRange, exposureRange) {
        QuickSettingRanges(iso = isoRange, zoom = zoomRange, exposure = exposureRange)
    }
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
                DisplaySettingsSection(
                    showPreview = showPreview,
                    onTogglePreview = { viewModel.updateShowPreview(it) },
                )
            }

            item {
                SettingsSection(title = "Exposure") {
                    SliderSetting(
                        title = "Exposure Compensation",
                        value = settings.exposureCompensation.toFloat(),
                        range = exposureRange.start.toFloat()..exposureRange.endInclusive.toFloat(),
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.EXPOSURE, it) }
                    )
                    DropdownSetting(
                        title = "ISO",
                        options = chipOptions(QuickSettingType.ISO, deviceRanges),
                        selected = chipSelected(QuickSettingType.ISO, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.ISO, it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Focus") {
                    DropdownSetting(
                        title = "Focus Mode",
                        options = chipOptions(QuickSettingType.FOCUS, deviceRanges),
                        selected = chipSelected(QuickSettingType.FOCUS, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.FOCUS, it) }
                    )
                    if (settings.focusMode == FocusMode.MANUAL) {
                        SliderSetting(
                            title = "Focus Distance",
                            value = settings.focusDistance ?: 0f,
                            range = QuickSettingCatalog.focusDistanceRange(),
                            onValueChange = { viewModel.updateFocusDistance(it) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "White Balance") {
                    DropdownSetting(
                        title = "White Balance",
                        options = chipOptions(QuickSettingType.WHITE_BALANCE, deviceRanges),
                        selected = chipSelected(QuickSettingType.WHITE_BALANCE, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.WHITE_BALANCE, it) }
                    )
                    if (settings.whiteBalance == WhiteBalance.MANUAL) {
                        SliderSetting(
                            title = "Color Temperature (K)",
                            value = (settings.colorTemperature ?: CameraSettings.DEFAULT_COLOR_TEMPERATURE_K).toFloat(),
                            range = QuickSettingCatalog.colorTemperatureRange(),
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
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.ZOOM, it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Capture") {
                    DropdownSetting(
                        title = "Resolution",
                        options = chipOptions(QuickSettingType.RESOLUTION, deviceRanges),
                        selected = chipSelected(QuickSettingType.RESOLUTION, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.RESOLUTION, it) }
                    )
                    SliderSetting(
                        title = "Frame Rate",
                        value = settings.frameRate.toFloat(),
                        range = QuickSettingCatalog.frameRateRange(),
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.FRAME_RATE, it) }
                    )
                    DropdownSetting(
                        title = "HDR",
                        options = chipOptions(QuickSettingType.HDR, deviceRanges),
                        selected = chipSelected(QuickSettingType.HDR, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.HDR, it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Video") {
                    SwitchSetting(
                        title = "Image Stabilization",
                        checked = settings.stabilization,
                        onCheckedChange = { viewModel.updateQuickSetting(QuickSettingType.STABILIZATION, it) }
                    )
                }
            }

            item {
                SettingsSection(title = "Night Vision / IR") {
                    DropdownSetting(
                        title = "Mode",
                        options = chipOptions(QuickSettingType.NIGHT_VISION, deviceRanges),
                        selected = chipSelected(QuickSettingType.NIGHT_VISION, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.NIGHT_VISION, it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = QuickSettingCatalog.nightVisionDescription(settings.nightVisionMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingsSection(title = "Scene") {
                    DropdownSetting(
                        title = "Scene Mode",
                        options = QuickSettingCatalog.sceneModeOptions,
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
    steps: Int = 0,
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
                text = CameraDashboardPolicy.sliderValueLabel(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
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
                    label = chipLabel(option),
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
