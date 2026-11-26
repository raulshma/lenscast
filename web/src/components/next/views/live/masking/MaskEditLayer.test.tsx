// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import type { AllSettings, MaskingZone } from '../../../../../types'
import type { PreviewMedia } from '../../../../StreamPreview'
import type { PlayerMode } from '../../../../../video/playerLadder'
import {
  maskEditActive,
  maskEditSession,
  resetMaskEditStoreForTests,
  setMaskEditSession,
  startMaskEdit,
} from '../../../../../lib/maskEditStore'
import type { PixelSize, ZoneRect } from '../../../../../lib/maskingZonePolicy'
import { en } from '../../../../../i18n/en'
import type { NextState } from '../../../NextState'
import { createMaskEditSession, type MaskEditSession, type MaskEditState } from './maskEditSession'
import MaskEditLayer from './MaskEditLayer'

// The masking-zone editor's picture layer, in jsdom: a real session, driven
// through real pointer and key events on the rendered layer. The session's
// two effects are injected (commit, onError); the API client is the only
// module mocked, so the real saveStreamingPatch never sees a network.
//
// jsdom lays nothing out: every getBoundingClientRect() is 0 × 0 and there
// is no ResizeObserver or pointer capture. So the boxes are scripted per
// element (the media <img> gets one; everything else reports the container's
// — the layer root included, which is what the picture's offset is taken
// from), the observer is a stand-in a test fires by hand, and pointer
// capture is a spy.

vi.mock('../../../../../api/client', () => ({ saveStreamingPatch: vi.fn() }))

type Box = { left: number; top: number; width: number; height: number }

/** The preview container's box on the page — what every element without a box of its own reports. */
const CONTAINER: Box = { left: 100, top: 100, width: 400, height: 300 }
/** The MJPEG <img>'s box: the container with 50px letterbox bars above and below. */
const MEDIA: Box = { left: 100, top: 150, width: 400, height: 200 }
/** A 2:1 frame fills the media box exactly, so the picture is the whole box (clean numbers). */
const FRAME: PixelSize = { width: 800, height: 400 }

/** A pointer position given as fractions of the picture, in viewport pixels. */
const pointer = (fx: number, fy: number): PointerEventInit => ({
  clientX: MEDIA.left + fx * MEDIA.width,
  clientY: MEDIA.top + fy * MEDIA.height,
  pointerId: 7,
  pointerType: 'mouse',
  button: 0,
  bubbles: true,
  cancelable: true,
})

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

function rectOf(item: MaskingZone): ZoneRect {
  return { x: item.x, y: item.y, width: item.width, height: item.height }
}

/** Four fields compared at 12 digits — float noise in the last ulps is not a finding. */
function expectRect(rect: ZoneRect, expected: ZoneRect): void {
  for (const field of ['x', 'y', 'width', 'height'] as const) {
    expect(rect[field], field).toBeCloseTo(expected[field], 12)
  }
}

/** A hand-built state with the four members the session reads, plus the one the layer reads (whether a modal is over the stage). */
function fakeState(zones: MaskingZone[], help: () => boolean = () => false) {
  const doc = (list: MaskingZone[]): AllSettings =>
    ({ ...settingsFixture, streaming: { ...settingsFixture.streaming, maskingZones: list } }) as unknown as AllSettings
  const [settings, setSettings] = createSignal<AllSettings | null>(doc(zones))
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>('whep')
  const updateStreamingDebounced = vi.fn((_patch: Partial<AllSettings['streaming']>) => {})
  const state: MaskEditState & { showShortcuts: () => boolean } = { settings, playerMode, setPlayerMode, updateStreamingDebounced, showShortcuts: help }
  return { state, updateStreamingDebounced, setSettings }
}

function saver() {
  const calls: MaskingZone[][] = []
  const onError = vi.fn()
  const commit = vi.fn((list: MaskingZone[]): Promise<void> => {
    calls.push(list)
    return Promise.resolve()
  })
  return { calls, commit, onError }
}

// ── The DOM jsdom does not provide ──

let boxes: WeakMap<Element, Box>
let observers: FakeResizeObserver[] = []
let captures: number[] = []

class FakeResizeObserver {
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
  constructor(readonly callback: ResizeObserverCallback) {
    observers.push(this)
  }
}

/** What the browser does after a layout change: every observer reports its entries. */
function layoutChange(): void {
  for (const observer of observers) observer.callback([], observer as unknown as ResizeObserver)
}

/** The media <img> reporting a new box (a resize, a rotation) — without notifying anyone. */
function rebox(el: Element, box: Box): void {
  boxes.set(el, box)
}

// ── Queries ──

const layer = () => document.querySelector<HTMLElement>('.nx-mask-layer')
const surface = () => document.querySelector<HTMLElement>('.nx-mask-picture')
const zoneElements = () => [...document.querySelectorAll<HTMLElement>('.nx-mask-zone')]
const zoneElement = (id: string) => document.querySelector<HTMLElement>(`[data-zone-id="${id}"]`)
const handleElement = (name: string) => document.querySelector<HTMLElement>(`[data-handle="${name}"]`)
const labelElement = () => document.querySelector<HTMLElement>('.nx-mask-zone-label')
const readout = () => document.querySelector<HTMLElement>('.nx-mask-dims')?.textContent ?? null
const rubber = () => document.querySelector<HTMLElement>('.nx-mask-rubber')

