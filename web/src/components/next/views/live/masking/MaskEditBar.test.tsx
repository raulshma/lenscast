// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { en } from '../../../../../i18n/en'
import { maskEditActive, resetMaskEditStoreForTests, startMaskEdit } from '../../../../../lib/maskEditStore'
import type { AllSettings, MaskingZone } from '../../../../../types'
import type { PlayerMode } from '../../../../../video/playerLadder'
import { createMaskEditSession, type MaskEditState } from './maskEditSession'
import MaskEditBar from './MaskEditBar'

// The editor's command bar over a real session: the hint is the session's
// state in words, and the two verbs are session commands — so the bar is
// tested by what it says and what the session does, never by a spy standing
// in for it. The commit is injected; no save ever reaches a network.

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
})

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

/** A hand-built state with the four members the session reads. */
function fakeState(zones: MaskingZone[]) {
  const doc = (): AllSettings =>
    ({ ...settingsFixture, streaming: { ...settingsFixture.streaming, maskingZones: zones } }) as unknown as AllSettings
  const [settings] = createSignal<AllSettings | null>(doc())
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>('whep')
  const updateStreamingDebounced = vi.fn(() => {})
  const state: MaskEditState = { settings, playerMode, setPlayerMode, updateStreamingDebounced }
  return { state, updateStreamingDebounced }
}

function mountBar(zones: MaskingZone[] = []) {
  const save = { commit: vi.fn(async () => {}), onError: vi.fn() }
  const fake = fakeState(zones)
  const session = createMaskEditSession(fake.state, { focusZoneId: null, commit: save.commit, onError: save.onError })
  render(() => <MaskEditBar session={session} />)
  const addButton = () => screen.getByRole('button', { name: en['privacy.addZone'] }) as HTMLButtonElement
  const doneButton = () => screen.getByRole('button', { name: en['privacy.editor.done'] }) as HTMLButtonElement
  const hint = () => document.querySelector('.nx-mask-bar-hint')?.textContent ?? null
  const keys = () => document.querySelector('.nx-mask-bar-keys')?.textContent ?? null
  return { session, ...fake, addButton, doneButton, hint, keys }
}

describe('MaskEditBar', () => {
  it('is a named region: the editor\'s title over one politely announced hint line', () => {
    mountBar([])
    const bar = document.querySelector('.nx-mask-bar')!
    expect(bar.getAttribute('role')).toBe('region')
    expect(bar.getAttribute('aria-label')).toBe(en['privacy.editor.title'])
    expect(document.querySelector('.nx-mask-bar-title')?.textContent).toBe(en['privacy.editor.title'])
    expect(document.querySelector('.nx-mask-bar-hint')?.getAttribute('aria-live')).toBe('polite')
  })

  it('with no zones it says to draw, "Add zone" is pressed, and a click disarms the draw', () => {
    const { session, addButton, hint, keys } = mountBar([])
    expect(hint()).toBe(en['privacy.editor.drawHint'])
    expect(keys()).toBeNull()
    expect(session.drawing()).toBe(true)
    expect(addButton().getAttribute('aria-pressed')).toBe('true')
    expect(addButton().classList.contains('nx-btn-toggled')).toBe(true)

    fireEvent.click(addButton())
    expect(session.drawing()).toBe(false)
    expect(hint()).toBe(en['privacy.editor.drawHint']) // still nothing to select
    expect(addButton().getAttribute('aria-pressed')).toBe('false')
    expect(addButton().classList.contains('nx-btn-toggled')).toBe(false)
    expect(addButton().classList.contains('nx-btn-secondary')).toBe(true)
  })

  it('a selected zone explains moving, with the keyboard line under it', () => {
    const { session, hint, keys } = mountBar([zone('a')])
    expect(session.selectedId()).toBe('a')
    expect(hint()).toBe(en['privacy.editor.hint'])
    expect(keys()).toBe(en['privacy.editor.keyHint'])
  })

  it('zones but no selection says to select one, and leaves the keyboard line out', () => {
    const { session, hint, keys, addButton } = mountBar([zone('a'), zone('b')])
    session.select(null)
    expect(hint()).toBe(en['privacy.editor.selectHint'])
    expect(keys()).toBeNull()
    expect(addButton().getAttribute('aria-pressed')).toBe('false')
  })

  it('an armed draw outranks a selection: the crosshair is the state to explain', () => {
    const { session, hint, keys, addButton } = mountBar([zone('a')])
    session.toggleDrawing()
    expect(session.selectedId()).toBe('a')
    expect(session.drawing()).toBe(true)
    expect(hint()).toBe(en['privacy.editor.drawHint'])
    expect(keys()).toBeNull()
    expect(addButton().getAttribute('aria-pressed')).toBe('true')
  })

  it('"Done" finishes the edit: the session ends it, and the store is no longer armed', () => {
    const { session, doneButton } = mountBar([zone('a')])
    startMaskEdit()
    expect(maskEditActive()).toBe(true)
    fireEvent.click(doneButton())
    expect(maskEditActive()).toBe(false)
    // Finished: the same session refuses every further command.
    session.toggleDrawing()
    expect(session.drawing()).toBe(false)
  })

  it('the verbs are the bar\'s only buttons, and the primary one is "Done"', () => {
    const { doneButton, addButton } = mountBar([])
    expect(screen.getAllByRole('button')).toHaveLength(2)
    expect(doneButton().classList.contains('nx-btn-primary')).toBe(true)
    expect(doneButton().disabled).toBe(false)
    expect(addButton().classList.contains('nx-btn-sm')).toBe(true)
    expect(doneButton().classList.contains('nx-btn-sm')).toBe(true)
  })
})
