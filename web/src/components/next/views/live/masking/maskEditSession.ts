import { batch, createSignal } from 'solid-js'
import type { MaskingZone } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { saveStreamingPatch } from '../../../../../api/client'
import { t } from '../../../../../lib/i18n'
import {
  clampPoint,
  createLatestWinsQueue,
  dragBounds,
  moveZone,
  resizeZone,
  roundZone,
  sameRect,
  sameZone,
  zoneFromDrag,
  zonesEqual,
  type ResizeHandle,
  type ZonePoint,
  type ZoneRect,
} from '../../../../../lib/maskingZonePolicy'
import { endMaskEdit, maskEditFocusZoneId } from '../../../../../lib/maskEditStore'
import type { NextState } from '../../../NextState'
import { pushToast } from '../../../ui/toast'

/**
 * The masking-zone editor's controller: everything the Live stage's picture
 * layer and the Live view's inspector do to the zones, without either of
 * them. A plain factory, not a component — it owns signals, no DOM — so the
 * edit logic runs in a node test and the two UIs built on it stay picture
 * code and chrome code.
 *
 * Three things make editing safe next to the app's settings machinery:
 *
 * - The draft is the editor's own copy of the zone list. It is seeded once
 *   from the settings mirror and never again — every settings GET (the 30 s
 *   poll included) replaces that mirror wholesale, and a draft derived from
 *   it would blink back to the stored zones mid-edit.
 * - Every save is a targeted `saveStreamingPatch({ maskingZones })`, not the
 *   panel's whole-section debounced PUT, and the writes go through a
 *   latest-wins queue: one on the wire, the rest collapsed into the last.
 * - "Done" is the one step that also updates the mirror, through
 *   `updateStreamingDebounced` like every other streaming control — and
 *   only when the draft still differs from it.
 */

/** The slice of NextState the editor reads and drives; the app's whole state object satisfies it. */
export type MaskEditState = Pick<NextState, 'settings' | 'playerMode' | 'setPlayerMode' | 'updateStreamingDebounced'>

/** What a command may change on a zone. The id is the zone's identity and never moves. */
export type MaskEditZonePatch = Partial<Omit<MaskingZone, 'id'>>

/** The gesture in progress. The geometry it works from stays inside the session. */
export type MaskEditGesture =
  | { kind: 'draw' }
  | { kind: 'move'; zoneId: string }
  | { kind: 'resize'; zoneId: string; handle: ResizeHandle }

export interface MaskEditSessionOptions {
  /**
   * The zone to select when the editor opens; an id that matches no zone
   * falls back to the first one. Read from the store (`startMaskEdit`'s
   * `zoneId`) when the key is absent; a `null` here means "select the first".
   */
  focusZoneId?: string | null
  /** Sends one zone list to the device. Default: `saveStreamingPatch({ maskingZones })`. */
  commit?: (zones: MaskingZone[]) => Promise<void>
  /** Reports a failed commit. Default: a danger toast, `privacy.editor.saveFailed`. */
  onError?: (error: unknown) => void
}

export interface MaskEditSession {
  // ── Reactive reads ──
  /** The draft zone list, in order. It changes only through this session. */
  zones(): MaskingZone[]
  /** The selected zone's id, or null when none is selected. */
  selectedId(): string | null
  /** The selected zone, or null when none is selected. */
  selectedZone(): MaskingZone | null
  /** True while a drag on the bare picture draws a zone (armed by an empty list, or by toggleDrawing). */
  drawing(): boolean
  /** The gesture in progress, or null between gestures. */
  gesture(): MaskEditGesture | null
  /**
   * The rectangle the draw in progress spans, clipped to the picture and of
   * any size — the rubber band, from the first pixel of movement. Null
   * whenever no draw is in progress.
   */
  rubberBand(): ZoneRect | null

  // ── Gestures — every point is normalized against the picture (0..1, origin top-left) ──
  /** Starts a rubber band at `point`. A no-op unless drawing is armed. */
  beginDraw(point: ZonePoint): void
  /** Starts moving `zoneId`; the zone is selected. A no-op for an unknown id. */
  beginMove(zoneId: string, point: ZonePoint): void
  /** Starts dragging `handle` of `zoneId`; the zone is selected. */
  beginResize(zoneId: string, handle: ResizeHandle, point: ZonePoint): void
  /** Moves the gesture's pointer to `point`, updating the draft (or the rubber band) live. No save. */
  dragTo(point: ZonePoint): void
  /** Ends the gesture and saves — exactly once, and only if a zone actually changed. */
  endGesture(): void
  /** Drops the gesture without saving: the draft goes back to how the gesture began. */
  cancelGesture(): void