const pct = (value: string): number => Number.parseFloat(value) / 100
/** A zone element's rectangle, read back from the percentages it is positioned with. */
function drawnRect(el: HTMLElement): ZoneRect {
  return { x: pct(el.style.left), y: pct(el.style.top), width: pct(el.style.width), height: pct(el.style.height) }
}
/** The picture element's box in the layer's own coordinates, in px. */
function surfaceBox(): Box {
  const el = surface()!
  const px = (value: string) => Number.parseFloat(value)
  return { left: px(el.style.left), top: px(el.style.top), width: px(el.style.width), height: px(el.style.height) }
}
/** A box compared at 3 decimals: the CSSOM keeps 4 digits of an inline length. */
function expectBox(box: Box, expected: Box): void {
  for (const field of ['left', 'top', 'width', 'height'] as const) {
    expect(box[field], field).toBeCloseTo(expected[field], 3)
  }
}

const dims = (w: number, h: number) => en['privacy.editor.dims'].replace('{w}', String(w)).replace('{h}', String(h))

// ── Mounting ──

function mountEditor(zones: MaskingZone[] = [], opts: { frame?: PixelSize; media?: Box; help?: () => boolean } = {}) {
  const img = document.createElement('img')
  rebox(img, opts.media ?? MEDIA)
  const frame = opts.frame ?? FRAME
  Object.defineProperty(img, 'naturalWidth', { value: frame.width, configurable: true })
  Object.defineProperty(img, 'naturalHeight', { value: frame.height, configurable: true })
  const state = fakeState(zones, opts.help)
  const save = saver()
  const session = createMaskEditSession(state.state, { focusZoneId: null, commit: save.commit, onError: save.onError })
  startMaskEdit()
  setMaskEditSession(session)
  render(() => <MaskEditLayer state={state.state as unknown as NextState} media={() => img} />)
  return { session, save, state, img }
}

/** Press on `target` (the picture by default), move to `to`, release or cancel — a real drag's event order. */
function drag(
  from: [number, number],
  to: [number, number],
  opts: { target?: Element; cancel?: boolean; pointerId?: number; through?: Array<[number, number]> } = {},
): void {
  const init = (at: [number, number]): PointerEventInit => ({ ...pointer(at[0], at[1]), pointerId: opts.pointerId ?? 7 })
  fireEvent.pointerDown(opts.target ?? surface()!, init(from))
  for (const step of opts.through ?? []) fireEvent.pointerMove(window, init(step))
  fireEvent.pointerMove(window, init(to))
  if (opts.cancel) fireEvent.pointerCancel(window, init(to))
  else fireEvent.pointerUp(window, init(to))
}

/** Lets every microtask (and the commit queue's trailing commit) run. */
const settle = () => new Promise<void>((done) => setTimeout(done, 0))

beforeEach(() => {
  resetMaskEditStoreForTests()
  boxes = new WeakMap()
  observers = []
  captures = []
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    const { left, top, width, height } = boxes.get(this) ?? CONTAINER
    return { left, top, width, height, x: left, y: top, right: left + width, bottom: top + height, toJSON: () => ({}) } as DOMRect
  })
  Object.defineProperty(Element.prototype, 'setPointerCapture', {
    configurable: true,
    value: (id: number) => {
      captures.push(id)
    },
  })
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
})

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  delete (Element.prototype as { setPointerCapture?: unknown }).setPointerCapture
})

