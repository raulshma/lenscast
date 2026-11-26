import { For, Show, createEffect, createMemo, createSignal, onCleanup } from 'solid-js'
import type { NextState } from '../../../NextState'
import type { PreviewMedia } from '../../../../StreamPreview'
import { maskEditActive, maskEditSession } from '../../../../../lib/maskEditStore'
import {
  RESIZE_HANDLES,
  clampPoint,
  containRect,
  sameRect,
  type PixelRect,
  type PixelSize,
  type ResizeHandle,
  type ZonePoint,
  type ZoneRect,
} from '../../../../../lib/maskingZonePolicy'
import { t } from '../../../../../lib/i18n'
import type { MaskingZone } from '../../../../../types'
import type { MaskEditSession } from './maskEditSession'

/**
 * The masking-zone editor's picture layer: the surface the zones are drawn,
 * moved and resized on, over the Live stage's picture.
 *
 * It renders only while an edit is active and its session is published
 * (maskEditStore); the Live view creates the session and owns its lifetime.
 * The draft, the frame rule and the saves all live in the session — this
 * file is pixels: where the picture is on screen, where the pointer is on
 * the picture, and which of the session's gestures that means.
 *
 * The root fills the preview container and ignores the pointer. The one
 * interactive part is `.nx-mask-picture`, sized to the picture itself — the
 * contain-fit rectangle of the frame inside the media element's box — so a
 * zone at 50% of the picture is at 50% of what the device masks,
 * letterboxing or not. Zones are positioned in percentages of that
 * rectangle, so a drag never needs a re-measure: one fresh measurement is
 * taken when a gesture starts and kept until it ends.
 */

export interface MaskEditLayerProps {
  /** The app's shared state — what the editor's session was created from. */
  state: NextState
  /** The mounted media element of the rung on stage; undefined while none is. */
  media: () => PreviewMedia | undefined
}

export default function MaskEditLayer(props: MaskEditLayerProps) {
  // Armed but not yet answered (the Live view has not published its
  // session), or finished: there is nothing to draw.
  const session = createMemo(() => (maskEditActive() ? maskEditSession() : null))
  // A modal over the stage — the shortcuts help — covers the picture and
  // leaves the focus exactly where it was: on this picture, if the operator
  // was mid-edit. While it is up its keys are its own: the Escape that
  // closes it must not also step the editor back, and nothing may be
  // nudged or removed behind it.
  const covered = () => props.state.showShortcuts()
  // Keyed: a new session is a new editor, from a clean slate.
  return (
    <Show when={session()} keyed>
      {(editor) => <MaskEditPicture editor={editor} media={() => props.media()} covered={covered} />}
    </Show>
  )
}

// ── The picture, on screen ───────────────────────────────────────────

/** A box in viewport pixels — the coordinates pointer positions come in. */
type ClientBox = Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>

/**
 * One measurement of the picture — the contain-fit rectangle of the frame
 * inside the media element's box — in the two coordinate spaces the layer
 * works in: `rect` is where the picture is drawn (offset from the layer's
 * top-left, what zones are positioned against) and `client` is where the
 * pointer meets it (viewport pixels, what pointer positions map through).
 */
interface Measurement {
  rect: PixelRect
  client: ClientBox
}

/** The frame's own size as the element reports it: an image's natural size, a video's decoded size, a canvas's pixel size. */
function intrinsicSize(el: PreviewMedia): PixelSize {
  if ('naturalWidth' in el) return { width: el.naturalWidth, height: el.naturalHeight }
  if ('videoWidth' in el) return { width: el.videoWidth, height: el.videoHeight }
  return { width: el.width, height: el.height }
}

/**
 * The one measuring surface: the MJPEG rung's `<img>`. The session switches
 * the stage's feed to MJPEG for the edit; if that rung exhausts its retries
 * mid-edit, StreamPreview's ladder falls to HLS — a centre-cropped `<video>`
 * whose frame the device does not mask, so zones measured against it would
 * sit wrong on the stream. Every other rung is a video (whep, hls) or a
 * canvas (h264): none of them is the picture the device masks, so none of
 * them measures — they are the no-media case, waiting chip and all.
 */
function isMjpegImage(el: PreviewMedia): el is HTMLImageElement {
  return el instanceof HTMLImageElement
}

const isHandle = (value: string | undefined): value is ResizeHandle =>
  value !== undefined && (RESIZE_HANDLES as readonly string[]).includes(value)

