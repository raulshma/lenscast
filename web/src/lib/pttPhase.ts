/**
 * The push-to-talk hold lifecycle (web/src/components/StreamPreview.tsx):
 * idle → arming (pressed; WS created, getUserMedia awaited) → holding
 * (audio graph up) → draining (released; cleanup awaited) → idle, with
 * teardown (unmount) landing idle from anywhere. The WS-vs-fallback
 * transport is deliberately NOT a phase — sendPcm re-decides it per chunk
 * from the socket's readyState and onclose flips it mid-hold.
 *
 * `pttBusy` is the press-latch set: exactly {arming, holding, draining} —
 * the set and time-intervals where the old boolean latch was true (the
 * drain keeps it true until cleanup completes).
 */
export type PttPhase = 'idle' | 'arming' | 'holding' | 'draining'
export type PttEvent = 'press' | 'audioUp' | 'release' | 'drained' | 'teardown'

/** True for every phase where a second press must be ignored (the press latch). */
export function pttBusy(phase: PttPhase): boolean {
  return phase !== 'idle'
}

/**
 * The hold machine's single transition step. Unnatural events are no-ops
 * (a `press` mid-hold, a `drained` outside a drain) — the component's hold
 * generation guard plus this closed transition table keep stale async
 * continuations from stomping a newer hold's phase.
 */
export function pttTransition(phase: PttPhase, event: PttEvent): PttPhase {
  switch (event) {
    case 'press': return phase === 'idle' ? 'arming' : phase
    case 'audioUp': return phase === 'arming' ? 'holding' : phase
    case 'release': return phase === 'arming' || phase === 'holding' ? 'draining' : phase
    case 'drained': return phase === 'draining' ? 'idle' : phase
    case 'teardown': return 'idle'
  }
}