describe('rendering', () => {
  it('renders nothing while no edit is running', () => {
    const img = document.createElement('img')
    const state = fakeState([])
    render(() => <MaskEditLayer state={state.state as unknown as NextState} media={() => img} />)
    expect(layer()).toBeNull()
    // Armed, but the Live view has not answered with a session yet: still nothing.
    startMaskEdit()
    expect(layer()).toBeNull()
  })

  it('renders nothing once the edit has finished', () => {
    const { session } = mountEditor()
    expect(layer()).not.toBeNull()
    session.finish()
    expect(layer()).toBeNull()
  })

  it('shows only the waiting chip while there is no picture, and the picture once there is one', () => {
    const img = document.createElement('img')
    const state = fakeState([])
    const [media, setMedia] = createSignal<HTMLImageElement | undefined>(undefined)
    const session = createMaskEditSession(state.state, { focusZoneId: null, commit: async () => {} })
    startMaskEdit()
    setMaskEditSession(session)
    render(() => <MaskEditLayer state={state.state as unknown as NextState} media={media} />)

    const chip = document.querySelector<HTMLElement>('.nx-mask-waiting')
    expect(chip?.textContent).toBe(en['privacy.editor.waiting'])
    expect(chip?.getAttribute('role')).toBe('status')
    expect(surface()).toBeNull()
    expect(zoneElements()).toHaveLength(0)

    // A media element that has no frame yet (natural size 0) is still no picture.
    setMedia(img)
    expect(surface()).toBeNull()

    // The frame arrives: the picture is drawn.
    Object.defineProperty(img, 'naturalWidth', { value: 800, configurable: true })
    Object.defineProperty(img, 'naturalHeight', { value: 400, configurable: true })
    fireEvent.load(img)
    expect(surface()).not.toBeNull()
    expect(document.querySelector('.nx-mask-waiting')).toBeNull()
  })

  it('sizes the picture to the contain-fit frame inside the media box, offset into the layer', () => {
    // A 4:3 frame in the 400 × 200 media box: 266.67 × 200, pillarboxed 66.67px
    // from each side; the media box sits 50px below the layer's top edge.
    mountEditor([zone('a')], { frame: { width: 800, height: 600 } })
    expectBox(surfaceBox(), { left: 200 / 3, top: 50, width: 800 / 3, height: 200 })
    expect(surface()!.getAttribute('tabindex')).toBe('0')
  })

  it('positions zones in percentages of the picture, so they need no re-measure', () => {
    mountEditor([zone('a', { x: 0.1, y: 0.2, width: 0.3, height: 0.4 })])
    expectRect(drawnRect(zoneElement('a')!), { x: 0.1, y: 0.2, width: 0.3, height: 0.4 })
  })

  it('a selected zone wears the accent, its label and eight handles; the others are quiet', () => {
    mountEditor([zone('a', { label: 'Front door' }), zone('b', { enabled: false }), zone('c', { x: 0.6, y: 0.6, width: 0.3, height: 0.3 })])
    const a = zoneElement('a')!
    expect(a.classList.contains('nx-mask-zone-selected')).toBe(true)
    expect(a.getAttribute('aria-pressed')).toBe('true')
    expect(a.getAttribute('role')).toBe('button')
    expect(a.getAttribute('tabindex')).toBe('0')
    expect(a.getAttribute('aria-label')).toBe('Front door')
    expect(labelElement()?.textContent).toBe('Front door')
    expect(a.querySelectorAll('.nx-mask-handle')).toHaveLength(8)
    for (const name of ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw']) {
      expect(handleElement(name)?.getAttribute('aria-hidden')).toBe('true')
    }

    const b = zoneElement('b')!
    expect(b.classList.contains('nx-mask-zone-selected')).toBe(false)
    expect(b.classList.contains('nx-mask-zone-disabled')).toBe(true)
    expect(b.getAttribute('aria-pressed')).toBe('false')
    expect(b.querySelector('.nx-mask-zone-label')).toBeNull()
    expect(b.querySelectorAll('.nx-mask-handle')).toHaveLength(0)
    // An unlabelled zone is numbered, as the panel numbers it.
    expect(b.getAttribute('aria-label')).toBe(en['privacy.zone'].replace('{n}', '2'))
    expect(zoneElement('c')!.getAttribute('aria-label')).toBe(en['privacy.zone'].replace('{n}', '3'))
  })

  it('the picture takes the focus on a press, so the keys land on it', () => {
    mountEditor([zone('a')])
    fireEvent.pointerDown(surface()!, pointer(0.9, 0.1))
    fireEvent.pointerUp(window, pointer(0.9, 0.1))
    expect(document.activeElement).toBe(surface())
  })
})

describe('drawing', () => {
  it('an empty list draws: a drag commits one zone with the normalized rectangle', () => {
    const { session, save } = mountEditor([])
    expect(session.drawing()).toBe(true)
    fireEvent.pointerDown(surface()!, pointer(0.2, 0.2))
    fireEvent.pointerMove(window, pointer(0.5, 0.6))
    // The rubber band and the readout follow the drag live; nothing is saved yet.
    expectRect(drawnRect(rubber()!), { x: 0.2, y: 0.2, width: 0.3, height: 0.4 })
    expect(readout()).toBe(dims(30, 40))
    expect(save.calls).toHaveLength(0)

    fireEvent.pointerUp(window, pointer(0.5, 0.6))
    expect(save.calls).toHaveLength(1)
    const drawn = session.zones()
    expect(drawn).toHaveLength(1)
    expectRect(rectOf(drawn[0]), { x: 0.2, y: 0.2, width: 0.3, height: 0.4 })
    expect(save.calls[0]).toEqual(drawn)
    // The new zone is selected, drawing is disarmed, the rubber band is gone.
    expect(session.selectedId()).toBe(drawn[0].id)
    expect(session.drawing()).toBe(false)
    expect(rubber()).toBeNull()
    expect(readout()).toBeNull()
    expectRect(drawnRect(zoneElement(drawn[0].id)!), { x: 0.2, y: 0.2, width: 0.3, height: 0.4 })
    // Pointer capture was asked for the dragging pointer.
    expect(captures).toEqual([7])
  })

  it('a drag under 2% is a tap: nothing is drawn, nothing is saved, drawing stays armed', () => {
    const { session, save } = mountEditor([])
    drag([0.3, 0.3], [0.31, 0.31])
    expect(session.zones()).toHaveLength(0)
    expect(save.calls).toHaveLength(0)
    expect(session.drawing()).toBe(true)
    expect(session.gesture()).toBeNull()
  })

  it('right-to-left and bottom-up drags give the same rectangle as their mirrored twins', () => {
    const { session } = mountEditor([zone('a')], {})
    // One zone exists: drawing is not armed. Arm it the way the chrome does.
    session.toggleDrawing()
    drag([0.8, 0.7], [0.3, 0.2])
    const list = session.zones()
    expect(list).toHaveLength(2)
    expectRect(rectOf(list[1]), { x: 0.3, y: 0.2, width: 0.5, height: 0.5 })
  })

  it('a drag past the edge is clipped to the picture', () => {
    const { session } = mountEditor([])
    // 1.2 and 1.5 of the picture: off its right and bottom edges.
    drag([0.6, 0.5], [1.2, 1.5])
    expectRect(rectOf(session.zones()[0]), { x: 0.6, y: 0.5, width: 0.4, height: 0.5 })
  })

  it('the readout sits below the rectangle, and flips above it at the picture\'s bottom edge, away from the label', () => {
    const { session } = mountEditor([zone('a')])
    session.toggleDrawing()
    const readoutClasses = () => new Set(document.querySelector('.nx-mask-dims')?.classList ?? [])
    /** Where the readout is anchored, in percentages of the picture (the stylesheet offsets it from there). */
    const readoutAnchor = () => {
      const el = document.querySelector<HTMLElement>('.nx-mask-dims')
      return el ? { left: el.style.left, top: el.style.top } : null
    }
    // 40% of the 200px picture (80px) below the band: room for the chip.
    fireEvent.pointerDown(surface()!, pointer(0.1, 0.3))
    fireEvent.pointerMove(window, pointer(0.4, 0.6))
    expect(readoutClasses()).toEqual(new Set(['nx-mask-dims', 'nx-mono']))
    expect(readoutAnchor()).toEqual({ left: '40%', top: '60%' }) // the bottom-right corner
    // 5% (10px) below it: no room — above the band, at its top-right corner (the band starts in the left half).
    fireEvent.pointerMove(window, pointer(0.4, 0.95))
    expect(readoutClasses()).toEqual(new Set(['nx-mask-dims', 'nx-mono', 'nx-mask-dims-above']))
    expect(readoutAnchor()).toEqual({ left: '40%', top: '30%' }) // the top-right corner
    fireEvent.pointerUp(window, pointer(0.4, 0.95))
    // A band drawn from the right half: in the right half the selected zone's
    // label sits at the top-right corner, so the readout takes the other one.
    session.toggleDrawing() // the draw above disarmed it
    fireEvent.pointerDown(surface()!, pointer(0.6, 0.3))
    fireEvent.pointerMove(window, pointer(0.9, 0.95))
    expect(readoutClasses()).toEqual(new Set(['nx-mask-dims', 'nx-mono', 'nx-mask-dims-above', 'nx-mask-dims-left']))
    expect(readoutAnchor()).toEqual({ left: '60%', top: '30%' }) // the top-left corner
    expect(readout()).toBe(dims(30, 65))
    fireEvent.pointerUp(window, pointer(0.9, 0.95))
    expect(readoutClasses()).toEqual(new Set())
    expect(readoutAnchor()).toBeNull()
  })
})

