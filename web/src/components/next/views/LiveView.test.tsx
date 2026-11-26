// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@solidjs/testing-library'
import { Show, batch, createSignal } from 'solid-js'
import settingsFixture from '../../../../contract/settings.json'
import { en } from '../../../i18n/en'
import { useKeyboardShortcuts } from '../../../hooks/useKeyboardShortcuts'
import { clearToasts, toastList } from '../ui/toast'
import {
  endMaskEdit,
  maskEditActive,
  maskEditSession,
  resetMaskEditStoreForTests,
  setMaskEditSession,
  startMaskEdit,
} from '../../../lib/maskEditStore'
import { hashRouter, isNextRoute, type RouteName } from '../../../lib/router'
import type { AllSettings, DeviceStatus, MaskingZone, WebUiLayoutDto } from '../../../types'
import type { InspectorLayoutController } from '../../../hooks/useInspectorLayout'
import { defaultInspectorLayout } from '../../../lib/inspectorLayout'
import type { PlayerMode } from '../../../video/playerLadder'
import { createMaskEditSession, type MaskEditState } from './live/masking/maskEditSession'
import type { NextState } from '../NextState'
import LiveView from './LiveView'

// The Live view as the owner of the masking-zone editor, in jsdom — one test
// per rule of the contract in LiveView's header comment. The session is the
// real one; its commit is the real saveStreamingPatch, mocked at the API
// client so nothing reaches a network. The four inspector decks poll and
// fetch, so they are stood in for by marked elements; the masking pieces
// (bar, inspector, quick panel) are the real components.
//
// jsdom lays nothing out, so "where" is read off the DOM: the edit bar
// precedes the stage slot, the inspector column holds one thing or the other.

// The marked stand-ins. (vi.mock is hoisted above everything else, so the
// helper has to be hoisted with it.)
const { panel } = vi.hoisted(() => ({
  panel: (id: string) => () => {
    const el = document.createElement('div')
    el.setAttribute('data-testid', id)
    return el
  },
}))

vi.mock('./live/ChannelMatrix', () => ({ default: panel('panel-channels') }))
vi.mock('./live/SystemInfoPanel', () => ({ default: panel('panel-system') }))
// Storage and Clients keep their marked stand-in body, but render the real
// Panel faceplate: the hide-confirm tests need the frame's header actions
// (collapse/hide) that the context drives, and Panel is pure UI — no polls.
vi.mock('./live/StoragePanel', async () => {
  const Panel = (await import('../ui/Panel')).default
  return {
    default: () => (
      <Panel title="Storage">
        <div data-testid="panel-storage" />
      </Panel>
    ),
  }
})
vi.mock('./live/ClientsPanel', async () => {
  const Panel = (await import('../ui/Panel')).default
  return {
    default: () => (
      <Panel title="Connected Clients">
        <div data-testid="panel-clients" />
      </Panel>
    ),
  }
})
vi.mock('../../../api/client', () => ({ saveStreamingPatch: vi.fn() }))

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

const streamStatus = (on: boolean): DeviceStatus => ({ streaming: { webStreamingActive: on, isActive: on } }) as unknown as DeviceStatus

/** A hand-built state with what LiveView and the session read; every write to the player rung is recorded in order. */
function fixtureState(opts: { streamOn?: boolean; statusLoaded?: boolean; settingsLoaded?: boolean; mode?: PlayerMode; zones?: MaskingZone[] } = {}) {
  const doc = (zones: MaskingZone[]): AllSettings =>
    ({
      ...settingsFixture,
      // The master switch on: the editor's own warning row is the inspector
      // suite's subject, not these tests'.
      streaming: { ...settingsFixture.streaming, maskingZones: zones, maskingEnabled: true },
    }) as unknown as AllSettings
  const [settings, setSettings] = createSignal<AllSettings | null>(opts.settingsLoaded === false ? null : doc(opts.zones ?? []))
  const [status, setStatus] = createSignal<DeviceStatus | null>(opts.statusLoaded === false ? null : streamStatus(opts.streamOn ?? true))
  const [playerMode, writeMode] = createSignal<PlayerMode>(opts.mode ?? 'whep')
  const [showShortcuts, setShowShortcuts] = createSignal(false)
  const modeWrites: PlayerMode[] = []
  const setPlayerMode = (next: PlayerMode) => {
    modeWrites.push(next)
    writeMode(next)
  }
  const updateStreamingDebounced = vi.fn((_patch: Partial<AllSettings['streaming']>) => {})
  const state = {
    settings,
    status,
    playerMode,
    setPlayerMode,
    updateStreamingDebounced,
    updateStreamingAndSave: vi.fn(),
    isViewer: () => false,
    showShortcuts,
    setShowShortcuts,
  } as unknown as NextState
  return { state, setStatus, setSettings, updateStreamingDebounced, modeWrites, writeMode, playerMode, setShowShortcuts }
}

/** NextShell's own mount for the view, verbatim: LiveView inside a non-keyed Show keyed on the route. */
function Shell(props: { state: NextState }) {
  const router = hashRouter()
  const view = (): RouteName => {
    const name = router.route().name
    return isNextRoute(name) ? name : 'live'
  }
  return (
    <Show when={view() === 'live'} fallback={<div data-testid="elsewhere" />}>
      <LiveView state={props.state} />
    </Show>
  )
}

/** The view under a switch of its own: it goes away and comes back with the route untouched. */
function Toggleable(props: { state: NextState; mounted: () => boolean }) {
  return (
    <Show when={props.mounted()}>
      <LiveView state={props.state} />
    </Show>
  )
}

function mountLive(state: NextState) {
  const view = render(() => <Shell state={state} />)
  return { ...view, root: () => view.container.querySelector<HTMLElement>('.nx-view-flush') }
}

function mountToggleable(state: NextState) {
  const [mounted, setMounted] = createSignal(true)
  const view = render(() => <Toggleable state={state} mounted={mounted} />)
  return { ...view, setMounted }
}

// ── DOM places ──

const bar = () => document.querySelector<HTMLElement>('.nx-mask-bar')
const column = () => document.querySelector<HTMLElement>('.nx-inspector')
const fab = () => document.querySelector<HTMLElement>('.nx-inspector-fab')
const drawer = () => document.querySelector<HTMLElement>('.nx-drawer')
const stageSlot = () => document.querySelector<HTMLElement>('.nx-stage-slot')
const done = () => screen.queryByRole('button', { name: en['privacy.editor.done'] })

