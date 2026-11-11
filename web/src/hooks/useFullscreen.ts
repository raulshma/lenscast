import { createEffect, createSignal, onCleanup, untrack } from 'solid-js'
import {
  chooseMode,
  detectSupport,
  escapeVerdict,
  hintKey,
  shouldReacquire,
  wakeLockVerdict,
  type FullscreenMode,
  type FullscreenSupport,
} from '../lib/fullscreenPolicy'

interface UseFullscreenOptions {
  /** The element to fullscreen — the shared preview container (covers every player rung). */
  container: () => HTMLElement | null
  /** True while the preview is actually showing a live stream (the wake lock's stream gate). */
  streamActive: () => boolean
  /** A higher overlay (media viewer, shortcuts help) owns Escape first. */
  higherOverlayActive?: () => boolean
  /** Lifts the active bit to App so the gallery defers Escape while fullscreen. */
  onActiveChange?: (active: boolean) => void
  /** Flashes a degradation hint (the caller's flashLater helper). */
  onHint?: (key: string) => void
}

interface UseFullscreenReturn {
  active: () => boolean
  mode: () => FullscreenMode
  cssOverlay: () => boolean
  wakeLockHeld: () => boolean
  toggle: () => void
  exit: () => void
}

type WebkitFullscreenElement = HTMLElement & { webkitRequestFullscreen?: () => Promise<void> }

/**
 * The full-screen preview's browser primitives, wrapped around the pure
 * fullscreenPolicy verdicts: native requestFullscreen on the container with a
 * CSS fixed-overlay fallback (iOS Safari), a fullscreen-scoped screen wake
 * lock that re-acquires on visibility regain, and Escape ownership per the
 * policy. Every API is guarded — a missing primitive degrades, never throws.
 */
export function useFullscreen(options: UseFullscreenOptions): UseFullscreenReturn {
  const { container, streamActive } = options

  const [active, setActive] = createSignal(false)
  const [mode, setMode] = createSignal<FullscreenMode>('native')
  const [wakeLockHeld, setWakeLockHeld] = createSignal(false)
  const cssOverlay = () => active() && mode() === 'css'

  // Sticky once a native request is rejected (iOS Safari rejects every time):
  // later toggles go straight to the overlay instead of re-trying a dead API.
  let nativeRejected = false
  let sentinel: WakeLockSentinel | null = null

  function probeSupport(): FullscreenSupport {
    const el = container() as WebkitFullscreenElement | null
    const native = !!el && (
      typeof el.requestFullscreen === 'function' ||
      typeof el.webkitRequestFullscreen === 'function'
    )
    return detectSupport({
      requestFullscreen: native,
      wakeLock: typeof navigator !== 'undefined' && !!navigator.wakeLock,
      secureContext: typeof window !== 'undefined' && window.isSecureContext === true,
      // The fixed-overlay fallback works in every browser that runs this
      // dashboard; recorded as a capability so the verdict stays gated.
      cssOverlay: true,
    })
  }

  function wantLock(): boolean {
    return wakeLockVerdict(probeSupport(), untrack(active), untrack(streamActive)) === 'acquire'
  }

  async function acquire() {
    if (sentinel) return
    const wakeLock = navigator.wakeLock
    if (!wakeLock) return
    try {
      const requested = await wakeLock.request('screen')
      // A release may have landed while the request was in flight (the tab
      // hid, the stream stopped) — don't strand a lock nobody wants.
      if (!wantLock()) {
        void requested.release().catch(() => {})
        return
      }
      sentinel = requested
      setWakeLockHeld(true)
      requested.addEventListener('release', () => {
        if (sentinel === requested) {
          sentinel = null
          setWakeLockHeld(false)
        }
      })
    } catch {
      // Graceful: a refused lock leaves the preview fully usable.
    }
  }

  function release() {
    const held = sentinel
    sentinel = null
    setWakeLockHeld(false)
    if (held) void held.release().catch(() => {})
  }

  function flashHint() {
    const key = hintKey(untrack(mode), probeSupport(), true)
    if (key) options.onHint?.(key)
  }

  function enterCss() {
    setMode('css')
    setActive(true)
    flashHint()
  }

  function enter() {
    const el = container() as WebkitFullscreenElement | null
    const chosen = chooseMode(probeSupport(), nativeRejected)
    if (chosen === 'unavailable' || !el) {
      options.onHint?.('preview.fullscreenUnavailable')
      return
    }
    if (chosen === 'native') {
      const request = el.requestFullscreen?.bind(el) ?? el.webkitRequestFullscreen?.bind(el)
      if (request) {
        request().then(() => {
          setMode('native')
          setActive(true)
          flashHint()
        }).catch(() => {
          nativeRejected = true
          enterCss()
        })
        return
      }
    }
    enterCss()
  }

  function exit() {
    if (untrack(mode) === 'native' && document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {})
    }
    setActive(false)
  }

  function toggle() {
    if (untrack(active)) exit()
    else enter()
  }

  // The wake lock follows the verdict: acquire while fullscreen + live +
  // capable, release when either ends. probeSupport reads the container so a
  // late-mounted element is picked up.
  createEffect(() => {
    const verdict = wakeLockVerdict(probeSupport(), active(), streamActive())
    if (verdict === 'acquire') void acquire()
    else if (verdict === 'release') release()
  })

  createEffect(() => {
    options.onActiveChange?.(active())
  })

  function handleFullscreenChange() {
    const el = container()
    if (document.fullscreenElement && document.fullscreenElement === el) {
      setMode('native')
      setActive(true)
    } else if (untrack(mode) === 'native') {
      // Native exit (the browser's Escape, or exitFullscreen) — the CSS
      // overlay's active bit is owned by enter/exit, not this event.
      setActive(false)
    }
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key !== 'Escape' || !untrack(active)) return
    const verdict = escapeVerdict(untrack(mode), options.higherOverlayActive?.() ?? false)
    if (verdict === 'exit') {
      e.preventDefault()
      exit()
    }
  }

  function handleVisibility() {
    if (shouldReacquire(probeSupport(), untrack(active), untrack(streamActive), !document.hidden)) {
      void acquire()
    }
  }

  createEffect(() => {
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    document.addEventListener('keydown', handleKeyDown)
    document.addEventListener('visibilitychange', handleVisibility)
    onCleanup(() => {
      document.removeEventListener('fullscreenchange', handleFullscreenChange)
      document.removeEventListener('keydown', handleKeyDown)
      document.removeEventListener('visibilitychange', handleVisibility)
      release()
      if (untrack(active) && untrack(mode) === 'native' && document.fullscreenElement) {
        void document.exitFullscreen().catch(() => {})
      }
    })
  })

  return { active, mode, cssOverlay, wakeLockHeld, toggle, exit }
}