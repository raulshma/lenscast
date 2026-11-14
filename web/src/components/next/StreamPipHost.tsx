import { createEffect, createSignal, onCleanup } from 'solid-js'
import type { NextState } from './NextState'
import { hashRouter, isNextRoute, type RouteName } from '../../lib/router'
import { API_DEFAULTS } from '../../api/defaults'
import { t } from '../../lib/i18n'
import { Led } from './ui'
import { IconClose, IconExpand, IconStop } from '../icons'
import CameraStrip from './views/live/CameraStrip'
import StreamPreview from '../StreamPreview'
import {
  PIP_MOBILE_BOTTOM_INSET,
  PIP_MOBILE_BREAKPOINT,
  clampPipPosition,
  defaultDockSize,
  defaultPipPosition,
  dismissPip,
  pipDismissed,
  pipDockSlot,
  pipStageSlot,
  pipWanted,
  readStoredPipPosition,
  registerDockSlot,
  resetPipDismissal,
  storePipPosition,
  type PipPosition,
} from '../../lib/pipStore'

/**
 * The persistent stream host: StreamPreview mounted ONCE for the shell's
 * lifetime, with its container re-parented between the live stage slot
 * (LiveView), the floating mini-player dock, and a hidden holding pen.
 * Navigating away from Live no longer tears down the player — the
 * WHEP/WS/MSE session rides along in the dock, and returning to Live
 * re-docks the same elements with zero renegotiation. The dock itself is
 * always mounted (visibility is a class flip) so its slot element is a
 * stable re-parent target in the same flush as a route flip.
 */
