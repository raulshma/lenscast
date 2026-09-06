package com.raulshma.lenscast.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.PhotoCaptureHelper
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {
    private val context: Context = context.applicationContext
    private val app: MainApplication
        get() = context as MainApplication

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraSettings())

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

    private var currentPreviewView: PreviewView? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingElapsedSeconds = MutableStateFlow(0)
    val recordingElapsedSeconds: StateFlow<Int> = _recordingElapsedSeconds.asStateFlow()
    private var recordingStartTimeMs: Long = 0L
    private var recordingTimerJob: Job? = null

    val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

        // Combined: All streaming status updates using nested combines (Kotlin combine supports max 5 flows)
        viewModelScope.launch {
            val videoFlow = combine(
                streamingManager.isStreaming,
                streamingManager.isWebStreamingActive,
                streamingManager.isServerRunning,
                streamingManager.streamUrl,
                streamingManager.clientCount,
            ) { isStreaming, isWebActive, isServerRunning, streamUrl, clientCount ->
                listOf(isStreaming, isWebActive, isServerRunning, streamUrl, clientCount)
            }

            val audioFlow = combine(
                streamingManager.isAudioStreaming,
                streamingManager.audioStreamUrl,
                streamingManager.isRtspRunning,
                streamingManager.rtspUrl,
            ) { isAudioStreaming, audioUrl, isRtspRunning, rtspUrl ->
                listOf(isAudioStreaming, audioUrl, isRtspRunning, rtspUrl)
            }

            combine(
                videoFlow,
                audioFlow,
                streamingManager.isWebEnabled,
                streamingManager.isRtspEnabled,
            ) { video, audio, isWebEnabled, isRtspEnabled ->
                _streamStatus.value = StreamStatus(
                    isActive = video[0] as Boolean,
                    isWebActive = video[1] as Boolean,
                    isServerRunning = video[2] as Boolean,
                    url = video[3] as String,
                    clientCount = video[4] as Int,
                    isAudioActive = audio[0] as Boolean,
                    audioUrl = audio[1] as String,
                    isRtspActive = audio[2] as Boolean,
                    rtspUrl = audio[3] as String,
                    isWebEnabled = isWebEnabled,
                    isRtspEnabled = isRtspEnabled,
                )
            }.collect { }
        }

        cameraService.setFrameListener { yuvData, width, height, rotation ->
            streamingManager.pushFrame(yuvData, width, height, rotation)
            streamingManager.pushFrameToRtsp(yuvData, width, height, rotation)
        }

        // Optimized: Connection quality polling with early cancellation
        viewModelScope.launch {
            streamingManager.isStreaming.collect { isActive ->
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
        currentPreviewView = previewView
        cameraService.setLifecycleOwner(lifecycleOwner)
        cameraService.startPreview(previewView)
        viewModelScope.launch {
            cameraService.applySettings(settings.value)
        }
    }

    fun stopPreview() {
        currentPreviewView = null
        cameraService.stopPreview()
    }

    fun switchCamera() {
        val pv = currentPreviewView ?: return
        cameraService.switchCamera(pv)
    }

    fun selectLens(index: Int) {
        cameraService.selectLens(index)
    }

    fun updateExposure(value: Int) {
        updateSettings { it.copy(exposureCompensation = value) }
    }

    fun updateIso(value: String) {
        val iso = if (value == "Auto") null else value.toIntOrNull()
        updateSettings { it.copy(iso = iso) }
    }

    fun updateFocusMode(mode: String) {
        updateSettings { it.copy(focusMode = FocusMode.valueOf(mode)) }
    }

    fun updateWhiteBalance(mode: String) {
        updateSettings { it.copy(whiteBalance = WhiteBalance.valueOf(mode)) }
    }

    fun updateZoom(ratio: Float) {
        updateSettings { it.copy(zoomRatio = ratio) }
    }

    fun updateHdrMode(mode: String) {
        updateSettings { it.copy(hdrMode = HdrMode.valueOf(mode)) }
    }

    fun updateFrameRate(rate: Int) {
        updateSettings { it.copy(frameRate = rate) }
    }

    fun updateResolution(name: String) {
        updateSettings { it.copy(resolution = Resolution.valueOf(name)) }
    }

    fun updateStabilization(enabled: Boolean) {
        updateSettings { it.copy(stabilization = enabled) }
    }

    fun updateNightVisionMode(mode: String) {
        updateSettings { it.copy(nightVisionMode = NightVisionMode.valueOf(mode)) }
    }

    fun togglePreview() {
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(!showPreview.value)
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val newSettings = transform(settings.value)
        // Gesture-driven camera controls apply immediately for responsiveness;
        // SettingsApplier re-applies after persistence — applying camera controls
        // is idempotent, so the overlap is harmless.
        viewModelScope.launch {
            cameraService.applySettings(newSettings)
            settingsDataStore.saveSettings(newSettings)
        }
    }

    fun toggleStreaming() {
        toggleWebStreaming()
    }

    fun toggleWebStreaming() {
        if (_streamStatus.value.isWebActive) stopWebStreaming() else startWebStreaming()
    }

    fun toggleRtspStreaming() {
        if (_streamStatus.value.isRtspActive) stopRtspStreaming() else startRtspStreaming()
    }

    private fun startWebStreaming() {
        if (!streamingManager.isWebEnabled.value) {
            Toast.makeText(context, "Web streaming is disabled in settings.", Toast.LENGTH_SHORT).show()
            return
        }

        refreshAudioPermission()
        if (streamAudioEnabled.value && !_hasAudioPermission.value) {
            Toast.makeText(
                context,
                "Microphone permission not granted. Streaming video without audio.",
                Toast.LENGTH_SHORT
            ).show()
        }

        val success = streamingManager.startWebStreaming()
        if (success) {
            beginSession()
        } else {
            Toast.makeText(context, "Failed to start web streaming.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopWebStreaming() {
        streamingManager.stopWebStreaming()
        endSession()
    }

    private fun startRtspStreaming() {
        if (!streamingManager.isRtspEnabled.value) {
            Toast.makeText(context, "RTSP streaming is disabled in settings.", Toast.LENGTH_SHORT).show()
            return
        }

        val success = streamingManager.startRtspStreaming()
        if (success) {
            beginSession()
        } else {
            Toast.makeText(context, "Failed to start RTSP streaming.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRtspStreaming() {
        streamingManager.stopRtspStreaming()
        endSession()
    }

    // Session begin/end is owned by StreamingSession; failures here (e.g. a
    // rejected foreground-service start) must not crash the app.
    private fun beginSession() {
        viewModelScope.launch {
            runCatching { streamingSession.begin() }
                .onFailure { Log.e(TAG, "Streaming session setup failed", it) }
        }
    }

    private fun endSession() {
        viewModelScope.launch {
            streamingSession.end()
        }
    }

    fun toggleServer() {
        if (_streamStatus.value.isServerRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val started = streamingManager.ensureServerRunning()
        if (started) {
            _streamStatus.value = _streamStatus.value.copy(
                isServerRunning = true,
                url = streamingManager.streamUrl.value,
            )
        } else {
            _streamStatus.value = _streamStatus.value.copy(
                isServerRunning = false,
                url = "Failed to start server",
            )
        }
    }

    private fun stopServer() {
        streamingManager.stopStreaming()
        endSession()
        _streamStatus.value = _streamStatus.value.copy(
            isActive = false,
            isServerRunning = false,
            url = "",
            clientCount = 0,
            isAudioActive = false,
            audioUrl = "",
        )
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
        val imageCapture = cameraService.acquirePhotoCapture() ?: return
        val fileName = PhotoCaptureHelper.generateFileName()
        PhotoCaptureHelper.takePhoto(
            context, imageCapture, fileName,
            onSaved = { filePath, fileSizeBytes ->
                val entry = app.captureHistoryStore.createPhotoEntry(
                    fileName = fileName,
                    filePath = filePath,
                    fileSizeBytes = fileSizeBytes,
                )
                app.captureHistoryStore.add(entry)
                cameraService.releasePhotoCapture()
            },
            onError = { exception ->
                Log.e(TAG, "Capture failed", exception)
                cameraService.releasePhotoCapture()
            },
        )
    }

    fun toggleRecording() {
        val intent = Intent(context, com.raulshma.lenscast.capture.RecordingService::class.java)
        if (_isRecording.value) {
            intent.action = com.raulshma.lenscast.capture.RecordingService.ACTION_STOP
            context.startService(intent)
            _isRecording.value = false
            recordingTimerJob?.cancel()
            recordingTimerJob = null
            _recordingElapsedSeconds.value = 0
        } else {
            refreshAudioPermission()
            if (recordingAudioEnabled.value && !_hasAudioPermission.value) {
                Toast.makeText(
                    context,
                    "Microphone permission not granted. Recording video without audio.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            intent.action = com.raulshma.lenscast.capture.RecordingService.ACTION_START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isRecording.value = true
            recordingStartTimeMs = System.currentTimeMillis()
            _recordingElapsedSeconds.value = 0
            recordingTimerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _recordingElapsedSeconds.value =
                        ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt()
                }
            }
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraViewModel(
                context, cameraService, streamingManager, thermalMonitor,
                settingsDataStore, streamingSession, streamWatchdog, connectivityMonitor
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
        _hasAudioPermission.value = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
