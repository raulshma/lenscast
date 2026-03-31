package com.raulshma.lenscast.capture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.capture.PhotoCaptureHelper
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CaptureViewModel(
    context: Context,
    private val cameraService: CameraService,
    private val captureHistoryStore: CaptureHistoryStore,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val context: Context = context.applicationContext

    private val _intervalConfig = MutableStateFlow(IntervalCaptureConfig())
    val intervalConfig: StateFlow<IntervalCaptureConfig> = _intervalConfig.asStateFlow()

    private val _isIntervalRunning = MutableStateFlow(false)
    val isIntervalRunning: StateFlow<Boolean> = _isIntervalRunning.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _captureCount = MutableStateFlow(0)
    val captureCount: StateFlow<Int> = _captureCount.asStateFlow()

    private val _recordingConfig = MutableStateFlow(RecordingConfig())
    val recordingConfig: StateFlow<RecordingConfig> = _recordingConfig.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var recordingDurationMs: Long = 0
    private var _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private var scheduledStartJob: Job? = null
    private val _scheduledStartTime = MutableStateFlow<Long?>(null)
    val scheduledStartTime: StateFlow<Long?> = _scheduledStartTime.asStateFlow()

    private val moshi by lazy {
        com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
    }
    private val recordingConfigAdapter by lazy {
        moshi.adapter(com.raulshma.lenscast.capture.model.RecordingConfig::class.java)
    }

    init {
        viewModelScope.launch {
            _recordingConfig.value = _recordingConfig.value.copy(
                includeAudio = settingsDataStore.recordingAudioEnabled.first()
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = IntervalCaptureScheduler.getStatus(context)
            _isIntervalRunning.value = snapshot.isRunning
            _captureCount.value = snapshot.completedCaptures
        }
    }

    fun capturePhoto() {
        val imageCapture = cameraService.acquirePhotoCapture() ?: return
        val fileName = PhotoCaptureHelper.generateFileName()
        PhotoCaptureHelper.takePhoto(
            context, imageCapture, fileName,
            onSaved = { filePath, fileSizeBytes ->
                val entry = captureHistoryStore.createPhotoEntry(
                    fileName = fileName,
                    filePath = filePath,
                    fileSizeBytes = fileSizeBytes,
                )
                captureHistoryStore.add(entry)
                cameraService.releasePhotoCapture()
                Log.d(TAG, "Photo saved: $filePath")
            },
            onError = { exception ->
                cameraService.releasePhotoCapture()
                Log.e(TAG, "Photo capture failed", exception)
            },
        )
    }

    fun startIntervalCapture(config: IntervalCaptureConfig) {
        IntervalCaptureScheduler.start(
            context = context,
            intervalSeconds = config.intervalSeconds,
            totalCaptures = config.totalCaptures,
            imageQuality = config.imageQuality,
            flashMode = config.flashMode.name,
            completedCaptures = 0,
        )

        _isIntervalRunning.value = true
        _captureCount.value = 0
        Log.d(TAG, "Interval capture started: every ${config.intervalSeconds}s")
    }

    fun stopIntervalCapture() {
        IntervalCaptureScheduler.stop(context)
        _isIntervalRunning.value = false
        Log.d(TAG, "Interval capture stopped")
    }

    fun updateIntervalConfig(config: IntervalCaptureConfig) {
        _intervalConfig.value = config
    }

    fun updateRecordingConfig(config: RecordingConfig) {
        _recordingConfig.value = config
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun startScheduledRecording() {
        val config = _recordingConfig.value
        val startTime = _scheduledStartTime.value
        if (startTime != null && startTime > System.currentTimeMillis()) {
            scheduledStartJob?.cancel()
            scheduledStartJob = viewModelScope.launch(Dispatchers.Main) {
                val delayMs = startTime - System.currentTimeMillis()
                delay(delayMs)
                startRecordingWithConfig(config)
            }
        } else {
            startRecordingWithConfig(config)
        }
    }

    fun cancelScheduledRecording() {
        scheduledStartJob?.cancel()
        scheduledStartJob = null
        _scheduledStartTime.value = null
    }

    fun updateScheduledStartTime(time: Long?) {
        _scheduledStartTime.value = time
    }

    private fun startRecordingWithConfig(config: RecordingConfig) {
        if (config.includeAudio && !hasAudioPermission()) {
            Toast.makeText(
                context,
                "Microphone permission not granted. Recording video without audio.",
                Toast.LENGTH_SHORT
            ).show()
        }
        val configJson = recordingConfigAdapter.toJson(config)

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_CONFIG, configJson)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isRecording.value = true
        recordingDurationMs = 0
        _recordingElapsedMs.value = 0

        if (config.durationSeconds > 0) {
            startRecordingTimer(config.durationSeconds * 1000)
        }
    }

    private fun startRecording() {
        startRecordingWithConfig(_recordingConfig.value)
    }

    private fun startRecordingTimer(maxDurationMs: Long) {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                _recordingElapsedMs.value = elapsed
                if (elapsed >= maxDurationMs) {
                    stopRecording()
                    val config = _recordingConfig.value
                    if (config.repeatIntervalSeconds > 0) {
                        delay(config.repeatIntervalSeconds * 1000)
                        startRecordingWithConfig(config)
                    }
                    break
                }
                delay(500)
            }
        }
    }

    private fun stopRecording() {
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
        _isRecording.value = false
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    override fun onCleared() {
        super.onCleared()
        if (_isIntervalRunning.value) {
            stopIntervalCapture()
        }
        recordingTimerJob?.cancel()
        scheduledStartJob?.cancel()
    }

    class Factory(
        private val context: Context,
        private val cameraService: CameraService,
        private val captureHistoryStore: CaptureHistoryStore,
        private val settingsDataStore: SettingsDataStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return CaptureViewModel(
                context,
                cameraService,
                captureHistoryStore,
                settingsDataStore
            ) as T
        }
    }

    companion object {
        private const val TAG = "CaptureViewModel"
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