describe('moving', () => {
  it('selects the zone first, then starts the move; the draft follows the drag and one save lands on release', () => {
    const { session, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    // Zone a is selected at the start (the first of the list); the press lands on b.
    const order: string[] = []
    for (const name of ['select', 'beginMove'] as const) {
      const original = session[name]
      Object.assign(session, {
        [name]: (...args: unknown[]) => {
          order.push(name)
          return (original as (...rest: unknown[]) => void)(...args)
        },
      })
    }

    fireEvent.pointerDown(zoneElement('b')!, pointer(0.6, 0.6))
    expect(order).toEqual(['select', 'beginMove'])
    expect(session.selectedId()).toBe('b')
    expect(session.gesture()?.kind).toBe('move')
    expect(zoneElement('b')!.classList.contains('nx-mask-zone-selected')).toBe(true)
    expect(save.calls).toHaveLength(0)

    fireEvent.pointerMove(window, pointer(0.7, 0.6))
    // Live in the draft and on screen, 10% to the right; the size never changes.
    expectRect(rectOf(session.zones()[1]), { x: 0.6, y: 0.5, width: 0.25, height: 0.25 })
    expectRect(drawnRect(zoneElement('b')!), { x: 0.6, y: 0.5, width: 0.25, height: 0.25 })
    expect(readout()).toBe(dims(25, 25))
    expect(save.calls).toHaveLength(0)

    fireEvent.pointerUp(window, pointer(0.7, 0.6))
    expect(session.gesture()).toBeNull()
    expect(save.calls).toHaveLength(1)
    expectRect(rectOf(save.calls[0][1]), { x: 0.6, y: 0.5, width: 0.25, height: 0.25 })
  })

  it('the topmost zone wins where zones overlap: the last of the list', () => {
    const { session, save } = mountEditor([
      zone('a', { x: 0.2, y: 0.2, width: 0.4, height: 0.4 }),
      zone('b', { x: 0.4, y: 0.4, width: 0.4, height: 0.4 }),
    ])
    expect(session.selectedId()).toBe('a')
    fireEvent.pointerDown(surface()!, pointer(0.5, 0.5))
    expect(session.selectedId()).toBe('b')
    expect(session.gesture()).toEqual({ kind: 'move', zoneId: 'b' })
    fireEvent.pointerUp(window, pointer(0.5, 0.5))
    // Released where it began: nothing changed, nothing is saved.
    expect(session.gesture()).toBeNull()
    expect(save.calls).toHaveLength(0)
    expectRect(rectOf(session.zones()[1]), { x: 0.4, y: 0.4, width: 0.4, height: 0.4 })
  })

  it('a press on the selected zone\'s label is a press on the zone', () => {
    const { session } = mountEditor([zone('a')])
    // Just above zone a's top edge (0.25): outside its box, on the label chip.
    fireEvent.pointerDown(labelElement()!, pointer(0.3, 0.22))
    expect(session.selectedId()).toBe('a')
    expect(session.gesture()?.kind).toBe('move')
    fireEvent.pointerUp(window, pointer(0.3, 0.22))
  })

  it('a press on the empty picture deselects', () => {
    const { session, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    drag([0.9, 0.05], [0.9, 0.05])
    expect(session.selectedId()).toBeNull()
    expect(zoneElement('a')!.getAttribute('aria-pressed')).toBe('false')
    expect(save.calls).toHaveLength(0)
  })

  it('dragged past the edge, the zone stops at the frame', () => {
    const { session } = mountEditor([zone('a')])
    drag([0.4, 0.4], [-0.5, -0.5])
    expectRect(rectOf(session.zones()[0]), { x: 0, y: 0, width: 0.25, height: 0.25 })
  })

  it('a secondary button and a second pointer are not the editor\'s', () => {
    const { session, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    // Right button on zone b: no selection change, no gesture.
    fireEvent.pointerDown(zoneElement('b')!, { ...pointer(0.6, 0.6), button: 2 })
    expect(session.selectedId()).toBe('a')
    expect(session.gesture()).toBeNull()
    // A left press on b starts its move; another finger moving is ignored.
    fireEvent.pointerDown(zoneElement('b')!, pointer(0.6, 0.6))
    fireEvent.pointerMove(window, { ...pointer(0.1, 0.1), pointerId: 8 })
    fireEvent.pointerUp(window, { ...pointer(0.1, 0.1), pointerId: 8 })
    expectRect(rectOf(session.zones()[1]), { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })
    expect(session.gesture()?.kind).toBe('move')
    // The dragging pointer's release ends it where it began: nothing saved.
    fireEvent.pointerUp(window, pointer(0.6, 0.6))
    expect(session.gesture()).toBeNull()
    expect(save.calls).toHaveLength(0)
  })
})

describe('resizing', () => {
  it('a handle of the selected zone resizes it: the opposite corner stays put', () => {
    const { session, save } = mountEditor([zone('a')])
    fireEvent.pointerDown(handleElement('se')!, pointer(0.5, 0.5))
    expect(session.gesture()).toEqual({ kind: 'resize', zoneId: 'a', handle: 'se' })
    fireEvent.pointerMove(window, pointer(0.7, 0.8))
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.45, height: 0.55 })
    expect(readout()).toBe(dims(45, 55))
    fireEvent.pointerUp(window, pointer(0.7, 0.8))
    expect(save.calls).toHaveLength(1)
    expectRect(rectOf(save.calls[0][0]), { x: 0.25, y: 0.25, width: 0.45, height: 0.55 })
  })

  it('the north-west handle drags the origin and keeps the far corner', () => {
    const { session } = mountEditor([zone('a')])
    drag([0.25, 0.25], [0.1, 0.1], { target: handleElement('nw')! })
    expectRect(rectOf(session.zones()[0]), { x: 0.1, y: 0.1, width: 0.4, height: 0.4 })
  })

  it('a handle dragged past its partner never flips the zone', () => {
    const { session } = mountEditor([zone('a')])
    drag([0.5, 0.5], [0.1, 0.1], { target: handleElement('se')! })
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.01, height: 0.01 })
  })

  it('only the selected zone has handles', () => {
    mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    expect(zoneElement('a')!.querySelectorAll('.nx-mask-handle')).toHaveLength(8)
    expect(zoneElement('b')!.querySelectorAll('.nx-mask-handle')).toHaveLength(0)
  })
})

