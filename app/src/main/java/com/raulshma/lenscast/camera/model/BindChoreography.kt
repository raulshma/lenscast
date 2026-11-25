package com.raulshma.lenscast.camera.model

/**
 * The pure bind choreography behind the camera-open observer and the
 * session producers of [com.raulshma.lenscast.camera.CameraService]: what
 * one observed camera-state emission decides, which post-bind controls
 * rungs survive a newer bind, and what the recording bind's
 * detach/rebind/reattach ladder binds at each rung — one data-in/data-out
 * plan per decision, which the service then executes against LiveData, the
 * Handler, and CameraX. The same move [CameraSessionArbiter] made for the
 * demand gates, applied to the sequencing glue that survived that
 * extraction because it lived *inside* the observer and the bind paths.
 *
 * Why pure: this is the code 5ad333c had to fix by hand. bindRecording
 * replaced the live session without running the settle choreography its
 * sibling runs, and every later settings apply kept routing at the dead
 * pre-recording camera the stale write-back field still held — manual
 * ISO/exposure silently gone for the whole recording. The fix was the
 * four-step order (detach the observations riding the outgoing camera,
 * bind, reattach to the session that just bound, schedule the deferred
 * applies) plus the live-session seam; pinning the order as a step list
 * here means the next session producer cannot skip or reorder a rung
 * without a failing test saying so. The deletion test: removing this
 * module scatters the attach identity guards back into posted lambdas, the
 * rebind-budget reset back into the observer body, and the rung
 * cancellation back into a captured `val` — each a one-line guard with no
 * home a plain JVM test can reach.
 *
 * Deliberately NOT here: the Handler/LiveData mechanics (the service posts
 * the attach and the error rebind — the posted-rebind-to-avoid-re-entry
 * rule is expressed as plan data, the post itself stays execution), the
 * atomic attempt counter (read and swapped by the service — [CameraErrorPolicy]'s
 * caller-owned bookkeeping, which survives unchanged: the verdicts here
 * are folded through it, not absorbed), the
 * [android.hardware.camera2.CameraManager] availability-callback wait (its
 * decision is already pure — the WAIT_CAMERA_ADDED verdict — and its body
 * is SDK-gated platform plumbing with no decision left to extract), and
 * the capability queries interleaved into the normal bind's skeleton. The
 * rung delays stay [ControlsApplyPolicy]'s — composed here, not duplicated.
 */
object BindChoreography {

    // ── the observation attach identity guard (torch + camera-open) ──

    /** Whether a pending observation attach may land, or is a stale generation's leftover. */
    enum class ObservationAttach {
        /** The registered observer is still current and the camera it captured is still live. */
        Attach,

        /**
         * A fast rebind replaced the observer or the camera before the
         * posted attach landed: drop it. Attaching would feed a dead
         * session's camera state into the live observation fields — the
         * stale generation's observation is ignored.
         */
        DropStaleAttach,
    }

    /**
     * The identity guard both observation attaches run, as data. The
     * service captures the observer and the camera at registration, posts
     * the attach, and consults this on the main thread with plain
     * reference-equality answers — the comparison itself is JVM-pure; only
     * the compared objects are Android-live. The guard runs at attach time
     * (once per registration), not per emission: once attached, the
     * observer rides that camera's LiveData until the unbind/new-bind
     * detach removes it, exactly as before this module existed.
     */
    fun observationAttachPlan(
        observerIsCurrent: Boolean,
        cameraIsLive: Boolean,
    ): ObservationAttach =
        if (observerIsCurrent && cameraIsLive) ObservationAttach.Attach
        else ObservationAttach.DropStaleAttach

    // ── the camera-open observer's transition decision ──

    /** Which human message an observed error renders; the service holds the strings. */
    sealed interface ErrorMessage {
        /** ERROR_CAMERA_DISABLED — the while-in-use denial's dedicated line. */
        data object DisabledByPolicy : ErrorMessage

        /** ERROR_CAMERA_FATAL_ERROR — the reboot-the-device line (CAMERA_FATAL_MESSAGE). */
        data object RebootRequired : ErrorMessage

        /** Every other code — the generic "code N" line. */
        data class Code(val code: Int) : ErrorMessage
    }

    /** What the shell does with one error observation, after reporting it. */
    enum class ErrorFollowUp {
        /**
         * Spend one rebind attempt, then rebind — via a handler post. The
         * post is the point: a bind run inline inside the LiveData dispatch
         * would re-enter the observer mid-transition (the bind's own
         * ERROR → OPEN choreography). The service owns the post and the
         * counter increment; the plan owns the rule that the rebind must
         * not run inline.
         */
        PostRebind,

        /** The camera is gone: register the platform availability-callback wait. No attempt spent — only actual rebinds count. */
        WaitCameraAdded,

        /** No sanctioned in-process lever (CameraX's retry, the device policy, a dead camera service): report only. */
        ReportOnly,
    }

