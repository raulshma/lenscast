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
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.core.BatteryOptimizationResult
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(
    context: Context,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val powerManager: PowerManager,
    private val thermalMonitor: ThermalMonitor,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val context: Context = context.applicationContext
    private val app: MainApplication
        get() = context as MainApplication

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _streamStatus = MutableStateFlow(StreamStatus())
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(false)
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private val _batteryInfo = MutableStateFlow<BatteryOptimizationResult?>(null)
    val batteryInfo: StateFlow<BatteryOptimizationResult?> = _batteryInfo.asStateFlow()

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _wifiConnected = MutableStateFlow(true)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    val availableLenses: StateFlow<List<CameraLensInfo>> = cameraService.availableLenses
    val selectedLensIndex: StateFlow<Int> = cameraService.selectedLensIndex

    private var currentPreviewView: PreviewView? = null
    private var batteryMonitorJob: Job? = null
    private var thermalMonitorJob: Job? = null
    private var settingsJob: Job? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingElapsedSeconds = MutableStateFlow(0)
    val recordingElapsedSeconds: StateFlow<Int> = _recordingElapsedSeconds.asStateFlow()
    private var recordingStartTimeMs: Long = 0L
    private var recordingTimerJob: Job? = null

    private val _showPreview = MutableStateFlow(true)
    val showPreview: StateFlow<Boolean> = _showPreview.asStateFlow()

    private val _adaptiveBitrateState = MutableStateFlow(AdaptiveBitrateController.AdaptiveState(
        enabled = false,
        qualityLevel = com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel.GOOD,
        currentQuality = 70,
        targetQuality = 70,
        currentFps = 24,
        targetFps = 24,
        estimatedBandwidthKbps = 5000,
        minClientThroughputKbps = 5000,
        activeClients = 0,
    ))
    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> = _adaptiveBitrateState.asStateFlow()

    private val _streamAudioEnabled = MutableStateFlow(true)
    private val _recordingAudioEnabled = MutableStateFlow(true)

    init {
        // Collect from service but ONLY update if the service is not Idle, 
        // to prevent overwriting our local RequestPermission state!
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
        viewModelScope.launch {
            streamingManager.isStreaming.collect { isActive ->
                _streamStatus.value = _streamStatus.value.copy(isActive = isActive)
            }
        }
        viewModelScope.launch {
            streamingManager.isServerRunning.collect { isRunning ->
                _streamStatus.value = _streamStatus.value.copy(isServerRunning = isRunning)
            }
        }
        viewModelScope.launch {
            streamingManager.streamUrl.collect { url ->
                _streamStatus.value = _streamStatus.value.copy(url = url)
            }
        }
        viewModelScope.launch {
            streamingManager.clientCount.collect { count ->
                _streamStatus.value = _streamStatus.value.copy(clientCount = count)
            }
        }
        viewModelScope.launch {
            streamingManager.isAudioStreaming.collect { isAudioStreaming ->
                _streamStatus.value = _streamStatus.value.copy(isAudioActive = isAudioStreaming)
            }
        }
        viewModelScope.launch {
            streamingManager.audioStreamUrl.collect { audioUrl ->
                _streamStatus.value = _streamStatus.value.copy(audioUrl = audioUrl)
            }
        }
        viewModelScope.launch {
            streamingManager.isRtspRunning.collect { isRtsp ->
                _streamStatus.value = _streamStatus.value.copy(isRtspActive = isRtsp)
            }
        }
        viewModelScope.launch {
            streamingManager.rtspUrl.collect { rtspUrl ->
                _streamStatus.value = _streamStatus.value.copy(rtspUrl = rtspUrl)
            }
        }
        viewModelScope.launch {
            streamingManager.isWebEnabled.collect { enabled ->
                _streamStatus.value = _streamStatus.value.copy(isWebEnabled = enabled)
            }
        }
        viewModelScope.launch {
            streamingManager.isRtspEnabled.collect { enabled ->
                _streamStatus.value = _streamStatus.value.copy(isRtspEnabled = enabled)
            }
        }
        cameraService.setFrameListener { yuvData, width, height, rotation ->
            streamingManager.pushFrame(yuvData, width, height, rotation)
            streamingManager.pushFrameToRtsp(yuvData, width, height, rotation)
        }
        settingsJob = viewModelScope.launch {
            settingsDataStore.settings.collect { saved ->
                _settings.value = saved
                cameraService.applySettings(saved)
            }
        }
        viewModelScope.launch {
            settingsDataStore.streamingPort.collect { port ->
                streamingManager.setPort(port)
            }
        }
        viewModelScope.launch {
            settingsDataStore.jpegQuality.collect { quality ->
                streamingManager.setJpegQuality(quality)
            }
        }
        viewModelScope.launch {
            settingsDataStore.showPreview.collect { show ->
                _showPreview.value = show
            }
        }
        viewModelScope.launch {
            settingsDataStore.streamAudioEnabled.collect { enabled ->
                _streamAudioEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsDataStore.recordingAudioEnabled.collect { enabled ->
                _recordingAudioEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            streamingManager.adaptiveBitrateState.collect { state ->
                _adaptiveBitrateState.value = state
            }
        }

        // Run checkPermission AFTER collectors are set up
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
        if (granted && _streamStatus.value.isActive && _streamAudioEnabled.value) {
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
            cameraService.applySettings(_settings.value)
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

    fun updateSettings(settings: CameraSettings) {
        _settings.value = settings
        viewModelScope.launch {
            cameraService.applySettings(settings)
        }
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

    fun togglePreview() {
        _showPreview.value = !_showPreview.value
        viewModelScope.launch {
            settingsDataStore.saveShowPreview(_showPreview.value)
        }
    }

    private fun updateSettings(transform: (CameraSettings) -> CameraSettings) {
        val newSettings = transform(_settings.value)
        _settings.value = newSettings
        viewModelScope.launch {
            cameraService.applySettings(newSettings)
            settingsDataStore.saveSettings(newSettings)
        }
    }

    fun toggleStreaming() {
        if (_streamStatus.value.isActive) stopStreaming() else startStreaming()
    }

    private fun startStreaming() {
        refreshAudioPermission()
        if (_streamAudioEnabled.value && !_hasAudioPermission.value) {
            Toast.makeText(
                context,
                "Microphone permission not granted. Streaming video without audio.",
                Toast.LENGTH_SHORT
            ).show()
        }
        _wifiConnected.value = com.raulshma.lenscast.core.NetworkUtils.isWifiConnected(context)
        powerManager.refreshBatteryState()
        powerManager.acquireWakeLock()
        thermalMonitor.startMonitoring()
        streamingManager.thermalMonitor = thermalMonitor
        startBatteryMonitoring()
        startThermalMonitoring()
        if (!streamingManager.isWebEnabled.value && !streamingManager.isRtspEnabled.value) {
            streamingManager.setWebStreamingEnabled(true)
            viewModelScope.launch {
                settingsDataStore.saveWebStreamingEnabled(true)
            }
        }
        val success = streamingManager.startStreaming()
        if (success) {
            cameraService.acquireKeepAlive()
            cameraService.rebindUseCases()

            val intent = Intent(context, com.raulshma.lenscast.streaming.StreamingService::class.java)
            intent.action = com.raulshma.lenscast.streaming.StreamingService.ACTION_START
            intent.putExtra(
                com.raulshma.lenscast.streaming.StreamingService.EXTRA_URL,
                streamingManager.streamUrl.value
            )
            intent.putExtra(
                com.raulshma.lenscast.streaming.StreamingService.EXTRA_AUDIO_ACTIVE,
                streamingManager.isAudioStreaming.value
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            _streamStatus.value = StreamStatus(
                isActive = true,
                isServerRunning = streamingManager.isServerRunning.value,
                url = streamingManager.streamUrl.value,
                clientCount = 0,
                isAudioActive = streamingManager.isAudioStreaming.value,
                audioUrl = streamingManager.audioStreamUrl.value,
            )
        } else {
            _streamStatus.value = _streamStatus.value.copy(
                isActive = false,
                isServerRunning = false,
                url = "Failed to start server",
                clientCount = 0,
                isAudioActive = false,
                audioUrl = "",
            )
        }
    }

    private fun stopStreaming() {
        streamingManager.pauseStreaming()
        powerManager.releaseWakeLock()
        thermalMonitor.stopMonitoring()
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
        thermalMonitorJob?.cancel()
        thermalMonitorJob = null
        _streamStatus.value = _streamStatus.value.copy(
            isActive = false,
            isServerRunning = streamingManager.isServerRunning.value,
            url = streamingManager.streamUrl.value,
            clientCount = 0,
            isAudioActive = false,
            audioUrl = "",
        )

        cameraService.releaseKeepAlive()
        cameraService.rebindUseCases()

        val intent = Intent(context, com.raulshma.lenscast.streaming.StreamingService::class.java)
        intent.action = com.raulshma.lenscast.streaming.StreamingService.ACTION_PAUSE
        intent.putExtra(
            com.raulshma.lenscast.streaming.StreamingService.EXTRA_URL,
            streamingManager.streamUrl.value
        )
        context.startService(intent)
    }

    private fun startBatteryMonitoring() {
        batteryMonitorJob?.cancel()
        batteryMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                powerManager.refreshBatteryState()
                val result = powerManager.optimizationResult.value
                _batteryInfo.value = result
                streamingManager.applyBatteryOptimization(result)
                delay(30_000)
            }
        }
    }

    private fun startThermalMonitoring() {
        thermalMonitorJob?.cancel()
        thermalMonitorJob = viewModelScope.launch {
            thermalMonitor.thermalState.collect { state ->
                _thermalState.value = state
            }
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
        if (_streamStatus.value.isActive) {
            streamingManager.stopStreaming()
            powerManager.releaseWakeLock()
            thermalMonitor.stopMonitoring()
            batteryMonitorJob?.cancel()
            batteryMonitorJob = null
            thermalMonitorJob?.cancel()
            thermalMonitorJob = null
            cameraService.releaseKeepAlive()
            cameraService.rebindUseCases()

            val intent = Intent(context, com.raulshma.lenscast.streaming.StreamingService::class.java)
            intent.action = com.raulshma.lenscast.streaming.StreamingService.ACTION_PAUSE
            context.startService(intent)
        } else {
            streamingManager.stopStreaming()
        }
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
            if (_recordingAudioEnabled.value && !_hasAudioPermission.value) {
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

    override fun onCleared() {
        super.onCleared()
        batteryMonitorJob?.cancel()
        thermalMonitorJob?.cancel()
        recordingTimerJob?.cancel()
        streamingManager.release()
        cameraService.release()
        powerManager.releaseWakeLock()
        thermalMonitor.stopMonitoring()
    }

    class Factory(
        private val context: Context,
        private val cameraService: CameraService,
        private val streamingManager: StreamingManager,
        private val powerManager: PowerManager,
        private val thermalMonitor: ThermalMonitor,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraViewModel(
                context, cameraService, streamingManager,
                powerManager, thermalMonitor, settingsDataStore
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