  // ── Commands — each one that changes a zone also saves it ──
  /** Selects a zone of the draft, or none (`null` / an unknown id). */
  select(zoneId: string | null): void
  /** Flips whether a drag on the bare picture draws a zone. */
  toggleDrawing(): void
  /**
   * Applies `patch` to a zone — the selected one unless `zoneId` names
   * another. Geometry goes through the frame rule and lands on the
   * four-decimal grid; a number that is not one (an empty field parses to
   * NaN) leaves that field alone. Nothing happens when nothing changes.
   */
  updateZone(patch: MaskEditZonePatch, zoneId?: string | null): void
  /** Removes a zone. Removing the selected one moves the selection to its neighbour. */
  removeZone(zoneId: string): void
  /** Moves the selected zone by a fixed step, stopping at the frame's edges. */
  nudge(dx: number, dy: number): void

  // ── The masking master switch ──
  /** True while the device's masking master switch is on — the gate a drawn zone paints behind on the stream. */
  maskingEnabled(): boolean
  /** Turns the masking master switch on or off, through the streaming mirror like every streaming control. */
  setMaskingEnabled(on: boolean): void

  // ── Lifecycle ──
  /** "Done": publishes the final list to the settings mirror if it still differs, then ends the edit. */
  finish(): void
  /** Releases the editor without publishing — the owner is going away. */
  dispose(): void
}

// randomUUID exists only in secure contexts, and the dashboard is routinely
// opened over plain HTTP on a LAN — the same fallback the Privacy Masking
// panel's Add Zone uses.
function newZoneId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

// A drawn zone starts as the panel's Add Zone makes one, and as the device's
// own default: an enabled blackout with the standard pixel size and blur
// radius, ready for whichever type the inspector then picks.
function createZone(rect: ZoneRect): MaskingZone {
  return {
    id: newZoneId(),
    label: '',
    enabled: true,
    type: 'BLACKOUT',
    x: rect.x,
    y: rect.y,
    width: rect.width,
    height: rect.height,
    pixelateSize: 16,
    blurRadius: 10,
  }
}

/** A number that can be used, or the zone's current value for one that cannot. */
function usable(value: number | undefined, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * The editor for one edit session. `state` is the app's shared state (or
 * any object with the four members it reads — see MaskEditState); the
 * options inject the two effects, for tests.
 *
 * Creating the session switches the stage's feed to MJPEG and reads the
 * settings mirror once; it does not navigate, open anything, or save.
 */
