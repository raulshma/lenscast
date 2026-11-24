import { createSignal } from 'solid-js'

// Stream-stats overlay mode — the uiMode pattern applied to the dashboard's
// stream-stats blocks (the preview indicator and the next shell's TX/FPS
// chips). The choice persists to localStorage so a refresh keeps it. The
// values gate VISIBILITY only; what the blocks show is always the picked
// lane's measured numbers (lib/streamStats) — never the stale adaptive-only
// values the pre-issue-25 UI displayed.
//
//   auto — today's behaviour: show while the stream is up, the preview is
//          visible, Adaptive Bitrate is enabled, and the preview's lane has
//          data (the pre-toggle gate, made lane-aware).
//   on   — show whenever the preview is live and the lane has data; no
//          Adaptive Bitrate requirement (stats work with adaptation off).
//   off  — never.

export type StreamStatsOverlayMode = 'auto' | 'on' | 'off'

export const STREAM_STATS_OVERLAY_MODES: readonly StreamStatsOverlayMode[] = ['auto', 'on', 'off']

const STORAGE_KEY = 'lenscast.streamStats'

/** Anything but on/off is auto — an unknown value can never wedge the app. */
export function parseStreamStatsOverlayMode(raw: string | null): StreamStatsOverlayMode {
  return raw === 'on' || raw === 'off' ? raw : 'auto'
}

/** The button's cycle: auto → on → off → auto. */
export function nextStreamStatsOverlayMode(mode: StreamStatsOverlayMode): StreamStatsOverlayMode {
  const index = STREAM_STATS_OVERLAY_MODES.indexOf(mode)
  return STREAM_STATS_OVERLAY_MODES[(index + 1) % STREAM_STATS_OVERLAY_MODES.length]
}

/** Everything the visibility verdict needs besides the mode itself. */
export interface StreamStatsGate {
  /** The stream is active and the preview panel is actually visible. */
  previewLive: boolean
  /** Adaptive Bitrate's master switch — the auto mode's legacy condition. */
  adaptiveEnabled: boolean
  /** The preview's lane has data (lib/streamStats' pick found something). */
  laneAvailable: boolean
}

/**
 * The overlay's visibility verdict, pure. auto reproduces the pre-toggle
 * gate exactly (live + visible + adaptive enabled + lane data — for the
 * MJPEG lane `connectionQuality` present); on drops only the adaptive
 * condition; off never shows.
 */
export function streamStatsOverlayVisible(mode: StreamStatsOverlayMode, gate: StreamStatsGate): boolean {
  if (!gate.previewLive || !gate.laneAvailable) return false
  if (mode === 'off') return false
  return mode === 'on' ? true : gate.adaptiveEnabled
}

/**
 * The TX/FPS chips' visibility verdict, pure. Both chips read measured
 * values now, so a null/0 value hides the chip in every mode (the honest
 * idle convention — nothing fake, nothing frozen). auto keeps the legacy
 * live-stream requirement; on shows measured data regardless; off never.
 */
export function streamStatsChipVisible(
  mode: StreamStatsOverlayMode,
  isLive: boolean,
  value: number | null,
): boolean {
  if (mode === 'off') return false
  if (value == null || value <= 0) return false
  return mode === 'on' ? true : isLive
}

export interface StreamStatsOverlayDeps {
  get(): string | null
  set(value: string): void
}

export function createStreamStatsOverlay(deps: StreamStatsOverlayDeps) {
  const [mode, setMode] = createSignal<StreamStatsOverlayMode>(parseStreamStatsOverlayMode(deps.get()))
  const store = {
    /** The current mode, as a reactive signal. */
    mode,
    set(next: StreamStatsOverlayMode) {
      try {
        deps.set(next)
      } catch {
        // Storage failures keep the switch working for this session.
      }
      setMode(next)
    },
    cycle() {
      store.set(nextStreamStatsOverlayMode(store.mode()))
    },
  }
  return store
}

function localStorageDeps(): StreamStatsOverlayDeps {
  return {
    get: () => (typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null),
    set: (value) => {
      if (typeof localStorage !== 'undefined') localStorage.setItem(STORAGE_KEY, value)
    },
  }
}

let singleton: ReturnType<typeof createStreamStatsOverlay> | null = null

/** The app-wide stats-overlay store; created lazily so importing in node tests is safe. */
export function streamStatsOverlayStore() {
  if (!singleton) singleton = createStreamStatsOverlay(localStorageDeps())
  return singleton
}
