import { describe, expect, it } from 'vitest'
import type { DetectionEvent } from '../types'
import { createStreamFallback, eventKey, mergeEvents, parseEventData } from './eventStreamCore'

function event(id: string, timestampMs: number): DetectionEvent {
  return { id, type: 'motion', source: 'test', timestampMs, dispatchedActions: [], zones: [] }
}

describe('parseEventData', () => {
  it('parses the bare JSON payload EventSource delivers to onmessage', () => {
    const parsed = parseEventData('{"id":"e1","type":"motion","timestampMs":100}')
    expect(parsed).not.toBeNull()
    expect(parsed!.id).toBe('e1')
  })

  it('extracts data lines from a raw SSE frame and ignores comments', () => {
    const parsed = parseEventData(': ping\ndata: {"id":"e2","timestampMs":200}\n\n')
    expect(parsed).not.toBeNull()
    expect(parsed!.id).toBe('e2')
  })

  it('joins split multi-line data payloads', () => {
    const parsed = parseEventData('data: {"id":"e3",\ndata: "timestampMs":300}\n')
    expect(parsed).not.toBeNull()
    expect(parsed!.id).toBe('e3')
  })

  it('returns null for comments only, blank text, and non-object JSON', () => {
    expect(parseEventData(': ping\n\n')).toBeNull()
    expect(parseEventData('')).toBeNull()
    expect(parseEventData('42')).toBeNull()
    expect(parseEventData('"text"')).toBeNull()
    expect(parseEventData('[1,2]')).toBeNull()
  })

  it('returns null for malformed JSON instead of throwing', () => {
    expect(parseEventData('data: {not json}')).toBeNull()
  })
})

describe('mergeEvents dedupe/cap', () => {
  it('keeps the newest first and caps the buffer', () => {
    const merged = mergeEvents([], [event('a', 100), event('b', 300), event('c', 200)], 2)
    expect(merged.map((e) => e.id)).toEqual(['b', 'c'])
  })

  it('dedupes by id keeping the newest occurrence', () => {
    const merged = mergeEvents([event('a', 100)], [event('a', 100), event('b', 50)], 10)
    expect(merged.map((e) => e.id)).toEqual(['a', 'b'])
    expect(merged).toHaveLength(2)
  })

  it('falls back to the timestamp as identity when the id is empty', () => {
    expect(eventKey({ ...event('', 100) })).toBe('t:100')
    const merged = mergeEvents([], [event('', 100), event('', 100), event('', 90)], 10)
    expect(merged).toHaveLength(2)
  })

  it('replays keep their position when older than the live tail', () => {
    const merged = mergeEvents([event('live', 500)], [event('replayOld', 100), event('replayNew', 600)], 10)
    expect(merged.map((e) => e.id)).toEqual(['replayNew', 'live', 'replayOld'])
  })
})

describe('createStreamFallback', () => {
  it('starts live: no polling, no reconnect', () => {
    const f = createStreamFallback({ retryAfterMs: 60_000 })
    expect(f.state()).toBe('live')
    expect(f.shouldPoll()).toBe(false)
    expect(f.takeReconnect(1000)).toBe(false)
  })

  it('degrades on error: polls immediately, reconnects only after the retry delay', () => {
    const f = createStreamFallback({ retryAfterMs: 60_000 })
    f.markError(10_000)
    expect(f.state()).toBe('degraded')
    expect(f.shouldPoll()).toBe(true)
    expect(f.takeReconnect(10_000 + 59_999)).toBe(false)
    expect(f.takeReconnect(10_000 + 60_000)).toBe(true)
  })

  it('consumes the reconnect attempt so the caller tries exactly once', () => {
    const f = createStreamFallback({ retryAfterMs: 60_000 })
    f.markError(0)
    expect(f.takeReconnect(60_000)).toBe(true)
    expect(f.takeReconnect(600_000)).toBe(false)
    // Polling pauses while the reconnect attempt is in flight.
    expect(f.shouldPoll()).toBe(true)
  })

  it('a fresh error re-arms the retry window', () => {
    const f = createStreamFallback({ retryAfterMs: 60_000 })
    f.markError(0)
    expect(f.takeReconnect(60_000)).toBe(true)
    f.markError(61_000)
    expect(f.takeReconnect(100_000)).toBe(false)
    expect(f.takeReconnect(121_000)).toBe(true)
  })

  it('open clears the degraded state entirely', () => {
    const f = createStreamFallback({ retryAfterMs: 60_000 })
    f.markError(0)
    f.markOpen(1000)
    expect(f.state()).toBe('live')
    expect(f.shouldPoll()).toBe(false)
    expect(f.takeReconnect(600_000)).toBe(false)
  })
})
