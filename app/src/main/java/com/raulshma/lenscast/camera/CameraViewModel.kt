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
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditorValue
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.StreamStatusSnapshot
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.MicStartDecision
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
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
                if (state != CameraState.Idle) {
                    _cameraState.value = state
                }
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
    private val maxRetries = 3

    fun retryCameraInit() {
        if (retryCount < maxRetries) {
            retryCount++
            initializeCamera()
        }
    }

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

    /** One-shot failure report for server start; StreamStatus itself is derived truth.
     *  Cleared by the next successful start — retrying is the natural dismiss. */
    val lastServerError: StateFlow<String?> = _lastServerError.asStateFlow()

    // One stream start/stop seam for both outputs and the server: the
    // gate → start → session begin → rollback ladder lives once in
    // StreamToggle; this ViewModel only maps outcomes onto toasts.
    private val streamToggle = StreamToggle(
        transports = object : StreamToggle.Transports {
            override val webEnabled: Boolean get() = streamingManager.isWebEnabled.value
            override val rtspEnabled: Boolean get() = streamingManager.isRtspEnabled.value
            override val webActive: Boolean get() = _streamStatus.value.isWebActive
            override val rtspActive: Boolean get() = _streamStatus.value.isRtspActive
            override fun startWeb(): Boolean = streamingManager.startWebStreaming()
            override fun stopWeb() = streamingManager.stopWebStreaming()
            override fun startRtsp(): Boolean = streamingManager.startRtspStreaming()
            override fun stopRtsp() = streamingManager.stopRtspStreaming()
            override fun stopServer() = streamingManager.stopStreaming()
            override suspend fun beginSession() = streamingSession.begin()
            override suspend fun endSession() = streamingSession.end()
        },
        // The pre-start mic warn-and-degrade check — web only, exactly as
        // before the seam existed.
        onBeforeStart = { kind -> if (kind == StreamKind.WEB) warnIfMicUnavailable() },
    )

    fun toggleWebStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleWeb()) }
    }

    fun toggleRtspStreaming() {
        viewModelScope.launch { handleStreamOutcome(streamToggle.toggleRtsp()) }
    }

    fun toggleServer() {
        if (_streamStatus.value.isServerRunning) {
            // Stopped outcome needs no user-facing report — the derived
            // StreamStatus flips on its own.
            viewModelScope.launch { streamToggle.stopServer() }
        } else {
            startServer()
        }
    }

    private fun startServer() {
        // StreamStatus is derived truth from the StreamingManager flows — the
        // combine collector re-emits it when the server state flips. Only the
        // failure, which no flow carries, is reported separately.
        if (!streamingManager.ensureServerRunning()) {
            _lastServerError.value = "Failed to start server"
        } else {
            _lastServerError.value = null
        }
    }

    // Mic warn-and-degrade before a web start: the same shared policy the
    // recording start consults.
    private fun warnIfMicUnavailable() {
        refreshAudioPermission()
        when (val decision = MicAccess.startDecision(
            featureEnabled = streamAudioEnabled.value,
            granted = _hasAudioPermission.value,
            featureLabel = "Streaming video",
        )) {
            is MicStartDecision.Degrade ->
                Toast.makeText(context, decision.warning, Toast.LENGTH_SHORT).show()
            MicStartDecision.Proceed -> {}
        }
    }

    private fun handleStreamOutcome(outcome: StreamStartOutcome) {
        when (outcome) {
            StreamStartOutcome.Started, StreamStartOutcome.Stopped -> {}
            is StreamStartOutcome.Disabled ->
                Toast.makeText(
                    context, StreamStartOutcome.disabledMessage(outcome.kind), Toast.LENGTH_SHORT
                ).show()
            is StreamStartOutcome.StartFailed ->
                Toast.makeText(
                    context, StreamStartOutcome.startFailedMessage(outcome.kind), Toast.LENGTH_SHORT
                ).show()
            is StreamStartOutcome.BeginFailedRolledBack -> {
                Log.e(TAG, "Streaming session setup failed; stream rolled back", outcome.cause)
                Toast.makeText(
                    context, StreamStartOutcome.startFailedMessage(outcome.kind), Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun copyStreamUrl() {
        val url = _streamStatus.value.url.ifEmpty { streamingManager.streamUrl.value }
        if (url.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
            Toast.makeText(context, "Stream URL copied", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyRtspUrl() {
        val url = _streamStatus.value.rtspUrl.ifEmpty { streamingManager.rtspUrl.value }
        if (url.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RTSP URL", url))
            Toast.makeText(context, "RTSP URL copied", Toast.LENGTH_SHORT).show()
        }
    }

    fun capturePhoto() {
        val fileName = photoCaptureManager.captureToGallery(
            onError = { exception -> Log.e(TAG, "Capture failed", exception) },
        )
        if (fileName == null) {
            Log.w(TAG, "capturePhoto: camera use case unavailable")
        }
    }

    fun toggleRecording() {
        val current = recordingState.value
        if (current is com.raulshma.lenscast.capture.RecordingState.Recording ||
            current is com.raulshma.lenscast.capture.RecordingState.Scheduled
        ) {
            recordingController.stop()
        } else {
            refreshAudioPermission()
            when (val decision = MicAccess.startDecision(
                featureEnabled = recordingAudioEnabled.value,
                granted = _hasAudioPermission.value,
                featureLabel = "Recording video",
            )) {
                is MicStartDecision.Degrade ->
                    Toast.makeText(context, decision.warning, Toast.LENGTH_SHORT).show()
                MicStartDecision.Proceed -> {}
            }
            recordingController.start(
                com.raulshma.lenscast.capture.model.RecordingConfig(
                    includeAudio = recordingAudioEnabled.value
                )
            )
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
