// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { en } from '../../../../../i18n/en'
import { resetMaskEditStoreForTests } from '../../../../../lib/maskEditStore'
import type { AllSettings, MaskingZone } from '../../../../../types'
import type { PlayerMode } from '../../../../../video/playerLadder'
import { createMaskEditSession, type MaskEditState } from './maskEditSession'
import MaskZoneInspector, { FIELD_COMMIT_DELAY_MS } from './MaskZoneInspector'

// The editor's zone inspector over a real session, in jsdom: the list, the
// selected zone's settings, and — the reason this file is long — how many
// saves each kind of edit makes. `updateZone` is one PUT per call, so the
// commit spy's call count is the thing under test as much as the DOM is.
// The clock is faked (timeouts only: Solid's effects are synchronous and
// must keep running), so the debounce is read off the dial, not waited on.

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
})

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
  vi.useRealTimers()
})

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

/** The commit spy's call count: one call is one PUT on the wire. */
type Commit = ReturnType<typeof vi.fn<(zones: MaskingZone[]) => Promise<void>>>

function mountInspector(
  zones: MaskingZone[],
  opts: { focus?: string | null; maskingEnabled?: boolean } = {},
) {
  const commit = vi.fn((_zones: MaskingZone[]): Promise<void> => Promise.resolve())
  const onError = vi.fn()
  const doc = (): AllSettings =>
    ({
      ...settingsFixture,
      streaming: { ...settingsFixture.streaming, maskingZones: zones, maskingEnabled: opts.maskingEnabled ?? true },
    }) as unknown as AllSettings
  const [settings, setSettings] = createSignal<AllSettings | null>(doc())
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>('mjpeg')
  // The debounced mirror write as the app's really lands: the patch reaches
  // the mirror, so whatever reads it live (the master-switch warning) follows.
  const updateStreamingDebounced = vi.fn((patch: Partial<AllSettings['streaming']>) => {
    setSettings((prev) =>
      ({ ...prev!, streaming: { ...prev!.streaming, ...patch } }) as AllSettings,
    )
  })
  const state: MaskEditState = { settings, playerMode, setPlayerMode, updateStreamingDebounced }
  const session = createMaskEditSession(state, { focusZoneId: opts.focus === undefined ? null : opts.focus, commit, onError })
  render(() => <MaskZoneInspector session={session} />)
  return { session, commit, onError, updateStreamingDebounced }
}

/** The zone as the last save sent it — or null if nothing was saved. */
function savedZone(commit: Commit, id: string): MaskingZone | null {
  const last: MaskingZone[] | undefined = commit.mock.lastCall?.[0]
  return last?.find((item) => item.id === id) ?? null
}

const zoneName = (n: number) => en['privacy.zone'].replace('{n}', String(n))
/** The inspector's panel titles in order: the list's, then the selected zone's (when there is one). */
const titles = () => [...document.querySelectorAll('.nx-mask-inspector .nx-panel-title')].map((el) => el.textContent)
const row = (id: string) => document.querySelector<HTMLElement>(`.nx-mask-zone-row[data-zone-id="${id}"]`)
const pick = (id: string) => within(row(id)!).getByRole('button')
const rowSwitch = (id: string) => within(row(id)!).getByRole('checkbox') as HTMLInputElement
const labelInput = () => screen.getByLabelText(en['privacy.label']) as HTMLInputElement
const enabledSwitch = () => screen.getByLabelText(en['privacy.enabled']) as HTMLInputElement
const typeSelect = () => screen.getByLabelText(en['privacy.maskType']) as HTMLSelectElement
const percentInput = (key: 'x' | 'y' | 'width' | 'height') =>
  screen.getByLabelText(en[`privacy.${key}`]) as HTMLInputElement
const readout = (label: string) =>
  [...document.querySelectorAll('.nx-slider-field')].find((field) => field.querySelector('.nx-field-label')?.textContent === label)
    ?.querySelector('.nx-slider-field-value')?.textContent ?? null

/** After the hand has gone quiet for longer than the commit delay. */
const settle = () => vi.advanceTimersByTime(FIELD_COMMIT_DELAY_MS + 1)

/**
 * After the commit queue has drained. Only one save is ever on the wire: a
 * second made before the first settles is held and sent a microtask later,
 * so a test that makes two must let that microtask run before it counts.
 */
async function drained(): Promise<void> {
  for (let tick = 0; tick < 5; tick++) await Promise.resolve()
}

