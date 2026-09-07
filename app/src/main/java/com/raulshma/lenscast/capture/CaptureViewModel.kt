package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Write-side surface for capture features. Recording start/stop/schedule goes
 * through the app-scoped RecordingController — this ViewModel holds only the
 * screen's input state (config editors) and display tickers.
 */
class CaptureViewModel(
    context: Context,
    private val captureHistoryStore: CaptureHistoryStore,
    private val settingsDataStore: SettingsDataStore,
    private val recordingController: RecordingController,
    private val photoCaptureManager: PhotoCaptureManager,
) : ViewModel() {
    private val context: Context = context.applicationContext

    private val _intervalConfig = MutableStateFlow(IntervalCaptureConfig())
    val intervalConfig: StateFlow<IntervalCaptureConfig> = _intervalConfig.asStateFlow()

    private val _isIntervalRunning = MutableStateFlow(false)
    val isIntervalRunning: StateFlow<Boolean> = _isIntervalRunning.asStateFlow()

    val recordingState: StateFlow<RecordingState> = recordingController.state

    val isRecording: StateFlow<Boolean> = recordingController.isRecording

    private val _captureCount = MutableStateFlow(0)
    val captureCount: StateFlow<Int> = _captureCount.asStateFlow()

    private val _recordingConfig = MutableStateFlow(RecordingConfig())
    val recordingConfig: StateFlow<RecordingConfig> = _recordingConfig.asStateFlow()

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    // The scheduled-start time is this screen's input field; once a start is
    // handed to the controller, its Scheduled state is the truth.
    private val _scheduledStartTime = MutableStateFlow<Long?>(null)
    val scheduledStartTime: StateFlow<Long?> = _scheduledStartTime.asStateFlow()

    init {
        viewModelScope.launch {
            _recordingConfig.value = _recordingConfig.value.copy(
                includeAudio = settingsDataStore.recordingAudioEnabled.first()
            )
        }
        // Interval-capture truth lives in WorkManager; observe it instead of
        // keeping optimistic copies.
        viewModelScope.launch {
            IntervalCaptureScheduler.observeStatus(context).collect { snapshot ->
                _isIntervalRunning.value = snapshot.isRunning
                _captureCount.value = snapshot.completedCaptures
            }
        }
        // Elapsed-recording ticker: derived from the controller's state so the
        // clock can't drift from the service's actual start time.
        viewModelScope.launch {
            recordingState.collectLatest { state ->
                if (state is RecordingState.Recording) {
                    while (isActive) {
                        _recordingElapsedMs.value = System.currentTimeMillis() - state.startedAtMs
                        delay(500)
                    }
                } else {
                    _recordingElapsedMs.value = 0L
                }
            }
        }
    }

    fun capturePhoto() {
        val fileName = photoCaptureManager.captureToGallery(
            onSaved = { filePath, _ -> Log.d(TAG, "Photo saved: $filePath") },
            onError = { exception -> Log.e(TAG, "Photo capture failed", exception) },
        )
        if (fileName == null) {
            Log.w(TAG, "capturePhoto: camera use case unavailable")
        }
    }

    fun startIntervalCapture(config: IntervalCaptureConfig) {
        IntervalCaptureScheduler.start(
            context = context,
            intervalSeconds = config.intervalSeconds,
            totalCaptures = config.totalCaptures,
            flashMode = config.flashMode.name,
            completedCaptures = 0,
        )
        Log.d(TAG, "Interval capture started: every ${config.intervalSeconds}s")
    }

    fun stopIntervalCapture() {
        IntervalCaptureScheduler.stop(context)
        Log.d(TAG, "Interval capture stopped")
    }

    fun updateIntervalConfig(config: IntervalCaptureConfig) {
        _intervalConfig.value = config
    }

    fun updateRecordingConfig(config: RecordingConfig) {
        _recordingConfig.value = config
    }

    fun toggleRecording() {
        if (recordingState.value is RecordingState.Recording ||
            recordingState.value is RecordingState.Scheduled
        ) {
            stopRecording()
        } else {
            startRecordingWithConfig(_recordingConfig.value)
        }
    }

    fun startScheduledRecording() {
        // Goes through the same path as an immediate start so the
        // max-duration/repeat policy is armed for scheduled recordings too —
        // the policy waits out the Scheduled phase itself.
        startRecordingWithConfig(_recordingConfig.value, _scheduledStartTime.value)
    }

    fun cancelScheduledRecording() {
        recordingController.cancelSchedule()
        _scheduledStartTime.value = null
    }

    fun updateScheduledStartTime(time: Long?) {
        _scheduledStartTime.value = time
    }

    private fun startRecordingWithConfig(config: RecordingConfig, startAtMs: Long? = null) {
        if (config.includeAudio && !MicAccess.isGranted(context)) {
            Toast.makeText(
                context,
                MicAccess.degradedMessage("Recording video"),
                Toast.LENGTH_SHORT
            ).show()
        }
        // The max-duration auto-stop and repeat policy is armed by the
        // RecordingController itself, so it holds no matter which client
        // started the recording and survives navigating away from this screen.
        recordingController.start(config, startAtMs)
    }

    private fun stopRecording() {
        recordingController.stop()
    }

    class Factory(
        private val context: Context,
        private val captureHistoryStore: CaptureHistoryStore,
        private val settingsDataStore: SettingsDataStore,
        private val recordingController: RecordingController,
        private val photoCaptureManager: PhotoCaptureManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return CaptureViewModel(
                context,
                captureHistoryStore,
                settingsDataStore,
                recordingController,
                photoCaptureManager
            ) as T
        }
    }

    companion object {
        private const val TAG = "CaptureViewModel"
    }
}
