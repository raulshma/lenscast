// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, within } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { en } from '../../../../../i18n/en'
import {
  maskEditActive,
  maskEditFocusZoneId,
  maskEditSession,
  resetMaskEditStoreForTests,
} from '../../../../../lib/maskEditStore'
import { hashRouter } from '../../../../../lib/router'
import type { MaskingZone, StreamingSettings } from '../../../../../types'
import PrivacyMaskingPanel from './PrivacyMaskingPanel'

// The beta System view's Privacy Masking panel (Camera → Overlays) in jsdom:
// the settings-panel entry point of the visual masking editor, and what the
// panel keeps for itself. The store and the router are the real ones; the
// panel's three props are handed to it the way SystemView hands them.

const keyText = en['privacy.editZoneTitle']
const streamOff = en['privacy.streamOffHint']
const zoneName = (n: number) => en['privacy.zone'].replace('{n}', String(n))
const count = (n: number) => en['privacy.zones'].replace('{count}', String(n))
/** What a zone's open key is called: the zone first, then what the key does. */
const openKeyName = (name: string) => `${name} · ${keyText}`

function zone(id: string, overrides: Partial<MaskingZone> = {}): MaskingZone {
  return { id, label: '', enabled: true, type: 'BLACKOUT', x: 0.25, y: 0.25, width: 0.25, height: 0.25, pixelateSize: 16, blurRadius: 10, ...overrides }
}

function settingsDoc(maskingEnabled: boolean, zones: MaskingZone[]): StreamingSettings {
  return { ...settingsFixture.streaming, maskingEnabled, maskingZones: zones } as unknown as StreamingSettings
}

/**
 * One mounted panel and its own queries. `apply` does what the parent does
 * with a patch — folds it into the settings the panel reads — which the
 * panel never does for itself.
 */
function mountPanel(opts: { zones?: MaskingZone[]; maskingEnabled?: boolean; streamOn?: boolean } = {}) {
  const [streaming, setStreaming] = createSignal<StreamingSettings>(settingsDoc(opts.maskingEnabled ?? true, opts.zones ?? []))
  const [streamActive, setStreamActive] = createSignal(opts.streamOn ?? true)
  const onUpdate = vi.fn((_patch: Partial<StreamingSettings>) => {})
  const view = render(() => (
    <PrivacyMaskingPanel streaming={streaming} onUpdate={onUpdate} streamActive={streamActive} />
  ))
  const root = () => view.container.querySelector<HTMLElement>('.nx-panel')!
  const inPanel = () => within(root())
  const inRow = (id: string) => within(root().querySelector(`[data-zone-id="${id}"]`)!)
  /** The patch of the n-th onUpdate call; the last one by default. */
  const patchOf = (call = onUpdate.mock.calls.length - 1): Partial<StreamingSettings> => onUpdate.mock.calls[call][0]
  return {
    setStreamActive,
    onUpdate,
    patchOf,
    root,
    rows: () => [...root().querySelectorAll<HTMLElement>('.nx-list-row[data-zone-id]')],
    row: (id: string) => root().querySelector<HTMLElement>(`.nx-list-row[data-zone-id="${id}"]`),
    editZones: () => inPanel().getByRole('button', { name: keyText }) as HTMLButtonElement,
    editZonesButtonOrNull: () => inPanel().queryByRole('button', { name: keyText }) as HTMLButtonElement | null,
    openKey: (id: string, name: string) => inRow(id).getByRole('button', { name: openKeyName(name) }) as HTMLButtonElement,
    removeKey: (id: string) => inRow(id).getByRole('button', { name: en['privacy.removeZone'] }) as HTMLButtonElement,
    /** The row's disclosure is named for its zone alone; the open key's name is longer. */
    disclosure: (id: string, name: string) => inRow(id).getByRole('button', { name }) as HTMLButtonElement,
    /** The zone count as it heads the list: the label of the row that holds "Edit zones". */
    countLabel: () => inPanel().getByRole('button', { name: keyText }).closest('.nx-field-row')?.querySelector('.nx-field-label')?.textContent ?? null,
    /** Why a key stands down — on the row, as text. */
    hint: () => root().querySelector('.nx-field-hint')?.textContent ?? null,
    emptyTitle: () => root().querySelector('.nx-empty-title')?.textContent ?? null,
    labelInput: () => inPanel().queryByLabelText(en['privacy.label']) as HTMLInputElement | null,
    enabledSwitch: () => inPanel().getByLabelText(en['privacy.enabled']) as HTMLInputElement,
    typeSelect: () => inPanel().getByLabelText(en['privacy.maskType']) as HTMLSelectElement,
    masterSwitch: () => inPanel().getByLabelText(en['privacy.enable']) as HTMLInputElement,
    apply: () => setStreaming({ ...streaming(), ...patchOf() }),
  }
}

