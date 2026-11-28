package com.raulshma.lenscast.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.FlashModePolicy
import com.raulshma.lenscast.camera.model.CameraInitRetry
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.ConsoleCapabilities
import com.raulshma.lenscast.camera.model.CountdownState
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.LevelIndicatorPolicy
import com.raulshma.lenscast.camera.model.ProToolsPolicy
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditorValue
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.RecordingToggle
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.camera.model.SelfTimerPolicy
import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.StreamStatusSnapshot
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.camera.model.stickyCameraState
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.MicGate
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.StreamingTransports
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CameraViewModel(
    context: Context,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val thermalMonitor: ThermalMonitor,
    private val settingsDataStore: SettingsDataStore,
    private val streamingSession: StreamingSession,
    streamWatchdog: StreamWatchdog,
    connectivityMonitor: ConnectivityMonitor,
    private val recordingController: com.raulshma.lenscast.capture.RecordingController,
    private val photoCaptureManager: com.raulshma.lenscast.capture.PhotoCaptureManager,
    private val lightSensorMonitor: LightSensorMonitor,
    ) : ViewModel(), CameraConsole {
        private val context: Context = context.applicationContext

    val watchdogState = streamWatchdog.state

    // Live app-scoped state exposed directly — no ViewModel mirrors for state
    // that outlives this ViewModel.
    override val wifiConnected: StateFlow<Boolean> = connectivityMonitor.isWifiConnected
    override val thermalState: StateFlow<ThermalState> = thermalMonitor.thermalState
    override val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> =
        streamingManager.adaptiveBitrateState

    // The console seam's capability table (see CameraConsole): the local
    // backend honors everything the dashboard renders, so the constant
    // local set is the whole verdict — the screen hides nothing. A flow,
    // not a val, only so the remote console backend can republish as its
    // device probe answers; this one never rewrites it.
    override val capabilities: StateFlow<ConsoleCapabilities> =
        MutableStateFlow(ConsoleCapabilities.local())

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    override val settings: StateFlow<CameraSettings> = settingsDataStore.settings

    private val _streamStatus = MutableStateFlow(StreamStatus())
    override val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(false)
    override val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    // The POST_NOTIFICATIONS ask-once budget: the verdict is
    // NotificationPermissionPolicy's, this state only makes "asked before"
    // survive recompositions and configuration changes with the screen's
    // ViewModel (the old home was a process-global var in the screen).
    private val _notificationPermissionAsked = MutableStateFlow(false)
    override val notificationPermissionAsked: StateFlow<Boolean> = _notificationPermissionAsked.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    override val availableLenses: StateFlow<List<CameraLensInfo>> = cameraService.availableLenses
    override val selectedLensIndex: StateFlow<Int> = cameraService.selectedLensIndex
    override val availableIsoRange: StateFlow<ClosedRange<Int>> = cameraService.availableIsoRange
    override val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = cameraService.availableZoomRange
    override val availableExposureRange: StateFlow<ClosedRange<Int>> = cameraService.availableExposureRange

    // Recording truth lives in the app-scoped RecordingController; this
    // ViewModel only derives display state from it.
    override val recordingState: StateFlow<com.raulshma.lenscast.capture.RecordingState> =
        recordingController.state

    override val isRecording: StateFlow<Boolean> = recordingController.isRecording

    // One shared clock over the controller's state (1s ticks, whole seconds).
    private val recordingClock = com.raulshma.lenscast.capture.RecordingClock(
        recordingState = recordingController.state,
        scope = viewModelScope,
        tickMs = 1000L,
    )
    override val recordingElapsedSeconds: StateFlow<Int> = recordingClock.elapsedSeconds

    override val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview

    private val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled

    private val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled

    private val _connectionQualityStats = MutableStateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?>(null)
    override val connectionQualityStats: StateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?> = _connectionQualityStats.asStateFlow()

    private val _encodedStreamStatus = MutableStateFlow<CameraDashboardPolicy.EncodedLaneStatus?>(null)
    override val encodedStreamStatus: StateFlow<CameraDashboardPolicy.EncodedLaneStatus?> = _encodedStreamStatus.asStateFlow()

    // ── Camera pro mode ──
    // Viewfinder aids (grid, spirit level, pro-tools overlays) and the
    // self-timer read straight from the Settings Store; the analyzer/monitor
    // runtimes own only plumbing — every verdict is their pure policy's.

    override val gridStyle: StateFlow<GridStyle> = settingsDataStore.gridStyle

    val selfTimer: StateFlow<SelfTimerMode> = settingsDataStore.selfTimer

    val spiritLevelEnabled: StateFlow<Boolean> = settingsDataStore.spiritLevelEnabled

    override val histogramEnabled: StateFlow<Boolean> = settingsDataStore.histogramEnabled

    override val zebrasEnabled: StateFlow<Boolean> = settingsDataStore.zebrasEnabled

    override val peakingEnabled: StateFlow<Boolean> = settingsDataStore.peakingEnabled

    private val geotagEnabled: StateFlow<Boolean> = settingsDataStore.geotagEnabled

    // The pro-tools frame consumer: rides the analysis NV21 frames while the
    // preview is up; the per-frame flags read is a cheap settings snapshot.
    private val proToolsFrameAnalyzer = ProToolsFrameAnalyzer(
        flags = {
            ProToolsPolicy.Flags(
                histogram = histogramEnabled.value,
                zebras = zebrasEnabled.value,
                peaking = peakingEnabled.value,
            )
        },
    )
    override val proToolsFrame: StateFlow<ProToolsPolicy.ProToolsFrame?> = proToolsFrameAnalyzer.frame

    // The level's sensors run only while both the setting is on and the
    // preview is showing (the second gate lives in start/stopPreview).
    private val spiritLevelMonitor = SpiritLevelMonitor(context)
    override val levelLineState: StateFlow<LevelIndicatorPolicy.LevelLineState?> = spiritLevelMonitor.lineState

    // The self-timer countdown machinery (Idle → Counting → Firing → Idle)
    // lives in the shared SelfTimerRunner — the same holder the remote
    // console backend runs, so the countdown behavior is identical over both
    // backends. Every verdict is SelfTimerPolicy's; the runner holds only the
    // job and the state flow.
    private val selfTimerRunner = SelfTimerRunner(
        scope = viewModelScope,
        onFire = { capturePhoto() },
    )
    val selfTimerState: StateFlow<CountdownState> = selfTimerRunner.state

    // Derived remaining-seconds the countdown overlay renders (null when no
    // countdown is counting — Idle and Firing both render as "none").
    override val selfTimerSecondsRemaining: StateFlow<Int?> = selfTimerRunner.secondsRemaining

    // One-shot signal per actual capture (immediate or timer fire) — the
    // screen's shutter flash rides it, so a countdown flashes at fire time.
    private val _captureFlashEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val captureFlashEvents: SharedFlow<Unit> = _captureFlashEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            cameraService.cameraState.collect { state ->
                // Never regress to Idle: the service reports Idle whenever no
                // camera session is live, and the screen keeps the state it
                // already reached. The verdict is the shared stickyCameraState.
                _cameraState.value = stickyCameraState(_cameraState.value, state)
            }
        }
        viewModelScope.launch {
            cameraService.isFrontCamera.collect { isFront ->
                _isFrontCamera.value = isFront
            }
        }

        // Combined: all streaming status updates in typed combines,
        // mapped through the shared snapshot builder — no untyped lists.
        viewModelScope.launch {
            val videoFlow = combine(
                streamingManager.isStreaming,
                streamingManager.isWebStreamingActive,
                streamingManager.isServerRunning,
                streamingManager.streamUrl,
                streamingManager.clientCount,
            ) { isStreaming, isWebActive, isServerRunning, url, clientCount ->
                StreamStatusSnapshot.VideoInputs(
                    isStreaming = isStreaming,
                    isWebActive = isWebActive,
                    isServerRunning = isServerRunning,
                    url = url,
                    clientCount = clientCount,
                )
            }

            val audioFlow = combine(
                streamingManager.isAudioStreaming,
                streamingManager.audioStreamUrl,
                streamingManager.isRtspRunning,
                streamingManager.rtspUrl,
            ) { isAudioActive, audioUrl, isRtspActive, rtspUrl ->
                StreamStatusSnapshot.AudioInputs(
                    isAudioActive = isAudioActive,
                    audioUrl = audioUrl,
                    isRtspActive = isRtspActive,
                    rtspUrl = rtspUrl,
                )
            }

            combine(
                videoFlow,
                audioFlow,
                streamingManager.isWebEnabled,
                streamingManager.isRtspEnabled,
            ) { video, audio, isWebEnabled, isRtspEnabled ->
                _streamStatus.value = StreamStatusSnapshot.build(
                    video = video,
                    audio = audio,
                    isWebEnabled = isWebEnabled,
                    isRtspEnabled = isRtspEnabled,
                )
            }.collect { }
        }

        // Optimized: Connection quality polling with early cancellation
        viewModelScope.launch {
            streamingManager.isStreaming.collectLatest { isActive ->
                if (isActive) {
                    while (true) {
                        _connectionQualityStats.value = streamingManager.getNetworkStatsSnapshot()
                        _encodedStreamStatus.value = streamingManager.encodedStreamStatus().let {
                            CameraDashboardPolicy.EncodedLaneStatus(
                                running = it.running,
                                measuredBitrateKbps = it.measuredBitrateKbps,
                            )
                        }
                        delay(2500)
                    }
                } else {
                    _connectionQualityStats.value = null
                    _encodedStreamStatus.value = null
                }
            }
        }

        // The spirit level's sensor registration follows the persisted toggle;
        // the preview-active gate opens and closes in start/stopPreview.
        viewModelScope.launch {
            settingsDataStore.spiritLevelEnabled.collect { spiritLevelMonitor.setEnabled(it) }
        }

        // The persisted self-timer mode is the countdown's source of truth:
        // flipping it to OFF mid-countdown cancels the countdown, whatever
        // setter path wrote the mode (the settings screen's dropdown today,
        // any quick-setting write tomorrow).
        viewModelScope.launch {
            settingsDataStore.selfTimer.collect { mode ->
                if (mode == SelfTimerMode.OFF && selfTimerRunner.state.value is CountdownState.Counting) {
                    selfTimerRunner.cancelCountdown()
                }
            }
        }

        checkPermission()
    }

    fun checkPermission() {
        refreshPermissions()
        Log.d(TAG, "checkPermission: granted=${_hasCameraPermission.value}")
        if (_hasCameraPermission.value) {
            initializeCamera()
        } else {
            _cameraState.value = CameraState.RequestPermission
        }
    }

    override fun onPermissionResult(cameraGranted: Boolean, audioGranted: Boolean) {
        _hasCameraPermission.value = cameraGranted
        _hasAudioPermission.value = audioGranted
        if (cameraGranted) {
            initializeCamera()
        } else {
            _cameraState.value = CameraState.RequestPermission
        }
    }

    override fun onAudioPermissionResult(granted: Boolean) {
        _hasAudioPermission.value = granted
        if (granted && _streamStatus.value.isActive && streamAudioEnabled.value) {
            streamingManager.setStreamAudioEnabled(true)
        }
    }

    /** Consumes the notification ask-once budget (the screen marks on its first composition, before any verdict). */
    override fun markNotificationPermissionAsked() {
        _notificationPermissionAsked.value = true
    }

    private fun initializeCamera() {
        viewModelScope.launch {
            Log.d(TAG, "initializeCamera: starting...")
            _cameraState.value = CameraState.Initializing
            val result = cameraService.initialize()
            Log.d(TAG, "initializeCamera: result=${result.isSuccess}, exception=${result.exceptionOrNull()?.message}")
            if (result.isSuccess) {
                // A successful init refills the retry budget: the budget caps
                // consecutive failures, not failures per session.
                retryCount = 0
                _cameraState.value = CameraState.Ready
            } else {
                _cameraState.value = CameraState.Error(
                    result.exceptionOrNull()?.message ?: "Camera initialization failed"
                )
            }
        }
    }

    private var retryCount = 0

    override fun retryCameraInit() {
        if (CameraInitRetry.shouldRetry(retryCount)) {
            retryCount++
            initializeCamera()
        } else {
            // Never a silent no-op: an exhausted budget tells the user so.
            _cameraState.value = CameraState.Error(
                "Camera did not recover after $retryCount retries. Restart the app, and check " +
                    "the camera permission and the system camera-access toggle."
            )
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun startPreview(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        cameraService.setLifecycleOwner(lifecycleOwner)
        cameraService.startPreview(previewView)
        // The pro-tools analyzer rides the analysis frames only while a
        // preview is up; the level's sensor gate opens with it.
        cameraService.setProToolsFrameListener { nv21, width, height, rotation ->
            proToolsFrameAnalyzer.onFrame(nv21, width, height, rotation, System.currentTimeMillis())
        }
        spiritLevelMonitor.setPreviewActive(true)
        lightSensorMonitor.setPreviewActive(true)
    }

    override fun stopPreview() {
        cameraService.stopPreview()
        cameraService.setProToolsFrameListener(null)
        // Drop the retained overlay frame with the preview: a re-enabled
        // preview must not flash the minutes-old analysis result.
        proToolsFrameAnalyzer.clear()
        spiritLevelMonitor.setPreviewActive(false)
        lightSensorMonitor.setPreviewActive(false)
    }

    // The service owns its own preview view; switching works headless too.
    override fun switchCamera() {
        cameraService.switchCamera()
    }

    override fun selectLens(index: Int) {
        cameraService.selectLens(index)
    }

    /** Normalized (0..1) preview tap coordinates; user-initiated metering. */
    override fun tapToFocus(x: Float, y: Float) {
        cameraService.tapToFocus(x, y)
    }

    /** Pinch-zoom path — the only per-field writer left; everything else funnels through [updateQuickSetting]. */
    override fun updateZoom(ratio: Float) {
        updateSettings { it.copy(zoomRatio = ratio) }
    }

    /**
     * The quick-setting sheet's single write entry: the raw editor callback
     * value is converted once onto the typed [QuickSettingEditorValue] per
     * the descriptor's editor shape, then dispatched through the catalog's
     * pure write transform onto the one CameraSettingsEditor path
     * (persist-only; the Settings Applier applies the persisted write).
     */
    override fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
        updateSettings { current -> QuickSettingCatalog.descriptorFor(type).write(current, editorValue) }
    }

    override fun togglePreview() {
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(!showPreview.value)
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            // Atomic field-level edit against disk truth (issue #25 / default-first
            // race): avoids clobbering concurrent writes (e.g. converged torch
            // or web zoom) with a lagging StateFlow snapshot.
            settingsDataStore.updateCameraSettings { current ->
                CameraSettingsEditor.normalizeManualDefaults(transform(current))
            }
        }
    }

    private val _lastServerError = MutableStateFlow<String?>(null)

    /** The server-start failure line the server status panel renders — written
     *  only by the stream outcome mapping below (no ladder sets it directly);
     *  cleared by the next successful start — retrying is the natural dismiss. */
    override val lastServerError: StateFlow<String?> = _lastServerError.asStateFlow()

    // One stream start/stop seam for both outputs and the server: the
    // gate → start → session begin → rollback ladder lives once in
    // StreamToggle; this ViewModel only maps outcomes onto toasts. The
    // transports are the shared StreamingTransports adapter over the manager
    // (whose live flows are the gate source of truth) and the session — the
    // same one the Web API Stream Handler toggles through.
    private val streamTransports = StreamingTransports(streamingManager, streamingSession)

    // The mic warn-and-degrade gate both the stream pre-start hook and the
    // recording toggle consult: refresh-then-cache — the refresh updates the
    // screen's exposed audio-permission state, and the freshly refreshed
    // value is what the consult reads. One ladder for every feature start.
    private val micGate = MicGate(
        context = context,
        refreshGranted = { refreshAudioPermission(); _hasAudioPermission.value },
    )

    private val streamToggle = StreamToggle(
        transports = streamTransports,
        // The pre-start mic warn-and-degrade check — web only, exactly as
        // before the seam existed.
        onBeforeStart = { kind ->
            if (kind == StreamKind.WEB) {
                micGate.consult(featureEnabled = streamAudioEnabled.value, featureLabel = "Streaming video")
            }
        },
    )

    override fun toggleWebStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleWeb()) }
    }

    override fun toggleRtspStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleRtsp()) }
    }

    override fun toggleServer() {
        viewModelScope.launch {
            if (_streamStatus.value.isServerRunning) {
                // Stopped outcome needs no user-facing report — the derived
                // StreamStatus flips on its own.
                streamToggle.stopServer()
            } else {
                // The whole-server ladder is StreamToggle.startServer's
                // (ensure server → session begin → rollback); the outcome
                // rides the same mapping as the outputs'.
                handleStreamOutcome(streamToggle.startServer())
            }
        }
    }

    private fun handleStreamOutcome(outcome: StreamStartOutcome) {
        when (outcome) {
            // A successful start clears the panel's stale server-failure
            // line; a stop leaves it alone (the old behavior) —
            // StreamStatus itself is derived truth.
            StreamStartOutcome.Started -> _lastServerError.value = null
            StreamStartOutcome.Stopped -> Unit
            is StreamStartOutcome.Disabled ->
                Toast.makeText(
                    context, StreamStartOutcome.disabledMessage(outcome.kind), Toast.LENGTH_SHORT
                ).show()
            is StreamStartOutcome.StartFailed ->
                reportStartFailure(outcome.kind, cause = null)
            is StreamStartOutcome.BeginFailedRolledBack ->
                reportStartFailure(outcome.kind, outcome.cause)
        }
    }

    // One failure surface per start kind, from the one outcome mapping: the
    // outputs warn through the shared toast; the whole-server start (kind
    // null) lands its message in the server status panel's failure line — no
    // manager flow carries that failure, so it stays the one-shot state the
    // next successful start clears.
    private fun reportStartFailure(kind: StreamKind?, cause: Exception?) {
        if (cause != null) {
            val subject = if (kind == null) "server" else "stream"
            Log.e(TAG, "Streaming session setup failed; $subject rolled back", cause)
        }
        val message = StreamStartOutcome.startFailedMessage(kind)
        if (kind == null) {
            _lastServerError.value = message
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // One clipboard+toast path for every copyable URL: derived-truth value
    // first, raw flow as the empty fallback, per-URL label and message.
    private fun copyUrlToClipboard(url: String, fallback: String, clipLabel: String, copiedMessage: String) {
        val value = url.ifEmpty { fallback }
        if (value.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, value))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    override fun copyStreamUrl() {
        copyUrlToClipboard(
            url = _streamStatus.value.url,
            fallback = streamingManager.streamUrl.value,
            clipLabel = "Stream URL",
            copiedMessage = "Stream URL copied",
        )
    }

    override fun copyRtspUrl() {
        copyUrlToClipboard(
            url = _streamStatus.value.rtspUrl,
            fallback = streamingManager.rtspUrl.value,
            clipLabel = "RTSP URL",
            copiedMessage = "RTSP URL copied",
        )
    }

    override fun getConnectInfo(): com.raulshma.lenscast.streaming.StreamingManager.ConnectInfo =
        streamingManager.getConnectInfo()

    /**
     * The web identity's TLS certificate fingerprint (empty until the TLS
     * server has built its identity). The connect sheet reads it through
     * here — once per open — instead of reaching around this ViewModel into
     * the app-scoped manager.
     */
    override fun tlsCertificateFingerprint(): String = streamingManager.tlsCertificateFingerprint()

    override fun copyHlsUrl() {
        val info = streamingManager.getConnectInfo()
        copyUrlToClipboard(
            url = info.hlsUrl,
            fallback = info.hlsUrl,
            clipLabel = "HLS URL",
            copiedMessage = "HLS URL copied",
        )
    }

    /**
     * The shutter press: the capture-now/countdown verdict is SelfTimerPolicy's
     * (dispatched inside the shared runner); a countdown fires the same
     * [capturePhoto] path at the policy's Fire boundary.
     */
    override fun onShutterPress() {
        selfTimerRunner.onShutterPress(selfTimer.value) { capturePhoto() }
    }

    /** A tap while the countdown runs cancels it (the verdict is SelfTimerPolicy's). */
    override fun cancelSelfTimer() {
        selfTimerRunner.cancelSelfTimer()
    }

    fun capturePhoto() {
        // The persisted flash mode rides every main-shutter capture; the
        // CameraX constant mapping is the one seam translation here.
        val flashMode = when (flashModeSetting.value) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
        val fileName = photoCaptureManager.captureToGallery(
            flashMode = flashMode,
            onSaved = { filePath, _ -> tagCapturedPhoto(filePath) },
            onError = { exception -> Log.e(TAG, "Capture failed", exception) },
        )
        if (fileName == null) {
            Log.w(TAG, "capturePhoto: camera use case unavailable")
            return
        }
        _captureFlashEvents.tryEmit(Unit)
    }

    /** The persisted main-shutter flash mode (OFF / AUTO / ON). */
    override val flashModeSetting: StateFlow<FlashMode> = settingsDataStore.flashMode

    /** A tap on the flash chip: the cycle order is FlashModePolicy's. */
    override fun cycleFlashMode() {
        viewModelScope.launch {
            settingsDataStore.saveFlashMode(FlashModePolicy.next(flashModeSetting.value))
        }
    }

    // EXIF tagging: the app-credit Artist/UserComment tags on every capture,
    // GPS only while the opt-in geotag setting is on AND the location
    // permission is granted (the settings screen asks once when the toggle
    // first turns on; a denied grant degrades to credit-only tags).
    private val exifTagger by lazy {
        ExifTagger(
            context = context,
            appName = context.getString(R.string.app_name),
            locationProvider = { currentLocationFix() },
        )
    }

    private fun tagCapturedPhoto(filePath: String) {
        viewModelScope.launch {
            exifTagger.tagPhoto(
                filePath,
                includeGeotag = geotagEnabled.value && hasLocationPermission(),
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** The freshest last-known fix across the standard providers, or null. */
    private fun currentLocationFix(): android.location.Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return null
        return listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filterNotNull()
            .maxByOrNull { it.time }
    }

    /**
     * The record button: the stop-vs-start verdict and the start config are
     * [RecordingToggle]'s; this ViewModel only executes the decision — the
     * default config carries the audio setting, and the mic gate rides the
     * toggle's pre-start hook, so a stop never refreshes permissions or
     * warns.
     */
    override fun toggleRecording() {
        when (val decision = RecordingToggle.decide(
            currentState = recordingState.value,
            startConfig = RecordingConfig(includeAudio = recordingAudioEnabled.value),
            onBeforeStart = micGate.recordingConfigConsult(label = "Recording video"),
        )) {
            is RecordingToggle.ToggleDecision.Start -> recordingController.start(decision.config)
            RecordingToggle.ToggleDecision.Stop -> recordingController.stop()
        }
    }

    class Factory(
        private val context: Context,
        private val cameraService: CameraService,
        private val streamingManager: StreamingManager,
        private val thermalMonitor: ThermalMonitor,
        private val settingsDataStore: SettingsDataStore,
        private val streamingSession: StreamingSession,
        private val streamWatchdog: StreamWatchdog,
        private val connectivityMonitor: ConnectivityMonitor,
        private val recordingController: com.raulshma.lenscast.capture.RecordingController,
        private val photoCaptureManager: com.raulshma.lenscast.capture.PhotoCaptureManager,
        private val lightSensorMonitor: LightSensorMonitor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraViewModel(
                context, cameraService, streamingManager, thermalMonitor,
                settingsDataStore, streamingSession, streamWatchdog, connectivityMonitor,
                recordingController, photoCaptureManager, lightSensorMonitor
            ) as T
        }
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private fun refreshPermissions() {
        _hasCameraPermission.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        refreshAudioPermission()
    }

    private fun refreshAudioPermission() {
        _hasAudioPermission.value = MicAccess.isGranted(context)
    }

    override fun onCleared() {
        selfTimerRunner.dispose()
        cameraService.setProToolsFrameListener(null)
        spiritLevelMonitor.setPreviewActive(false)
        lightSensorMonitor.setPreviewActive(false)
        super.onCleared()
    }
}
