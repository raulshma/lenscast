/**
 * The recording lane's phase as one derived verdict over the two flags
 * (web/src/hooks/useAppState.ts). Recording wins over scheduled — the
 * server's RecordingController state is exclusive (Idle/Scheduled/
 * Recording) and the stop-button label already prefers recording
 * (RecordingCard's `isRecording ? stop : stopScheduled`); both-true is
 * unreachable from a healthy server but the verdict must stay total over
 * the wire's independent optional fields.
 *
 * Payloads stay in their own signals (recordingElapsed,
 * scheduledStartTimeMs); the optimistic start arms exactly one of the two
 * flags by config.startTimeMs, and the optimistic stop clears both but
 * deliberately leaves recordingElapsed stale until the next poll.
 */
export type RecordingPhaseKind = 'idle' | 'scheduled' | 'recording'

export function recordingPhaseKind(isRecording: boolean, isScheduled: boolean): RecordingPhaseKind {
  if (isRecording) return 'recording'
  if (isScheduled) return 'scheduled'
  return 'idle'
}
