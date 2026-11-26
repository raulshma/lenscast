// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, within } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { en } from '../../../../../i18n/en'
import { maskEditActive, resetMaskEditStoreForTests, startMaskEdit } from '../../../../../lib/maskEditStore'
import type { AllSettings, DeviceStatus, MaskingZone } from '../../../../../types'
import type { NextState } from '../../../NextState'
import MaskingQuickPanel from './MaskingQuickPanel'

// The normal Live inspector's privacy-masking panel: what it shows about the
// zones, when "Edit zones" stands down, and what the button arms. The state
// is hand-built and cast (the NextShell fixture pattern); the store is the
// real one, reset around every test.

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
})

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

function settingsDoc(maskingEnabled: boolean, zones: MaskingZone[]): AllSettings {
  return { ...settingsFixture, streaming: { ...settingsFixture.streaming, maskingEnabled, maskingZones: zones } } as unknown as AllSettings
}

const streamStatus = (on: boolean): DeviceStatus => ({ streaming: { webStreamingActive: on } }) as unknown as DeviceStatus

/**
 * One mounted panel and its own queries. Several may be mounted side by side
 * in a test, so nothing here looks at the whole document.
 */
function mountPanel(opts: { zones?: MaskingZone[]; maskingEnabled?: boolean; streamOn?: boolean | null } = {}) {
  const [settings, setSettings] = createSignal<AllSettings | null>(settingsDoc(opts.maskingEnabled ?? false, opts.zones ?? []))
  // streamOn: null = the status has not loaded yet.
  const [status, setStatus] = createSignal<DeviceStatus | null>(opts.streamOn === null ? null : streamStatus(opts.streamOn !== false))
  const state = { settings, status, isViewer: () => false } as unknown as NextState
  const view = render(() => <MaskingQuickPanel state={state} />)
  const inPanel = () => within(view.container)
  return {
    container: view.container,
    setSettings,
    setStatus,
    led: () => view.container.querySelector('.nx-panel-header .nx-led'),
    lit: () => view.container.querySelector('.nx-panel-header .nx-led')?.classList.contains('nx-led-ok') === true,
    countLabel: () => view.container.querySelector('.nx-field-label')?.textContent ?? null,
    hint: () => view.container.querySelector('.nx-field-hint')?.textContent ?? null,
    editButton: () => inPanel().queryByRole('button', { name: en['privacy.editZoneTitle'] }) as HTMLButtonElement | null,
  }
}

const count = (n: number) => en['privacy.zones'].replace('{count}', String(n))

describe('MaskingQuickPanel', () => {
  it('shows the panel, the zone count, and "Edit zones" named for what it does', () => {
    const panel = mountPanel({ zones: [zone('a'), zone('b')], streamOn: true })
    expect(panel.container.querySelector('.nx-panel-title')?.textContent).toBe(en['privacy.title'])
    expect(panel.countLabel()).toBe(count(2))
    const button = panel.editButton()!
    expect(button).not.toBeNull()
    expect(button.textContent).toBe(en['privacy.editZones'])
    // The kit speaks the styled bubble (data-tooltip), never the native title.
    expect(button.getAttribute('data-tooltip')).toBe(en['privacy.editZoneTitle'])
    expect(button.hasAttribute('title')).toBe(false)
    expect(button.classList.contains('nx-btn-primary')).toBe(true)
    expect(panel.hint()).toBeNull()
  })

  it('the LED is lit only when the master is on and at least one zone is enabled — the truth the picture shows', () => {
    // A disabled master with a zone masks nothing.
    expect(mountPanel({ zones: [zone('a')], maskingEnabled: false, streamOn: true }).lit()).toBe(false)
    expect(mountPanel({ zones: [zone('a')], maskingEnabled: true, streamOn: true }).lit()).toBe(true)
    // An enabled master with no zones masks nothing.
    expect(mountPanel({ zones: [], maskingEnabled: true, streamOn: true }).lit()).toBe(false)
    // Nor with only disabled zones.
    expect(mountPanel({ zones: [zone('a', { enabled: false }), zone('b', { enabled: false })], maskingEnabled: true, streamOn: true }).lit()).toBe(false)
    // One enabled zone is enough.
    expect(mountPanel({ zones: [zone('a', { enabled: false }), zone('b')], maskingEnabled: true, streamOn: true }).lit()).toBe(true)
  })

  it('follows the settings mirror as the poll replaces it', () => {
    const panel = mountPanel({ zones: [zone('a')], maskingEnabled: false, streamOn: true })
    expect(panel.lit()).toBe(false)
    expect(panel.countLabel()).toBe(count(1))
    panel.setSettings(settingsDoc(true, [zone('a'), zone('b')]))
    expect(panel.lit()).toBe(true)
    expect(panel.countLabel()).toBe(count(2))
    panel.setSettings(settingsDoc(true, []))
    expect(panel.lit()).toBe(false)
    expect(panel.countLabel()).toBe(count(0))
  })

  it('"Edit zones" arms the edit — and the panel leaves the screen the moment it is armed', () => {
    const panel = mountPanel({ zones: [zone('a')], streamOn: true })
    expect(maskEditActive()).toBe(false)
    fireEvent.click(panel.editButton()!)
    expect(maskEditActive()).toBe(true)
    // Nothing of the panel is left: its button must not re-arm a running edit.
    expect(panel.container.querySelector('.nx-panel')).toBeNull()
    expect(panel.editButton()).toBeNull()
  })

  it('renders nothing while an edit is already active', () => {
    startMaskEdit()
    const armed = mountPanel({ zones: [zone('a')], streamOn: true })
    // Not the panel, not its button, not a character of text.
    expect(armed.container.querySelector('.nx-panel')).toBeNull()
    expect(armed.editButton()).toBeNull()
    expect(armed.container.textContent).toBe('')
  })

  it('without the web stream the button stands down and says why', () => {
    const panel = mountPanel({ zones: [zone('a')], streamOn: false })
    const button = panel.editButton()!
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('data-tooltip')).toBe(en['privacy.streamOffHint'])
    // The reason is on the row, not only in a tooltip.
    expect(panel.hint()).toBe(en['privacy.streamOffHint'])
    fireEvent.click(button)
    expect(maskEditActive()).toBe(false)
  })

  it('a status that has not loaded counts as no stream', () => {
    expect(mountPanel({ zones: [zone('a')], streamOn: null }).editButton()!.disabled).toBe(true)
  })

  it('the button follows the stream: down when it stops, back when it returns', () => {
    const panel = mountPanel({ zones: [zone('a')], streamOn: true })
    expect(panel.editButton()!.disabled).toBe(false)
    expect(panel.hint()).toBeNull()
    panel.setStatus(streamStatus(false))
    expect(panel.editButton()!.disabled).toBe(true)
    expect(panel.hint()).toBe(en['privacy.streamOffHint'])
    panel.setStatus(streamStatus(true))
    expect(panel.editButton()!.disabled).toBe(false)
    expect(panel.hint()).toBeNull()
    expect(panel.editButton()!.getAttribute('data-tooltip')).toBe(en['privacy.editZoneTitle'])
  })
})
