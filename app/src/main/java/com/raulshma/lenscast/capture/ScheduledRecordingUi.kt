package com.raulshma.lenscast.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The capture screen's view of an armed schedule, derived purely from the
 * controller's [RecordingState] — the only public truth about recording.
 * No screen keeps a shadow "scheduled start" draft that can drift from the
 * armed job: everything the schedule row renders comes from here.
 */
data class ScheduledUi(
    /** The armed start instant, formatted into the "Start: HH:mm" label. */
    val startAtMs: Long,
    /** The clear (trash) button may cancel the armed schedule. */
    val canCancel: Boolean,
    /** The main button is arming ("Schedule") rather than immediate ("Start Now"). */
    val isScheduled: Boolean,
)

/** The time-picker button's prefix, before the formatted start time. */
const val SCHEDULED_START_PREFIX = "Start: "

/** The time-picker button's text when no start is pending. */
const val SCHEDULED_NO_START_LABEL = "Set Start Time"

/** The main record button's text when a start is pending (armed or drafted). */
const val SCHEDULED_BUTTON_LABEL = "Schedule"

/** The main record button's text when nothing is pending. */
const val SCHEDULED_BUTTON_START_NOW_LABEL = "Start Now"

/**
 * The whole schedule row as rendered: the time-picker button's label, the
 * trash button's visibility, the main button's verdict, and the pending
 * start instant. Both sources feed it — the controller's [RecordingState]
 * (the armed schedule's truth) and the config draft's `startTimeMs` (the
 * picked-but-not-yet-armed input) — with the armed schedule winning the
 * merge.
 */
data class ScheduleRowUi(
    /** The time-picker button's text: "Start: HH:mm" or "Set Start Time". */
    val label: String,
    /** The clear (trash) button shows: an armed schedule or a draft pick exists. */
    val canCancel: Boolean,
    /** The main button arms a schedule ("Schedule") or starts immediately ("Start Now"). */
    val buttonLabel: String,
    /** The pending start instant — armed if present, else the draft pick. */
    val pendingStartMs: Long?,
)

/**
 * The state → schedule-row mapping. Idle and Recording expose no armed
 * schedule (the recording UI replaces the row), a Scheduled state exposes
 * the armed start with cancel available.
 */
fun scheduledUiModel(state: RecordingState): ScheduledUi? = when (state) {
    is RecordingState.Scheduled -> ScheduledUi(
        startAtMs = state.startAtMs,
        canCancel = true,
        isScheduled = true,
    )
    RecordingState.Idle -> null
    is RecordingState.Recording -> null
}

/**
 * The full row for the capture screen: folds the armed-schedule view
 * ([scheduledUiModel]) with the draft pick into the four verdicts the screen
 * renders — label, canCancel, buttonLabel, pendingStartMs — so no caller
 * re-derives any of them inline. No clock input: every verdict is presence-
 * or instant-based — the label renders the pending start's absolute
 * wall-clock time, and a stale (already-past) pending start renders exactly
 * as always; whether an armed start is overdue is the controller's business,
 * not the row's.
 */
fun scheduleRowUi(state: RecordingState, draftStartMs: Long?): ScheduleRowUi {
    val armed = scheduledUiModel(state)
    val pendingStartMs = armed?.startAtMs ?: draftStartMs
    return ScheduleRowUi(
        label = pendingStartMs?.let(::formatStartLabel) ?: SCHEDULED_NO_START_LABEL,
        canCancel = armed?.canCancel == true || draftStartMs != null,
        buttonLabel = if (armed?.isScheduled == true || draftStartMs != null) {
            SCHEDULED_BUTTON_LABEL
        } else {
            SCHEDULED_BUTTON_START_NOW_LABEL
        },
        pendingStartMs = pendingStartMs,
    )
}

/**
 * The "Start: HH:mm" text. The [SimpleDateFormat] is constructed explicitly
 * with [Locale.getDefault] — the device locale on purpose, matching what the
 * capture screen always rendered for this user-facing label; a locale-less
 * format would silently platform-default and a hard-pinned one would change
 * today's text.
 */
private fun formatStartLabel(startAtMs: Long): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "$SCHEDULED_START_PREFIX${format.format(Date(startAtMs))}"
}