describe('MaskZoneInspector — the list', () => {
  it('no zones: the empty state says to draw — the editor is already open here — and nothing to select or remove', () => {
    mountInspector([])
    // The "Click Edit zones" wording is the System panel's: the inspector's
    // empty state is shown with the editor open, so it says how to draw.
    expect(screen.getByText(en['privacy.editor.drawHint'])).toBeTruthy()
    expect(screen.queryByText(en['privacy.editor.empty'])).toBeNull()
    expect(document.querySelector('.nx-mask-zone-row')).toBeNull()
    expect(screen.queryByText(en['privacy.editor.selectHint'])).toBeNull()
    expect(screen.queryByRole('button', { name: en['privacy.removeZone'] })).toBeNull()
    expect(screen.queryByLabelText(en['privacy.label'])).toBeNull()
  })

  it('zones but no selection: one row each — name, type, on/off — and the hint to pick one', () => {
    // The session opens on the first zone; "nothing picked" is asked for.
    const { session } = mountInspector([zone('a', { label: 'Front door' }), zone('b', { type: 'PIXELATE', enabled: false })])
    session.select(null)
    expect(session.selectedId()).toBeNull()
    expect(document.querySelectorAll('.nx-mask-zone-row')).toHaveLength(2)
    expect(screen.getByText('Front door')).toBeTruthy()
    expect(screen.getByText(zoneName(2))).toBeTruthy()
    expect(screen.getByText(en['option.mask.blackout'])).toBeTruthy()
    expect(screen.getByText(en['option.mask.pixelate'])).toBeTruthy()
    // A disabled zone is still a row, marked as off and switched off.
    expect(row('b')!.classList.contains('nx-mask-zone-row-disabled')).toBe(true)
    expect(rowSwitch('b').checked).toBe(false)
    expect(rowSwitch('a').checked).toBe(true)
    // No row is chosen yet.
    for (const el of document.querySelectorAll('.nx-mask-zone-pick')) expect(el.getAttribute('aria-pressed')).toBe('false')
    expect(screen.getByText(en['privacy.editor.selectHint'])).toBeTruthy()
    expect(screen.queryByLabelText(en['privacy.label'])).toBeNull()
  })

  it('the list title counts the zones, and keeps counting', () => {
    const { session } = mountInspector([zone('a'), zone('b')])
    const count = (n: number) => en['privacy.zones'].replace('{count}', String(n))
    // With a zone selected there are two panels: the list, and that zone.
    expect(titles()).toEqual([count(2), zoneName(1)])
    session.select(null)
    expect(titles()).toEqual([count(2)])
    session.removeZone('b')
    expect(titles()).toEqual([count(1)])
  })

  it('clicking a row selects it, highlights it, and opens its settings', () => {
    const { session } = mountInspector([zone('a'), zone('b', { type: 'PIXELATE' })])
    session.select(null)
    expect(screen.queryByLabelText(en['privacy.label'])).toBeNull()
    fireEvent.click(pick('b'))
    expect(session.selectedId()).toBe('b')
    expect(row('b')!.classList.contains('nx-mask-zone-row-selected')).toBe(true)
    expect(pick('b').getAttribute('aria-pressed')).toBe('true')
    expect(row('a')!.classList.contains('nx-mask-zone-row-selected')).toBe(false)
    expect(pick('a').getAttribute('aria-pressed')).toBe('false')
    expect(labelInput()).toBeTruthy()
    expect(typeSelect().value).toBe('PIXELATE')

    // The row is the target, not only its name: a click on the row itself —
    // the strip its highlight covers, beside the switch — selects as well.
    session.select('a')
    expect(session.selectedId()).toBe('a')
    fireEvent.click(row('b')!)
    expect(session.selectedId()).toBe('b')
  })

  it('the row\'s switch turns that zone off without moving the selection', () => {
    const { session, commit } = mountInspector([zone('a'), zone('b')], { focus: 'a' })
    expect(session.selectedId()).toBe('a')
    fireEvent.click(rowSwitch('b'))
    // The selection stayed where it was: the details still belong to a.
    expect(session.selectedId()).toBe('a')
    expect(labelInput()).toBeTruthy()
    expect(session.selectedZone()!.id).toBe('a')
    // b alone changed, in one save.
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'b')!.enabled).toBe(false)
    expect(savedZone(commit, 'a')).toEqual(zone('a'))
    expect(row('b')!.classList.contains('nx-mask-zone-row-disabled')).toBe(true)
    expect(rowSwitch('b').checked).toBe(false)
  })

  it('turning a row back on is its own save, and still does not move the selection', async () => {
    const { session, commit } = mountInspector([zone('a'), zone('b', { enabled: false })], { focus: 'a' })
    expect(rowSwitch('b').checked).toBe(false)
    fireEvent.click(rowSwitch('b'))
    await drained()
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'b')!.enabled).toBe(true)
    expect(rowSwitch('b').checked).toBe(true)
    expect(row('b')!.classList.contains('nx-mask-zone-row-disabled')).toBe(false)
    expect(session.selectedId()).toBe('a')
  })

  it('each row\'s switch is named for its zone', () => {
    mountInspector([zone('a', { label: 'Front door' }), zone('b')])
    expect(rowSwitch('a').getAttribute('aria-label')).toBe(`Front door · ${en['privacy.enabled']}`)
    expect(rowSwitch('b').getAttribute('aria-label')).toBe(`Zone 2 · ${en['privacy.enabled']}`)
  })
})