/** The entry point never fired: nothing armed, no zone named, and the route never left the panel. */
function stillOnThePanel(): void {
  expect(maskEditActive()).toBe(false)
  expect(maskEditFocusZoneId()).toBeNull()
  expect(maskEditSession()).toBeNull()
  expect(hashRouter().route()).toEqual({ name: 'system', tab: 'camera' })
  expect(location.hash).toBe('#/system/camera')
}

beforeEach(() => {
  resetMaskEditStoreForTests()
  // Every test opens the panel where it lives, with nothing armed.
  hashRouter().navigate({ name: 'system', tab: 'camera' })
  expect(maskEditActive()).toBe(false)
})

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
})

// ══ The way in ═════════════════════════════════════════════════════════

describe('PrivacyMaskingPanel — the way into the visual editor', () => {
  it('"Edit zones" opens the editor with no zone selected', () => {
    const panel = mountPanel({ zones: [zone('a'), zone('b')] })
    const button = panel.editZones()
    // Visible as the verb, named for what it does.
    expect(button.textContent).toBe(en['privacy.editZones'])
    expect(button.getAttribute('data-tooltip')).toBe(keyText)
    expect(button.hasAttribute('title')).toBe(false)
    expect(button.classList.contains('nx-btn-primary')).toBe(true)
    expect(button.disabled).toBe(false)
    expect(panel.hint()).toBeNull()

    fireEvent.click(button)
    expect(maskEditActive()).toBe(true)
    expect(maskEditFocusZoneId()).toBeNull()
    expect(hashRouter().route().name).toBe('live')
    expect(location.hash).toBe('#/live')
    // Armed and routed, and nothing more: the editor is Live's to create.
    expect(maskEditSession()).toBeNull()
  })

  it('with no zones it is still the way in — the empty state sits under the button', () => {
    const panel = mountPanel({ zones: [] })
    expect(panel.rows()).toHaveLength(0)
    expect(panel.emptyTitle()).toBe(en['privacy.editor.empty'])
    expect(panel.editZones().disabled).toBe(false)

    fireEvent.click(panel.editZones())
    expect(maskEditActive()).toBe(true)
    expect(maskEditFocusZoneId()).toBeNull()
    expect(hashRouter().route().name).toBe('live')
  })

  it('a zone row opens the editor on that zone', () => {
    const panel = mountPanel({ zones: [zone('a', { label: 'Front door' }), zone('b', { type: 'BLUR' }), zone('c')] })
    expect(panel.rows().map((row) => row.dataset.zoneId)).toEqual(['a', 'b', 'c'])

    fireEvent.click(panel.openKey('b', zoneName(2)))
    expect(maskEditActive()).toBe(true)
    expect(maskEditFocusZoneId()).toBe('b')
    expect(hashRouter().route().name).toBe('live')
    expect(maskEditSession()).toBeNull()
  })

  it('a labelled zone opens on its label, an unlabelled one on its number', () => {
    const panel = mountPanel({ zones: [zone('a', { label: 'Front door' }), zone('b')] })

    fireEvent.click(panel.openKey('a', 'Front door'))
    expect(maskEditFocusZoneId()).toBe('a')
    expect(hashRouter().route().name).toBe('live')

    resetMaskEditStoreForTests()
    hashRouter().navigate({ name: 'system', tab: 'camera' })
    fireEvent.click(panel.openKey('b', zoneName(2)))
    expect(maskEditFocusZoneId()).toBe('b')
    expect(hashRouter().route().name).toBe('live')
  })

  it('every key names its zone first, then the one thing they all do', () => {
    const panel = mountPanel({ zones: [zone('a', { label: 'Front door' }), zone('b', { label: 'Monitor' }), zone('c')] })
    const keys = [...panel.root().querySelectorAll<HTMLButtonElement>('.nx-privacy-zone-open')]
    // Three zones, three keys — and not one name shared three times.
    expect(keys.map((key) => key.getAttribute('aria-label'))).toEqual([
      `Front door · ${keyText}`,
      `Monitor · ${keyText}`,
      `${zoneName(3)} · ${keyText}`,
    ])
    // Every button that speaks for the editor, and nothing else: the panel's
    // own "Edit zones", and the three keys — one a zone.
    const namedForKey = (name: string) => name.includes(keyText)
    expect(within(panel.root()).getAllByRole('button', { name: namedForKey })).toHaveLength(4)
    for (const key of keys) {
      // The name on screen is the name announced (Label in Name).
      expect(key.getAttribute('aria-label')!.startsWith(key.querySelector('.nx-privacy-zone-name')!.textContent!)).toBe(true)
      expect(key.getAttribute('data-tooltip')).toBe(keyText)
    }
    // The zone's type travels with its name.
    expect(keys[0].querySelector('.nx-badge')!.textContent).toBe(en['option.mask.blackout'])
  })
})

