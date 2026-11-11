// The full-screen preview's decisions, extracted pure (the previewRetryPolicy
// / transportSwitch shape: the browser primitives — requestFullscreen, the
// wake-lock sentinel, the DOM listeners — stay in hooks/useFullscreen, and the
// verdicts themselves are vitest-pinned without one).
//
// Two capabilities degrade independently: *fullscreen* (native API, with a
// universal CSS fixed-overlay fallback for iOS Safari) and the *screen wake
// lock* (needs both the API and a secure context). The wake lock is
// fullscreen-scoped only — an active stream alone never holds the screen.

export type FullscreenMode = 'native' | 'css' | 'unavailable'

export interface FullscreenSupport {
  /** The container element exposes requestFullscreen (vendor prefixes normalized by the hook). */
  nativeFullscreen: boolean
  /** navigator.wakeLock exists. */
  wakeLock: boolean
  /** window.isSecureContext — the wake lock requires it (fullscreen itself does not). */
  secureContext: boolean
  /** The CSS fixed-overlay fallback is available (universal in a browser; capability-gated, not assumed). */
  cssOverlay: boolean
}

/** Fold the hook's raw capability probes into the record every verdict reads. */
export function detectSupport(probes: {
  requestFullscreen: boolean
  wakeLock: boolean
  secureContext: boolean
  cssOverlay: boolean
}): FullscreenSupport {
  return {
    nativeFullscreen: probes.requestFullscreen,
    wakeLock: probes.wakeLock,
    secureContext: probes.secureContext,
    cssOverlay: probes.cssOverlay,
  }
}

/**
 * Which fullscreen rung to take. Native when the API exists and its request
 * has not been rejected; otherwise the CSS overlay (iOS Safari, or a native
 * request that threw). `unavailable` only when even the overlay is gated off.
 */
export function chooseMode(support: FullscreenSupport, nativeRejected: boolean): FullscreenMode {
  if (support.nativeFullscreen && !nativeRejected) return 'native'
  if (support.cssOverlay) return 'css'
  return 'unavailable'
}

export type WakeLockVerdict = 'acquire' | 'release' | 'noop'

/**
 * The screen wake-lock verdict. Acquire only while fullscreen AND the stream
 * is active AND the API + secure context allow it; release whenever either
 * fullscreen or the stream ends (the hook no-ops a release with nothing held);
 * otherwise no-op (wanted but unsupported — the graceful degradation). The
 * fullscreen-scoped rule is the load-bearing pin: an active stream with no
 * fullscreen never acquires.
 */
export function wakeLockVerdict(
  support: FullscreenSupport,
  fullscreenActive: boolean,
  streamActive: boolean,
): WakeLockVerdict {
  if (!fullscreenActive || !streamActive) return 'release'
  if (support.wakeLock && support.secureContext) return 'acquire'
  return 'noop'
}

/**
 * Whether to re-acquire on a visibility regain: the browser releases a screen
 * wake lock when the tab hides, so returning to a still-fullscreen, still-live
 * preview must request it again — under the same capability gate.
 */
export function shouldReacquire(
  support: FullscreenSupport,
  fullscreenActive: boolean,
  streamActive: boolean,
  visible: boolean,
): boolean {
  return visible && wakeLockVerdict(support, fullscreenActive, streamActive) === 'acquire'
}

export type EscapeVerdict = 'exit' | 'defer' | 'noop'

/**
 * Who owns Escape while fullscreen. Native: the browser exits fullscreen on
 * Escape itself, so the hook does nothing. CSS: the hook exits — unless a
 * higher overlay (the shared media viewer, the shortcuts help) owns Escape
 * first, in which case it defers.
 */
export function escapeVerdict(mode: FullscreenMode, higherOverlayActive: boolean): EscapeVerdict {
  if (mode === 'native') return 'noop'
  if (higherOverlayActive) return 'defer'
  return 'exit'
}

/**
 * The one subtle hint to flash, or null. `unavailable` explains a browser that
 * cannot offer fullscreen at all; once fullscreen is up, an insecure context
 * with the wake-lock API present explains why the screen may still sleep
 * (HTTPS is the fix). A missing wake-lock API degrades silently — fullscreen
 * works, the keep-awake simply is not there to miss.
 */
export function hintKey(
  mode: FullscreenMode,
  support: FullscreenSupport,
  fullscreenActive: boolean,
): string | null {
  if (mode === 'unavailable') return 'preview.fullscreenUnavailable'
  if (!fullscreenActive) return null
  if (support.wakeLock && !support.secureContext) return 'preview.fullscreenNeedsHttps'
  return null
}