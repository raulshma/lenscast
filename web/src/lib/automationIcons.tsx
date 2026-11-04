/**
 * Automation catalog id → inline SVG — the web twin of the app's
 * AutomationVisuals map. Stroke-based feather-style glyphs over the same
 * 24×24 grid the dashboard's other inline icons use (currentColor inherits
 * the badge/row tint, so one path set serves the card, the editor and the
 * pickers). Unknown ids fall back to a neutral dot-in-circle, mirroring the
 * app's FALLBACK visual — a stale-firmware catalog can never blank a badge.
 */
import { Show, type JSX } from 'solid-js'

/** The catalog surface an icon is picked for (the three id spaces overlap). */
export type AutomationIconKind = 'trigger' | 'action' | 'condition'

/** One glyph: the SVG innards (paths/circles), stroked by the wrapper. */
const GLYPHS: Record<string, JSX.Element> = {
  // ── shared / triggers ──
  time_of_day: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 3" />
    </>
  ),
  motion_detected: (
    <>
      <circle cx="12" cy="12" r="2" />
      <path d="M16.2 7.8a6 6 0 010 8.4M19 5a10 10 0 010 14M7.8 7.8a6 6 0 000 8.4M5 5a10 10 0 000 14" />
    </>
  ),
  sound_detected: (
    <>
      <path d="M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4" />
    </>
  ),
  tamper_detected: (
    <>
      <path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" />
      <path d="M12 8v4M12 15v.5" />
    </>
  ),
  charging_changed: (
    <>
      <path d="M13 2L5 13h5l-1 9 8-11h-5z" />
    </>
  ),
  battery_level: (
    <>
      <rect x="2" y="8" width="16" height="8" rx="2" />
      <path d="M22 11v2" />
      <path d="M6 11v2M10 11v2" />
    </>
  ),
  streaming_changed: (
    <>
      <path d="M3 5a14 14 0 0118 0" transform="rotate(90 12 12)" />
      <rect x="7" y="9" width="10" height="8" rx="1.5" />
    </>
  ),
  viewers_changed: (
    <>
      <circle cx="9" cy="9" r="3" />
      <path d="M4 19c0-2.5 2.2-4 5-4s5 1.5 5 4" />
      <circle cx="16.5" cy="10" r="2.2" />
      <path d="M16 15.2c2.4 0 4.2 1.4 4.2 3.6" />
    </>
  ),
  app_started: (
    <>
      <path d="M5 19l2-6 8-8c1.5-1.5 4-2 5-1s.5 3.5-1 5l-8 8z" />
      <path d="M5 19l4-1M9 12l3 3" />
    </>
  ),
  // ── actions ──
  camera_exposure: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4" />
    </>
  ),
  camera_ev: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 3a9 9 0 000 18z" fill="currentColor" stroke="none" />
    </>
  ),
  camera_zoom: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M11 8v6M8 11h6M20 20l-4.5-4.5" />
    </>
  ),
  camera_fps: (
    <>
      <path d="M4 17l5-5-5-5M12 17l5-5-5-5" />
    </>
  ),
  camera_lens: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="4" />
      <path d="M12 3v5M12 16v5M3 12h5M16 12h5" />
    </>
  ),
  torch: (
    <>
      <path d="M9 2h6v3l-1.5 3v12a1.5 1.5 0 01-3 0V8L9 5z" />
      <path d="M9 5h6" />
    </>
  ),
  stream: (
    <>
      <path d="M12 12v9" />
      <path d="M6.3 6.3a8 8 0 000 11.4M17.7 6.3a8 8 0 010 11.4M3.5 3.5a12 12 0 000 17M20.5 3.5a12 12 0 010 17" />
      <circle cx="12" cy="12" r="2" />
    </>
  ),
  capture_photo: (
    <>
      <path d="M4 7h3l2-2h6l2 2h3a1 1 0 011 1v11a1 1 0 01-1 1H4a1 1 0 01-1-1V8a1 1 0 011-1z" />
      <circle cx="12" cy="13" r="3.5" />
    </>
  ),
  recording: (
    <>
      <rect x="3" y="6" width="13" height="12" rx="2" />
      <path d="M16 10l5-3v10l-5-3" />
    </>
  ),
  siren: (
    <>
      <path d="M12 3a6 6 0 00-6 6v5H6a1 1 0 00-1 1v2h14v-2a1 1 0 00-1-1h0V9a6 6 0 00-6-6z" />
      <path d="M10 21h4M12 3V1.5M4 9H2.5M21.5 9H20" />
    </>
  ),
  detection: (
    <>
      <circle cx="12" cy="12" r="2" />
      <path d="M12 5a7 7 0 017 7M12 5a7 7 0 00-7 7M12 1.5A10.5 10.5 0 0122.5 12M12 1.5A10.5 10.5 0 001.5 12" />
    </>
  ),
  // ── conditions ──
  time_window: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 3M8 3.5h8" />
    </>
  ),
  charging: (
    <>
      <path d="M13 3L5 13h5l-1 8 8-10h-5z" />
    </>
  ),
  power_save: (
    <>
      <rect x="2" y="8" width="16" height="8" rx="2" />
      <path d="M22 11v2" />
      <path d="M11 9l-2 3h3l-2 3" />
    </>
  ),
  wifi_connected: (
    <>
      <path d="M3.5 9.5a12 12 0 0117 0M6.5 12.8a8 8 0 0111 0M9.4 16a4 4 0 015.2 0" />
      <circle cx="12" cy="18.5" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  streaming: (
    <>
      <rect x="3" y="5" width="18" height="13" rx="2" />
      <path d="M10 9l5 2.5-5 2.5z" />
      <path d="M8 21h8" />
    </>
  ),
  viewers: (
    <>
      <circle cx="12" cy="9" r="3.5" />
      <path d="M6 19c0-3 2.7-4.5 6-4.5s6 1.5 6 4.5" />
    </>
  ),
  recording_active: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="4" fill="currentColor" stroke="none" />
    </>
  ),
  thermal: (
    <>
      <path d="M10 4a2 2 0 014 0v9.5a4.5 4.5 0 11-4 0z" />
      <path d="M12 9v6" />
    </>
  ),
  motion_detection: (
    <>
      <path d="M4 17l4-6 3 4 4-7 5 9" />
      <path d="M2 20h20" />
    </>
  ),
}

/** condition "torch"/"recording" ids share the trigger/action glyphs' look. */
GLYPHS['torch_on'] = GLYPHS['torch']

/**
 * Shared chrome glyphs — not catalog ids, so they live outside GLYPHS: the
 * clock behind the last-fired line / the WHEN header, and the bolt behind
 * the empty state. Sized by CSS like the other inline chrome icons.
 */
export function AutomationClockGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 3" />
    </svg>
  )
}

export function AutomationBoltGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path d="M13 2L5 13h5l-1 9 8-11h-5z" />
    </svg>
  )
}

/** The neutral fallback for an id this build doesn't know (FALLBACK visual). */
const FALLBACK_GLYPH: JSX.Element = (
  <>
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="2" fill="currentColor" stroke="none" />
  </>
)

export function AutomationIcon(props: { typeId: string; kind: AutomationIconKind; size?: number }) {
  const size = () => props.size ?? 18
  return (
    <svg
      viewBox="0 0 24 24"
      width={size()}
      height={size()}
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <Show when={GLYPHS[props.typeId]} fallback={FALLBACK_GLYPH}>
        {GLYPHS[props.typeId]}
      </Show>
    </svg>
  )
}
