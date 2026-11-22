package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.CaptureSource
import com.raulshma.lenscast.capture.IntervalCapturePolicy
import com.raulshma.lenscast.capture.RecordingSnapshot
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.capture.model.IntervalCaptureState
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.TimelapseState
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.IntervalCaptureStatus
import com.raulshma.lenscast.companion.client.dto.RecordingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The capture screen's REMOTE backend: [CaptureSource] over the paired
 * device's capture routes — interval capture via
 * `GET /api/capture/interval/status` + `POST …/start`/`…/stop`, recording
 * (manual AND scheduled) via `GET /api/recording/status` +
 * `POST /api/recording/start`/`stop`, and the one-shot photo via
 * `POST /api/capture` — all through the device's `LenscastApiClient`. Built
 * by [com.raulshma.lenscast.companion.di.CompanionGraph.captureSource],
 * cached per device beside the other sources.
 *
 * Lifecycle/visibility contract: plain REST throughout — the source never
 * starts or stops the device session (the console screen owns that
 * lifecycle), so a deep-linked capture screen works with no live link. The
 * two status polls ride WhileSubscribed flows (the
 * AutomationViewModel.lastFiredKeys idiom): each beat fetches only while a
 * screen is subscribed, so nothing polls in the background; a write re-fetches
 * the touched status once after its round trip (the events source's
 * clear-after-confirm shape), so the UI never waits out a beat to show the
 * command's effect.
 *
 * Scheduled recording rides the same wire as an immediate start: the draft's
 * future `startTimeMs` POSTs on /api/recording/start and the device's own
 * controller arms its Scheduled state; the status route's
 * `isScheduled`/`scheduledStartTimeMs` is the only schedule truth this
 * backend reads. The schedule row's trash can cancels through the
 * schedule-only `/api/recording/cancel-schedule` — the device's controller
 * checks the pending schedule atomically, so a recording that became due
 * after this phone's last status poll is left untouched (the stop verb,
 * which cancels AND stops, would kill exactly that recording).
 *
 * Degraded-to-hidden (the wire has no carrier, so the flag hides the
 * affordance rather than rendering a dead control):
 * - [canTimelapse] = false — timelapse assembly reads THIS phone's interval
 *   photos through the local composer; the device's captures are its
 *   gallery's concern and no timelapse route exists. The auto-assemble
 *   toggle is equally unpersisted (no settings field).
 * - [includeAudioDefault] = null — the wire start carries `includeAudio`
 *   explicitly and the device's persisted recording preference is its own
 *   settings surface (the system tab already edits it), so the recording
 *   draft does not seed from a preference here.
 *
 * Role gates: every write here is a device-state write a viewer-role pairing
 * would 403 — the flags ([canCapturePhoto]/[canControlRecording]/
 * [canControlInterval]) hide the affordances instead, mirroring the device's
 * own [com.raulshma.lenscast.streaming.web.RoleGatePolicy] verdict; a gated
 * op is a no-op at the source.
 */
