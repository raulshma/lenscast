import { createSignal } from 'solid-js'
import type { DeviceStatus } from '../types'
import type { PlayerMode } from '../video/playerLadder'
import { finiteOrNull, pickPreviewLane } from './streamStats'

// Stream-stats overlay mode — the uiMode pattern applied to the dashboard's
// stream-stats blocks (the preview indicator and the next shell's TX/FPS
// chips). The choice persists to localStorage so a refresh keeps it. The
// values gate VISIBILITY only; what the blocks show is always the preview's
// own lane's measured numbers (lib/streamStats) — never the stale
// adaptive-only values the pre-issue-25 UI displayed.
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
  /**
   * The preview's lane has data (lib/streamStats' pick found something).
   * Decides visibility only — never whether the indicator is mounted.
   */
  laneAvailable: boolean
}

/**
 * The indicator's MOUNT verdict, pure: does its component tree exist at
 * all? off never mounts; on mounts with a live preview; auto adds Adaptive
 * Bitrate's master switch (the legacy condition). Deliberately blind to the
 * lane's data: a lane switch reaches the gate as "the new lane has not
 * reported yet", and mounting on that would tear the indicator down on
 * every switch — an expanded panel, its sparkline history and all. The lane
 * hides it instead (streamStatsOverlayVisible).
 */
export function streamStatsOverlayMounted(mode: StreamStatsOverlayMode, gate: StreamStatsGate): boolean {
  if (!gate.previewLive) return false
  if (mode === 'off') return false
  return mode === 'on' ? true : gate.adaptiveEnabled
}

/**
 * The overlay's visibility verdict, pure: the mounted tree, and the
 * preview's lane has data. auto reproduces the pre-toggle gate exactly
 * (live + visible + adaptive enabled + lane data — for the MJPEG lane
 * `connectionQuality` present); on drops only the adaptive condition; off
 * never shows.
 */
export function streamStatsOverlayVisible(mode: StreamStatsOverlayMode, gate: StreamStatsGate): boolean {
  return streamStatsOverlayMounted(mode, gate) && gate.laneAvailable
}

/** The TX/FPS chips' read: a value shows its chip, a null hides it. */
export interface StreamStatsChipValues {
  /** The preview lane's measured bitrate (kbps), or null when the TX chip is hidden. */
  bitrateKbps: number | null
  /** The preview lane's measured fps, or null when the FPS chip is hidden. */
  fps: number | null
}

/**
 * main's chip gate, exactly: the stream live, and Adaptive Bitrate running
 * with a positive congestion estimate. main omitted the adaptive block when
 * the feature was off; the wire carries `enabled` now, so the switch is read
 * instead of inferred from the block's presence.
 */
function adaptiveGateLive(status: DeviceStatus | null | undefined, isLive: boolean): boolean {
  const adaptive = status?.adaptiveBitrate
  const estimate = finiteOrNull(adaptive?.estimatedBandwidthKbps) ?? 0
  return isLive && adaptive?.enabled === true && estimate > 0
}

/** A measured value shows only when positive — a 0 is an idle lane. */
function positiveOrNull(value: number | null): number | null {
  return value != null && value > 0 ? value : null
}

/**
 * The TX/FPS chips' whole decision — whether each chip shows, and the value
 * it shows — pure. The values are the PREVIEW lane's measured bitrate/fps,
 * picked by the preview's own rung (lib/streamStats): never another lane's
 * numbers, never a placeholder. off hides both; on shows a chip whenever
 * the lane measured it (the stream's liveness is not asked); auto requires
 * main's legacy gate first (adaptiveGateLive), then the same measured data.
 * A null/0 value hides its chip in every mode (the honest idle convention
 * — nothing fake, nothing frozen).
 */
export function pickStreamStatsChips(
  mode: StreamStatsOverlayMode,
  status: DeviceStatus | null | undefined,
  previewMode: PlayerMode | undefined,
  isLive: boolean,
): StreamStatsChipValues {
  const none = (): StreamStatsChipValues => ({ bitrateKbps: null, fps: null })
  if (mode === 'off') return none()
  if (mode === 'auto' && !adaptiveGateLive(status, isLive)) return none()
  const lane = pickPreviewLane(previewMode, status)
  return {
    bitrateKbps: positiveOrNull(lane?.bitrateKbps ?? null),
    fps: positiveOrNull(lane?.fps ?? null),
  }
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
