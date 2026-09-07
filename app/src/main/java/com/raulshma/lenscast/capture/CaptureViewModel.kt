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
import com.raulshma.lenscast.core.MicStartDecision
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    // Interval-capture truth lives in WorkManager; derive directly from the
    // scheduler's cold flow — no manual mirrors to drift.
    private val intervalStatus: StateFlow<IntervalCaptureStatusSnapshot?> =
        IntervalCaptureScheduler.observeStatus(context)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    val isIntervalRunning: StateFlow<Boolean> = intervalStatus
        .map { it?.isRunning == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), false)

    val recordingState: StateFlow<RecordingState> = recordingController.state

    val isRecording: StateFlow<Boolean> = recordingController.isRecording

    private val _recordingConfig = MutableStateFlow(RecordingConfig())
    val recordingConfig: StateFlow<RecordingConfig> = _recordingConfig.asStateFlow()

    // Same shared clock as the camera screen (500ms ticks here).
    private val recordingClock = RecordingClock(
        recordingState = recordingController.state,
        scope = viewModelScope,
        tickMs = 500L,
    )
    val recordingElapsedMs: StateFlow<Long> = recordingClock.elapsedMs

    init {
        // Keep the recording draft's includeAudio in sync with the persisted
        // setting continuously; other user edits to the draft are preserved.
        viewModelScope.launch {
            settingsDataStore.recordingAudioEnabled.collect {
                _recordingConfig.value = _recordingConfig.value.copy(includeAudio = it)
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
        // the policy waits out the Scheduled phase itself. The picked time
        // rides in the config draft's `startTimeMs`; the controller turns a
        // future start into its Scheduled state, which becomes the only truth
        // the screen renders.
        startRecordingWithConfig(_recordingConfig.value)
    }

    /**
     * The clear (trash) button: cancels the controller's real armed schedule
     * — not a screen-local shadow of it — and clears the picked time from the
     * config draft so the next start is immediate.
     */
    fun cancelScheduledRecording() {
        recordingController.cancelSchedule()
        _recordingConfig.value = _recordingConfig.value.copy(startTimeMs = null)
    }

    private fun startRecordingWithConfig(config: RecordingConfig) {
        // Same decision as every other audio-wanting start: check the mic
        // live, then warn-and-degrade through MicAccess if audio is wanted
        // but unavailable.
        when (val decision = MicAccess.startDecision(
            featureEnabled = config.includeAudio,
            granted = MicAccess.isGranted(context),
            featureLabel = "Recording video",
        )) {
            is MicStartDecision.Degrade ->
                Toast.makeText(context, decision.warning, Toast.LENGTH_SHORT).show()
            MicStartDecision.Proceed -> {}
        }
        // The max-duration auto-stop and repeat policy is armed by the
        // RecordingController itself, so it holds no matter which client
        // started the recording and survives navigating away from this screen.
        // A future `startTimeMs` in the config becomes the controller's
        // Scheduled state.
        recordingController.start(config)
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
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }
}
