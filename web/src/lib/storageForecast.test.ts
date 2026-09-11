import { describe, expect, it } from 'vitest'
import { MIN_HISTORY_DAYS, forecastStorage } from './storageForecast'

const GB = 1024 ** 3
const DAY = 86_400_000
// Two weeks of history ending "now".
const NEWEST = Date.parse('2026-09-09T12:00:00Z')
const OLDEST = NEWEST - 14 * DAY

function input(overrides: Partial<Parameters<typeof forecastStorage>[0]> = {}) {
  return {
    oldestCaptureMs: OLDEST,
    newestCaptureMs: NEWEST,
    usedBytes: 2 * GB,
    quotaBytes: 10 * GB,
    ...overrides,
  }
}

describe('forecastStorage', () => {
  it('projects remaining quota over the average daily growth', () => {
    // 2 GB over 14 days ≈ 146.6 MB/day; 8 GB left ≈ 56 days.
    const forecast = forecastStorage(input())
    expect(forecast).not.toBeNull()
    expect(forecast!.daysSpanned).toBeCloseTo(14)
    expect(forecast!.bytesPerDay).toBeCloseTo((2 * GB) / 14)
    expect(forecast!.daysRemaining).toBe(56)
  })

  it('reports 0 days once the quota is met or exceeded', () => {
    expect(forecastStorage(input({ usedBytes: 10 * GB }))!.daysRemaining).toBe(0)
    expect(forecastStorage(input({ usedBytes: 11 * GB }))!.daysRemaining).toBe(0)
  })

  it('hides the estimate for under two days of history', () => {
    expect(forecastStorage(input({ oldestCaptureMs: NEWEST - 1 * DAY }))).toBeNull()
    expect(forecastStorage(input({ oldestCaptureMs: NEWEST - (MIN_HISTORY_DAYS - 0.001) * DAY }))).toBeNull()
    // Exactly the minimum is enough.
    expect(forecastStorage(input({ oldestCaptureMs: NEWEST - 2 * DAY }))).not.toBeNull()
  })

  it('hides the estimate when nothing is used or the quota is unusable', () => {
    expect(forecastStorage(input({ usedBytes: 0 }))).toBeNull()
    expect(forecastStorage(input({ quotaBytes: 0 }))).toBeNull()
    expect(forecastStorage(input({ quotaBytes: -1 }))).toBeNull()
  })

  it('hides the estimate when capture edges are missing or inverted', () => {
    expect(forecastStorage(input({ oldestCaptureMs: null }))).toBeNull()
    expect(forecastStorage(input({ newestCaptureMs: null }))).toBeNull()
    expect(forecastStorage(input({ oldestCaptureMs: NEWEST, newestCaptureMs: OLDEST }))).toBeNull()
    expect(forecastStorage(input({ oldestCaptureMs: NEWEST, newestCaptureMs: NEWEST }))).toBeNull()
  })

  it('rounds to whole days', () => {
    // 1 GB over 4 days → 256 MB/day; 1 GB left → exactly 4 days.
    expect(forecastStorage(input({
      oldestCaptureMs: NEWEST - 4 * DAY,
      usedBytes: 1 * GB,
      quotaBytes: 2 * GB,
    }))!.daysRemaining).toBe(4)
    // 1 GB over 3 days → ~341 MB/day; 1 GB left → 2.93 days → 3.
    expect(forecastStorage(input({
      oldestCaptureMs: NEWEST - 3 * DAY,
      usedBytes: 1 * GB,
      quotaBytes: 2 * GB,
    }))!.daysRemaining).toBe(3)
  })
})
