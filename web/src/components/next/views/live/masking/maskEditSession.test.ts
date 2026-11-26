import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSignal, type Setter } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { saveStreamingPatch } from '../../../../../api/client'
import { clearToasts, toastList } from '../../../ui/toast'
import {
  endMaskEdit,
  maskEditActive,
  maskEditFocusZoneId,
  maskEditSession,
  resetMaskEditStoreForTests,
  setMaskEditSession,
  startMaskEdit,
} from '../../../../../lib/maskEditStore'
import { moveZone, type ZonePoint, type ZoneRect } from '../../../../../lib/maskingZonePolicy'
import { en } from '../../../../../i18n/en'
import { de } from '../../../../../i18n/de'
import { es } from '../../../../../i18n/es'
import { fr } from '../../../../../i18n/fr'
import { pt } from '../../../../../i18n/pt'
import { ru } from '../../../../../i18n/ru'
import { zh } from '../../../../../i18n/zh'
import type { AllSettings, MaskingZone } from '../../../../../types'
import type { PlayerMode } from '../../../../../video/playerLadder'
import { createMaskEditSession, type MaskEditState } from './maskEditSession'

// The session's defaults are the two effects the options inject. They are
// the only thing this file mocks: the API client, so the real
// saveStreamingPatch never sees a network.
vi.mock('../../../../../api/client', () => ({ saveStreamingPatch: vi.fn() }))

// The masking-zone editor's controller, in the node env: the draft, the
// gestures, the commands, the saves and the lifecycle. The commit and the
// error report are injected, and the state is hand-built — the
// StreamPipHost fixture pattern, minus the cast: the session asks for the
// four members it reads (MaskEditState), not the whole NextState.

/** Four fields compared at 12 digits — float noise in the last ulps is not a finding. */
function expectRect(rect: ZoneRect, expected: ZoneRect): void {
  for (const field of ['x', 'y', 'width', 'height'] as const) {
    expect(rect[field], field).toBeCloseTo(expected[field], 12)
  }
}

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return {
    id,
    label: '',
    enabled: true,
    type: 'BLACKOUT',
    x: 0.25,
    y: 0.25,
    width: 0.25,
    height: 0.25,
    pixelateSize: 16,
    blurRadius: 10,
    ...overrides,
  }
}

function rectOf(item: MaskingZone): ZoneRect {
  return { x: item.x, y: item.y, width: item.width, height: item.height }
}

/** A hand-built state with the four members the session reads. */
function fakeState(
  opts: { zones?: MaskingZone[]; mode?: PlayerMode; loaded?: boolean; maskingEnabled?: boolean } = {},
) {
  const doc = (zones: MaskingZone[]): AllSettings =>
    ({
      ...settingsFixture,
      streaming: { ...settingsFixture.streaming, maskingZones: zones, maskingEnabled: opts.maskingEnabled ?? false },
    }) as unknown as AllSettings
  const [settings, setSettings] = createSignal<AllSettings | null>(opts.loaded === false ? null : doc(opts.zones ?? []))
  const [playerMode, writeMode] = createSignal<PlayerMode>(opts.mode ?? 'whep')
  // Every write the session makes to the player rung, in order: the real
  // setter, behind a recording wrapper of the same Setter shape.
  const modeWrites: PlayerMode[] = []
  const setPlayerMode: Setter<PlayerMode> = (<U extends PlayerMode>(value: Exclude<U, Function> | ((prev: PlayerMode) => U)) => {
    const next: PlayerMode = typeof value === 'function' ? value(playerMode()) : value
    modeWrites.push(next)
    return writeMode(next) as U
  })
  const updateStreamingDebounced = vi.fn((_patch: Partial<AllSettings['streaming']>) => {})
  const state: MaskEditState = { settings, playerMode, setPlayerMode, updateStreamingDebounced }
  return {
    state,
    updateStreamingDebounced,
    modeWrites,
    /** What the 30 s settings poll does: replaces the mirror wholesale. */
    poll(zones: MaskingZone[]): void {
      setSettings(doc(zones))
    },
    /** Moves the master switch the way a poll's fresh mirror would, zones untouched. */
    pollMasking(on: boolean): void {
      setSettings((prev) =>
        ({ ...prev!, streaming: { ...prev!.streaming, maskingEnabled: on } }) as AllSettings,
      )
    },
    /** What StreamPreview's ladder does behind the session's back. */
    ladderChoose(mode: PlayerMode): void {
      writeMode(mode)
    },
  }
}

function deferred() {
  let release!: () => void
  let fail!: (error: unknown) => void
  const promise = new Promise<void>((resolve, reject) => {
    release = resolve
    fail = reject
  })
  return { promise, release, fail }
}

/** A commit spy with the values it was asked to save, plus gates that make the next commit hang. */
function saver() {
  const calls: MaskingZone[][] = []
  const gates: Array<Promise<void>> = []
  const onError = vi.fn()
  const commit = vi.fn((zones: MaskingZone[]): Promise<void> => {
    calls.push(zones)
    return gates.length > 0 ? gates.shift()! : Promise.resolve()
  })
  return {
    calls,
    commit,
    onError,
    /** Makes the next commit hang until the returned gate is released (or failed). */
    hang() {
      const gate = deferred()
      gates.push(gate.promise)
      return gate
    },
  }
}

/** Lets every microtask (and the commit queue's continuation) run. */
const settle = () => new Promise<void>((done) => setTimeout(done, 0))

