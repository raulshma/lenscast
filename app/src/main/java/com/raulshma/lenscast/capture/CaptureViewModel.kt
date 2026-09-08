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

    private val _timelapseBusy = MutableStateFlow(false)
    val timelapseBusy: StateFlow<Boolean> = _timelapseBusy.asStateFlow()
    private val _timelapseMessage = MutableStateFlow<String?>(null)
    val timelapseMessage: StateFlow<String?> = _timelapseMessage.asStateFlow()

    /** Assemble the most recent interval photos into an MP4 timelapse. */
    fun assembleTimelapse(lastN: Int = 100, fps: Int = 30) {
        if (_timelapseBusy.value) return
        _timelapseBusy.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val photos = TimelapseAssembler.selectSources(captureHistoryStore.history.value, lastN)
                if (photos.size < TimelapseAssembler.MIN_SOURCES) {
                    _timelapseMessage.value = "Need at least 10 photos (have ${photos.size})"
                    return@launch
                }
                val resolver = CaptureMediaResolver(context.contentResolver)
                val tmpDir = java.io.File(context.cacheDir, "timelapse_frames").apply { mkdirs() }
                tmpDir.listFiles()?.forEach { it.delete() }
                var idx = 0
                for (entry in photos) {
                    val bytes = try {
                        resolver.openStream(entry.filePath)?.use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    java.io.File(tmpDir, com.raulshma.lenscast.capture.MediaFileNaming.timelapseFrameName(idx++)).writeBytes(bytes)
                }
                if (idx < TimelapseAssembler.MIN_SOURCES) {
                    _timelapseMessage.value = "Could not read frames (read $idx)"
                    return@launch
                }
                val outName = com.raulshma.lenscast.capture.MediaFileNaming.timelapseName(java.util.Date())
                val movies = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES
                )
                val dir = com.raulshma.lenscast.capture.model.CaptureMediaFormat.videoDir(movies).apply { mkdirs() }
                val outFile = java.io.File(dir, outName)
                val ok = TimelapseAssembler.assemble(tmpDir, outFile, fps)
                if (ok) {
                    captureHistoryStore.add(
                        captureHistoryStore.createVideoEntry(
                            fileName = outName,
                            filePath = outFile.absolutePath,
                            fileSizeBytes = outFile.length(),
                            durationMs = (idx * 1000L / fps),
                        )
                    )
                    _timelapseMessage.value = "Timelapse saved: $outName ($idx frames)"
                } else {
                    _timelapseMessage.value = "Timelapse failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Timelapse failed", e)
                _timelapseMessage.value = "Timelapse failed: ${e.message}"
            } finally {
                _timelapseBusy.value = false
            }
        }
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
