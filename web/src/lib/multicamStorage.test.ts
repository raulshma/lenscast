import { describe, expect, it } from 'vitest'
import {
  type SavedCamera,
  type TileMode,
  authHeaders,
  captureRequest,
  mediaUrl,
  nextGlobalMode,
  normalizeCredentials,
  normalizeMode,
  normalizeRefreshMs,
  parseCameras,
  tileRefreshMs,
  withMode,
} from './multicamStorage'

const LEGACY_ENTRY = { id: 'cam-1', name: 'Front door', baseUrl: 'http://192.168.1.55:8080' }
const LIVE_ENTRY: SavedCamera = { ...LEGACY_ENTRY, mode: 'live' }
const TOKEN_AUTH = { kind: 'token', token: 'secret-token' } as const
const BASIC_AUTH = { kind: 'basic', username: 'admin', password: 'p@ss ünicode' } as const

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

describe('v2 migration: credentials + refresh period', () => {
  it('v1 entries (no auth/refreshMs) load unchanged — no fields injected', () => {
    expect(parseCameras([LEGACY_ENTRY])).toEqual([LEGACY_ENTRY])
  })

  it('round-trips valid auth and refreshMs additively', () => {
    const saved: SavedCamera = { ...LEGACY_ENTRY, auth: TOKEN_AUTH, refreshMs: 10000, mode: 'snapshot' }
    expect(parseCameras([saved])).toEqual([saved])
  })

  it('an invalid auth shape is dropped, not the camera', () => {
    for (const bad of [null, 'token', {}, { kind: 'token' }, { kind: 'token', token: '' }, { kind: 'basic', username: 'u' }, { kind: 'sso', token: 'x' }]) {
      expect(parseCameras([{ ...LEGACY_ENTRY, auth: bad }])).toEqual([LEGACY_ENTRY])
    }
  })

  it('a wrong-typed refreshMs is dropped, not the camera', () => {
    for (const bad of ['5000', NaN, Infinity]) {
      expect(parseCameras([{ ...LEGACY_ENTRY, refreshMs: bad }])).toEqual([LEGACY_ENTRY])
    }
  })

  it('normalizeRefreshMs clamps up to the 1 s floor', () => {
    expect(normalizeRefreshMs(999)).toBe(1000)
    expect(normalizeRefreshMs(0)).toBe(1000)
    expect(normalizeRefreshMs(2500.6)).toBe(2501)
    expect(normalizeRefreshMs(undefined)).toBeUndefined()
    expect(normalizeRefreshMs('fast')).toBeUndefined()
  })

  it('tileRefreshMs reads the default when the field is absent', () => {
    expect(tileRefreshMs(LEGACY_ENTRY)).toBe(5000)
    expect(tileRefreshMs({ ...LEGACY_ENTRY, refreshMs: 30000 })).toBe(30000)
  })

  it('normalizeCredentials accepts only the two complete shapes', () => {
    expect(normalizeCredentials(TOKEN_AUTH)).toEqual(TOKEN_AUTH)
    expect(normalizeCredentials(BASIC_AUTH)).toEqual(BASIC_AUTH)
    expect(normalizeCredentials({ kind: 'basic', username: 'u', password: '' })).toEqual({ kind: 'basic', username: 'u', password: '' })
    expect(normalizeCredentials({ kind: 'token', token: '' })).toBeUndefined()
    expect(normalizeCredentials(undefined)).toBeUndefined()
  })
})

describe('request building', () => {
  it('token credentials send the X-Api-Token header', () => {
    expect(authHeaders(TOKEN_AUTH)).toEqual({ 'X-Api-Token': 'secret-token' })
  })

  it('basic credentials send the Authorization header, UTF-8-safe', () => {
    const headers = authHeaders(BASIC_AUTH)
    expect(headers.Authorization).toMatch(/^Basic /)
    // Decode through the same UTF-8 path the encoder used — plain atob
    // would return Latin-1 mojibake for the multibyte 'ü'.
    const decoded = new TextDecoder().decode(
      Uint8Array.from(atob(headers.Authorization.slice('Basic '.length)), (c) => c.charCodeAt(0)),
    )
    expect(decoded).toBe('admin:p@ss ünicode')
  })

  it('no credentials means no headers', () => {
    expect(authHeaders(undefined)).toEqual({})
  })

  it('token cameras also send ?token= on media URLs (header-less <img> rung)', () => {
    const cam: SavedCamera = { ...LEGACY_ENTRY, auth: TOKEN_AUTH }
    expect(mediaUrl(cam.baseUrl, '/snapshot?t=3', cam)).toBe('http://192.168.1.55:8080/snapshot?t=3&token=secret-token')
    expect(mediaUrl(cam.baseUrl, '/stream', cam)).toBe('http://192.168.1.55:8080/stream?token=secret-token')
  })

  it('plain cameras and non-token auth leave media URLs untouched', () => {
    expect(mediaUrl(LEGACY_ENTRY.baseUrl, '/snapshot', LEGACY_ENTRY)).toBe('http://192.168.1.55:8080/snapshot')
    expect(mediaUrl(LEGACY_ENTRY.baseUrl, '/snapshot', { ...LEGACY_ENTRY, auth: BASIC_AUTH })).toBe('http://192.168.1.55:8080/snapshot')
  })

  it('captureRequest targets POST /api/capture with the credentials on', () => {
    const cam: SavedCamera = { ...LEGACY_ENTRY, auth: TOKEN_AUTH }
    expect(captureRequest(cam)).toEqual({
      url: 'http://192.168.1.55:8080/api/capture?token=secret-token',
      headers: { 'X-Api-Token': 'secret-token' },
    })
    expect(captureRequest(LEGACY_ENTRY).url).toBe('http://192.168.1.55:8080/api/capture')
    expect(captureRequest(LEGACY_ENTRY).headers).toEqual({})
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
