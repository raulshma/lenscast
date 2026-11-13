import { createEffect, createMemo, createSignal, onCleanup } from 'solid-js'
import * as api from '../api/client'
import { API_DEFAULTS } from '../api/defaults'
import {
  CAM_STRIP_STORAGE_KEY,
  clampExposureIndex,
  clampRatio,
  evPressGuard,
  parseStripCollapsed,
  resetZoom,
  steppedZoom,
  stripCollapsedStorageValue,
  ZOOM_MIRROR_HOLD_MS,
  zoomFlushDelayMs,
  zoomSendVerdict,
} from '../lib/cameraControlsPolicy'
import { flashLater } from '../lib/flashLater'
import { t } from '../lib/i18n'
import type {
  AllSettings, CameraSettings, ControlRange, DeviceStatus,
  FocusMode, LensInfo, WhiteBalance,
} from '../types'

/**
 * What the camera controls strip needs from the one app state. The strip
 * never owns truth — the device does: torch and zoom mirrors resync from the
 * 1 s status push (each snapshot settles any raced optimistic flip, which is
 * also how a failed action self-corrects), and the exposure mirror resyncs
 * from the settings mirror once a post-press window has closed.
 */
export interface CameraControlsDeps {
  status: () => DeviceStatus | null
  lenses: () => LensInfo[]
  settings: () => AllSettings | null
  isViewer: () => boolean
  updateCamera: (patch: Partial<CameraSettings>) => void
  handleSelectLens: (index: number) => Promise<void>
}

export interface CameraControls {
  /** False for the viewer pair — every camera write is admin-only on the device. */
  canControl: () => boolean
  collapsed: () => boolean
  toggleCollapsed: () => void
  zoomRatio: () => number
  zoomMax: () => number
  /** Optimistic + throttled: a slider drag lands as at most one POST per window plus a final flush. */
  setZoom: (ratio: number) => void
  zoomIn: () => void
  zoomOut: () => void
  resetWide: () => void
  torchOn: () => boolean
  toggleTorch: () => void
  exposure: () => number
  /** The device's published EV index range, or null while unknown (renderers fall back). */
  evRange: () => ControlRange | null
  stepExposure: (direction: 1 | -1) => void
  focusMode: () => FocusMode | null
  setFocusMode: (mode: FocusMode) => void
  whiteBalance: () => WhiteBalance | null
  setWhiteBalance: (wb: WhiteBalance) => void
  lensOptions: () => LensInfo[]
  selectedLensIndex: () => number | null
  selectLens: (index: number) => void
  /** Transient failure line for actions the device refused; self-clears. */
  stripMsg: () => string
}

