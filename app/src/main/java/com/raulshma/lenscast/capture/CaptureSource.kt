package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.TimelapseState
import com.raulshma.lenscast.core.MicGate
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The capture screen's one seam — the exact truth surface [CaptureViewModel]
 * and CaptureScreen consume (interval-capture status, recording state +
 * elapsed, the timelapse pipeline, and the commands behind every control),
 * as a plain interface. One interface, two backends (the
 * [com.raulshma.lenscast.camera.CameraConsole] pattern; the
 * [GallerySource]/[EventsSource] precedents): the screen drives either
 *
 * - the **local** backend — [LocalCaptureSource], today's graph singletons
 *   (`IntervalCaptureScheduler` + `RecordingController` + `PhotoCaptureManager`
 *   + `TimelapseComposer` + the settings store) unchanged; or
 * - the **remote** backend —
 *   [com.raulshma.lenscast.companion.console.RemoteCaptureSource], the paired
 *   device's interval-capture, recording and one-shot-photo routes over its
 *   `LenscastApiClient` (`/api/capture/interval` control, `/api/recording`
 *   status + start/stop, `POST /api/capture` for the photo).
 *
 * The UI binds to this interface and reads the verdicts below to hide what
 * the current backend can't honor — the source owns the divergence, so no
 * screen carries an `if (remote)` branch.
 *
 * Mutation gates (the [com.raulshma.lenscast.streaming.web.RoleGatePolicy]
 * spirit applied to this screen's writes — every one is a device-state write
 * the device would 403 for a viewer-role pairing):
 *
 * - [canCapturePhoto] — POST /api/capture remotely, the camera pipeline
 *   locally. Local always true.
 * - [canControlRecording] — the record toggle, the scheduled start and the
 *   schedule cancel. Local always true.
 * - [canControlInterval] — interval-series start/stop. Local always true.
 * - [canTimelapse] — the manual assembly and the auto-assemble toggle read
 *   THIS phone's interval photos through the local composer pipeline; the
 *   wire defines no timelapse carrier (the device's own capture history is
 *   its gallery's concern), so the remote backend pins this false and the
 *   controls hide rather than render dead.
 *
 * The schedule's truth is the source's, wholly: the local impl arms the
 * controller's [RecordingState.Scheduled] (a future `startTimeMs` in the
 * start config waits out its delay); the remote impl rides the wire's
 * `isScheduled`/`scheduledStartTimeMs` on GET /api/recording/status, set by
 * POSTing the same future `startTimeMs` on /api/recording/start and cleared
 * by the schedule-only POST /api/recording/cancel-schedule (see
 * [cancelScheduledRecording] — the stop verb cancels AND stops, so it stays
 * the verb only once recording has begun). The two backends render the same
 * [RecordingState] vocabulary, so the schedule-row model
 * ([scheduleRowUi]) needs no branch.
 */
interface CaptureSource {

    // ── Reads ──

    /**
     * The interval series' tri-state: [IntervalCaptureState.Unknown] while
     * the truth is momentarily unobservable (locally, the first WorkManager
     * read in flight; remotely, the first status poll not yet landed),
     * [IntervalCaptureState.Running] with the confirmed snapshot, or
     * [IntervalCaptureState.Stopped].
     */
    val intervalState: StateFlow<IntervalCaptureState>

    /**
     * The recording pipeline's one atomic snapshot — the confirmed state and
     * the confirmation folded into a single value (the schedule row's and
     * the record control's truth). The pre-first-read window (remotely:
     * before the first `GET /api/recording/status` answer lands; locally
     * never — the controller is in-process truth) is the distinct
     * [RecordingSnapshot.Unconfirmed] instead of a misleading Idle, so the
     * control renders and derives its press intent from one read instead of
     * a state+confirmed flow pair that can straddle a publish boundary.
     */
    val recordingSnapshot: StateFlow<RecordingSnapshot>

    /** The live recording's elapsed milliseconds; 0 while none is running. */
    val recordingElapsedMs: StateFlow<Long>

    /** The manual timelapse run's state machine (Idle / Running / Done). Idle where [canTimelapse] is false. */
    val timelapseState: StateFlow<TimelapseState>

    /** The persisted auto-assemble-on-completion toggle; false where [canTimelapse] is false (row hidden). */
    val autoTimelapseEnabled: StateFlow<Boolean>