/** Is `after` placed after `before` in the document? */
const precedes = (before: Node, after: Node): boolean =>
  (before.compareDocumentPosition(after) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

beforeEach(() => {
  resetMaskEditStoreForTests()
  clearToasts()
  // Every test starts on the Live route with nothing armed.
  hashRouter().navigate({ name: 'live' })
  expect(maskEditActive()).toBe(false)
})

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
  clearToasts()
})

// ══ 1. Answered once ══════════════════════════════════════════════════

describe('LiveView — the masking edit is answered here, once', () => {
  it('an armed edit gets exactly one session, published, and the stage goes to MJPEG for it', () => {
    const { state, modeWrites } = fixtureState({ zones: [zone('a'), zone('b')], mode: 'whep' })
    mountLive(state)
    expect(maskEditSession()).toBeNull()
    expect(bar()).toBeNull()
    expect(modeWrites).toEqual([])

    startMaskEdit('b')
    const session = maskEditSession()
    expect(session).not.toBeNull()
    expect(maskEditActive()).toBe(true)
    // The entry point's zone is the one it opens on.
    expect(session!.selectedId()).toBe('b')
    expect(modeWrites).toEqual(['mjpeg'])
    // The editor's chrome is up for it.
    expect(bar()).not.toBeNull()
    expect(column()!.querySelector('.nx-mask-inspector')).not.toBeNull()
    expect(done()).not.toBeNull()
  })

  it('waits for settings hydration so the first new zone cannot replace persisted zones', () => {
    const { state, setSettings, modeWrites } = fixtureState({ settingsLoaded: false, zones: [zone('saved')] })
    mountLive(state)
    startMaskEdit()

    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual([])

    setSettings({
      ...settingsFixture,
      streaming: { ...settingsFixture.streaming, maskingZones: [zone('saved')], maskingEnabled: true },
    } as unknown as AllSettings)
    expect(maskEditSession()?.zones()).toEqual([zone('saved')])
    expect(modeWrites).toEqual(['mjpeg'])
  })

  it('nothing that changes under the edit makes a second session', () => {
    const { state, setStatus, setSettings, writeMode, modeWrites } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    const first = maskEditSession()
    const before = [...modeWrites]

    // The status tick, the 30 s settings poll, the ladder falling to another rung.
    setStatus(streamStatus(true))
    setStatus(streamStatus(true))
    setSettings({ ...settingsFixture, streaming: { ...settingsFixture.streaming, maskingZones: [] } } as unknown as AllSettings)
    writeMode('hls')
    // And the same request again.
    startMaskEdit('a')
    expect(maskEditSession()).toBe(first)
    expect(maskEditActive()).toBe(true)
    expect(modeWrites).toEqual(before)
  })

  it('a session already published is adopted, not replaced', () => {
    const { state, modeWrites } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    // What a previous view, or a test, leaves in the store: armed, with its editor.
    startMaskEdit()
    const existing = createMaskEditSession(state as unknown as MaskEditState, { focusZoneId: null, commit: async () => {} })
    setMaskEditSession(existing)
    const before = [...modeWrites]
    expect(before).toEqual(['mjpeg'])

    mountLive(state)
    expect(maskEditSession()).toBe(existing)
    expect(modeWrites).toEqual(before)
    expect(bar()).not.toBeNull()
    expect(column()!.querySelector('.nx-mask-inspector')).not.toBeNull()
  })
})

// ══ 2. Not without a stream ═══════════════════════════════════════════

describe('LiveView — nothing to draw on', () => {
  it('an armed edit is turned away while the web stream is off: no session, the edit ended, the reason shown', () => {
    const { state, setStatus, modeWrites } = fixtureState({ streamOn: false, mode: 'whep' })
    mountLive(state)
    startMaskEdit()

    expect(maskEditSession()).toBeNull()
    expect(maskEditActive()).toBe(false)
    // The feed was never switched — and so never needs putting back.
    expect(modeWrites).toEqual([])
    // One warning, saying why.
    expect(toastList().map((toast) => [toast.tone, toast.message])).toEqual([['warning', en['privacy.streamOffHint']]])
    // Nothing of the editor is on screen, and the normal Live view is whole.
    expect(bar()).toBeNull()
    expect(screen.getByTestId('panel-channels')).not.toBeNull()
    expect(fab()).not.toBeNull()
    expect(screen.getByTestId('panel-storage')).not.toBeNull()

    // Status ticks while the stream is still off must not report it again:
    // the request was answered once, and is over.
    setStatus(streamStatus(false))
    setStatus(streamStatus(false))
    expect(toastList()).toHaveLength(1)
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
  })

  it('a status that has not loaded counts as no stream', () => {
    const { state, modeWrites } = fixtureState({ statusLoaded: false })
    mountLive(state)
    startMaskEdit()
    expect(maskEditSession()).toBeNull()
    expect(maskEditActive()).toBe(false)
    expect(modeWrites).toEqual([])
    expect(toastList().map((toast) => toast.message)).toEqual([en['privacy.streamOffHint']])
  })

  it('the stream coming back does not resurrect the request: a new one is needed', () => {
    const { state, setStatus, modeWrites } = fixtureState({ streamOn: false, mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    expect(maskEditActive()).toBe(false)

    setStatus(streamStatus(true))
    expect(maskEditSession()).toBeNull()
    expect(maskEditActive()).toBe(false)
    expect(bar()).toBeNull()

    startMaskEdit()
    expect(maskEditSession()).not.toBeNull()
    expect(modeWrites).toEqual(['mjpeg'])
  })
})

// ══ 2b. The stream goes off mid-edit ══════════════════════════════════

describe('LiveView — the stream going off mid-edit ends the edit', () => {
  it('the W shortcut\'s stop mid-edit: the edit ends, the reason is shown, the feed restored', () => {
    const { state, setStatus, modeWrites } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()).not.toBeNull()

    setStatus(streamStatus(false))
    // The arm-time refusal's teardown, exactly: ended, released, said why.
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
    expect(toastList().map((toast) => [toast.tone, toast.message])).toEqual([['warning', en['privacy.streamOffHint']]])
    expect(bar()).toBeNull()
    // The normal view is whole again, and further off-ticks say nothing more.
    expect(screen.getByTestId('panel-channels')).not.toBeNull()
    setStatus(streamStatus(false))
    expect(toastList()).toHaveLength(1)
  })

  it('a status that unloads mid-edit counts as the stream going off', () => {
    const { state, setStatus, modeWrites } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()

    setStatus(null)
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
    expect(toastList().map((toast) => toast.message)).toEqual([en['privacy.streamOffHint']])
  })

  it('the stream staying on says nothing: on-ticks do not end the edit', () => {
    const { state, setStatus } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()

    setStatus(streamStatus(true))
    setStatus(streamStatus(true))
    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()).not.toBeNull()
    expect(toastList()).toHaveLength(0)
  })
})

