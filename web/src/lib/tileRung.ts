// Pure verdict layer for one multi-camera tile's media source (beside its
// import neighbor multicamStorage, whose SavedCamera/mediaUrl it builds on).
// The routes are the remote LensCast media endpoints (server-side:
// StreamingServer.kt).
//
// The rung and the badge verdict are DERIVED, never stored: the tile's five
// writable primitives (frameUrl, mode, status, tick, liveBroken) stay the
// written truth in MultiCamCard, each written under the wave-6 revoke/stale
// discipline, and this module only re-reads them. In particular the
// persisted-vs-local `mode` divergence is deliberate — a silent MJPEG
// demotion (liveBroken) lasts the session only and must not destroy the
// user's persisted preference, and credential tiles are snapshot-locked
// outright.

import type { SavedCamera, TileMode } from './multicamStorage'
import { mediaUrl } from './multicamStorage'

export const MJPEG_PATH = '/stream' // multipart/x-mixed-replace, rendered natively by <img>
export const SNAPSHOT_PATH = '/snapshot' // image/jpeg, latest MJPEG frame

/** Which source the tile's <img> is on — the rung ladder's resting point. */
export type TileRung =
  | { kind: 'auth-frame'; url: string }
  | { kind: 'live'; url: string }
  | { kind: 'snapshot'; url: string }

/**
 * The tile's media source, from the primitives the fetch/toggle/demotion
 * writers set: a held auth frame wins outright (credential tiles carry
 * their fetched blob), else the live MJPEG rung, else the cache-busted
 * snapshot (the tick is the <img> refresh's cache buster).
 */
export function tileRung(frameUrl: string | null, mode: TileMode, tick: number, camera: SavedCamera): TileRung {
  if (frameUrl) return { kind: 'auth-frame', url: frameUrl }
  if (mode === 'live') return { kind: 'live', url: mediaUrl(camera.baseUrl, MJPEG_PATH, camera) }
  return { kind: 'snapshot', url: mediaUrl(camera.baseUrl, `${SNAPSHOT_PATH}?t=${tick}`, camera) }
}

export type TileBadgeKind = 'live' | 'online' | 'offline' | 'loading'

/** The status badge's verdict: online tiles split live/online by mode, the rest pass through. */
export function tileBadgeKind(status: 'loading' | 'online' | 'offline', mode: TileMode): TileBadgeKind {
  return status === 'online' ? (mode === 'live' ? 'live' : 'online') : status
}
