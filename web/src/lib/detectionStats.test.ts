import { describe, expect, it } from 'vitest'
import type { DetectionStats } from '../types'
import detectionStatsFixture from '../../contract/detection-stats.json'
import { STATS_WINDOWS, sevenDaySeries, windowCounts, windowTotal, type StatsWindow } from './detectionStats'

function stats(overrides: Partial<DetectionStats> = {}): DetectionStats {
  return { ...(detectionStatsFixture as DetectionStats), ...overrides }
}

describe('window counts mapping', () => {
  it('maps each window toggle to its server block', () => {
    const s = stats()
    expect(windowCounts(s, '24h')).toEqual([
      { type: 'motion', count: 6 },
      { type: 'sound', count: 2 },
      { type: 'tamper', count: 0 },
    ])
    expect(windowCounts(s, '7d')).toEqual([
      { type: 'motion', count: 21 },
      { type: 'sound', count: 8 },
      { type: 'tamper', count: 1 },
    ])
    expect(windowCounts(s, 'all-time')).toEqual([
      { type: 'motion', count: 90 },
      { type: 'sound', count: 30 },
      { type: 'tamper', count: 3 },
    ])
  })

  it('keeps zero rows for quiet types and appends unknown types alphabetically', () => {
    const s = stats({
      last24h: { sound: 4, vibration: 2, animal: 7 } as Record<string, number>,
    })
    expect(windowCounts(s, '24h')).toEqual([
      { type: 'motion', count: 0 },
      { type: 'sound', count: 4 },
      { type: 'tamper', count: 0 },
      { type: 'animal', count: 7 },
      { type: 'vibration', count: 2 },
    ])
  })

  it('survives a missing window block', () => {
    const s = stats({ last7d: undefined as unknown as Record<string, number> })
    expect(windowCounts(s, '7d')).toEqual([
      { type: 'motion', count: 0 },
      { type: 'sound', count: 0 },
      { type: 'tamper', count: 0 },
    ])
  })

  it('windowTotal sums the mapped rows', () => {
    expect(windowTotal(stats(), '24h')).toBe(8)
    expect(windowTotal(stats(), '7d')).toBe(30)
    expect(windowTotal(stats(), 'all-time')).toBe(123)
  })
})

describe('seven-day series', () => {
  it('zero-fills the seven days ending today, oldest first', () => {
    const series = sevenDaySeries(
      [
        { day: '2026-09-07', count: 4 },
        { day: '2026-09-09', count: 5 },
      ],
      '2026-09-09',
    )
    expect(series.map((d) => `${d.day}:${d.count}`)).toEqual([
      '2026-09-03:0',
      '2026-09-04:0',
      '2026-09-05:0',
      '2026-09-06:0',
      '2026-09-07:4',
      '2026-09-08:0',
      '2026-09-09:5',
    ])
  })

  it('drops days outside the seven-day window and malformed day keys', () => {
    const series = sevenDaySeries(
      [
        { day: '2026-08-31', count: 9 },
        { day: 'bogus', count: 9 },
        { day: '2026-09-09', count: 5 },
      ],
      '2026-09-09',
    )
    expect(series).toHaveLength(7)
    expect(series[0].count).toBe(0)
    expect(series[6]).toEqual({ day: '2026-09-09', count: 5 })
  })

  it('returns seven zero columns for an empty series and handles a malformed today', () => {
    expect(sevenDaySeries(undefined, '2026-09-09')).toHaveLength(7)
    expect(sevenDaySeries([{ day: '2026-09-09', count: 1 }], 'oops')).toEqual([])
  })
})

describe('window chips', () => {
  it('cover 24h, 7d and all-time in toggle order', () => {
    expect(STATS_WINDOWS.map((w) => w.value)).toEqual<StatsWindow[]>(['24h', '7d', 'all-time'])
  })
})
