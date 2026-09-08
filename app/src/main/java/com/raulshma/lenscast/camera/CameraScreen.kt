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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.FlashlightOn
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
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.PreviewGestures
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingDescriptor
import com.raulshma.lenscast.camera.model.QuickSettingEditor
import com.raulshma.lenscast.camera.model.QuickSettingIcon
import com.raulshma.lenscast.camera.model.QuickSettingRanges
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.gallery.formatDuration
import com.raulshma.lenscast.ui.theme.LensOrange
import com.raulshma.lenscast.ui.theme.LensRed
import com.raulshma.lenscast.ui.theme.RecordingRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OverlayScrim = Color(0xB3000000)
private val OverlayLight = Color(0x80000000)
private val TopGradientColor = Color(0x78000000)
private val BottomGradientColor = Color(0x78000000)

/** The quick-setting controls' icon selectors, mapped to material vectors at this seam. */
private fun QuickSettingIcon.vector(): ImageVector = when (this) {
    QuickSettingIcon.EXPOSURE -> Icons.Default.Exposure
    QuickSettingIcon.ISO -> Icons.Default.Iso
    QuickSettingIcon.WHITE_BALANCE -> Icons.Default.WbSunny
    QuickSettingIcon.FOCUS -> Icons.Default.Bolt
    QuickSettingIcon.ZOOM -> Icons.Default.ZoomIn
    QuickSettingIcon.HDR -> Icons.Default.HdrOn
    QuickSettingIcon.RESOLUTION -> Icons.Default.Image
    QuickSettingIcon.FRAME_RATE -> Icons.Default.Speed
    QuickSettingIcon.STABILIZATION -> Icons.Default.Handyman
    QuickSettingIcon.NIGHT_VISION -> Icons.Default.NightsStay
    QuickSettingIcon.TORCH -> Icons.Default.FlashlightOn
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
            app.thermalMonitor, app.settingsDataStore, app.streamingSession,
            app.streamWatchdog, app.connectivityMonitor, app.recordingController,
            app.photoCaptureManager
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
    var showConnectSheet by remember { mutableStateOf(false) }
    var isPinching by remember { mutableStateOf(false) }
    var pinchZoomRatio by remember { mutableFloatStateOf(1f) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var requestedMissingAudioPermission by remember { mutableStateOf(false) }

    LaunchedEffect(cameraState, hasAudioPermission) {
        if (MicAccess.shouldAutoRequest(
                featureReady = cameraState is CameraState.Ready,
                granted = hasAudioPermission,
                alreadyRequested = requestedMissingAudioPermission,
            )
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
                onWebStreamToggle = { viewModel.toggleWebStreaming() },
                onRtspStreamToggle = { viewModel.toggleRtspStreaming() },
                onRecord = { viewModel.toggleRecording() },
                onSwitchCamera = { viewModel.switchCamera() },
                onTogglePreview = { viewModel.togglePreview() },
                onNavigateToGallery = onNavigateToGallery,
                onNavigateToCapture = onNavigateToCapture,
                onNavigateToSettings = onNavigateToSettings,
                onCopyStreamUrl = { viewModel.copyStreamUrl() },
                onCopyRtspUrl = { viewModel.copyRtspUrl() },
                onShowConnect = { showConnectSheet = true },
                onToggleServer = { viewModel.toggleServer() },
                onSelectLens = { viewModel.selectLens(it) },
            )

            if (showConnectSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showConnectSheet = false },
                ) {
                    val info = remember(showConnectSheet) { viewModel.getConnectInfo() }
                    val tlsFingerprint = remember(showConnectSheet) {
                        (context.applicationContext as MainApplication)
                            .streamingManager.tlsCertificateFingerprint()
                            .takeIf { info.httpUrl.startsWith("https://") }
                    }
                    ConnectSheet(
                        info = info,
                        currentPort = info.httpUrl.substringAfter(":", "").substringBefore("/")
                            .toIntOrNull() ?: StreamDefaults.WEB_PORT,
                        onCopyHttp = { viewModel.copyStreamUrl() },
                        onCopyHls = { viewModel.copyHlsUrl() },
                        onCopyRtsp = { viewModel.copyRtspUrl() },
                        tlsFingerprint = tlsFingerprint,
                    )
                }
            }

            if (activeSetting != null) {
                val isoRange by viewModel.availableIsoRange.collectAsState()
                val zoomRange by viewModel.availableZoomRange.collectAsState()
                val exposureRange by viewModel.availableExposureRange.collectAsState()
                QuickSettingSheet(
                    descriptor = QuickSettingCatalog.descriptorFor(activeSetting!!),
                    settings = settings,
                    ranges = QuickSettingRanges(
                        iso = isoRange,
                        zoom = zoomRange,
                        exposure = exposureRange,
                    ),
                    sheetState = sheetState,
                    onDismiss = { activeSetting = null },
                    onUpdate = { type, value -> viewModel.updateQuickSetting(type, value) },
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
    onWebStreamToggle: () -> Unit,
    onRtspStreamToggle: () -> Unit,
    onRecord: () -> Unit,
    onSwitchCamera: () -> Unit,
    onTogglePreview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onShowConnect: () -> Unit,
    onToggleServer: () -> Unit,
    onSelectLens: (Int) -> Unit,
    onPinchStateChange: (Boolean, Float) -> Unit,
) {
    val lastServerError by viewModel.lastServerError.collectAsState()

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
            lastServerError = lastServerError,
            onSwitchCamera = onSwitchCamera,
            onTogglePreview = onTogglePreview,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToCapture = onNavigateToCapture,
            onNavigateToSettings = onNavigateToSettings,
            onToggleQuickSettings = onToggleQuickSettings,
            onCopyStreamUrl = onCopyStreamUrl,
            onCopyRtspUrl = onCopyRtspUrl,
            onShowConnect = onShowConnect,
            onToggleServer = onToggleServer,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        if (CameraDashboardPolicy.shouldShowWifiBanner(wifiConnected, streamStatus.isServerRunning)) {
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
                        text = CameraDashboardPolicy.wifiBannerMessage(streamStatus.isActive),
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
            onWebStreamToggle = onWebStreamToggle,
            onRtspStreamToggle = onRtspStreamToggle,
            onCapture = onCapture,
            onRecord = onRecord,
            onSelectLens = onSelectLens,
            onToggleQuickSettings = onToggleQuickSettings,
            onQuickSettingTap = onQuickSettingTap,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        )

        CameraDashboardPolicy.thermalBanner(thermalState)?.let { banner ->
            ThermalWarningOverlay(
                banner = banner,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 200.dp)
            )
        }

        if (CameraDashboardPolicy.qualityIndicatorVisible(
                streamStatusActive = streamStatus.isActive,
                adaptiveEnabled = adaptiveBitrateState.enabled,
            )
        ) {
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
    lastServerError: String?,
    onSwitchCamera: () -> Unit,
    onTogglePreview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onToggleQuickSettings: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onShowConnect: () -> Unit,
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
                lastServerError = lastServerError,
                onCopyUrl = onCopyStreamUrl,
                onCopyRtspUrl = onCopyRtspUrl,
                onToggleServer = onToggleServer,
            )
            CameraControlButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Connect — QR + URLs",
                onClick = onShowConnect
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
    onWebStreamToggle: () -> Unit,
    onRtspStreamToggle: () -> Unit,
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
            isWebStreaming = streamStatus.isWebActive,
            isRtspStreaming = streamStatus.isRtspActive,
            isWebEnabled = streamStatus.isWebEnabled,
            isRtspEnabled = streamStatus.isRtspEnabled,
            isRecording = isRecording,
            quickSettingsExpanded = quickSettingsExpanded,
            onWebStreamToggle = onWebStreamToggle,
            onRtspStreamToggle = onRtspStreamToggle,
            onCapture = onCapture,
            onRecord = onRecord,
            onToggleQuickSettings = onToggleQuickSettings,
        )
    }
}

