// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NextState } from './NextState'
import type { InspectorLayoutController } from '../../hooks/useInspectorLayout'
import {
  defaultInspectorLayout,
  setColumnWidth,
  type WebUiLayoutDto,
} from '../../lib/inspectorLayout'
import { en } from '../../i18n/en'
import InspectorConfigurator from './InspectorConfigurator'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function fixture(viewer = false) {
  const [layout, setLayout] = createSignal<WebUiLayoutDto>(defaultInspectorLayout())
  const [isViewer] = createSignal(viewer)
  const reset = vi.fn(async () => { setLayout(defaultInspectorLayout()) })
  const controller: InspectorLayoutController = {
    layout,
    ready: () => true,
    saving: () => false,
    localOnly: () => false,
    error: () => '',
    mutate: (transform) => setLayout((current) => transform(current)),
    replaceLayout: (raw) => setLayout(raw as WebUiLayoutDto),
    reset,
    refresh: async () => {},
    flush: async () => {},
  }
  return {
    layout,
    reset,
    state: { isViewer } as unknown as NextState,
    controller,
  }
}

function mount(viewer = false) {
  const data = fixture(viewer)
  const view = render(() => (
    <InspectorConfigurator open onClose={vi.fn()} state={data.state} layout={data.controller} />
  ))
  return { ...data, ...view }
}

