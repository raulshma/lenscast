package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.camera.model.BindChoreography.BindSlot
import com.raulshma.lenscast.camera.model.BindChoreography.ErrorFollowUp
import com.raulshma.lenscast.camera.model.BindChoreography.ErrorMessage
import com.raulshma.lenscast.camera.model.BindChoreography.ObservationAttach
import com.raulshma.lenscast.camera.model.BindChoreography.OpenStatePlan
import com.raulshma.lenscast.camera.model.BindChoreography.PostBindRung
import com.raulshma.lenscast.camera.model.BindChoreography.RecordingBindStep
import com.raulshma.lenscast.camera.model.BindChoreography.RungAction
import com.raulshma.lenscast.camera.model.CameraErrorPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure bind choreography: the observation attach identity guard,
 * the camera-open observer's transition decision, the post-bind rung
 * cancellation, and the recording bind's detach/rebind/reattach ladder —
 * the sequencing decisions whose inline homes scattered across the
 * observer and the session producers until they moved here.
 */
class BindChoreographyTest {

    private fun openPlan(
        errorCode: Int? = null,
        typeOpen: Boolean = false,
        rebindAttempts: Int = 0,
        readyAlreadyPublished: Boolean = false,
        previouslyObserved: Boolean = false,
        cameraIsLive: Boolean = true,
        isStalePreBindError: Boolean = false,
    ) = BindChoreography.openStatePlan(
        errorCode = errorCode,
        typeOpen = typeOpen,
        rebindAttempts = rebindAttempts,
        readyAlreadyPublished = readyAlreadyPublished,
        previouslyObserved = previouslyObserved,
        cameraIsLive = cameraIsLive,
        isStalePreBindError = isStalePreBindError,
    )

    // ── the observation attach identity guard ──

    @Test
    fun `a current observer over the live camera attaches`() {
        assertEquals(
            ObservationAttach.Attach,
            BindChoreography.observationAttachPlan(observerIsCurrent = true, cameraIsLive = true),
        )
    }

    @Test
    fun `a stale attach is dropped - the fast rebind replaced observer or camera`() {
        // Either half of the identity going stale is enough: the posted
        // attach belongs to a session that no longer exists.
        assertEquals(
            ObservationAttach.DropStaleAttach,
            BindChoreography.observationAttachPlan(observerIsCurrent = false, cameraIsLive = true),
        )
        assertEquals(
            ObservationAttach.DropStaleAttach,
            BindChoreography.observationAttachPlan(observerIsCurrent = true, cameraIsLive = false),
        )
        assertEquals(
            ObservationAttach.DropStaleAttach,
            BindChoreography.observationAttachPlan(observerIsCurrent = false, cameraIsLive = false),
        )
    }

    // ── the camera-open observer's transition decision ──

