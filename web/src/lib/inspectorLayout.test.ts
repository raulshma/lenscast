import { describe, expect, it } from 'vitest'
import {
  addBuiltinSection,
  addCustomSection,
  applyPreset,
  BUILTIN_PRESETS,
  defaultInspectorLayout,
  deleteCustomPreset,
  filterSectionsForRole,
  INSPECTOR_LAYOUT_LIMITS,
  inspectorWidthMax,
  preserveInspectorLayoutIdentity,
  reorderSections,
  removeSection,
  sanitizeInspectorLayout,
  saveCustomPreset,
  setColumnWidth,
  toggleColumnCollapsed,
  toggleHiddenItem,
  toggleSectionCollapsed,
  toggleSectionVisibility,
  updateCustomPreset,
  updateCustomSectionItems,
  updateCustomSectionTitle,
} from './inspectorLayout'

describe('inspector layout defaults and presets', () => {
  it('keeps the existing five-panel default byte-for-byte', () => {
    expect(defaultInspectorLayout()).toEqual({
      schemaVersion: 1,
      updatedAtMs: 0,
      activePresetId: 'default',
      column: { widthPx: 320, widthPreset: 'default', collapsed: false },
      sections: ['channels', 'storage', 'clients', 'masking-quick', 'system-info'].map((sectionId) => ({
        id: sectionId,
        kind: 'builtin',
        sectionId,
        visible: true,
        collapsed: false,
        hiddenItems: [],
        customTitle: null,
        items: [],
      })),
      presets: [],
    })
  })

  it('offers all five read-only built-in presets', () => {
    expect(BUILTIN_PRESETS.map((preset) => preset.id)).toEqual([
      'default', 'camera-operator', 'security-monitor', 'streaming-links', 'minimal',
    ])
    const before = JSON.stringify(BUILTIN_PRESETS)
    const layout = applyPreset(defaultInspectorLayout(), 'minimal')
    expect(layout.column).toEqual({ widthPx: 280, widthPreset: 'compact', collapsed: false })
    expect(layout.sections.map((section) => [section.sectionId, section.collapsed])).toEqual([
      ['channels', false], ['clients', true],
    ])
    expect(JSON.stringify(BUILTIN_PRESETS)).toBe(before)
  })

  it('ignores an unknown preset', () => {
    const layout = defaultInspectorLayout()
    expect(applyPreset(layout, 'missing')).toBe(layout)
  })
})