export function createCameraControls(deps: CameraControlsDeps): CameraControls {
  // ── Mirrors ──
  const [zoomRatio, setZoomRatio] = createSignal(1)
  const [torchOn, setTorchOn] = createSignal(false)
  const [exposure, setExposure] = createSignal(0)
  const [stripMsg, setStripMsg] = createSignal('')
  const flashStripMsg = flashLater(setStripMsg, 4000)

  // Zoom mirrors the device the same way the old preview buttons did, but a
  // focused slider belongs to an in-flight manual drag — never yank it from
  // under the user's pointer. The id doubles as the drag guard; a recent
  // stepper press gets the same hold (its echo can still carry the old ratio),
  // so the mirror only resyncs from quiet-device snapshots.
  let lastZoomActionAt = 0
  const zoomMax = () => deps.status()?.zoomRange?.max ?? API_DEFAULTS.cameraZoomMaxRatio
  createEffect(() => {
    const deviceZoom = deps.status()?.zoomRatio
    const dragging = document.activeElement?.id === 'remote-zoom-slider'
    if (deviceZoom != null && !dragging && !evPressGuard(lastZoomActionAt, Date.now(), ZOOM_MIRROR_HOLD_MS)) {
      setZoomRatio(Math.min(deviceZoom, zoomMax()))
    }
  })

  // Torch truth lives on the device: the status push is authoritative, and
  // the local optimistic flip in toggleTorch below only bridges the round-trip.
  createEffect(() => {
    const deviceTorch = deps.status()?.torchOn
    if (deviceTorch != null) setTorchOn(deviceTorch)
  })

  // The exposure mirror resyncs from the settings mirror (which updateCamera
  // writes optimistically and the slow settings poll confirms) — but a just-
  // pressed stepper keeps its value for a short window, so a 30 s poll echo
  // can never visibly revert a tap.
  let lastEvPressAt = 0
  createEffect(() => {
    const deviceEv = deps.settings()?.camera?.exposureCompensation
    if (deviceEv != null && !evPressGuard(lastEvPressAt, Date.now())) setExposure(deviceEv)
  })

  // ── Zoom: optimistic mirror + throttled sends ──
  // Leading send on the first input of a burst, then at most one trailing
  // flush carrying the newest ratio — a slider drag produces a handful of
  // POSTs instead of one per input event.
  let lastZoomSentAt = 0
  let zoomFlushTimer: ReturnType<typeof setTimeout> | null = null
  onCleanup(() => { if (zoomFlushTimer) clearTimeout(zoomFlushTimer) })

  function sendZoom(ratio: number) {
    api.setZoom(ratio).catch(() => flashStripMsg(t('cam.zoomFailed')))
  }

  function setZoom(ratio: number) {
    const clamped = clampRatio(ratio, zoomMax())
    setZoomRatio(clamped)
    const now = Date.now()
    lastZoomActionAt = now
    if (zoomSendVerdict(now, lastZoomSentAt) === 'send') {
      if (zoomFlushTimer) clearTimeout(zoomFlushTimer)
      zoomFlushTimer = null
      lastZoomSentAt = now
      sendZoom(clamped)
    } else if (!zoomFlushTimer) {
      zoomFlushTimer = setTimeout(() => {
        zoomFlushTimer = null
        lastZoomSentAt = Date.now()
        sendZoom(zoomRatio())
      }, zoomFlushDelayMs(now, lastZoomSentAt))
    }
  }

  const zoomIn = () => setZoom(steppedZoom(zoomRatio(), 1, zoomMax()))
  const zoomOut = () => setZoom(steppedZoom(zoomRatio(), -1, zoomMax()))
  const resetWide = () => setZoom(resetZoom())

  // ── Torch: optimistic flip, device snapshot settles it ──
  function toggleTorch() {
    const next = !torchOn()
    setTorchOn(next)
    api.setTorch(next).catch(() => {
      setTorchOn(!next)
      flashStripMsg(t('cam.torchFailed'))
    })
  }

  // ── Exposure compensation: integer index steps through updateCamera ──
  const evRange = () => deps.status()?.exposureCompensationRange ?? null

  function stepExposure(direction: 1 | -1) {
    const next = clampExposureIndex(exposure() + direction, evRange())
    if (next === exposure()) return
    setExposure(next)
    lastEvPressAt = Date.now()
    deps.updateCamera({ exposureCompensation: next })
  }

  // ── Focus / white balance quick-settings (MANUAL WB stays settings-only:
  //    it needs a Kelvin slider the strip does not carry) ──
  const focusMode = () => deps.settings()?.camera?.focusMode ?? null
  const setFocusMode = (mode: FocusMode) => deps.updateCamera({ focusMode: mode })
  const whiteBalance = () => deps.settings()?.camera?.whiteBalance ?? null
  const setWhiteBalance = (wb: WhiteBalance) => deps.updateCamera({ whiteBalance: wb })

  // ── Lens picker ──
  const lensOptions = () => deps.lenses()
  const selectedLensIndex = createMemo(() => {
    const selected = deps.lenses().find((lens) => lens.selected)
    return selected ? selected.index : null
  })

  function selectLens(index: number) {
    void deps.handleSelectLens(index).catch(() => flashStripMsg(t('cam.lensFailed')))
  }

  // ── Collapse: persisted under lenscast.camStrip (uiMode's key pattern) ──
  const [collapsed, setCollapsed] = createSignal(parseStripCollapsed(readStoredCollapsed()))
  function toggleCollapsed() {
    const next = !collapsed()
    try {
      localStorage.setItem(CAM_STRIP_STORAGE_KEY, stripCollapsedStorageValue(next))
    } catch {
      // Storage failures keep the toggle working for this session.
    }
    setCollapsed(next)
  }

  return {
    canControl: () => !deps.isViewer(),
    collapsed, toggleCollapsed,
    zoomRatio, zoomMax, setZoom, zoomIn, zoomOut, resetWide,
    torchOn, toggleTorch,
    exposure, evRange, stepExposure,
    focusMode, setFocusMode,
    whiteBalance, setWhiteBalance,
    lensOptions, selectedLensIndex, selectLens,
    stripMsg,
  }
}

function readStoredCollapsed(): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(CAM_STRIP_STORAGE_KEY) : null
  } catch {
    return null
  }
}
