import { describe, expect, it } from 'vitest'
import type { GalleryItem } from '../types'
import { dayKeyFor, dayLabel, groupGalleryByDay, listCaptureDays } from './groupByDay'

// Fixed "now": 2026-09-09 12:00 local time.
const NOW = new Date(2026, 8, 9, 12, 0, 0).getTime()

function item(id: string, timestamp: number): GalleryItem {
  return {
    id, type: 'PHOTO', fileName: `${id}.jpg`, timestamp, fileSizeBytes: 1,
    durationMs: 0, thumbnailUrl: `/api/media/${id}/thumbnail`, downloadUrl: `/api/media/${id}`,
    url: `/api/media/${id}`,
  }
}

describe('dayKeyFor', () => {
  it('formats a local YYYY-MM-DD key, zero-padded', () => {
    expect(dayKeyFor(new Date(2026, 8, 9, 7, 30).getTime())).toBe('2026-09-09')
    expect(dayKeyFor(new Date(2026, 0, 3, 23, 59).getTime())).toBe('2026-01-03')
  })
})

describe('dayLabel', () => {
  it('labels today and yesterday relative to now', () => {
    expect(dayLabel('2026-09-09', NOW)).toBe('Today')
    expect(dayLabel('2026-09-08', NOW)).toBe('Yesterday')
  })

  it('labels older days without calling them yesterday across month edges', () => {
    expect(dayLabel('2026-09-07', NOW)).not.toBe('Yesterday')
    expect(dayLabel('2026-09-07', NOW)).not.toBe('Today')
    expect(dayLabel('2026-09-07', NOW)).toContain('7')
  })

  it('handles year boundaries (Dec 31 → Jan 1 is yesterday, not a year-old label)', () => {
    const newYear = new Date(2027, 0, 1, 3).getTime()
    expect(dayLabel('2026-12-31', newYear)).toBe('Yesterday')
  })

  it('returns the raw key for malformed keys', () => {
    expect(dayLabel('not-a-day', NOW)).toBe('not-a-day')
  })
})

describe('groupGalleryByDay', () => {
  it('groups by capture day keeping newest-day-first encounter order', () => {
    const groups = groupGalleryByDay([
      item('a', new Date(2026, 8, 9, 10).getTime()),
      item('b', new Date(2026, 8, 9, 8).getTime()),
      item('c', new Date(2026, 8, 7, 22).getTime()),
    ], NOW)
    expect(groups.map((g) => g.key)).toEqual(['2026-09-09', '2026-09-07'])
    expect(groups[0].label).toBe('Today')
    expect(groups[0].items.map((i) => i.id)).toEqual(['a', 'b'])
    expect(groups[1].items.map((i) => i.id)).toEqual(['c'])
  })

  it('keeps item order stable inside a group', () => {
    const t1 = new Date(2026, 8, 8, 9).getTime()
    const t2 = new Date(2026, 8, 8, 15).getTime()
    const groups = groupGalleryByDay([item('x', t1), item('y', t2)], NOW)
    expect(groups[0].items.map((i) => i.id)).toEqual(['x', 'y'])
  })

  it('returns an empty array for an empty gallery', () => {
    expect(groupGalleryByDay([], NOW)).toEqual([])
  })
})

describe('listCaptureDays', () => {
  it('lists distinct days in grid order with labels', () => {
    const days = listCaptureDays([
      item('a', new Date(2026, 8, 9, 10).getTime()),
      item('b', new Date(2026, 8, 8, 10).getTime()),
      item('c', new Date(2026, 8, 9, 11).getTime()),
    ], NOW)
    expect(days).toEqual([
      { key: '2026-09-09', label: 'Today' },
      { key: '2026-09-08', label: 'Yesterday' },
    ])
  })
})
