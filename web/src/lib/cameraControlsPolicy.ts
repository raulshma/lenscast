// The camera controls strip's numeric decisions, extracted pure (the
// previewRetryPolicy shape: the browser primitives — the slider element, the
// throttle timer, localStorage — stay at the call site, and the verdicts
// themselves are vitest-pinned without one).
import type { ControlRange } from '../types'

/** Each −/+ tap moves the camera zoom by half a step — the old slider's increment. */
export const ZOOM_STEP = 0.5

/** Post-press window in which a device/settings resync may not overwrite an optimistic EV mirror. */
export const EV_PRESS_WINDOW_MS = 800

/**
 * Post-action window in which a status echo may not overwrite the optimistic
 * zoom mirror: a stepper press (unlike a slider drag, which the focus guard
 * covers) lands between two 1 s status pushes, and the first echo can still
 * carry the pre-press ratio.
 */
export const ZOOM_MIRROR_HOLD_MS = 600

/** Zoom POSTs coalesce to at most one in-flight send per interval while the slider drags. */
export const ZOOM_SEND_THROTTLE_MS = 150

/**
 * EV bounds while the device has not published its real range on
 * /api/status — the same -12..12 the settings sliders have always used.
 */
export const DEFAULT_EV_RANGE: ControlRange = { min: -12, max: 12 }

/** The localStorage key the strip's collapsed state persists under (uiMode's key pattern). */
export const CAM_STRIP_STORAGE_KEY = 'lenscast.camStrip'

/** The camera zoom's floor is 1.0 (no digital shrink); the ceiling is the device's live range. */
export function clampRatio(value: number, max: number): number {
  return Math.min(Math.max(value, 1), max)
}

/** One −/+ tap from the current ratio, rounded back onto the 0.5 grid and clamped. */
export function steppedZoom(ratio: number, direction: 1 | -1, max: number): number {
  const raw = Math.round((ratio + direction * ZOOM_STEP) * 10) / 10
  return clampRatio(raw, max)
}

/** The `1x` chip's target: the wide lens' full-field view. */
export function resetZoom(): number {
  return 1
}

/** The slider's mono readout: one decimal, always with the `x` suffix. */
export function zoomReadout(ratio: number): string {
  return `${ratio.toFixed(1)}x`
}

/**
 * The exposure-compensation index clamp. The device's published range wins
 * when present; the static default covers devices that did not publish one.
 */
export function clampExposureIndex(value: number, range: ControlRange | null | undefined): number {
  const bounds = range ?? DEFAULT_EV_RANGE
  const rounded = Math.round(value)
  // Math.round(-0.4) is -0, and Object.is(-0, 0) is false — normalize before clamping.
  return Math.min(Math.max(rounded === 0 ? 0 : rounded, bounds.min), bounds.max)
}

/** True while a just-pressed EV mirror must not be resynced from settings/status yet. */
export function evPressGuard(lastPressAt: number, now: number, windowMs: number = EV_PRESS_WINDOW_MS): boolean {
  return now - lastPressAt < windowMs
}

/**
 * The zoom send verdict at a slider input: a leading send when the throttle
 * window has elapsed, otherwise defer — the caller arms a trailing flush
 * (zoomFlushDelayMs) that fires the newest value once.
 */
export function zoomSendVerdict(now: number, lastSentAt: number, throttleMs: number = ZOOM_SEND_THROTTLE_MS): 'send' | 'defer' {
  return now - lastSentAt >= throttleMs ? 'send' : 'defer'
}

/** How far from now the trailing flush timer must be armed, in ms (0 = immediately). */
export function zoomFlushDelayMs(now: number, lastSentAt: number, throttleMs: number = ZOOM_SEND_THROTTLE_MS): number {
  return Math.max(0, lastSentAt + throttleMs - now)
}

/** Anything but 'collapsed' is expanded — an unknown value can never wedge the strip shut. */
export function parseStripCollapsed(raw: string | null): boolean {
  return raw === 'collapsed'
}

export function stripCollapsedStorageValue(collapsed: boolean): string {
  return collapsed ? 'collapsed' : 'open'
}
