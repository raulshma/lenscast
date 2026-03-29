package com.raulshma.lenscast.capture

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CaptureViewModel(
    private val context: Context,
    private val cameraService: CameraService,
    private val captureHistoryStore: CaptureHistoryStore,
) : ViewModel() {

    private val _captureHistory = MutableStateFlow<List<CaptureHistory>>(emptyList())
    val captureHistory: StateFlow<List<CaptureHistory>> = _captureHistory.asStateFlow()

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
        captureHistoryStore.refreshFromMediaStore()
        viewModelScope.launch {
            captureHistoryStore.history.collect { history ->
                _captureHistory.value = history
            }
        }
    }

    fun capturePhoto() {
        val imageCapture = cameraService.getImageCapture() ?: return

        val fileName = "IMG_${DATE_FORMAT.format(Date())}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            captureWithMediaStore(imageCapture, fileName)
        } else {
            captureWithFile(imageCapture, fileName)
        }
    }

    private fun captureWithMediaStore(imageCapture: ImageCapture, fileName: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LensCast")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val entry = captureHistoryStore.createPhotoEntry(
                        fileName = fileName,
                        filePath = output.savedUri?.toString() ?: "",
                        fileSizeBytes = 0,
                    )
                    captureHistoryStore.add(entry)
                    Log.d(TAG, "Photo saved via MediaStore: $fileName")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                }
            },
        )
    }

    private fun captureWithFile(imageCapture: ImageCapture, fileName: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "LensCast"
        )
        if (!dir.exists()) dir.mkdirs()
        val outputFile = File(dir, fileName)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val entry = captureHistoryStore.createPhotoEntry(
                        fileName = fileName,
                        filePath = outputFile.absolutePath,
                        fileSizeBytes = outputFile.length(),
                    )
                    captureHistoryStore.add(entry)
                    Log.d(TAG, "Photo saved: ${outputFile.absolutePath}")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                }
            },
        )
    }

    fun startIntervalCapture(config: IntervalCaptureConfig) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<IntervalCaptureWorker>(
            config.intervalSeconds, TimeUnit.SECONDS
        )
            .setInputData(
                Data.Builder()
                    .putLong(IntervalCaptureWorker.KEY_INTERVAL_SECONDS, config.intervalSeconds)
                    .putInt(IntervalCaptureWorker.KEY_TOTAL_CAPTURES, config.totalCaptures)
                    .putInt(IntervalCaptureWorker.KEY_IMAGE_QUALITY, config.imageQuality)
                    .putInt(IntervalCaptureWorker.KEY_COMPLETED_CAPTURES, _captureCount.value)
                    .build()
            )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_INTERVAL,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        _isIntervalRunning.value = true
        _captureCount.value = 0
        Log.d(TAG, "Interval capture started: every ${config.intervalSeconds}s")
    }

    fun stopIntervalCapture() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_INTERVAL)
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

    fun clearHistory() {
        captureHistoryStore.clear()
    }

    fun deleteHistoryEntry(id: String) {
        captureHistoryStore.deleteMedia(id)
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return CaptureViewModel(context, cameraService, captureHistoryStore) as T
        }
    }

    companion object {
        private const val TAG = "CaptureViewModel"
        private const val WORK_NAME_INTERVAL = "interval_capture"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