describe('InspectorConfigurator', () => {
  it('reorders panels with accessible buttons and changes visibility', () => {
    const { layout } = mount()
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.moveDown'].replace('{title}', 'Channels') }))
    expect(layout().sections[1].sectionId).toBe('channels')
    const row = document.querySelector<HTMLElement>('[data-placement-id="channels"]')!
    fireEvent.click(within(row).getByLabelText(en['next.inspector.configurator.visible']))
    expect(layout().sections[1].visible).toBe(false)
  })

  it('reorders panels by dragging and by Space plus Arrow keys', () => {
    const { layout } = mount()
    const channels = document.querySelector<HTMLElement>('[data-placement-id="channels"]')!
    const storage = document.querySelector<HTMLElement>('[data-placement-id="storage"]')!
    fireEvent.dragStart(channels)
    fireEvent.dragOver(storage)
    fireEvent.drop(storage)
    expect(layout().sections[1].sectionId).toBe('channels')

    fireEvent.keyDown(channels, { key: ' ' })
    fireEvent.keyDown(channels, { key: 'ArrowDown' })
    expect(layout().sections[2].sectionId).toBe('channels')
    fireEvent.keyDown(channels, { key: 'Escape' })
    expect(within(channels).getByRole('button', {
      name: en['next.inspector.configurator.grab'].replace('{title}', 'Channels'),
    }).getAttribute('aria-pressed')).toBe('false')
  })

  it('filters the catalog with localized category labels', () => {
    mount()
    const category = screen.getByRole('combobox', { name: en['next.inspector.configurator.category'] })
    expect(within(category).getByRole('option', {
      name: en['next.inspector.configurator.category.detection'],
    })).toBeTruthy()
    expect(within(category).queryByText('system.tab.detection')).toBeNull()
    fireEvent.change(category, { target: { value: 'detection' } })
    expect(screen.getByRole('button', { name: 'Detection Events: Add' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Lens: Add' })).toBeNull()
  })

  it('toggles individual built-in panel items', () => {
    const { layout } = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Items in Channels' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Channels' }))
    expect(layout().sections[0].hiddenItems).toEqual(['channels.status'])
  })

  it('adds catalog panels and pins controls into a custom section', () => {
    const { layout } = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Lens: Add' }))
    expect(layout().sections.some((section) => section.sectionId === 'lens')).toBe(true)

    fireEvent.input(screen.getByPlaceholderText(en['next.inspector.configurator.customTitlePlaceholder']), {
      target: { value: 'Night rig' },
    })
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.add'] }))
    const custom = layout().sections.at(-1)!
    expect(custom.kind).toBe('custom')
    expect(custom.customTitle).toBe('Night rig')
    const customRow = screen.getByDisplayValue('Night rig').closest('.nx-inspector-order-row') as HTMLElement
    fireEvent.click(within(customRow).getByLabelText(en['next.inspector.configurator.collapsed']))
    expect(layout().sections.at(-1)!.collapsed).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Items in Night rig' }))
    fireEvent.input(screen.getByRole('searchbox', { name: en['next.inspector.configurator.searchItems'] }), {
      target: { value: 'ISO' },
    })
    fireEvent.click(screen.getByRole('checkbox', { name: 'ISO' }))
    expect(layout().sections.at(-1)!.items).toEqual(['exposure.iso'])
  })

  it('saves the current layout as a named custom preset', () => {
    const { layout } = mount()
    fireEvent.input(screen.getByPlaceholderText(en['next.inspector.configurator.presetNamePlaceholder']), {
      target: { value: 'Night watch' },
    })
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.savePreset'] }))
    expect(layout().presets).toHaveLength(1)
    expect(layout().presets[0].name).toBe('Night watch')
    expect(layout().activePresetId).toBe(layout().presets[0].id)
  })

  it('exports the current layout as downloadable JSON', async () => {
    const { layout } = mount()
    const createObjectURL = vi.fn((_blob: Blob) => 'blob:layout')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.export'] }))
    const blob = createObjectURL.mock.calls[0][0]
    expect(blob.type).toBe('application/json')
    const text = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(blob)
    })
    expect(JSON.parse(text)).toEqual(layout())
    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:layout'))
  })

  it('imports a JSON layout through the file boundary', async () => {
    const { layout, container } = mount()
    const imported = setColumnWidth(defaultInspectorLayout(), 480, 'custom')
    const file = new File([JSON.stringify(imported)], 'layout.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', { value: async () => JSON.stringify(imported) })
    const input = container.querySelector<HTMLInputElement>('input[type="file"]')!
    fireEvent.change(input, { target: { files: [file] } })
    await waitFor(() => expect(layout().column.widthPx).toBe(480))
  })

  it('asks for confirmation before resetting through the controller', () => {
    const { reset } = mount()
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.reset'] }))
    const dialog = screen.getByRole('alertdialog', { name: en['next.inspector.configurator.resetConfirmTitle'] })
    fireEvent.click(within(dialog).getByRole('button', { name: en['next.inspector.configurator.reset'] }))
    expect(reset).toHaveBeenCalledTimes(1)
  })

  it('offers no mutation controls to a viewer', () => {
    mount(true)
    expect(screen.getByText(en['next.inspector.configurator.readOnly'])).toBeTruthy()
    expect(screen.queryByPlaceholderText(en['next.inspector.configurator.presetNamePlaceholder'])).toBeNull()
    expect(screen.queryByPlaceholderText(en['next.inspector.configurator.customTitlePlaceholder'])).toBeNull()
    expect((screen.getByRole('button', { name: 'Lens: Add' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('keeps row DOM nodes mounted across visibility, collapse, and custom title edits', () => {
    const { layout } = mount()
    fireEvent.input(screen.getByPlaceholderText(en['next.inspector.configurator.customTitlePlaceholder']), {
      target: { value: 'Rig' },
    })
    fireEvent.click(screen.getByRole('button', { name: en['next.inspector.configurator.add'] }))
    const customId = layout().sections.at(-1)!.id
    const row = document.querySelector<HTMLElement>(`.nx-inspector-order-row[data-placement-id="${customId}"]`)!
    const titleInput = within(row).getByLabelText(en['next.inspector.configurator.customTitle']) as HTMLInputElement

    fireEvent.input(titleInput, { target: { value: 'Night Rig' } })
    expect(document.querySelector(`.nx-inspector-order-row[data-placement-id="${customId}"]`)).toBe(row)
    expect(within(row).getByLabelText(en['next.inspector.configurator.customTitle'])).toBe(titleInput)

    fireEvent.click(within(row).getByLabelText(en['next.inspector.configurator.collapsed']))
    expect(document.querySelector(`.nx-inspector-order-row[data-placement-id="${customId}"]`)).toBe(row)

    fireEvent.click(within(row).getByLabelText(en['next.inspector.configurator.visible']))
    expect(document.querySelector(`.nx-inspector-order-row[data-placement-id="${customId}"]`)).toBe(row)
    expect(row.classList.contains('nx-inspector-order-row-hidden')).toBe(true)
  })
})
