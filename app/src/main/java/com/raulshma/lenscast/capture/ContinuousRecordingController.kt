package com.raulshma.lenscast.capture

import android.util.Log
import com.raulshma.lenscast.capture.model.ContinuousRecordingPolicy
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the NVR-style continuous loop alive over the RecordingController's
 * public API. No new service and no direct RecordingService contact: the
 * controller observes the store's continuous-recording preferences plus the
 * RecordingController's `RecordingState` flow — the only public truth about
 * recording — and starts/stops through [RecordingController.start]/[stop]
 * with [ContinuousRecordingPolicy.segmentConfig].
 *
 * Chaining: the segment config carries `repeatIntervalSeconds > 0`, so the
 * RecordingController's bounded+repeat machinery re-starts each segment after
 * its auto-stop (RecordingDurationPolicy); this class only arms the first
 * segment, keeps the flags honest around foreign recordings, and repairs the
 * loop when the chain dies. A chain death is an Idle that outlives
 * [ContinuousRecordingPolicy.CHAIN_BREAK_GRACE_MS] while the loop was armed —
 * a user stop (the controller's stop() cancels the armed repeat), a missed
 * repeat fire (a drain slower than the repeat gap), or a service death. The
 * break sets the policy's suppressed-until stamp, so a manual stop stays
 * respected for the cooldown before the loop re-arms; a healthy chained
 * auto-stop (Idle for about one second) never reaches the grace mark and
 * costs nothing.
 *
 * Hosted (constructed + started) at the streaming manager — the one
 * app-lifetime composition point available here — but owned by the capture
 * module: everything it decides lives in [ContinuousRecordingPolicy].
 */
class ContinuousRecordingController(
    private val settingsDataStore: SettingsDataStore,
    private val recordingController: RecordingController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** True while this controller considers the loop maintained (a segment is live or starting). */
    private var armed = false

    /** True when the live segment was started by this controller (its stop is ours to send). */
    private var armedByUs = false

    /** Arming is suppressed until this stamp lapses (a stop broke the chain). */
    private var suppressedUntilMs = 0L

    /** The scheduled re-evaluation after a chain break or while suppressed. */
    private var rearmJob: Job? = null

    /** The scheduled re-check when the chain-break grace window expires. */
    private var graceJob: Job? = null

    /** Idle-seen stamp for the chain-break grace window; null while not counting. */
    private var idleSinceMs: Long? = null

    /**
     * Starts observing. Idempotent enough for single-call use; the observation
     * runs for the scope's lifetime (the process — the hosting manager lives
     * as long as the app).
     */
    fun start() {
        scope.launch {
            combine(
                settingsDataStore.continuousRecording,
                settingsDataStore.continuousSegmentMinutes,
                settingsDataStore.recordingAudioEnabled,
                recordingController.state,
            ) { enabled, segmentMinutes, audioEnabled, state ->
                Inputs(enabled, segmentMinutes, audioEnabled, state)
            }.collect { evaluate(it) }
        }
    }

    private data class Inputs(
        val enabled: Boolean,
        val segmentMinutes: Int,
        val audioEnabled: Boolean,
        val state: RecordingState,
    )

    private fun evaluate(inputs: Inputs) {
        val config = ContinuousRecordingPolicy.segmentConfig(inputs.segmentMinutes, inputs.audioEnabled)
        // One decision under the lock; the controller calls happen under it
        // too (see the Start commit below).
        val decision = synchronized(lock) { decide(inputs, config) }
        when (decision) {
            is Decision.Start -> synchronized(lock) {
                // Re-verify under the commit lock: decide() saw Idle, but a
                // user start can land between decision and commit — and
                // RecordingController.start supersedes whatever is live, so
                // the verdict, the flags, and the start must be one step.
                if (recordingController.state.value is RecordingState.Idle) {
                    Log.d(TAG, "Arming continuous segment: ${config.durationSeconds}s, " +
                        "repeat=${config.repeatIntervalSeconds}s")
                    armed = true
                    armedByUs = true
                    idleSinceMs = null
                    cancelGraceLocked()
                    recordingController.start(config)
                }
            }
            is Decision.StopOurs -> synchronized(lock) {
                // Same commit discipline as Start: re-verify under the lock
                // so a user stop/start landing after the decision is never
                // overridden by the disable-time teardown.
                if (armedByUs && recordingController.state.value is RecordingState.Recording) {
                    Log.d(TAG, "Continuous recording disabled; stopping the loop segment")
                    armed = false
                    armedByUs = false
                    idleSinceMs = null
                    suppressedUntilMs = 0L
                    cancelGraceLocked()
                    recordingController.stop()
                }
            }
            is Decision.ScheduleRearm -> scheduleRearm(decision.delayMs)
            Decision.ScheduleGraceCheck -> scheduleGraceCheck()
            Decision.Noop, Decision.Live -> Unit
        }
    }

    /** Caller holds [lock]. */
    private fun decide(inputs: Inputs, config: RecordingConfig): Decision {
        if (!inputs.enabled) {
            rearmJob?.cancel()
            rearmJob = null
            suppressedUntilMs = 0L
            val recordingActive = inputs.state is RecordingState.Recording
            return when {
                ContinuousRecordingPolicy.shouldStopOnDisable(
                    enabled = false,
                    recordingActive = recordingActive,
                    armedByController = armedByUs,
                ) -> Decision.StopOurs
                else -> {
                    // A foreign recording outlives the toggle untouched; our
                    // own idle loop simply ends here.
                    armed = false
                    armedByUs = false
                    idleSinceMs = null
                    cancelGraceLocked()
                    Decision.Noop
                }
            }
        }
        return when (val state = inputs.state) {
            is RecordingState.Recording -> {
                idleSinceMs = null
                cancelGraceLocked()
                // Any live recording IS the loop — ours mid-chain or a user's
                // own start. Maintain it, never double-start over it.
                armed = true
                armedByUs = armedByUs || state.config == config
                Decision.Live
            }
            is RecordingState.Scheduled -> {
                // A future start is pending (capture-screen schedule): leave
                // it alone; its fire re-enters through the state flow.
                idleSinceMs = null
                cancelGraceLocked()
                Decision.Noop
            }
            RecordingState.Idle -> decideIdle(config)
        }
    }

    /** Caller holds [lock]; the feature is enabled and the state is Idle. */
    private fun decideIdle(config: RecordingConfig): Decision {
        if (!armed) {
            // Nothing is being maintained: either the initial arm (arm
            // immediately — the RecordingController gates camera access) or a
            // suppression window from an earlier break (wait it out).
            if (!ContinuousRecordingPolicy.canArm(nowMs(), suppressedUntilMs)) {
                val remaining = suppressedUntilMs - nowMs()
                if (rearmJob == null) return Decision.ScheduleRearm(remaining)
                return Decision.Noop
            }
            return if (ContinuousRecordingPolicy.shouldArm(
                    enabled = true,
                    recordingStateIsIdle = true,
                    alreadyArmed = false,
                )
            ) {
                Decision.Start
            } else {
                Decision.Noop
            }
        }
        // Armed and Idle: the chain broke. Give the repeat machinery the grace
        // window before declaring the break — a healthy chained auto-stop
        // spends about REPEAT_GAP_SECONDS in Idle.
        val since = idleSinceMs ?: run {
            idleSinceMs = nowMs()
            // The state flow stays Idle — it will not re-emit on its own — so
            // schedule the re-check that declares the break when the grace
            // window lapses (or is cancelled by the next Recording emission).
            return Decision.ScheduleGraceCheck
        }
        val idleForMs = nowMs() - since
        if (!ContinuousRecordingPolicy.chainBreakConfirmed(idleForMs)) return Decision.Noop
        idleSinceMs = null
        armed = false
        armedByUs = false
        suppressedUntilMs = ContinuousRecordingPolicy.suppressedUntilMs(nowMs())
        Log.d(TAG, "Continuous chain broken after ${idleForMs}ms Idle; arming suppressed for " +
            "${ContinuousRecordingPolicy.USER_STOP_SUPPRESSION_MS}ms")
        return if (rearmJob == null) {
            Decision.ScheduleRearm(ContinuousRecordingPolicy.USER_STOP_SUPPRESSION_MS)
        } else {
            Decision.Noop
        }
    }

    /** Re-evaluate after [delayMs]: the suppression cooldown tail. */
    private fun scheduleRearm(delayMs: Long) {
        synchronized(lock) {
            rearmJob?.cancel()
            // Assigned under the lock the body nulls it from: the body's
            // reset can never land before the assignment (which would wedge
            // a completed Job into the field and Noop every later re-arm).
            rearmJob = scope.launch {
                delay(delayMs.coerceAtLeast(0L))
                synchronized(lock) { rearmJob = null }
                tryArm()
            }
        }
    }

    /**
     * The chain-break grace re-check: one re-evaluation after the grace
     * window, since nothing else fires while the state flow sits on Idle.
     * No-op when a check is already pending.
     */
    private fun scheduleGraceCheck() {
        synchronized(lock) {
            if (graceJob != null) return
            // Same assignment discipline as [scheduleRearm]: assigned under
            // the lock the body nulls it from.
            graceJob = scope.launch {
                delay(ContinuousRecordingPolicy.CHAIN_BREAK_GRACE_MS + GRACE_CHECK_SLACK_MS)
                synchronized(lock) { graceJob = null }
                evaluate(currentInputs())
            }
        }
    }

    /** Caller holds [lock]. */
    private fun cancelGraceLocked() {
        graceJob?.cancel()
        graceJob = null
    }

    /** The live decision inputs, re-read for a scheduled re-check. */
    private fun currentInputs(): Inputs = Inputs(
        enabled = settingsDataStore.continuousRecording.value,
        segmentMinutes = settingsDataStore.continuousSegmentMinutes.value,
        audioEnabled = settingsDataStore.recordingAudioEnabled.value,
        state = recordingController.state.value,
    )

    /**
     * The delayed re-arm: the same verdicts as the live path ([shouldArm] +
     * [ContinuousRecordingPolicy.canArm]) plus the segment config, all
     * re-read at fire time so a toggle flip, a user start, or a segment/
     * audio change landing during the wait wins. Verdict, flags, and start
     * commit under one lock — [RecordingController.start] supersedes
     * whatever is live, so the Idle verdict and the start must not be
     * separable.
     */
    private fun tryArm() {
        synchronized(lock) {
            val idle = recordingController.state.value is RecordingState.Idle
            val fire = ContinuousRecordingPolicy.shouldArm(
                enabled = settingsDataStore.continuousRecording.value,
                recordingStateIsIdle = idle,
                alreadyArmed = armed,
            ) && ContinuousRecordingPolicy.canArm(nowMs(), suppressedUntilMs)
            if (!fire) return
            val config = ContinuousRecordingPolicy.segmentConfig(
                segmentMinutes = settingsDataStore.continuousSegmentMinutes.value,
                audioEnabled = settingsDataStore.recordingAudioEnabled.value,
            )
            armed = true
            armedByUs = true
            idleSinceMs = null
            recordingController.start(config)
        }
        Log.d(TAG, "Re-arming continuous segment after cooldown")
    }

    private sealed interface Decision {
        data object Start : Decision
        data object StopOurs : Decision
        data object Live : Decision
        data object Noop : Decision
        data class ScheduleRearm(val delayMs: Long) : Decision

        /** Entering the armed-Idle grace count: the break declaration needs a scheduled re-check. */
        data object ScheduleGraceCheck : Decision
    }

    companion object {
        private const val TAG = "ContinuousRecording"

        /** Fired slightly past the grace mark, so the break declaration is unambiguous. */
        private const val GRACE_CHECK_SLACK_MS = 50L
    }
}