export default function StreamPipHost(props: { state: NextState }) {
  const state = props.state
  const router = hashRouter()

  const view = (): RouteName => {
    const name = router.route().name
    return isNextRoute(name) ? name : 'live'
  }
  const webActive = () => state.status()?.streaming?.webStreamingActive ?? API_DEFAULTS.webStreamingActive
  const pipMode = () => pipWanted(view(), webActive(), state.previewVisible(), pipDismissed())
  const bottomInset = () =>
    typeof window !== 'undefined' && window.innerWidth < PIP_MOBILE_BREAKPOINT ? PIP_MOBILE_BOTTOM_INSET : 0

  // One dismissal covers one stream session: ✕ stashes the dock until the
  // stream actually goes down and comes back. The prev-value form is
  // mandatory — webActive() is re-pushed every SSE tick, and an ungated
  // reset would resurrect the dock the second after it was dismissed.
  createEffect((prevActive) => {
    const active = webActive()
    if (active && !prevActive) resetPipDismissal()
    return active
  }, false)

  // ── Dock position ────────────────────────────────────────────────
  // Rests bottom-right unless a stored drag position says otherwise;
  // a stored (or default-seeded) position is re-clamped against the
  // measured on-screen size whenever the dock becomes visible and on
  // window resizes — measurement needs the settled layout, so it runs
  // a frame after the flip rather than mid-effect.
  const initialPosition = (): PipPosition => {
    const vw = typeof window !== 'undefined' ? window.innerWidth : 1280
    const vh = typeof window !== 'undefined' ? window.innerHeight : 800
    const { w, h } = defaultDockSize(vw)
    return readStoredPipPosition() ?? defaultPipPosition(vw, vh, w, h, bottomInset())
  }
  const [position, setPosition] = createSignal<PipPosition>(initialPosition())
  const [pipEl, setPipEl] = createSignal<HTMLDivElement | null>(null)

  const clampToViewport = () => {
    const el = pipEl()
    if (!el) return
    const rect = el.getBoundingClientRect()
    if (rect.width === 0) return // not laid out (hidden, or jsdom)
    setPosition((p) => {
      const clamped = clampPipPosition(p.x, p.y, rect.width, rect.height, window.innerWidth, window.innerHeight, bottomInset())
      return clamped.x === p.x && clamped.y === p.y ? p : clamped
    })
  }

  createEffect(() => {
    if (!pipMode()) return
    pipEl() // track the ref so the very first flip also measures
    requestAnimationFrame(() => requestAnimationFrame(clampToViewport))
  })

  window.addEventListener('resize', clampToViewport)
  onCleanup(() => window.removeEventListener('resize', clampToViewport))

  // Drag by the chrome bar. The move/up listeners live on the window for
  // the hold's lifetime — the drag survives the pointer leaving the dock
  // (and browsers whose pointer capture is unavailable), with capture as
  // the extra keep-alive. Buttons inside the bar opt out of the drag.
  let drag: { pointerId: number; offsetX: number; offsetY: number } | null = null
  const moveDrag = (e: PointerEvent) => {
    if (!drag || e.pointerId !== drag.pointerId) return
    const el = pipEl()
    if (!el) return
    const rect = el.getBoundingClientRect()
    setPosition(clampPipPosition(
      e.clientX - drag.offsetX,
      e.clientY - drag.offsetY,
      rect.width, rect.height,
      window.innerWidth, window.innerHeight,
      bottomInset(),
    ))
  }
  const endDrag = (e: PointerEvent) => {
    if (!drag || e.pointerId !== drag.pointerId) return
    drag = null
    window.removeEventListener('pointermove', moveDrag)
    window.removeEventListener('pointerup', endDrag)
    window.removeEventListener('pointercancel', endDrag)
    storePipPosition(position())
  }
  const startDrag = (e: PointerEvent) => {
    if ((e.target as HTMLElement | null)?.closest('button')) return
    const el = pipEl()
    if (!el) return
    const rect = el.getBoundingClientRect()
    drag = { pointerId: e.pointerId, offsetX: e.clientX - rect.left, offsetY: e.clientY - rect.top }
    window.addEventListener('pointermove', moveDrag)
    window.addEventListener('pointerup', endDrag)
    window.addEventListener('pointercancel', endDrag)
    try { el.setPointerCapture(e.pointerId) } catch { /* pointer capture unavailable */ }
  }
  onCleanup(() => {
    window.removeEventListener('pointermove', moveDrag)
    window.removeEventListener('pointerup', endDrag)
    window.removeEventListener('pointercancel', endDrag)
  })

  // ── Re-parenting ─────────────────────────────────────────────────
  const [hostEl, setHostEl] = createSignal<HTMLDivElement | null>(null)
  const [holdingEl, setHoldingEl] = createSignal<HTMLDivElement | null>(null)
  const dockSlotRef = (el: HTMLDivElement) => {
    registerDockSlot(el)
    onCleanup(() => registerDockSlot(null))
  }

  createEffect(() => {
    const host = hostEl()
    if (!host) return
    // Every dependency read unconditionally, so a flip in any of them
    // re-runs the effect no matter which branch ran last.
    const onLive = view() === 'live'
    const wantPip = pipMode()
    const stage = pipStageSlot()
    const dock = pipDockSlot()
    const target = onLive ? stage : (wantPip ? dock : null)
    if (target) {
      if (host.parentElement !== target) target.appendChild(host)
      return
    }
    const holding = holdingEl()
    if (holding && host.parentElement !== holding) holding.appendChild(host)
  })

  const canStop = () => !state.isViewer() && !state.streamActionLoading()
  const expandToLive = () => router.navigate({ name: 'live' })

  return (
    <>
      {/* The holding pen: a parked host stays alive (players keep running)
          but out of layout entirely. */}
      <div class="nx-pip-holding" ref={setHoldingEl} />

      {/* The dock — always in the DOM, visibility is a class flip. */}
      <div
        class="nx-pip"
        classList={{ 'nx-pip-hidden': !pipMode() }}
        ref={setPipEl}
        style={{ left: `${position().x}px`, top: `${position().y}px` }}
        aria-hidden={!pipMode()}
      >
        <div class="nx-pip-chrome" onPointerDown={startDrag}>
          <span class="nx-pip-live">
            <Led tone="rec" />
            {t('next.pip.title')}
          </span>
          <button
            type="button"
            class="nx-pip-btn nx-pip-btn-stop"
            onClick={() => { if (canStop()) state.handleStopWebStream() }}
            disabled={!canStop()}
            title={t('next.pip.stop')}
            aria-label={t('next.pip.stop')}
          >
            <IconStop size={14} />
          </button>
          <button
            type="button"
            class="nx-pip-btn"
            onClick={expandToLive}
            title={t('next.pip.expand')}
            aria-label={t('next.pip.expand')}
          >
            <IconExpand size={14} />
          </button>
          <button
            type="button"
            class="nx-pip-btn"
            onClick={() => dismissPip()}
            title={t('next.pip.close')}
            aria-label={t('next.pip.close')}
          >
            <IconClose size={14} />
          </button>
        </div>
        <div class="nx-pip-slot" ref={dockSlotRef}>
          {/* Clicking the picture expands back to the stage; this layer
              also keeps the tiny window clear of tap-to-focus/zoom. */}
          <button
            type="button"
            class="nx-pip-tap"
            onClick={expandToLive}
            aria-label={t('next.pip.expand')}
            title={t('next.pip.expand')}
          />
        </div>
      </div>

      {/* The persistent player: view switches move this container, they
          never unmount it. */}
      <div class="nx-stream-host" ref={setHostEl}>
        <StreamPreview
          status={state.status}
          previewVisible={state.previewVisible}
          streamNonce={state.streamNonce}
          streamActionLoading={state.streamActionLoading}
          isRecording={state.isRecording}
          captureMsg={state.captureMsg}
          liveAudioStatus={state.liveAudioStatus}
          recordingTimer={state.recordingTimer}
          playerMode={state.playerMode}
          setPlayerMode={state.setPlayerMode}
          handleCapture={state.handleCapture}
          handleStartWebStream={state.handleStartWebStream}
          handleStopWebStream={state.handleStopWebStream}
          handleStartRtspStream={state.handleStartRtspStream}
          handleStopRtspStream={state.handleStopRtspStream}
          setPreviewVisible={state.setPreviewVisible}
          connectionLost={state.connectionLost}
          stripOverlay={() => <CameraStrip state={state} />}
        />
      </div>
    </>
  )
}
