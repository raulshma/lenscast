import type { DetectionEvent } from '../types'

// Pure parsing/scheduling core for the detection-event SSE stream — the
// pollLadder pattern: no EventSource, timers, or Solid here, only injected
// primitives, so vitest can drive every transition without a browser.
// The Solid wiring lives in useEventStream.ts.

/**
 * Parse one SSE `data:` frame payload into a DetectionEvent. Accepts either
 * the bare JSON text EventSource hands to `onmessage` or a raw frame chunk
 * containing `data:` lines (comment lines like `: ping` are ignored), and
 * returns null for anything that is not a JSON object.
 */
export function parseEventData(text: string): DetectionEvent | null {
  const dataLines = text
    .split('\n')
    .map((line) => line.replace(/\r$/, ''))
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice('data:'.length).replace(/^ /, ''))
  const payload = dataLines.length > 0 ? dataLines.join('\n') : text.trim()
  if (!payload) return null
  try {
    const parsed: unknown = JSON.parse(payload)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as DetectionEvent
    }
    return null
  } catch {
    return null
  }
}

/** Stable identity for dedupe: the server id when present, else the timestamp. */
export function eventKey(event: DetectionEvent): string {
  if (event.id) return `id:${event.id}`
  return `t:${event.timestampMs}`
}

/**
 * Merge replayed + live events into one newest-first list: dedupe by
 * eventKey, sort by timestampMs (stable for equal stamps), cap to `cap`.
 */
export function mergeEvents(existing: DetectionEvent[], incoming: DetectionEvent[], cap: number): DetectionEvent[] {
  const byKey = new Map<string, DetectionEvent>()
  for (const event of [...incoming, ...existing]) {
    byKey.set(eventKey(event), event)
  }
  const merged = [...byKey.values()]
  merged.sort((a, b) => {
    if (b.timestampMs !== a.timestampMs) return b.timestampMs - a.timestampMs
    return eventKey(a).localeCompare(eventKey(b))
  })
  return merged.length > cap ? merged.slice(0, cap) : merged
}

export type StreamFallbackState = 'live' | 'degraded'

/**
 * The SSE-error fallback state machine: while `degraded` the consumer polls,
 * and once `retryAfterMs` has passed since the error it may take exactly one
 * SSE reconnect attempt (polling pauses during the attempt; another error
 * re-arms the cycle). `now` is injected on every call — no internal clock.
 */
export interface StreamFallback {
  markOpen(now: number): void
  markError(now: number): void
  state(): StreamFallbackState
  /** True while the consumer should keep its poll timer running. */
  shouldPoll(): boolean
  /**
   * True when a reconnect attempt is due; consumes the due flag so the
   * caller attempts once and waits for open/error to re-arm the machine.
   */
  takeReconnect(now: number): boolean
}

export function createStreamFallback(options: { retryAfterMs: number }): StreamFallback {
  let currentState: StreamFallbackState = 'live'
  let erroredAt: number | null = null

  return {
    markOpen() {
      currentState = 'live'
      erroredAt = null
    },
    markError(now) {
      currentState = 'degraded'
      erroredAt = now
    },
    state: () => currentState,
    shouldPoll: () => currentState === 'degraded',
    takeReconnect(now) {
      if (currentState !== 'degraded' || erroredAt === null) return false
      if (now - erroredAt < options.retryAfterMs) return false
      erroredAt = null
      return true
    },
  }
}
