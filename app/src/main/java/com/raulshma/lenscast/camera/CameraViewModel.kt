package com.raulshma.lenscast.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.camera.model.CameraInitRetry
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditorValue
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.RecordingToggle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    ) : ViewModel() {
        private val context: Context = context.applicationContext

    val watchdogState = streamWatchdog.state

    // Live app-scoped state exposed directly — no ViewModel mirrors for state
    // that outlives this ViewModel.
    val wifiConnected: StateFlow<Boolean> = connectivityMonitor.isWifiConnected
    val thermalState: StateFlow<ThermalState> = thermalMonitor.thermalState
    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> =
        streamingManager.adaptiveBitrateState

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    val settings: StateFlow<CameraSettings> = settingsDataStore.settings

    private val _streamStatus = MutableStateFlow(StreamStatus())
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(false)
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    val availableLenses: StateFlow<List<CameraLensInfo>> = cameraService.availableLenses
    val selectedLensIndex: StateFlow<Int> = cameraService.selectedLensIndex
    val availableIsoRange: StateFlow<ClosedRange<Int>> = cameraService.availableIsoRange
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = cameraService.availableZoomRange
    val availableExposureRange: StateFlow<ClosedRange<Int>> = cameraService.availableExposureRange

    // Recording truth lives in the app-scoped RecordingController; this
    // ViewModel only derives display state from it.
    val recordingState: StateFlow<com.raulshma.lenscast.capture.RecordingState> =
        recordingController.state

    val isRecording: StateFlow<Boolean> = recordingController.isRecording

    // One shared editor for camera-control writes: apply now for
    // responsiveness, persist for the Settings Applier to re-apply.
    private val settingsEditor = CameraSettingsEditor(
        current = { settings.value },
        persist = { settingsDataStore.saveSettings(it) },
        apply = { cameraService.applySettings(it) },
    )

    // One shared clock over the controller's state (1s ticks, whole seconds).
    private val recordingClock = com.raulshma.lenscast.capture.RecordingClock(
        recordingState = recordingController.state,
        scope = viewModelScope,
        tickMs = 1000L,
    )
    val recordingElapsedSeconds: StateFlow<Int> = recordingClock.elapsedSeconds

    val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview

    private val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled

    private val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled

    private val _connectionQualityStats = MutableStateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?>(null)
    val connectionQualityStats: StateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?> = _connectionQualityStats.asStateFlow()

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
                        delay(2500)
                    }
                } else {
                    _connectionQualityStats.value = null
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

    fun onPermissionResult(cameraGranted: Boolean, audioGranted: Boolean) {
        _hasCameraPermission.value = cameraGranted
        _hasAudioPermission.value = audioGranted
        if (cameraGranted) {
            initializeCamera()
        } else {
            _cameraState.value = CameraState.RequestPermission
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        _hasAudioPermission.value = granted
        if (granted && _streamStatus.value.isActive && streamAudioEnabled.value) {
            streamingManager.setStreamAudioEnabled(true)
        }
    }

    private fun initializeCamera() {
        viewModelScope.launch {
            Log.d(TAG, "initializeCamera: starting...")
            _cameraState.value = CameraState.Initializing
            val result = cameraService.initialize()
            Log.d(TAG, "initializeCamera: result=${result.isSuccess}, exception=${result.exceptionOrNull()?.message}")
            if (result.isSuccess) {
                _cameraState.value = CameraState.Ready
            } else {
                _cameraState.value = CameraState.Error(
                    result.exceptionOrNull()?.message ?: "Camera initialization failed"
                )
            }
        }
    }

    private var retryCount = 0

    fun retryCameraInit() {
        if (CameraInitRetry.shouldRetry(retryCount)) {
            retryCount++
            initializeCamera()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    fun startPreview(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        cameraService.setLifecycleOwner(lifecycleOwner)
        cameraService.startPreview(previewView)
        viewModelScope.launch {
            cameraService.applySettings(settings.value)
        }
    }

    fun stopPreview() {
        cameraService.stopPreview()
    }

    // The service owns its own preview view; switching works headless too.
    fun switchCamera() {
        cameraService.switchCamera()
    }

    fun selectLens(index: Int) {
        cameraService.selectLens(index)
    }

    /** Normalized (0..1) preview tap coordinates; user-initiated metering. */
    fun tapToFocus(x: Float, y: Float) {
        cameraService.tapToFocus(x, y)
    }

    /** Pinch-zoom path — the only per-field writer left; everything else funnels through [updateQuickSetting]. */
    fun updateZoom(ratio: Float) {
        updateSettings { it.copy(zoomRatio = ratio) }
    }

    /**
     * The quick-setting sheet's single write entry: the raw editor callback
     * value is converted once onto the typed [QuickSettingEditorValue] per
     * the descriptor's editor shape, then dispatched through the catalog's
     * pure write transform onto the one CameraSettingsEditor path
     * (apply-then-persist).
     */
    fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
        updateSettings { current -> QuickSettingCatalog.descriptorFor(type).write(current, editorValue) }
    }

    fun togglePreview() {
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(!showPreview.value)
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        viewModelScope.launch {
            settingsEditor.edit(transform)
        }
    }

    private val _lastServerError = MutableStateFlow<String?>(null)

    /** The server-start failure line the server status panel renders — written
     *  only by the stream outcome mapping below (no ladder sets it directly);
     *  cleared by the next successful start — retrying is the natural dismiss. */
    val lastServerError: StateFlow<String?> = _lastServerError.asStateFlow()

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

    fun toggleWebStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleWeb()) }
    }

    fun toggleRtspStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleRtsp()) }
    }

    fun toggleServer() {
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

    fun copyStreamUrl() {
        copyUrlToClipboard(
            url = _streamStatus.value.url,
            fallback = streamingManager.streamUrl.value,
            clipLabel = "Stream URL",
            copiedMessage = "Stream URL copied",
        )
    }

    fun copyRtspUrl() {
        copyUrlToClipboard(
            url = _streamStatus.value.rtspUrl,
            fallback = streamingManager.rtspUrl.value,
            clipLabel = "RTSP URL",
            copiedMessage = "RTSP URL copied",
        )
    }

    fun getConnectInfo(): com.raulshma.lenscast.streaming.StreamingManager.ConnectInfo =
        streamingManager.getConnectInfo()

    fun copyHlsUrl() {
        val info = streamingManager.getConnectInfo()
        copyUrlToClipboard(
            url = info.hlsUrl,
            fallback = info.hlsUrl,
            clipLabel = "HLS URL",
            copiedMessage = "HLS URL copied",
        )
    }

    fun capturePhoto() {
        val fileName = photoCaptureManager.captureToGallery(
            onError = { exception -> Log.e(TAG, "Capture failed", exception) },
        )
        if (fileName == null) {
            Log.w(TAG, "capturePhoto: camera use case unavailable")
        }
    }

    /**
     * The record button: the stop-vs-start verdict and the start config are
     * [RecordingToggle]'s; this ViewModel only executes the decision — the
     * default config carries the audio setting, and the mic gate rides the
     * toggle's pre-start hook, so a stop never refreshes permissions or
     * warns.
     */
    fun toggleRecording() {
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraViewModel(
                context, cameraService, streamingManager, thermalMonitor,
                settingsDataStore, streamingSession, streamWatchdog, connectivityMonitor,
                recordingController, photoCaptureManager
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
}
