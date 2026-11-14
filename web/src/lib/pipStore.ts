import { createSignal } from 'solid-js'
import type { RouteName } from './router'

// Module-level mini-player (PiP) store — the viewerStore pattern: the live
// stream's floating dock is a shared "where does the player live" surface,
// not any single view's state. StreamPipHost (mounted once in NextShell)
// owns the persistent StreamPreview and re-parents its container between the
// two mount points registered here (the live stage slot and the dock slot);
// the dismissal bit and the dock position round-trip localStorage like the
// other console preferences (sidebar, theme, multicam tiles).

export interface PipPosition {
  x: number
  y: number
}

const PIP_STORAGE_KEY = 'lenscast.nextPip'

/** Viewport edge padding the dock never crosses (both axes). */
export const PIP_EDGE = 8

/** Bottom inset on <768px viewports: the 58px bottom tab bar plus margin. */
export const PIP_MOBILE_BOTTOM_INSET = 66

// CSS mirrors (pip.css owns the real values) for seeding the default rest
// position before the dock is first measured.
export const PIP_DESKTOP_WIDTH = 320
export const PIP_MOBILE_WIDTH = 232
export const PIP_CHROME_HEIGHT = 34
export const PIP_MOBILE_BREAKPOINT = 768

/**
 * The dock's resting size at a given viewport width: CSS renders
 * `min(320px, 44vw)` on desktop and `min(232px, 52vw)` on mobile, with a
 * 16:9 slot plus the chrome row and 2px of dock borders.
 */
export function defaultDockSize(vw: number): { w: number; h: number } {
  const w = vw < PIP_MOBILE_BREAKPOINT
    ? Math.min(PIP_MOBILE_WIDTH, Math.round(vw * 0.52))
    : Math.min(PIP_DESKTOP_WIDTH, Math.round(vw * 0.44))
  return { w, h: Math.round((w * 9) / 16) + PIP_CHROME_HEIGHT + 2 }
}

// ── Mount points ────────────────────────────────────────────────────
// The owners (LiveView's stage, the dock's slot) register their element
// and clear it on cleanup; the host's effect re-parents on flips.

const [stageSlot, setStageSlot] = createSignal<HTMLElement | null>(null)
const [dockSlot, setDockSlot] = createSignal<HTMLElement | null>(null)

/** The live stage's mount point (registered by LiveView). */
export const pipStageSlot = stageSlot

/** The floating dock's inner mount point (registered by the dock). */
export const pipDockSlot = dockSlot

export function registerStageSlot(el: HTMLElement | null) {
  setStageSlot(el)
}

export function registerDockSlot(el: HTMLElement | null) {
  setDockSlot(el)
}

// ── Dismissal ───────────────────────────────────────────────────────
// ✕ hides the dock without touching the stream. One dismissal covers one
// stream session: the host resets the bit when web-active flips false→true.

const [dismissed, setDismissed] = createSignal(false)

/** True while the user has hidden the dock for the current stream session. */
export const pipDismissed = dismissed

/** Hide the dock; the stream itself keeps running. */
export function dismissPip() {
  setDismissed(true)
}

/** Clear the dismissal (stream-session boundary or tests). */
export function resetPipDismissal() {
  setDismissed(false)
}

// ── Dock position ───────────────────────────────────────────────────

export function readStoredPipPosition(): PipPosition | null {
  try {
    const raw = localStorage.getItem(PIP_STORAGE_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (
      typeof parsed === 'object' && parsed !== null &&
      typeof (parsed as PipPosition).x === 'number' &&
      typeof (parsed as PipPosition).y === 'number'
    ) {
      return { x: (parsed as PipPosition).x, y: (parsed as PipPosition).y }
    }
  } catch {
    /* private mode / corrupt JSON: fall through to the default */
  }
  return null
}

export function storePipPosition(pos: PipPosition): void {
  try {
    localStorage.setItem(PIP_STORAGE_KEY, JSON.stringify(pos))
  } catch {
    /* private mode: the position just won't persist */
  }
}

/** Bottom-right rest position with a viewport margin. */
export function defaultPipPosition(vw: number, vh: number, dockW: number, dockH: number, bottomInset = 0): PipPosition {
  return {
    x: Math.max(PIP_EDGE, vw - dockW - 16),
    y: Math.max(PIP_EDGE, vh - dockH - bottomInset - 16),
  }
}

/**
 * Keep the dock fully inside the viewport: the position is the window's
 * top-left corner, clamped to [edge, vw − w − edge] × [edge, vh − h − inset
 * − edge]. A viewport smaller than the dock pins to the top-left edge.
 */
export function clampPipPosition(x: number, y: number, dockW: number, dockH: number, vw: number, vh: number, bottomInset = 0): PipPosition {
  const maxX = Math.max(PIP_EDGE, vw - dockW - PIP_EDGE)
  const maxY = Math.max(PIP_EDGE, vh - dockH - bottomInset - PIP_EDGE)
  return {
    x: Math.min(Math.max(x, PIP_EDGE), maxX),
    y: Math.min(Math.max(y, PIP_EDGE), maxY),
  }
}

// ── The verdict ─────────────────────────────────────────────────────

/**
 * When the dock shows: away from the live stage, the web stream playing,
 * and not dismissed for this session. Pure so vitest pins the truth table.
 */
export function pipWanted(view: RouteName, webActive: boolean, previewVisible: boolean, dismissed: boolean): boolean {
  return view !== 'live' && webActive && previewVisible && !dismissed
}

// ── Tests ───────────────────────────────────────────────────────────

/** Reset the module singletons between tests (signals + stored position). */
export function resetPipStoreForTests(): void {
  setStageSlot(null)
  setDockSlot(null)
  setDismissed(false)
  try {
    localStorage.removeItem(PIP_STORAGE_KEY)
  } catch {
    /* node env has no localStorage */
  }
}
