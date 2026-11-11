import { describe, expect, it } from 'vitest'
import { SSE_STALE_MS, sseLaneHealthy } from './sseHealth'

describe('sseLaneHealthy', () => {
  it('counts a fresh open socket with no events yet as healthy', () => {
    // The onopen handler stamps both clocks (there is no event age yet):
    // a socket that just answered must not read as dead at birth.
    const now = 10_000_000
    expect(sseLaneHealthy(now, true, now, now)).toBe(true)
  })

  it('holds the 5 s alive-grace boundary: 4999 ms healthy, 5000 ms not', () => {
    const now = 10_000_000
    expect(sseLaneHealthy(now, true, now - 4_999, now)).toBe(true)
    expect(sseLaneHealthy(now, true, now - 5_000, now)).toBe(false)
  })

  it('holds the stale-event boundary: 4999 ms silent healthy, SSE_STALE_MS not', () => {
    const now = 10_000_000
    expect(sseLaneHealthy(now, true, now, now - (SSE_STALE_MS - 1))).toBe(true)
    expect(sseLaneHealthy(now, true, now, now - SSE_STALE_MS)).toBe(false)
  })

  it('a non-open socket is never healthy, however fresh the clocks are', () => {
    const now = 10_000_000
    expect(sseLaneHealthy(now, false, now, now)).toBe(false)
    expect(sseLaneHealthy(now, false, now - 1, now - 1)).toBe(false)
  })

  it('keeps the freshness bar at its production value', () => {
    // The banner timing and the poll-lane handback are tuned to 5 s of
    // silence; a silent constant drift would change observable behavior.
    expect(SSE_STALE_MS).toBe(5_000)
  })
})
