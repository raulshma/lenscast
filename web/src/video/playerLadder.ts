// Player selection for the live preview: a strict preference order from
// lowest latency to widest compatibility. h264 (WebCodecs over WebSocket,
// sub-second) → mjpeg (HTTP multipart, no audio) → hls (muxed A/V, ~10s
// behind live on the server's 5x2s sliding window, but the only rung that
// survives a blocked WebSocket). Pure decision math — the caller owns the
// players, the signals, and the retry timers.

export type PlayerMode = 'h264' | 'mjpeg' | 'hls'

export const PLAYER_LADDER: readonly PlayerMode[] = ['h264', 'mjpeg', 'hls']

/**
 * Best available ladder rung, lowest latency first. `h264Failed` /
 * `mjpegFailed` filter out rungs that already gave up this cycle (passing
 * `h264Failed` from the mjpeg rung keeps a dead WebSocket from being
 * re-tried); `current` comes back unchanged when every rung is exhausted,
 * so the caller keeps its own retry loop.
 */
export function nextPlayerMode(
  current: PlayerMode,
  h264Failed: boolean,
  mjpegFailed: boolean,
  hlsSupported: boolean,
): PlayerMode {
  if (!h264Failed) return 'h264'
  if (!mjpegFailed) return 'mjpeg'
  if (hlsSupported) return 'hls'
  return current
}

/** The manual toggle: one step down the ladder, wrapping hls → h264. */
export function cyclePlayerMode(current: PlayerMode): PlayerMode {
  const index = PLAYER_LADDER.indexOf(current)
  return PLAYER_LADDER[(index + 1) % PLAYER_LADDER.length]
}

/** Safari/iOS decode the playlist natively; everywhere else HLS needs
 *  hls.js, which rides on Media Source Extensions. */
export function hlsSupported(): boolean {
  if (typeof window === 'undefined' || typeof document === 'undefined') return false
  if ('MediaSource' in window) return true
  return !!document.createElement('video').canPlayType('application/vnd.apple.mpegurl')
}
