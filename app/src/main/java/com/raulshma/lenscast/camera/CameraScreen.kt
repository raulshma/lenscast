package com.raulshma.lenscast.camera

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import java.util.Locale
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.ConnectInfoPolicy
import com.raulshma.lenscast.camera.model.GridViewPolicy
import com.raulshma.lenscast.camera.model.LevelIndicatorPolicy
import com.raulshma.lenscast.camera.model.LensLabelKind
import com.raulshma.lenscast.camera.model.NotificationPermissionPolicy
import com.raulshma.lenscast.camera.model.PreviewGestures
import com.raulshma.lenscast.camera.model.ProToolsPolicy
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingsUiState
import com.raulshma.lenscast.camera.model.QuickSettingDescriptor
import com.raulshma.lenscast.camera.model.QuickSettingEditor
import com.raulshma.lenscast.camera.model.QuickSettingIcon
import com.raulshma.lenscast.camera.model.QuickSettingRanges
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.ConsoleCapabilities
import com.raulshma.lenscast.camera.model.ZoomIndicatorState
import com.raulshma.lenscast.camera.model.ZoomPresetsPolicy
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.companion.console.ConsoleEvent
import com.raulshma.lenscast.companion.console.DeviceConsoleViewModel
import com.raulshma.lenscast.companion.console.RemotePreviewStage
import com.raulshma.lenscast.companion.console.RungPhase
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.gallery.formatDuration
import com.raulshma.lenscast.ui.animation.expressiveMotionSpec
import com.raulshma.lenscast.ui.theme.LensBlue
import com.raulshma.lenscast.ui.theme.LensGreen
import com.raulshma.lenscast.ui.theme.LensOrange
import com.raulshma.lenscast.ui.theme.LensRed
import com.raulshma.lenscast.ui.theme.RecordingRed
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val OverlayScrim = Color(0xB3000000)
private val OverlayLight = Color(0x80000000)
private val TopGradientColor = Color(0x78000000)
private val BottomGradientColor = Color(0x78000000)

// Viewfinder-aid palette: the composition grid is a faint white; the level
// line shares the app's live-status green when aligned; pro-tools heat uses
// the exposure-convention colors (blown highlights red, crushed blacks blue)
// and the focus-peaking yellow.
private val GridLineColor = Color(0x66FFFFFF)
private val LevelAlignedColor = Color(0xFF4CAF50)
private val ZebraOverColor = Color(0x66FF3B30)
private val ZebraUnderColor = Color(0x660A84FF)
private val PeakingColor = Color(0x99FFD60A)

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

/** The Activity behind a composition context (possibly wrapper-wrapped) — the quit's finishAffinity target. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The remote viewfinder's PiP affordance — the companion live view's enterPip
 * plumbing ported verbatim: a 16:9 stage (the device stream's shape) and no custom
 * actions, gated to API 26+ (the platform API's floor; the app's minSdk is
 * 23, so the gate is load-bearing). No auto-PiP on user-leave and no
 * lifecycle changes — the console's lanes keep running while piped.
 */