@Composable
private fun ShutterRow(
    isWebStreaming: Boolean,
    isRtspStreaming: Boolean,
    isWebEnabled: Boolean,
    isRtspEnabled: Boolean,
    isRecording: Boolean,
    quickSettingsExpanded: Boolean,
    onWebStreamToggle: () -> Unit,
    onRtspStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onToggleQuickSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        color = OverlayScrim.copy(alpha = 0.45f),
        shape = RoundedCornerShape(30.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
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
                    StreamShutterButton(
                        visual = CameraDashboardPolicy.StreamShutterVisual.of(
                            isStreaming = isWebStreaming,
                            isEnabled = isWebEnabled,
                            streamName = "web",
                        ),
                        icon = if (isWebStreaming) Icons.Default.Stop else Icons.Default.Wifi,
                        onClick = onWebStreamToggle,
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = quickSettingsExpanded,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(100))
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        color = OverlayLight,
                        shape = CircleShape,
                        onClick = onToggleQuickSettings
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Flip,
                                contentDescription = "Collapse quick settings",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
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
                    StreamShutterButton(
                        visual = CameraDashboardPolicy.StreamShutterVisual.of(
                            isStreaming = isRtspStreaming,
                            isEnabled = isRtspEnabled,
                            streamName = "RTSP",
                        ),
                        icon = if (isRtspStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                        onClick = onRtspStreamToggle,
                    )
                }
            }

            Surface(
                modifier = Modifier.size(52.dp),
                color = if (isRecording) RecordingRed else OverlayLight,
                shape = CircleShape,
                onClick = onRecord
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop recording" else "Record video",
                        tint = if (isRecording) Color.White else RecordingRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Box(
                modifier = Modifier.size(70.dp),
                contentAlignment = Alignment.Center
            ) {
                ShutterButton(onClick = onCapture)
            }
        }
    }
}