/** The labels the commit spy was asked to save, one call per entry. */
const savedLabels = (save: ReturnType<typeof saver>) => save.calls.map((list) => list.map((item) => item.label))

beforeEach(() => {
  resetMaskEditStoreForTests()
  vi.mocked(saveStreamingPatch).mockReset()
  vi.mocked(saveStreamingPatch).mockResolvedValue(undefined)
})

afterEach(() => {
  resetMaskEditStoreForTests()
  clearToasts()
})

describe('the editor store hand-off', () => {
  it('startMaskEdit arms the edit and nothing else: no session, no focus', () => {
    startMaskEdit()
    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()).toBeNull()
    expect(maskEditFocusZoneId()).toBeNull()
  })

  it('a focus zone can ride along, and a later request without one clears it', () => {
    startMaskEdit('b')
    expect(maskEditFocusZoneId()).toBe('b')
    startMaskEdit()
    expect(maskEditFocusZoneId()).toBeNull()
    expect(maskEditActive()).toBe(true)
  })

  it('endMaskEdit disarms the edit and clears the focus, leaving the published session to its owner', () => {
    startMaskEdit('b')
    const session = createMaskEditSession(fakeState().state)
    setMaskEditSession(session)
    endMaskEdit()
    expect(maskEditActive()).toBe(false)
    expect(maskEditFocusZoneId()).toBeNull()
    expect(maskEditSession()).toBe(session)
    setMaskEditSession(null)
    expect(maskEditSession()).toBeNull()
  })
})

describe('seeding', () => {
  it('copies the settings mirror into a draft of its own', () => {
    const mirror = [zone('a'), zone('b')]
    const { state } = fakeState({ zones: mirror })
    const session = createMaskEditSession(state)
    expect(session.zones()).toEqual(mirror)
    // Equal, not shared: the mirror's zone objects are not the draft's.
    expect(session.zones()[0]).not.toBe(mirror[0])
  })

  it('selects the first zone when no focus is requested', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state)
    expect(session.selectedId()).toBe('a')
    expect(session.selectedZone()).toEqual(zone('a'))
  })

  it('selects the focus zone the option names', () => {
    const { state } = fakeState({ zones: [zone('a'), zone('b')] })
    const session = createMaskEditSession(state, { focusZoneId: 'b' })
    expect(session.selectedId()).toBe('b')
  })

  it('reads the focus from the store when the option is absent', () => {
    startMaskEdit('b')
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state)
    expect(session.selectedId()).toBe('b')
  })

  it('an explicit null focus means the first zone, even when the store names one', () => {
    startMaskEdit('b')
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state, { focusZoneId: null })
    expect(session.selectedId()).toBe('a')
  })

  it('falls back to the first zone for a focus that matches none', () => {
    const { state } = fakeState({ zones: [zone('a'), zone('b')] })
    expect(createMaskEditSession(state, { focusZoneId: 'gone' }).selectedId()).toBe('a')
  })

  it('arms drawing and selects nothing when there are no zones', () => {
    const session = createMaskEditSession(fakeState().state)
    expect(session.zones()).toEqual([])
    expect(session.selectedId()).toBeNull()
    expect(session.selectedZone()).toBeNull()
    expect(session.drawing()).toBe(true)
  })

  it('does not arm drawing when there are zones', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    expect(session.drawing()).toBe(false)
  })

  it('starts from an empty list while the settings are not loaded', () => {
    const session = createMaskEditSession(fakeState({ loaded: false }).state)
    expect(session.zones()).toEqual([])
    expect(session.drawing()).toBe(true)
  })

  it('opens clean: no gesture, no rubber band, nothing saved, nothing published', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    expect(session.gesture()).toBeNull()
    expect(session.rubberBand()).toBeNull()
    expect(save.commit).not.toHaveBeenCalled()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
  })
})

describe('the feed switch', () => {
  it('switches the stage to MJPEG while the edit runs', () => {
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    expect(fake.state.playerMode()).toBe('mjpeg')
    session.dispose()
  })

  it.each(['whep', 'h264', 'hls'] as const)('puts the %s rung back when the edit finishes', (mode) => {
    const fake = fakeState({ zones: [zone('a')], mode })
    const session = createMaskEditSession(fake.state)
    session.finish()
    expect(fake.state.playerMode()).toBe(mode)
    expect(fake.modeWrites).toEqual(['mjpeg', mode])
  })

  it('puts the previous rung back when the editor is disposed', () => {
    const fake = fakeState({ zones: [zone('a')], mode: 'whep' })
    const session = createMaskEditSession(fake.state)
    session.dispose()
    expect(fake.state.playerMode()).toBe('whep')
  })

  it('leaves the player ladder its choice when the user was already on MJPEG', () => {
    const fake = fakeState({ zones: [zone('a')], mode: 'mjpeg' })
    const session = createMaskEditSession(fake.state)
    // MJPEG gives up mid-edit and StreamPreview's ladder falls through.
    fake.ladderChoose('hls')
    session.finish()
    expect(fake.state.playerMode()).toBe('hls')
    expect(fake.modeWrites).toEqual(['mjpeg'])
  })

  it('releases the feed once: dispose after finish changes nothing', () => {
    const fake = fakeState({ zones: [zone('a')], mode: 'whep' })
    const session = createMaskEditSession(fake.state)
    session.finish()
    session.dispose()
    session.finish()
    expect(fake.modeWrites).toEqual(['mjpeg', 'whep'])
  })
})

