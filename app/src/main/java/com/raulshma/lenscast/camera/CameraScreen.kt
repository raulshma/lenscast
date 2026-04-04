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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.ui.theme.LensOrange
import com.raulshma.lenscast.ui.theme.LensRed
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OverlayScrim = Color(0xB3000000)
private val OverlayLight = Color(0x80000000)
private val TopGradientColor = Color(0x78000000)
private val BottomGradientColor = Color(0x78000000)

private enum class QuickSettingType {
    EXPOSURE, ISO, WHITE_BALANCE, FOCUS, ZOOM, HDR, RESOLUTION, FRAME_RATE, STABILIZATION, NIGHT_VISION
}

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
    val adaptiveBitrateState by viewModel.adaptiveBitrateState.collectAsState()
    val connectionQualityStats by viewModel.connectionQualityStats.collectAsState()
    val hasAudioPermission by viewModel.hasAudioPermission.collectAsState()

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionResult(
            cameraGranted = results[Manifest.permission.CAMERA] == true,
            audioGranted = results[Manifest.permission.RECORD_AUDIO] == true,
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAudioPermissionResult(granted)
    }

    val coroutineScope = rememberCoroutineScope()
    val flashAlpha = remember { Animatable(0f) }

    var quickSettingsExpanded by remember { mutableStateOf(false) }
    var activeSetting by remember { mutableStateOf<QuickSettingType?>(null) }
    var isPinching by remember { mutableStateOf(false) }
    var pinchZoomRatio by remember { mutableFloatStateOf(1f) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var requestedMissingAudioPermission by remember { mutableStateOf(false) }

    LaunchedEffect(cameraState, hasAudioPermission) {
        if (cameraState is CameraState.Ready &&
            !hasAudioPermission &&
            !requestedMissingAudioPermission
        ) {
            requestedMissingAudioPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    when (cameraState) {
        is CameraState.RequestPermission -> CameraPermissionRequest(
            onRequestPermission = {
                mediaPermissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }
        )
        is CameraState.Initializing -> CameraInitializingScreen()
        is CameraState.Error -> ErrorDisplay(
            message = (cameraState as CameraState.Error).message,
            onRetry = { viewModel.retryCameraInit() }
        )
        is CameraState.Ready -> {
            ImmersiveCameraView(
                viewModel = viewModel,
                streamStatus = streamStatus,
                thermalState = thermalState,
                isRecording = isRecording,
                recordingElapsedSeconds = recordingElapsedSeconds,
                wifiConnected = wifiConnected,
                availableLenses = availableLenses,
                selectedLensIndex = selectedLensIndex,
                settings = settings,
                showPreview = showPreview,
                adaptiveBitrateState = adaptiveBitrateState,
                connectionQualityStats = connectionQualityStats,
                quickSettingsExpanded = quickSettingsExpanded,
                activeSetting = activeSetting,
                flashAlpha = flashAlpha,
                isPinching = isPinching,
                pinchZoomRatio = pinchZoomRatio,
                onPinchStateChange = { pinching, ratio ->
                    isPinching = pinching
                    pinchZoomRatio = ratio
                },
                onToggleQuickSettings = {
                    quickSettingsExpanded = !quickSettingsExpanded
                    if (!quickSettingsExpanded) activeSetting = null
                },
                onQuickSettingTap = { type ->
                    activeSetting = if (activeSetting == type) null else type
                },
                onCapture = {
                    viewModel.capturePhoto()
                    coroutineScope.launch {
                        flashAlpha.snapTo(1f)
                        flashAlpha.animateTo(0f, animationSpec = tween(150))
                    }
                },
                onStreamToggle = { viewModel.toggleStreaming() },
                onRecord = { viewModel.toggleRecording() },
                onSwitchCamera = { viewModel.switchCamera() },
                onTogglePreview = { viewModel.togglePreview() },
                onNavigateToGallery = onNavigateToGallery,
                onNavigateToCapture = onNavigateToCapture,
                onNavigateToSettings = onNavigateToSettings,
                onCopyStreamUrl = { viewModel.copyStreamUrl() },
                onCopyRtspUrl = { viewModel.copyRtspUrl() },
                onToggleServer = { viewModel.toggleServer() },
                onSelectLens = { viewModel.selectLens(it) },
            )

            if (activeSetting != null) {
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
                    onUpdateNightVisionMode = { viewModel.updateNightVisionMode(it) },
                )
            }
        }
        is CameraState.Idle -> {}
    }
}

@Composable
private fun ImmersiveCameraView(
    viewModel: CameraViewModel,
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    thermalState: ThermalState,
    isRecording: Boolean,
    recordingElapsedSeconds: Int,
    wifiConnected: Boolean,
    availableLenses: List<CameraLensInfo>,
    selectedLensIndex: Int,
    settings: CameraSettings,
    showPreview: Boolean,
    adaptiveBitrateState: com.raulshma.lenscast.streaming.AdaptiveBitrateController.AdaptiveState,
    connectionQualityStats: com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkStatsSnapshot?,
    quickSettingsExpanded: Boolean,
    activeSetting: QuickSettingType?,
    flashAlpha: Animatable<Float, *>,
    isPinching: Boolean,
    pinchZoomRatio: Float,
    onToggleQuickSettings: () -> Unit,
    onQuickSettingTap: (QuickSettingType) -> Unit,
    onCapture: () -> Unit,
    onStreamToggle: () -> Unit,
    onRecord: () -> Unit,
    onSwitchCamera: () -> Unit,
    onTogglePreview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onToggleServer: () -> Unit,
    onSelectLens: (Int) -> Unit,
    onPinchStateChange: (Boolean, Float) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (showPreview) {
            CameraPreview(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
                isPinching = isPinching,
                pinchZoomRatio = pinchZoomRatio,
                onPinchStateChange = onPinchStateChange
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Preview Hidden",
                        color = Color.White.copy(alpha = 0.35f),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(TopGradientColor, Color.Transparent)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, BottomGradientColor)
                    )
                )
        )

        CameraTopOverlay(
            streamStatus = streamStatus,
            isRecording = isRecording,
            recordingElapsedSeconds = recordingElapsedSeconds,
            showPreview = showPreview,
            onSwitchCamera = onSwitchCamera,
            onTogglePreview = onTogglePreview,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToCapture = onNavigateToCapture,
            onNavigateToSettings = onNavigateToSettings,
            onToggleQuickSettings = onToggleQuickSettings,
            onCopyStreamUrl = onCopyStreamUrl,
            onCopyRtspUrl = onCopyRtspUrl,
            onToggleServer = onToggleServer,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        if (!wifiConnected && streamStatus.isServerRunning) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
                color = LensOrange.copy(alpha = 0.92f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (streamStatus.isActive) "Not on WiFi"
                        else "Not on WiFi — server may not be reachable",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }

        CameraBottomOverlay(
            streamStatus = streamStatus,
            availableLenses = availableLenses,
            selectedLensIndex = selectedLensIndex,
            settings = settings,
            quickSettingsExpanded = quickSettingsExpanded,
            activeSetting = activeSetting,
            isRecording = isRecording,
            onStreamToggle = onStreamToggle,
            onCapture = onCapture,
            onRecord = onRecord,
            onSelectLens = onSelectLens,
            onToggleQuickSettings = onToggleQuickSettings,
            onQuickSettingTap = onQuickSettingTap,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        )

        if (thermalState != ThermalState.NORMAL && thermalState != ThermalState.LIGHT) {
            ThermalWarningOverlay(
                thermalState = thermalState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 200.dp)
            )
        }

        if (streamStatus.isActive && adaptiveBitrateState.enabled) {
            ConnectionQualityIndicator(
                qualityLevel = adaptiveBitrateState.qualityLevel,
                currentQuality = adaptiveBitrateState.currentQuality,
                currentFps = adaptiveBitrateState.currentFps,
                activeClients = adaptiveBitrateState.activeClients,
                minThroughputKbps = adaptiveBitrateState.minClientThroughputKbps,
                estimatedBandwidthKbps = adaptiveBitrateState.estimatedBandwidthKbps,
                stats = connectionQualityStats,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 56.dp, end = 8.dp)
            )
        }

        if (isPinching) {
            ZoomIndicator(
                zoomRatio = pinchZoomRatio,
                modifier = Modifier
                    .align(Alignment.Center)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraTopOverlay(
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    isRecording: Boolean,
    recordingElapsedSeconds: Int,
    showPreview: Boolean,
    onSwitchCamera: () -> Unit,
    onTogglePreview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleQuickSettings: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CameraControlButton(
                icon = Icons.Default.Cameraswitch,
                contentDescription = "Switch camera",
                onClick = onSwitchCamera
            )
            if (streamStatus.isActive) {
                StreamIndicator(streamStatus = streamStatus)
            }
            if (isRecording) {
                RecordingIndicator(elapsedSeconds = recordingElapsedSeconds)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerStatusButton(
                streamStatus = streamStatus,
                onCopyUrl = onCopyStreamUrl,
                onCopyRtspUrl = onCopyRtspUrl,
                onToggleServer = onToggleServer,
            )
            CameraControlButton(
                icon = if (showPreview) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (showPreview) "Hide preview" else "Show preview",
                onClick = onTogglePreview
            )
            CameraControlButton(
                icon = Icons.Default.Collections,
                contentDescription = "Gallery",
                onClick = onNavigateToGallery
            )
            Box {
                CameraControlButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Camera controls",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleQuickSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Capture tools",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onNavigateToCapture()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Settings",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onNavigateToSettings()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Surface(
        modifier = modifier.size(40.dp),
        color = OverlayScrim,
        shape = CircleShape,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CameraBottomOverlay(
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    availableLenses: List<CameraLensInfo>,
    selectedLensIndex: Int,
    settings: CameraSettings,
    quickSettingsExpanded: Boolean,
    activeSetting: QuickSettingType?,
    isRecording: Boolean,
    onStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onSelectLens: (Int) -> Unit,
    onToggleQuickSettings: () -> Unit,
    onQuickSettingTap: (QuickSettingType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom
    ) {
        AnimatedVisibility(
            visible = quickSettingsExpanded,
            enter = fadeIn(tween(200)) + androidx.compose.animation.expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            ),
            exit = fadeOut(tween(150)) + androidx.compose.animation.shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = tween(150)
            )
        ) {
            HorizontalQuickSettingsBar(
                settings = settings,
                activeSetting = activeSetting,
                onSettingTap = onQuickSettingTap,
            )
        }

        if (availableLenses.size > 1) {
            LensSelectorRow(
                lenses = availableLenses,
                selectedIndex = selectedLensIndex,
                onLensSelected = onSelectLens
            )
        }

        ShutterRow(
            isStreaming = streamStatus.isActive,
            isRecording = isRecording,
            quickSettingsExpanded = quickSettingsExpanded,
            onStreamToggle = onStreamToggle,
            onCapture = onCapture,
            onRecord = onRecord,
            onToggleQuickSettings = onToggleQuickSettings,
        )
    }
}

@Composable
private fun ShutterRow(
    isStreaming: Boolean,
    isRecording: Boolean,
    quickSettingsExpanded: Boolean,
    onStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onToggleQuickSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = !quickSettingsExpanded,
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
                modifier = Modifier.size(52.dp),
                color = if (isStreaming) Color(0xFFD32F2F) else OverlayScrim,
                shape = CircleShape,
                onClick = onStreamToggle
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = if (isStreaming) "Stop streaming" else "Start streaming",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = quickSettingsExpanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100))
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                color = OverlayScrim,
                shape = CircleShape,
                onClick = onToggleQuickSettings
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Flip,
                        contentDescription = "Collapse quick settings",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        ShutterButton(onClick = onCapture)

        Surface(
            modifier = Modifier.size(52.dp),
            color = if (isRecording) Color(0xFFD32F2F) else OverlayScrim,
            shape = CircleShape,
            onClick = onRecord
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "Stop recording" else "Record video",
                    tint = if (isRecording) Color.White else Color(0xFFD32F2F),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            color = Color.White,
            shape = CircleShape,
            onClick = {
                coroutineScope.launch {
                    scale.snapTo(0.85f)
                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
                }
                onClick()
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Capture photo",
                    tint = Color(0xFF1A1A1A),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun HorizontalQuickSettingsBar(
    settings: CameraSettings,
    activeSetting: QuickSettingType?,
    onSettingTap: (QuickSettingType) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = OverlayScrim,
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingPill(
                icon = Icons.Default.Exposure,
                label = "${settings.exposureCompensation}",
                isActive = activeSetting == QuickSettingType.EXPOSURE,
                onClick = { onSettingTap(QuickSettingType.EXPOSURE) }
            )
            QuickSettingPill(
                icon = Icons.Default.Iso,
                label = settings.iso?.toString() ?: "A",
                isActive = activeSetting == QuickSettingType.ISO,
                onClick = { onSettingTap(QuickSettingType.ISO) }
            )
            QuickSettingPill(
                icon = Icons.Default.WbSunny,
                label = when (settings.whiteBalance) {
                    WhiteBalance.AUTO -> "AWB"
                    WhiteBalance.MANUAL -> "${settings.colorTemperature ?: 5500}K"
                    else -> settings.whiteBalance.name.take(3)
                },
                isActive = activeSetting == QuickSettingType.WHITE_BALANCE,
                onClick = { onSettingTap(QuickSettingType.WHITE_BALANCE) }
            )
            QuickSettingPill(
                icon = Icons.Default.Bolt,
                label = settings.focusMode.name.take(3),
                isActive = activeSetting == QuickSettingType.FOCUS,
                onClick = { onSettingTap(QuickSettingType.FOCUS) }
            )
            QuickSettingPill(
                icon = Icons.Default.ZoomIn,
                label = "${String.format("%.1f", settings.zoomRatio)}x",
                isActive = activeSetting == QuickSettingType.ZOOM,
                onClick = { onSettingTap(QuickSettingType.ZOOM) }
            )
            QuickSettingPill(
                icon = Icons.Default.HdrOn,
                label = settings.hdrMode.name,
                isActive = activeSetting == QuickSettingType.HDR,
                onClick = { onSettingTap(QuickSettingType.HDR) }
            )
            QuickSettingPill(
                icon = Icons.Default.Image,
                label = settings.resolution.name.replace("_", " ").take(5),
                isActive = activeSetting == QuickSettingType.RESOLUTION,
                onClick = { onSettingTap(QuickSettingType.RESOLUTION) }
            )
            QuickSettingPill(
                icon = Icons.Default.Speed,
                label = "${settings.frameRate}",
                isActive = activeSetting == QuickSettingType.FRAME_RATE,
                onClick = { onSettingTap(QuickSettingType.FRAME_RATE) }
            )
            QuickSettingPill(
                icon = Icons.Default.Handyman,
                label = if (settings.stabilization) "OIS" else "OFF",
                isActive = activeSetting == QuickSettingType.STABILIZATION,
                onClick = { onSettingTap(QuickSettingType.STABILIZATION) }
            )
            QuickSettingPill(
                icon = Icons.Default.NightsStay,
                label = when (settings.nightVisionMode) {
                    NightVisionMode.ON -> "IR"
                    NightVisionMode.AUTO -> "AUTO"
                    NightVisionMode.OFF -> "OFF"
                },
                isActive = activeSetting == QuickSettingType.NIGHT_VISION,
                onClick = { onSettingTap(QuickSettingType.NIGHT_VISION) }
            )
        }
    }
}

@Composable
private fun QuickSettingPill(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        else OverlayLight,
        animationSpec = tween(200)
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        onClick = {
            coroutineScope.launch {
                scale.snapTo(0.92f)
                scale.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
            }
            onClick()
        }
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isActive) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = label,
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
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
    onUpdateNightVisionMode: (String) -> Unit,
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                    QuickSettingType.NIGHT_VISION -> "Night Vision / IR"
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(20.dp))

            when (type) {
                QuickSettingType.EXPOSURE -> ProSliderControl(
                    value = settings.exposureCompensation.toFloat(),
                    range = -12f..12f,
                    label = "${settings.exposureCompensation}",
                    onValueChange = { onUpdateExposure(it.toInt()) }
                )
                QuickSettingType.ISO -> ProChipSelector(
                    options = listOf("Auto", "100", "200", "400", "800", "1600", "3200"),
                    selected = settings.iso?.toString() ?: "Auto",
                    onSelect = onUpdateIso
                )
                QuickSettingType.WHITE_BALANCE -> ProChipSelector(
                    options = WhiteBalance.entries.map { it.name },
                    selected = settings.whiteBalance.name,
                    onSelect = onUpdateWhiteBalance
                )
                QuickSettingType.FOCUS -> ProChipSelector(
                    options = FocusMode.entries.map { it.name },
                    selected = settings.focusMode.name,
                    onSelect = onUpdateFocusMode
                )
                QuickSettingType.ZOOM -> ProSliderControl(
                    value = settings.zoomRatio,
                    range = 0.5f..10f,
                    label = "${String.format("%.1f", settings.zoomRatio)}x",
                    onValueChange = onUpdateZoom
                )
                QuickSettingType.HDR -> ProChipSelector(
                    options = HdrMode.entries.map { it.name },
                    selected = settings.hdrMode.name,
                    onSelect = onUpdateHdrMode
                )
                QuickSettingType.RESOLUTION -> ProChipSelector(
                    options = Resolution.entries.map { it.name },
                    selected = settings.resolution.name,
                    onSelect = onUpdateResolution
                )
                QuickSettingType.FRAME_RATE -> ProSliderControl(
                    value = settings.frameRate.toFloat(),
                    range = 15f..60f,
                    label = "${settings.frameRate} fps",
                    onValueChange = { onUpdateFrameRate(it.toInt()) }
                )
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
                        Switch(
                            checked = settings.stabilization,
                            onCheckedChange = onUpdateStabilization,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                QuickSettingType.NIGHT_VISION -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Mode",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NightVisionMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.nightVisionMode == mode,
                                    onClick = { onUpdateNightVisionMode(mode.name) },
                                    label = {
                                        Text(
                                            text = when (mode) {
                                                NightVisionMode.ON -> "IR On"
                                                NightVisionMode.AUTO -> "Auto"
                                                NightVisionMode.OFF -> "Off"
                                            }
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (settings.nightVisionMode) {
                                NightVisionMode.ON -> "Forces night scene mode with maximum exposure and reduced frame rate for best low-light performance."
                                NightVisionMode.AUTO -> "Automatically adapts to lighting conditions using night portrait mode with auto flash."
                                NightVisionMode.OFF -> "Standard camera behavior without low-light enhancements."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
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
            colors = SliderDefaults.colors(
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
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(200)
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Camera access required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "LensCast needs camera access for the live preview and microphone for audio streaming and recordings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Grant permission",
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    isPinching: Boolean = false,
    pinchZoomRatio: Float = 1f,
    onPinchStateChange: (Boolean, Float) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val settings by viewModel.settings.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(previewView, lifecycleOwner) {
        viewModel.startPreview(previewView, lifecycleOwner)
        onDispose { viewModel.stopPreview() }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            val currentZoom = settings.zoomRatio
                            val newZoom = (currentZoom * zoom).coerceIn(0.5f, 10f)
                            if ((newZoom - currentZoom).absoluteValue > 0.01f) {
                                viewModel.updateZoom(newZoom)
                                onPinchStateChange(true, newZoom)
                            }
                        }
                    }
                }
        )
    }

    LaunchedEffect(isPinching) {
        if (!isPinching) {
            delay(800)
            onPinchStateChange(false, settings.zoomRatio)
        }
    }
}

@Composable
private fun CameraInitializingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Initializing camera\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "!",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Camera error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun StreamIndicator(
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFFD32F2F).copy(alpha = 0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = CircleShape,
                    color = Color.White
                ) {}
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
            }
        }

        if (streamStatus.isWebEnabled) {
            StreamBadge(icon = Icons.Default.Wifi, label = "WEB")
        }
        if (streamStatus.isRtspEnabled) {
            StreamBadge(icon = Icons.Default.Videocam, label = "RTSP")
        }
    }
}