// ══ Nothing to draw on ═════════════════════════════════════════════════

describe('PrivacyMaskingPanel — without the web stream', () => {
  it('every way in stands down, and the reason is on the row as text', () => {
    const panel = mountPanel({ zones: [zone('a'), zone('b')], streamOn: false })
    const button = panel.editZones()
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('data-tooltip')).toBe(streamOff)
    // The reason is on the row, not only in a tooltip: a disabled button is
    // not one a keyboard or screen reader user can reach to hear it.
    expect(panel.hint()).toBe(streamOff)
    const keys = [panel.openKey('a', zoneName(1)), panel.openKey('b', zoneName(2))]
    for (const key of keys) {
      expect(key.disabled).toBe(true)
      expect(key.getAttribute('data-tooltip')).toBe(streamOff)
    }

    // However the press arrives, nothing is armed and the panel is not left.
    fireEvent.click(button)
    for (const key of keys) fireEvent.click(key)
    stillOnThePanel()
  })

  it('no zones, no stream: the button stands down there too', () => {
    const panel = mountPanel({ zones: [], streamOn: false })
    expect(panel.editZones().disabled).toBe(true)
    expect(panel.hint()).toBe(streamOff)
    fireEvent.click(panel.editZones())
    stillOnThePanel()
  })

  it('the keys follow the stream: down when it stops, back when it returns', () => {
    const panel = mountPanel({ zones: [zone('a')] })
    expect(panel.openKey('a', zoneName(1)).disabled).toBe(false)
    expect(panel.hint()).toBeNull()

    panel.setStreamActive(false)
    expect(panel.editZones().disabled).toBe(true)
    expect(panel.openKey('a', zoneName(1)).disabled).toBe(true)
    expect(panel.hint()).toBe(streamOff)

    panel.setStreamActive(true)
    expect(panel.editZones().disabled).toBe(false)
    expect(panel.openKey('a', zoneName(1)).disabled).toBe(false)
    expect(panel.hint()).toBeNull()
    expect(panel.openKey('a', zoneName(1)).getAttribute('data-tooltip')).toBe(keyText)
    // Back with the stream, the key does its work again.
    fireEvent.click(panel.openKey('a', zoneName(1)))
    expect(maskEditFocusZoneId()).toBe('a')
    expect(hashRouter().route().name).toBe('live')
  })

  it('only the way in is shut: the master switch, the settings and × still work', () => {
    const a = zone('a', { label: 'Front door' })
    const b = zone('b')
    const panel = mountPanel({ zones: [a, b], streamOn: false })

    fireEvent.click(panel.disclosure('a', 'Front door'))
    fireEvent.input(panel.labelInput()!, { target: { value: 'Gate' } })
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [{ ...a, label: 'Gate' }, b] })

    fireEvent.click(panel.removeKey('a'))
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [b] })

    fireEvent.click(panel.masterSwitch())
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingEnabled: false })
    stillOnThePanel()
  })
})

