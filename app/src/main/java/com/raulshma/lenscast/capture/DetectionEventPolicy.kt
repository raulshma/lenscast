package com.raulshma.lenscast.capture

/**
 * Pure event-dispatch verdicts for the [DetectionCoordinator]: which actions a
 * detection event (motion or sound) produces given the armed state and the
 * recording toggle. Event-sequence-tested; the coordinator keeps the clocks,
 * the recording controller handle, and the dispatch.
 */
object DetectionEventPolicy {

    /**
     * The event's recording action. A motion-triggered recording is bounded
     * (auto-stops after the post-roll), starts only from Idle, and never
     * restarts over a live recording — restart churn would finalize an MP4
     * per event; a live clip simply keeps rolling through the event cluster.
     * A sound event records only when [soundRecordingEnabled] is on, under
     * the same bounded, never-restart-over-live rules.
     */
    fun recordingAction(
        motionRecordingEnabled: Boolean,
        armed: Boolean,
        recordingActive: Boolean,
        soundRecordingEnabled: Boolean = false,
        isSound: Boolean = false,
    ): RecordingAction {
        val triggerEnabled = if (isSound) soundRecordingEnabled else motionRecordingEnabled
        return when {
            !triggerEnabled || !armed -> RecordingAction.NONE
            recordingActive -> RecordingAction.KEEP_ROLLING
            else -> RecordingAction.START
        }
    }

    enum class RecordingAction { NONE, START, KEEP_ROLLING }

    /**
     * Whether the legacy auto-photo fires for this event: motion only (a
     * sound event has no frame to attribute), only when motion recording is
     * off (a photo mid-exclusive-recording bind is not schedulable), and
     * only when the event is armed.
     */
    fun shouldAutoPhoto(
        motionRecordingEnabled: Boolean,
        armed: Boolean,
        isSound: Boolean = false,
    ): Boolean = !isSound && !motionRecordingEnabled && armed
}