// ══ 3. Ended ═══════════════════════════════════════════════════════════

describe('LiveView — the edit ends, and the store is emptied', () => {
  it('"Done": the store is emptied, and the remembered feed is put back exactly once', () => {
    const { state, modeWrites, playerMode } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    const session = maskEditSession()!
    expect(playerMode()).toBe('mjpeg')

    fireEvent.click(done()!)
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    // Once by finish; the release that follows it (dispose) adds nothing.
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
    expect(playerMode()).toBe('whep')

    // The normal Live view is back, whole.
    expect(bar()).toBeNull()
    expect(column()!.querySelector('.nx-mask-inspector')).toBeNull()
    expect(screen.getByTestId('panel-channels')).not.toBeNull()
    expect(fab()).not.toBeNull()
    // And the finished session is inert.
    session.toggleDrawing()
    expect(maskEditActive()).toBe(false)
  })

  it('a user already on MJPEG is left on it', () => {
    const { state, modeWrites } = fixtureState({ mode: 'mjpeg' })
    mountLive(state)
    startMaskEdit()
    fireEvent.click(done()!)
    expect(modeWrites).toEqual(['mjpeg'])
  })

  it('"Done" publishes the zones to the mirror when they differ from it', () => {
    const { state, updateStreamingDebounced } = fixtureState({ zones: [zone('a')], mode: 'mjpeg' })
    mountLive(state)
    startMaskEdit()
    maskEditSession()!.updateZone({ label: 'Front door' }, 'a')

    // Each command is already saved on its own; the mirror is "Done"'s.
    expect(updateStreamingDebounced).not.toHaveBeenCalled()
    fireEvent.click(done()!)
    expect(updateStreamingDebounced).toHaveBeenCalledTimes(1)
    const patch = updateStreamingDebounced.mock.calls[0][0] as Partial<AllSettings['streaming']>
    expect(patch.maskingZones!.map((item) => [item.id, item.label])).toEqual([['a', 'Front door']])
  })

  it('"Done" with nothing changed publishes nothing', () => {
    const { state, updateStreamingDebounced } = fixtureState({ zones: [zone('a')] })
    mountLive(state)
    startMaskEdit()
    fireEvent.click(done()!)
    expect(updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('finish() then dispose() restores the remembered feed exactly once', () => {
    const { state, modeWrites } = fixtureState({ mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    const session = maskEditSession()!

    session.finish()
    // The armed flag is what this view watches: it releases and empties.
    expect(maskEditSession()).toBeNull()
    session.dispose()
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
    expect(bar()).toBeNull()
  })

  it('whatever clears the armed flag empties the store', () => {
    mountLive(fixtureState().state)
    startMaskEdit()
    const session = maskEditSession()!
    expect(maskEditSession()).toBe(session)
    endMaskEdit()
    expect(maskEditSession()).toBeNull()
    expect(bar()).toBeNull()
  })

  it('dispose() alone never ends the edit — the view does not lean on it', () => {
    const { state, modeWrites } = fixtureState({ mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    const session = maskEditSession()!

    session.dispose()
    // Still armed, still published, its chrome still up: only the feed is back.
    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()).toBe(session)
    expect(bar()).not.toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])

    // The flag ends it — and the second release adds nothing.
    endMaskEdit()
    expect(maskEditSession()).toBeNull()
    expect(bar()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
  })
})

// ══ 4. Leaving ═════════════════════════════════════════════════════════

describe('LiveView — the view goes away, and so does the edit', () => {
  it('leaving Live finishes the edit: zones published, feed restored, nothing left armed for the mini-player', () => {
    const { state, modeWrites, updateStreamingDebounced } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    maskEditSession()!.updateZone({ label: 'Front door' }, 'a')
    expect(updateStreamingDebounced).not.toHaveBeenCalled()

    hashRouter().navigate({ name: 'media' })
    expect(screen.getByTestId('elsewhere')).not.toBeNull()
    expect(document.querySelector('.nx-view-flush')).toBeNull()
    // The mini-player's host reads these two: neither may still say "editing".
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])
    expect(updateStreamingDebounced).toHaveBeenCalledTimes(1)
    const patch = updateStreamingDebounced.mock.calls[0][0] as Partial<AllSettings['streaming']>
    expect(patch.maskingZones!.map((item) => [item.id, item.label])).toEqual([['a', 'Front door']])
  })

  it('leaving Live with nothing changed restores the feed and publishes nothing', () => {
    const { state, modeWrites, updateStreamingDebounced } = fixtureState({ mode: 'h264' })
    mountLive(state)
    startMaskEdit()
    hashRouter().navigate({ name: 'detection' })
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'h264'])
    expect(updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('back on Live, the view is the normal one: the edit did not follow', () => {
    const { state } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    mountLive(state)
    startMaskEdit()
    expect(bar()).not.toBeNull()

    hashRouter().navigate({ name: 'system' })
    hashRouter().navigate({ name: 'live' })
    expect(document.querySelector('.nx-view-flush')).not.toBeNull()
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(bar()).toBeNull()
    expect(screen.getByTestId('panel-channels')).not.toBeNull()
    expect(stageSlot()).not.toBeNull()
  })

  it('the view going away ends the edit even when the route stays on Live', () => {
    const { state, modeWrites } = fixtureState({ mode: 'whep' })
    const { setMounted } = mountToggleable(state)
    startMaskEdit()
    expect(hashRouter().route().name).toBe('live')

    setMounted(false)
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual(['mjpeg', 'whep'])

    // A view that comes back on the same route finds nothing armed.
    setMounted(true)
    expect(document.querySelector('.nx-view-flush')).not.toBeNull()
    expect(maskEditActive()).toBe(false)
    expect(bar()).toBeNull()
  })

  it('an edit armed as the view goes away is not left behind', () => {
    const { state, modeWrites } = fixtureState({ mode: 'whep' })
    const { setMounted } = mountToggleable(state)
    // One batch: the view leaves before it ever answered the arm.
    batch(() => {
      setMounted(false)
      startMaskEdit()
    })
    expect(maskEditActive()).toBe(false)
    expect(maskEditSession()).toBeNull()
    expect(modeWrites).toEqual([])
  })
})

// ══ The inspector is one thing or the other ═══════════════════════════

describe('LiveView — editing re-lays the view', () => {
  it('the edit bar sits above the stage slot, inside the stage', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    const { root } = mountLive(state)
    expect(root()!.classList.contains('nx-mask-editing')).toBe(false)

    startMaskEdit()
    expect(root()!.classList.contains('nx-mask-editing')).toBe(true)
    const stage = root()!.querySelector<HTMLElement>('.nx-stage')!
    expect(stage).not.toBeNull()
    expect(bar()!.parentElement).toBe(stage)
    expect(stageSlot()).not.toBeNull()
    expect(precedes(bar()!, stageSlot()!)).toBe(true)
    expect(stage.firstElementChild).toBe(bar())

    // The slot is still the stage's: the picture keeps its mount point.
    expect(stageSlot()!.parentElement).toBe(stage)
  })

  it('the inspector column holds the zones while editing, and the normal decks otherwise — never both', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    const { root } = mountLive(state)
    expect(within(column()!).getByTestId('panel-channels')).toBeTruthy()
    expect(within(column()!).getByTestId('panel-system')).toBeTruthy()
    expect(within(column()!).getByRole('button', { name: en['privacy.editZoneTitle'] })).toBeTruthy()

    startMaskEdit()
    expect(column()!.querySelector('.nx-mask-inspector')).not.toBeNull()
    expect(document.querySelector('[data-testid="panel-channels"]')).toBeNull()
    expect(document.querySelector('[data-testid="panel-storage"]')).toBeNull()
    expect(document.querySelector('[data-testid="panel-clients"]')).toBeNull()
    expect(document.querySelector('[data-testid="panel-system"]')).toBeNull()
    // The quick panel's own button went with them: it must not re-arm the edit.
    expect(screen.queryByRole('button', { name: en['privacy.editZoneTitle'] })).toBeNull()
    // In its place, the zones: the selected one's verb, and the bar's.
    expect(within(column()!).getByRole('button', { name: en['privacy.removeZone'] })).toBeTruthy()
    expect(within(bar()!).getByRole('button', { name: en['privacy.addZone'] })).toBeTruthy()

    fireEvent.click(done()!)
    expect(root()!.classList.contains('nx-mask-editing')).toBe(false)
    expect(column()!.querySelector('.nx-mask-inspector')).toBeNull()
    expect(within(column()!).getByTestId('panel-channels')).toBeTruthy()
  })

  it('the inspector FAB and the Drawer are not on offer while an edit runs, and come back after', () => {
    const { state } = fixtureState()
    mountLive(state)
    expect(fab()).not.toBeNull()
    expect(drawer()).toBeNull()

    // The tablet's way in: the Drawer carries the normal panels.
    fireEvent.click(fab()!)
    expect(drawer()).not.toBeNull()
    expect(within(drawer()!).getByTestId('panel-channels')).toBeTruthy()
    expect(within(drawer()!).getByRole('button', { name: en['privacy.editZoneTitle'] })).toBeTruthy()

    // "Edit zones" from inside the Drawer: the edit opens, and the Drawer —
    // with the normal panels in it — is gone, FAB and all.
    fireEvent.click(within(drawer()!).getByRole('button', { name: en['privacy.editZoneTitle'] }))
    expect(maskEditActive()).toBe(true)
    expect(bar()).not.toBeNull()
    expect(fab()).toBeNull()
    expect(drawer()).toBeNull()
    expect(document.querySelectorAll('[data-testid="panel-channels"]')).toHaveLength(0)

    // Done: the FAB is back, and the Drawer is not standing open again.
    fireEvent.click(done()!)
    expect(fab()).not.toBeNull()
    expect(drawer()).toBeNull()
    expect(screen.getAllByTestId('panel-channels')).toHaveLength(1)
  })

  it('"Edit zones" in the inspector column opens the editor over the stage', () => {
    const { state, modeWrites } = fixtureState({ zones: [zone('a')], mode: 'whep' })
    const { root } = mountLive(state)
    fireEvent.click(within(column()!).getByRole('button', { name: en['privacy.editZoneTitle'] }))
    expect(maskEditActive()).toBe(true)
    expect(maskEditSession()!.selectedId()).toBe('a')
    expect(modeWrites).toEqual(['mjpeg'])
    expect(root()!.classList.contains('nx-mask-editing')).toBe(true)
    expect(bar()).not.toBeNull()
  })
})

// ══ The player rung stays put ══════════════════════════════════════════

describe('LiveView — P does not cycle the player while an edit runs', () => {
  /** A real keydown on an element below document, the way a browser dispatches one. */
  function press(target: Element, key: string, init: KeyboardEventInit = {}): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
    target.dispatchEvent(event)
    return event
  }

  /** A bubble-phase listener on document — the phase and target the global shortcuts use. */
  function documentSpy() {
    const seen: string[] = []
    const listener = (e: KeyboardEvent) => seen.push(e.key)
    document.addEventListener('keydown', listener)
    return { seen, stop: () => document.removeEventListener('keydown', listener) }
  }

  const places = () => [bar()!, column()!, document.querySelector('.nx-stage')!, document.body]

  it('a bubbling P never reaches document while the edit runs, wherever it is pressed', () => {
    mountLive(fixtureState().state)
    const spy = documentSpy()
    try {
      // No edit: P reaches document, as ever.
      press(document.body, 'p')
      expect(spy.seen).toEqual(['p'])

      startMaskEdit()
      spy.seen.length = 0
      for (const place of places()) {
        for (const key of ['p', 'P']) {
          const event = press(place, key)
          expect(spy.seen, `${key} on ${place.className}`).toEqual([])
          // Stopped, not cancelled: the guard swallows nothing of the browser's.
          // (Whether it was stopped is read off the spy above — the DOM clears
          // cancelBubble once a dispatch finishes, so it says nothing here.)
          expect(event.defaultPrevented).toBe(false)
        }
      }
    } finally {
      spy.stop()
    }
  })

  it('every other key still reaches document — the editor\'s own keys included (Escape is the guard\'s, see below)', () => {
    mountLive(fixtureState().state)
    const spy = documentSpy()
    try {
      startMaskEdit()
      const keys = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Delete', 'Backspace', 'Enter', ' ', 'c', 'C', 's', 'w', 'r', 't', 'g', '?', '/', 'a', '0', 'Tab']
      for (const place of places()) {
        spy.seen.length = 0
        for (const key of keys) {
          const event = press(place, key)
          expect(spy.seen, `${key} on ${place.className}`).toContain(key)
          expect(event.defaultPrevented, key).toBe(false)
        }
      }
      // A modified P is the browser's (Ctrl+P prints): the guard leaves it alone too.
      for (const init of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }]) {
        spy.seen.length = 0
        const event = press(bar()!, 'p', init)
        expect(spy.seen).toEqual(['p'])
        expect(event.defaultPrevented).toBe(false)
      }
      // Shift is not a modifier the key map excludes — Shift+P is still the shortcut.
      spy.seen.length = 0
      press(bar()!, 'P', { shiftKey: true })
      expect(spy.seen).toEqual([])
    } finally {
      spy.stop()
    }
  })

  it('the real global shortcut does not run: the rung does not move until the edit is over', () => {
    const { state, modeWrites } = fixtureState({ mode: 'whep' })
    mountLive(state)
    // The app's own hook, with only the handler under test wired.
    const cycle = vi.fn()
    render(() => {
      useKeyboardShortcuts({ 'cycle-player': cycle })
      return null
    })

    press(document.querySelector('.nx-inspector')!, 'p')
    expect(cycle).toHaveBeenCalledTimes(1)

    startMaskEdit()
    expect(modeWrites).toEqual(['mjpeg'])
    for (const place of places()) press(place, 'p')
    expect(cycle).toHaveBeenCalledTimes(1)
    expect(modeWrites).toEqual(['mjpeg'])

    fireEvent.click(done()!)
    press(document.querySelector('.nx-inspector')!, 'p')
    expect(cycle).toHaveBeenCalledTimes(2)
  })

  it('the guard lives exactly as long as the edit: gone when it ends, back with a new edit, gone with the view', () => {
    const { state } = fixtureState()
    const { setMounted } = mountToggleable(state)
    const spy = documentSpy()
    /** What document saw for one P, and nothing older. */
    const onePress = (): string[] => {
      spy.seen.length = 0
      press(document.body, 'p')
      return [...spy.seen]
    }
    try {
      startMaskEdit()
      expect(onePress()).toEqual([])

      endMaskEdit()
      expect(onePress()).toEqual(['p'])

      // A fresh edit brings the guard back with it.
      startMaskEdit()
      expect(onePress()).toEqual([])

      // The view goes away mid-edit: it ends the edit and takes the guard with it.
      setMounted(false)
      expect(maskEditActive()).toBe(false)
      expect(onePress()).toEqual(['p'])
    } finally {
      spy.stop()
    }
  })

  it('an armed edit that was refused leaves the key alone', () => {
    mountLive(fixtureState({ streamOn: false }).state)
    const spy = documentSpy()
    try {
      startMaskEdit()
      press(document.body, 'p')
      expect(spy.seen).toEqual(['p'])
    } finally {
      spy.stop()
    }
  })
})