private fun enterPip(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val activity = context.findActivity() ?: return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    activity.enterPictureInPictureMode(params)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToDevices: () -> Unit,
    /**
     * The backend selector: null (the start destination's no-arg form) is
     * this phone's local camera — today's dashboard verbatim; a paired
     * device id opens the same dashboard against that device's remote
     * console ([DeviceConsoleViewModel]).
     */
    remoteDeviceId: String? = null,
    /** Remote only: opens the paired device's event log. */
    onNavigateToEvents: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph

    // One interface, two backends: the screen below binds the [CameraConsole]
    // seam and reads [ConsoleCapabilities] to hide what a backend can't
    // honor — this is the only place that knows which one was picked.
    val console: CameraConsole
    val remote: DeviceConsoleViewModel?
    if (remoteDeviceId == null) {
        val local: CameraViewModel = viewModel(
            factory = CameraViewModel.Factory(
                context, app.cameraService, app.streamingManager,
                app.thermalMonitor, app.settingsDataStore, app.streamingSession,
                app.streamWatchdog, app.connectivityMonitor, app.recordingController,
                app.photoCaptureManager
            )
        )
        console = local
        remote = null
    } else {
        val deviceConsole: DeviceConsoleViewModel = viewModel(
            key = remoteDeviceId,
            factory = app.companion.consoleFactory(remoteDeviceId, context.applicationContext),
        )
        console = deviceConsole
        remote = deviceConsole
    }

    val cameraState by console.cameraState.collectAsState()
    val capabilities by console.capabilities.collectAsState()
    val streamStatus by console.streamStatus.collectAsState()
    val thermalState by console.thermalState.collectAsState()
    val isRecording by console.isRecording.collectAsState()
    val recordingState by console.recordingState.collectAsState()
    val recordingElapsedSeconds by console.recordingElapsedSeconds.collectAsState()
    val wifiConnected by console.wifiConnected.collectAsState()
    val availableLenses by console.availableLenses.collectAsState()
    val selectedLensIndex by console.selectedLensIndex.collectAsState()
    val settings by console.settings.collectAsState()
    val showPreview by console.showPreview.collectAsState()
    val adaptiveBitrateState by console.adaptiveBitrateState.collectAsState()
    val connectionQualityStats by console.connectionQualityStats.collectAsState()
    val hasAudioPermission by console.hasAudioPermission.collectAsState()
    val flashMode by console.flashModeSetting.collectAsState()
    val availableZoomRange by console.availableZoomRange.collectAsState()

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        console.onPermissionResult(
            cameraGranted = results[Manifest.permission.CAMERA] == true,
            audioGranted = results[Manifest.permission.RECORD_AUDIO] == true,
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        console.onAudioPermissionResult(granted)
    }
    // Detection alerts ride the same first-launch permission pass: a one-shot
    // POST_NOTIFICATIONS ask on API 33+, silently skipped when already granted
    // and never repeated after a denial — the ask-once verdict is
    // NotificationPermissionPolicy's, the once-flag lives in the ViewModel's
    // state (the old home was a process-global var in this file).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    // Saveable: the once-per-session ask budget must survive rotation, or a
    // configuration change would re-prompt for an already-denied mic.
    var requestedMissingAudioPermission by rememberSaveable { mutableStateOf(false) }
    if (remoteDeviceId == null) {
        // Local-only plumbing: the remote backend's cameraState never enters
        // RequestPermission (the permission screens below never render), its
        // notification state is this phone's concern, and auto-asking for
        // THIS phone's mic when the device console turns Ready would be a
        // surprise — the mic is requested on the remote branch only when the
        // user first holds the talk button.
        LaunchedEffect(Unit) {
            // Fresh StateFlow read rather than a composition-collected delegate:
            // the old global was visible to the next fresh composition
            // immediately, and the ask-once guard must stay that airtight.
            val askedBefore = console.notificationPermissionAsked.value
            if (NotificationPermissionPolicy.shouldMarkAsked(askedBefore)) {
                console.markNotificationPermissionAsked()
            }
            if (NotificationPermissionPolicy.shouldAsk(
                    granted = ContextCompat.checkSelfPermission(
                        context, NotificationPermissionPolicy.PERMISSION,
                    ) == PackageManager.PERMISSION_GRANTED,
                    askedBefore = askedBefore,
                    apiLevel = Build.VERSION.SDK_INT,
                )
            ) {
                notificationPermissionLauncher.launch(NotificationPermissionPolicy.PERMISSION)
            }
        }

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
    }

    val flashAlpha = remember { Animatable(0f) }

    // The shutter flash rides the console's capture events, so a self-timer
    // countdown flashes when the capture actually fires, not at press time.
    LaunchedEffect(Unit) {
        console.captureFlashEvents.collect {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(0f, animationSpec = tween(150))
        }
    }

    // The quick-settings panel is one sealed machine (Collapsed / Expanded /
    // Sheet) instead of a boolean + nullable-setting pair — the "sheet open
    // ⇒ bar expanded" invariant is structural now, and the callbacks below
    // are its transitions. The sub-composables still take booleans; the
    // projections are derived here at the call site. Plain remember: the
    // panel intentionally resets on rotation, as before.
    var quickSettingsUiState by remember { mutableStateOf<QuickSettingsUiState>(QuickSettingsUiState.Collapsed) }
    var showConnectSheet by remember { mutableStateOf(false) }
    // The pinch-zoom indicator as one machine (ZoomIndicatorState): the
    // gesture layer's writes and the retire timer's write all land here, so
    // the show/hide contract can't split across a flag + a float again.
    var zoomIndicator by remember { mutableStateOf(ZoomIndicatorState.Hidden) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── remote-only lanes ──
    // The retired device shell's session contract, carried over to the console:
    // enter restarts the link if the last visit stopped it (retryCameraInit
    // is session.start(), idempotent and a no-op while CONNECTED) and turns
    // the console's playback/polls on; leaving freezes then stops them. The
    // roster display name rides the top overlay.
    val deviceName = remember(remoteDeviceId) {
        remoteDeviceId?.let { id ->
            app.companion.roster.find(id)?.let { device ->
                device.name.ifBlank { "${device.host}:${device.port}" }
            }
        }
    }
    // The companion audio lanes' callbacks, declared at screen scope with
    // remote-guarded no-op bodies — on the local backend the affordances
    // that invoke them never render (their capability flags are off).
    // Push-to-talk's runtime mic permission lives at the host surface, the
    // companion live view's precedent: the first hold requests RECORD_AUDIO
    // (no new manifest entry) and the grant lands the hold.
    // The hold's liveness flag: the mic grant can land long after the press
    // (the permission dialog covers the app, the hold reports Cancel), so
    // the launcher verifies the originating hold is still open before it
    // starts the uplink — a stale grant must not open a talk nobody holds.
    var talkHoldActive by remember { mutableStateOf(false) }
    val talkbackMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && talkHoldActive) remote?.startTalkback()
        else {
            // A denial (or a stale grant) closes the hold the accessibility
            // click left open — the touch path's release was swallowed by
            // the permission dialog, and a click has no release to cancel —
            // so the button below retires its phantom hold and the NEXT
            // accessibility click opens a fresh one (the one-click retry)
            // instead of first "ending" a hold that never talked.
            talkHoldActive = false
        }
    }
    val onTalkStart: () -> Unit = {
        talkHoldActive = true
        val micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) remote?.startTalkback()
        else talkbackMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val onTalkEnd: () -> Unit = {
        talkHoldActive = false
        remote?.stopTalkback()
    }
    val onListenToggle: () -> Unit = {
        remote?.let { deviceConsole ->
            if (deviceConsole.listening.value) deviceConsole.stopListen()
            else deviceConsole.startListen()
        }
    }
    val onEnterPip: () -> Unit = { enterPip(context) }
    if (remote != null) {
        DisposableEffect(remoteDeviceId) {
            remote.retryCameraInit()
            remote.setActive(true)
            onDispose {
                remote.setActive(false)
                // The shell's dispose contract: freeze + stop the link; the
                // session object stays cached in the manager for re-entry.
                remote.dispose()
            }
        }

        // Control-failure notices → toast, the console-event rendering idiom:
        // every Notice copy pre-resolves in composition (a captured
        // getString would freeze the pre-rotation locale), the vocabulary
        // is closed, and the collector maps the resId to its copy.
        val forbiddenText = stringResource(R.string.companion_live_forbidden)
        val controlAuthText = stringResource(R.string.companion_live_control_auth)
        val controlFailedText = stringResource(R.string.companion_live_control_failed)
        val audioFailedText = stringResource(R.string.companion_live_audio_failed)
        val talkbackFailedText = stringResource(R.string.companion_live_talkback_failed)
        val talkbackNeedsLoginText = stringResource(R.string.companion_live_talkback_needs_login)
        LaunchedEffect(
            remote,
            forbiddenText, controlAuthText, controlFailedText,
            audioFailedText, talkbackFailedText, talkbackNeedsLoginText,
        ) {
            remote.consoleEvents.collect { event ->
                when (event) {
                    is ConsoleEvent.Notice -> Toast.makeText(
                        context,
                        when (event.resId) {
                            R.string.companion_live_forbidden -> forbiddenText
                            R.string.companion_live_control_auth -> controlAuthText
                            R.string.companion_live_audio_failed -> audioFailedText
                            R.string.companion_live_talkback_failed -> talkbackFailedText
                            R.string.companion_live_talkback_needs_login -> talkbackNeedsLoginText
                            else -> controlFailedText
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
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
            onRetry = { console.retryCameraInit() }
        )
        is CameraState.Ready -> {
            ImmersiveCameraView(
                console = console,
                remote = remote,
                capabilities = capabilities,
                deviceName = deviceName,
                remoteDeviceId = remoteDeviceId,
                streamStatus = streamStatus,
                thermalState = thermalState,
                isRecording = isRecording,
                recordingState = recordingState,
                recordingElapsedSeconds = recordingElapsedSeconds,
                wifiConnected = wifiConnected,
                availableLenses = availableLenses,
                selectedLensIndex = selectedLensIndex,
                settings = settings,
                zoomRange = availableZoomRange,
                flashMode = flashMode,
                showPreview = showPreview,
                adaptiveBitrateState = adaptiveBitrateState,
                connectionQualityStats = connectionQualityStats,
                quickSettingsExpanded = quickSettingsUiState.isBarExpanded,
                activeSetting = quickSettingsUiState.openSetting,
                flashAlpha = flashAlpha,
                isPinching = zoomIndicator.isVisible,
                pinchZoomRatio = zoomIndicator.ratio,
                onPinchStateChange = { pinching, ratio ->
                    zoomIndicator =
                        if (pinching) zoomIndicator.onPinch(ratio) else zoomIndicator.onRetire()
                },
                onToggleQuickSettings = {
                    quickSettingsUiState = quickSettingsUiState.toggled()
                },
                onQuickSettingTap = { type ->
                    quickSettingsUiState = quickSettingsUiState.settingTapped(type)
                },
                onCapture = {
                    // The press verdict (capture now vs self-timer countdown)
                    // is SelfTimerPolicy's, executed in the backend.
                    console.onShutterPress()
                },
                onWebStreamToggle = { console.toggleWebStreaming() },
                onRtspStreamToggle = { console.toggleRtspStreaming() },
                onRecord = { console.toggleRecording() },
                onSwitchCamera = { console.switchCamera() },
                onTogglePreview = { console.togglePreview() },
                onTalkStart = onTalkStart,
                onTalkEnd = onTalkEnd,
                talkHoldActive = talkHoldActive,
                onListenToggle = onListenToggle,
                onEnterPip = onEnterPip,
                onNavigateToGallery = onNavigateToGallery,
                onNavigateToEvents = onNavigateToEvents,
                onNavigateToCapture = onNavigateToCapture,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToDevices = onNavigateToDevices,
                onQuitApplication = {
                    // Local-only (the remote overflow hides this item — the
                    // quit kills THIS phone's app, not the device's).
                    // finishAffinity empties the task first, so the process
                    // kill cannot leave the system to relaunch the activity;
                    // the teardown order is quitApplication's.
                    if (remoteDeviceId == null) {
                        context.findActivity()?.finishAffinity()
                        app.quitApplication()
                    }
                },
                onCopyStreamUrl = { console.copyStreamUrl() },
                onCopyRtspUrl = { console.copyRtspUrl() },
                onShowConnect = { showConnectSheet = true },
                onToggleServer = { console.toggleServer() },
                onSelectLens = { console.selectLens(it) },
            )

            // The connect sheet is local-server-only: the remote console's
            // connect info is a loud stub and its serverPanel capability
            // keeps both the affordance and the sheet off.
            if (showConnectSheet && capabilities.serverPanel) {
                ModalBottomSheet(
                    onDismissRequest = { showConnectSheet = false },
                ) {
                    val info = remember(showConnectSheet) { console.getConnectInfo() }
                    // The fingerprint flows through the backend (which owns
                    // the StreamingManager); the TLS gate is the policy's
                    // scheme verdict, read once per sheet open as before.
                    val tlsFingerprint = remember(showConnectSheet) {
                        console.tlsCertificateFingerprint()
                            .takeIf { ConnectInfoPolicy.isSecureHttp(info.httpUrl) }
                    }
                    ConnectSheet(
                        info = info,
                        currentPort = ConnectInfoPolicy.currentPort(info.httpUrl),
                        onCopyHttp = { console.copyStreamUrl() },
                        onCopyHls = { console.copyHlsUrl() },
                        onCopyRtsp = { console.copyRtspUrl() },
                        tlsFingerprint = tlsFingerprint,
                    )
                }
            }

            // The sheet reads its control off the machine's projection; the
            // dismiss is the sheetDismissed transition (bar stays expanded).
            // Write-gated: the bar and every entry that opens it are hidden
            // for a viewer-role pairing, so this can only render when the
            // backend honors writes.
            val sheetSetting = quickSettingsUiState.openSetting
            if (sheetSetting != null && capabilities.canControl) {
                val isoRange by console.availableIsoRange.collectAsState()
                val zoomRange by console.availableZoomRange.collectAsState()
                val exposureRange by console.availableExposureRange.collectAsState()
                QuickSettingSheet(
                    descriptor = QuickSettingCatalog.descriptorFor(sheetSetting),
                    settings = settings,
                    ranges = QuickSettingRanges(
                        iso = isoRange,
                        zoom = zoomRange,
                        exposure = exposureRange,
                    ),
                    sheetState = sheetState,
                    onDismiss = { quickSettingsUiState = quickSettingsUiState.sheetDismissed() },
                    onUpdate = { type, value -> console.updateQuickSetting(type, value) },
                )
            }
        }
        is CameraState.Idle -> {}
    }
}

@Composable
private fun ImmersiveCameraView(
    console: CameraConsole,
    /** The concrete remote backend, null on the local one — the companion audio lanes' source. */
    remote: DeviceConsoleViewModel?,
    /** The backend's render-verdict flags: every hide/disable decision below reads these. */
    capabilities: ConsoleCapabilities,
    /** The paired device's roster display name; null on the local backend. */
    deviceName: String?,
    /** The backend selector; null on the local backend — the top bar's quit gate. */
    remoteDeviceId: String?,
    streamStatus: com.raulshma.lenscast.camera.model.StreamStatus,
    thermalState: ThermalState,
    isRecording: Boolean,
    recordingState: com.raulshma.lenscast.capture.RecordingState,
    recordingElapsedSeconds: Int,
    wifiConnected: Boolean,
    availableLenses: List<CameraLensInfo>,
    selectedLensIndex: Int,
    settings: CameraSettings,
    zoomRange: ClosedFloatingPointRange<Float>,
    flashMode: FlashMode,
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
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    talkHoldActive: Boolean,
    onListenToggle: () -> Unit,
    onEnterPip: () -> Unit,
    onNavigateToGallery: () -> Unit,
    /** Non-null on the remote branch: adds the paired device's event-log item. */
    onNavigateToEvents: (() -> Unit)?,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onQuitApplication: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onShowConnect: () -> Unit,
    onToggleServer: () -> Unit,
    onSelectLens: (Int) -> Unit,
    onPinchStateChange: (Boolean, Float) -> Unit,
) {
    val lastServerError by console.lastServerError.collectAsState()
    val selfTimerSeconds by console.selfTimerSecondsRemaining.collectAsState()
    // The remote companion lanes (null-collected on the local backend —
    // their affordances are capability-gated off there).
    val remotePlayback = remote?.playback?.collectAsStateWithLifecycle()
    val listening = remote?.listening?.collectAsStateWithLifecycle()?.value ?: false
    val talkbackActive = remote?.talkbackActive?.collectAsStateWithLifecycle()?.value ?: false
    val talkbackNeedsLogin =
        remote?.talkbackNeedsLogin?.collectAsStateWithLifecycle()?.value ?: false
    // The PiP button's enable verdict: the affordance pipes the viewfinder,
    // so it needs the preview on screen and the rung actually LIVE — a
    // connecting/failed rung or a hidden preview has nothing to pipe.
    val pipEnabled = showPreview && remotePlayback?.value?.phase == RungPhase.LIVE

    Box(modifier = Modifier.fillMaxSize()) {
        if (showPreview) {
            CameraPreview(
                console = console,
                remote = remote,
                capabilities = capabilities,
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
                        text = stringResource(R.string.camera_preview_hidden),
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
            flashMode = flashMode,
            capabilities = capabilities,
            deviceName = deviceName,
            remoteDeviceId = remoteDeviceId,
            listening = listening,
            talkbackActive = talkbackActive,
            talkbackNeedsLogin = talkbackNeedsLogin,
            pipEnabled = pipEnabled,
            onCycleFlashMode = { console.cycleFlashMode() },
            onSwitchCamera = onSwitchCamera,
            onTogglePreview = onTogglePreview,
            onTalkStart = onTalkStart,
            onTalkEnd = onTalkEnd,
            talkHoldActive = talkHoldActive,
            onListenToggle = onListenToggle,
            onEnterPip = {
                // PiP's gate: a rung must actually be showing frames, and the
                // viewfinder must be on screen (a hidden preview has nothing
                // to pipe) — the button's enabled state mirrors both.
                if (remotePlayback?.value?.phase == RungPhase.LIVE && showPreview) onEnterPip()
            },
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToEvents = onNavigateToEvents,
            onNavigateToCapture = onNavigateToCapture,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToDevices = onNavigateToDevices,
            onQuitApplication = onQuitApplication,
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
                        text = stringResource(
                            when (CameraDashboardPolicy.wifiBannerVariant(streamStatus.isActive)) {
                                CameraDashboardPolicy.WifiBannerVariant.STREAM_ACTIVE -> R.string.camera_wifi_banner_active
                                CameraDashboardPolicy.WifiBannerVariant.STREAM_IDLE -> R.string.camera_wifi_banner_idle
                            }
                        ),
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
            zoomRange = zoomRange,
            quickSettingsExpanded = quickSettingsExpanded,
            activeSetting = activeSetting,
            recordingState = recordingState,
            flashAlpha = flashAlpha,
            canControl = capabilities.canControl,
            onWebStreamToggle = onWebStreamToggle,
            onRtspStreamToggle = onRtspStreamToggle,
            onCapture = onCapture,
            onRecord = onRecord,
            onSelectLens = onSelectLens,
            onZoomPreset = { ratio ->
                // The preset snaps through the zoom quick-setting's own write
                // path: apply now for responsiveness, persist for the Applier.
                console.updateQuickSetting(QuickSettingType.ZOOM, ratio)
            },
            onToggleQuickSettings = onToggleQuickSettings,
            onQuickSettingTap = onQuickSettingTap,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        )

        CameraDashboardPolicy.thermalBanner(thermalState)?.let { severity ->
            ThermalWarningOverlay(
                severity = severity,
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

        // The self-timer's countdown covers the viewfinder; any tap cancels
        // (the timer mode stays selected for the next press).
        selfTimerSeconds?.let { seconds ->
            SelfTimerCountdownOverlay(
                secondsRemaining = seconds,
                onCancel = { console.cancelSelfTimer() },
                modifier = Modifier.align(Alignment.Center)
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
    flashMode: FlashMode,
    capabilities: ConsoleCapabilities,
    /** The paired device's roster name; null on the local backend (no name chip). */
    deviceName: String?,
    /** The backend selector; null on the local backend (the quit item's gate). */
    remoteDeviceId: String?,
    listening: Boolean,
    talkbackActive: Boolean,
    talkbackNeedsLogin: Boolean,
    /** The PiP button's enable verdict: LIVE rung + on-screen viewfinder. */
    pipEnabled: Boolean,
    onCycleFlashMode: () -> Unit,
    onSwitchCamera: () -> Unit,
    onTogglePreview: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    /** The screen-level talk hold flag: keeps the button's hold visual lit
     *  while the mic grant dialog covers the app and swallows the release. */
    talkHoldActive: Boolean,
    onListenToggle: () -> Unit,
    onEnterPip: () -> Unit,
    onNavigateToGallery: () -> Unit,
    /** Non-null on the remote branch: adds the paired device's event-log button. */
    onNavigateToEvents: (() -> Unit)?,
    onNavigateToCapture: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onQuitApplication: () -> Unit,
    onToggleQuickSettings: () -> Unit,
    onCopyStreamUrl: () -> Unit,
    onCopyRtspUrl: () -> Unit,
    onShowConnect: () -> Unit,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The remote branch's signal: the backend selector itself, independent
    // of which affordances a console offers (the event-log button below
    // separately gates on its callback — the Quit item hides for ANY remote
    // device, callback or not).
    val isRemote = remoteDeviceId != null
    // PiP also needs the platform's support, not just a backend that renders
    // a pipeable surface: API 26+ (enterPip's own floor) and the device
    // feature bit — a missing requirement hides the control.
    val pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        LocalContext.current.packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = Modifier
                // The left cluster's chips (identity, stream badges) are
                // content-sized: a long roster name at a large font scale on
                // a 320dp screen would claim the whole bar and push the
                // pinned More button off-screen. Both clusters take a
                // bounded weighted share (this row's fill=false idiom, the
                // right cluster's own weight mirroring it): the row hugs its
                // content but never exceeds its share, and an overflow
                // scrolls instead of overflowing (the More button keeps both
                // its slot and its 40dp hit target).
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The lens switch is a camera-pipeline write: viewer-role
            // pairings never see it (their console no-ops it anyway).
            if (capabilities.canControl) {
                CameraControlButton(
                    icon = Icons.Default.Cameraswitch,
                    contentDescription = stringResource(R.string.camera_switch_camera_cd),
                    onClick = onSwitchCamera
                )
            }
            // The main-shutter flash chip: cycles OFF → AUTO → ON through the
            // persisted setting (the icon is the mode's own state). Rendered
            // only when the backend can carry the mode into a photo.
            if (capabilities.flashControl) {
                CameraControlButton(
                    icon = when (flashMode) {
                        FlashMode.ON -> Icons.Default.FlashOn
                        FlashMode.AUTO -> Icons.Default.FlashAuto
                        FlashMode.OFF -> Icons.Default.FlashOff
                    },
                    contentDescription = stringResource(R.string.camera_flash_cd),
                    onClick = onCycleFlashMode
                )
            }
            if (deviceName != null) {
                // The remote backend's identity chip (the roster's display
                // name, the device shell's label idiom).
                Surface(
                    color = OverlayScrim,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                    )
                }
            }
            if (streamStatus.isActive) {
                StreamIndicator(streamStatus = streamStatus)
            }
            if (isRecording) {
                RecordingIndicator(elapsedSeconds = recordingElapsedSeconds)
            }
        }

        // The right cluster overflows the screen with the stream chips up
        // (six buttons never fit beside LIVE/WEB): the secondary buttons
        // scroll, while the More-options button is PINNED outside the scroll
        // so the menu (camera controls, capture tools, settings, quit) stays
        // reachable without swiping the tail into view — the overflow fix for
        // the pushed-off-screen Gallery/Menu report. The scroll row takes a
        // fill=false weight: an overflowing scroll row without one claims the
        // parent's whole width and clips the pinned button off-screen —
        // weight carves the pinned button its slot first, fill=false keeps
        // width-hugging when idle so SpaceBetween still pins the cluster to
        // the edge. The cluster itself takes the same fill=false weight as
        // the left cluster: unweighted, an overflowing secondary row is
        // measured FIRST and claims the whole bar, squeezing the left
        // cluster's weighted share to nothing — the weight bounds both
        // clusters to an even split of the bar.
        Row(
            modifier = Modifier
                .weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The server panel is local-server chrome: the remote
                // backend serves no viewers of its own (serverPanel = false
                // keeps both the status button and the connect sheet off).
                if (capabilities.serverPanel) {
                    ServerStatusButton(
                        streamStatus = streamStatus,
                        lastServerError = lastServerError,
                        onCopyUrl = onCopyStreamUrl,
                        onCopyRtspUrl = onCopyRtspUrl,
                        onToggleServer = onToggleServer,
                    )
                    CameraControlButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.camera_connect_cd),
                        onClick = onShowConnect
                    )
                }
                // The companion audio lanes (remote backend only — the
                // capability flags are off on the local one): push-to-talk
                // hold and the live-listen toggle, the companion live view's
                // audio affordances compacted into the top-bar chrome. The hold
                // tints error while the last dial failed on the cookie gate
                // (the re-pair cue; the notice rides the toast).
                if (capabilities.talkback) {
                    CameraHoldButton(
                        icon = Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (talkbackActive) R.string.companion_talkback_stop
                            else R.string.companion_talkback_start
                        ),
                        active = talkbackActive,
                        activeTint = LensGreen,
                        warnTint = if (talkbackNeedsLogin) MaterialTheme.colorScheme.error else Color.White,
                        onStart = onTalkStart,
                        onEnd = onTalkEnd,
                        holdOpen = talkHoldActive,
                    )
                }
                if (capabilities.liveAudio) {
                    CameraControlButton(
                        icon = Icons.Default.Hearing,
                        contentDescription = stringResource(
                            if (listening) R.string.companion_stop_audio
                            else R.string.companion_listen_audio
                        ),
                        onClick = onListenToggle,
                        tint = if (listening) LensGreen else Color.White,
                    )
                }
                // PiP is a remote-only affordance (the local dashboard never
                // renders one — capabilities.local pins pip false), and it
                // needs the device's own PiP support.
                if (capabilities.pip && pipSupported) {
                    CameraControlButton(
                        icon = Icons.Default.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.companion_pip_cd),
                        onClick = onEnterPip,
                        enabled = pipEnabled,
                    )
                }
                CameraControlButton(
                    icon = if (showPreview) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (showPreview) stringResource(R.string.camera_hide_preview_cd)
                    else stringResource(R.string.camera_show_preview_cd),
                    onClick = onTogglePreview
                )
                CameraControlButton(
                    icon = Icons.Default.Collections,
                    contentDescription = stringResource(R.string.camera_gallery_cd),
                    onClick = onNavigateToGallery
                )
                // The paired device's event log — a remote-only affordance
                // (the local dashboard reaches its own log through app
                // settings, as always).
                if (onNavigateToEvents != null) {
                    CameraControlButton(
                        icon = Icons.Default.Notifications,
                        contentDescription = stringResource(R.string.detection_title),
                        onClick = onNavigateToEvents
                    )
                }
                CameraControlButton(
                    icon = Icons.Default.Devices,
                    contentDescription = stringResource(R.string.companion_nav_devices_cd),
                    onClick = onNavigateToDevices
                )
            }
            Box {
                CameraControlButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.camera_more_options_cd),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    // "Camera controls" opens the quick-settings sheet stack
                    // — every entry in it is a state write, so it is
                    // viewer-gated like the bar itself.
                    if (capabilities.canControl) {
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
                                        stringResource(R.string.camera_menu_camera_controls),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleQuickSettings()
                            }
                        )
                    }
                    // Capture tools open the interval/scheduled capture
                    // screen against the SAME backend this dashboard rides
                    // (the route carries the device argument — P5), so the
                    // entry renders on the remote branch too.
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
                                    stringResource(R.string.camera_menu_capture_tools),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onNavigateToCapture()
                        }
                    )
                    // Per-camera settings ride the paired device too (P6): the
                    // route's device argument selects the backend — this
                    // phone's store locally, the device's settings document
                    // remotely.
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
                                    stringResource(R.string.camera_menu_settings),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onNavigateToSettings()
                        }
                    )
                    if (!isRemote) {
                        // Local only: the quit kills THIS phone's app — on a
                        // paired device's dashboard it would be a lie.
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.PowerSettingsNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.camera_menu_quit),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onQuitApplication()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The top bar's compact press-and-hold button — the push-to-talk gesture
 * (the companion live view's hold contract, compacted to the dashboard's
 * 40dp scrim circle): [onStart] on the down event, [onEnd] on release or
 * cancel, [active] tints while a hold is open.
 */