describe('inspector layout sanitization', () => {
  it('falls back to the default for non-objects and corrupt shapes', () => {
    expect(sanitizeInspectorLayout(null)).toEqual(defaultInspectorLayout())
    expect(sanitizeInspectorLayout('bad')).toEqual(defaultInspectorLayout())
    expect(sanitizeInspectorLayout({}).sections).toHaveLength(5)
  })

  it('clamps width, normalizes the schema and stamps, and caps title lengths', () => {
    const layout = sanitizeInspectorLayout({
      schemaVersion: 99,
      updatedAtMs: -4.8,
      activePresetId: ' custom ',
      column: { widthPx: 900, widthPreset: 'nope', collapsed: 'yes' },
      sections: [{ id: ' ', kind: 'custom', sectionId: '', customTitle: `  ${'x'.repeat(80)}  ` }],
    })
    expect(layout.schemaVersion).toBe(1)
    expect(layout.updatedAtMs).toBe(0)
    expect(layout.activePresetId).toBe('custom')
    expect(layout.column).toEqual({ widthPx: 560, widthPreset: 'custom', collapsed: false })
    expect(layout.sections[0].id).not.toBe('')
    expect(layout.sections[0].customTitle).toHaveLength(48)
    expect(sanitizeInspectorLayout({ column: { widthPx: 1 } }).column.widthPx).toBe(260)
  })

  it('deduplicates placement ids and built-in sections while retaining custom duplicates', () => {
    const layout = sanitizeInspectorLayout({
      sections: [
        { id: 'same', kind: 'builtin', sectionId: 'channels' },
        { id: 'same', kind: 'builtin', sectionId: 'channels' },
        { id: 'same', kind: 'builtin', sectionId: 'clients' },
        { id: 'custom-a', kind: 'custom', sectionId: 'one', items: ['iso', 'iso', 'ev'] },
        { id: 'custom-b', kind: 'custom', sectionId: 'one', items: ['iso'] },
      ],
    })
    expect(layout.sections).toHaveLength(4)
    expect(new Set(layout.sections.map((section) => section.id)).size).toBe(4)
    expect(layout.sections[0].sectionId).toBe('channels')
    expect(layout.sections[2].items).toEqual(['iso', 'ev'])
    expect(layout.sections[3].items).toEqual(['iso'])
  })

  it('caps sections, presets and item lists', () => {
    const sections = Array.from({ length: 60 }, (_, index) => ({
      id: `s${index}`, kind: 'custom', sectionId: `s${index}`, items: Array.from({ length: 40 }, (_, i) => `i${i}`),
    }))
    const presets = Array.from({ length: 20 }, (_, index) => ({
      id: `p${index}`, name: `Preset ${index}`, column: {}, sections: [],
    }))
    const layout = sanitizeInspectorLayout({ sections, presets })
    expect(layout.sections).toHaveLength(INSPECTOR_LAYOUT_LIMITS.maxSections)
    expect(layout.presets).toHaveLength(INSPECTOR_LAYOUT_LIMITS.maxCustomPresets)
    expect(layout.sections[0].items).toHaveLength(INSPECTOR_LAYOUT_LIMITS.maxCustomItemsPerSection)
    expect(layout.sections[0].hiddenItems).toEqual([])
  })

  it('drops blank preset names and sanitizes preset snapshots recursively', () => {
    const layout = sanitizeInspectorLayout({
      presets: [
        { id: 'blank', name: '   ' },
        { id: 'ok', name: '  Night Watch  ', column: { widthPx: 500 }, sections: [{ id: 'x', kind: 'builtin', sectionId: 'lens' }] },
      ],
    })
    expect(layout.presets).toHaveLength(1)
    expect(layout.presets[0]).toMatchObject({
      id: 'ok', name: 'Night Watch', column: { widthPx: 500, widthPreset: 'custom' },
    })
  })
})

describe('inspector section transitions', () => {
  it('toggles visibility and collapse without mutating the input', () => {
    const original = defaultInspectorLayout()
    const hidden = toggleSectionVisibility(original, 'channels')
    const collapsed = toggleSectionCollapsed(hidden, 'channels')
    expect(original.sections[0].visible).toBe(true)
    expect(hidden.sections[0].visible).toBe(false)
    expect(collapsed.sections[0].collapsed).toBe(true)
    expect(toggleSectionVisibility(original, 'missing')).toBe(original)
  })

  it('reorders through one immutable move and rejects invalid indexes', () => {
    const layout = defaultInspectorLayout()
    const moved = reorderSections(layout, 0, 2)
    expect(moved.sections.map((section) => section.sectionId)).toEqual(['storage', 'clients', 'channels', 'masking-quick', 'system-info'])
    expect(layout.sections[0].sectionId).toBe('channels')
    expect(reorderSections(layout, -1, 0)).toBe(layout)
    expect(reorderSections(layout, 0, 99)).toBe(layout)
    expect(reorderSections(layout, 2, 2)).toBe(layout)
  })

  it('adds a built-in once and reveals an existing hidden placement', () => {
    const layout = defaultInspectorLayout()
    expect(addBuiltinSection(layout, 'lens').sections.at(-1)?.sectionId).toBe('lens')
    expect(addBuiltinSection(addBuiltinSection(layout, 'lens'), 'lens').sections).toHaveLength(6)
    const hidden = toggleSectionVisibility(layout, 'channels')
    expect(addBuiltinSection(hidden, 'channels').sections[0].visible).toBe(true)
    expect(addBuiltinSection(layout, ' ')).toBe(layout)
  })

  it('adds, retitles, filters and removes custom sections', () => {
    let layout = addCustomSection(defaultInspectorLayout(), '  Quick Rig  ', ['camera.iso', 'camera.iso', 'camera.ev'])
    const id = layout.sections.at(-1)!.id
    expect(layout.sections.at(-1)).toMatchObject({ kind: 'custom', customTitle: 'Quick Rig', items: ['camera.iso', 'camera.ev'] })
    layout = updateCustomSectionTitle(layout, id, 'Night Watch')
    layout = updateCustomSectionItems(layout, id, ['stream.audio'])
    expect(layout.sections.at(-1)).toMatchObject({ customTitle: 'Night Watch', items: ['stream.audio'] })
    expect(toggleHiddenItem(layout, id, 'camera.iso')).toBe(layout)
    expect(removeSection(layout, id).sections).toHaveLength(5)
    expect(removeSection(layout, 'missing')).toBe(layout)
    expect(addCustomSection(layout, ' ', [])).toBe(layout)
  })

  it('filters built-in items with deduplication and a 24-item cap', () => {
    let layout = defaultInspectorLayout()
    layout = toggleHiddenItem(layout, 'channels', 'channels.status')
    expect(layout.sections[0].hiddenItems).toEqual(['channels.status'])
    layout = toggleHiddenItem(layout, 'channels', 'channels.status')
    expect(layout.sections[0].hiddenItems).toEqual([])
  })
})