    @Test
    fun `a recoverable error spends one attempt and posts the rebind`() {
        // STREAM_CONFIG: the bounded lever. The plan says post — the rebind
        // never runs inline inside the LiveData dispatch.
        val plan = openPlan(errorCode = CameraErrorPolicy.CODE_STREAM_CONFIG, rebindAttempts = 0)
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.REBIND, plan.verdict)
        assertEquals(CameraErrorPolicy.CODE_STREAM_CONFIG, plan.errorCode)
        assertEquals(ErrorMessage.Code(CameraErrorPolicy.CODE_STREAM_CONFIG), plan.messageKind)
        assertEquals(ErrorFollowUp.PostRebind, plan.followUp)
    }

    @Test
    fun `budget exhaustion reports instead of rebinding`() {
        // The third failed rebind hands the error back to report-only — the
        // plan's follow-up drops to ReportOnly and the counter is spent
        // nothing further (CameraErrorPolicy's verdict, the choreography's
        // execution).
        val plan = openPlan(
            errorCode = CameraErrorPolicy.CODE_STREAM_CONFIG,
            rebindAttempts = CameraErrorPolicy.MAX_REBIND_ATTEMPTS,
        )
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.CAMERAX_HANDLES, plan.verdict)
        assertEquals(ErrorFollowUp.ReportOnly, plan.followUp)
    }

    @Test
    fun `the while-in-use denial reports the dedicated message with no lever spent`() {
        val plan = openPlan(errorCode = CameraErrorPolicy.CODE_CAMERA_DISABLED, rebindAttempts = 0)
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.POLICY_DENIED, plan.verdict)
        assertEquals(ErrorMessage.DisabledByPolicy, plan.messageKind)
        assertEquals(ErrorFollowUp.ReportOnly, plan.followUp)
    }

    @Test
    fun `the fatal error reports reboot-required and spends nothing`() {
        val plan = openPlan(errorCode = CameraErrorPolicy.CODE_CAMERA_FATAL_ERROR, rebindAttempts = 0)
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.REBOOT_REQUIRED, plan.verdict)
        assertEquals(ErrorMessage.RebootRequired, plan.messageKind)
        assertEquals(ErrorFollowUp.ReportOnly, plan.followUp)
    }

    @Test
    fun `the removed camera registers the camera-added wait without spending an attempt`() {
        val plan = openPlan(errorCode = CameraErrorPolicy.CODE_CAMERA_REMOVED, rebindAttempts = 1)
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.WAIT_CAMERA_ADDED, plan.verdict)
        assertEquals(ErrorFollowUp.WaitCameraAdded, plan.followUp)
    }

    @Test
    fun `the error verdict stays CameraErrorPolicy's over the whole code table`() {
        // Composition pin: the choreography re-derives nothing — for every
        // code (known, and one this build does not know) and every budget
        // state, the plan's verdict is the policy's.
        val codes = listOf(
            CameraErrorPolicy.CODE_MAX_CAMERAS_IN_USE,
            CameraErrorPolicy.CODE_CAMERA_IN_USE,
            CameraErrorPolicy.CODE_OTHER_RECOVERABLE_ERROR,
            CameraErrorPolicy.CODE_STREAM_CONFIG,
            CameraErrorPolicy.CODE_CAMERA_DISABLED,
            CameraErrorPolicy.CODE_CAMERA_FATAL_ERROR,
            CameraErrorPolicy.CODE_DO_NOT_DISTURB_MODE_ENABLED,
            CameraErrorPolicy.CODE_CAMERA_REMOVED,
            42, // unknown
        )
        for (code in codes) {
            for (attempts in listOf(0, CameraErrorPolicy.MAX_REBIND_ATTEMPTS)) {
                val plan = openPlan(errorCode = code, rebindAttempts = attempts)
                assertEquals(
                    "code=$code attempts=$attempts",
                    CameraErrorPolicy.decide(code, attempts),
                    (plan as OpenStatePlan.ErrorObservation).verdict,
                )
            }
        }
    }

    @Test
    fun `OPEN resets the rebind budget and claims Ready`() {
        // An open camera is the only receipt that the budget restarts —
        // reset is the plan's directive; the service performs the swap.
        val plan = openPlan(typeOpen = true, rebindAttempts = 2, readyAlreadyPublished = false)
        assertEquals(
            OpenStatePlan.StateObservation(
                openObserved = true,
                resetRebindAttempts = true,
                publishReady = true,
                reassertControls = true,
            ),
            plan,
        )
    }

    @Test
    fun `a first observed OPEN re-asserts the controls`() {
        // The event-driven rung: the open fact was not standing and OPEN
        // arrived — the rising edge. This is the camera that opens LATER than
        // its bind, past both timed rungs; the observed open is the only
        // receipt that the session can take controls at all.
        val plan = openPlan(typeOpen = true, previouslyObserved = false) as OpenStatePlan.StateObservation
        assertTrue(plan.reassertControls)
    }

    @Test
    fun `a re-stated OPEN does not re-assert the controls`() {
        // LiveData re-delivers the current value on attach and on lifecycle
        // moves: an OPEN the observer already counted is not an edge. Without
        // this every re-attach would fire a force-focus apply that cancels a
        // deliberate tap-to-focus.
        val plan = openPlan(typeOpen = true, previouslyObserved = true) as OpenStatePlan.StateObservation
        assertEquals(false, plan.reassertControls)
        // The fact still stands and the budget still reads as reset.
        assertTrue(plan.openObserved)
        assertTrue(plan.resetRebindAttempts)
    }

    @Test
    fun `the re-assert is the edge alone - independent of the Ready claim`() {
        // The bind already claimed Ready, so nothing is published — and the
        // first observed OPEN still re-asserts: the claim is bookkeeping,
        // the controls need the real open.
        val plan = openPlan(typeOpen = true, readyAlreadyPublished = true, previouslyObserved = false)
            as OpenStatePlan.StateObservation
        assertEquals(false, plan.publishReady)
        assertTrue(plan.reassertControls)
    }

    @Test
    fun `an OPEN observed for a camera that is no longer live does not re-assert`() {
        // The stale-generation half of the guard, as data: a late dispatch
        // from a camera a newer bind replaced must not drive its force-focus
        // apply onto the session that replaced it. The open fact itself is
        // the emission's and still stands.
        val plan = openPlan(typeOpen = true, previouslyObserved = false, cameraIsLive = false)
            as OpenStatePlan.StateObservation
        assertEquals(false, plan.reassertControls)
        assertTrue(plan.openObserved)
        assertTrue(plan.resetRebindAttempts)
    }

    @Test
    fun `the re-assert needs the edge and the live camera together`() {
        assertFalse(
            (openPlan(typeOpen = true, previouslyObserved = true, cameraIsLive = false)
                as OpenStatePlan.StateObservation).reassertControls,
        )
        assertTrue(
            (openPlan(typeOpen = true, previouslyObserved = false, cameraIsLive = true)
                as OpenStatePlan.StateObservation).reassertControls,
        )
    }

    @Test
    fun `OPEN resets the budget even from a fresh counter`() {
        // The reset is unconditional on OPEN (the swap just reads zero); the
        // reset log is the service's getAndSet(0) > 0 concern.
        val plan = openPlan(typeOpen = true, rebindAttempts = 0, readyAlreadyPublished = true)
        assertTrue(plan is OpenStatePlan.StateObservation)
        plan as OpenStatePlan.StateObservation
        assertTrue(plan.resetRebindAttempts)
        assertTrue(plan.openObserved)
        // The bind's claim already stands — the observed open must not
        // republish a fresher state.
        assertEquals(false, plan.publishReady)
    }

    @Test
    fun `a non-open transition withdraws the open fact and resets nothing`() {
        // OPENING/PENDING_OPEN/CLOSING/CLOSED emissions: the confirmation
        // dies, the budget is untouched, no state is published, nothing is
        // re-asserted.
        assertEquals(
            OpenStatePlan.StateObservation(
                openObserved = false,
                resetRebindAttempts = false,
                publishReady = false,
                reassertControls = false,
            ),
            openPlan(typeOpen = false, rebindAttempts = 2, readyAlreadyPublished = false),
        )
    }

    @Test
    fun `stale pre-bind error returns IgnoreStaleError`() {
        assertEquals(
            OpenStatePlan.IgnoreStaleError,
            openPlan(
                errorCode = CameraErrorPolicy.CODE_CAMERA_DISABLED,
                isStalePreBindError = true,
            ),
        )
    }

    @Test
    fun `non-stale error returns ErrorObservation even for policy denied`() {
        val plan = openPlan(
            errorCode = CameraErrorPolicy.CODE_CAMERA_DISABLED,
            isStalePreBindError = false,
        )
        assertTrue(plan is OpenStatePlan.ErrorObservation)
        plan as OpenStatePlan.ErrorObservation
        assertEquals(Verdict.POLICY_DENIED, plan.verdict)
    }

    @Test
    fun `non-error state ignores isStalePreBindError flag and returns StateObservation`() {
        assertEquals(
            OpenStatePlan.StateObservation(
                openObserved = true,
                resetRebindAttempts = true,
                publishReady = true,
                reassertControls = true,
            ),
            openPlan(
                errorCode = null,
                typeOpen = true,
                isStalePreBindError = true,
            ),
        )
    }

    // ── the post-bind controls-apply rungs ──

    @Test
    fun `rungs carry the schedule-time generation over ControlsApplyPolicy's delays`() {
        // Composition pin: one rung per policy delay, in fire order, each
        // bound to the generation captured when the bind scheduled them.
        assertEquals(
            listOf(
                PostBindRung(delayMs = 400L, boundGeneration = 7),
                PostBindRung(delayMs = 1500L, boundGeneration = 7),
            ),
            BindChoreography.postBindRungs(boundGeneration = 7, delaysMs = ControlsApplyPolicy.applyDelaysMs()),
        )
    }

    @Test
    fun `a rung landing after a newer bind cancels`() {
        // The stale-generation guard as data: the new bind schedules its own
        // rungs, and the old rung's force-focus reapply must not cancel a
        // tap-to-focus on the session that replaced it.
        assertEquals(
            RungAction.CancelStaleGeneration,
            BindChoreography.rungAction(PostBindRung(delayMs = 400L, boundGeneration = 1), bindGenerationNow = 2),
        )
    }

    @Test
    fun `a rung on its own generation applies`() {
        assertEquals(
            RungAction.ApplyControls,
            BindChoreography.rungAction(PostBindRung(delayMs = 1500L, boundGeneration = 3), bindGenerationNow = 3),
        )
    }

    // ── the recording bind ladder ──

    @Test
    fun `the ladder is detach, bind, reattach, schedule - and binds without write-back`() {
        // The 5ad333c order, pinned: the preview-side fields must keep their
        // values so unbindRecording can restore the standard binding, and
        // the fresh session must get the settle choreography.
        val plan = BindChoreography.recordingBindPlan(
            previewBound = true,
            imageCaptureBound = true,
            imageAnalysisBound = true,
        )
        assertEquals(4, plan.steps.size)
        assertEquals(RecordingBindStep.DetachObservations, plan.steps[0])
        val bind = plan.steps[1] as RecordingBindStep.BindRecordingSession
        assertEquals(false, bind.writeBackStandardFields)
        assertEquals(RecordingBindStep.AttachObservations, plan.steps[2])
        assertEquals(RecordingBindStep.SchedulePostBindApplies, plan.steps[3])
    }

    @Test
    fun `the full standard set ladders largest-first with video riding every attempt`() {
        val attempts = BindChoreography.recordingBindPlan(
            previewBound = true,
            imageCaptureBound = true,
            imageAnalysisBound = true,
        ).steps.let { steps -> (steps[1] as RecordingBindStep.BindRecordingSession).attempts }

        assertEquals(
            listOf(
                listOf(BindSlot.Preview, BindSlot.ImageCapture, BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.Preview, BindSlot.ImageCapture, BindSlot.VideoCapture),
                listOf(BindSlot.Preview, BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.ImageCapture, BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.Preview, BindSlot.VideoCapture),
                listOf(BindSlot.ImageCapture, BindSlot.VideoCapture),
                listOf(BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.VideoCapture),
            ),
            attempts,
        )
    }

    @Test
    fun `a missing standard use case drops from every attempt`() {
        val attempts = BindChoreography.recordingBindPlan(
            previewBound = false,
            imageCaptureBound = true,
            imageAnalysisBound = true,
        ).steps.let { steps -> (steps[1] as RecordingBindStep.BindRecordingSession).attempts }

        assertEquals(
            listOf(
                listOf(BindSlot.ImageCapture, BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.ImageCapture, BindSlot.VideoCapture),
                listOf(BindSlot.ImageAnalysis, BindSlot.VideoCapture),
                listOf(BindSlot.VideoCapture),
            ),
            attempts,
        )
        assertTrue(attempts.all { BindSlot.Preview !in it })
    }

    @Test
    fun `no standard set binds the recording alone`() {
        val attempts = BindChoreography.recordingBindPlan(
            previewBound = false,
            imageCaptureBound = false,
            imageAnalysisBound = false,
        ).steps.let { steps -> (steps[1] as RecordingBindStep.BindRecordingSession).attempts }

        assertEquals(listOf(listOf(BindSlot.VideoCapture)), attempts)
    }

    @Test
    fun `the recording attempts are the combination math over the same slots`() {
        // Composition pin: the plan's ladder is the service's binding math
        // (orderedCombinations) in slot terms — re-derived nowhere.
        for (preview in listOf(false, true)) {
            for (capture in listOf(false, true)) {
                for (analysis in listOf(false, true)) {
                    val base = listOfNotNull(
                        if (preview) BindSlot.Preview else null,
                        if (capture) BindSlot.ImageCapture else null,
                        if (analysis) BindSlot.ImageAnalysis else null,
                    )
                    assertEquals(
                        "preview=$preview capture=$capture analysis=$analysis",
                        BindChoreography.orderedCombinations(base, BindSlot.VideoCapture),
                        BindChoreography.recordingBindPlan(preview, capture, analysis)
                            .steps.let { steps -> (steps[1] as RecordingBindStep.BindRecordingSession).attempts },
                    )
                }
            }
        }
    }
}