/** A fraction of the picture, as a CSS length. */
const pct = (fraction: number): string => `${fraction * 100}%`

/** A fraction of the picture, as a whole percent — the readout's unit. */
const wholePct = (fraction: number): number => Math.round(fraction * 100)

/** The readout chip (24px), its gap from the rectangle (8px) and a margin: less room than this below flips it above. */
const READOUT_ROOM = 40

function rectStyle(rect: ZoneRect): Record<string, string> {
  return { left: pct(rect.x), top: pct(rect.y), width: pct(rect.width), height: pct(rect.height) }
}

/** The id of the zone an element belongs to — its box, or its label chip. */
const zoneIdOf = (el: Element | null): string | undefined =>
  el?.closest<HTMLElement>('[data-zone-id]')?.dataset.zoneId

/** True for an element inside the selected zone's label chip, which sits outside the zone's own box. */
const onLabel = (el: Element | null): boolean => el !== null && el.closest('.nx-mask-zone-label') !== null

/** The keys the picture answers for the selected zone: a focused zone takes them itself. */
const ZONE_KEYS: ReadonlySet<string> = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Delete', 'Backspace'])

function MaskEditPicture(props: {
  editor: MaskEditSession
  media: () => PreviewMedia | undefined
  /** True while a modal is over the picture: the keys are the modal's until it goes. */
  covered: () => boolean
}) {
  const editor = props.editor
  // The picture's last measurement; null until there is a picture.
  const [picture, setPicture] = createSignal<Measurement | null>(null)
  const [layerEl, setLayerEl] = createSignal<HTMLDivElement | null>(null)
  // Rows are keyed by zone id, so a zone keeps its element (and the focus,
  // when Tab reached it) through every edit of its rectangle.
  const zoneIds = createMemo(() => editor.zones().map((zone) => zone.id))

  /**
   * Where the picture is: the contain-fit rectangle of the media element's
   * frame size inside the element's own box — the stage letterboxes the
   * frame in it — taken twice, once in each of the two coordinate spaces
   * the layer works in (see Measurement).
   */
  function measure(): Measurement | null {
    const el = props.media()
    const host = layerEl()
    if (!el || !host || !isMjpegImage(el)) return null
    const box = el.getBoundingClientRect()
    // Not laid out (hidden, or jsdom).
    if (box.width === 0 || box.height === 0) return null
    // No frame yet: an <img> has no natural size before its first load.
    const size = intrinsicSize(el)
    if (size.width === 0 || size.height === 0) return null
    const fit = containRect({ width: box.width, height: box.height }, size)
    const left = box.left + fit.x
    const top = box.top + fit.y
    const origin = host.getBoundingClientRect()
    return {
      client: { left, top, width: fit.width, height: fit.height },
      rect: { x: left - origin.left, y: top - origin.top, width: fit.width, height: fit.height },
    }
  }

  /** Publishes a measurement, keeping the previous one when the picture has not moved. */
  function publish(next: Measurement | null): void {
    // A picture going away under the focus (a rung fell through, the element
    // lost its box) hands the focus on before it goes.
    if (next === null) handOffFocus()
    setPicture((prev) => (prev && next && sameRect(prev.rect, next.rect) ? prev : next))
  }

  // ── The focus, when the picture goes ─────────────────────────────────
  // The surface is the editor's keys — and it can unmount under the focus:
  // the edit finishing, or the measurement lost. Left alone, the focus drops
  // to <body> and the keyboard flow dies. Where it lands instead:
  let surfaceEl: HTMLDivElement | null = null

  /**
   * The edit bar's Done button while the edit is still open (the measurement
   * was lost; the bar stays), else the Live view's root — which outlives the
   * edit, so a finish keeps the focus. Both are chrome classes the Live
   * view's own tests pin, not styling details. `tabindex="-1"` on the root
   * makes it focusable without joining the tab order.
   */
  function focusAfterPicture(): void {
    const done = maskEditActive() ? document.querySelector<HTMLElement>('.nx-mask-bar .nx-btn-primary') : null
    ;(done ?? document.querySelector<HTMLElement>('.nx-view-flush'))?.focus()
  }

  /** Moves the focus out of the picture — only when the picture held it. */
  function handOffFocus(): void {
    const el = surfaceEl
    const active = document.activeElement
    if (!el || !active || active === document.body) return
    if (active !== el && !el.contains(active)) return
    focusAfterPicture()
  }

  // The edit finishing unmounts the picture with the whole editor.
  onCleanup(handOffFocus)

  // ── Gestures ──
  // The press that drives the gesture in flight: its pointer, and the box it
  // maps against. Non-null exactly while this layer owns a drag — the same
  // window-listener pattern as the mini-player's (StreamPipHost): the drag
  // survives the pointer leaving the picture, with pointer capture as the
  // extra keep-alive.
  let drag: { pointerId: number; box: ClientBox } | null = null

  /** A gesture keeps the box it started with; every other trigger waits for it to end. */
  function remeasure(): void {
    if (drag) return
    publish(measure())
  }

  // An MJPEG <img> fires `load` on every frame. Only a frame of a new size
  // can move the picture's box, so the rest are turned away without a measurement.
  let loadedSize: PixelSize | null = null
  function onLoad(): void {
    const el = props.media()
    if (!el) return
    const size = intrinsicSize(el)
    if (loadedSize && loadedSize.width === size.width && loadedSize.height === size.height) return
    loadedSize = size
    remeasure()
  }

  // The picture's box follows the media element: measured when the element
  // changes, and re-measured by everything that can move it — the element's
  // own size (ResizeObserver, the frame arriving), the window, and the
  // element's transform settling (the preview's zoom reset animates it).
  createEffect(() => {
    const el = props.media()
    // Only the MJPEG <img> is a surface (isMjpegImage): the ladder falling
    // to another rung mid-edit is the no-media case — the waiting chip,
    // every gesture locked — never a re-measure onto a frame the device
    // does not mask.
    if (!el || !isMjpegImage(el)) {
      loadedSize = null
      publish(null)
      return
    }
    // The size the element already has counts as loaded: a stream that was
    // playing before the edit opened keeps firing `load` for frames of it.
    loadedSize = intrinsicSize(el)
    el.addEventListener('load', onLoad)
    el.addEventListener('loadedmetadata', remeasure)
    el.addEventListener('resize', remeasure)
    el.addEventListener('transitionend', remeasure)
    remeasure()
    let observer: ResizeObserver | undefined
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(remeasure)
      observer.observe(el)
      const host = layerEl()
      if (host) observer.observe(host)
    }
    onCleanup(() => {
      el.removeEventListener('load', onLoad)
      el.removeEventListener('loadedmetadata', remeasure)
      el.removeEventListener('resize', remeasure)
      el.removeEventListener('transitionend', remeasure)
      observer?.disconnect()
    })
  })

  window.addEventListener('resize', remeasure)
  onCleanup(() => window.removeEventListener('resize', remeasure))

  /** A pointer position on the picture, normalized and pinned inside it. */
  function pointAt(e: { clientX: number; clientY: number }, box: ClientBox): ZonePoint {
    return clampPoint({ x: (e.clientX - box.left) / box.width, y: (e.clientY - box.top) / box.height }) ?? { x: 0, y: 0 }
  }

  /** The topmost zone under a point of the picture: the last of the list wins. */
  function zoneAt(point: ZonePoint): MaskingZone | null {
    const zones = editor.zones()
    for (let i = zones.length - 1; i >= 0; i--) {
      const zone = zones[i]
      if (point.x >= zone.x && point.x <= zone.x + zone.width && point.y >= zone.y && point.y <= zone.y + zone.height) {
        return zone
      }
    }
    return null
  }

  function onDragMove(e: PointerEvent): void {
    if (!drag || e.pointerId !== drag.pointerId) return
    // Ended some other way (Escape, Done): the listeners go with it.
    if (!editor.gesture()) {
      stopGesture()
      return
    }
    editor.dragTo(pointAt(e, drag.box))
  }

  function onDragEnd(e: PointerEvent): void {
    if (!drag || e.pointerId !== drag.pointerId) return
    editor.endGesture()
    stopGesture()
  }

  function onDragCancel(e: PointerEvent): void {
    if (!drag || e.pointerId !== drag.pointerId) return
    editor.cancelGesture()
    stopGesture()
  }

  /** Lets go of the drag: the window listeners go, and the box — held still through the gesture — catches up. */
  function stopGesture(): void {
    window.removeEventListener('pointermove', onDragMove)
    window.removeEventListener('pointerup', onDragEnd)
    window.removeEventListener('pointercancel', onDragCancel)
    drag = null
    publish(measure())
  }
  onCleanup(() => {
    window.removeEventListener('pointermove', onDragMove)
    window.removeEventListener('pointerup', onDragEnd)
    window.removeEventListener('pointercancel', onDragCancel)
  })

  function startGesture(e: PointerEvent, surface: HTMLDivElement, box: ClientBox, begin: () => void): void {
    begin()
    if (!editor.gesture()) return
    drag = { pointerId: e.pointerId, box }
    window.addEventListener('pointermove', onDragMove)
    window.addEventListener('pointerup', onDragEnd)
    window.addEventListener('pointercancel', onDragCancel)
    try {
      surface.setPointerCapture(e.pointerId)
    } catch {
      /* pointer capture unavailable */
    }
  }

  function onPointerDown(e: PointerEvent): void {
    // Secondary buttons are not the editor's.
    if (e.button !== 0) return
    if (drag) {
      // A second pointer while a drag runs is ignored. The dragging pointer
      // itself pressing again means its release never arrived: that gesture
      // is dropped, and this press is a fresh one.
      if (e.pointerId !== drag.pointerId) return
      editor.cancelGesture()
      stopGesture()
    }
    const surface = e.currentTarget as HTMLDivElement
    const found = measure()
    if (!found) {
      publish(null)
      return
    }
    // One fresh measurement for the whole gesture.
    publish(found)
    const box = found.client
    surface.focus({ preventScroll: true })
    e.preventDefault()
    const point = pointAt(e, box)
    const target = e.target as Element | null
    const handle = target?.closest<HTMLElement>('[data-handle]')?.dataset.handle
    const selectedId = editor.selectedId()
    if (isHandle(handle) && selectedId !== null) {
      startGesture(e, surface, box, () => editor.beginResize(selectedId, handle, point))
      return
    }
    // The selected zone's label sits outside its own box: a press on it is a press on its zone.
    const labelZone = onLabel(target) ? editor.zones().find((zone) => zone.id === zoneIdOf(target)) : undefined
    const zone = labelZone ?? zoneAt(point)
    if (zone) {
      // Select first: a command is ignored once a gesture is in flight.
      editor.select(zone.id)
      startGesture(e, surface, box, () => editor.beginMove(zone.id, point))
      return
    }
    if (editor.drawing()) {
      startGesture(e, surface, box, () => editor.beginDraw(point))
      return
    }
    editor.select(null)
  }

  // ── Keyboard ──
  const nudgeStep = (e: KeyboardEvent): number => (e.shiftKey ? 0.1 : 0.01)

  /** Escape steps back one thing at a time; with nothing left to step back from, it is Done. */
  function stepBack(): void {
    if (editor.gesture()) {
      editor.cancelGesture()
      stopGesture()
      return
    }
    if (editor.drawing()) {
      editor.toggleDrawing()
      return
    }
    if (editor.selectedId() !== null) {
      editor.select(null)
      return
    }
    editor.finish()
  }

  function onKeyDown(e: KeyboardEvent): void {
    if (props.covered()) return
    // Ctrl / Alt / Meta belong to the browser and the OS; Shift is the wide step.
    if (e.ctrlKey || e.altKey || e.metaKey) return
    switch (e.key) {
      case 'ArrowLeft':
        e.preventDefault()
        editor.nudge(-nudgeStep(e), 0)
        return
      case 'ArrowRight':
        e.preventDefault()
        editor.nudge(nudgeStep(e), 0)
        return
      case 'ArrowUp':
        e.preventDefault()
        editor.nudge(0, -nudgeStep(e))
        return
      case 'ArrowDown':
        e.preventDefault()
        editor.nudge(0, nudgeStep(e))
        return
      case 'Delete':
      case 'Backspace': {
        e.preventDefault()
        // A held key must not walk down the list removing zone after zone.
        if (e.repeat) return
        const id = editor.selectedId()
        if (id === null) return
        editor.removeZone(id)
        // The removed zone may have held the focus (Tab reaches the zones):
        // back to the picture, so the keys keep landing on the editor.
        const surface = e.currentTarget as HTMLDivElement
        surface.focus({ preventScroll: true })
        return
      }
      case 'Escape':
        e.preventDefault()
        stepBack()
        return
      default:
        return
    }
  }

  // ── Readouts ──
  /** The rectangle the gesture in flight is changing: the rubber band, or the zone being moved or resized. */
  const changing = createMemo<ZoneRect | null>(() => {
    const gesture = editor.gesture()
    if (!gesture) return null
    if (gesture.kind === 'draw') return editor.rubberBand()
    return editor.zones().find((zone) => zone.id === gesture.zoneId) ?? null
  })
  const rubberBand = createMemo(() => (editor.gesture()?.kind === 'draw' ? editor.rubberBand() : null))

  return (
    <div class="nx-mask-layer" ref={setLayerEl}>
      <Show
        when={picture()}
        fallback={
          <div class="nx-mask-waiting" role="status">
            {t('privacy.editor.waiting')}
          </div>
        }
      >
        {(measured) => (
          <div
            class="nx-mask-picture"
            ref={(el) => (surfaceEl = el)}
            classList={{ 'nx-mask-drawing': editor.drawing() }}
            role="application"
            aria-label={t('privacy.editor.title')}
            tabindex={0}
            style={{
              left: `${measured().rect.x}px`,
              top: `${measured().rect.y}px`,
              width: `${measured().rect.width}px`,
              height: `${measured().rect.height}px`,
            }}
            onPointerDown={onPointerDown}
            onKeyDown={onKeyDown}
          >
            <For each={zoneIds()}>
              {(id, index) => (
                <MaskZone editor={editor} zone={() => editor.zones().find((zone) => zone.id === id)} index={index} />
              )}
            </For>
            <Show when={rubberBand()}>
              {(band) => <div class="nx-mask-rubber" aria-hidden="true" style={rectStyle(band())} />}
            </Show>
            <Show when={changing()}>
              {(changed) => <MaskReadout rect={changed} pictureHeight={() => measured().rect.height} />}
            </Show>
          </div>
        )}
      </Show>
    </div>
  )
}