// ══ Escape is the editor's while it runs ══════════════════════════════
// useFullscreen (StreamPreview's own instance, reachable from no beta code)
// exits the CSS fullscreen overlay from a document-bubble Escape listener —
// the same key the picture's ladder steps the editor back with, so one
// press used to double-act. While a session is published, a capture-phase
// listener on window stops the key and runs the ladder itself through
// session commands; the document-bubble handlers — the overlay's, the
// shortcuts', Solid's delegated ones — never see it.

describe('LiveView — Escape is the editor\'s while it runs', () => {
  /** A real keydown on an element below document, the way a browser dispatches one. */
  function press(target: Element, key: string): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
    target.dispatchEvent(event)
    return event
  }

  /** A document-bubble listener — the phase and target the fullscreen hook and the global shortcuts listen on. */
  function bubbleSpy() {
    const seen: KeyboardEvent[] = []
    const listener = (e: KeyboardEvent) => seen.push(e)
    document.addEventListener('keydown', listener)
    return { seen, stop: () => document.removeEventListener('keydown', listener) }
  }

  it('while the edit runs, Escape steps the editor back and never reaches the overlay\'s document listener', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    mountLive(state)
    const spy = bubbleSpy()
    try {
      startMaskEdit()
      const session = maskEditSession()!
      expect(session.selectedId()).toBe('a')

      const event = press(document.body, 'Escape')
      // Step one: the selection is gone, the key taken — and stopped before
      // every document-bubble handler, the fullscreen overlay's included.
      expect(session.selectedId()).toBeNull()
      expect(maskEditActive()).toBe(true)
      expect(spy.seen).toEqual([])
      expect(event.defaultPrevented).toBe(true)

      press(document.body, 'Escape')
      // With nothing left to step back from, the editor is Done. The press
      // that finished it was still the editor's — the guard was registered
      // when it arrived.
      expect(maskEditActive()).toBe(false)
      expect(spy.seen).toEqual([])

      // And the next Escape is the overlay's again, as without an edit.
      press(document.body, 'Escape')
      expect(spy.seen.map((e) => e.key)).toEqual(['Escape'])
    } finally {
      spy.stop()
    }
  })

  it('the ladder is the session\'s commands in the picture\'s order: a gesture, then a draw armed', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    mountLive(state)
    const spy = bubbleSpy()
    try {
      startMaskEdit()
      const session = maskEditSession()!

      // A gesture in flight is the first thing the ladder cancels — the
      // draft goes back to how the gesture began, nothing saved.
      session.beginMove('a', { x: 0.3, y: 0.3 })
      press(document.body, 'Escape')
      expect(session.gesture()).toBeNull()
      expect(session.selectedId()).toBe('a')
      expect(session.zones()[0].x).toBe(0.25)
      expect(spy.seen).toEqual([])

      // Next: the draw armed by an empty list is disarmed, not the editor.
      session.removeZone('a')
      expect(session.drawing()).toBe(true)
      press(document.body, 'Escape')
      expect(session.drawing()).toBe(false)
      expect(maskEditActive()).toBe(true)
      expect(spy.seen).toEqual([])
    } finally {
      spy.stop()
    }
  })

  it('the shortcuts help keeps its Escape: the modal\'s key, the editor untouched behind it', () => {
    const { state, setShowShortcuts } = fixtureState({ zones: [zone('a')] })
    mountLive(state)
    const spy = bubbleSpy()
    try {
      startMaskEdit()
      const session = maskEditSession()!
      expect(session.selectedId()).toBe('a')

      setShowShortcuts(true)
      press(document.body, 'Escape')
      // The key passed through (the overlay's listener closes it), and the
      // editor behind it did not step.
      expect(spy.seen.map((e) => e.key)).toEqual(['Escape'])
      expect(session.selectedId()).toBe('a')
      expect(maskEditActive()).toBe(true)

      setShowShortcuts(false)
      press(document.body, 'Escape')
      expect(session.selectedId()).toBeNull()
      expect(spy.seen.map((e) => e.key)).toEqual(['Escape'])
    } finally {
      spy.stop()
    }
  })

  it('armed without a session — answered, or refused — leaves Escape the overlay\'s', () => {
    const { state } = fixtureState({ streamOn: false })
    mountLive(state)
    const spy = bubbleSpy()
    try {
      startMaskEdit()
      // Refused at the arm: no session ever published, so no guard.
      expect(maskEditActive()).toBe(false)
      press(document.body, 'Escape')
      expect(spy.seen.map((e) => e.key)).toEqual(['Escape'])

      // An arm the view has not answered yet (here: nothing to answer with —
      // the store is armed while the view is gone) registers nothing either.
      hashRouter().navigate({ name: 'media' })
      startMaskEdit()
      press(document.body, 'Escape')
      expect(spy.seen.map((e) => e.key)).toEqual(['Escape', 'Escape'])
    } finally {
      spy.stop()
    }
  })
})

