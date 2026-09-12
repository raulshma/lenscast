// Pure persistence + request-building layer for the multi-camera tile list —
// the pollLadder pattern: localStorage itself stays behind MultiCamCard (a
// browser primitive); this module owns the shape, so the additive-field
// contract (older entries without `mode`/`auth`/`refreshMs` must keep
// loading, new fields must never rename or drop existing ones) is pinned by
// vitest.

export type TileMode = 'snapshot' | 'live'

/**
 * Optional per-camera credentials for remotes with auth enabled. Stored in
 * plain text in localStorage (the UI warns about this) — <img> tags cannot
 * send headers, so these ride on fetch-based tile frames and capture POSTs.
 */
export type CameraCredentials =
  | { kind: 'token'; token: string }
  | { kind: 'basic'; username: string; password: string }

export interface SavedCamera {
  id: string
  name: string
  baseUrl: string
  /** Additive field (absent on pre-live-toggle entries): the tile's persisted snapshot/live mode. */
  mode?: TileMode
  /** Additive field (absent on pre-v2 entries): optional per-camera credentials. */
  auth?: CameraCredentials
  /** Additive field: per-tile snapshot refresh period in ms, clamped to MIN_REFRESH_MS. */
  refreshMs?: number
}

export const MIN_REFRESH_MS = 1000
export const DEFAULT_REFRESH_MS = 5000

function isTileMode(value: unknown): value is TileMode {
  return value === 'snapshot' || value === 'live'
}

/** Unknown mode values (or the field's absence) fall back to snapshots. */
export function normalizeMode(value: unknown): TileMode {
  return isTileMode(value) ? value : 'snapshot'
}

function isCredentials(value: unknown): value is CameraCredentials {
  if (value === null || typeof value !== 'object') return false
  const a = value as Record<string, unknown>
  if (a.kind === 'token') return typeof a.token === 'string' && a.token.length > 0
  if (a.kind === 'basic') return typeof a.username === 'string' && typeof a.password === 'string'
  return false
}

/** Credentials pass through only when complete; anything else reads as none. */
export function normalizeCredentials(value: unknown): CameraCredentials | undefined {
  return isCredentials(value) ? value : undefined
}

/** A malformed/absent refresh period reads as the default; values clamp up. */
export function normalizeRefreshMs(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined
  return Math.max(MIN_REFRESH_MS, Math.round(value))
}

/** The period a tile's snapshot auto-refresh actually runs at. */
export function tileRefreshMs(camera: SavedCamera): number {
  return camera.refreshMs ?? DEFAULT_REFRESH_MS
}

/** UTF-8-safe base64 — plain btoa throws on non-Latin1 passwords. */
function base64(s: string): string {
  if (typeof TextEncoder !== 'undefined') {
    let binary = ''
    for (const byte of new TextEncoder().encode(s)) binary += String.fromCharCode(byte)
    return btoa(binary)
  }
  return btoa(s)
}

/** The auth headers fetch-based requests carry (tile frames, capture POSTs). */
export function authHeaders(auth: CameraCredentials | undefined): Record<string, string> {
  if (!auth) return {}
  if (auth.kind === 'token') return { 'X-Api-Token': auth.token }
  return { Authorization: `Basic ${base64(`${auth.username}:${auth.password}`)}` }
}

/**
 * A tile media URL. The token — when present — is also sent as a query param:
 * <img> requests cannot carry headers, so today's header-only servers ignore
 * the param (harmless), while the fetch-based rung below sends the real
 * headers. Server-side keep-it-working: the param is additive.
 */
export function mediaUrl(baseUrl: string, path: string, camera?: SavedCamera): string {
  const token = camera?.auth?.kind === 'token' ? camera.auth.token : null
  if (!token) return `${baseUrl}${path}`
  const separator = path.includes('?') ? '&' : '?'
  return `${baseUrl}${path}${separator}token=${encodeURIComponent(token)}`
}

/** The fully-qualified capture POST for one remote camera, credentials on. */
export function captureRequest(camera: SavedCamera): { url: string; headers: Record<string, string> } {
  return {
    url: mediaUrl(camera.baseUrl, '/api/camera/capture', camera),
    headers: authHeaders(camera.auth),
  }
}

/**
 * Validate a parsed localStorage payload into SavedCamera entries: rows
 * without the required id/name/baseUrl strings are dropped; present-but-
 * invalid `mode`/`auth`/`refreshMs` fall back to defaults rather than
 * dropping the camera (an additive field must never evict an older valid
 * entry — the v1 → v2 migration is exactly that).
 */
export function parseCameras(raw: unknown): SavedCamera[] {
  if (!Array.isArray(raw)) return []
  const cameras: SavedCamera[] = []
  for (const row of raw) {
    if (row === null || typeof row !== 'object') continue
    const c = row as Record<string, unknown>
    if (typeof c.id !== 'string' || typeof c.name !== 'string' || typeof c.baseUrl !== 'string') continue
    const camera: SavedCamera = { id: c.id, name: c.name, baseUrl: c.baseUrl }
    if (isTileMode(c.mode)) camera.mode = c.mode
    const auth = normalizeCredentials(c.auth)
    if (auth) camera.auth = auth
    const refreshMs = normalizeRefreshMs(c.refreshMs)
    if (refreshMs !== undefined) camera.refreshMs = refreshMs
    cameras.push(camera)
  }
  return cameras
}

/**
 * The "Live all" toggle's next state: only when every tile is already live
 * does the button switch everything back to snapshots.
 */
export function nextGlobalMode(cameras: SavedCamera[]): TileMode {
  return cameras.length > 0 && cameras.every((c) => normalizeMode(c.mode) === 'live')
    ? 'snapshot'
    : 'live'
}

/** Every tile's mode set at once, keeping the other fields untouched. */
export function withMode(cameras: SavedCamera[], mode: TileMode): SavedCamera[] {
  return cameras.map((c) => ({ ...c, mode }))
}