describe('cancelling', () => {
  it('pointercancel mid-move leaves the zones as they were and saves nothing', () => {
    const { session, save } = mountEditor([zone('a')])
    drag([0.4, 0.4], [0.6, 0.7], { through: [[0.5, 0.5]], cancel: true })
    expect(session.gesture()).toBeNull()
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.25, height: 0.25 })
    expectRect(drawnRect(zoneElement('a')!), { x: 0.25, y: 0.25, width: 0.25, height: 0.25 })
    expect(save.calls).toHaveLength(0)
    // The gesture's listeners are gone: the pointer moving on changes nothing.
    fireEvent.pointerMove(window, pointer(0.9, 0.9))
    fireEvent.pointerUp(window, pointer(0.9, 0.9))
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.25, height: 0.25 })
    expect(save.calls).toHaveLength(0)
  })

  it('Escape mid-draw cancels the rubber band and leaves the zones unchanged', () => {
    const { session, save } = mountEditor([])
    fireEvent.pointerDown(surface()!, pointer(0.2, 0.2))
    fireEvent.pointerMove(window, pointer(0.5, 0.6))
    expect(rubber()).not.toBeNull()
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    surface()!.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(session.gesture()).toBeNull()
    expect(rubber()).toBeNull()
    expect(session.drawing()).toBe(true) // still armed: nothing was drawn
    fireEvent.pointerMove(window, pointer(0.7, 0.7))
    fireEvent.pointerUp(window, pointer(0.7, 0.7))
    expect(session.zones()).toHaveLength(0)
    expect(save.calls).toHaveLength(0)
  })

  it('Escape steps back one thing at a time, and is Done with nothing left', () => {
    const { session, state, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })], {})
    session.toggleDrawing() // armed, and a zone is selected
    const escape = () => fireEvent.keyDown(surface()!, { key: 'Escape' })

    escape() // drawing armed → disarmed
    expect(session.drawing()).toBe(false)
    expect(session.selectedId()).toBe('a')
    escape() // zone selected → deselected
    expect(session.selectedId()).toBeNull()
    expect(maskEditActive()).toBe(true)
    escape() // nothing left → Done
    expect(maskEditActive()).toBe(false)
    expect(layer()).toBeNull()
    expect(state.state.playerMode()).toBe('whep') // the rung the edit started on
    expect(save.calls).toHaveLength(0) // an edit that changed nothing publishes nothing
  })

  it('while a modal is over the picture the keys are the modal\'s own: nothing steps back behind it', () => {
    // The shortcuts help: an opaque layer over the whole stage that closes on
    // Escape — and leaves the focus exactly where it was, on the picture.
    const [help, setHelp] = createSignal(false)
    const { session } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })], { help })
    session.toggleDrawing() // armed, and a zone is selected: three steps from Done
    const before = rectOf(session.zones()[0])

    setHelp(true)
    for (const key of ['Escape', 'ArrowLeft', 'ArrowUp', 'Delete', 'Backspace']) {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
      surface()!.dispatchEvent(event)
      // Not even taken: the key is left whole for whatever is above the picture.
      expect(event.defaultPrevented, key).toBe(false)
    }
    expect(session.drawing()).toBe(true)
    expect(session.selectedId()).toBe('a')
    expect(session.zones()).toHaveLength(2)
    expectRect(rectOf(session.zones()[0]), before)
    expect(maskEditActive()).toBe(true)

    // The overlay goes, and the same keys are the editor's again.
    setHelp(false)
    fireEvent.keyDown(surface()!, { key: 'Escape' })
    expect(session.drawing()).toBe(false)
    expect(session.selectedId()).toBe('a')
    fireEvent.keyDown(surface()!, { key: 'ArrowLeft' })
    expectRect(rectOf(session.zones()[0]), { x: 0.24, y: 0.25, width: 0.25, height: 0.25 })
  })
})

