package com.raulshma.lenscast.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditor
import com.raulshma.lenscast.camera.model.QuickSettingRanges
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.PhotoCapturePlan
import com.raulshma.lenscast.camera.model.SelfTimerMode
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
            app.audioModelStore,
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
    val gridStyle by viewModel.gridStyle.collectAsState()
    val selfTimer by viewModel.selfTimer.collectAsState()
    val spiritLevelEnabled by viewModel.spiritLevelEnabled.collectAsState()
    val histogramEnabled by viewModel.histogramEnabled.collectAsState()
    val zebrasEnabled by viewModel.zebrasEnabled.collectAsState()
    val peakingEnabled by viewModel.peakingEnabled.collectAsState()
    val photoJpegQuality by viewModel.photoJpegQuality.collectAsState()
    val photoMaximizeQuality by viewModel.photoMaximizeQuality.collectAsState()
    val rawCaptureEnabled by viewModel.rawCaptureEnabled.collectAsState()
    val rawCaptureSupported by viewModel.isRawCaptureSupported.collectAsState()
    val geotagEnabled by viewModel.geotagEnabled.collectAsState()

    // Opt-in GPS geotag: the runtime ask happens only on the setting's
    // enable transition, never at launch — the same point-of-need pattern
    // as the camera screen's first-launch POST_NOTIFICATIONS pass.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(
        topBar = {
            LensCastTopBar(
                title = stringResource(R.string.settings_camera_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = onNavigateToAppSettings) {
                        Text(stringResource(R.string.settings_app_title))
                    }
                    TextButton(onClick = { viewModel.resetToDefaults() }) {
                        Text(stringResource(R.string.settings_reset), color = MaterialTheme.colorScheme.error)
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
                SettingsSection(title = stringResource(R.string.settings_section_exposure)) {
                    SliderSetting(
                        title = stringResource(R.string.settings_exposure_compensation),
                        value = settings.exposureCompensation.toFloat(),
                        range = exposureRange.start.toFloat()..exposureRange.endInclusive.toFloat(),
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.EXPOSURE, it) }
                    )
                    DropdownSetting(
                        title = stringResource(R.string.settings_iso),
                        options = chipOptions(QuickSettingType.ISO, deviceRanges),
                        selected = chipSelected(QuickSettingType.ISO, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.ISO, it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_focus)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_focus_mode),
                        options = chipOptions(QuickSettingType.FOCUS, deviceRanges),
                        selected = chipSelected(QuickSettingType.FOCUS, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.FOCUS, it) }
                    )
                    if (settings.focusMode == FocusMode.MANUAL) {
                        SliderSetting(
                            title = stringResource(R.string.settings_focus_distance),
                            value = settings.focusDistance ?: 0f,
                            range = QuickSettingCatalog.focusDistanceRange(),
                            onValueChange = { viewModel.updateFocusDistance(it) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_white_balance)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_white_balance),
                        options = chipOptions(QuickSettingType.WHITE_BALANCE, deviceRanges),
                        selected = chipSelected(QuickSettingType.WHITE_BALANCE, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.WHITE_BALANCE, it) }
                    )
                    if (settings.whiteBalance == WhiteBalance.MANUAL) {
                        SliderSetting(
                            title = stringResource(R.string.settings_color_temperature),
                            value = (settings.colorTemperature ?: CameraSettings.DEFAULT_COLOR_TEMPERATURE_K).toFloat(),
                            range = QuickSettingCatalog.colorTemperatureRange(),
                            onValueChange = { viewModel.updateColorTemperature(it.toInt()) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_lens)) {
                    SliderSetting(
                        title = stringResource(R.string.settings_zoom),
                        value = settings.zoomRatio,
                        range = zoomRange,
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.ZOOM, it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_capture)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_resolution),
                        options = chipOptions(QuickSettingType.RESOLUTION, deviceRanges),
                        selected = chipSelected(QuickSettingType.RESOLUTION, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.RESOLUTION, it) }
                    )
                    SliderSetting(
                        title = stringResource(R.string.settings_frame_rate),
                        value = settings.frameRate.toFloat(),
                        range = QuickSettingCatalog.frameRateRange(),
                        onValueChange = { viewModel.updateQuickSetting(QuickSettingType.FRAME_RATE, it) }
                    )
                    DropdownSetting(
                        title = stringResource(R.string.settings_hdr),
                        options = chipOptions(QuickSettingType.HDR, deviceRanges),
                        selected = chipSelected(QuickSettingType.HDR, settings),
                        onSelect = { viewModel.updateQuickSetting(QuickSettingType.HDR, it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_video)) {
                    SwitchSetting(
                        title = stringResource(R.string.settings_image_stabilization),
                        checked = settings.stabilization,
                        onCheckedChange = { viewModel.updateQuickSetting(QuickSettingType.STABILIZATION, it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_viewfinder_aids)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_grid_overlay),
                        options = GridStyle.entries.map { it.name },
                        selected = gridStyle.name,
                        onSelect = { viewModel.updateGridStyle(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownSetting(
                        title = stringResource(R.string.settings_self_timer),
                        options = SelfTimerMode.entries.map { it.name },
                        selected = selfTimer.name,
                        onSelect = { viewModel.updateSelfTimer(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_spirit_level),
                        checked = spiritLevelEnabled,
                        onCheckedChange = { viewModel.updateSpiritLevelEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_histogram),
                        checked = histogramEnabled,
                        onCheckedChange = { viewModel.updateHistogramEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_exposure_zebras),
                        checked = zebrasEnabled,
                        onCheckedChange = { viewModel.updateZebrasEnabled(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_focus_peaking),
                        checked = peakingEnabled,
                        onCheckedChange = { viewModel.updatePeakingEnabled(it) }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_photo_capture)) {
                    SliderSetting(
                        title = stringResource(R.string.settings_jpeg_quality),
                        value = photoJpegQuality.toFloat(),
                        range = PhotoCapturePlan.PHOTO_JPEG_QUALITY_MIN.toFloat()..
                                PhotoCapturePlan.PHOTO_JPEG_QUALITY_MAX.toFloat(),
                        onValueChange = { viewModel.updatePhotoJpegQuality(it.toInt()) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_maximize_quality),
                        checked = photoMaximizeQuality,
                        onCheckedChange = { viewModel.updatePhotoMaximizeQuality(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_raw_jpeg),
                        checked = rawCaptureEnabled,
                        onCheckedChange = { viewModel.updateRawCaptureEnabled(it) }
                    )
                    if (rawCaptureEnabled && !rawCaptureSupported) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_raw_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchSetting(
                        title = stringResource(R.string.settings_gps_geotag),
                        checked = geotagEnabled,
                        onCheckedChange = { enabled ->
                            // The ask rides the setting's first enable only;
                            // a denied grant degrades to credit-only EXIF.
                            if (enabled && ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_COARSE_LOCATION,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                locationPermissionLauncher.launch(
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            }
                            viewModel.updateGeotagEnabled(enabled)
                        }
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_night_vision)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_mode),
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
                SettingsSection(title = stringResource(R.string.settings_section_scene)) {
                    DropdownSetting(
                        title = stringResource(R.string.settings_scene_mode),
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

/**
 * The display name for a dropdown's wire option — the one resource map from
 * wire value to localized chip text, replacing the raw uppercase enum
 * spellings the dropdowns used to show. The wire value itself is untouched:
 * it stays the storage key, the `selected` comparison, and the value the
 * write path parses back, so only the DISPLAY routes through resources.
 * Anything without a mapping (numeric ISO stops, the NV* pixel formats,
 * audio device names, already-localized labels) falls back to the catalog's
 * underscore-to-space rule, exactly as before.
 */
@Composable
internal fun dropdownOptionLabel(option: String): String = when (option) {
    // Shared mode verdicts
    "Auto", "AUTO" -> stringResource(R.string.option_auto)
    "OFF" -> stringResource(R.string.option_off)
    "ON" -> stringResource(R.string.option_on)
    // FocusMode
    "MANUAL" -> stringResource(R.string.option_manual)
    "MACRO" -> stringResource(R.string.option_macro)
    "CONTINUOUS_PICTURE" -> stringResource(R.string.option_continuous_picture)
    "CONTINUOUS_VIDEO" -> stringResource(R.string.option_continuous_video)
    // WhiteBalance
    "DAYLIGHT" -> stringResource(R.string.option_daylight)
    "CLOUDY" -> stringResource(R.string.option_cloudy)
    "INDOOR" -> stringResource(R.string.option_indoor)
    "FLUORESCENT" -> stringResource(R.string.option_fluorescent)
    // Resolution
    "SD_480P" -> stringResource(R.string.option_sd_480p)
    "HD_720P" -> stringResource(R.string.option_hd_720p)
    "FHD_1080P" -> stringResource(R.string.option_fhd_1080p)
    "QHD_1440P" -> stringResource(R.string.option_qhd_1440p)
    "UHD_4K" -> stringResource(R.string.option_uhd_4k)
    // GridStyle
    "GRID_3X3" -> stringResource(R.string.option_grid_3x3)
    "GRID_4X4" -> stringResource(R.string.option_grid_4x4)
    "GOLDEN_RATIO" -> stringResource(R.string.option_golden_ratio)
    // SelfTimerMode
    "S3" -> stringResource(R.string.option_s3)
    "S10" -> stringResource(R.string.option_s10)
    // Scene modes
    "FACE_DETECTION" -> stringResource(R.string.option_face_detection)
    "NIGHT" -> stringResource(R.string.option_night)
    "HDR" -> stringResource(R.string.option_hdr)
    "SUNSET" -> stringResource(R.string.option_sunset)
    "FIREWORKS" -> stringResource(R.string.option_fireworks)
    else -> chipLabel(option)
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
                    label = dropdownOptionLabel(option),
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