describe('drawing', () => {
  it('shows the rubber band live while the drag runs, and saves nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.beginDraw({ x: 0.25, y: 0.25 })
    expect(session.gesture()).toEqual({ kind: 'draw' })
    expect(session.rubberBand()).toEqual({ x: 0.25, y: 0.25, width: 0, height: 0 })
    session.dragTo({ x: 0.75, y: 0.5 })
    expect(session.rubberBand()).toEqual({ x: 0.25, y: 0.25, width: 0.5, height: 0.25 })
    expect(session.zones()).toEqual([])
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('creates the zone, selects it, disarms drawing and saves exactly once when the drag ends', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.beginDraw({ x: 0.25, y: 0.25 })
    session.dragTo({ x: 0.75, y: 0.5 })
    session.endGesture()
    const drawn = session.zones()
    expect(drawn).toHaveLength(1)
    expect(drawn[0]).toEqual({
      id: expect.any(String),
      label: '',
      enabled: true,
      type: 'BLACKOUT',
      x: 0.25,
      y: 0.25,
      width: 0.5,
      height: 0.25,
      pixelateSize: 16,
      blurRadius: 10,
    })
    expect(session.selectedId()).toBe(drawn[0].id)
    expect(session.drawing()).toBe(false)
    expect(session.gesture()).toBeNull()
    expect(session.rubberBand()).toBeNull()
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual(drawn)
    // A gesture ends once: a second endGesture is not a second save.
    session.endGesture()
    expect(save.commit).toHaveBeenCalledTimes(1)
  })

  it('ends a right-to-left, bottom-up drag in the same zone', () => {
    const forward = createMaskEditSession(fakeState().state)
    forward.beginDraw({ x: 0.25, y: 0.25 })
    forward.dragTo({ x: 0.75, y: 0.5 })
    forward.endGesture()
    const backward = createMaskEditSession(fakeState().state)
    backward.beginDraw({ x: 0.75, y: 0.5 })
    backward.dragTo({ x: 0.25, y: 0.25 })
    backward.endGesture()
    expect(rectOf(backward.zones()[0])).toEqual(rectOf(forward.zones()[0]))
    expect(rectOf(backward.zones()[0])).toEqual({ x: 0.25, y: 0.25, width: 0.5, height: 0.25 })
  })

  it('clips a drag that leaves the picture to the picture', () => {
    const session = createMaskEditSession(fakeState().state)
    session.beginDraw({ x: 0.75, y: 0.75 })
    session.dragTo({ x: 1.6, y: 1.2 })
    expect(session.rubberBand()).toEqual({ x: 0.75, y: 0.75, width: 0.25, height: 0.25 })
    session.endGesture()
    expectRect(rectOf(session.zones()[0]), { x: 0.75, y: 0.75, width: 0.25, height: 0.25 })
  })

  it('puts the drawn rectangle on the four-decimal grid', () => {
    const session = createMaskEditSession(fakeState().state)
    session.beginDraw({ x: 0.1, y: 0.1 })
    session.dragTo({ x: 0.8, y: 0.9 })
    session.endGesture()
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.1, y: 0.1, width: 0.7, height: 0.8 })
  })

  it('gives each drawn zone its own id', () => {
    const session = createMaskEditSession(fakeState().state)
    session.beginDraw({ x: 0.1, y: 0.1 })
    session.dragTo({ x: 0.3, y: 0.3 })
    session.endGesture()
    session.toggleDrawing()
    session.beginDraw({ x: 0.5, y: 0.5 })
    session.dragTo({ x: 0.7, y: 0.7 })
    session.endGesture()
    const [first, second] = session.zones()
    expect(first.id).not.toBe(second.id)
  })

  it('ignores a drag under 2% and leaves drawing armed', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.beginDraw({ x: 0.5, y: 0.5 })
    session.dragTo({ x: 0.6, y: 0.51 })
    session.endGesture()
    expect(session.zones()).toEqual([])
    expect(session.drawing()).toBe(true)
    expect(session.gesture()).toBeNull()
    expect(session.rubberBand()).toBeNull()
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('a tap — a drag with no dragTo at all — draws nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.beginDraw({ x: 0.5, y: 0.5 })
    session.endGesture()
    expect(session.zones()).toEqual([])
    expect(session.drawing()).toBe(true)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('cancels without creating anything, and leaves drawing armed', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.beginDraw({ x: 0.25, y: 0.25 })
    session.dragTo({ x: 0.75, y: 0.5 })
    session.cancelGesture()
    expect(session.gesture()).toBeNull()
    expect(session.rubberBand()).toBeNull()
    expect(session.zones()).toEqual([])
    expect(session.drawing()).toBe(true)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('does not start a draw unless drawing is armed', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.beginDraw({ x: 0.5, y: 0.5 })
    session.dragTo({ x: 0.9, y: 0.9 })
    session.endGesture()
    expect(session.gesture()).toBeNull()
    expect(session.zones()).toHaveLength(1)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('is one shot: once a zone is drawn the next drag draws nothing until drawing is re-armed', () => {
    const session = createMaskEditSession(fakeState().state)
    session.beginDraw({ x: 0.1, y: 0.1 })
    session.dragTo({ x: 0.3, y: 0.3 })
    session.endGesture()
    expect(session.drawing()).toBe(false)
    session.beginDraw({ x: 0.5, y: 0.5 })
    expect(session.gesture()).toBeNull()
    session.toggleDrawing()
    session.beginDraw({ x: 0.5, y: 0.5 })
    expect(session.gesture()).toEqual({ kind: 'draw' })
  })

  it('ignores a pointer position that is not a number', () => {
    const session = createMaskEditSession(fakeState().state)
    session.beginDraw({ x: Number.NaN, y: 0.5 })
    expect(session.gesture()).toBeNull()
    session.beginDraw({ x: 0.5, y: 0.5 })
    session.dragTo({ x: Number.POSITIVE_INFINITY, y: 0.5 })
    expect(session.rubberBand()).toEqual({ x: 0.5, y: 0.5, width: 0, height: 0 })
  })
})

describe('moving', () => {
  const origin: ZonePoint = { x: 0.375, y: 0.375 }

  it('moves the draft zone live while the drag runs, and saves nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state, { commit: save.commit })
    session.beginMove('a', origin)
    expect(session.gesture()).toEqual({ kind: 'move', zoneId: 'a' })
    expect(session.selectedId()).toBe('a')
    session.dragTo({ x: 0.5, y: 0.625 })
    expectRect(rectOf(session.zones()[0]), { x: 0.375, y: 0.5, width: 0.25, height: 0.25 })
    // Only the dragged zone moved.
    expect(session.zones()[1]).toEqual(zone('b'))
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('saves exactly once when the drag ends', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.beginMove('a', origin)
    session.dragTo({ x: 0.5, y: 0.625 })
    session.endGesture()
    expect(session.gesture()).toBeNull()
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual([zone('a', { x: 0.375, y: 0.5 })])
    session.endGesture()
    expect(save.commit).toHaveBeenCalledTimes(1)
  })

  it('saves nothing when the drag ends where it began', () => {
    const save = saver()
    const before = [zone('a')]
    const session = createMaskEditSession(fakeState({ zones: before }).state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.beginMove('a', origin)
    session.dragTo({ x: 0.37503, y: 0.375 })
    session.endGesture()
    expect(save.commit).not.toHaveBeenCalled()
    // The zone is the very object it was, not a re-rounded copy.
    expect(session.zones()[0]).toBe(untouched)
  })

  it('stops at the edges of the picture', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.beginMove('a', origin)
    session.dragTo({ x: 5, y: 5 })
    expectRect(rectOf(session.zones()[0]), { x: 0.75, y: 0.75, width: 0.25, height: 0.25 })
    session.dragTo({ x: -5, y: -5 })
    expectRect(rectOf(session.zones()[0]), { x: 0, y: 0, width: 0.25, height: 0.25 })
  })

  it('computes from the zone as the gesture began, not frame by frame', () => {
    const start = zone('a')
    const session = createMaskEditSession(fakeState({ zones: [start] }).state)
    session.beginMove('a', { x: 0.25, y: 0.25 })
    // Out past the right edge (the zone stops there), then back to +25%.
    session.dragTo({ x: 2, y: 0.25 })
    session.dragTo({ x: 0.5, y: 0.25 })
    // +25% of where the pointer went down. Restarting from the clamped
    // position would have answered 0.25 — back where the zone began.
    expectRect(rectOf(session.zones()[0]), { x: 0.5, y: 0.25, width: 0.25, height: 0.25 })
    expectRect(rectOf(session.zones()[0]), moveZone(start, 0.25, 0))
  })

  it('puts the moved rectangle on the four-decimal grid when it ends', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.beginMove('a', origin)
    session.dragTo({ x: 0.423456, y: 0.678912 })
    session.endGesture()
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.2985, y: 0.5539, width: 0.25, height: 0.25 })
  })

  it('cancels back to the zone as the gesture began', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.beginMove('a', origin)
    session.dragTo({ x: 0.5, y: 0.625 })
    session.cancelGesture()
    expect(session.gesture()).toBeNull()
    expect(session.zones()[0]).toBe(untouched)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('brings a zone that overhangs the picture into it as it moves', () => {
    // x = 90%, width = 30%: a zone the panel's sliders can store.
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a', { x: 0.9, width: 0.3 })] }).state, {
      commit: save.commit,
    })
    session.beginMove('a', { x: 0.95, y: 0.25 })
    session.dragTo({ x: 0.95, y: 0.5 })
    session.endGesture()
    expectRect(rectOf(session.zones()[0]), { x: 0.9, y: 0.5, width: 0.1, height: 0.25 })
    expect(save.commit).toHaveBeenCalledTimes(1)
  })

  it('ignores a zone the draft does not hold', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.beginMove('gone', origin)
    expect(session.gesture()).toBeNull()
    expect(session.selectedId()).toBe('a')
  })

  it('runs one gesture at a time', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state)
    session.beginMove('a', origin)
    // Every other begin is refused while the first gesture is in flight.
    session.beginMove('b', origin)
    session.beginResize('b', 'se', origin)
    session.beginDraw(origin)
    expect(session.gesture()).toEqual({ kind: 'move', zoneId: 'a' })
    session.dragTo({ x: 0.5, y: 0.625 })
    expect(session.zones()[1]).toEqual(zone('b'))
  })

  it('does nothing without a gesture', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.dragTo({ x: 0.9, y: 0.9 })
    session.endGesture()
    session.cancelGesture()
    expect(session.zones()).toEqual([zone('a')])
    expect(save.commit).not.toHaveBeenCalled()
  })
})

