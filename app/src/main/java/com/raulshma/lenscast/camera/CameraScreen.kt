package com.raulshma.lenscast.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.ui.theme.LensOrange
import com.raulshma.lenscast.ui.theme.LensRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToGallery: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val viewModel: CameraViewModel = viewModel(
        factory = CameraViewModel.Factory(
            context, app.cameraService, app.streamingManager,
            app.powerManager, app.thermalMonitor, app.settingsDataStore
        )
    )

    val cameraState by viewModel.cameraState.collectAsState()
    val streamStatus by viewModel.streamStatus.collectAsState()
    val thermalState by viewModel.thermalState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingElapsedSeconds by viewModel.recordingElapsedSeconds.collectAsState()
    val wifiConnected by viewModel.wifiConnected.collectAsState()
    val availableLenses by viewModel.availableLenses.collectAsState()
    val selectedLensIndex by viewModel.selectedLensIndex.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showPreview by viewModel.showPreview.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    val coroutineScope = rememberCoroutineScope()
    val flashAlpha = remember { Animatable(0f) }

    var quickSettingsExpanded by remember { mutableStateOf(false) }
    var activeSetting by remember { mutableStateOf<QuickSettingType?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LensCast") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                actions = {
                    if (cameraState is CameraState.Ready) {
                        IconButton(onClick = { viewModel.togglePreview() }) {
                            Icon(
                                if (showPreview) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showPreview) "Hide preview" else "Show preview"
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToGallery) {
                        Icon(Icons.Default.Collections, contentDescription = "Gallery")
                    }
                    IconButton(onClick = onNavigateToCapture) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Capture")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (streamStatus.isServerRunning && streamStatus.url.isNotBlank()) {
                    StreamInfoBar(
                        url = streamStatus.url,
                        isStreaming = streamStatus.isActive,
                        clientCount = streamStatus.clientCount,
                        onCopyUrl = { viewModel.copyStreamUrl() }
                    )
                }
                if (cameraState is CameraState.Ready && availableLenses.size > 1) {
                    LensSelectorRow(
                        lenses = availableLenses,
                        selectedIndex = selectedLensIndex,
                        onLensSelected = { index -> viewModel.selectLens(index) }
                    )
                }
                BottomControlBar(
                    isStreaming = streamStatus.isActive,
                    isRecording = isRecording,
                    onStreamToggle = { viewModel.toggleStreaming() },
                    onCapture = {
                        viewModel.capturePhoto()
                        coroutineScope.launch {
                            flashAlpha.snapTo(1f)
                            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150))
                        }
                    },
                    onRecord = { viewModel.toggleRecording() },
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (cameraState) {
                is CameraState.RequestPermission -> {
                    CameraPermissionRequest(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }
                is CameraState.Ready -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showPreview) {
                            CameraPreview(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (!showPreview) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Preview Hidden",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        if (flashAlpha.value > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White.copy(alpha = flashAlpha.value))
                            )
                        }

                        ProQuickSettingsBar(
                            settings = settings,
                            expanded = quickSettingsExpanded,
                            onToggleExpand = {
                                quickSettingsExpanded = !quickSettingsExpanded
                                if (!quickSettingsExpanded) activeSetting = null
                            },
                            onSettingTap = { type ->
                                if (activeSetting == type) {
                                    activeSetting = null
                                } else {
                                    activeSetting = type
                                }
                            },
                            activeSetting = activeSetting,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
                is CameraState.Error -> {
                    ErrorDisplay(
                        message = (cameraState as CameraState.Error).message,
                        onRetry = {
                            viewModel.retryCameraInit()
                        }
                    )
                }
                is CameraState.Initializing -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Initializing camera...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                is CameraState.Idle -> {
                }
            }

            if (streamStatus.isActive) {
                StreamIndicator(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                )
            }

            if (isRecording) {
                RecordingIndicator(
                    elapsedSeconds = recordingElapsedSeconds,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = if (streamStatus.isActive) 44.dp else 16.dp)
                )
            }

            if (cameraState is CameraState.Ready) {
                IconButton(
                    onClick = { viewModel.switchCamera() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            if (thermalState != ThermalState.NORMAL
                && thermalState != ThermalState.LIGHT) {
                ThermalWarningOverlay(
                    thermalState = thermalState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }

            if (streamStatus.isServerRunning && !wifiConnected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    color = LensOrange.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (streamStatus.isActive) {
                            "Not on WiFi - stream may not be reachable"
                        } else {
                            "Not on WiFi - web server may not be reachable"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }
    }

    if (activeSetting != null && cameraState is CameraState.Ready) {
        QuickSettingSheet(
            type = activeSetting!!,
            settings = settings,
            sheetState = sheetState,
            onDismiss = { activeSetting = null },
            onUpdateExposure = { viewModel.updateExposure(it) },
            onUpdateIso = { viewModel.updateIso(it) },
            onUpdateFocusMode = { viewModel.updateFocusMode(it) },
            onUpdateWhiteBalance = { viewModel.updateWhiteBalance(it) },
            onUpdateZoom = { viewModel.updateZoom(it) },
            onUpdateHdrMode = { viewModel.updateHdrMode(it) },
            onUpdateFrameRate = { viewModel.updateFrameRate(it) },
            onUpdateResolution = { viewModel.updateResolution(it) },
            onUpdateStabilization = { viewModel.updateStabilization(it) },
        )
    }
}

private enum class QuickSettingType {
    EXPOSURE, ISO, WHITE_BALANCE, FOCUS, ZOOM, HDR, RESOLUTION, FRAME_RATE, STABILIZATION
}

@Composable
private fun ProQuickSettingsBar(
    settings: CameraSettings,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSettingTap: (QuickSettingType) -> Unit,
    activeSetting: QuickSettingType?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + slideInHorizontally(
                initialOffsetX = { it / 2 },
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(150)) + slideOutHorizontally(
                targetOffsetX = { it / 2 },
                animationSpec = tween(150)
            )
        ) {
            Column(
                modifier = Modifier
                    .width(52.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Exposure, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = "${settings.exposureCompensation}",
                    isActive = activeSetting == QuickSettingType.EXPOSURE,
                    onClick = { onSettingTap(QuickSettingType.EXPOSURE) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Iso, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = settings.iso?.toString() ?: "A",
                    isActive = activeSetting == QuickSettingType.ISO,
                    onClick = { onSettingTap(QuickSettingType.ISO) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = when (settings.whiteBalance) {
                        WhiteBalance.AUTO -> "AWB"
                        WhiteBalance.MANUAL -> "${settings.colorTemperature ?: 5500}K"
                        else -> settings.whiteBalance.name.take(3)
                    },
                    isActive = activeSetting == QuickSettingType.WHITE_BALANCE,
                    onClick = { onSettingTap(QuickSettingType.WHITE_BALANCE) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = settings.focusMode.name.take(3),
                    isActive = activeSetting == QuickSettingType.FOCUS,
                    onClick = { onSettingTap(QuickSettingType.FOCUS) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = "${String.format("%.1f", settings.zoomRatio)}x",
                    isActive = activeSetting == QuickSettingType.ZOOM,
                    onClick = { onSettingTap(QuickSettingType.ZOOM) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.HdrOn, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = settings.hdrMode.name,
                    isActive = activeSetting == QuickSettingType.HDR,
                    onClick = { onSettingTap(QuickSettingType.HDR) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = settings.resolution.name.replace("_", " ").take(5),
                    isActive = activeSetting == QuickSettingType.RESOLUTION,
                    onClick = { onSettingTap(QuickSettingType.RESOLUTION) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = "${settings.frameRate}",
                    isActive = activeSetting == QuickSettingType.FRAME_RATE,
                    onClick = { onSettingTap(QuickSettingType.FRAME_RATE) }
                )
                QuickSettingChip(
                    icon = { Icon(Icons.Default.Handyman, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = if (settings.stabilization) "OIS" else "OFF",
                    isActive = activeSetting == QuickSettingType.STABILIZATION,
                    onClick = { onSettingTap(QuickSettingType.STABILIZATION) }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    shape = CircleShape,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Flip,
                            contentDescription = "Collapse",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(200)) + scaleIn(
                initialScale = 0.8f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(100)) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(100)
            )
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
                shape = CircleShape,
                modifier = Modifier.padding(4.dp)
            ) {
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        Icons.Default.Flip,
                        contentDescription = "Quick settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickSettingChip(
    icon: @Composable () -> Unit,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        animationSpec = tween(200)
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable {
                coroutineScope.launch {
                    scale.snapTo(0.9f)
                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
                }
                onClick()
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(
            text = label,
            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 8.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSettingSheet(
    type: QuickSettingType,
    settings: CameraSettings,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onUpdateExposure: (Int) -> Unit,
    onUpdateIso: (String) -> Unit,
    onUpdateFocusMode: (String) -> Unit,
    onUpdateWhiteBalance: (String) -> Unit,
    onUpdateZoom: (Float) -> Unit,
    onUpdateHdrMode: (String) -> Unit,
    onUpdateFrameRate: (Int) -> Unit,
    onUpdateResolution: (String) -> Unit,
    onUpdateStabilization: (Boolean) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = when (type) {
                    QuickSettingType.EXPOSURE -> "Exposure Compensation"
                    QuickSettingType.ISO -> "ISO"
                    QuickSettingType.WHITE_BALANCE -> "White Balance"
                    QuickSettingType.FOCUS -> "Focus Mode"
                    QuickSettingType.ZOOM -> "Zoom"
                    QuickSettingType.HDR -> "HDR"
                    QuickSettingType.RESOLUTION -> "Resolution"
                    QuickSettingType.FRAME_RATE -> "Frame Rate"
                    QuickSettingType.STABILIZATION -> "Stabilization"
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(20.dp))

            when (type) {
                QuickSettingType.EXPOSURE -> {
                    ProSliderControl(
                        value = settings.exposureCompensation.toFloat(),
                        range = -12f..12f,
                        label = "${settings.exposureCompensation}",
                        onValueChange = { onUpdateExposure(it.toInt()) }
                    )
                }
                QuickSettingType.ISO -> {
                    ProChipSelector(
                        options = listOf("Auto", "100", "200", "400", "800", "1600", "3200"),
                        selected = settings.iso?.toString() ?: "Auto",
                        onSelect = onUpdateIso
                    )
                }
                QuickSettingType.WHITE_BALANCE -> {
                    ProChipSelector(
                        options = WhiteBalance.entries.map { it.name },
                        selected = settings.whiteBalance.name,
                        onSelect = onUpdateWhiteBalance
                    )
                }
                QuickSettingType.FOCUS -> {
                    ProChipSelector(
                        options = FocusMode.entries.map { it.name },
                        selected = settings.focusMode.name,
                        onSelect = onUpdateFocusMode
                    )
                }
                QuickSettingType.ZOOM -> {
                    ProSliderControl(
                        value = settings.zoomRatio,
                        range = 0.5f..10f,
                        label = "${String.format("%.1f", settings.zoomRatio)}x",
                        onValueChange = onUpdateZoom
                    )
                }
                QuickSettingType.HDR -> {
                    ProChipSelector(
                        options = HdrMode.entries.map { it.name },
                        selected = settings.hdrMode.name,
                        onSelect = onUpdateHdrMode
                    )
                }
                QuickSettingType.RESOLUTION -> {
                    ProChipSelector(
                        options = Resolution.entries.map { it.name },
                        selected = settings.resolution.name,
                        onSelect = onUpdateResolution
                    )
                }
                QuickSettingType.FRAME_RATE -> {
                    ProSliderControl(
                        value = settings.frameRate.toFloat(),
                        range = 15f..60f,
                        label = "${settings.frameRate} fps",
                        onValueChange = { onUpdateFrameRate(it.toInt()) }
                    )
                }
                QuickSettingType.STABILIZATION -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Image Stabilization",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.Switch(
                            checked = settings.stabilization,
                            onCheckedChange = onUpdateStabilization,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProSliderControl(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(16.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                thumbColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${range.start}".let { if (it.endsWith(".0")) it.dropLast(2) else it },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "${range.endInclusive}".let { if (it.endsWith(".0")) it.dropLast(2) else it },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ProChipSelector(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(200)
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                animationSpec = tween(200)
            )

            Surface(
                onClick = { onSelect(option) },
                color = bgColor,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = option.replace("_", " "),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionRequest(
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera permission is required",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "LensCast needs access to your camera to stream video.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun CameraPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(previewView, lifecycleOwner) {
        viewModel.startPreview(previewView, lifecycleOwner)
        onDispose { viewModel.stopPreview() }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun StreamIndicator(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error
            ) {}
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun RecordingIndicator(
    elapsedSeconds: Int,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )
    val h = elapsedSeconds / 3600
    val m = (elapsedSeconds % 3600) / 60
    val s = elapsedSeconds % 60
    val timeText = if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = dotAlpha)
            ) {}
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StreamInfoBar(
    url: String,
    isStreaming: Boolean,
    clientCount: Int,
    onCopyUrl: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1
                )
                Text(
                    text = when {
                        clientCount > 0 -> "$clientCount viewer(s)"
                        isStreaming -> "Live stream active"
                        else -> "Web server ready"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (clientCount > 0 && !isStreaming) {
                    Text(
                        text = "Waiting for live frames",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onCopyUrl,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy URL",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomControlBar(
    isStreaming: Boolean,
    isRecording: Boolean,
    onStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onStreamToggle,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = if (isStreaming) "Stop streaming" else "Start streaming",
                    tint = if (isStreaming) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(
                onClick = onCapture,
                modifier = Modifier.size(64.dp)
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Camera,
                        contentDescription = "Capture photo",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(32.dp)
                            .padding(2.dp)
                    )
                }
            }

            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "Stop recording" else "Record video",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ThermalWarningOverlay(
    thermalState: ThermalState,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (thermalState) {
        ThermalState.MODERATE ->
            LensOrange to "Thermal: Moderate"
        ThermalState.SEVERE ->
            LensRed to "Thermal: Severe"
        ThermalState.CRITICAL ->
            MaterialTheme.colorScheme.error to "Thermal: Critical!"
        else -> LensOrange to "Thermal: Warm"
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError
        )
    }
}

@Composable
private fun LensSelectorRow(
    lenses: List<CameraLensInfo>,
    selectedIndex: Int,
    onLensSelected: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            lenses.forEachIndexed { index, lens ->
                if (index > 0) Spacer(modifier = Modifier.width(8.dp))
                val isSelected = index == selectedIndex
                FilterChip(
                    selected = isSelected,
                    onClick = { onLensSelected(index) },
                    label = {
                        Text(
                            text = lens.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            ),
                            textAlign = TextAlign.Center,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }
}