class RemoteCaptureSource(
    /**
     * The device's JSON client; null when the roster lost the device — a
     * settled no-dial state (Unknown interval truth, Idle recording, every
     * mutation a no-op).
     */
    private val api: LenscastApiClient?,
    /** The roster role's write verdicts (admin pairings only). */
    override val canCapturePhoto: Boolean,
    override val canControlRecording: Boolean,
    override val canControlInterval: Boolean,
    /** The poll beats (the injectable record; tests shrink it). */
    private val timings: ConsoleTimings = ConsoleTimings(),
    /** The scope the polls and the write round trips run on (the graph's app scope). */
    private val scope: CoroutineScope,
) : CaptureSource {

    override val canTimelapse = false // no timelapse carrier on the wire — the section hides

    // ── polled truth ──
    // Each beat flow fetches while subscribed and bumps a monotonic tick;
    // the public flows merge the tick with the mutable truth so both poll
    // refreshes and write answers re-render (a StateFlow would conflate the
    // tick's nulls — the increment keeps every beat visible).
    //
    // Stale-read discipline (both lanes): every status read stamps the
    // lane's write generation before dialing, and its answer publishes only
    // if no command completed while it was in flight — a response that
    // predates a settled command is rejected instead of overwriting the
    // command's refreshed truth (the RemoteAutomationSource convention). A
    // failed command bumps nothing. The command's own post-command refresh
    // dials after the bump and publishes under the lane's mutex, so it
    // lands immediately and cannot be reordered behind the older read.

    private val _intervalState =
        MutableStateFlow<IntervalCaptureState>(IntervalCaptureState.Unknown)

    private val intervalWriteGeneration = AtomicInteger(0)
    private val intervalPublishMutex = Mutex()

    private val intervalBeat: StateFlow<Long> = flow {
        var tick = 0L
        while (true) {
            fetchIntervalStatus()
            emit(++tick)
            delay(timings.capturePollMs)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), 0L)

    override val intervalState: StateFlow<IntervalCaptureState> =
        merge(intervalBeat, _intervalState)
            .map { _intervalState.value }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), IntervalCaptureState.Unknown)

    /**
     * The one atomic recording snapshot: the confirmed state and the
     * confirmation published together, so the control's render and its
     * start/stop intent answer a single value.
     * [RecordingSnapshot.Unconfirmed] until the first status read lands —
     * the placeholder is not an Idle, and a press in that window must not
     * send a start against an unobserved device state (the screen keeps the
     * record affordances disabled).
     */
    private val _recordingSnapshot =
        MutableStateFlow<RecordingSnapshot>(RecordingSnapshot.Unconfirmed)

    private val _recordingElapsedMs = MutableStateFlow(0L)

    private val recordingWriteGeneration = AtomicInteger(0)
    private val recordingPublishMutex = Mutex()

    private val recordingBeat: StateFlow<Long> = flow {
        var tick = 0L
        while (true) {
            fetchRecordingStatus()
            emit(++tick)
            delay(timings.recordingPollMs)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), 0L)

    override val recordingSnapshot: StateFlow<RecordingSnapshot> =
        merge(recordingBeat, _recordingSnapshot)
            .map { _recordingSnapshot.value }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), RecordingSnapshot.Unconfirmed)

    override val recordingElapsedMs: StateFlow<Long> =
        merge(recordingBeat, _recordingElapsedMs)
            .map { _recordingElapsedMs.value }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), 0L)

    // Timelapse has no wire carrier: pinned Idle (the screen's message line
    // renders nothing) and false (the controls hide).
    override val timelapseState: StateFlow<TimelapseState> =
        MutableStateFlow(TimelapseState.Idle)
    override val autoTimelapseEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val includeAudioDefault: StateFlow<Boolean?> = MutableStateFlow(null)

    /**
     * The command path's post-command refresh: dials AFTER the caller's
     * generation bump (under the held publish mutex) and publishes
     * unconditionally — no older read can pass its stamp check while the
     * mutex is held, so this lands last with the command's truth.
     */
    private suspend fun publishIntervalStatus() {
        val client = api ?: return
        val status = try {
            client.intervalCaptureStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure keeps the last truth rendered
        }
        _intervalState.value = status.toIntervalState()
    }

    private suspend fun publishRecordingStatus() {
        val client = api ?: return
        val status = try {
            client.recordingStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure keeps the last truth rendered
        }
        _recordingSnapshot.value = RecordingSnapshot.Confirmed(
            status.toRecordingState(nowMs = System.currentTimeMillis()),
        )
        _recordingElapsedMs.value = status.elapsedSeconds * 1000L
    }

    private suspend fun fetchIntervalStatus() {
        val client = api ?: return
        val startGeneration = intervalWriteGeneration.get()
        val status = try {
            client.intervalCaptureStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure keeps the last truth rendered
        }
        intervalPublishMutex.withLock {
            if (intervalWriteGeneration.get() == startGeneration) {
                _intervalState.value = status.toIntervalState()
            }
        }
    }

    private suspend fun fetchRecordingStatus() {
        val client = api ?: return
        val startGeneration = recordingWriteGeneration.get()
        val status = try {
            client.recordingStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure keeps the last truth rendered
        }
        recordingPublishMutex.withLock {
            if (recordingWriteGeneration.get() == startGeneration) {
                // The first landed read confirms the device's state — the
                // record affordances un-disable and the control's verdict
                // starts trusting the snapshot's value.
                _recordingSnapshot.value = RecordingSnapshot.Confirmed(
                    status.toRecordingState(nowMs = System.currentTimeMillis()),
                )
                _recordingElapsedMs.value = status.elapsedSeconds * 1000L
            }
        }
    }

    // ── operations ──

    override fun capturePhoto() {
        val client = api ?: return
        if (!canCapturePhoto) return
        scope.launch {
            try {
                client.capturePhoto()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The local surface logs and moves on; no error banner here.
            }
        }
    }

    override fun startIntervalCapture(config: IntervalCaptureConfig) {
        val client = api ?: return
        if (!canControlInterval) return
        scope.launch {
            try {
                client.intervalCaptureStart(
                    intervalSeconds = config.intervalSeconds.toInt(),
                    totalCaptures = config.totalCaptures,
                    flashMode = config.flashMode.name,
                )
                // The command's effect, immediately: the next beat is a
                // poll interval away. The bump under the publish mutex
                // rejects any poll response still in flight from before
                // the command.
                intervalPublishMutex.withLock {
                    intervalWriteGeneration.incrementAndGet()
                    publishIntervalStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed start leaves the last truth; the next beat reconciles.
            }
        }
    }

    override fun stopIntervalCapture() {
        val client = api ?: return
        if (!canControlInterval) return
        scope.launch {
            try {
                client.intervalCaptureStop()
                // Same reconciliation rule as the start.
                intervalPublishMutex.withLock {
                    intervalWriteGeneration.incrementAndGet()
                    publishIntervalStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same reconciliation rule as the start.
            }
        }
    }

    override fun startRecording(draft: RecordingConfig) {
        val client = api ?: return
        if (!canControlRecording) return
        scope.launch {
            try {
                // The control's intended start — it only sends this from its
                // confirmed-Idle snapshot, so no verdict is asked here (the
                // draft's includeAudio rides the wire start; the device
                // guards its own mic, not this phone's). The same wire start
                // as the schedule command: a future startTimeMs arms the
                // device's Scheduled state, an immediate one starts now.
                client.recordingStart(
                    durationSeconds = draft.durationSeconds,
                    repeatIntervalSeconds = draft.repeatIntervalSeconds,
                    quality = draft.quality.name,
                    includeAudio = draft.includeAudio,
                    startTimeMs = draft.startTimeMs,
                )
                recordingPublishMutex.withLock {
                    recordingWriteGeneration.incrementAndGet()
                    publishRecordingStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed command leaves the last truth; the next beat reconciles.
            }
        }
    }

    override fun stopRecording() {
        val client = api ?: return
        if (!canControlRecording) return
        scope.launch {
            try {
                client.recordingStop()
                // Same reconciliation rule as the start.
                recordingPublishMutex.withLock {
                    recordingWriteGeneration.incrementAndGet()
                    publishRecordingStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same reconciliation rule as the start.
            }
        }
    }

    override fun startScheduledRecording(draft: RecordingConfig) {
        val client = api ?: return
        if (!canControlRecording) return
        scope.launch {
            try {
                // The same wire start as the toggle's start branch: a future
                // startTimeMs arms the device's Scheduled state, an
                // immediate one starts now. No stop verdict is asked — a
                // schedule command is a start, not a toggle.
                client.recordingStart(
                    durationSeconds = draft.durationSeconds,
                    repeatIntervalSeconds = draft.repeatIntervalSeconds,
                    quality = draft.quality.name,
                    includeAudio = draft.includeAudio,
                    startTimeMs = draft.startTimeMs,
                )
                recordingPublishMutex.withLock {
                    recordingWriteGeneration.incrementAndGet()
                    publishRecordingStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same reconciliation rule as the toggle.
            }
        }
    }

    override fun cancelScheduledRecording() {
        val client = api ?: return
        if (!canControlRecording) return
        scope.launch {
            try {
                // The schedule-only cancel: the device's controller checks
                // the pending schedule ATOMICALLY (under its own lock), so a
                // recording that became due after this phone's last status
                // poll is left untouched — the stale Scheduled snapshot this
                // screen may hold cannot stop it. The old stop-verb path
                // would have killed exactly that recording.
                client.recordingCancelSchedule()
                recordingPublishMutex.withLock {
                    recordingWriteGeneration.incrementAndGet()
                    publishRecordingStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same reconciliation rule.
            }
        }
    }

    override fun assembleTimelapse(lastN: Int, fps: Int) = Unit // gated off: no wire carrier
    override fun setAutoTimelapseEnabled(enabled: Boolean) = Unit // gated off: no wire carrier

    companion object {
        /** The subscribe grace that parks the beats when no screen shows. */
        private const val SUBSCRIBE_GRACE_MS = 5_000L
    }
}

/**
 * The pure wire→state projection: the device's recording status onto the
 * controller state vocabulary the capture screen (and schedule-row model)
 * render. Same mapping the console's recording readout uses — one truth, two
 * consumers. JVM-pure (both ends are Moshi data classes).
 */
internal fun RecordingStatus.toRecordingState(nowMs: Long): RecordingState = when {
    isRecording -> RecordingState.Recording(
        // The wire carries elapsed seconds only — the local `startedAtMs`
        // back-computed from the device's own clock reading.
        startedAtMs = nowMs - elapsedSeconds * 1000L,
        config = null, // the device's config is its own
    )
    isScheduled == true -> RecordingState.Scheduled(
        startAtMs = scheduledStartTimeMs ?: 0L,
        config = RecordingConfig(),
    )
    else -> RecordingState.Idle
}

/**
 * The pure wire→state projection: the device's interval status onto the
 * tri-state the interval section renders — confirmed-running with the
 * snapshot (the completed count rides along), stopped otherwise. JVM-pure.
 */
internal fun IntervalCaptureStatus.toIntervalState(): IntervalCaptureState =
    if (isRunning) {
        IntervalCaptureState.Running(
            IntervalCapturePolicy.StatusSnapshot(
                isRunning = true,
                completedCaptures = completedCaptures,
            ),
        )
    } else {
        IntervalCaptureState.Stopped
    }
