import type {
  WebUiColumnDto,
  WebUiLayoutDto,
  WebUiLayoutPresetDto,
  WebUiSectionPlacementDto,
} from '../types'

export type {
  WebUiColumnDto,
  WebUiLayoutDto,
  WebUiLayoutPresetDto,
  WebUiSectionPlacementDto,
} from '../types'

export const INSPECTOR_LAYOUT_STORAGE_KEY = 'lenscast.nextInspectorLayout'

export const INSPECTOR_LAYOUT_LIMITS = {
  schemaVersion: 1,
  columnWidthMinPx: 260,
  columnWidthDefaultPx: 320,
  columnWidthMaxPx: 560,
  maxSections: 48,
  maxCustomPresets: 12,
  maxCustomItemsPerSection: 24,
  maxTitleLength: 48,
  maxPresetNameLength: 40,
} as const

export interface BuiltinInspectorPreset {
  id: string
  name: string
  widthPx: number
  sections: Array<{ sectionId: string; collapsed?: boolean }>
}

export const ADMIN_SECTION_IDS = new Set(['auth', 'backup', 'config-backup', 'audit'])

export const DEFAULT_SECTION_IDS = ['channels', 'storage', 'clients', 'masking-quick', 'system-info'] as const

export const BUILTIN_PRESETS: readonly BuiltinInspectorPreset[] = [
  {
    id: 'default',
    name: 'Default',
    widthPx: 320,
    sections: DEFAULT_SECTION_IDS.map((sectionId) => ({ sectionId })),
  },
  {
    id: 'camera-operator',
    name: 'Camera Operator',
    widthPx: 340,
    sections: ['channels', 'lens', 'exposure', 'focus', 'white-balance', 'zoom-frame', 'effects', 'night-vision', 'overlay']
      .map((sectionId) => ({ sectionId })),
  },
  {
    id: 'security-monitor',
    name: 'Security Monitor',
    widthPx: 380,
    sections: ['channels', 'event-log', 'detection-stats', 'security', 'recording', 'clients', 'masking-quick']
      .map((sectionId) => ({ sectionId })),
  },
  {
    id: 'streaming-links',
    name: 'Streaming & Links',
    widthPx: 340,
    sections: ['channels', 'web-stream', 'audio', 'rtsp', 'whip', 'rtmp', 'clients', 'share']
      .map((sectionId) => ({ sectionId })),
  },
  {
    id: 'minimal',
    name: 'Minimal',
    widthPx: 280,
    sections: [{ sectionId: 'channels' }, { sectionId: 'clients', collapsed: true }],
  },
] as const

const WIDTH_PRESETS: ReadonlyArray<{ preset: WebUiColumnDto['widthPreset']; widthPx: number }> = [
  { preset: 'compact', widthPx: 280 },
  { preset: 'default', widthPx: 320 },
  { preset: 'wide', widthPx: 420 },
]

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(value: unknown, maxLength: number): string {
  return typeof value === 'string' ? value.trim().slice(0, maxLength) : ''
}

function finiteNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function clampWidth(value: unknown): number {
  return Math.round(Math.min(
    INSPECTOR_LAYOUT_LIMITS.columnWidthMaxPx,
    Math.max(INSPECTOR_LAYOUT_LIMITS.columnWidthMinPx, finiteNumber(value, INSPECTOR_LAYOUT_LIMITS.columnWidthDefaultPx)),
  ))
}

function widthPreset(value: unknown, widthPx: number): WebUiColumnDto['widthPreset'] {
  if (value === 'compact' || value === 'default' || value === 'wide' || value === 'custom') return value
  return WIDTH_PRESETS.find((entry) => entry.widthPx === widthPx)?.preset ?? 'custom'
}

function stringList(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  const seen = new Set<string>()
  const result: string[] = []
  for (const entry of value) {
    const id = text(entry, 96)
    if (!id || seen.has(id)) continue
    seen.add(id)
    result.push(id)
    if (result.length === INSPECTOR_LAYOUT_LIMITS.maxCustomItemsPerSection) break
  }
  return result
}