describe('keyboard', () => {
  it('arrows nudge 1%, Shift 10%, and always take the key', async () => {
    const { session, save } = mountEditor([zone('a')])
    const press = (key: string, shiftKey = false) => {
      const event = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true })
      surface()!.dispatchEvent(event)
      return event
    }
    expect(press('ArrowRight').defaultPrevented).toBe(true)
    expectRect(rectOf(session.zones()[0]), { x: 0.26, y: 0.25, width: 0.25, height: 0.25 })
    expect(press('ArrowUp').defaultPrevented).toBe(true)
    expectRect(rectOf(session.zones()[0]), { x: 0.26, y: 0.24, width: 0.25, height: 0.25 })
    press('ArrowDown', true)
    expectRect(rectOf(session.zones()[0]), { x: 0.26, y: 0.34, width: 0.25, height: 0.25 })
    press('ArrowLeft', true)
    expectRect(rectOf(session.zones()[0]), { x: 0.16, y: 0.34, width: 0.25, height: 0.25 })
    // Every nudge saves, and the writes go through the session's latest-wins
    // queue: one is on the wire, the rest collapse into one trailing commit
    // that carries the zone as it ended up.
    expect(save.commit).toHaveBeenCalledTimes(1)
    expectRect(rectOf(save.calls[0][0]), { x: 0.26, y: 0.25, width: 0.25, height: 0.25 })
    await settle()
    expect(save.calls).toHaveLength(2)
    expectRect(rectOf(save.calls[1][0]), { x: 0.16, y: 0.34, width: 0.25, height: 0.25 })

    // Against the edge the zone stops; a step that moves it saves, one that cannot does not.
    press('ArrowLeft', true) // 0.06
    press('ArrowLeft', true) // 0 — the frame's edge
    expectRect(rectOf(session.zones()[0]), { x: 0, y: 0.34, width: 0.25, height: 0.25 })
    await settle()
    expect(save.calls.at(-1)![0].x).toBeCloseTo(0, 12)
    const saves = save.calls.length
    press('ArrowLeft', true) // nowhere to go
    await settle()
    expect(save.calls).toHaveLength(saves)
  })

  it('with no zone selected the arrows still take the key and change nothing', () => {
    const { session, save } = mountEditor([zone('a')])
    session.select(null)
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true })
    surface()!.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.25, height: 0.25 })
    expect(save.calls).toHaveLength(0)
  })

  it('Delete removes the selected zone and hands the focus back to the picture', async () => {
    const { session, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    // Tab reached the zone: it holds the focus when the key lands.
    const a = zoneElement('a')!
    a.focus()
    expect(document.activeElement).toBe(a)
    fireEvent.keyDown(a, { key: 'Delete' })
    expect(session.zones().map((item) => item.id)).toEqual(['b'])
    expect(zoneElement('a')).toBeNull()
    // The keys keep landing on the editor.
    expect(document.activeElement).toBe(surface())
    fireEvent.keyDown(surface()!, { key: 'ArrowLeft' })
    expectRect(rectOf(session.zones()[0]), { x: 0.49, y: 0.5, width: 0.25, height: 0.25 })
    // Backspace is the other key for it.
    fireEvent.keyDown(surface()!, { key: 'Backspace' })
    expect(session.zones()).toHaveLength(0)
    // Removing the last zone arms drawing.
    expect(session.drawing()).toBe(true)
    // Three edits in a row: the first write, then one trailing commit with the final list.
    await settle()
    expect(save.calls.at(-1)).toEqual([])
    // Nothing selected: the key does nothing and saves nothing.
    const saves = save.calls.length
    fireEvent.keyDown(surface()!, { key: 'Delete' })
    await settle()
    expect(save.calls).toHaveLength(saves)
  })

  it('a held Delete removes one zone, not the list', async () => {
    const { session, save } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    fireEvent.keyDown(surface()!, { key: 'Delete' })
    fireEvent.keyDown(surface()!, { key: 'Delete', repeat: true })
    expect(session.zones().map((item) => item.id)).toEqual(['b'])
    await settle()
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0].map((item) => item.id)).toEqual(['b'])
  })

  it('a focused zone is selected by Enter or Space', () => {
    const { session } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    const b = zoneElement('b')!
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    b.dispatchEvent(space)
    expect(space.defaultPrevented).toBe(true)
    expect(session.selectedId()).toBe('b')
    session.select('a')
    fireEvent.keyDown(b, { key: 'Enter' })
    expect(session.selectedId()).toBe('b')
  })

  it('the zone with the focus is the one the arrows act on, and it keeps the focus', () => {
    const { session } = mountEditor([zone('a'), zone('b', { x: 0.5, y: 0.5, width: 0.25, height: 0.25 })])
    // Tab reached zone b while zone a is the selected one.
    const b = zoneElement('b')!
    b.focus()
    fireEvent.keyDown(b, { key: 'ArrowRight' })
    fireEvent.keyDown(b, { key: 'ArrowRight' })
    expect(session.selectedId()).toBe('b')
    expect(document.activeElement).toBe(b)
    expectRect(drawnRect(b), { x: 0.52, y: 0.5, width: 0.25, height: 0.25 })
    // The other zone did not move.
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.25, height: 0.25 })
  })
})