@Composable
private fun CameraHoldButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    activeTint: Color,
    warnTint: Color = Color.White,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    /** The screen-level hold flag ([talkHoldActive] — the mic grant path). */
    holdOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    // One hold at a time: the `holding` flag collapses the double-report a
    // cancelled gesture can produce, and it is the same machine the
    // accessibility click drives — so the dispose safety net below ends a
    // hold opened by either path.
    var holding by remember { mutableStateOf(false) }
    // One-way retire sync: the screen can close a hold this button still
    // holds open — the mic permission DENIAL retires the hold the
    // accessibility click opened (a click has no release for the permission
    // dialog to cancel). Retire without onEnd — the talk never started —
    // so the next accessibility click opens a fresh hold (the one-click
    // retry) instead of first "ending" a phantom one. The normal end path
    // already cleared `holding` before flipping `holdOpen`, so this no-ops
    // there.
    LaunchedEffect(holdOpen) {
        if (!holdOpen && holding) holding = false
    }
    fun toggleHold() {
        if (holding) {
            holding = false
            onEnd()
        } else {
            holding = true
            onStart()
        }
    }
    // Safety net: leaving composition mid-hold ends the talk.
    DisposableEffect(Unit) {
        onDispose {
            if (holding) onEnd()
        }
    }
    Surface(
        modifier = modifier
            .size(40.dp)
            // The click action is accessibility's path: TalkBack dispatches a
            // click without producing the press/release pair below, so a tap
            // there toggles the hold (start from idle, stop while active) and
            // rides the same `holding` machine as touch.
            .semantics {
                role = Role.Button
                onClick { toggleHold(); true }
            }
            // D-pad/keyboard reaches the hold too: focus + the click keys run
            // the same toggle as the accessibility click (foundation's own
            // clickable key set — DirectionCenter/Enter/NumPadEnter/Spacebar
            // on key up), so a physical keyboard opens and closes the hold
            // without a touch event. The touch handler below stays separate:
            // a swipe still cancels the pending hold instead of toggling.
            .focusable()
            .onKeyEvent { event ->
                val isClickKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.Spacebar
                if (isClickKey && event.type == KeyEventType.KeyUp) {
                    toggleHold()
                    true
                } else {
                    false
                }
            }
            // Touch keeps the press-and-release contract, with the start
            // deferred until the press commits as a hold: a swipe that
            // begins on the button starts scrolling (movement past touch
            // slop, or a consumer taking the gesture) inside the pending
            // window and cancels it with no onStart — a swipe never opens
            // the talk. A press still stationary past the long-press
            // timeout is the recognized hold: onStart fires, and its
            // release/cancel runs the end path as before (a quick
            // stationary tap keeps its brief blip — start+end, the same
            // round trip the old down/up pair made).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var scrolled = false
                    var tapped = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var drift = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: continue
                            if (change.isConsumed) {
                                scrolled = true
                                break
                            }
                            drift += change.positionChange()
                            if (drift.getDistance() > viewConfiguration.touchSlop) {
                                scrolled = true
                                break
                            }
                            if (!change.pressed) {
                                tapped = true
                                break
                            }
                        }
                    }
                    when {
                        tapped -> {
                            // The old brief blip: start+end in one stroke —
                            // the same round trip the old down/up pair made.
                            if (!holding) {
                                holding = true
                                onStart()
                            }
                            holding = false
                            onEnd()
                        }
                        !scrolled -> {
                            // Timed out stationary: the recognized hold —
                            // the permission request and talkback start fire
                            // here, and the release/cancel runs the end path.
                            if (!holding) {
                                holding = true
                                onStart()
                            }
                            // The hold rides the initiating pointer, not the
                            // whole gesture: waitForUpOrCancellation returned
                            // only once EVERY pointer lifted, so a second
                            // finger resting on screen kept the talk open
                            // after the hold's own finger left the button.
                            // Same cancellation verdicts as that helper
                            // (consumed — both passes — or out of bounds),
                            // but THIS pointer's lift ends the hold.
                            var up = false
                            var cancelled = false
                            while (!up && !cancelled) {
                                val event = awaitPointerEvent()
                                if (event.changes.any {
                                        it.isConsumed ||
                                            it.isOutOfBounds(size, extendedTouchPadding)
                                    }
                                ) {
                                    cancelled = true
                                } else if (event.changes.firstOrNull { it.id == down.id }
                                        ?.pressed == false
                                ) {
                                    up = true
                                } else {
                                    // waitForUpOrCancellation's Final-pass
                                    // consume check: a consumer acting after
                                    // Main cancels within the same event.
                                    val finalCheck =
                                        awaitPointerEvent(PointerEventPass.Final)
                                    if (finalCheck.changes.any { it.isConsumed }) {
                                        cancelled = true
                                    }
                                }
                            }
                            if (holding) {
                                holding = false
                                onEnd()
                            }
                        }
                        // else: the swipe cancelled the pending hold before
                        // it ever fired.
                    }
                }
            },
        color = OverlayScrim,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    active -> activeTint
                    else -> warnTint
                },
                modifier = Modifier.size(22.dp)
            )
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
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = expressiveMotionSpec(MaterialTheme.motionScheme.fastSpatialSpec()),
        label = "controlPressScale"
    )
    Surface(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        color = OverlayScrim,
        shape = CircleShape,
        interactionSource = interactionSource,
        enabled = enabled,
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
    zoomRange: ClosedFloatingPointRange<Float>,
    quickSettingsExpanded: Boolean,
    activeSetting: QuickSettingType?,
    recordingState: com.raulshma.lenscast.capture.RecordingState,
    flashAlpha: Animatable<Float, *>,
    /**
     * The roster role's write gate: every control below is a state write
     * (capture, record, stream toggles, quick settings, zoom, lens pick), so
     * a viewer-role pairing renders none of them — the companion live view's
     * controls precedent (viewers never see writes). The local backend
     * pins this true.
     */
    canControl: Boolean,
    onWebStreamToggle: () -> Unit,
    onRtspStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onSelectLens: (Int) -> Unit,
    onZoomPreset: (Float) -> Unit,
    onToggleQuickSettings: () -> Unit,
    onQuickSettingTap: (QuickSettingType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The preset ladder is derived from the lens inventory + live zoom range;
    // with no multi-lens info it degrades to [1x, max] (hidden below two).
    val zoomPresets = remember(availableLenses, zoomRange) {
        ZoomPresetsPolicy.presets(availableLenses, zoomRange)
    }
    Column(
        modifier = modifier.navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (canControl && zoomPresets.size >= ZoomPresetsPolicy.MIN_PRESETS_FOR_ROW) {
            ZoomPresetChipRow(
                presets = zoomPresets,
                currentRatio = settings.zoomRatio,
                onPresetSelected = onZoomPreset,
            )
        }
        AnimatedVisibility(
            visible = canControl && quickSettingsExpanded,
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

        if (canControl && availableLenses.size > 1) {
            LensSelectorRow(
                lenses = availableLenses,
                selectedIndex = selectedLensIndex,
                onLensSelected = onSelectLens
            )
        }

        if (canControl) {
            ShutterRow(
                isWebStreaming = streamStatus.isWebActive,
                isRtspStreaming = streamStatus.isRtspActive,
                isWebEnabled = streamStatus.isWebEnabled,
                isRtspEnabled = streamStatus.isRtspEnabled,
                recordingState = recordingState,
                flashAlpha = flashAlpha,
                quickSettingsExpanded = quickSettingsExpanded,
                onWebStreamToggle = onWebStreamToggle,
                onRtspStreamToggle = onRtspStreamToggle,
                onCapture = onCapture,
                onRecord = onRecord,
                onToggleQuickSettings = onToggleQuickSettings,
            )
        }
    }
}

/**
 * The viewfinder's zoom-preset chip row: one chip per [ZoomPresetsPolicy.ZoomPreset]
 * ("0.5x" / "1x" / "2x" / "MAX"), the active one highlighted within the
 * policy's epsilon, as one connected Expressive [ButtonGroup] — the chips
 * share the connected shapes (the checked one on the active preset) and the
 * press-expansion width physics. A tap snaps zoom through the caller's write
 * path.
 */
@Composable
private fun ZoomPresetChipRow(
    presets: List<ZoomPresetsPolicy.ZoomPreset>,
    currentRatio: Float,
    onPresetSelected: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonGroup(
            overflowIndicator = {},
            horizontalArrangement = Arrangement.spacedBy(
                ButtonGroupDefaults.ConnectedSpaceBetween,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEachIndexed { index, preset ->
                val active = ZoomPresetsPolicy.isActive(preset, currentRatio)
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val shape = when {
                            active -> ButtonGroupDefaults.connectedButtonCheckedShape
                            index == 0 ->
                                if (pressed) ButtonGroupDefaults.connectedLeadingButtonPressShape
                                else ButtonGroupDefaults.connectedLeadingButtonShape
                            index == presets.lastIndex ->
                                if (pressed) ButtonGroupDefaults.connectedTrailingButtonPressShape
                                else ButtonGroupDefaults.connectedTrailingButtonShape
                            else ->
                                if (pressed) ButtonGroupDefaults.connectedMiddleButtonPressShape
                                else RectangleShape
                        }
                        Surface(
                            onClick = { onPresetSelected(preset.ratio) },
                            modifier = Modifier.animateWidth(interactionSource),
                            color = if (active) LensBlue.copy(alpha = 0.92f) else OverlayScrim.copy(alpha = 0.55f),
                            shape = shape,
                            interactionSource = interactionSource,
                        ) {
                            Text(
                                text = preset.label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.88f),
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    },
                    // Presets never overflow (the row scrolls), so the menu
                    // content stays empty and the group's overflow indicator
                    // is intentionally blank.
                    menuContent = {},
                )
            }
        }
    }
}

@Composable
private fun ShutterRow(
    isWebStreaming: Boolean,
    isRtspStreaming: Boolean,
    isWebEnabled: Boolean,
    isRtspEnabled: Boolean,
    recordingState: com.raulshma.lenscast.capture.RecordingState,
    flashAlpha: Animatable<Float, *>,
    quickSettingsExpanded: Boolean,
    onWebStreamToggle: () -> Unit,
    onRtspStreamToggle: () -> Unit,
    onCapture: () -> Unit,
    onRecord: () -> Unit,
    onToggleQuickSettings: () -> Unit,
) {
    val recordTier = CameraDashboardPolicy.recordButtonTier(recordingState)
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
                            kind = CameraDashboardPolicy.StreamKind.WEB,
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
                                contentDescription = stringResource(R.string.camera_collapse_quick_settings_cd),
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
                            kind = CameraDashboardPolicy.StreamKind.RTSP,
                        ),
                        icon = if (isRtspStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                        onClick = onRtspStreamToggle,
                    )
                }
            }

            // The visual verdict is the policy's, read off the controller's
            // full state — an armed schedule shows the distinct armed look
            // instead of the plain record affordance. The tap semantics stay
            // RecordingToggle.decide's (a scheduled start stops like a live
            // recording); only the look differentiates here.
            Surface(
                modifier = Modifier.size(52.dp),
                color = when (recordTier) {
                    CameraDashboardPolicy.RecordButtonTier.RECORDING -> RecordingRed
                    CameraDashboardPolicy.RecordButtonTier.SCHEDULED -> OverlayLight
                    CameraDashboardPolicy.RecordButtonTier.IDLE -> OverlayLight
                },
                shape = CircleShape,
                onClick = onRecord
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (recordTier) {
                            CameraDashboardPolicy.RecordButtonTier.RECORDING -> Icons.Default.Stop
                            CameraDashboardPolicy.RecordButtonTier.SCHEDULED -> Icons.Default.Schedule
                            CameraDashboardPolicy.RecordButtonTier.IDLE -> Icons.Default.FiberManualRecord
                        },
                        contentDescription = stringResource(
                            when (recordTier) {
                                CameraDashboardPolicy.RecordButtonTier.RECORDING -> R.string.camera_stop_recording_cd
                                CameraDashboardPolicy.RecordButtonTier.SCHEDULED -> R.string.camera_recording_scheduled_cd
                                CameraDashboardPolicy.RecordButtonTier.IDLE -> R.string.camera_record_video_cd
                            }
                        ),
                        tint = when (recordTier) {
                            CameraDashboardPolicy.RecordButtonTier.RECORDING -> Color.White
                            CameraDashboardPolicy.RecordButtonTier.SCHEDULED -> LensOrange
                            CameraDashboardPolicy.RecordButtonTier.IDLE -> RecordingRed
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Box(
                modifier = Modifier.size(70.dp),
                contentAlignment = Alignment.Center
            ) {
                ShutterButton(onClick = onCapture, flashAlpha = flashAlpha)
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
    // The verdict is the policy's; the localized label and the theme-adjacent
    // container-color mapping stay here.
    val streamNoun = stringResource(
        when (visual.kind) {
            CameraDashboardPolicy.StreamKind.WEB -> R.string.camera_stream_kind_web
            CameraDashboardPolicy.StreamKind.RTSP -> R.string.camera_stream_kind_rtsp
        }
    )
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
                contentDescription = stringResource(
                    if (visual.container == CameraDashboardPolicy.StreamShutterContainer.RECORDING) {
                        R.string.camera_stream_stop_cd
                    } else {
                        R.string.camera_stream_start_cd
                    },
                    streamNoun,
                ),
                tint = visual.tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ShutterButton(
    onClick: () -> Unit,
    flashAlpha: Animatable<Float, *>,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The press scale is cosmetic only: it never sits on the click path, so
    // the capture still fires at press time.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = expressiveMotionSpec(MaterialTheme.motionScheme.fastSpatialSpec()),
        label = "shutterPressScale"
    )
    // One ring breath per capture event (press, or the self-timer's fire —
    // the flash rides the same events), settled on the effects spec; snap()
    // under reduced motion makes it invisible. The specs resolve outside the
    // flow collection — they are composable, the collector is not.
    val ringScale = remember { Animatable(1f) }
    val ringUpSpec = expressiveMotionSpec(MaterialTheme.motionScheme.fastEffectsSpec<Float>())
    val ringSettleSpec = expressiveMotionSpec(MaterialTheme.motionScheme.defaultEffectsSpec<Float>())
    LaunchedEffect(flashAlpha) {
        snapshotFlow { flashAlpha.value > 0.5f }.collect { flashing ->
            if (flashing) {
                ringScale.animateTo(1.05f, ringUpSpec)
                ringScale.animateTo(1f, ringSettleSpec)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(70.dp)
            .graphicsLayer {
                scaleX = ringScale.value
                scaleY = ringScale.value
            }
            .border(3.dp, Color.White.copy(alpha = 0.95f), CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            interactionSource = interactionSource,
            color = Color.White,
            shape = CircleShape,
            onClick = onClick
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = stringResource(R.string.camera_capture_photo_cd),
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = expressiveMotionSpec(MaterialTheme.motionScheme.fastSpatialSpec()),
        label = "pillPressScale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isActive) LensBlue.copy(alpha = 0.9f)
        else OverlayLight,
        animationSpec = tween(200)
    )

    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        },
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isActive) Color.White else Color.White.copy(alpha = 0.9f)
            )
            Text(
                text = label,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.85f),
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
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.camera_permission_rationale),
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
                    text = stringResource(R.string.camera_permission_grant),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CameraPreview(
    console: CameraConsole,
    /** The concrete remote backend, null on the local one — swaps the base layer. */
    remote: DeviceConsoleViewModel?,
    /** The backend's render-verdict flags: the aid overlays' render gates. */
    capabilities: ConsoleCapabilities,
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
    val settings by console.settings.collectAsState()
    val zoomRange by console.availableZoomRange.collectAsState()
    val gridStyle by console.gridStyle.collectAsState()
    val levelLineState by console.levelLineState.collectAsState()
    val histogramEnabled by console.histogramEnabled.collectAsState()
    val zebrasEnabled by console.zebrasEnabled.collectAsState()
    val peakingEnabled by console.peakingEnabled.collectAsState()
    val proToolsFrame by console.proToolsFrame.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    if (remote == null) {
        // Local-only: the remote backend renders the device's stream through
        // its own stage below and never binds this phone's PreviewView.
        DisposableEffect(previewView, lifecycleOwner) {
            console.startPreview(previewView, lifecycleOwner)
            onDispose { console.stopPreview() }
        }
    }

    Box(modifier = modifier) {
        if (remote != null) {
            // The remote backend's video: the ladder-driven stage fills the
            // preview box with its own chrome OFF (the dashboard renders the
            // rung affordance itself, in dashboard aesthetics, clear of the
            // top overlay row). The overlays stay OFF — re-enabling them
            // would restore the stage's duplicate cycle button — so the
            // localized playback phase (and a failure's reason) renders
            // here instead, as one merged live-region node so screen
            // readers announce the state, a failure included.
            val playback by remote.playback.collectAsStateWithLifecycle()
            RemotePreviewStage(
                viewModel = remote,
                modifier = Modifier.fillMaxSize(),
                showOverlays = false,
            )
            val phaseRes = when (playback.phase) {
                RungPhase.CONNECTING -> R.string.companion_rung_connecting
                RungPhase.LIVE -> R.string.companion_connected
                RungPhase.FAILED -> R.string.companion_rung_failed
            }
            val (statusContainer, statusContent) = when (playback.phase) {
                RungPhase.CONNECTING ->
                    MaterialTheme.colorScheme.tertiaryContainer to
                        MaterialTheme.colorScheme.onTertiaryContainer
                RungPhase.LIVE ->
                    MaterialTheme.colorScheme.primaryContainer to
                        MaterialTheme.colorScheme.onPrimaryContainer
                RungPhase.FAILED ->
                    MaterialTheme.colorScheme.errorContainer to
                        MaterialTheme.colorScheme.onErrorContainer
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = statusContainer,
                contentColor = statusContent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(phaseRes),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val failureReason = playback.failureReason
                    if (playback.phase == RungPhase.FAILED && !failureReason.isNullOrBlank()) {
                        Text(
                            text = failureReason,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.width(180.dp),
                        )
                    }
                }
            }
        } else {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Viewfinder aids: the pure policies own the geometry — these
        // composables only map it onto the preview box (Canvas-only, so the
        // gesture layer below keeps every pointer event). The grid and the
        // gesture layer are backend-neutral (the remote stream takes the
        // same taps and pinches); the sensor-fed aids render only under
        // their capability flags.
        GridOverlay(lines = GridViewPolicy.linesFor(gridStyle))
        if (capabilities.spiritLevel) {
            levelLineState?.let { LevelLineOverlay(state = it) }
        }
        if (capabilities.proToolsOverlays && (histogramEnabled || zebrasEnabled || peakingEnabled)) {
            proToolsFrame?.let { frame ->
                ProToolsCellsOverlay(frame = frame)
                frame.histogram?.let { histogram ->
                    HistogramPanel(
                        bins = histogram,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tap-to-focus is its own detector: a stationary tap reports
                // exactly once, and a pinch (movement past the slop) cancels
                // the tap instead of riding the transform callback — the old
                // transform-detector branch only fired after touch-slop
                // movement, so a clean tap never focused and a small drag
                // did. Coordinates are normalized like LensWebHandler's.
                .pointerInput(Unit) {
                    detectTapGestures { centroid ->
                        console.tapToFocus(
                            (centroid.x / size.width).coerceIn(0f, 1f),
                            (centroid.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                }
                // Pinch-zoom is a camera-state write: gated on canControl
                // like the preset row's control check (the backend no-ops a
                // viewer's zoom anyway), and the zoom-ratio indicator rides
                // the same gate — its writes all come from this pinch path.
                // The tap detector above stays live for every role.
                .pointerInput(capabilities.canControl) {
                    if (capabilities.canControl) {
                        detectTransformGestures { _, _, zoom, _ ->
                            PreviewGestures.onScale(settings.zoomRatio, zoom, zoomRange)?.let { newZoom ->
                                console.updateZoom(newZoom)
                                onPinchStateChange(true, newZoom)
                            }
                        }
                    }
                }
        )

        // The rung chip rides ABOVE the gesture layer: its own tap must
        // cycle the playback rung, not feed the viewfinder's focus/zoom
        // detectors (the full-size gesture box below would otherwise
        // swallow it); the gesture layer stays active over the rest of the
        // viewfinder.
        if (remote != null) {
            RemoteRungChip(
                viewModel = remote,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
            )
        }
    }

    // The indicator retires INDICATOR_HIDE_DELAY_MS after the LAST zoom
    // event: both state fields are in the key, so every pinch write re-arms
    // the deadline and the fire lands once the fingers settle. (The old
    // effect keyed on the flag alone and only ran its body while already
    // hidden — nothing else ever wrote `false`, so the exit transition was
    // dead and the pill never left the viewfinder.)
    LaunchedEffect(isPinching, pinchZoomRatio) {
        if (isPinching) {
            delay(PreviewGestures.INDICATOR_HIDE_DELAY_MS)
            onPinchStateChange(false, pinchZoomRatio)
        }
    }
}

/**
 * The remote viewfinder's rung affordance: which transport the ladder is on
 * (WebRTC / HLS / MJPEG) and that rung's phase as a colored dot, one tap
 * cycling to the next rung — the live tab's rung-header shape, restyled to
 * the dashboard's scrim-chip aesthetics and parked in the top band the
 * remote branch never uses for anything else (the wifi banner and the
 * connection-quality card are both local-server chrome).
 */
@Composable
private fun RemoteRungChip(
    viewModel: DeviceConsoleViewModel,
    modifier: Modifier = Modifier,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    Surface(
        onClick = viewModel::onCycleRung,
        shape = RoundedCornerShape(10.dp),
        color = OverlayScrim.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    when (playback.rung) {
                        PlaybackRung.WHEP -> R.string.companion_rung_whep
                        PlaybackRung.HLS -> R.string.companion_rung_hls
                        PlaybackRung.MJPEG -> R.string.companion_rung_mjpeg
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            // The phase dot: connecting orange, live green, failed red.
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = when (playback.phase) {
                    RungPhase.CONNECTING -> LensOrange
                    RungPhase.LIVE -> LensGreen
                    RungPhase.FAILED -> LensRed
                },
            ) {}
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.companion_cycle_rung_cd),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                LoadingIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.camera_initializing),
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
                        text = stringResource(R.string.camera_error_bang),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.camera_error_title),
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
                Text(stringResource(R.string.camera_retry))
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
                    text = stringResource(R.string.camera_live_badge),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
            }
        }

        if (streamStatus.isWebActive) {
            StreamBadge(icon = Icons.Default.Wifi, label = stringResource(R.string.camera_badge_web))
        }
        if (streamStatus.isRtspActive) {
            StreamBadge(icon = Icons.Default.Videocam, label = stringResource(R.string.camera_badge_rtsp))
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
                color = LensGreen.copy(alpha = dotAlpha)
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
            contentDescription = stringResource(R.string.camera_server_status_cd),
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
                            stringResource(R.string.camera_web_server_title),
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
                                stringResource(R.string.camera_server_offline),
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
                            run {
                                val status = CameraDashboardPolicy.serverStatusLine(
                                    clientCount = streamStatus.clientCount,
                                    isActive = streamStatus.isActive,
                                    isServerRunning = streamStatus.isServerRunning,
                                )
                                when (status.tier) {
                                    CameraDashboardPolicy.ServerStatusTier.LIVE ->
                                        if (status.viewerCount > 0) {
                                            pluralStringResource(
                                                R.plurals.camera_server_status_viewers, status.viewerCount, status.viewerCount,
                                            )
                                        } else {
                                            stringResource(R.string.camera_server_status_live)
                                        }
                                    CameraDashboardPolicy.ServerStatusTier.READY -> stringResource(R.string.camera_server_status_ready)
                                    CameraDashboardPolicy.ServerStatusTier.OFFLINE -> stringResource(R.string.camera_server_status_offline)
                                }
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
                            Text(stringResource(R.string.camera_copy_url), style = MaterialTheme.typography.bodyMedium)
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
                                stringResource(R.string.camera_rtsp_stream_title),
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
                            Text(stringResource(R.string.camera_copy_rtsp_url), style = MaterialTheme.typography.bodyMedium)
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
                                if (streamStatus.isServerRunning) stringResource(R.string.camera_turn_server_off)
                                else stringResource(R.string.camera_turn_server_on),
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
    severity: CameraDashboardPolicy.ThermalSeverity,
    modifier: Modifier = Modifier,
) {
    // The verdict is the policy's; the localized label and the theme-adjacent
    // color mapping stay here.
    val color = when (severity) {
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
            text = stringResource(
                when (severity) {
                    CameraDashboardPolicy.ThermalSeverity.MODERATE -> R.string.camera_thermal_moderate
                    CameraDashboardPolicy.ThermalSeverity.SEVERE -> R.string.camera_thermal_severe
                    CameraDashboardPolicy.ThermalSeverity.CRITICAL -> R.string.camera_thermal_critical
                }
            ),
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

// ── Viewfinder aid overlays ──
// The pure policies decide the geometry and the verdicts (GridViewPolicy,
// LevelIndicatorPolicy, ProToolsPolicy); these composables only map the
// results onto the preview box.

@Composable
private fun GridOverlay(
    lines: List<GridViewPolicy.GridLine>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        lines.forEach { line ->
            when (line.axis) {
                GridViewPolicy.Axis.VERTICAL -> drawLine(
                    color = GridLineColor,
                    start = Offset(size.width * line.fraction, 0f),
                    end = Offset(size.width * line.fraction, size.height),
                    strokeWidth = stroke,
                )
                GridViewPolicy.Axis.HORIZONTAL -> drawLine(
                    color = GridLineColor,
                    start = Offset(0f, size.height * line.fraction),
                    end = Offset(size.width, size.height * line.fraction),
                    strokeWidth = stroke,
                )
            }
        }
    }
}

@Composable
private fun LevelLineOverlay(
    state: LevelIndicatorPolicy.LevelLineState,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // The policy's verdict picks the color: the line goes green only
        // within the level threshold on both axes.
        val color = if (state.isLevel) LevelAlignedColor else Color.White.copy(alpha = 0.85f)
        val halfLength = size.width * 0.3f
        val radians = Math.toRadians(state.lineRotationDeg.toDouble())
        val centerX = size.width / 2f
        val centerY = size.height / 2f + state.lineCenterOffsetFraction * (size.height / 2f)
        val dx = cos(radians).toFloat() * halfLength
        val dy = sin(radians).toFloat() * halfLength
        drawLine(
            color = color,
            start = Offset(centerX - dx, centerY - dy),
            end = Offset(centerX + dx, centerY + dy),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ProToolsCellsOverlay(
    frame: ProToolsPolicy.ProToolsFrame,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        frame.cells.forEach { cell ->
            drawRect(
                color = when (cell.kind) {
                    ProToolsPolicy.CellKind.ZEBRA_OVER -> ZebraOverColor
                    ProToolsPolicy.CellKind.ZEBRA_UNDER -> ZebraUnderColor
                    ProToolsPolicy.CellKind.PEAK -> PeakingColor
                },
                topLeft = Offset(cell.left * size.width, cell.top * size.height),
                size = Size(
                    width = (cell.right - cell.left) * size.width,
                    height = (cell.bottom - cell.top) * size.height,
                ),
            )
        }
    }
}

@Composable
private fun HistogramPanel(
    bins: IntArray,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .padding(start = 16.dp, bottom = 230.dp)
            .size(width = 140.dp, height = 64.dp)
            .background(OverlayScrim.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        val maxCount = bins.max()
        val barWidth = size.width / ProToolsPolicy.HISTOGRAM_BINS
        bins.forEachIndexed { index, count ->
            val barHeight = if (maxCount > 0) size.height * count.toFloat() / maxCount else 0f
            drawRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(index * barWidth, size.height - barHeight),
                size = Size(width = barWidth, height = barHeight),
            )
        }
    }
}

@Composable
private fun SelfTimerCountdownOverlay(
    secondsRemaining: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Full-screen tap surface: the tap cancels the countdown (the timer mode
    // stays selected, so the next press counts down again).
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Transparent,
        onClick = onCancel,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$secondsRemaining",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
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
    val label = stringResource(
        when (qualityLevel) {
            NetworkQualityLevel.EXCELLENT -> R.string.camera_quality_abbr_excellent
            NetworkQualityLevel.GOOD -> R.string.camera_quality_abbr_good
            NetworkQualityLevel.FAIR -> R.string.camera_quality_abbr_fair
            NetworkQualityLevel.POOR -> R.string.camera_quality_abbr_poor
            NetworkQualityLevel.CRITICAL -> R.string.camera_quality_abbr_critical
        }
    )

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
                        text = pluralStringResource(
                            R.plurals.camera_client_summary, activeClients, activeClients, minThroughputKbps,
                        ),
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
                        text = stringResource(R.string.camera_connection_quality_title),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConnectionStatRow(label = stringResource(R.string.camera_quality_label), value = label, valueColor = dotColor)
                    CameraDashboardPolicy.connectionStatRows(
                        estimatedBandwidthKbps = estimatedBandwidthKbps,
                        stats = stats,
                    ).forEach { row ->
                        ConnectionStatRow(label = stringResource(row.labelRes), value = row.value)
                    }

                    if (stats.clientDetails.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.camera_per_client_stats),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        stats.clientDetails.forEach { (clientId, detail) ->
                            Text(
                                text = stringResource(
                                    R.string.camera_client_stat_header,
                                    CameraDashboardPolicy.clientIdPrefix(clientId),
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            )
                            CameraDashboardPolicy.clientStatRows(detail).forEach { row ->
                                ConnectionStatRow(label = stringResource(row.labelRes), value = row.value)
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
                            text = localizedLensLabel(lens),
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

/**
 * The chip label, built from the lens's [LensLabelKind] and string
 * resources — the inventory hands down data, never finished English
 * strings, so the chips localize with the rest of the screen. Zoom lenses
 * render their relative magnification ("2x", locale-formatted); with no
 * ratio (unknown 1x reference) they fall back to the focal length.
 */
@Composable
private fun localizedLensLabel(lens: CameraLensInfo): String {
    val front = lens.lensFacing == CameraSelector.LENS_FACING_FRONT
    return when (lens.labelKind) {
        LensLabelKind.FRONT -> stringResource(R.string.lens_front)
        LensLabelKind.BACK -> stringResource(R.string.lens_back)
        LensLabelKind.ULTRAWIDE ->
            if (front) stringResource(R.string.lens_front_ultrawide)
            else stringResource(R.string.lens_ultrawide)
        LensLabelKind.WIDE -> stringResource(R.string.lens_wide)
        LensLabelKind.ZOOM -> {
            val zoom = if (lens.zoomRatio != null && lens.zoomRatio > 0f) {
                LensInventory.formatZoom(lens.zoomRatio, Locale.getDefault())
            } else {
                LensInventory.formatFocal(lens.focalLength, Locale.getDefault())
            }
            lensVariant(front, zoom)
        }
        LensLabelKind.FOCAL_MM ->
            lensVariant(front, LensInventory.formatFocal(lens.focalLength, Locale.getDefault()))
        LensLabelKind.CAMERA_ID -> stringResource(R.string.lens_camera_id, lens.id)
    }
}

/** "Front 2x"-style composition for the front camera's secondary lenses. */
@Composable
private fun lensVariant(front: Boolean, variant: String): String =
    if (front) stringResource(R.string.lens_front_variant, variant) else variant