    /**
     * The seed a new recording draft's `includeAudio` starts from — the
     * phone's persisted recording-audio setting locally, kept in sync
     * continuously (the draft's other edits are preserved). Null where the
     * backend has no carrier for that preference (remote: the start command
     * carries `includeAudio` explicitly and the device's own preference is
     * its settings surface), which tells the ViewModel not to sync.
     */
    val includeAudioDefault: StateFlow<Boolean?>

    // ── Mutation gates (see the KDoc's verdict table) ──

    val canCapturePhoto: Boolean
    val canControlRecording: Boolean
    val canControlInterval: Boolean
    val canTimelapse: Boolean

    // ── Operations ──

    /** One photo through the owning camera pipeline. Caller-gated by [canCapturePhoto]. */
    fun capturePhoto()

    /**
     * Starts an interval-capture series with [config] (the screen's draft).
     * Caller-gated by [canControlInterval].
     */
    fun startIntervalCapture(config: IntervalCaptureConfig)

    /** Stops the running series. Caller-gated by [canControlInterval]. */
    fun stopIntervalCapture()

    /**
     * The control's intended START — passed from the [RecordingSnapshot.Confirmed]
     * Idle the control renders (a confirmed idle device starts normally; the
     * unconfirmed placeholder never reaches here, the control stays disabled
     * and drops its presses). Carries [draft] whole (the screen's config
     * draft, quality/duration/repeat/audio included); the mic consult rides
     * the local backend's start ladder. Caller-gated by [canControlRecording].
     */
    fun startRecording(draft: RecordingConfig)

    /**
     * The control's intended STOP — passed from a confirmed Scheduled or
     * Recording snapshot. A stop never consults the mic gate. Caller-gated
     * by [canControlRecording].
     */
    fun stopRecording()

    /**
     * Arms the schedule (or starts immediately when [draft] carries no
     * future `startTimeMs`): locally through the controller's Scheduled
     * state, remotely through the wire start's `startTimeMs`. The same
     * command path as a start, so the max-duration/repeat policy is armed
     * for scheduled recordings too. Caller-gated by [canControlRecording].
     */
    fun startScheduledRecording(draft: RecordingConfig)

    /**
     * Cancels an armed schedule without touching a live recording (local:
     * the controller's cancel seam — the schedule row's trash can; remote:
     * the schedule-only `/api/recording/cancel-schedule`, whose device-side
     * state check is atomic — a schedule that became due before the call
     * lands no-ops, so a recording that started after this phone's last
     * status poll is never stopped by the stale Scheduled screen state).
     */
    fun cancelScheduledRecording()

    /**
     * Assembles the most recent interval photos into an MP4 timelapse.
     * Caller-gated by [canTimelapse]; a no-op where the gate is false.
     */
    fun assembleTimelapse(lastN: Int = 100, fps: Int = 30)

    /** The auto-assemble toggle's write. Caller-gated by [canTimelapse]. */
    fun setAutoTimelapseEnabled(enabled: Boolean)
}

/**
 * The record affordance's atomic read: the state and its confirmation in one
 * value, so the control's render verdict and its start/stop intent answer a
 * single snapshot — the old `recordingState` + `recordingStateConfirmed`
 * pair could straddle a publish boundary and disagree.
 */
sealed interface RecordingSnapshot {

    /** The pre-first-read placeholder: the record affordances render disabled. */
    data object Unconfirmed : RecordingSnapshot

    /**
     * A confirmed status read; [state] is the backend's real Idle /
     * Scheduled / Recording truth. Confirmed Idle is the one state a start
     * may be intended from.
     */
    data class Confirmed(val state: RecordingState) : RecordingSnapshot
}

/**
 * The capture screen's LOCAL backend: [CaptureSource] over this phone's
 * graph singletons — the CaptureViewModel's old collaborators, lifted
 * verbatim into a source so the remote backend can stand beside them.
 * Every behavior is today's: the WorkManager observe-status tail (Unknown
 * until the first snapshot, the WhileSubscribed cadence included), the mic
 * warn-and-degrade consult behind every start, the controller's
 * start/schedule/stop choreography, the 500 ms recording clock, and the
 * composer's timelapse state machine.
 */