describe('resizing', () => {
  it('resizes the draft zone live from its corner, and saves nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.beginResize('a', 'se', { x: 0.5, y: 0.5 })
    expect(session.gesture()).toEqual({ kind: 'resize', zoneId: 'a', handle: 'se' })
    expect(session.selectedId()).toBe('a')
    session.dragTo({ x: 0.625, y: 0.375 })
    expectRect(rectOf(session.zones()[0]), { x: 0.25, y: 0.25, width: 0.375, height: 0.125 })
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('saves exactly once when the drag ends', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.beginResize('a', 'se', { x: 0.5, y: 0.5 })
    session.dragTo({ x: 0.625, y: 0.375 })
    session.endGesture()
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual([zone('a', { width: 0.375, height: 0.125 })])
  })

  it('a north-west handle moves the origin and keeps the far corner', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.beginResize('a', 'nw', { x: 0.25, y: 0.25 })
    session.dragTo({ x: 0.375, y: 0.125 })
    session.endGesture()
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.375, y: 0.125, width: 0.125, height: 0.375 })
  })

  it('an edge handle moves one edge only', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.beginResize('a', 'n', { x: 0.375, y: 0.25 })
    // The pointer also travels sideways; the north edge has no use for it.
    session.dragTo({ x: 0.9, y: 0.375 })
    session.endGesture()
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.25, y: 0.375, width: 0.25, height: 0.125 })
  })

  it('saves nothing when the drag ends where it began', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.beginResize('a', 'se', { x: 0.5, y: 0.5 })
    session.endGesture()
    expect(save.commit).not.toHaveBeenCalled()
    expect(session.zones()[0]).toEqual(zone('a'))
  })

  it('cancels back to the zone as the gesture began', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.beginResize('a', 'se', { x: 0.5, y: 0.5 })
    session.dragTo({ x: 2, y: 2 })
    session.cancelGesture()
    expect(session.gesture()).toBeNull()
    expect(session.zones()[0]).toBe(untouched)
    expect(save.commit).not.toHaveBeenCalled()
  })
})