@Composable
private fun StreamBadge(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = OverlayScrim,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp
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
        color = OverlayScrim,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = Color(0xFFD32F2F).copy(alpha = dotAlpha)
            ) {}
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun ServerStatusButton(
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    onCopyUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val iconTint by animateColorAsState(
        targetValue = when {
            streamStatus.isActive -> Color(0xFF4CAF50)
            streamStatus.isServerRunning -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.4f)
        },
        animationSpec = tween(300),
        label = "server_status_tint"
    )

    Box(modifier = modifier) {
        CameraControlButton(
            icon = Icons.Default.Wifi,
            contentDescription = "Web server status",
            onClick = { expanded = true },
            tint = iconTint,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            "Web Server",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (streamStatus.url.isNotBlank()) {
                            Text(
                                streamStatus.url,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 1,
                            )
                        } else {
                            Text(
                                "Server offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            when {
                                streamStatus.clientCount > 0 -> "${streamStatus.clientCount} viewer(s) connected"
                                streamStatus.isActive -> "Live stream active"
                                streamStatus.isServerRunning -> "Server ready"
                                else -> "Offline"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                onClick = { expanded = false },
                enabled = false,
            )
            if (streamStatus.url.isNotBlank()) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Copy URL", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = {
                        expanded = false
                        onCopyUrl()
                    },
                )
            }
            if (streamStatus.rtspUrl.isNotBlank()) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                "RTSP Stream",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                streamStatus.rtspUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = { expanded = false },
                    enabled = false,
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Copy RTSP URL", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = {
                        expanded = false
                        onCopyRtspUrl()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (streamStatus.isServerRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (streamStatus.isServerRunning) "Turn off server" else "Turn on server",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = streamStatus.isServerRunning,
                            onCheckedChange = null,
                            thumbContent = if (streamStatus.isServerRunning) {
                                { Icon(Icons.Default.Check, null, Modifier.size(12.dp)) }
                            } else null,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onToggleServer()
                },
            )
        }
    }
}