describe('MaskZoneInspector — the selected zone', () => {
  it('a labelled zone is named by its label, in the list and as the details title', () => {
    const { session } = mountInspector([zone('a', { label: 'Front door' })])
    expect(session.selectedId()).toBe('a')
    expect(titles()).toEqual([en['privacy.zones'].replace('{count}', '1'), 'Front door'])
    // Once in the list, once as the selected zone's title.
    expect(screen.getAllByText('Front door').length).toBe(2)
  })

  it('shows the zone as it is: label, switch, type', () => {
    mountInspector([zone('a', { label: 'Door', enabled: false, type: 'BLUR' })])
    expect(labelInput().value).toBe('Door')
    expect(enabledSwitch().checked).toBe(false)
    expect(typeSelect().value).toBe('BLUR')
    expect([...typeSelect().options].map((option) => [option.value, option.textContent])).toEqual([
      ['BLACKOUT', en['option.mask.blackout']],
      ['PIXELATE', en['option.mask.pixelate']],
      ['BLUR', en['option.mask.blur']],
    ])
    expect(labelInput().placeholder).toBe(en['privacy.labelPlaceholder'])
  })

  it('the enabled switch and the type menu each commit once, on the change', async () => {
    const { session, commit } = mountInspector([zone('a')])
    fireEvent.click(enabledSwitch())
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')!.enabled).toBe(false)
    expect(session.selectedZone()!.enabled).toBe(false)

    // The first save is still on the wire (its promise has not settled), so
    // the second waits its turn rather than racing it — then lands.
    fireEvent.change(typeSelect(), { target: { value: 'PIXELATE' } })
    expect(session.selectedZone()!.type).toBe('PIXELATE')
    await drained()
    expect(commit).toHaveBeenCalledTimes(2)
    expect(savedZone(commit, 'a')!.type).toBe('PIXELATE')
    expect(savedZone(commit, 'a')!.enabled).toBe(false)
  })

  it('the parameter belongs to the type: pixel size for pixelate, blur radius for blur, neither for blackout', () => {
    mountInspector([zone('a', { type: 'PIXELATE', pixelateSize: 24 })])
    expect(screen.getByLabelText(en['privacy.pixelSize'])).toBeTruthy()
    expect(screen.queryByLabelText(en['privacy.blurRadius'])).toBeNull()
    const pixel = screen.getByLabelText(en['privacy.pixelSize']) as HTMLInputElement
    expect(pixel.min).toBe('4')
    expect(pixel.max).toBe('64')
    expect(pixel.value).toBe('24')

    fireEvent.change(typeSelect(), { target: { value: 'BLUR' } })
    expect(screen.queryByLabelText(en['privacy.pixelSize'])).toBeNull()
    const blur = screen.getByLabelText(en['privacy.blurRadius']) as HTMLInputElement
    expect(blur.min).toBe('1')
    expect(blur.max).toBe('50')
    expect(blur.value).toBe('10')

    fireEvent.change(typeSelect(), { target: { value: 'BLACKOUT' } })
    expect(screen.queryByLabelText(en['privacy.pixelSize'])).toBeNull()
    expect(screen.queryByLabelText(en['privacy.blurRadius'])).toBeNull()
  })

  it('"Remove zone" removes the selected zone, and the selection moves to its neighbour', () => {
    const { session, commit } = mountInspector([zone('a'), zone('b'), zone('c')])
    fireEvent.click(screen.getByRole('button', { name: en['privacy.removeZone'] }))
    expect(session.zones().map((item) => item.id)).toEqual(['b', 'c'])
    expect(session.selectedId()).toBe('b')
    expect(commit).toHaveBeenCalledTimes(1)
    expect(row('a')).toBeNull()
    expect(labelInput()).toBeTruthy()
    // Removing the last one leaves the empty state and nothing selected.
    fireEvent.click(screen.getByRole('button', { name: en['privacy.removeZone'] }))
    fireEvent.click(screen.getByRole('button', { name: en['privacy.removeZone'] }))
    expect(session.zones()).toEqual([])
    expect(session.selectedId()).toBeNull()
    expect(screen.getByText(en['privacy.editor.drawHint'])).toBeTruthy()
    expect(screen.queryByRole('button', { name: en['privacy.removeZone'] })).toBeNull()
  })
})