describe('commands', () => {
  it('select selects a zone of the draft; null or an unknown id deselects', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state, { commit: save.commit })
    session.select('b')
    expect(session.selectedId()).toBe('b')
    expect(session.selectedZone()).toEqual(zone('b'))
    session.select('gone')
    expect(session.selectedId()).toBeNull()
    session.select('a')
    session.select(null)
    expect(session.selectedId()).toBeNull()
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('toggleDrawing flips drawing and saves nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.toggleDrawing()
    expect(session.drawing()).toBe(true)
    session.toggleDrawing()
    expect(session.drawing()).toBe(false)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('updateZone changes the selected zone and saves', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state, { commit: save.commit })
    session.updateZone({ label: 'Door', type: 'BLUR', enabled: false })
    expect(session.zones()).toEqual([zone('a', { label: 'Door', type: 'BLUR', enabled: false }), zone('b')])
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual(session.zones())
  })

  it('updateZone can address a zone by id without moving the selection to it', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state)
    session.updateZone({ label: 'Window' }, 'b')
    expect(session.selectedId()).toBe('a')
    expect(session.zones()[1].label).toBe('Window')
  })

  it('updateZone with no zone selected, or an unknown id, does nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState().state, { commit: save.commit })
    session.updateZone({ label: 'Door' })
    session.updateZone({ label: 'Door' }, 'gone')
    expect(session.zones()).toEqual([])
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('updateZone keeps the zone inside the frame', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.updateZone({ x: 0.9, width: 0.3 })
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.9, y: 0.25, width: 0.1, height: 0.25 })
    session.updateZone({ x: -0.5 })
    expect(rectOf(session.zones()[0])).toEqual({ x: 0, y: 0.25, width: 0.1, height: 0.25 })
    session.updateZone({ width: 0.001, height: 0.001 })
    expect(rectOf(session.zones()[0])).toEqual({ x: 0, y: 0.25, width: 0.01, height: 0.01 })
    session.updateZone({ width: 5 })
    expect(rectOf(session.zones()[0])).toEqual({ x: 0, y: 0.25, width: 1, height: 0.01 })
  })

  it('updateZone puts geometry on the four-decimal grid', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.updateZone({ x: 0.123456 })
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.1235, y: 0.25, width: 0.25, height: 0.25 })
  })

  it('updateZone leaves a geometry field alone when its number is not one', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.updateZone({ x: Number.NaN })
    expect(session.zones()[0]).toEqual(zone('a'))
    expect(save.commit).not.toHaveBeenCalled()
    // The fields that are usable still apply — an empty X box must not eat the label typed with it.
    session.updateZone({ x: Number.NaN, width: Number.POSITIVE_INFINITY, label: 'Door' })
    expect(session.zones()[0]).toEqual(zone('a', { label: 'Door' }))
    expect(save.commit).toHaveBeenCalledTimes(1)
  })

  it('updateZone saves nothing when nothing changes', () => {
    const save = saver()
    // x = 0.3 is stored as a 32-bit float on the device and reads back widened.
    const session = createMaskEditSession(fakeState({ zones: [zone('a', { x: 0.3 })] }).state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.updateZone({ label: '' })
    session.updateZone({ width: 0.25 })
    // The read-back typed back into the same field is not a new position.
    session.updateZone({ x: Math.fround(0.3) })
    expect(Math.fround(0.3)).not.toBe(0.3)
    expect(session.zones()[0]).toBe(untouched)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('removeZone removes the zone and moves the selection to its neighbour', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b'), zone('c')] }).state, {
      commit: save.commit,
    })
    session.select('b')
    session.removeZone('b')
    expect(session.zones()).toEqual([zone('a'), zone('c')])
    expect(session.selectedId()).toBe('c')
    // The last of the list takes the one before it.
    session.removeZone('c')
    expect(session.zones()).toEqual([zone('a')])
    expect(session.selectedId()).toBe('a')
    await settle()
    expect(save.calls).toHaveLength(2)
    expect(save.calls[1]).toEqual([zone('a')])
  })

  it('removeZone leaves the selection alone when another zone is removed', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state)
    session.select('a')
    session.removeZone('b')
    expect(session.selectedId()).toBe('a')
    expect(session.zones()).toEqual([zone('a')])
  })

  it('removing the last zone deselects and arms drawing, as an empty list does on opening', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.removeZone('a')
    expect(session.zones()).toEqual([])
    expect(session.selectedId()).toBeNull()
    expect(session.drawing()).toBe(true)
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual([])
  })

  it('removeZone ignores a zone the draft does not hold', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.removeZone('gone')
    expect(session.zones()).toEqual([zone('a')])
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('nudge moves the selected zone by the step and saves', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.nudge(0.01, 0)
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.26, y: 0.25, width: 0.25, height: 0.25 })
    session.nudge(0, -0.1)
    expect(rectOf(session.zones()[0])).toEqual({ x: 0.26, y: 0.15, width: 0.25, height: 0.25 })
    await settle()
    expect(save.calls).toHaveLength(2)
  })

  it('nudge stops at the edge of the picture and then saves nothing', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a', { x: 0.75, y: 0.75 })] }).state, {
      commit: save.commit,
    })
    session.nudge(0.01, 0.01)
    expectRect(rectOf(session.zones()[0]), { x: 0.75, y: 0.75, width: 0.25, height: 0.25 })
    expect(save.commit).not.toHaveBeenCalled()
    // Back the other way still works.
    session.nudge(-0.1, 0)
    expectRect(rectOf(session.zones()[0]), { x: 0.65, y: 0.75, width: 0.25, height: 0.25 })
    expect(save.commit).toHaveBeenCalledTimes(1)
  })

  it('nudge does nothing with no zone selected', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    session.select(null)
    session.nudge(0.01, 0.01)
    expect(session.zones()).toEqual([zone('a')])
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('no command touches the draft while a gesture is in flight', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a'), zone('b')] }).state, { commit: save.commit })
    session.beginMove('a', { x: 0.375, y: 0.375 })
    session.updateZone({ label: 'Door' })
    session.removeZone('b')
    session.nudge(0.1, 0)
    session.select('b')
    session.toggleDrawing()
    expect(session.zones()).toEqual([zone('a'), zone('b')])
    expect(session.selectedId()).toBe('a')
    expect(session.drawing()).toBe(false)
    expect(session.gesture()).toEqual({ kind: 'move', zoneId: 'a' })
    expect(save.commit).not.toHaveBeenCalled()
  })
})