function newId(prefix: string): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return `${prefix}_${uuid ?? `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`}`
}

function uniqueId(value: unknown, used: Set<string>, prefix: string): string {
  const candidate = text(value, 96)
  if (candidate && !used.has(candidate)) {
    used.add(candidate)
    return candidate
  }
  let minted = newId(prefix)
  while (used.has(minted)) minted = newId(prefix)
  used.add(minted)
  return minted
}

function sanitizeColumn(raw: unknown): WebUiColumnDto {
  const value = record(raw) ? raw : {}
  const widthPx = clampWidth(value.widthPx)
  return {
    widthPx,
    widthPreset: widthPreset(value.widthPreset, widthPx),
    collapsed: value.collapsed === true,
  }
}

function sanitizeSections(raw: unknown): WebUiSectionPlacementDto[] {
  if (!Array.isArray(raw)) return defaultSections()
  const usedIds = new Set<string>()
  const usedBuiltins = new Set<string>()
  const sections: WebUiSectionPlacementDto[] = []
  for (const entry of raw) {
    if (!record(entry)) continue
    const kind = entry.kind === 'custom' ? 'custom' : 'builtin'
    const sectionId = text(entry.sectionId, 96)
    if (kind === 'builtin') {
      if (!sectionId || usedBuiltins.has(sectionId)) continue
      usedBuiltins.add(sectionId)
    }
    const id = uniqueId(entry.id, usedIds, kind === 'custom' ? 'custom' : 'section')
    sections.push({
      id,
      kind,
      sectionId: kind === 'builtin' ? sectionId : sectionId || `custom-${id}`,
      visible: entry.visible !== false,
      collapsed: entry.collapsed === true,
      hiddenItems: kind === 'builtin' ? stringList(entry.hiddenItems) : [],
      customTitle: text(entry.customTitle, INSPECTOR_LAYOUT_LIMITS.maxTitleLength) || null,
      items: kind === 'custom' ? stringList(entry.items) : [],
    })
    if (sections.length === INSPECTOR_LAYOUT_LIMITS.maxSections) break
  }
  return sections
}

function sanitizePresets(raw: unknown): WebUiLayoutPresetDto[] {
  if (!Array.isArray(raw)) return []
  const usedIds = new Set<string>()
  const presets: WebUiLayoutPresetDto[] = []
  for (const entry of raw) {
    if (!record(entry)) continue
    const name = text(entry.name, INSPECTOR_LAYOUT_LIMITS.maxPresetNameLength)
    if (!name) continue
    presets.push({
      id: uniqueId(entry.id, usedIds, 'preset'),
      name,
      column: sanitizeColumn(entry.column),
      sections: sanitizeSections(entry.sections),
    })
    if (presets.length === INSPECTOR_LAYOUT_LIMITS.maxCustomPresets) break
  }
  return presets
}

function defaultSections(): WebUiSectionPlacementDto[] {
  return DEFAULT_SECTION_IDS.map((sectionId) => ({
    id: sectionId,
    kind: 'builtin',
    sectionId,
    visible: true,
    collapsed: false,
    hiddenItems: [],
    customTitle: null,
    items: [],
  }))
}

export function defaultInspectorLayout(): WebUiLayoutDto {
  return {
    schemaVersion: INSPECTOR_LAYOUT_LIMITS.schemaVersion,
    updatedAtMs: 0,
    activePresetId: 'default',
    column: { widthPx: 320, widthPreset: 'default', collapsed: false },
    sections: defaultSections(),
    presets: [],
  }
}