// ══ The layout, as written ════════════════════════════════════════════
// jsdom lays nothing out, so the edit layout — which cannot be read off the
// DOM — is pinned where it is written: styles/next/views/masking.css. The
// rules are keyed on the view's .nx-mask-editing root, which is why they win
// over live.css and layout.css without either file changing.

describe('LiveView — the edit layout, as written in masking.css', () => {
  const read = (file: string) => readFileSync(resolve(process.cwd(), file), 'utf8')
  const masking = read('src/styles/next/views/masking.css')
  const chromeAt = masking.indexOf("THE EDITOR'S CHROME")

  interface CssRule {
    selector: string
    /** The innermost @media the rule sits in, or null at the top level (every width). */
    media: string | null
    body: string
  }

  /** Every rule of a stylesheet, with its comments stripped and its media context kept. */
  function rules(css: string): CssRule[] {
    const text = css.replace(/\/\*[\s\S]*?\*\//g, '')
    const found: CssRule[] = []
    const open: Array<{ kind: 'media' | 'rule'; prelude: string; start: number }> = []
    let last = 0
    for (let i = 0; i < text.length; i++) {
      if (text[i] === '{') {
        const prelude = text.slice(last, i).trim()
        open.push({ kind: prelude.startsWith('@media') ? 'media' : 'rule', prelude, start: i + 1 })
        last = i + 1
      } else if (text[i] === '}') {
        const frame = open.pop()
        if (frame?.kind === 'rule') {
          found.push({
            selector: frame.prelude,
            media: open.filter((item) => item.kind === 'media').map((item) => item.prelude).pop() ?? null,
            body: text.slice(frame.start, i),
          })
        }
        last = i + 1
      } else if (text[i] === ';') {
        last = i + 1
      }
    }
    return found
  }

  const NARROW = '@media (max-width: 1199px)'
  const PHONE = '@media (max-width: 767px)'
  const of = (...selectors: string[]) => rules(masking).filter((rule) => selectors.includes(rule.selector))

  it('every rule stays inside the beta shell: nothing can reach the classic UI', () => {
    const all = rules(masking)
    expect(all.length).toBeGreaterThan(60)
    expect(all.filter((rule) => !rule.selector.startsWith("[data-ui='next']"))).toEqual([])
    // And the shell's scope is the whole selector's own first compound: no
    // rule leads with a classic class and merely mentions the scope later.
    for (const rule of all) {
      expect(rule.selector.split(',').every((part) => part.trim().startsWith("[data-ui='next']")), rule.selector).toBe(true)
    }
  })

  it('every nx-mask-* class the chrome renders has a rule there', () => {
    const styled = new Set([...masking.matchAll(/\.(nx-mask-[a-z0-9-]+)/g)].map((match) => match[1]))
    const rendered = [
      'src/components/next/views/LiveView.tsx',
      'src/components/next/views/live/masking/MaskEditBar.tsx',
      'src/components/next/views/live/masking/MaskZoneInspector.tsx',
    ]
    let counted = 0
    for (const file of rendered) {
      for (const match of read(file).matchAll(/\bnx-mask-[a-z0-9-]+/g)) {
        counted++
        expect(styled.has(match[0]), `${file} names .${match[0]}, which has no rule in masking.css`).toBe(true)
      }
    }
    expect(counted).toBeGreaterThan(20)
  })

  it('the edit bar is pinned above the picture: it never shrinks, at every width', () => {
    const bars = of("[data-ui='next'] .nx-mask-bar")
    expect(bars).toHaveLength(2) // the rule, and the phone margins
    const [bar] = bars
    expect(bar.media).toBeNull()
    expect(bar.body).toMatch(/(^|\s)flex-shrink:\s*0;/)
    // No width's rule lets it shrink again.
    for (const rule of bars) expect(rule.body).not.toMatch(/flex-shrink:\s*[1-9]/)
  })

  // The rules that re-lay the view under the stage's row: they belong to the
  // sheet, so each must sit inside a below-1200px media query.
  const LAYOUT = /(^|\s)(flex-direction|overflow-y|min-height|width|border-left|border-top)\s*:/
  const SHEET_SELECTORS = ['.nx-stage-row', '.nx-stage', '.nx-inspector'].map((name) => `[data-ui='next'] .nx-mask-editing ${name}`)
  const sheetRules = () =>
    rules(masking).filter((rule) => SHEET_SELECTORS.includes(rule.selector) && LAYOUT.test(rule.body))

  it('below 1200px the inspector is a sheet under the stage, and the stage keeps a usable height', () => {
    const bodies = new Map(sheetRules().map((rule) => [rule.selector, rule.body]))
    expect(bodies.get(SHEET_SELECTORS[0])).toMatch(/flex-direction:\s*column;/)
    expect(bodies.get(SHEET_SELECTORS[0])).toMatch(/overflow-y:\s*auto;/)
    expect(bodies.get(SHEET_SELECTORS[1])).toMatch(/min-height:/)
    const inspector = bodies.get(SHEET_SELECTORS[2]) ?? ''
    expect(inspector).toMatch(/display:\s*flex;/)
    expect(inspector).toMatch(/width:\s*100%;/)
    expect(inspector).toMatch(/border-left:\s*none;/)
    expect(inspector).toMatch(/border-top:\s*1px solid var\(--color-rule\);/)
  })

  it('from 1200px none of that applies: every sheet rule sits inside a below-1200px media query', () => {
    const layoutRules = sheetRules()
    expect(layoutRules.length).toBeGreaterThanOrEqual(4) // the three, and the phone's stage floor
    for (const rule of layoutRules) {
      // The top level would apply at every width; the two narrow queries are the only places allowed.
      expect([NARROW, PHONE], `${rule.selector} sits in ${rule.media ?? 'the top level'}`).toContain(rule.media)
    }
  })

  it('on phones the keyboard line goes and the bar takes the mobile well\'s margins', () => {
    const [keys] = of("[data-ui='next'] .nx-mask-bar-keys")
    expect(keys.media).toBe(PHONE)
    expect(keys.body).toMatch(/display:\s*none;/)
    // The phone's own rule — the base rule sets a margin too, and is not the one asked for.
    const phoneBars = of("[data-ui='next'] .nx-mask-bar").filter((rule) => rule.media === PHONE)
    expect(phoneBars).toHaveLength(1)
    expect(phoneBars[0].body).toMatch(/(^|\s)margin:\s*var\(--space-sm\) var\(--space-sm\) 0;/)
    // The base rule keeps the well's wider margins everywhere else.
    expect(of("[data-ui='next'] .nx-mask-bar").filter((rule) => rule.media === null)[0].body).toMatch(/(^|\s)margin:\s*var\(--space-md\) var\(--space-md\) 0;/)

    // The stage's floor is the phone's own: a lower one than the sheet's,
    // and only at phone widths.
    const floors = of("[data-ui='next'] .nx-mask-editing .nx-stage").filter((rule) => /min-height/.test(rule.body))
    expect(floors.map((rule) => rule.media)).toEqual([NARROW, PHONE])
    expect(floors[1].body).toMatch(/min-height:\s*min\(48vh, 400px\);/)
    expect(floors[0].body).toMatch(/min-height:\s*min\(56vh, 520px\);/)
  })

  it('light theme needs no rule of its own: the chrome paints in tokens, and never branches on the theme', () => {
    expect(chromeAt).toBeGreaterThan(0)
    const chrome = masking.slice(chromeAt)
    expect(chrome).not.toMatch(/#[0-9a-fA-F]{3,8}\b|\brgba?\(|\boklch\(|\bhsl\(/)
    expect(chrome).not.toContain('data-theme')
    // Every colour it names is a console token — or no colour at all.
    const colours = [...chrome.matchAll(/(?:background|color|border(?:-color)?|outline)\s*:\s*([^;]+);/g)].map((match) => match[1])
    expect(colours.length).toBeGreaterThan(20)
    for (const value of colours) {
      expect(/var\(--color-|transparent|\bnone\b/.test(value), `${value} is not a colour token`).toBe(true)
    }
  })
})

function configuredLayout(initial = defaultInspectorLayout()) {
  const [layout, setLayout] = createSignal<WebUiLayoutDto>(initial)
  const controller: InspectorLayoutController = {
    layout,
    ready: () => true,
    saving: () => false,
    localOnly: () => false,
    error: () => '',
    mutate: (transform) => setLayout((current) => transform(current)),
    replaceLayout: (raw) => setLayout(raw as WebUiLayoutDto),
    reset: async () => { setLayout(defaultInspectorLayout()) },
    refresh: async () => {},
    flush: async () => {},
  }
  return { layout, controller }
}

function mountConfigured(state: NextState, controller: InspectorLayoutController) {
  return render(() => <LiveView state={state} inspectorLayout={controller} />)
}

describe('LiveView — configurable inspector', () => {
  it('keeps the exact default five panels in their original order', () => {
    const { state } = fixtureState()
    const { controller } = configuredLayout()
    mountConfigured(state, controller)
    expect(Array.from(column()!.querySelectorAll('.nx-inspector-frame'), (frame) => frame.getAttribute('data-section-id')))
      .toEqual(['channels', 'storage', 'clients', 'masking-quick', 'system-info'])
  })

  it('renders custom placements as pinned controls even when an imported id collides with a built-in', () => {
    const { state } = fixtureState()
    const initial = defaultInspectorLayout()
    initial.sections = [{
      id: 'custom-imported',
      kind: 'custom',
      sectionId: 'channels',
      visible: true,
      collapsed: false,
      hiddenItems: [],
      customTitle: 'Imported rig',
      items: ['channels.status'],
    }]
    const { controller } = configuredLayout(initial)
    mountConfigured(state, controller)
    expect(screen.getByText('Imported rig')).toBeTruthy()
    expect(screen.queryByTestId('panel-channels')).toBeNull()
    expect(column()!.querySelector('.nx-channel')).not.toBeNull()
  })

  it('renders operator order but strips admin panels and disables layout controls for viewers', () => {
    const base = fixtureState()
    const [isViewer] = createSignal(true)
    const state = { ...base.state, isViewer } as unknown as NextState
    const initial = defaultInspectorLayout()
    initial.sections = [
      initial.sections[4],
      { ...initial.sections[0], id: 'plc_channels', sectionId: 'channels' },
      { ...initial.sections[0], id: 'plc_auth', sectionId: 'auth' },
    ]
    const { controller } = configuredLayout(initial)
    mountConfigured(state, controller)
    expect(Array.from(column()!.querySelectorAll('.nx-inspector-frame'), (frame) => frame.getAttribute('data-section-id')))
      .toEqual(['system-info', 'channels'])
    expect(screen.queryByRole('button', { name: en['next.inspector.customize'] })).toBeNull()
    const separator = screen.getByRole('separator', { name: en['next.inspector.resize'] })
    expect(separator.getAttribute('aria-disabled')).toBe('true')
    expect(separator.getAttribute('tabindex')).toBe('-1')
  })

  it('resizes by keyboard and pointer, commits once, and clamps to the stage-safe maximum', () => {
    const { state } = fixtureState()
    const { layout, controller } = configuredLayout()
    mountConfigured(state, controller)
    const separator = screen.getByRole('separator', { name: en['next.inspector.resize'] })
    fireEvent.keyDown(separator, { key: 'ArrowRight' })
    expect(layout().column.widthPx).toBe(340)
    fireEvent.keyDown(separator, { key: 'Home' })
    expect(layout().column.widthPx).toBe(260)
    fireEvent.keyDown(separator, { key: 'End' })
    expect(layout().column.widthPx).toBe(Math.min(560, window.innerWidth - 680))
    fireEvent.keyDown(separator, { key: 'Home' })
    for (let step = 0; step < 3; step += 1) fireEvent.keyDown(separator, { key: 'ArrowRight' })
    expect(layout().column.widthPx).toBe(320)

    fireEvent.pointerDown(separator, { clientX: 500, pointerId: 1 })
    fireEvent.pointerMove(separator, { clientX: 440, pointerId: 1 })
    expect(layout().column.widthPx).toBe(320)
    fireEvent.pointerUp(separator, { clientX: 440, pointerId: 1 })
    expect(layout().column.widthPx).toBe(Math.min(560, window.innerWidth - 680))
  })

  it('temporarily expands a collapsed inspector for masking and restores the saved collapse', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    const initial = defaultInspectorLayout()
    initial.column.collapsed = true
    const { layout, controller } = configuredLayout(initial)
    mountConfigured(state, controller)
    expect(column()!.classList.contains('nx-inspector-collapsed')).toBe(true)
    startMaskEdit()
    expect(column()!.classList.contains('nx-inspector-collapsed')).toBe(false)
    expect(layout().column.collapsed).toBe(true)
    fireEvent.click(done()!)
    expect(column()!.classList.contains('nx-inspector-collapsed')).toBe(true)
    expect(layout().column.collapsed).toBe(true)
  })

  it('closes the configurator when masking starts and does not reopen it afterward', () => {
    const { state } = fixtureState({ zones: [zone('a')] })
    const { controller } = configuredLayout()
    mountConfigured(state, controller)
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.customize'] }))
    expect(screen.getByRole('dialog', { name: en['next.inspector.configurator.title'] })).toBeTruthy()
    startMaskEdit()
    expect(screen.queryByRole('dialog', { name: en['next.inspector.configurator.title'] })).toBeNull()
    fireEvent.click(done()!)
    expect(screen.queryByRole('dialog', { name: en['next.inspector.configurator.title'] })).toBeNull()
  })

  it('mounts one inspector in the tablet drawer and replaces it with the configurator', () => {
    const { state } = fixtureState()
    const { controller } = configuredLayout()
    mountConfigured(state, controller)
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector'] }))
    const drawer = screen.getByRole('dialog', { name: en['view.live'] })
    expect(document.querySelectorAll('.nx-inspector-frame')).toHaveLength(5)
    fireEvent.click(within(drawer).getByRole('button', { name: en['next.inspector.customize'] }))
    expect(screen.queryByRole('dialog', { name: en['view.live'] })).toBeNull()
    expect(document.querySelectorAll('.nx-drawer')).toHaveLength(1)
    expect(screen.getByRole('dialog', { name: en['next.inspector.configurator.title'] })).toBeTruthy()
  })

  it('offers a configure action when every registered panel item is hidden', () => {
    const { state } = fixtureState()
    const initial = defaultInspectorLayout()
    initial.sections.push({
      id: 'custom-empty',
      kind: 'custom',
      sectionId: 'custom-empty',
      visible: true,
      collapsed: false,
      hiddenItems: [],
      customTitle: 'Empty section',
      items: [],
    })
    const { controller } = configuredLayout(initial)
    mountConfigured(state, controller)
    expect(screen.getByText(en['next.inspector.allHidden'])).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.configureItems'] }))
    expect(screen.getByRole('dialog', { name: en['next.inspector.configurator.title'] })).toBeTruthy()
  })

  it('keeps the head one compact row while a save runs: nothing renders to shift it', () => {
    const { state } = fixtureState()
    const { controller } = configuredLayout()
    mountConfigured(state, { ...controller, saving: () => true })
    const head = document.querySelector('.nx-inspector-head')
    expect(head).not.toBeNull()
    expect(head!.querySelector('[role="status"]')).toBeNull()
    // The head is the select and the two icon buttons — one row, no wrap.
    expect(head!.querySelectorAll('button')).toHaveLength(2)
    expect(head!.querySelector('select')).not.toBeNull()
  })

  it('the column scrolls vertically and never horizontally, as written in layout.css', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/next/layout.css'), 'utf8')
    const inner = css.match(/\[data-ui='next'\] \.nx-inspector-inner \{[^}]*\}/)?.[0] ?? ''
    expect(inner).toMatch(/overflow-x:\s*hidden;/)
    expect(inner).toMatch(/overflow-y:\s*auto;/)
    // The shiftable saving indicator is gone for good, everywhere.
    expect(css).not.toContain('nx-inspector-saving')
  })
})

