// Pure persistence layer for the multi-camera tile list — the pollLadder
// pattern: localStorage itself stays behind MultiCamCard (a browser
// primitive); this module owns the shape, so the additive-field contract
// (older entries without `mode` must keep loading, new fields must never
// rename or drop existing ones) is pinned by vitest.

export type TileMode = 'snapshot' | 'live'

export interface SavedCamera {
  id: string
  name: string
  baseUrl: string
  /** Additive field (absent on pre-live-toggle entries): the tile's persisted snapshot/live mode. */
  mode?: TileMode
}

function isTileMode(value: unknown): value is TileMode {
  return value === 'snapshot' || value === 'live'
}

/** Unknown mode values (or the field's absence) fall back to snapshots. */
export function normalizeMode(value: unknown): TileMode {
  return isTileMode(value) ? value : 'snapshot'
}

/**
 * Validate a parsed localStorage payload into SavedCamera entries: rows
 * without the required id/name/baseUrl strings are dropped; a present-but-
 * invalid `mode` falls back to 'snapshot' rather than dropping the camera
 * (the additive field must never evict an older valid entry).
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