export function sanitizeInspectorLayout(raw: unknown): WebUiLayoutDto {
  if (!record(raw)) return defaultInspectorLayout()
  return {
    schemaVersion: INSPECTOR_LAYOUT_LIMITS.schemaVersion,
    updatedAtMs: Math.max(0, Math.floor(finiteNumber(raw.updatedAtMs, 0))),
    activePresetId: text(raw.activePresetId, 64) || 'default',
    column: sanitizeColumn(raw.column),
    sections: sanitizeSections(raw.sections),
    presets: sanitizePresets(raw.presets),
  }
}

function copySections(sections: WebUiSectionPlacementDto[]): WebUiSectionPlacementDto[] {
  return sections.map((section) => ({ ...section, hiddenItems: [...section.hiddenItems], items: [...section.items] }))
}

function replaceSection(
  layout: WebUiLayoutDto,
  placementId: string,
  transform: (section: WebUiSectionPlacementDto) => WebUiSectionPlacementDto,
): WebUiLayoutDto {
  let changed = false
  const sections = layout.sections.map((section) => {
    if (section.id !== placementId) return section
    const next = transform(section)
    changed ||= next !== section
    return next
  })
  return changed ? { ...layout, sections } : layout
}

export function toggleSectionVisibility(layout: WebUiLayoutDto, placementId: string): WebUiLayoutDto {
  return replaceSection(layout, placementId, (section) => ({ ...section, visible: !section.visible }))
}

export function toggleSectionCollapsed(layout: WebUiLayoutDto, placementId: string): WebUiLayoutDto {
  return replaceSection(layout, placementId, (section) => ({ ...section, collapsed: !section.collapsed }))
}

export function reorderSections(layout: WebUiLayoutDto, fromIndex: number, toIndex: number): WebUiLayoutDto {
  const last = layout.sections.length - 1
  if (fromIndex < 0 || fromIndex > last || toIndex < 0 || toIndex > last || fromIndex === toIndex) return layout
  const sections = [...layout.sections]
  const [moved] = sections.splice(fromIndex, 1)
  sections.splice(toIndex, 0, moved)
  return { ...layout, sections }
}

export function addBuiltinSection(layout: WebUiLayoutDto, sectionId: string): WebUiLayoutDto {
  const id = text(sectionId, 96)
  if (!id) return layout
  const existingIndex = layout.sections.findIndex((section) => section.kind === 'builtin' && section.sectionId === id)
  if (existingIndex >= 0) {
    if (layout.sections[existingIndex].visible) return layout
    return replaceSection(layout, layout.sections[existingIndex].id, (section) => ({ ...section, visible: true }))
  }
  if (layout.sections.length >= INSPECTOR_LAYOUT_LIMITS.maxSections) return layout
  return {
    ...layout,
    sections: [...layout.sections, {
      id,
      kind: 'builtin',
      sectionId: id,
      visible: true,
      collapsed: false,
      hiddenItems: [],
      customTitle: null,
      items: [],
    }],
  }
}

export function addCustomSection(layout: WebUiLayoutDto, title: string, itemIds: string[]): WebUiLayoutDto {
  const customTitle = text(title, INSPECTOR_LAYOUT_LIMITS.maxTitleLength)
  if (!customTitle || layout.sections.length >= INSPECTOR_LAYOUT_LIMITS.maxSections) return layout
  const id = newId('custom')
  return {
    ...layout,
    sections: [...layout.sections, {
      id,
      kind: 'custom',
      sectionId: `custom-${id}`,
      visible: true,
      collapsed: false,
      hiddenItems: [],
      customTitle,
      items: stringList(itemIds),
    }],
  }
}

export function removeSection(layout: WebUiLayoutDto, placementId: string): WebUiLayoutDto {
  const sections = layout.sections.filter((section) => section.id !== placementId)
  return sections.length === layout.sections.length ? layout : { ...layout, sections }
}

export function toggleHiddenItem(layout: WebUiLayoutDto, placementId: string, itemId: string): WebUiLayoutDto {
  return replaceSection(layout, placementId, (section) => {
    if (section.kind !== 'builtin') return section
    const hiddenItems = section.hiddenItems.includes(itemId)
      ? section.hiddenItems.filter((id) => id !== itemId)
      : stringList([...section.hiddenItems, itemId])
    return { ...section, hiddenItems }
  })
}