// ══ The row's controls are not the row ═════════════════════════════════

describe('PrivacyMaskingPanel — the row opens the editor; its controls do not', () => {
  it('the remove key removes its zone, and only that', () => {
    const a = zone('a')
    const b = zone('b', { label: 'Monitor' })
    const panel = mountPanel({ zones: [a, b] })
    fireEvent.click(panel.removeKey('b'))
    expect(panel.onUpdate).toHaveBeenCalledTimes(1)
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [a] })
    // The zone that stays is the same object, not a copy.
    expect(panel.patchOf().maskingZones![0]).toBe(a)
    stillOnThePanel()

    panel.apply()
    expect(panel.row('b')).toBeNull()
    expect(panel.row('a')).not.toBeNull()
    expect(panel.countLabel()).toBe(count(1))
  })

  it('removing the last zone leaves the empty state; removing the open one closes its settings', () => {
    const panel = mountPanel({ zones: [zone('a'), zone('b')] })
    // a's settings are open, then a goes.
    fireEvent.click(panel.disclosure('a', zoneName(1)))
    expect(panel.labelInput()).not.toBeNull()
    fireEvent.click(panel.removeKey('a'))
    panel.apply()
    expect(panel.row('a')).toBeNull()
    // Nothing of a is left on screen: its settings went with it.
    expect(panel.labelInput()).toBeNull()
    stillOnThePanel()

    fireEvent.click(panel.removeKey('b'))
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [] })
    panel.apply()
    expect(panel.rows()).toHaveLength(0)
    expect(panel.emptyTitle()).toBe(en['privacy.editor.empty'])
    // "Edit zones" stays: an empty list is the first thing to draw.
    expect(panel.editZones().disabled).toBe(false)
    stillOnThePanel()
  })

  it('the disclosure opens one zone\'s settings at a time, and never the editor', () => {
    const panel = mountPanel({ zones: [zone('a', { label: 'Front door' }), zone('b', { label: 'Monitor' })] })
    expect(panel.labelInput()).toBeNull()
    expect(panel.disclosure('a', 'Front door').getAttribute('aria-expanded')).toBe('false')

    fireEvent.click(panel.disclosure('a', 'Front door'))
    expect(panel.labelInput()!.value).toBe('Front door')
    expect(panel.disclosure('a', 'Front door').getAttribute('aria-expanded')).toBe('true')
    stillOnThePanel()

    // One open at a time, as ever.
    fireEvent.click(panel.disclosure('b', 'Monitor'))
    expect(panel.labelInput()!.value).toBe('Monitor')
    expect(panel.disclosure('a', 'Front door').getAttribute('aria-expanded')).toBe('false')
    expect(panel.disclosure('b', 'Monitor').getAttribute('aria-expanded')).toBe('true')
    stillOnThePanel()

    fireEvent.click(panel.disclosure('b', 'Monitor'))
    expect(panel.labelInput()).toBeNull()
    stillOnThePanel()
  })
})

// ══ What the panel keeps ═══════════════════════════════════════════════