export function createMaskEditSession(state: MaskEditState, options: MaskEditSessionOptions = {}): MaskEditSession {
  const commitZones = options.commit ?? ((zones: MaskingZone[]) => saveStreamingPatch({ maskingZones: zones }))
  const reportError = options.onError ?? (() => pushToast('danger', t('privacy.editor.saveFailed')))

  // ── The draft ──
  const seed = state.settings()?.streaming?.maskingZones ?? []
  const [zones, setZones] = createSignal<MaskingZone[]>(seed.map((zone) => ({ ...zone })))
  const [selectedId, setSelectedId] = createSignal<string | null>(null)
  const [drawing, setDrawing] = createSignal(seed.length === 0)
  const [gesture, setGesture] = createSignal<MaskEditGesture | null>(null)
  const [rubberBand, setRubberBand] = createSignal<ZoneRect | null>(null)

  // The zone the entry point named, else the first. An empty list has nothing to select — and arms drawing.
  const focus = 'focusZoneId' in options ? options.focusZoneId : maskEditFocusZoneId()
  setSelectedId(seed.find((zone) => zone.id === focus)?.id ?? seed[0]?.id ?? null)

  function findZone(zoneId: string | null | undefined): MaskingZone | undefined {
    if (zoneId === null || zoneId === undefined) return undefined
    return zones().find((zone) => zone.id === zoneId)
  }

  // Derived on read: a handful of zones, and it stays true through every
  // draft write without a second signal to keep in step.
  function selectedZone(): MaskingZone | null {
    return findZone(selectedId()) ?? null
  }

  /** Swaps one zone for a new object; the list is never mutated in place. */
  function replaceZone(zoneId: string, zone: MaskingZone): void {
    setZones((list) => {
      let changed = false
      const next = list.map((item) => {
        if (item.id !== zoneId || item === zone) return item
        changed = true
        return zone
      })
      return changed ? next : list
    })
  }

  // ── Saves ──
  const queue = createLatestWinsQueue<MaskingZone[]>(commitZones, reportError)

  /** Saves the draft as it is now. The queue sends one write at a time and keeps only the latest waiting one. */
  function commit(): void {
    queue.push(zones())
  }

  // ── The feed switch ──
  // Masks are painted on the MJPEG web stream only; the WHEP default is a
  // clean, centre-cropped, letterboxed picture that zones cannot be placed
  // on. The rung the user was on is remembered here and put back on exit.
  const previousMode = state.playerMode()
  state.setPlayerMode('mjpeg')
  let ended = false

  function release(): void {
    if (ended) return
    ended = true
    // MJPEG failing during the edit lets StreamPreview's ladder fall through
    // to HLS. A user who was already on MJPEG is left on whatever the ladder
    // chose: forcing MJPEG back would revive a rung that just gave up.
    if (previousMode !== 'mjpeg') state.setPlayerMode(previousMode)
  }

  // ── Gestures ──
  // Where the pointer went down, where it is now, and the zone as it was
  // when the gesture began. Plain variables: the UI reads the gesture's
  // effect in the draft and the rubber band, never this bookkeeping.
  let origin: ZonePoint = { x: 0, y: 0 }
  let pointer: ZonePoint = { x: 0, y: 0 }
  let startZone: MaskingZone | null = null

  /** Where a pointer-down lands a new gesture, or null when it cannot start one. */
  function gestureStart(point: ZonePoint): ZonePoint | null {
    if (ended || gesture() !== null) return null
    return clampPoint(point)
  }

  /** The rectangle a move or resize gesture has with its pointer at `at` — always from the gesture's start, never from the last frame. */
  function gestureRect(active: MaskEditGesture, start: MaskingZone, at: ZonePoint): ZoneRect {
    const dx = at.x - origin.x
    const dy = at.y - origin.y
    return active.kind === 'resize'
      ? resizeZone(start, active.handle, dx, dy)
      : moveZone(start, dx, dy)
  }

  function clearGesture(): void {
    batch(() => {
      setGesture(null)
      setRubberBand(null)
    })
  }

  function beginDraw(point: ZonePoint): void {
    // A drag on the picture draws only while drawing is armed.
    if (!drawing()) return
    const start = gestureStart(point)
    if (!start) return
    origin = start
    pointer = start
    startZone = null
    batch(() => {
      setGesture({ kind: 'draw' })
      setRubberBand(dragBounds(start, start))
    })
  }

  function beginMove(zoneId: string, point: ZonePoint): void {
    const zone = findZone(zoneId)
    if (!zone) return
    const start = gestureStart(point)
    if (!start) return
    origin = start
    pointer = start
    startZone = zone
    batch(() => {
      setSelectedId(zone.id)
      setGesture({ kind: 'move', zoneId: zone.id })
    })
  }

  function beginResize(zoneId: string, handle: ResizeHandle, point: ZonePoint): void {
    const zone = findZone(zoneId)
    if (!zone) return
    const start = gestureStart(point)
    if (!start) return
    origin = start
    pointer = start
    startZone = zone
    batch(() => {
      setSelectedId(zone.id)
      setGesture({ kind: 'resize', zoneId: zone.id, handle })
    })
  }

  function dragTo(point: ZonePoint): void {
    const active = gesture()
    if (ended || !active) return
    const at = clampPoint(point)
    // A pointer position that is not a number is not a position; the gesture waits for the next one.
    if (!at) return
    pointer = at
    if (active.kind === 'draw') {
      setRubberBand(dragBounds(origin, at))
      return
    }
    const start = startZone
    if (start) replaceZone(start.id, { ...start, ...gestureRect(active, start, at) })
  }

  function endGesture(): void {
    // No gesture in progress — or one that already ended: never a second save.
    const active = gesture()
    if (ended || !active) return
    clearGesture()
    if (active.kind === 'draw') {
      // One shot: a drag too small to be a zone (a tap, a slip of the
      // pointer) creates nothing and leaves drawing armed for the next try.
      const rect = zoneFromDrag(origin, pointer)
      if (!rect) return
      const zone = createZone(roundZone(rect))
      batch(() => {
        setZones((list) => [...list, zone])
        setSelectedId(zone.id)
        setDrawing(false)
      })
      commit()
      return
    }
    const start = startZone
    if (!start) return
    const rect = roundZone(gestureRect(active, start, pointer))
    // A drag that ended where it began changed nothing: the zone goes back
    // exactly as it was, and nothing is saved.
    if (sameRect(rect, start)) {
      replaceZone(start.id, start)
      return
    }
    replaceZone(start.id, { ...start, ...rect })
    commit()
  }

  function cancelGesture(): void {
    const active = gesture()
    if (ended || !active) return
    const start = startZone
    clearGesture()
    // A move or resize in flight had been writing the draft live; put the zone back.
    if (start) replaceZone(start.id, start)
  }

  // ── Commands ──
  // No command runs while a gesture is in flight: a drag owns the draft
  // until it ends or is cancelled, so a keystroke can never pull a zone out
  // from under the pointer.

  function select(zoneId: string | null): void {
    if (ended || gesture() !== null) return
    setSelectedId(findZone(zoneId)?.id ?? null)
  }

  function toggleDrawing(): void {
    if (ended || gesture() !== null) return
    setDrawing(!drawing())
  }

  function updateZone(patch: MaskEditZonePatch, zoneId: string | null | undefined = selectedId()): void {
    if (ended || gesture() !== null) return
    const zone = findZone(zoneId)
    if (!zone) return
    const next: MaskingZone = { ...zone }
    if (patch.label !== undefined) next.label = patch.label
    if (patch.enabled !== undefined) next.enabled = patch.enabled
    if (patch.type !== undefined) next.type = patch.type
    if (patch.pixelateSize !== undefined) next.pixelateSize = patch.pixelateSize
    if (patch.blurRadius !== undefined) next.blurRadius = patch.blurRadius
    const geometry = {
      x: usable(patch.x, zone.x),
      y: usable(patch.y, zone.y),
      width: usable(patch.width, zone.width),
      height: usable(patch.height, zone.height),
    }
    // Only a real change of geometry re-fits the rectangle, so a label edit
    // leaves a zone the editor has not touched exactly as the device has it.
    if (
      geometry.x !== zone.x ||
      geometry.y !== zone.y ||
      geometry.width !== zone.width ||
      geometry.height !== zone.height
    ) {
      Object.assign(next, roundZone(geometry))
    }
    // Nothing changed (the same value typed again, or a geometry that the frame rule turns back into itself): nothing to save.
    if (sameZone(next, zone)) return
    replaceZone(zone.id, next)
    commit()
  }

  function removeZone(zoneId: string): void {
    if (ended || gesture() !== null) return
    const list = zones()
    const index = list.findIndex((zone) => zone.id === zoneId)
    if (index === -1) return
    const rest = list.filter((zone) => zone.id !== zoneId)
    batch(() => {
      setZones(rest)
      // The selection moves to the neighbour that took the removed zone's place — the one before it at the end of the list.
      if (selectedId() === zoneId) setSelectedId(rest[index]?.id ?? rest[index - 1]?.id ?? null)
      // An empty list arms drawing, exactly as an empty list does when the editor opens.
      if (rest.length === 0) setDrawing(true)
    })
    commit()
  }

  function nudge(dx: number, dy: number): void {
    if (ended || gesture() !== null) return
    const zone = selectedZone()
    if (!zone) return
    const rect = roundZone(moveZone(zone, dx, dy))
    // Already against the frame's edge: nowhere to go, nothing to save.
    if (sameRect(rect, zone)) return
    replaceZone(zone.id, { ...zone, ...rect })
    commit()
  }

  // ── The masking master switch ──
  // Not draft state: the device paints a drawn zone only while the master
  // switch is on, so the editor's inspector must be able to read the switch
  // and flip it. It reads the settings mirror live — a poll that moves it is
  // news the operator should see — and writes it the way every streaming
  // control does. Neither touches the zone draft.

  function maskingEnabled(): boolean {
    return state.settings()?.streaming?.maskingEnabled ?? API_DEFAULTS.maskingEnabled
  }

  function setMaskingEnabled(on: boolean): void {
    state.updateStreamingDebounced({ maskingEnabled: on })
  }

  // ── Lifecycle ──

  function finish(): void {
    if (ended) return
    // A gesture still in flight is dropped, not saved.
    cancelGesture()
    // Every edit is already on the device (or queued to land there); what
    // "Done" adds is the mirror, which the targeted saves never touched —
    // so the rest of the UI shows the new zones now rather than at the next
    // poll. Skipped when the mirror already agrees, which includes the
    // float-widened read-back of a successful save.
    const final = zones()
    const mirror = state.settings()?.streaming?.maskingZones ?? []
    if (!zonesEqual(final, mirror)) state.updateStreamingDebounced({ maskingZones: final.slice() })
    release()
    endMaskEdit()
  }

  function dispose(): void {
    if (ended) return
    // The gesture in flight is dropped with the editor. Commits already
    // queued still land: the queue belongs to the edits, not to the editor.
    cancelGesture()
    release()
  }

  return {
    zones,
    selectedId,
    selectedZone,
    drawing,
    gesture,
    rubberBand,
    beginDraw,
    beginMove,
    beginResize,
    dragTo,
    endGesture,
    cancelGesture,
    select,
    toggleDrawing,
    updateZone,
    removeZone,
    nudge,
    maskingEnabled,
    setMaskingEnabled,
    finish,
    dispose,
  }
}