class LocalCaptureSource(
    /** The app context the scheduler, the mic gate and the composer resolve. */
    private val context: Context,
    private val recordingController: RecordingController,
    private val photoCaptureManager: PhotoCaptureManager,
    private val settingsDataStore: SettingsDataStore,
    /** The one timelapse pipeline (MediaStore + encryption publish seam). */
    private val timelapseComposer: TimelapseComposer? = null,
    /** The scope the status tail, the recording clock and the timelapse run on. */
    private val scope: CoroutineScope,
) : CaptureSource {

    override val canCapturePhoto = true
    override val canControlRecording = true
    override val canControlInterval = true
    override val canTimelapse = true

    // Interval-capture truth lives in WorkManager; derived directly from the
    // scheduler's cold flow — no manual mirrors to drift. Same WhileSubscribed
    // cadence as before — no WorkManager polling while no screen shows — with
    // a zero replay expiration: once sharing stops the retained value resets
    // to Unknown, so re-entry renders Unknown again (disabled-with-progress
    // start) while the first read is in flight.
    override val intervalState: StateFlow<IntervalCaptureState> =
        IntervalCaptureScheduler.observeStatus(context)
            .map { status ->
                if (status.isRunning) {
                    IntervalCaptureState.Running(status)
                } else {
                    IntervalCaptureState.Stopped
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS, replayExpirationMillis = 0),
                IntervalCaptureState.Unknown,
            )

    // The local controller is in-process truth — every state is confirmed,
    // there is no unconfirmed window to model.
    override val recordingSnapshot: StateFlow<RecordingSnapshot> = recordingController.state
        .map { RecordingSnapshot.Confirmed(it) }
        .stateIn(scope, SharingStarted.Eagerly, RecordingSnapshot.Confirmed(RecordingState.Idle))

    // Same shared clock as the camera screen (500ms ticks here).
    private val recordingClock = RecordingClock(
        recordingState = recordingController.state,
        scope = scope,
        tickMs = 500L,
    )
    override val recordingElapsedMs: StateFlow<Long> = recordingClock.elapsedMs

    /**
     * The mic warn-and-degrade consult behind every recording start — the
     * same gate the camera screen consults: one refresh-then-consult
     * behavior, one warning wording, one toast sink. A degrade warns and the
     * start proceeds either way (warn-and-degrade is the mic policy; the
     * service guards the live permission at record time).
     */
    private val consultMic = MicGate(context).recordingConfigConsult(label = "Recording video")

    // The manual timelapse run's state machine (Idle / Running / Done) —
    // the old two-flow shape (busy + message) never reset its message, so a
    // stale success/failure line persisted into the next run; the machine's
    // Running re-assert at each run's start is the fix.
    private val _timelapseState = MutableStateFlow<TimelapseState>(TimelapseState.Idle)
    override val timelapseState: StateFlow<TimelapseState> = _timelapseState.asStateFlow()

    override val autoTimelapseEnabled: StateFlow<Boolean> = settingsDataStore.autoTimelapseEnabled

    /**
     * The seed a new recording draft's `includeAudio` starts from, gated on
     * the store's hydration: the shared flow's value is the descriptor
     * default (the startup placeholder) until DataStore's first disk read
     * lands — the store's default-first race — and publishing that here
     * would seed the draft, and so every start, with the placeholder
     * answer. The recording-audio setting's FromDisk twin is the readiness
     * signal and the first truth; the shared flow then supplies the change
     * notifications only. A delivery that disagrees with the last disk
     * answer is re-consulted against the twin and ITS answer published:
     * the share-in's own disk-read landing races this one (both consume
     * the same first DataStore emission, on separate scopes), so the
     * replayed placeholder can still arrive after this read landed — the
     * twin's fresh answer is what keeps a persisted false from ever being
     * replaced by that replay.
     */
    override val includeAudioDefault: StateFlow<Boolean?> = flow {
        var persisted = settingsDataStore.recordingAudioEnabledFromDisk()
        emit(persisted)
        settingsDataStore.recordingAudioEnabled.collect { delivered ->
            if (delivered != persisted) {
                val fresh = settingsDataStore.recordingAudioEnabledFromDisk()
                if (fresh != persisted) {
                    persisted = fresh
                    emit(fresh)
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The start path's audio-hydration gate: a start that races the
     * hydration window (the draft has never been synced:
     * [includeAudioDefault] still holds null, the readiness verdict) must
     * not carry the placeholder audio answer, so the persisted setting is
     * applied from the disk twin. The read is fresh per start — a failed
     * read is never retained, so the next attempt retries it, and a read
     * that fails again logs and aborts the start (null return) rather than
     * substituting the placeholder answer. After hydration the draft rides
     * whole, its user edits preserved.
     */
    private suspend fun hydrationGatedStartDraft(draft: RecordingConfig): RecordingConfig? {
        if (includeAudioDefault.value != null) return draft
        val persisted = runCatching { settingsDataStore.recordingAudioEnabledFromDisk() }
            .onFailure { Log.w(TAG, "Recording audio preference unreadable — start aborted", it) }
            .getOrNull()
        return persisted?.let { draft.copy(includeAudio = it) }
    }

    override fun capturePhoto() {
        val fileName = photoCaptureManager.captureToGallery(
            onSaved = { filePath, _ -> Log.d(TAG, "Photo saved: $filePath") },
            onError = { exception -> Log.e(TAG, "Photo capture failed", exception) },
        )
        if (fileName == null) {
            Log.w(TAG, "capturePhoto: camera use case unavailable")
        }
    }

    override fun startIntervalCapture(config: IntervalCaptureConfig) {
        IntervalCaptureScheduler.start(
            context = context,
            intervalSeconds = config.intervalSeconds,
            totalCaptures = config.totalCaptures,
            flashMode = config.flashMode.name,
            completedCaptures = 0,
        )
        Log.d(TAG, "Interval capture started: every ${config.intervalSeconds}s")
    }

    override fun stopIntervalCapture() {
        IntervalCaptureScheduler.stop(context)
        Log.d(TAG, "Interval capture stopped")
    }

    override fun startRecording(draft: RecordingConfig) {
        // The control's intended start (its confirmed-Idle snapshot decided
        // already — no stop verdict is asked here). A start in the store's
        // default-first window must not carry the placeholder audio answer,
        // so the start gates on the disk twin — one read per gated start,
        // then the mic consult on the gated config, warn-and-degrade as
        // ever. The choreography rides the main looper: the mic gate's
        // warning sink is a Toast, and pre-seam it fired from the click.
        scope.launch(Dispatchers.Main.immediate) {
            hydrationGatedStartDraft(draft)?.let { recordingController.start(consultMic(it)) }
        }
    }

    override fun stopRecording() {
        // The control's intended stop: sent without the audio hydration gate
        // at all — hydration is a start's concern, a stop carries no config.
        recordingController.stop()
    }

    override fun startScheduledRecording(draft: RecordingConfig) {
        // Goes through the same start path as an immediate start so the
        // max-duration/repeat policy is armed for scheduled recordings too —
        // the policy waits out the Scheduled phase itself. A schedule command
        // is a start, not a toggle — the mic consult runs, but no stop
        // verdict is asked. Same audio hydration gate as the toggle, and
        // the same main-looper ride for the consult's warning sink.
        scope.launch(Dispatchers.Main.immediate) {
            hydrationGatedStartDraft(draft)?.let { recordingController.start(consultMic(it)) }
        }
    }

    override fun cancelScheduledRecording() {
        // The controller's real armed schedule — not a screen-local shadow of
        // it. (Clearing the picked time from the config draft is the
        // ViewModel's — the draft is screen input state, not backend truth.)
        recordingController.cancelSchedule()
    }

    override fun assembleTimelapse(lastN: Int, fps: Int) {
        if (_timelapseState.value is TimelapseState.Running) return
        // Running at the START of each run — the previous run's Done stops
        // rendering the moment a new one begins.
        _timelapseState.value = TimelapseState.Running
        scope.launch(Dispatchers.IO) {
            try {
                // The one timelapse pipeline: source selection, decrypting
                // reads, MediaCodec assembly, and the MediaStore + encryption
                // publish seam all live in the composer (shared with the
                // interval worker's auto-assembly). Output never lands in a
                // legacy public folder as plaintext.
                val result = timelapseComposer?.assembleLatest(lastN, fps)
                    // A missing composer is the failed shape (the message
                    // mapping folds it onto the same "Timelapse failed" text
                    // the old null branch rendered).
                    ?: TimelapseComposer.Result.NotSaved(TimelapseComposer.Reason.ASSEMBLY_FAILED)
                _timelapseState.value = TimelapseState.Done(result)
            } catch (e: Exception) {
                Log.e(TAG, "Timelapse failed", e)
                _timelapseState.value = TimelapseState.Done(
                    TimelapseComposer.Result.NotSaved(
                        TimelapseComposer.Reason.ASSEMBLY_FAILED,
                        e.message ?: "",
                    ),
                )
            }
        }
    }

    override fun setAutoTimelapseEnabled(enabled: Boolean) {
        scope.launch { settingsDataStore.saveAutoTimelapseEnabled(enabled) }
    }

    companion object {
        private const val TAG = "LocalCaptureSource"

        /** The status tail's subscribe grace — today's ViewModel cadence, kept. */
        private const val SUBSCRIBE_TIMEOUT_MS = 5000L
    }
}