    /** The decision for one camera-state emission. */
    sealed interface OpenStatePlan {
        /**
         * A pre-bind StateError replayed synchronously when `observeForever`
         * attaches to a singleton CameraX `CameraInfo.cameraState` whose
         * previous graph closed with an error — ignore it without publishing.
         */
        data object IgnoreStaleError : OpenStatePlan

        /** A StateError rode the emission: publish Error, then run the follow-up. */
        data class ErrorObservation(
            /** The [CameraErrorPolicy] verdict — published on the error and logged. */
            val verdict: CameraErrorPolicy.Verdict,
            /** The raw CameraX error code — the service logs it verbatim. */
            val errorCode: Int,
            /** Which human message renders; the strings stay in the service. */
            val messageKind: ErrorMessage,
            /** The lever the verdict earns, if any. */
            val followUp: ErrorFollowUp,
        ) : OpenStatePlan

        /** No error on the emission: the open-fact transition. */
        data class StateObservation(
            /** OPEN confirms the observed-open fact; anything else withdraws it. */
            val openObserved: Boolean,

            /**
             * An observed OPEN is the only receipt that the bounded rebind
             * budget restarts — until OPEN, each observed error spent one.
             * The service performs the atomic swap.
             */
            val resetRebindAttempts: Boolean,

            /**
             * Claim Ready — but only when the bind's own claim is not
             * already standing, so the observed open never stomps a fresher
             * published state.
             */
            val publishReady: Boolean,

            /**
             * Re-assert the controls — the RISING EDGE of the open fact on
             * the LIVE camera: OPEN arrived while the fact was not standing
             * and the camera observed is still the bound one. The two timed
             * rungs ([postBindRungs]) count milliseconds from the bind, so a
             * camera that opens later than both — a slow HAL, a while-in-use
             * denial that clears, a recording session rebinding an open
             * camera — never takes its settings: both rungs fired at a camera
             * that could not take them yet. The observed open is the only
             * receipt that the session CAN take controls, so it re-asserts
             * too. An edge, not an emission: LiveData re-delivers the current
             * value on attach and on lifecycle moves, and a re-assert carries
             * a force-focus apply that would cancel a deliberate
             * tap-to-focus. The identity guard is data like every other
             * attach guard here: a late dispatch from a camera a newer bind
             * replaced is dropped in the plan, not at the execution site.
             */
            val reassertControls: Boolean,
        ) : OpenStatePlan
    }

    /**
     * The one open-state transition decision. [errorCode] non-null means
     * the emission carried a StateError (CameraX has no ERROR type member —
     * the error's presence, not the type, is the check); [typeOpen] is the
     * emitted type being OPEN; [rebindAttempts] is the service's counter
     * read at dispatch; [readyAlreadyPublished] whether the session's bind
     * already claimed Ready; [previouslyObserved] whether the open fact stood
     * before this emission (the service reads it before folding the
     * emission's own verdict into it); [cameraIsLive] whether the camera the
     * emission came from is still the bound one. The verdict is folded
     * through [CameraErrorPolicy.decide] — composed, not re-derived.
     */
    fun openStatePlan(
        errorCode: Int?,
        typeOpen: Boolean,
        rebindAttempts: Int,
        readyAlreadyPublished: Boolean,
        previouslyObserved: Boolean,
        cameraIsLive: Boolean,
        isStalePreBindError: Boolean = false,
    ): OpenStatePlan {
        val code = errorCode ?: return OpenStatePlan.StateObservation(
            openObserved = typeOpen,
            resetRebindAttempts = typeOpen,
            publishReady = typeOpen && !readyAlreadyPublished,
            reassertControls = typeOpen && !previouslyObserved && cameraIsLive,
        )
        if (isStalePreBindError) {
            return OpenStatePlan.IgnoreStaleError
        }
        val verdict = CameraErrorPolicy.decide(code, rebindAttempts)
        val messageKind = when {
            code == CameraErrorPolicy.CODE_CAMERA_DISABLED -> ErrorMessage.DisabledByPolicy
            verdict == CameraErrorPolicy.Verdict.REBOOT_REQUIRED -> ErrorMessage.RebootRequired
            else -> ErrorMessage.Code(code)
        }
        val followUp = when (verdict) {
            CameraErrorPolicy.Verdict.REBIND -> ErrorFollowUp.PostRebind
            CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED -> ErrorFollowUp.WaitCameraAdded
            // CAMERAX_HANDLES, POLICY_DENIED, REBOOT_REQUIRED — no lever.
            else -> ErrorFollowUp.ReportOnly
        }
        return OpenStatePlan.ErrorObservation(verdict, code, messageKind, followUp)
    }

    // ── the post-bind controls-apply rungs ──

    /**
     * One deferred controls-apply rung: the delay and the bind generation
     * it was scheduled under — the capture-at-schedule half of the
     * cancellation rule as data.
     */
    data class PostBindRung(val delayMs: Long, val boundGeneration: Int)

    enum class RungAction {
        /** The generation is unchanged since scheduling: the rung's session still holds, apply the controls. */
        ApplyControls,