describe('PrivacyMaskingPanel — what stays here, and what the picture does now', () => {
  it('the master switch heads the panel; while masking is off, nothing follows it', () => {
    const panel = mountPanel({ zones: [zone('a')], maskingEnabled: false })
    expect(panel.masterSwitch().checked).toBe(false)
    expect(panel.editZonesButtonOrNull()).toBeNull()
    expect(panel.rows()).toHaveLength(0)
    expect(panel.emptyTitle()).toBeNull()

    fireEvent.click(panel.masterSwitch())
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingEnabled: true })
  })

  it('the zone count heads the list', () => {
    const panel = mountPanel({ zones: [zone('a'), zone('b')] })
    expect(panel.countLabel()).toBe(count(2))
  })

  it('a zone is placed on the picture: no position sliders, and no "Add Zone"', () => {
    const panel = mountPanel({ zones: [zone('a', { type: 'PIXELATE' }), zone('b')] })
    expect(within(panel.root()).queryByRole('button', { name: en['privacy.addZone'] })).toBeNull()
    expect(panel.root().textContent).not.toContain(en['privacy.addZone'])
    // Open a zone's settings: the only sliders left are the mask's own strength.
    fireEvent.click(panel.disclosure('a', zoneName(1)))
    for (const field of ['x', 'y', 'width', 'height']) {
      expect(within(panel.root()).queryByLabelText(en[`privacy.${field}`])).toBeNull()
    }
    expect(within(panel.root()).getAllByRole('slider').map((slider) => slider.getAttribute('aria-label'))).toEqual([
      en['privacy.pixelSize'],
    ])
  })

  it('the empty state is the beta one — never the classic card\'s', () => {
    const panel = mountPanel({ zones: [] })
    expect(panel.emptyTitle()).toBe(en['privacy.editor.empty'])
    // The classic wording names a button this panel no longer has.
    expect(panel.root().textContent).not.toContain(en['privacy.empty'])
    expect(panel.root().textContent).not.toContain('Add Zone')
  })

  it('the settings save through onUpdate — the zone changed, and only it', () => {
    const a = zone('a')
    const b = zone('b', { label: 'Monitor' })
    const panel = mountPanel({ zones: [a, b] })
    fireEvent.click(panel.disclosure('a', zoneName(1)))

    fireEvent.input(panel.labelInput()!, { target: { value: 'Front door' } })
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [{ ...a, label: 'Front door' }, b] })

    fireEvent.click(panel.enabledSwitch())
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [{ ...a, enabled: false }, b] })

    fireEvent.change(panel.typeSelect(), { target: { value: 'PIXELATE' } })
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [{ ...a, type: 'PIXELATE' }, b] })
    // The other zone rides along untouched: the same object, not a copy.
    expect(panel.patchOf().maskingZones![1]).toBe(b)
    // Not one of these has opened the editor.
    stillOnThePanel()

    // The parameter belongs to the type, over the same range as ever.
    panel.apply()
    const pixel = within(panel.root()).getByLabelText(en['privacy.pixelSize']) as HTMLInputElement
    expect([pixel.min, pixel.max, pixel.value]).toEqual(['4', '64', '16'])
    fireEvent.input(pixel, { target: { value: '24' } })
    expect(panel.onUpdate).toHaveBeenLastCalledWith({ maskingZones: [{ ...a, type: 'PIXELATE', pixelateSize: 24 }, b] })

    panel.apply()
    fireEvent.change(panel.typeSelect(), { target: { value: 'BLUR' } })
    panel.apply()
    const blur = within(panel.root()).getByLabelText(en['privacy.blurRadius']) as HTMLInputElement
    expect([blur.min, blur.max, blur.value]).toEqual(['1', '50', '10'])
    fireEvent.input(blur, { target: { value: '20' } })
    expect(panel.onUpdate).toHaveBeenLastCalledWith({
      maskingZones: [{ ...a, type: 'BLUR', pixelateSize: 24, blurRadius: 20 }, b],
    })

    // A blackout has no strength to tune.
    panel.apply()
    fireEvent.change(panel.typeSelect(), { target: { value: 'BLACKOUT' } })
    panel.apply()
    expect(within(panel.root()).queryByLabelText(en['privacy.pixelSize'])).toBeNull()
    expect(within(panel.root()).queryByLabelText(en['privacy.blurRadius'])).toBeNull()
    stillOnThePanel()
  })
})

// ══ The open key, as written ═══════════════════════════════════════════
// jsdom lays nothing out, so the hit target is read where it is written:
// the kit's button recipe in components.css, and the key's own rules in
// styles/next/views/cards.css.

