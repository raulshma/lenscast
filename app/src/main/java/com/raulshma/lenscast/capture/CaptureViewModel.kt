package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.camera.model.RecordingToggle
import com.raulshma.lenscast.core.MicGate
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

    // The mic warn-and-degrade gate behind every recording start — the same
    // gate the camera screen consults: one refresh-then-consult behavior,
    // one warning wording, one toast sink.
    private val micGate = MicGate(context)

    /**
     * The mic warn-and-degrade consult, shared by the toggle's pre-start
     * hook and the schedule command: refresh-then-consult through the gate —
     * a degrade warns through the shared sink and the start proceeds either
     * way (warn-and-degrade is the mic policy; the service guards the live
     * permission at record time).
     */
    private val consultMic = micGate.recordingConfigConsult(label = "Recording video")

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

    /**
     * The record button: the stop-vs-start verdict and the draft start
     * config are [RecordingToggle]'s — the same decide the camera screen
     * answers — and this ViewModel only executes the decision. The mic gate
     * rides the toggle's pre-start hook, so a stop never refreshes
     * permissions or warns.
     */
    fun toggleRecording() {
        when (val decision = RecordingToggle.decide(
            currentState = recordingState.value,
            startConfig = _recordingConfig.value,
            onBeforeStart = consultMic,
        )) {
            is RecordingToggle.ToggleDecision.Start -> startRecordingWithConfig(decision.config)
            RecordingToggle.ToggleDecision.Stop -> stopRecording()
        }
    }

    fun startScheduledRecording() {
        // Goes through the same start path as an immediate start so the
        // max-duration/repeat policy is armed for scheduled recordings too —
        // the policy waits out the Scheduled phase itself. The picked time
        // rides in the config draft's `startTimeMs`; the controller turns a
        // future start into its Scheduled state, which becomes the only truth
        // the screen renders. A schedule command is a start, not a toggle —
        // the mic consult runs, but no stop verdict is asked.
        startRecordingWithConfig(consultMic(_recordingConfig.value))
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

    /**
     * Executes a decided start: the max-duration auto-stop and repeat policy
     * is armed by the RecordingController itself, so it holds no matter
     * which client started the recording and survives navigating away from
     * this screen. A future `startTimeMs` in the config becomes the
     * controller's Scheduled state.
     */
    private fun startRecordingWithConfig(config: RecordingConfig) {
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