describe('the settings poll', () => {
  it('never re-seeds the draft mid-gesture', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a'), zone('b')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    session.beginMove('a', { x: 0.375, y: 0.375 })
    session.dragTo({ x: 0.5, y: 0.625 })
    // The 30 s poll lands with a list from before the drag — or from another client.
    fake.poll([zone('a', { label: 'elsewhere' }), zone('c')])
    expect(session.zones()).toEqual([zone('a', { x: 0.375, y: 0.5 }), zone('b')])
    session.endGesture()
    expect(save.calls).toHaveLength(1)
    expect(save.calls[0]).toEqual([zone('a', { x: 0.375, y: 0.5 }), zone('b')])
  })

  it('never re-seeds the draft between gestures either', () => {
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    session.removeZone('a')
    fake.poll([zone('a'), zone('b')])
    expect(session.zones()).toEqual([])
    expect(session.selectedZone()).toBeNull()
  })

  it('keeps the selection through a poll that replaces every zone object', () => {
    const fake = fakeState({ zones: [zone('a'), zone('b')] })
    const session = createMaskEditSession(fake.state, { focusZoneId: 'b' })
    fake.poll([zone('a'), zone('b')])
    expect(session.selectedId()).toBe('b')
    expect(session.selectedZone()).toEqual(zone('b'))
  })
})