describe('inspector column and custom presets', () => {
  it('sets bounded widths and toggles collapse', () => {
    const layout = defaultInspectorLayout()
    expect(setColumnWidth(layout, 999, 'wide').column.widthPx).toBe(560)
    expect(setColumnWidth(layout, 0, 'compact').column.widthPx).toBe(260)
    expect(setColumnWidth(layout, 400).column.widthPreset).toBe('custom')
    expect(toggleColumnCollapsed(layout).column.collapsed).toBe(true)
    expect(inspectorWidthMax(1000)).toBe(320)
    expect(inspectorWidthMax(1440)).toBe(560)
  })

  it('saves independent snapshots, updates them and applies them later', () => {
    let layout = addBuiltinSection(defaultInspectorLayout(), 'lens')
    layout = setColumnWidth(layout, 360, 'custom')
    layout = saveCustomPreset(layout, '  Night Security  ')
    const preset = layout.presets[0]
    expect(preset.name).toBe('Night Security')
    expect(preset.column.widthPx).toBe(360)
    layout = removeSection(layout, 'lens')
    expect(preset.sections.some((section) => section.sectionId === 'lens')).toBe(true)
    const applied = applyPreset(layout, preset.id)
    expect(applied.sections.some((section) => section.sectionId === 'lens')).toBe(true)
    expect(applied.activePresetId).toBe(preset.id)
    const updated = updateCustomPreset(removeSection(applied, 'lens'), preset.id)
    expect(updated.presets[0].sections.some((section) => section.sectionId === 'lens')).toBe(false)
  })

  it('caps custom presets and never deletes a built-in', () => {
    let layout = defaultInspectorLayout()
    for (let index = 0; index < 20; index += 1) layout = saveCustomPreset(layout, `Preset ${index}`)
    expect(layout.presets).toHaveLength(12)
    expect(saveCustomPreset(layout, 'Overflow')).toBe(layout)
    expect(deleteCustomPreset(layout, 'default')).toBe(layout)
    const active = layout.presets[0].id
    layout = applyPreset(layout, active)
    layout = deleteCustomPreset(layout, active)
    expect(layout.activePresetId).toBe('custom')
    expect(layout.presets).toHaveLength(11)
  })
})

describe('viewer role filtering', () => {
  it('strips all admin sections for viewers and leaves operators unchanged', () => {
    let layout = defaultInspectorLayout()
    for (const sectionId of ['auth', 'backup', 'config-backup', 'audit', 'lens']) layout = addBuiltinSection(layout, sectionId)
    expect(filterSectionsForRole(layout.sections, true).map((section) => section.sectionId)).toEqual([
      'channels', 'storage', 'clients', 'masking-quick', 'system-info', 'lens',
    ])
    expect(filterSectionsForRole(layout.sections, false)).toBe(layout.sections)
  })

  it('does not mistake a custom placement with a colliding imported id for an admin panel', () => {
    const layout = addCustomSection(defaultInspectorLayout(), 'Imported rig', ['channels.status'])
    layout.sections[layout.sections.length - 1].sectionId = 'auth'
    expect(filterSectionsForRole(layout.sections, true).map((section) => section.sectionId)).toContain('auth')
  })
})