describe('measurement', () => {
  it('the picture follows the media element: the observer, the window, and the element\'s own events', () => {
    const { img } = mountEditor([zone('a')])
    // The stage turned square: a 2:1 frame in a 300 × 300 box letterboxes to 300 × 150.
    const tall: Box = { left: 100, top: 100, width: 300, height: 300 }
    expect(observers.length).toBeGreaterThan(0)

    rebox(img, tall)
    layoutChange()
    expectBox(surfaceBox(), { left: 0, top: 75, width: 300, height: 150 })

    rebox(img, MEDIA)
    fireEvent(window, new Event('resize'))
    expectBox(surfaceBox(), { left: 0, top: 50, width: 400, height: 200 })

    rebox(img, tall)
    fireEvent(img, new Event('resize'))
    expectBox(surfaceBox(), { left: 0, top: 75, width: 300, height: 150 })
  })

  it('an MJPEG <img> firing load per frame is not re-measured unless the frame size changed', () => {
    const { img } = mountEditor([zone('a')])
    const offscreen: Box = { left: 100, top: 100, width: 300, height: 300 }
    rebox(img, offscreen)
    // Same frame size: the load is turned away, the picture stays where it was measured.
    fireEvent.load(img)
    expectBox(surfaceBox(), { left: 0, top: 50, width: 400, height: 200 })
    // A frame of a new size is measured: a 600 × 400 frame in the 300 × 300
    // box is 300 × 200, letterboxed 50px above and below.
    Object.defineProperty(img, 'naturalWidth', { value: 600, configurable: true })
    fireEvent.load(img)
    expectBox(surfaceBox(), { left: 0, top: 50, width: 300, height: 200 })
  })

  it('a press takes one fresh measurement and keeps it for the whole gesture', () => {
    const { session, img } = mountEditor([zone('a')])
    // The stage shrank behind the layer's back: the picture is now 200 × 100 at (100, 100).
    const small: Box = { left: 100, top: 100, width: 200, height: 100 }
    rebox(img, small)
    // The press lands at 40%, 40% of the NEW picture: 180, 140 in the viewport.
    fireEvent.pointerDown(surface()!, { ...pointer(0, 0), clientX: 180, clientY: 140 })
    expect(session.gesture()?.kind).toBe('move')
    expectBox(surfaceBox(), { left: 0, top: 0, width: 200, height: 100 })

    // Mid-gesture the stage grows again: the gesture keeps its box until it ends.
    rebox(img, MEDIA)
    layoutChange()
    expectBox(surfaceBox(), { left: 0, top: 0, width: 200, height: 100 })
    // +20% of the gesture's 200px box = 40px, to the right.
    fireEvent.pointerMove(window, { clientX: 220, clientY: 140, pointerId: 7 })
    expectRect(rectOf(session.zones()[0]), { x: 0.45, y: 0.25, width: 0.25, height: 0.25 })

    fireEvent.pointerUp(window, { clientX: 220, clientY: 140, pointerId: 7 })
    // Released: the box catches up with the layout.
    expectBox(surfaceBox(), { left: 0, top: 50, width: 400, height: 200 })
  })

  it('no picture on a press hides the picture until there is one again', () => {
    const { img, session } = mountEditor([zone('a')])
    rebox(img, { left: 100, top: 100, width: 0, height: 0 })
    fireEvent.pointerDown(surface()!, pointer(0.4, 0.4))
    expect(surface()).toBeNull()
    expect(document.querySelector('.nx-mask-waiting')?.textContent).toBe(en['privacy.editor.waiting'])
    expect(session.gesture()).toBeNull()
    rebox(img, MEDIA)
    layoutChange()
    expect(surface()).not.toBeNull()
  })

  // The session forces the stage's feed to MJPEG; if that rung exhausts its
  // retries mid-edit, StreamPreview's ladder falls to HLS — a centre-cropped
  // <video> whose frame the device does not mask. Measuring against it would
  // place zones wrong on the stream, so it is no picture at all: the
  // waiting chip, and every gesture locked, exactly as with no media.
  it('the ladder falling to a video rung mid-edit is no picture: the waiting chip, no gesture, and editable when the img is back', () => {
    const img = document.createElement('img')
    rebox(img, MEDIA)
    Object.defineProperty(img, 'naturalWidth', { value: 800, configurable: true })
    Object.defineProperty(img, 'naturalHeight', { value: 400, configurable: true })
    // What the HLS rung mounts: a video with a frame of its own — and still no picture.
    const video = document.createElement('video')
    rebox(video, MEDIA)
    Object.defineProperty(video, 'videoWidth', { value: 1280, configurable: true })
    Object.defineProperty(video, 'videoHeight', { value: 720, configurable: true })

    const state = fakeState([])
    const save = saver()
    const [media, setMedia] = createSignal<PreviewMedia | undefined>(img)
    const session = createMaskEditSession(state.state, { focusZoneId: null, commit: save.commit, onError: save.onError })
    startMaskEdit()
    setMaskEditSession(session)
    render(() => <MaskEditLayer state={state.state as unknown as NextState} media={media} />)
    expect(surface()).not.toBeNull()

    setMedia(video)
    expect(surface()).toBeNull()
    expect(document.querySelector('.nx-mask-waiting')?.textContent).toBe(en['privacy.editor.waiting'])
    // A press where the picture was begins nothing, moves nothing, saves nothing.
    fireEvent.pointerDown(layer()!, pointer(0.4, 0.4))
    fireEvent.pointerMove(window, pointer(0.6, 0.6))
    fireEvent.pointerUp(window, pointer(0.6, 0.6))
    expect(session.gesture()).toBeNull()
    expect(session.selectedId()).toBeNull()
    expect(session.zones()).toHaveLength(0)
    expect(save.calls).toHaveLength(0)

    // The img is back on stage (the rung recovered): the editor with it.
    setMedia(img)
    expect(document.querySelector('.nx-mask-waiting')).toBeNull()
    expect(surface()).not.toBeNull()
    drag([0.2, 0.2], [0.5, 0.6])
    expect(session.zones()).toHaveLength(1)
    expect(save.calls).toHaveLength(1)
  })
})

