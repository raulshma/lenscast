// Pure keyboard-shortcut mapping for the dashboard — the whip.ts pattern:
// the key→action table and every guard rule are plain functions over
// structural inputs, so vitest pins them without a DOM. The only listener
// lives in hooks/useKeyboardShortcuts.

export type ShortcutAction =
  | 'capture'       // C — capture a photo
  | 'snapshot'      // S — download a high-res snapshot
  | 'toggle-web'    // W — start/stop the web stream
  | 'toggle-rtsp'   // R — start/stop the RTSP stream
  | 'cycle-player'  // P — cycle the player mode (H.264 → MJPEG → HLS)
  | 'gallery'       // G — open/close the gallery
  | 'search'        // / — focus the gallery search
  | 'help'          // ? — show the shortcut overlay

/** The keys as `event.key` reports them ('?' is shift+/ on most layouts). */
const KEY_ACTIONS: Record<string, ShortcutAction> = {
  c: 'capture',
  s: 'snapshot',
  w: 'toggle-web',
  r: 'toggle-rtsp',
  p: 'cycle-player',
  g: 'gallery',
  '/': 'search',
  '?': 'help',
}

export interface KeyEventLike {
  key: string
  ctrlKey?: boolean
  altKey?: boolean
  metaKey?: boolean
  shiftKey?: boolean
}

/**
 * The event→action mapping, or null when no shortcut applies. Modifier keys
 * (ctrl/alt/meta) never fire — those belong to the browser and OS (Ctrl+R
 * must reload, Cmd+S must save) — while shift is allowed because '?' is
 * shift+'/' by construction.
 */
export function shortcutFor(event: KeyEventLike): ShortcutAction | null {
  if (event.ctrlKey || event.altKey || event.metaKey) return null
  const action = KEY_ACTIONS[event.key.toLowerCase()]
  return action ?? null
}

/**
 * The focus guard: shortcuts must never fire while the user is typing.
 * Structural input so tests pass a plain object — tagName is the uppercase
 * HTML tag name, isContentEditable the DOM property.
 */
export function isEditableTarget(target: { tagName?: unknown; isContentEditable?: unknown } | null | undefined): boolean {
  if (!target) return false
  const tag = typeof target.tagName === 'string' ? target.tagName.toUpperCase() : ''
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable === true
}

/** One row of the help overlay, in display order. */
export const SHORTCUT_HELP: ReadonlyArray<{ action: ShortcutAction; key: string; label: string }> = [
  { action: 'capture', key: 'C', label: 'Capture photo' },
  { action: 'snapshot', key: 'S', label: 'Download snapshot' },
  { action: 'toggle-web', key: 'W', label: 'Toggle web stream' },
  { action: 'toggle-rtsp', key: 'R', label: 'Toggle RTSP stream' },
  { action: 'cycle-player', key: 'P', label: 'Cycle player mode' },
  { action: 'gallery', key: 'G', label: 'Open / close gallery' },
  { action: 'search', key: '/', label: 'Focus gallery search' },
  { action: 'help', key: '?', label: 'Show this help' },
]