describe('finish', () => {
  it('publishes the final list through updateStreamingDebounced when it differs from the mirror', () => {
    startMaskEdit()
    const save = saver()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    session.updateZone({ label: 'Door' })
    session.finish()
    expect(fake.updateStreamingDebounced).toHaveBeenCalledTimes(1)
    expect(fake.updateStreamingDebounced.mock.calls[0][0]).toEqual({ maskingZones: [zone('a', { label: 'Door' })] })
  })

  it('publishes nothing while the draft equals the mirror', () => {
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    session.select('a')
    session.toggleDrawing()
    session.finish()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('publishes nothing once the mirror holds the float-widened read-back of the saved zones', () => {
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    session.updateZone({ x: 0.3 })
    // The device keeps the rectangle as 32-bit floats: this is what the next GET brings back.
    fake.poll([zone('a', { x: Math.fround(0.3) })])
    session.finish()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('publishes an emptied list too', () => {
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    session.removeZone('a')
    session.finish()
    expect(fake.updateStreamingDebounced.mock.calls[0][0]).toEqual({ maskingZones: [] })
  })

  it('ends the edit in the store', () => {
    startMaskEdit('a')
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    expect(maskEditActive()).toBe(true)
    session.finish()
    expect(maskEditActive()).toBe(false)
    expect(maskEditFocusZoneId()).toBeNull()
  })

  it('publishes before the edit ends, and ends only once', () => {
    startMaskEdit()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state)
    session.removeZone('a')
    session.finish()
    session.finish()
    expect(fake.updateStreamingDebounced).toHaveBeenCalledTimes(1)
  })

  it('drops a gesture in flight rather than saving it', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.beginMove('a', { x: 0.375, y: 0.375 })
    session.dragTo({ x: 0.5, y: 0.625 })
    session.finish()
    expect(session.gesture()).toBeNull()
    expect(session.zones()[0]).toBe(untouched)
    expect(save.commit).not.toHaveBeenCalled()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('afterwards the session is inert', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    session.finish()
    session.beginMove('a', { x: 0.375, y: 0.375 })
    session.dragTo({ x: 0.5, y: 0.625 })
    session.endGesture()
    session.updateZone({ label: 'Door' })
    session.removeZone('a')
    session.nudge(0.1, 0)
    session.select(null)
    session.toggleDrawing()
    session.beginDraw({ x: 0.1, y: 0.1 })
    expect(session.zones()).toEqual([zone('a')])
    expect(session.selectedId()).toBe('a')
    expect(session.drawing()).toBe(false)
    expect(session.gesture()).toBeNull()
    expect(save.commit).not.toHaveBeenCalled()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
  })
})

describe('dispose', () => {
  it('releases the feed without publishing', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')], mode: 'whep' })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    session.updateZone({ label: 'Door' })
    session.dispose()
    expect(fake.state.playerMode()).toBe('whep')
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
    // The edit itself was already saved by its commit.
    expect(save.calls).toHaveLength(1)
  })

  it('leaves ending the edit to its owner', () => {
    startMaskEdit()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.dispose()
    expect(maskEditActive()).toBe(true)
    endMaskEdit()
    expect(maskEditActive()).toBe(false)
  })

  it('drops a gesture in flight', () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    const untouched = session.zones()[0]
    session.beginMove('a', { x: 0.375, y: 0.375 })
    session.dragTo({ x: 0.5, y: 0.625 })
    session.dispose()
    expect(session.gesture()).toBeNull()
    expect(session.zones()[0]).toBe(untouched)
    expect(save.commit).not.toHaveBeenCalled()
  })

  it('lets the commits already queued land', async () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')] })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    const gate = save.hang()
    session.updateZone({ label: 'Door' })
    session.updateZone({ label: 'Window' })
    session.dispose()
    gate.release()
    await settle()
    expect(savedLabels(save)).toEqual([['Door'], ['Window']])
  })

  it('afterwards the session is inert, and a later finish publishes nothing', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')], mode: 'whep' })
    const session = createMaskEditSession(fake.state, { commit: save.commit })
    session.dispose()
    session.updateZone({ label: 'Door' })
    session.removeZone('a')
    session.beginDraw({ x: 0.1, y: 0.1 })
    session.finish()
    expect(session.zones()).toEqual([zone('a')])
    expect(save.commit).not.toHaveBeenCalled()
    expect(fake.updateStreamingDebounced).not.toHaveBeenCalled()
    expect(fake.modeWrites).toEqual(['mjpeg', 'whep'])
  })
})

describe('saves', () => {
  it('collapse rapid edits into one trailing commit that carries the latest list', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    const gate = save.hang()
    session.updateZone({ label: 'one' })
    session.updateZone({ label: 'two' })
    session.updateZone({ label: 'three' })
    expect(savedLabels(save)).toEqual([['one']])
    gate.release()
    await settle()
    expect(savedLabels(save)).toEqual([['one'], ['three']])
  })

  it('never put two commits on the wire at once', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, { commit: save.commit })
    const first = save.hang()
    session.updateZone({ label: 'one' })
    session.updateZone({ label: 'two' })
    // The gate the next commit will hang on, queued before "one" settles.
    const second = save.hang()
    first.release()
    await settle()
    // "two" took the wire the moment "one" settled…
    expect(savedLabels(save)).toEqual([['one'], ['two']])
    // …and "three" waits behind it rather than going out beside it.
    session.updateZone({ label: 'three' })
    expect(savedLabels(save)).toEqual([['one'], ['two']])
    second.release()
    await settle()
    expect(savedLabels(save)).toEqual([['one'], ['two'], ['three']])
  })

  it('report a failed save, and the next edit sends the whole draft again', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, {
      commit: save.commit,
      onError: save.onError,
    })
    const gate = save.hang()
    session.updateZone({ label: 'Door' })
    gate.fail(new Error('device said no'))
    await settle()
    expect(save.onError).toHaveBeenCalledTimes(1)
    expect(save.onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'device said no' }))
    // The draft kept the edit the device refused…
    expect(session.zones()).toEqual([zone('a', { label: 'Door' })])
    // …so the next commit carries it along with the newer one: the retry.
    session.updateZone({ enabled: false })
    expect(save.calls).toHaveLength(2)
    expect(save.calls[1]).toEqual([zone('a', { label: 'Door', enabled: false })])
  })

  it('a failed save never blocks the commit held behind it', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, {
      commit: save.commit,
      onError: save.onError,
    })
    const gate = save.hang()
    session.updateZone({ label: 'Door' })
    session.updateZone({ label: 'Window' })
    gate.fail(new Error('device said no'))
    await settle()
    expect(save.onError).toHaveBeenCalledTimes(1)
    expect(savedLabels(save)).toEqual([['Door'], ['Window']])
  })

  it('a failure is reported once per failed commit, not per edit', async () => {
    const save = saver()
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state, {
      commit: save.commit,
      onError: save.onError,
    })
    const first = save.hang()
    session.updateZone({ label: 'one' })
    session.updateZone({ label: 'two' })
    session.updateZone({ label: 'three' })
    first.fail(new Error('offline'))
    const second = save.hang()
    await settle()
    expect(save.onError).toHaveBeenCalledTimes(1)
    second.fail(new Error('still offline'))
    await settle()
    expect(save.onError).toHaveBeenCalledTimes(2)
    expect(savedLabels(save)).toEqual([['one'], ['three']])
  })
})