// ── The focus, when the picture goes ─────────────────────────────────
// The surface is the editor's keys, and it can unmount under the focus: the
// edit finishing, or the measurement lost. The focus hands off — the edit
// bar's Done button while the edit is still open, else the Live view's root
// (which outlives the edit) — instead of dropping to <body>.

describe('the focus, when the picture goes', () => {
  /** A stand-in for the Live view's root: focusable, never in the tab order. */
  function fakeViewRoot(): HTMLElement {
    const root = document.createElement('div')
    root.className = 'nx-view nx-view-flush'
    root.tabIndex = -1
    document.body.appendChild(root)
    return root
  }

  /** A stand-in for the edit bar with its one primary button. */
  function fakeEditBar(): HTMLButtonElement {
    const bar = document.createElement('div')
    bar.className = 'nx-mask-bar'
    const done = document.createElement('button')
    done.className = 'nx-btn nx-btn-primary'
    bar.appendChild(done)
    document.body.appendChild(bar)
    return done
  }

  it('finishing hands the focus to the Live view\'s root', () => {
    const root = fakeViewRoot()
    try {
      const { session } = mountEditor([zone('a')])
      fireEvent.pointerDown(surface()!, pointer(0.4, 0.4))
      fireEvent.pointerUp(window, pointer(0.4, 0.4))
      expect(document.activeElement).toBe(surface())

      session.finish()
      expect(document.activeElement).toBe(root)
    } finally {
      root.remove()
    }
  })

  it('a picture lost mid-edit hands the focus to the edit bar\'s Done button', () => {
    const done = fakeEditBar()
    try {
      const { img } = mountEditor([zone('a')])
      fireEvent.pointerDown(surface()!, pointer(0.4, 0.4))
      fireEvent.pointerUp(window, pointer(0.4, 0.4))
      expect(document.activeElement).toBe(surface())

      // The element loses its box: the measurement goes, the picture with
      // it — the edit itself is still on.
      rebox(img, { left: 100, top: 100, width: 0, height: 0 })
      layoutChange()
      expect(surface()).toBeNull()
      expect(document.activeElement).toBe(done)
    } finally {
      done.parentElement!.remove()
    }
  })

  it('a focus held elsewhere is left alone', () => {
    const { session } = mountEditor([zone('a')])
    const elsewhere = fakeViewRoot()
    try {
      elsewhere.focus()
      expect(document.activeElement).toBe(elsewhere)
      session.finish()
      expect(document.activeElement).toBe(elsewhere)
    } finally {
      elsewhere.remove()
    }
  })
})
