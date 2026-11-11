import { describe, expect, it } from 'vitest'
import { recordingPhaseKind } from './recordingPhase'

describe('recordingPhaseKind', () => {
  it('maps the 2×2 flag table, recording winning over scheduled', () => {
    expect(recordingPhaseKind(false, false)).toBe('idle')
    expect(recordingPhaseKind(true, false)).toBe('recording')
    expect(recordingPhaseKind(false, true)).toBe('scheduled')
    // Both-true is unreachable from a healthy server (RecordingController's
    // state is exclusive), but the verdict pins recording as the winner —
    // same priority the stop-button label already applies.
    expect(recordingPhaseKind(true, true)).toBe('recording')
  })
})
