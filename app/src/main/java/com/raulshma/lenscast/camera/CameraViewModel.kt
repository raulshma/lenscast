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
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.core.BatteryOptimizationResult
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(
    private val context: Context,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val powerManager: PowerManager,
    private val thermalMonitor: ThermalMonitor,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _settings = MutableStateFlow(CameraSettings())
    val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    private val _streamStatus = MutableStateFlow(StreamStatus())
    val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(false)
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    private val _batteryInfo = MutableStateFlow<BatteryOptimizationResult?>(null)
    val batteryInfo: StateFlow<BatteryOptimizationResult?> = _batteryInfo.asStateFlow()

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _wifiConnected = MutableStateFlow(true)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private var currentPreviewView: PreviewView? = null
    private var streamMonitorJob: Job? = null
    private var batteryMonitorJob: Job? = null
    private var thermalMonitorJob: Job? = null
    private var settingsJob: Job? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    init {
        checkPermission()
        viewModelScope.launch {
            cameraService.cameraState.collect { state ->
                _cameraState.value = state
            }
        }
        viewModelScope.launch {
            cameraService.isFrontCamera.collect { isFront ->
                _isFrontCamera.value = isFront
            }
        }
        settingsJob = viewModelScope.launch {
            settingsDataStore.settings.collect { saved ->
                _settings.value = saved
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
    }

    fun checkPermission() {
        _hasCameraPermission.value = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (_hasCameraPermission.value) {
            _cameraState.value = CameraState.Idle
            initializeCamera()
        } else {
            _cameraState.value = CameraState.RequestPermission
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _hasCameraPermission.value = granted
        if (granted) {
            initializeCamera()
        } else {
            _cameraState.value = CameraState.RequestPermission
        }
    }

    private fun initializeCamera() {
        viewModelScope.launch {
            _cameraState.value = CameraState.Idle
            val result = cameraService.initialize()
            if (result.isFailure) {
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

    fun startPreview(previewView: PreviewView) {
        currentPreviewView = previewView
        cameraService.setFrameListener { bitmap ->
            streamingManager.pushFrame(bitmap)
        }
        cameraService.startPreview(previewView)
    }

    fun switchCamera() {
        val pv = currentPreviewView ?: return
        cameraService.switchCamera(pv)
    }

    fun updateSettings(settings: CameraSettings) {
        _settings.value = settings
        viewModelScope.launch {
            cameraService.applySettings(settings)
        }
    }

    fun toggleStreaming() {
        if (_streamStatus.value.isActive) stopStreaming() else startStreaming()
    }

    private fun startStreaming() {
        _wifiConnected.value = com.raulshma.lenscast.core.NetworkUtils.isWifiConnected(context)
        powerManager.refreshBatteryState()
        powerManager.acquireWakeLock()
        thermalMonitor.startMonitoring()
        streamingManager.thermalMonitor = thermalMonitor
        startBatteryMonitoring()
        startThermalMonitoring()
        val success = streamingManager.startStreaming()
        if (success) {
            _streamStatus.value = StreamStatus(
                isActive = true,
                url = streamingManager.streamUrl.value,
                clientCount = 0
            )
            startStreamMonitor()
        } else {
            _streamStatus.value = StreamStatus(isActive = false, url = "Failed to start server")
        }
    }

    private fun stopStreaming() {
        streamingManager.stopStreaming()
        powerManager.releaseWakeLock()
        thermalMonitor.stopMonitoring()
        streamMonitorJob?.cancel()
        streamMonitorJob = null
        batteryMonitorJob?.cancel()
        batteryMonitorJob = null
        thermalMonitorJob?.cancel()
        thermalMonitorJob = null
        _streamStatus.value = StreamStatus()
    }

    private fun startStreamMonitor() {
        streamMonitorJob?.cancel()
        streamMonitorJob = viewModelScope.launch(Dispatchers.Main) {
            streamingManager.clientCount.collect { count ->
                if (_streamStatus.value.isActive) {
                    _streamStatus.value = _streamStatus.value.copy(clientCount = count)
                }
            }
        }
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

    fun copyStreamUrl() {
        val url = _streamStatus.value.url
        if (url.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
            Toast.makeText(context, "Stream URL copied", Toast.LENGTH_SHORT).show()
        }
    }

    fun capturePhoto() {
        val imageCapture = cameraService.getImageCapture() ?: return
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val fileName = "IMG_${dateFormat.format(java.util.Date())}.jpg"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/LensCast")
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val app = context.applicationContext as MainApplication
                        val entry = app.captureHistoryStore.createPhotoEntry(
                            fileName = fileName,
                            filePath = output.savedUri?.toString() ?: "",
                            fileSizeBytes = 0,
                        )
                        app.captureHistoryStore.add(entry)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Capture failed", exception)
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ), "LensCast"
            )
            if (!dir.exists()) dir.mkdirs()
            val outputFile = java.io.File(dir, fileName)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val app = context.applicationContext as MainApplication
                        val entry = app.captureHistoryStore.createPhotoEntry(
                            fileName = fileName,
                            filePath = outputFile.absolutePath,
                            fileSizeBytes = outputFile.length(),
                        )
                        app.captureHistoryStore.add(entry)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Capture failed", exception)
                    }
                },
            )
        }
    }

    fun toggleRecording() {
        val intent = Intent(context, com.raulshma.lenscast.capture.RecordingService::class.java)
        if (_isRecording.value) {
            intent.action = com.raulshma.lenscast.capture.RecordingService.ACTION_STOP
            context.startService(intent)
            _isRecording.value = false
        } else {
            intent.action = com.raulshma.lenscast.capture.RecordingService.ACTION_START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isRecording.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamMonitorJob?.cancel()
        batteryMonitorJob?.cancel()
        thermalMonitorJob?.cancel()
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
}