describe('LiveView — hiding a section asks first', () => {
  const HIDE_CONFIRM_KEY = 'lenscast.nextInspectorHideConfirm'
  /** The registry's en titles for the sections these tests hide. */
  const SECTION_TITLES: Record<string, string> = { storage: 'Storage', clients: 'Connected Clients' }
  const hideButtonIn = (frame: HTMLElement): HTMLElement =>
    within(frame).getByRole('button', {
      name: en['next.inspector.section.hide'].replace('{title}', SECTION_TITLES[frame.getAttribute('data-section-id') ?? '']),
    })
  const storageFrame = () => column()!.querySelector<HTMLElement>('[data-section-id="storage"]')!
  const clientsFrame = () => column()!.querySelector<HTMLElement>('[data-section-id="clients"]')!

  beforeEach(() => {
    localStorage.removeItem(HIDE_CONFIRM_KEY)
  })

  it('the minus button opens a confirm, and only confirming hides the section', () => {
    const { state } = fixtureState()
    const { layout, controller } = configuredLayout()
    mountConfigured(state, controller)
    fireEvent.click(hideButtonIn(storageFrame()))
    const dialog = screen.getByRole('alertdialog', {
      name: en['next.inspector.section.hideConfirmTitle'].replace('{title}', 'Storage'),
    })
    expect(storageFrame()).not.toBeNull()
    expect(layout().sections.find((section) => section.sectionId === 'storage')!.visible).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', {
      name: en['next.inspector.section.hide'].replace('{title}', 'Storage'),
    }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(layout().sections.find((section) => section.sectionId === 'storage')!.visible).toBe(false)
    expect(storageFrame()).toBeNull()
  })

  it('cancel answers the dialog and keeps the section', () => {
    const { state } = fixtureState()
    const { layout, controller } = configuredLayout()
    mountConfigured(state, controller)
    fireEvent.click(hideButtonIn(storageFrame()))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: en['common.cancel'] }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(layout().sections.find((section) => section.sectionId === 'storage')!.visible).toBe(true)
    expect(storageFrame()).not.toBeNull()
  })

  it('remember stores the skip in this browser, and the next hide needs no dialog', () => {
    const { state } = fixtureState()
    const { controller } = configuredLayout()
    mountConfigured(state, controller)
    fireEvent.click(hideButtonIn(storageFrame()))
    const dialog = screen.getByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('checkbox', { name: en['next.inspector.section.hideConfirmRemember'] }))
    fireEvent.click(within(dialog).getByRole('button', {
      name: en['next.inspector.section.hide'].replace('{title}', 'Storage'),
    }))
    expect(localStorage.getItem(HIDE_CONFIRM_KEY)).toBe('skip')
    expect(storageFrame()).toBeNull()

    // Same mount, second section: the answer is remembered, the dialog never
    // comes up, and the skip stays a per-browser flag, not layout data.
    fireEvent.click(hideButtonIn(clientsFrame()))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(clientsFrame()).toBeNull()
    expect(controller.layout().sections.find((section) => section.sectionId === 'clients')!.visible).toBe(false)
  })
})
