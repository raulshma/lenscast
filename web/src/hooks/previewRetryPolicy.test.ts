import { describe, expect, it } from 'vitest'
import { MAX_PREVIEW_ERRORS, NO_HLS_RETRY_MS, previewRetryDelayMs, shouldDemoteToHls } from './previewRetryPolicy'

describe('previewRetryDelayMs', () => {
  it('backs off 2 s per error, capped at 4 errors worth (8 s)', () => {
    expect(previewRetryDelayMs(1)).toBe(2_000)
    expect(previewRetryDelayMs(2)).toBe(4_000)
    expect(previewRetryDelayMs(3)).toBe(6_000)
    expect(previewRetryDelayMs(4)).toBe(8_000)
    expect(previewRetryDelayMs(5)).toBe(8_000)
    expect(previewRetryDelayMs(99)).toBe(8_000)
  })
})

describe('shouldDemoteToHls', () => {
  it('demotes only once the MJPEG budget is exhausted and an HLS rung exists', () => {
    expect(shouldDemoteToHls(4, true)).toBe(false)
    expect(shouldDemoteToHls(5, true)).toBe(true)
    expect(shouldDemoteToHls(6, true)).toBe(true)
    expect(shouldDemoteToHls(5, false)).toBe(false)
    expect(shouldDemoteToHls(6, false)).toBe(false)
    expect(shouldDemoteToHls(0, true)).toBe(false)
  })
})

describe('constants', () => {
  it('pin the error budget and the no-HLS retry at their production values', () => {
    // The recovery cadence (8 s max backoff, 15 s no-HLS wait) is tuned
    // behavior; a silent constant drift would change observable timing.
    expect(MAX_PREVIEW_ERRORS).toBe(5)
    expect(NO_HLS_RETRY_MS).toBe(15_000)
  })
})