        /**
         * A newer bind landed since this rung was scheduled: drop without
         * applying. The new bind schedules its own rungs, and this rung's
         * force-focus reapply would otherwise cancel a tap-to-focus on the
         * session that replaced it.
         */
        CancelStaleGeneration,
    }

    /**
     * The rungs one successful bind schedules: one per [delaysMs] delay
     * (the caller passes [ControlsApplyPolicy.applyDelaysMs] — composed,
     * not duplicated), each bound to the generation captured at schedule
     * time. The Handler stays in the service; this only says what was
     * scheduled and under which generation.
     */
    fun postBindRungs(boundGeneration: Int, delaysMs: List<Long>): List<PostBindRung> =
        delaysMs.map { PostBindRung(it, boundGeneration) }

    /**
     * The fire-time verdict for one rung — the cancellation semantics as
     * data: only the generation match keeps the apply alive.
     */
    fun rungAction(rung: PostBindRung, bindGenerationNow: Int): RungAction =
        if (rung.boundGeneration == bindGenerationNow) RungAction.ApplyControls
        else RungAction.CancelStaleGeneration

    // ── the recording bind ladder ──

    /** The use-case slots a session producer can bind, in the service's field terms. */
    enum class BindSlot { Preview, ImageCapture, ImageAnalysis, VideoCapture }

    /** The detach/rebind/reattach ladder the recording bind executes, in order. */
    sealed interface RecordingBindStep {
        /** Detach the torch and camera-open observations riding the outgoing camera. */
        data object DetachObservations : RecordingBindStep

        /**
         * Bind the recording session. [attempts] is the largest-first
         * ladder — the service unbinds before each attempt and falls down
         * the ladder on bind failure, VideoCapture riding every rung.
         */
        data class BindRecordingSession(
            val attempts: List<List<BindSlot>>,

            /**
             * false for the recording bind, deliberately: the preview-side
             * fields must keep their values so the unbind path can restore
             * the standard binding — the stale-field hazard 5ad333c closed
             * from the other side (control applies route at the live
             * session, never at the write-back fields).
             */
            val writeBackStandardFields: Boolean,
        ) : RecordingBindStep

        /** Reattach both observations to the session the bind just produced. */
        data object AttachObservations : RecordingBindStep

        /** Schedule the deferred controls-apply ladder ([ControlsApplyPolicy]'s rungs). */
        data object SchedulePostBindApplies : RecordingBindStep
    }

    data class RecordingBindPlan(val steps: List<RecordingBindStep>)

    /**
     * The recording bind plan — the settle choreography 5ad333c taught the
     * recording seam, as an ordered step list: detach the outgoing
     * session's observations, bind (no write-back), reattach to the fresh
     * session, schedule the deferred applies. [previewBound]/
     * [imageCaptureBound]/[imageAnalysisBound] are the standard binding
     * demand — the use cases currently standing; each rides the ladder,
     * VideoCapture rides every rung, and the ladder falls to VideoCapture
     * alone when the standard set cannot bind alongside it.
     */
    fun recordingBindPlan(
        previewBound: Boolean,
        imageCaptureBound: Boolean,
        imageAnalysisBound: Boolean,
    ): RecordingBindPlan {
        val base = listOfNotNull(
            if (previewBound) BindSlot.Preview else null,
            if (imageCaptureBound) BindSlot.ImageCapture else null,
            if (imageAnalysisBound) BindSlot.ImageAnalysis else null,
        )
        return RecordingBindPlan(
            listOf(
                RecordingBindStep.DetachObservations,
                RecordingBindStep.BindRecordingSession(
                    attempts = orderedCombinations(base, BindSlot.VideoCapture),
                    writeBackStandardFields = false,
                ),
                RecordingBindStep.AttachObservations,
                RecordingBindStep.SchedulePostBindApplies,
            )
        )
    }

    // ── the combination ladder (canonical home; the service keeps a delegating camera-package re-export) ──

    /**
     * The combination ladder, largest first: every subset of [base]
     * (optionally plus [extra]), so preview-start and recording-start fall
     * back identically on constraint-limited devices. The recording plan's
     * attempts are this ladder in slot terms, which is why its canonical
     * home is here; the service's use-case instances resolve per attempt.
     */
    fun <T> orderedCombinations(base: List<T>, extra: T?): List<List<T>> =
        buildList {
            for (size in base.size downTo 0) {
                subsetsOf(base, size).forEach { subset ->
                    add(if (extra != null) subset + extra else subset)
                }
            }
        }

    private fun <T> subsetsOf(items: List<T>, size: Int): List<List<T>> {
        val result = mutableListOf<List<T>>()
        fun recurse(start: Int, current: MutableList<T>) {
            if (current.size == size) {
                result += current.toList()
                return
            }
            for (i in start..items.lastIndex) {
                current += items[i]
                recurse(i + 1, current)
                current.removeAt(current.lastIndex)
            }
        }
        recurse(0, mutableListOf())
        return result
    }
}
