package com.raulshma.lenscast.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.TimelapseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The capture screen's state owner — pure UI state (the two config drafts)
 * over the [CaptureSource] seam, one interface over this phone's local
 * pipeline and the paired device's capture routes. The source owns
 * everything that differs between backends (interval truth, recording state
 * and elapsed, the timelapse pipeline, the mic consult, the write gates);
 * this ViewModel owns the draft editors and the derived projections both
 * backends share, and routes every control to the source.
 *
 * The recording draft's `includeAudio` seeds from the source's
 * [CaptureSource.includeAudioDefault] (null = no carrier — the remote
 * backend), keeping the draft in sync with the persisted setting
 * continuously until the user makes an explicit audio choice; other user
 * edits to the draft are preserved.
 */
class CaptureViewModel(
    private val source: CaptureSource,
) : ViewModel() {

    private val _intervalConfig = MutableStateFlow(IntervalCaptureConfig())
    val intervalConfig: StateFlow<IntervalCaptureConfig> = _intervalConfig.asStateFlow()

    private val _recordingConfig = MutableStateFlow(RecordingConfig())
    val recordingConfig: StateFlow<RecordingConfig> = _recordingConfig.asStateFlow()

    /**
     * Whether the user has explicitly chosen the recording draft's audio:
     * once set, later default emissions stop overwriting the draft's
     * [RecordingConfig.includeAudio] (the sync below defers to it) and both
     * start paths send the chosen value as-is.
     */
    private var audioExplicitlyChosen = false

    init {
        // Keep the recording draft's includeAudio in sync with the backend's
        // default continuously; other user edits to the draft are preserved,
        // and so is an explicit audio choice — a later default emission must
        // not overwrite the user's own flip of the switch.
        viewModelScope.launch {
            source.includeAudioDefault.collect { default ->
                default?.let { enabled ->
                    if (!audioExplicitlyChosen) {
                        _recordingConfig.value = _recordingConfig.value.copy(includeAudio = enabled)
                    }
                }
            }
        }
    }

    // ── reads (straight from the source — no mirrors) ──

    val intervalState: StateFlow<IntervalCaptureState> = source.intervalState

    /**
     * The record control's one atomic read (the [RecordingSnapshot.Confirmed]
     * state or the unconfirmed placeholder) — the enable gate, the look, and
     * the press's intended start/stop action all answer it.
     */
    val recordingSnapshot: StateFlow<RecordingSnapshot> = source.recordingSnapshot

    /**
     * The [RecordingState] view of the snapshot for the schedule row
     * (Unconfirmed reads as the Idle placeholder — the row renders nothing
     * there, exactly as the pre-read flow always did).
     */
    val recordingState: StateFlow<RecordingState> = source.recordingSnapshot
        .map { (it as? RecordingSnapshot.Confirmed)?.state ?: RecordingState.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), RecordingState.Idle)

    // The boolean view of the snapshot: confirmed Recording → true,
    // everything else false — exactly the pre-seam `controller.isRecording` shape.
    val isRecording: StateFlow<Boolean> = source.recordingSnapshot
        .map { it is RecordingSnapshot.Confirmed && it.state is RecordingState.Recording }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), false)

    val recordingElapsedMs: StateFlow<Long> = source.recordingElapsedMs

    val timelapseState: StateFlow<TimelapseState> = source.timelapseState

    val autoTimelapseEnabled: StateFlow<Boolean> = source.autoTimelapseEnabled

    // ── draft editors (screen input state, not backend truth) ──

    fun updateIntervalConfig(config: IntervalCaptureConfig) {
        _intervalConfig.value = config
    }

    fun updateRecordingConfig(config: RecordingConfig) {
        // An includeAudio change is the user's explicit audio choice — it
        // wins over the synced default from here on (the default-sync
        // collector and both start paths defer to it).
        if (config.includeAudio != _recordingConfig.value.includeAudio) {
            audioExplicitlyChosen = true
        }
        _recordingConfig.value = config
    }

    // ── controls (routed to the source; gates hide the affordances) ──

    fun capturePhoto() = source.capturePhoto()

    fun startIntervalCapture(config: IntervalCaptureConfig) = source.startIntervalCapture(config)

    fun stopIntervalCapture() = source.stopIntervalCapture()

    /**
     * The record control's intended START (its confirmed-Idle snapshot
     * decided already): the draft is this ViewModel's, the mic consult and
     * the command path the source's.
     */
    fun startRecording() = source.startRecording(startDraft())

    /** The record control's intended STOP — no draft, no consult. */
    fun stopRecording() = source.stopRecording()

    /** The schedule command: a start, not a toggle — the draft rides whole. */
    fun startScheduledRecording() = source.startScheduledRecording(startDraft())

    /**
     * The start's draft: an explicit audio choice rides whole; a
     * never-chosen draft lets the hydrated default own includeAudio at
     * start time — taken from the source's seed when it has landed, left
     * to the source's start gate (the disk twin) while the seed still
     * holds the null readiness verdict — so a stale pre-sync draft value
     * is never sent.
     */
    private fun startDraft(): RecordingConfig {
        val draft = _recordingConfig.value
        if (audioExplicitlyChosen) return draft
        source.includeAudioDefault.value?.let { default ->
            if (draft.includeAudio != default) return draft.copy(includeAudio = default)
        }
        return draft
    }

    /**
     * The clear (trash) button: cancels the backend's real armed schedule —
     * not a screen-local shadow of it — and clears the picked time from the
     * config draft so the next start is immediate.
     */
    fun cancelScheduledRecording() {
        source.cancelScheduledRecording()
        _recordingConfig.value = _recordingConfig.value.copy(startTimeMs = null)
    }

    /** Assemble the most recent interval photos into an MP4 timelapse. */
    fun assembleTimelapse(lastN: Int = 100, fps: Int = 30) = source.assembleTimelapse(lastN, fps)

    fun setAutoTimelapseEnabled(enabled: Boolean) = source.setAutoTimelapseEnabled(enabled)

    class Factory(
        /** The backend for this route's device argument (local when absent). */
        private val source: CaptureSource,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CaptureViewModel(source) as T
        }
    }

    companion object {
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }
}
