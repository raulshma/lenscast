import { describe, expect, it } from 'vitest'
import { type SavedCamera } from './multicamStorage'
import { MJPEG_PATH, SNAPSHOT_PATH, tileBadgeKind, tileRung } from './tileRung'

const PLAIN: SavedCamera = { id: 'cam-1', name: 'Front door', baseUrl: 'http://192.168.1.55:8080' }
const TOKEN: SavedCamera = { ...PLAIN, auth: { kind: 'token', token: 'secret-token' } }

describe('tileRung', () => {
  it('a held auth frame wins outright — mode and tick are irrelevant', () => {
    expect(tileRung('blob:frame', 'snapshot', 0, TOKEN)).toEqual({ kind: 'auth-frame', url: 'blob:frame' })
    expect(tileRung('blob:frame', 'live', 7, PLAIN)).toEqual({ kind: 'auth-frame', url: 'blob:frame' })
  })

  it('null frame + live mode lands on the MJPEG rung — token cameras ride the query param', () => {
    expect(tileRung(null, 'live', 3, PLAIN)).toEqual({
      kind: 'live',
      url: 'http://192.168.1.55:8080/stream',
    })
    expect(tileRung(null, 'live', 3, TOKEN)).toEqual({
      kind: 'live',
      url: 'http://192.168.1.55:8080/stream?token=secret-token',
    })
  })

  it('null frame + snapshot mode cache-busts with the tick', () => {
    expect(tileRung(null, 'snapshot', 42, PLAIN)).toEqual({
      kind: 'snapshot',
      url: 'http://192.168.1.55:8080/snapshot?t=42',
    })
    expect(tileRung(null, 'snapshot', 42, TOKEN)).toEqual({
      kind: 'snapshot',
      url: 'http://192.168.1.55:8080/snapshot?t=42&token=secret-token',
    })
  })
})

describe('tileBadgeKind', () => {
  it('online tiles split live/online by mode', () => {
    expect(tileBadgeKind('online', 'live')).toBe('live')
    expect(tileBadgeKind('online', 'snapshot')).toBe('online')
  })

  it('offline and loading pass through regardless of mode', () => {
    expect(tileBadgeKind('offline', 'live')).toBe('offline')
    expect(tileBadgeKind('offline', 'snapshot')).toBe('offline')
    expect(tileBadgeKind('loading', 'live')).toBe('loading')
    expect(tileBadgeKind('loading', 'snapshot')).toBe('loading')
  })
})

describe('route path pins', () => {
  it('keeps the remote media routes at their production values', () => {
    expect(MJPEG_PATH).toBe('/stream')
    expect(SNAPSHOT_PATH).toBe('/snapshot')
  })
})
