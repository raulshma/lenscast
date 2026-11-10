package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.TimelapseAssembler
import com.raulshma.lenscast.capture.TimelapseComposer

/**
 * The manual timelapse assembly run's state machine:
 *
 * - [Idle] — no run has started (or the last run's [Done] was the final
 *   state until the next run begins).
 * - [Running] — an assemble pass is on the composer's worker thread; the
 *   button is disabled and shows the assembling label.
 * - [Done] — the pass finished; the already-sealed
 *   [TimelapseComposer.Result] carries the outcome.
 *
 * The CaptureViewModel is the only writer (one flow for the capture
 * screen's timelapse section); the screen reads it and resolves the
 * result's message through [timelapseMessage]. Running is re-asserted at
 * the start of every assemble run, so a previous run's Done never leaks
 * into the next one.
 */
sealed interface TimelapseState {
    data object Idle : TimelapseState

    data object Running : TimelapseState

    data class Done(val result: TimelapseComposer.Result) : TimelapseState
}

/**
 * The user-facing timelapse outcome as data — the pure half of the result →
 * message mapping, pinned by test. The screen resolves each variant onto its
 * [com.raulshma.lenscast.R.string] resource with these exact fields (the
 * same resources and text the two-flow implementation rendered); no
 * user-facing prose lives here.
 */
sealed interface TimelapseMessage {
    /** `capture_timelapse_saved` with the output name and frame count. */
    data class Saved(val fileName: String, val frameCount: Int) : TimelapseMessage

    /** `capture_timelapse_need_photos` with the assembler's floor. */
    data class NeedSources(val minSources: Int) : TimelapseMessage

    /** `capture_timelapse_unreadable` with the composer's detail line. */
    data class Unreadable(val detail: String) : TimelapseMessage

    /** `capture_timelapse_failed` — every other outcome. */
    data object Failed : TimelapseMessage
}

/**
 * The [TimelapseComposer.Result] → [TimelapseMessage] mapping: Saved and the
 * two reasons with their own resources map onto their message shapes, every
 * other reason folds to [TimelapseMessage.Failed]. Pure so the screen keeps
 * no result-matching logic of its own.
 */
fun timelapseMessage(result: TimelapseComposer.Result): TimelapseMessage = when (result) {
    is TimelapseComposer.Result.Saved ->
        TimelapseMessage.Saved(result.fileName, result.frameCount)
    is TimelapseComposer.Result.NotSaved -> when (result.reason) {
        TimelapseComposer.Reason.NOT_ENOUGH_SOURCES ->
            TimelapseMessage.NeedSources(TimelapseAssembler.MIN_SOURCES)
        TimelapseComposer.Reason.UNREADABLE_SOURCES ->
            TimelapseMessage.Unreadable(result.detail)
        else -> TimelapseMessage.Failed
    }
}
