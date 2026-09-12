import { describe, expect, it } from 'vitest'
import { filterByQuery, matchesQuery } from './mediaSearch'

describe('matchesQuery', () => {
  it('is a case-insensitive substring match on the file name', () => {
    expect(matchesQuery('IMG_2026.WMV', 'img')).toBe(true)
    expect(matchesQuery('front-door_20260909_101500.mp4', 'DOOR_2026')).toBe(true)
    expect(matchesQuery('front-door.mp4', 'back')).toBe(false)
  })

  it('a blank (or whitespace-only) query matches everything', () => {
    expect(matchesQuery('anything.mp4', '')).toBe(true)
    expect(matchesQuery('anything.mp4', '   ')).toBe(true)
  })
})

describe('filterByQuery', () => {
  const items = [
    { id: '1', fileName: 'front_20260909_101500.mp4' },
    { id: '2', fileName: 'back_20260909_101600.mp4' },
    { id: '3', fileName: 'front_20260908_090000.mp4' },
  ]

  it('keeps only matching items, order preserved', () => {
    expect(filterByQuery(items, 'front').map((i) => i.id)).toEqual(['1', '3'])
    expect(filterByQuery(items, '20260908').map((i) => i.id)).toEqual(['3'])
  })

  it('a blank query returns every item unchanged', () => {
    expect(filterByQuery(items, '')).toEqual(items)
  })

  it('no matches yields an empty list (the grid shows its no-match state)', () => {
    expect(filterByQuery(items, 'zzz')).toEqual([])
  })

  it('is a no-op over already-server-filtered rows (send q= AND filter client-side)', () => {
    const serverFiltered = items.filter((i) => i.fileName.includes('front'))
    expect(filterByQuery(serverFiltered, 'front')).toEqual(serverFiltered)
  })
})
