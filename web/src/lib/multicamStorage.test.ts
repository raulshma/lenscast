import { describe, expect, it } from 'vitest'
import {
  type SavedCamera,
  type TileMode,
  nextGlobalMode,
  normalizeMode,
  parseCameras,
  withMode,
} from './multicamStorage'

const LEGACY_ENTRY = { id: 'cam-1', name: 'Front door', baseUrl: 'http://192.168.1.55:8080' }
const LIVE_ENTRY: SavedCamera = { ...LEGACY_ENTRY, mode: 'live' }

describe('persistence shape compatibility', () => {
  it('loads the pre-mode legacy shape unchanged', () => {
    expect(parseCameras([LEGACY_ENTRY])).toEqual([LEGACY_ENTRY])
  })

  it('round-trips the additive mode field alongside the legacy fields', () => {
    const parsed = parseCameras([LEGACY_ENTRY, LIVE_ENTRY])
    expect(parsed).toHaveLength(2)
    expect(parsed[0]).toEqual(LEGACY_ENTRY) // no mode injected on load
    expect(parsed[1]).toEqual(LIVE_ENTRY)
  })

  it('drops rows missing any required field', () => {
    expect(parseCameras([
      LEGACY_ENTRY,
      { id: 'x', name: 'no url' },
      { id: 'y', baseUrl: 'http://x' },
      { name: 'z', baseUrl: 'http://x' },
      'junk',
      null,
      42,
    ])).toEqual([LEGACY_ENTRY])
  })

  it('rejects non-array payloads', () => {
    expect(parseCameras(null)).toEqual([])
    expect(parseCameras({})).toEqual([])
    expect(parseCameras('nope')).toEqual([])
  })

  it('a present-but-invalid mode degrades to snapshots instead of dropping the camera', () => {
    const parsed = parseCameras([{ ...LEGACY_ENTRY, mode: 'video' }])
    expect(parsed).toEqual([LEGACY_ENTRY])
  })
})

describe('mode helpers', () => {
  it('normalizeMode accepts only the two tile modes', () => {
    expect(normalizeMode('live')).toBe<TileMode>('live')
    expect(normalizeMode('snapshot')).toBe<TileMode>('snapshot')
    expect(normalizeMode(undefined)).toBe('snapshot')
    expect(normalizeMode('LIVE')).toBe('snapshot')
  })

  it('withMode stamps every tile additively, keeping id/name/baseUrl', () => {
    const stamped = withMode([LEGACY_ENTRY, { ...LIVE_ENTRY }], 'live')
    expect(stamped).toEqual([
      { id: 'cam-1', name: 'Front door', baseUrl: 'http://192.168.1.55:8080', mode: 'live' },
      LIVE_ENTRY,
    ])
  })

  it('nextGlobalMode flips to snapshots only once every tile is live', () => {
    expect(nextGlobalMode([])).toBe('live')
    expect(nextGlobalMode([LEGACY_ENTRY])).toBe('live')
    expect(nextGlobalMode([LIVE_ENTRY, LEGACY_ENTRY])).toBe('live')
    expect(nextGlobalMode([LIVE_ENTRY, { ...LIVE_ENTRY, id: 'cam-2' }])).toBe('snapshot')
  })
})