export function updateCustomSectionItems(
  layout: WebUiLayoutDto,
  placementId: string,
  itemIds: string[],
): WebUiLayoutDto {
  return replaceSection(layout, placementId, (section) => (
    section.kind === 'custom' ? { ...section, items: stringList(itemIds) } : section
  ))
}

export function updateCustomSectionTitle(layout: WebUiLayoutDto, placementId: string, title: string): WebUiLayoutDto {
  return replaceSection(layout, placementId, (section) => (
    section.kind === 'custom' ? { ...section, customTitle: text(title, INSPECTOR_LAYOUT_LIMITS.maxTitleLength) || null } : section
  ))
}

export function setColumnWidth(
  layout: WebUiLayoutDto,
  widthPx: number,
  preset: WebUiColumnDto['widthPreset'] = 'custom',
): WebUiLayoutDto {
  const width = clampWidth(widthPx)
  if (layout.column.widthPx === width && layout.column.widthPreset === preset) return layout
  return { ...layout, column: { ...layout.column, widthPx: width, widthPreset: preset } }
}

export function toggleColumnCollapsed(layout: WebUiLayoutDto): WebUiLayoutDto {
  return { ...layout, column: { ...layout.column, collapsed: !layout.column.collapsed } }
}

function presetSections(preset: BuiltinInspectorPreset): WebUiSectionPlacementDto[] {
  return preset.sections.map(({ sectionId, collapsed = false }) => ({
    id: sectionId,
    kind: 'builtin',
    sectionId,
    visible: true,
    collapsed,
    hiddenItems: [],
    customTitle: null,
    items: [],
  }))
}

export function applyPreset(layout: WebUiLayoutDto, presetId: string): WebUiLayoutDto {
  const builtin = BUILTIN_PRESETS.find((preset) => preset.id === presetId)
  if (builtin) {
    return {
      ...layout,
      activePresetId: builtin.id,
      column: { widthPx: builtin.widthPx, widthPreset: widthPreset(undefined, builtin.widthPx), collapsed: false },
      sections: presetSections(builtin),
    }
  }
  const custom = layout.presets.find((preset) => preset.id === presetId)
  if (!custom) return layout
  return {
    ...layout,
    activePresetId: custom.id,
    column: { ...custom.column },
    sections: copySections(custom.sections),
  }
}

export function saveCustomPreset(layout: WebUiLayoutDto, name: string): WebUiLayoutDto {
  const presetName = text(name, INSPECTOR_LAYOUT_LIMITS.maxPresetNameLength)
  if (!presetName || layout.presets.length >= INSPECTOR_LAYOUT_LIMITS.maxCustomPresets) return layout
  const id = newId('preset')
  return {
    ...layout,
    activePresetId: id,
    presets: [...layout.presets, {
      id,
      name: presetName,
      column: { ...layout.column },
      sections: copySections(layout.sections),
    }],
  }
}

export function updateCustomPreset(layout: WebUiLayoutDto, presetId: string): WebUiLayoutDto {
  let changed = false
  const presets = layout.presets.map((preset) => {
    if (preset.id !== presetId) return preset
    changed = true
    return { ...preset, column: { ...layout.column }, sections: copySections(layout.sections) }
  })
  return changed ? { ...layout, activePresetId: presetId, presets } : layout
}

export function deleteCustomPreset(layout: WebUiLayoutDto, presetId: string): WebUiLayoutDto {
  if (BUILTIN_PRESETS.some((preset) => preset.id === presetId)) return layout
  const presets = layout.presets.filter((preset) => preset.id !== presetId)
  if (presets.length === layout.presets.length) return layout
  return { ...layout, presets, activePresetId: layout.activePresetId === presetId ? 'custom' : layout.activePresetId }
}