/** Every rule of a stylesheet, comments stripped, as [selector, body]. An at-rule's own rules are listed on their own. */
function rules(css: string): Array<[string, string]> {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const found: Array<[string, string]> = []
  const open: Array<{ selector: string | null; start: number }> = []
  let last = 0
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '{') {
      const prelude = text.slice(last, i).trim()
      open.push({ selector: /^@media/.test(prelude) ? null : prelude, start: i + 1 })
      last = i + 1
    } else if (text[i] === '}') {
      const frame = open.pop()
      if (frame && frame.selector !== null) found.push([frame.selector, text.slice(frame.start, i)])
      last = i + 1
    } else if (text[i] === ';') {
      last = i + 1
    }
  }
  return found
}

describe('PrivacyMaskingPanel — the open key meets the hit-target floor, as the stylesheets write it', () => {
  const read = (file: string) => readFileSync(resolve(process.cwd(), file), 'utf8')
  const cards = rules(read('src/styles/next/views/cards.css'))
  const components = rules(read('src/styles/next/components.css'))
  const tokens = rules(read('src/styles/next/tokens.css'))
  /** The one rule with exactly this selector. */
  const body = (sheet: Array<[string, string]>, selector: string): string => {
    const found = sheet.filter(([candidate]) => candidate === selector)
    expect(found, selector).toHaveLength(1)
    return found[0][1]
  }
  /** Every rule whose selector mentions the key. */
  const keyRules = (needle: string) => cards.filter(([selector]) => selector.includes(needle))

  it('is a real button, and the kit\'s own at its full height', () => {
    const panel = mountPanel({ zones: [zone('a')] })
    const key = panel.openKey('a', zoneName(1))
    expect(key.tagName).toBe('BUTTON')
    expect(key.type).toBe('button')
    expect(key.disabled).toBe(false)
    expect(key.tabIndex).toBe(0)
    expect([...key.classList]).toContain('nx-btn')
    // The 36px key, not the 32px small one.
    expect(key.classList.contains('nx-btn-sm')).toBe(false)
  })

  it('36px tall, with a hit reaching 4px past every edge: the 44px floor', () => {
    // The kit: the box is the tall control; its ::before reaches past it.
    expect(body(components, "[data-ui='next'] .nx-btn")).toMatch(/(^|\s)height:\s*var\(--control-h-tall\);/)
    const reach = /inset:\s*-(\d+)px;/.exec(body(components, "[data-ui='next'] .nx-btn:before"))
    const height = /--control-h-tall:\s*(\d+)px;/.exec(body(tokens, ":root[data-ui='next']"))
    expect(reach, 'the kit button\'s ::before inset').not.toBeNull()
    expect(height, 'the --control-h-tall token').not.toBeNull()
    expect(Number(height![1]) + 2 * Number(reach![1])).toBeGreaterThanOrEqual(44)
  })

  it('the key\'s own recipe adds no height and no hit of its own', () => {
    const own = keyRules('.nx-privacy-zone-open')
    expect(own.length).toBeGreaterThanOrEqual(2)
    for (const [selector, ruleBody] of own) {
      expect(selector, selector).not.toMatch(/::?before/)
      expect(ruleBody, selector).not.toMatch(/(^|[;\s])(height|min-height|max-height|inset|display)\s*:/)
    }
  })

  it('its rules stay inside the beta shell and paint in tokens only', () => {
    const own = [...keyRules('.nx-privacy-zone-open'), ...keyRules('.nx-privacy-zone-name')]
    expect(own.length).toBeGreaterThanOrEqual(3)
    for (const [selector, ruleBody] of own) {
      expect(selector.split(',').every((part) => part.trim().startsWith("[data-ui='next']")), selector).toBe(true)
      expect(ruleBody, selector).not.toMatch(/#[0-9a-fA-F]{3,8}\b|\brgba?\(|\boklch\(|\bhsl\(/)
      expect(ruleBody, selector).not.toContain('data-theme')
    }
  })
})
