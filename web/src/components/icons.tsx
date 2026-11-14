import type { JSX } from 'solid-js'

// Shared stroke-icon module for the next UI. One source of truth for the
// glyphs the shell and cards repeat (the classic UI keeps its inline SVGs).
// All icons: 24×24 viewBox, currentColor stroke, sized via the `size` prop
// or the parent CSS — never hardcoded fill colors.

interface IconProps {
  size?: number
  class?: string
}

function icon(path: JSX.Element) {
  // Solid JSX compiles to real DOM nodes, and a module-level fragment is a
  // single node set: a second mounted usage of one icon (System lives in
  // both the sidebar and the Live inspector FAB) walks the glyphs out of
  // the first mount, leaving it an empty svg. Deep-clone per render so
  // every mount gets its own paths. Icons carry no listeners, so clones
  // lose nothing.
  const clone = () => (Array.isArray(path) ? path : [path]).map((node) => (node as Element).cloneNode(true))
  return (props: IconProps) => (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      width={props.size ?? 20}
      height={props.size ?? 20}
      class={props.class}
      aria-hidden="true"
    >
      {clone()}
    </svg>
  )
}

/** Live view — a camera body with lens. */
export const IconLive = icon(
  <>
    <path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z" />
    <circle cx="12" cy="13" r="4" />
  </>,
)

/** Media — the four-tile gallery grid. */
export const IconMedia = icon(
  <>
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </>,
)

/** Detection — shield with a check. */
export const IconShield = icon(
  <>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    <path d="M9 12l2 2 4-4" />
  </>,
)

/** System — horizontal sliders, the settings verb. */
export const IconSliders = icon(
  <>
    <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3" />
    <path d="M1 14h6M9 8h6M17 16h6" />
  </>,
)

/** Theme: sun (shown when dark — click for light). */
export const IconSun = icon(
  <>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
  </>,
)

/** Theme: moon (shown when light — click for dark). */
export const IconMoon = icon(
  <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />,
)

/** Keyboard shortcuts help. */
export const IconKeyboard = icon(
  <>
    <rect x="2" y="6" width="20" height="12" rx="2" />
    <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M6 14h.01M18 14h.01M9 14h6" stroke-width="2" />
  </>,
)

/** Logout. */
export const IconLogout = icon(
  <>
    <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
    <polyline points="16 17 21 12 16 7" />
    <line x1="21" y1="12" x2="9" y2="12" />
  </>,
)

/** Return to classic UI — undo arrow. */
export const IconUndo = icon(
  <>
    <path d="M1 4v6h6" />
    <path d="M3.51 15a9 9 0 102.13-9.36L1 10" />
  </>,
)

/** Language / locale. */
export const IconGlobe = icon(
  <>
    <circle cx="12" cy="12" r="10" />
    <path d="M2 12h20M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z" />
  </>,
)

/** Warning triangle (connection lost). */
export const IconWarning = icon(
  <>
    <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
    <line x1="12" y1="9" x2="12" y2="13" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </>,
)

/** Viewer (locked) role banner. */
export const IconLock = icon(
  <>
    <rect x="3" y="11" width="18" height="11" rx="2" />
    <path d="M7 11V7a5 5 0 0110 0v4" />
  </>,
)

/** Recording dot — filled circle inside a ring. */
export const IconRecord = icon(
  <>
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="4" fill="currentColor" stroke="none" />
  </>,
)

/* ── Operator console glyphs (shell, telemetry, views) ─────────────── */

/** Close / dismiss. */
export const IconClose = icon(
  <>
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </>,
)

/** Collapse / expand affordances. */
export const IconChevronLeft = icon(<polyline points="15 18 9 12 15 6" />)
export const IconChevronRight = icon(<polyline points="9 18 15 12 9 6" />)

/** Expand — maximize the mini player back onto the live stage. */
export const IconExpand = icon(
  <>
    <polyline points="15 3 21 3 21 9" />
    <polyline points="9 21 3 21 3 15" />
    <line x1="21" y1="3" x2="14" y2="10" />
    <line x1="3" y1="21" x2="10" y2="14" />
  </>,
)

/** Battery — body + charge level tick. */
export const IconBattery = icon(
  <>
    <rect x="2" y="7" width="17" height="10" rx="2" />
    <line x1="22" y1="11" x2="22" y2="13" />
    <line x1="6" y1="11" x2="6" y2="13" stroke-width="2.5" />
  </>,
)

/** Thermal — thermometer. */
export const IconTherm = icon(
  <>
    <path d="M14 14.76V5a2 2 0 00-4 0v9.76a4 4 0 104 0z" />
  </>,
)

/** Connection quality — signal bars. */
export const IconSignal = icon(
  <>
    <line x1="4" y1="18" x2="4" y2="14" stroke-width="2.5" />
    <line x1="9" y1="18" x2="9" y2="10" stroke-width="2.5" />
    <line x1="14" y1="18" x2="14" y2="6" stroke-width="2.5" />
    <line x1="19" y1="18" x2="19" y2="3" stroke-width="2.5" />
  </>,
)