export function filterSectionsForRole(
  sections: WebUiSectionPlacementDto[],
  isViewer: boolean,
): WebUiSectionPlacementDto[] {
  return isViewer
    ? sections.filter((section) => section.kind !== 'builtin' || !ADMIN_SECTION_IDS.has(section.sectionId))
    : sections
}

export function inspectorWidthMax(viewportWidth: number): number {
  return Math.max(
    INSPECTOR_LAYOUT_LIMITS.columnWidthMinPx,
    Math.min(INSPECTOR_LAYOUT_LIMITS.columnWidthMaxPx, viewportWidth - 680),
  )
}

export function inspectorLayoutsEqual(a: WebUiLayoutDto, b: WebUiLayoutDto): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

function stringListEquals(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((entry, index) => entry === b[index])
}

function sectionsEqual(a: WebUiSectionPlacementDto, b: WebUiSectionPlacementDto): boolean {
  return a.id === b.id
    && a.kind === b.kind
    && a.sectionId === b.sectionId
    && a.visible === b.visible
    && a.collapsed === b.collapsed
    && a.customTitle === b.customTitle
    && stringListEquals(a.hiddenItems, b.hiddenItems)
    && stringListEquals(a.items, b.items)
}

function columnsEqual(a: WebUiColumnDto, b: WebUiColumnDto): boolean {
  return a.widthPx === b.widthPx && a.widthPreset === b.widthPreset && a.collapsed === b.collapsed
}

function presetsEqual(a: WebUiLayoutPresetDto, b: WebUiLayoutPresetDto): boolean {
  return a.id === b.id
    && a.name === b.name
    && columnsEqual(a.column, b.column)
    && a.sections.length === b.sections.length
    && a.sections.every((section, index) => sectionsEqual(section, b.sections[index]))
}

/**
 * Re-unite a freshly sanitized document with the objects it was derived from.
 * Sanitizing rebuilds every section, preset and column as a new object, and
 * Solid's `<For>` reconciles by reference — handing it all-new objects on a
 * one-section edit would unmount and remount every panel in the column (each
 * panel re-fetches on mount, and the momentarily shrunken content yanks the
 * scroller). So every controller write passes through here: whatever is
 * content-equal to the previous document keeps the previous reference, and
 * only what truly changed gets a new one.
 */
export function preserveInspectorLayoutIdentity(
  previous: WebUiLayoutDto,
  next: WebUiLayoutDto,
): WebUiLayoutDto {
  const column = columnsEqual(previous.column, next.column) ? previous.column : next.column
  const previousSections = new Map(previous.sections.map((section) => [section.id, section]))
  let sectionsChanged = false
  const sections = next.sections.map((section) => {
    const prior = previousSections.get(section.id)
    if (prior && sectionsEqual(prior, section)) return prior
    sectionsChanged = true
    return section
  })
  const previousPresets = new Map(previous.presets.map((preset) => [preset.id, preset]))
  let presetsChanged = false
  const presets = next.presets.map((preset) => {
    const prior = previousPresets.get(preset.id)
    if (prior && presetsEqual(prior, preset)) return prior
    presetsChanged = true
    return preset
  })
  // Content-equal sections in a different order are still a change — a
  // reorder must keep the new sequence even though every reference is
  // preserved — so "untouched" compares the id sequences too.
  const sameSectionOrder = previous.sections.length === next.sections.length
    && previous.sections.every((section, index) => section.id === next.sections[index].id)
  const samePresetOrder = previous.presets.length === next.presets.length
    && previous.presets.every((preset, index) => preset.id === next.presets[index].id)
  const untouched = column === previous.column
    && !sectionsChanged
    && !presetsChanged
    && sameSectionOrder
    && samePresetOrder
    && previous.activePresetId === next.activePresetId
    && previous.schemaVersion === next.schemaVersion
    && previous.updatedAtMs === next.updatedAtMs
  if (untouched) return previous
  return { ...next, column, sections, presets }
}