@Composable
private fun ThermalWarningOverlay(
    thermalState: ThermalState,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (thermalState) {
        ThermalState.MODERATE -> LensOrange to "Thermal: Moderate"
        ThermalState.SEVERE -> LensRed to "Thermal: Severe"
        ThermalState.CRITICAL -> MaterialTheme.colorScheme.error to "Thermal: Critical!"
        else -> LensOrange to "Thermal: Warm"
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.92f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@Composable
private fun ZoomIndicator(
    zoomRatio: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = OverlayScrim.copy(alpha = 0.85f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${String.format("%.1f", zoomRatio)}x",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun ConnectionQualityIndicator(
    qualityLevel: NetworkQualityLevel,
    currentQuality: Int,
    currentFps: Int,
    activeClients: Int,
    minThroughputKbps: Int,
    estimatedBandwidthKbps: Int,
    stats: com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkStatsSnapshot?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val (dotColor, label) = when (qualityLevel) {
        NetworkQualityLevel.EXCELLENT -> Color(0xFF4CAF50) to "EXC"
        NetworkQualityLevel.GOOD -> Color(0xFF8BC34A) to "GOOD"
        NetworkQualityLevel.FAIR -> Color(0xFFFFC107) to "FAIR"
        NetworkQualityLevel.POOR -> Color(0xFFFF9800) to "POOR"
        NetworkQualityLevel.CRITICAL -> Color(0xFFF44336) to "CRIT"
    }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.clickable { expanded = !expanded },
            color = OverlayScrim,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = CircleShape,
                        color = dotColor
                    ) {}
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${currentQuality}q ${currentFps}fps",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                )
                if (activeClients > 0) {
                    Text(
                        text = "${activeClients} client${if (activeClients != 1) "s" else ""} · ${minThroughputKbps}kbps",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        if (expanded && stats != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(top = 40.dp)
                    .width(220.dp),
                color = Color(0xDD1C1C1E),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Connection Quality",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConnectionStatRow(label = "Quality", value = label, valueColor = dotColor)
                    ConnectionStatRow(label = "Bandwidth", value = "${estimatedBandwidthKbps} kbps")
                    ConnectionStatRow(label = "Min Throughput", value = "${stats.minThroughputKbps} kbps")
                    ConnectionStatRow(label = "Avg Throughput", value = "${stats.avgThroughputKbps} kbps")
                    ConnectionStatRow(label = "Latency", value = "${stats.worstLatencyMs} ms")
                    ConnectionStatRow(label = "Avg Frame", value = "${stats.avgFrameSizeBytes / 1024} KB")
                    ConnectionStatRow(label = "Clients", value = "${stats.activeClients}")
                    ConnectionStatRow(label = "Total Sent", value = formatBytes(stats.totalBytesSent))

                    if (stats.clientDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Per-Client Stats",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        stats.clientDetails.forEach { (clientId, detail) ->
                            Text(
                                text = "Client ${clientId.take(8)}:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            )
                            ConnectionStatRow(label = "  Frames", value = "${detail.framesSent}")
                            ConnectionStatRow(label = "  Throughput", value = "${detail.avgThroughputKbps} kbps")
                            ConnectionStatRow(label = "  Latency", value = "${detail.lastSendDurationMs} ms")
                            ConnectionStatRow(label = "  Frame Size", value = "${detail.lastFrameSizeBytes / 1024} KB")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.5f)
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = valueColor
            )
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
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
        color = OverlayScrim.copy(alpha = 0.6f),
        shape = RoundedCornerShape(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            lenses.forEachIndexed { index, lens ->
                if (index > 0) Spacer(modifier = Modifier.width(6.dp))
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
                        selectedContainerColor = Color.White.copy(alpha = 0.25f),
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.08f),
                        labelColor = Color.White.copy(alpha = 0.7f),
                    ),
                    border = null,
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }
}
