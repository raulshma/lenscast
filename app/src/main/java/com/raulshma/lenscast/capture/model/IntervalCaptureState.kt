package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.IntervalCapturePolicy

/**
 * The interval-capture run state as the capture screen renders it, folding
 * the WorkManager snapshot flow's "not yet observed" case into its own
 * variant instead of collapsing it onto [Stopped].
 *
 * - [Unknown] — no WorkManager snapshot has been observed yet (the first
 *   `getWorkInfosForUniqueWork` read is still in flight — the first frames
 *   after entering the screen). For the user this means the series' truth
 *   is momentarily unobservable, not that it is stopped: the Start
 *   affordance renders disabled-with-progress so it cannot be tapped while
 *   a start may be mid-enqueue.
 * - [Running] — WorkManager reports unfinished unique work for the series;
 *   [snapshot] carries the confirmed status (completed captures ride along
 *   for readers that want them).
 * - [Stopped] — a snapshot was observed and reports no unfinished work.
 *
 * The CaptureViewModel is the only writer (one map off the scheduler's
 * `observeStatus` flow); the capture screen is the reader.
 * `isIntervalRunning` stays derived from this state ([Running] → true), so
 * boolean consumers see exactly the pre-tri-state behavior.
 */
sealed interface IntervalCaptureState {
    data object Unknown : IntervalCaptureState

    data class Running(val snapshot: IntervalCapturePolicy.StatusSnapshot) : IntervalCaptureState

    data object Stopped : IntervalCaptureState
}