@Composable
private fun StreamShutterButton(
    visual: CameraDashboardPolicy.StreamShutterVisual,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    // The verdict and strings are the policy's; only the theme-adjacent
    // container-color mapping stays here.
    Surface(
        modifier = Modifier.size(52.dp),
        color = when (visual.container) {
            CameraDashboardPolicy.StreamShutterContainer.RECORDING -> RecordingRed
            CameraDashboardPolicy.StreamShutterContainer.ENABLED -> OverlayLight
            CameraDashboardPolicy.StreamShutterContainer.DISABLED -> OverlayLight.copy(alpha = 0.45f)
        },
        shape = CircleShape,
        onClick = {
            if (visual.clickEnabled) {
                onClick()
            }
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = visual.contentDescription,
                tint = visual.tint,
                modifier = Modifier.size(24.dp)
            )
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
            .size(70.dp)
            .border(3.dp, Color.White.copy(alpha = 0.95f), CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(56.dp)
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
                    modifier = Modifier.size(24.dp)
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
            QuickSettingCatalog.entries.forEach { descriptor ->
                QuickSettingPill(
                    icon = descriptor.icon.vector(),
                    label = descriptor.label(settings),
                    isActive = activeSetting == descriptor.type,
                    onClick = { onSettingTap(descriptor.type) }
                )
            }
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
    descriptor: QuickSettingDescriptor,
    settings: CameraSettings,
    ranges: QuickSettingRanges,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onUpdate: (QuickSettingType, Any) -> Unit,
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
                text = descriptor.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(20.dp))

            when (val editor = descriptor.editor) {
                is QuickSettingEditor.Toggle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = editor.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = editor.checked(settings),
                            onCheckedChange = { onUpdate(descriptor.type, it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                is QuickSettingEditor.Chips -> ProChipSelector(
                    options = editor.options(ranges),
                    selected = editor.selected(settings),
                    optionLabel = editor.optionLabel,
                    onSelect = { onUpdate(descriptor.type, it) },
                )
                is QuickSettingEditor.Slider -> ProSliderControl(
                    value = editor.value(settings),
                    range = editor.range(ranges),
                    label = editor.label(settings),
                    onValueChange = { onUpdate(descriptor.type, it) },
                )
            }

            descriptor.description?.invoke(settings)?.let { description ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
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
                text = CameraDashboardPolicy.sliderEndpoint(range.start),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = CameraDashboardPolicy.sliderEndpoint(range.endInclusive),
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
    optionLabel: (String) -> String = { it.replace("_", " ") },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val label = optionLabel(option)
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
                    text = label,
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
    val zoomRange by viewModel.availableZoomRange.collectAsState()

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
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        PreviewGestures.onScale(settings.zoomRatio, zoom, zoomRange)?.let { newZoom ->
                            viewModel.updateZoom(newZoom)
                            onPinchStateChange(true, newZoom)
                        }
                        // Identity zoom + minimal pan: a tap on the preview.
                        // Coordinates are normalized like LensWebHandler's.
                        if (size.width > 0 && size.height > 0 &&
                            PreviewGestures.isTap(zoom, pan.getDistance())
                        ) {
                            viewModel.tapToFocus(
                                (centroid.x / size.width).coerceIn(0f, 1f),
                                (centroid.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
        )
    }

    LaunchedEffect(isPinching) {
        if (!isPinching) {
            delay(PreviewGestures.INDICATOR_HIDE_DELAY_MS)
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
            color = RecordingRed.copy(alpha = 0.9f),
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

        if (streamStatus.isWebActive) {
            StreamBadge(icon = Icons.Default.Wifi, label = "WEB")
        }
        if (streamStatus.isRtspActive) {
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
    val timeText = formatDuration(elapsedSeconds * 1000L)

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
                color = RecordingRed.copy(alpha = dotAlpha)
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
    lastServerError: String?,
    onCopyUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val iconTint by animateColorAsState(
        targetValue = when (
            CameraDashboardPolicy.serverStatusTier(streamStatus.isActive, streamStatus.isServerRunning)
        ) {
            CameraDashboardPolicy.ServerStatusTier.LIVE -> Color(0xFF4CAF50)
            CameraDashboardPolicy.ServerStatusTier.READY -> MaterialTheme.colorScheme.primary
            CameraDashboardPolicy.ServerStatusTier.OFFLINE -> Color.White.copy(alpha = 0.4f)
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
                        if (lastServerError != null) {
                            Text(
                                lastServerError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        Text(
                            CameraDashboardPolicy.serverStatusText(
                                clientCount = streamStatus.clientCount,
                                isActive = streamStatus.isActive,
                                isServerRunning = streamStatus.isServerRunning,
                            ),
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
    banner: CameraDashboardPolicy.ThermalBanner,
    modifier: Modifier = Modifier,
) {
    // The verdict and label are the policy's; only the theme-adjacent color
    // mapping stays here.
    val color = when (banner.severity) {
        CameraDashboardPolicy.ThermalSeverity.MODERATE -> LensOrange
        CameraDashboardPolicy.ThermalSeverity.SEVERE -> LensRed
        CameraDashboardPolicy.ThermalSeverity.CRITICAL -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.92f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = banner.label,
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
                // The zoom pill's own formatter — same Locale.US pin, so the
                // indicator can never disagree with the pill.
                text = QuickSettingCatalog.zoomLabel(zoomRatio),
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
    val badge = CameraDashboardPolicy.qualityBadge(qualityLevel)
    val dotColor = badge.color
    val label = badge.abbreviation

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
                    text = CameraDashboardPolicy.qualitySummary(currentQuality, currentFps),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                )
                if (activeClients > 0) {
                    Text(
                        text = CameraDashboardPolicy.clientSummary(activeClients, minThroughputKbps),
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
                    CameraDashboardPolicy.connectionStatRows(
                        estimatedBandwidthKbps = estimatedBandwidthKbps,
                        stats = stats,
                    ).forEach { row ->
                        ConnectionStatRow(label = row.label, value = row.value)
                    }

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
                                text = CameraDashboardPolicy.clientStatHeader(clientId),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            )
                            CameraDashboardPolicy.clientStatRows(detail).forEach { row ->
                                ConnectionStatRow(label = row.label, value = row.value)
                            }
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