describe('the default effects', () => {
  it('saves through saveStreamingPatch({ maskingZones })', () => {
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.updateZone({ label: 'Door' })
    expect(vi.mocked(saveStreamingPatch)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(saveStreamingPatch)).toHaveBeenCalledWith({ maskingZones: [zone('a', { label: 'Door' })] })
    session.dispose()
  })

  it('reports a failed save with a danger toast', async () => {
    vi.mocked(saveStreamingPatch).mockRejectedValue(new Error('device said no'))
    const session = createMaskEditSession(fakeState({ zones: [zone('a')] }).state)
    session.updateZone({ label: 'Door' })
    await settle()
    expect(toastList().map((entry) => [entry.tone, entry.message])).toEqual([
      ['danger', 'Could not save masking zones'],
    ])
    session.dispose()
  })
})

describe('the editor catalog keys', () => {
  const catalogs = { en, de, es, fr, pt, ru, zh }

  // privacy.empty is also the classic PrivacyMaskingCard's empty state, and
  // the classic shell has no "Edit zones" button. The beta editor's empty
  // state therefore rides its own key, and the shared one keeps the wording
  // the classic card was written against.
  it('carries the beta empty state under privacy.editor.empty in every catalog', () => {
    for (const [name, catalog] of Object.entries(catalogs)) {
      const empty = catalog['privacy.editor.empty'] ?? ''
      const button = catalog['privacy.editZones'] ?? ''
      expect(empty, `${name} has no privacy.editor.empty`).not.toBe('')
      // The hint names the button as the same catalog labels it.
      expect(empty, name).toContain(button)
      // One string sends the user to Add Zone, the other to Edit zones: they are never the same.
      expect(empty, name).not.toBe(catalog['privacy.empty'])
    }
    expect(en['privacy.editor.empty']).toBe('No masking zones yet. Click "Edit zones" to draw one on the live picture.')
  })

  it('leaves the classic card empty state exactly as it was', () => {
    // The wording PrivacyMaskingCard shipped with. A hint that mentions
    // "Edit zones" points classic-UI users at a button that shell does not have.
    expect(en['privacy.empty']).toBe('No masking zones defined. Click "Add Zone" to create one.')
  })

  it('carries the editor\'s master-switch warning in every catalog', () => {
    for (const [name, catalog] of Object.entries(catalogs)) {
      for (const key of ['privacy.editor.maskingOff', 'privacy.editor.maskingOffAction'] as const) {
        expect((catalog[key] ?? '').length, `${name} has no ${key}`).toBeGreaterThan(0)
      }
    }
    expect(en['privacy.editor.maskingOff']).toBe('Privacy masking is off — these zones will not paint on the stream.')
    expect(en['privacy.editor.maskingOffAction']).toBe('Turn on masking')
  })
})

describe('the masking master switch', () => {
  it('reads the streaming mirror, and follows a poll that replaces it', () => {
    const fake = fakeState({ zones: [zone('a')], maskingEnabled: true })
    const session = createMaskEditSession(fake.state)
    expect(session.maskingEnabled()).toBe(true)
    // What a fresh mirror from the poll carries: the switch is read live, so
    // the editor sees the new value.
    fake.pollMasking(false)
    expect(session.maskingEnabled()).toBe(false)
    fake.pollMasking(true)
    expect(session.maskingEnabled()).toBe(true)
  })

  it('an unloaded status leaves the API default: off', () => {
    const fake = fakeState({ loaded: false })
    const session = createMaskEditSession(fake.state)
    expect(session.maskingEnabled()).toBe(API_DEFAULTS.maskingEnabled)
    expect(API_DEFAULTS.maskingEnabled).toBe(false)
  })

  it('setMaskingEnabled goes through the streaming mirror, and never touches the draft', () => {
    const save = saver()
    const fake = fakeState({ zones: [zone('a')], maskingEnabled: false })
    const session = createMaskEditSession(fake.state, { commit: save.commit, onError: save.onError })

    session.setMaskingEnabled(true)
    expect(fake.updateStreamingDebounced).toHaveBeenCalledTimes(1)
    expect(fake.updateStreamingDebounced).toHaveBeenCalledWith({ maskingEnabled: true })
    session.setMaskingEnabled(false)
    expect(fake.updateStreamingDebounced).toHaveBeenLastCalledWith({ maskingEnabled: false })

    // The draft and its saves are untouched: the master switch is not part
    // of the zone draft.
    expect(session.zones()).toEqual([zone('a')])
    expect(save.commit).not.toHaveBeenCalled()
    expect(session.maskingEnabled()).toBe(false) // only the mirror answers, and it has not yet
  })
})