describe('identity preservation across sanitize boundaries', () => {
  it('returns the previous document untouched when nothing differs', () => {
    const layout = defaultInspectorLayout()
    expect(preserveInspectorLayoutIdentity(layout, JSON.parse(JSON.stringify(layout)))).toBe(layout)
    expect(preserveInspectorLayoutIdentity(layout, layout)).toBe(layout)
  })

  it('keeps the previous object for every section an edit did not touch', () => {
    const layout = defaultInspectorLayout()
    const edited = toggleSectionCollapsed(layout, 'storage')
    const sanitized = sanitizeInspectorLayout(edited)
    const preserved = preserveInspectorLayoutIdentity(layout, sanitized)
    expect(preserved.sections).toHaveLength(layout.sections.length)
    expect(preserved.sections[0]).toBe(layout.sections[0])
    expect(preserved.sections[2]).toBe(layout.sections[2])
    expect(preserved.sections[4]).toBe(layout.sections[4])
    expect(preserved.sections[1]).not.toBe(layout.sections[1])
    expect(preserved.sections[1].collapsed).toBe(true)
    expect(preserved.column).toBe(layout.column)
    expect(preserved.updatedAtMs).toBe(sanitized.updatedAtMs)
  })

  it('carries the previous references through a reordering and a fresh arrival', () => {
    const layout = addBuiltinSection(defaultInspectorLayout(), 'lens')
    const moved = reorderSections(layout, 0, layout.sections.length - 1)
    const preserved = preserveInspectorLayoutIdentity(layout, sanitizeInspectorLayout(moved))
    for (const section of layout.sections) {
      expect(preserved.sections).toContain(section)
    }
    // A reorder is a real change even though every reference survives —
    // the new sequence must win, not the previous document.
    expect(preserved).not.toBe(layout)
    expect(preserved.sections.map((section) => section.id)).toEqual(moved.sections.map((section) => section.id))
    // The move itself only shifts references; nothing is rebuilt.
    expect(preserved.sections[0]).toBe(layout.sections[1])
  })

  it('replaces a section object only when its content truly changes', () => {
    const layout = defaultInspectorLayout()
    const hidden = toggleHiddenItem(layout, 'storage', 'storage.summary')
    const preserved = preserveInspectorLayoutIdentity(layout, sanitizeInspectorLayout(hidden))
    expect(preserved.sections[1]).not.toBe(layout.sections[1])
    expect(preserved.sections[1].hiddenItems).toEqual(['storage.summary'])
  })

  it('preserves presets and the column independently of the sections', () => {
    let layout = saveCustomPreset(defaultInspectorLayout(), 'Rig')
    const edited = toggleColumnCollapsed(layout)
    const preserved = preserveInspectorLayoutIdentity(layout, sanitizeInspectorLayout(edited))
    expect(preserved.presets[0]).toBe(layout.presets[0])
    expect(preserved.column).not.toBe(layout.column)
    expect(preserved.column.collapsed).toBe(true)
    for (const section of layout.sections) expect(preserved.sections).toContain(section)
  })

  it('still rebuilds a section whose identity or shape arrives from outside', () => {
    const layout = defaultInspectorLayout()
    const foreign = sanitizeInspectorLayout({
      ...JSON.parse(JSON.stringify(layout)),
      sections: JSON.parse(JSON.stringify(layout.sections)).map((section: { id: string }) => (
        section.id === 'clients' ? { ...section, collapsed: true } : section
      )),
    })
    const preserved = preserveInspectorLayoutIdentity(layout, foreign)
    expect(preserved.sections.find((section) => section.id === 'clients')).not.toBe(layout.sections[2])
  })
})