describe('MaskZoneInspector — save volume', () => {
  it('a label is typed as one save: nothing per keystroke, one once the hand settles', () => {
    const { session, commit } = mountInspector([zone('a')])
    const input = labelInput()
    for (const text of ['F', 'Fr', 'Fro', 'Fron', 'Front door']) {
      fireEvent.input(input, { target: { value: text } })
      // The field follows the hand at once.
      expect(input.value).toBe(text)
    }
    expect(commit).not.toHaveBeenCalled()
    expect(session.selectedZone()!.label).toBe('')

    vi.advanceTimersByTime(FIELD_COMMIT_DELAY_MS - 1)
    expect(commit).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')!.label).toBe('Front door')
    expect(session.selectedZone()!.label).toBe('Front door')
    // The details title took the name once it was the zone's.
    expect(screen.getAllByText('Front door').length).toBe(2)

    // Settled: the clock running on adds nothing.
    vi.advanceTimersByTime(FIELD_COMMIT_DELAY_MS * 3)
    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('leaving the label field saves what is on screen at once, and only once', () => {
    const { session, commit } = mountInspector([zone('a')])
    fireEvent.input(labelInput(), { target: { value: 'Door' } })
    fireEvent.blur(labelInput())
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.label).toBe('Door')
    settle()
    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('a blur with nothing typed saves nothing', () => {
    const { commit } = mountInspector([zone('a')])
    fireEvent.blur(labelInput())
    settle()
    expect(commit).not.toHaveBeenCalled()
  })

  it('Enter in the label field is the commit', () => {
    const { session, commit } = mountInspector([zone('a')])
    const input = labelInput()
    input.focus()
    fireEvent.input(input, { target: { value: 'Gate' } })
    expect(commit).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.label).toBe('Gate')
  })

  it('a slider dragged across its range is one save, with the value it landed on', () => {
    const { session, commit } = mountInspector([zone('a', { type: 'PIXELATE' })])
    const slider = screen.getByLabelText(en['privacy.pixelSize'])
    for (const value of ['20', '30', '48', '64']) {
      fireEvent.input(slider, { target: { value } })
      // The readout follows the hand; the session does not, yet.
      expect(readout(en['privacy.pixelSize'])).toBe(`${value}px`)
      expect(session.selectedZone()!.pixelateSize).toBe(16)
    }
    expect(commit).not.toHaveBeenCalled()
    settle()
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')!.pixelateSize).toBe(64)
    expect(session.selectedZone()!.pixelateSize).toBe(64)
    expect(readout(en['privacy.pixelSize'])).toBe('64px')
  })

  it('the blur radius saves once, as a whole number', () => {
    const { session, commit } = mountInspector([zone('a', { type: 'BLUR' })])
    const slider = screen.getByLabelText(en['privacy.blurRadius'])
    for (const value of ['12', '25', '37']) fireEvent.input(slider, { target: { value } })
    expect(readout(en['privacy.blurRadius'])).toBe('37')
    settle()
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')!.blurRadius).toBe(37)
    expect(session.selectedZone()!.blurRadius).toBe(37)
  })

  it('a pending save follows its own zone when the selection moves', () => {
    const { session, commit } = mountInspector([zone('a'), zone('b')])
    expect(session.selectedId()).toBe('a')
    fireEvent.input(labelInput(), { target: { value: 'New name' } })
    expect(commit).not.toHaveBeenCalled()

    // Another zone is picked while the first name is still unsaved.
    fireEvent.click(pick('b'))
    expect(session.selectedId()).toBe('b')
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')!.label).toBe('New name')
    expect(savedZone(commit, 'b')).toEqual(zone('b'))
    // The field is the new zone's now — empty, not the name typed for a.
    expect(labelInput().value).toBe('')
    settle()
    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('a pending save for a zone that was removed in the meantime saves nothing', () => {
    const { session, commit } = mountInspector([zone('a'), zone('b')])
    fireEvent.input(labelInput(), { target: { value: 'Too late' } })
    fireEvent.click(screen.getByRole('button', { name: en['privacy.removeZone'] }))
    // Only the removal reached the device.
    expect(commit).toHaveBeenCalledTimes(1)
    expect(savedZone(commit, 'a')).toBeNull()
    expect(session.selectedId()).toBe('b')
    settle()
    expect(commit).toHaveBeenCalledTimes(1)
  })
})

describe('MaskZoneInspector — fine-tune', () => {
  function openFineTune() {
    const button = screen.getByRole('button', { name: en['privacy.editor.fineTune'] })
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByLabelText(en['privacy.x'])).toBeNull()
    fireEvent.click(button)
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(document.getElementById(button.getAttribute('aria-controls')!)).toBe(document.querySelector('.nx-mask-grid'))
    return button
  }

  it('stays collapsed until asked for', () => {
    mountInspector([zone('a')])
    openFineTune()
  })

  it('holds the four fields in whole percent, each with its own bounds', () => {
    mountInspector([zone('a', { x: 0.1, y: 0.2, width: 0.3, height: 0.4 })])
    openFineTune()
    const expected: Array<['x' | 'y' | 'width' | 'height', string, number, number]> = [
      ['x', '10', 0, 100],
      ['y', '20', 0, 100],
      ['width', '30', 1, 100],
      ['height', '40', 1, 100],
    ]
    for (const [key, value, min, max] of expected) {
      const input = percentInput(key)
      expect(input.value, key).toBe(value)
      expect(input.getAttribute('min'), key).toBe(String(min))
      expect(input.getAttribute('max'), key).toBe(String(max))
      expect(input.getAttribute('step'), key).toBe('1')
      expect(input.type, key).toBe('number')
    }
  })

  it('Enter commits the number; no keystroke is a save', () => {
    const { session, commit } = mountInspector([zone('a')])
    openFineTune()
    const x = percentInput('x')
    x.focus()
    for (const value of ['6', '60']) {
      fireEvent.input(x, { target: { value } })
      expect(commit).not.toHaveBeenCalled()
    }
    expect(x.value).toBe('60')
    expect(session.selectedZone()!.x).toBe(0.25)
    fireEvent.keyDown(x, { key: 'Enter' })
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.x).toBe(0.6)
    expect(savedZone(commit, 'a')!.x).toBe(0.6)
    // Enter is not left to do anything else (submit, a newline).
  })

  it('blur commits too, and takes a whole number from a fraction typed', () => {
    const { session, commit } = mountInspector([zone('a')])
    openFineTune()
    fireEvent.input(percentInput('y'), { target: { value: '33' } })
    fireEvent.blur(percentInput('y'))
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.y).toBe(0.33)
  })

  it('a typed fraction rounds to the whole percent it names — it is never truncated', () => {
    const { session, commit } = mountInspector([zone('a')])
    openFineTune()
    fireEvent.input(percentInput('x'), { target: { value: '12.5' } })
    fireEvent.blur(percentInput('x'))
    expect(commit).toHaveBeenCalledTimes(1)
    // 12.5% rounds to 13, not down to 12.
    expect(session.selectedZone()!.x).toBe(0.13)
    expect(percentInput('x').value).toBe('13')
  })

  it('a number past the field\'s bounds is the session\'s frame rule to settle, and the fields show that result', () => {
    const { session, commit } = mountInspector([zone('a', { x: 0.25, width: 0.25 })])
    openFineTune()
    // The origin pushed past the far edge, to 150. A zone 25% wide cannot
    // start there — the frame rule cuts the width to its 1% floor and slides
    // the origin back to fit it: a sliver at the right edge, inside the picture.
    fireEvent.input(percentInput('x'), { target: { value: '150' } })
    fireEvent.blur(percentInput('x'))
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.x).toBe(0.99)
    expect(session.selectedZone()!.width).toBe(0.01)
    // The fields read the zone back — not the 150 that was typed, and not
    // the 25% the width used to be.
    expect(percentInput('x').value).toBe('99')
    expect(percentInput('width').value).toBe('1')

    // A size of zero is not a zone: the frame rule's floor holds, and since
    // that is already the zone's width there is nothing to save.
    fireEvent.input(percentInput('width'), { target: { value: '0' } })
    fireEvent.blur(percentInput('width'))
    expect(percentInput('width').value).toBe('1')
    expect(session.selectedZone()!.width).toBe(0.01)
    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('an origin before the near edge lands on it', () => {
    const { session, commit } = mountInspector([zone('a', { y: 0.25 })])
    openFineTune()
    fireEvent.input(percentInput('y'), { target: { value: '-40' } })
    fireEvent.blur(percentInput('y'))
    expect(commit).toHaveBeenCalledTimes(1)
    expect(session.selectedZone()!.y).toBe(0)
    expect(percentInput('y').value).toBe('0')
  })

  it('a width past the frame is cut to the room its origin has', () => {
    const { session, commit } = mountInspector([zone('a', { x: 0.8, width: 0.1 })])
    openFineTune()
    fireEvent.input(percentInput('width'), { target: { value: '60' } })
    fireEvent.blur(percentInput('width'))
    expect(commit).toHaveBeenCalledTimes(1)
    // The origin stays where it is; the width is what the picture has left.
    expect(session.selectedZone()!.x).toBe(0.8)
    expect(session.selectedZone()!.width).toBe(0.2)
    expect(percentInput('width').value).toBe('20')
  })

  it('a number that is not one puts the zone\'s value back and saves nothing', () => {
    const { commit } = mountInspector([zone('a')])
    openFineTune()
    const height = percentInput('height')
    fireEvent.input(height, { target: { value: '' } })
    fireEvent.blur(height)
    expect(height.value).toBe('25')
    expect(commit).not.toHaveBeenCalled()
  })

  it('follows the zone: a move on the picture lands in the fields', () => {
    const { session } = mountInspector([zone('a')])
    openFineTune()
    expect(percentInput('x').value).toBe('25')
    session.nudge(0.1, 0)
    expect(percentInput('x').value).toBe('35')
    session.updateZone({ width: 0.5 })
    expect(percentInput('width').value).toBe('50')
  })

  it('collapses again, keeping nothing — but the button keeps naming its grid', () => {
    mountInspector([zone('a')])
    const button = openFineTune()
    const gridId = button.getAttribute('aria-controls')!
    expect(gridId).not.toBe('')
    fireEvent.click(button)
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(button.getAttribute('aria-controls')).toBe(gridId)
    expect(document.querySelector('.nx-mask-grid')).toBeNull()
    // And the named grid is the one that comes back.
    fireEvent.click(button)
    expect(document.getElementById(button.getAttribute('aria-controls')!)).toBe(document.querySelector('.nx-mask-grid'))
  })
})

describe('MaskZoneInspector — the masking master switch', () => {
  const warnRow = () => document.querySelector('.nx-banner-warning')

  it('masking on: no warning row', () => {
    mountInspector([zone('a')], { maskingEnabled: true })
    expect(warnRow()).toBeNull()
    expect(screen.queryByText(en['privacy.editor.maskingOff'])).toBeNull()
    expect(screen.queryByRole('button', { name: en['privacy.editor.maskingOffAction'] })).toBeNull()
  })

  it('masking off: the warning leads the inspector, saying why, with the compact action', () => {
    mountInspector([zone('a')], { maskingEnabled: false })
    const row = warnRow()
    expect(row).not.toBeNull()
    expect(row!.getAttribute('role')).toBe('status')
    expect(row!.querySelector('.nx-mask-warn-text')?.textContent).toBe(en['privacy.editor.maskingOff'])
    expect(screen.getByRole('button', { name: en['privacy.editor.maskingOffAction'] })).toBeTruthy()
    // First thing in the column, above the zone list.
    expect(document.querySelector('.nx-mask-inspector')!.firstElementChild).toBe(row)
  })

  it('the action turns the master switch on through the streaming mirror, and the row goes', () => {
    const { updateStreamingDebounced } = mountInspector([zone('a')], { maskingEnabled: false })
    fireEvent.click(screen.getByRole('button', { name: en['privacy.editor.maskingOffAction'] }))
    expect(updateStreamingDebounced).toHaveBeenCalledTimes(1)
    expect(updateStreamingDebounced).toHaveBeenCalledWith({ maskingEnabled: true })
    // The mirror took the patch: the row reads the switch live, and is gone.
    expect(warnRow()).toBeNull()
    // The zone list is untouched by the flip.
    expect(document.querySelectorAll('.nx-mask-zone-row')).toHaveLength(1)
  })
})