// ── The live readout ─────────────────────────────────────────────────

/**
 * `W% × H%` of the rectangle the gesture in flight is changing. Below the
 * rectangle while there is room; at the picture's bottom edge it flips
 * above — to the corner opposite the selected zone's label, which sits at
 * the other one, so the two chips never meet.
 */
function MaskReadout(props: { rect: () => ZoneRect; pictureHeight: () => number }) {
  const above = createMemo(() => {
    const rect = props.rect()
    return props.pictureHeight() * (1 - rect.y - rect.height) < READOUT_ROOM
  })
  const left = createMemo(() => above() && props.rect().x > 0.5)
  return (
    <div
      class="nx-mask-dims nx-mono"
      classList={{ 'nx-mask-dims-above': above(), 'nx-mask-dims-left': left() }}
      aria-hidden="true"
      style={{
        left: pct(left() ? props.rect().x : props.rect().x + props.rect().width),
        top: pct(above() ? props.rect().y : props.rect().y + props.rect().height),
      }}
    >
      {t('privacy.editor.dims', { w: wholePct(props.rect().width), h: wholePct(props.rect().height) })}
    </div>
  )
}

// ── One zone ─────────────────────────────────────────────────────────

function MaskZone(props: { editor: MaskEditSession; zone: () => MaskingZone | undefined; index: () => number }) {
  const selected = () => props.editor.selectedId() === props.zone()?.id
  const name = () => {
    const zone = props.zone()
    return zone ? zone.label || t('privacy.zone', { n: props.index() + 1 }) : ''
  }
  return (
    <Show when={props.zone()}>
      {(zone) => (
        <div
          class="nx-mask-zone"
          classList={{
            'nx-mask-zone-selected': selected(),
            'nx-mask-zone-disabled': !zone().enabled,
          }}
          role="button"
          tabindex={0}
          aria-pressed={selected()}
          aria-label={name()}
          data-zone-id={zone().id}
          style={rectStyle(zone())}
          onKeyDown={(e) => {
            // A keyboard press on the focused zone selects it; the pointer
            // path selects from the picture's own handler.
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              e.stopPropagation()
              props.editor.select(zone().id)
              return
            }
            // The keys the picture acts on with the selected zone act on the
            // zone that has the focus: select it, and let the key bubble.
            if (ZONE_KEYS.has(e.key)) props.editor.select(zone().id)
          }}
        >
          <Show when={selected()}>
            <span
              class="nx-mask-zone-label"
              classList={{
                'nx-mask-zone-label-inside': zone().y < 0.1,
                'nx-mask-zone-label-right': zone().x > 0.5,
              }}
            >
              {name()}
            </span>
            <For each={RESIZE_HANDLES}>
              {(handle) => (
                <span class={`nx-mask-handle nx-mask-handle-${handle}`} data-handle={handle} aria-hidden="true" />
              )}
            </For>
          </Show>
        </div>
      )}
    </Show>
  )
}
