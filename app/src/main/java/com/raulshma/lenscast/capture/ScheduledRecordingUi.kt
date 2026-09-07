package com.raulshma.lenscast.capture

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