/** Search. */
export const IconSearch = icon(
  <>
    <circle cx="11" cy="11" r="7" />
    <line x1="21" y1="21" x2="16.5" y2="16.5" />
  </>,
)

/** Start / stop transport controls. */
export const IconPlay = icon(<polygon points="7 4 19 12 7 20 7 4" fill="currentColor" stroke="none" />)
export const IconStop = icon(<rect x="6" y="6" width="12" height="12" rx="1.5" fill="currentColor" stroke="none" />)

/** Capture — lens rings. */
export const IconCapture = icon(
  <>
    <circle cx="12" cy="12" r="9" />
    <circle cx="12" cy="12" r="3.5" />
  </>,
)

/** Clipboard copy / copied check. */
export const IconCopy = icon(
  <>
    <rect x="9" y="9" width="13" height="13" rx="2" />
    <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
  </>,
)
export const IconCheck = icon(<polyline points="20 6 9 17 4 12" />)

/** Connected clients. */
export const IconUsers = icon(
  <>
    <path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
    <circle cx="9" cy="7" r="4" />
    <path d="M23 21v-2a4 4 0 00-3-3.87" />
    <path d="M16 3.13a4 4 0 010 7.75" />
  </>,
)

/** Throughput / activity. */
export const IconActivity = icon(<polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />)

/** Storage. */
export const IconFolder = icon(<path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z" />)

/** Device / silicon — system info. */
export const IconChip = icon(
  <>
    <rect x="4" y="4" width="16" height="16" rx="2" />
    <rect x="9" y="9" width="6" height="6" />
    <line x1="9" y1="1" x2="9" y2="4" />
    <line x1="15" y1="1" x2="15" y2="4" />
    <line x1="9" y1="20" x2="9" y2="23" />
    <line x1="15" y1="20" x2="15" y2="23" />
    <line x1="20" y1="9" x2="23" y2="9" />
    <line x1="20" y1="14" x2="23" y2="14" />
    <line x1="1" y1="9" x2="4" y2="9" />
    <line x1="1" y1="14" x2="4" y2="14" />
  </>,
)

/** Link / channels. */
export const IconLink = icon(
  <>
    <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" />
    <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" />
  </>,
)

/** Download / export. */
export const IconDownload = icon(
  <>
    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
    <polyline points="7 10 12 15 17 10" />
    <line x1="12" y1="15" x2="12" y2="3" />
  </>,
)

/** Photo / clip media filters. */
export const IconPhoto = icon(
  <>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <polyline points="21 15 16 10 5 21" />
  </>,
)
export const IconFilm = icon(
  <>
    <rect x="2" y="4" width="20" height="16" rx="2" />
    <line x1="7" y1="4" x2="7" y2="20" />
    <line x1="17" y1="4" x2="17" y2="20" />
    <line x1="2" y1="9" x2="7" y2="9" />
    <line x1="2" y1="15" x2="7" y2="15" />
    <line x1="17" y1="9" x2="22" y2="9" />
    <line x1="17" y1="15" x2="22" y2="15" />
  </>,
)

/** Trash — destructive rows. */
export const IconTrash = icon(
  <>
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
  </>,
)

/** Open in viewer — external arrow. */
export const IconExternal = icon(
  <>
    <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6" />
    <polyline points="15 3 21 3 21 9" />
    <line x1="10" y1="14" x2="21" y2="3" />
  </>,
)

/* ── Camera controls strip ─────────────────────────────────────────── */

/** Plus / minus strokes — the strip's steppers (zoom, exposure). */
export const IconPlus = icon(
  <>
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </>,
)
export const IconMinus = icon(<line x1="5" y1="12" x2="19" y2="12" />)

/** Zoom tele / wide — magnifier with a ± inside. */
export const IconZoomIn = icon(
  <>
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
    <line x1="11" y1="8" x2="11" y2="14" />
    <line x1="8" y1="11" x2="14" y2="11" />
  </>,
)
export const IconZoomOut = icon(
  <>
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
    <line x1="8" y1="11" x2="14" y2="11" />
  </>,
)

/** Torch — the same bolt the classic preview bar uses. */
export const IconTorch = icon(
  <path d="M8 2h8l-1 7h3L10 22l2-9H7l1-11z" />,
)

/** Lens — aperture iris. */
export const IconAperture = icon(
  <>
    <circle cx="12" cy="12" r="10" />
    <line x1="14.31" y1="8" x2="20.05" y2="17.94" />
    <line x1="9.69" y1="8" x2="21.17" y2="8" />
    <line x1="7.38" y1="12" x2="13.12" y2="2.06" />
    <line x1="9.69" y1="16" x2="3.95" y2="6.06" />
    <line x1="14.31" y1="16" x2="2.83" y2="16" />
    <line x1="16.62" y1="12" x2="10.88" y2="21.94" />
  </>,
)

/** Collapse chevron (the strip rotates it 180° when collapsed). */
export const IconChevronDown = icon(<polyline points="6 9 12 15 18 9" />)
