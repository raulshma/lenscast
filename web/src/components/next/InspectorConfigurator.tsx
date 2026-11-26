import { createMemo, createSignal, For, Show } from 'solid-js'
import type { NextState } from './NextState'
import type { InspectorLayoutController } from '../../hooks/useInspectorLayout'
import { t } from '../../lib/i18n'
import {
  addBuiltinSection,
  addCustomSection,
  applyPreset,
  BUILTIN_PRESETS,
  deleteCustomPreset,
  inspectorWidthMax,
  INSPECTOR_LAYOUT_LIMITS,
  removeSection,
  reorderSections,
  saveCustomPreset,
  setColumnWidth,
  toggleHiddenItem,
  toggleSectionCollapsed,
  toggleSectionVisibility,
  updateCustomPreset,
  updateCustomSectionItems,
  updateCustomSectionTitle,
} from '../../lib/inspectorLayout'
import type { WebUiLayoutPresetDto, WebUiSectionPlacementDto } from '../../types'
import Button from './ui/Button'
import ConfirmDialog from './ui/ConfirmDialog'
import Drawer from './ui/Drawer'
import { pushToast } from './ui'
import {
  INSPECTOR_CATEGORIES,
  INSPECTOR_SECTIONS,
  inspectorSection,
  inspectorSectionTitle,
  type InspectorCategory,
} from './views/live/inspectorRegistry'
import { controlItem, customControlItems, type ControlItemDefinition } from './views/live/controlItemCatalog'

interface Props {
  open: boolean
  onClose: () => void
  state: NextState
  layout: InspectorLayoutController
}

type CategoryFilter = InspectorCategory | 'all'

export default function InspectorConfigurator(props: Props) {
  const [sectionQuery, setSectionQuery] = createSignal('')
  const [category, setCategory] = createSignal<CategoryFilter>('all')
  const [controlQuery, setControlQuery] = createSignal('')
  const [expandedPlacementId, setExpandedPlacementId] = createSignal<string | null>(null)
  const [selectedCustomId, setSelectedCustomId] = createSignal<string | null>(null)
  const [customTitle, setCustomTitle] = createSignal('')
  const [presetName, setPresetName] = createSignal('')
  const [draggedId, setDraggedId] = createSignal<string | null>(null)
  const [dragOverId, setDragOverId] = createSignal<string | null>(null)
  const [grabbedId, setGrabbedId] = createSignal<string | null>(null)
  const [confirmReset, setConfirmReset] = createSignal(false)
  const [deletePreset, setDeletePreset] = createSignal<WebUiLayoutPresetDto | null>(null)
  let importInput: HTMLInputElement | undefined

  const readOnly = () => props.state.isViewer()
  const widthMax = () => inspectorWidthMax(window.innerWidth)
  const filteredSections = createMemo(() => {
    const needle = sectionQuery().trim().toLocaleLowerCase()
    return INSPECTOR_SECTIONS.filter((section) => {
      if (category() !== 'all' && section.category !== category()) return false
      return !needle || [section.id, section.titleKey, ...section.keywords, t(section.titleKey)]
        .join(' ')
        .toLocaleLowerCase()
        .includes(needle)
    })
  })
  const filteredControls = createMemo(() => {
    const needle = controlQuery().trim().toLocaleLowerCase()
    return customControlItems().filter((item) => {
      const itemCategory = controlCategory(item)
      if (category() !== 'all' && itemCategory !== category()) return false
      return !needle || [item.id, item.labelKey, t(item.labelKey), ...item.keywords]
        .join(' ')
        .toLocaleLowerCase()
        .includes(needle)
    })
  })
  const selectedCustom = () => props.layout.layout().sections.find(
    (section) => section.id === selectedCustomId() && section.kind === 'custom',
  )
  const activeCustomPreset = () => props.layout.layout().presets.find(
    (preset) => preset.id === props.layout.layout().activePresetId,
  )
  const customPresetMissing = () => props.layout.layout().activePresetId === 'custom'
    || (!BUILTIN_PRESETS.some((preset) => preset.id === props.layout.layout().activePresetId)
      && !activeCustomPreset())

  function sectionTitle(section: WebUiSectionPlacementDto): string {
    return section.customTitle || t(inspectorSectionTitle(section.sectionId))
  }

  function moveSection(placementId: string, delta: number): void {
    if (readOnly()) return
    const sections = props.layout.layout().sections
    const from = sections.findIndex((section) => section.id === placementId)
    props.layout.mutate((current) => reorderSections(current, from, from + delta))
  }

  function dropSection(targetId: string): void {
    const sourceId = draggedId()
    setDraggedId(null)
    setDragOverId(null)
    if (!sourceId || sourceId === targetId || readOnly()) return
    const sections = props.layout.layout().sections
    const from = sections.findIndex((section) => section.id === sourceId)
    const to = sections.findIndex((section) => section.id === targetId)
    props.layout.mutate((current) => reorderSections(current, from, to))
  }

  function handleReorderKey(event: KeyboardEvent, section: WebUiSectionPlacementDto): void {
    if (readOnly()) return
    if (event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault()
      setGrabbedId((current) => current === section.id ? null : section.id)
      return
    }
    if (event.key === 'Escape') {
      setGrabbedId(null)
      return
    }
    if ((event.key !== 'ArrowUp' && event.key !== 'ArrowDown') || grabbedId() !== section.id) return
    event.preventDefault()
    moveSection(section.id, event.key === 'ArrowUp' ? -1 : 1)
  }

  function toggleItem(placementId: string, itemId: string): void {
    if (readOnly()) return
    const placement = props.layout.layout().sections.find((section) => section.id === placementId)
    if (!placement) return
    if (placement.kind === 'custom') {
      const items = placement.items.includes(itemId)
        ? placement.items.filter((id) => id !== itemId)
        : [...placement.items, itemId]
      props.layout.mutate((current) => updateCustomSectionItems(current, placementId, items))
    } else {
      props.layout.mutate((current) => toggleHiddenItem(current, placementId, itemId))
    }
  }

  function createCustom(): void {
    if (readOnly() || !customTitle().trim()) return
    props.layout.mutate((current) => addCustomSection(current, customTitle(), []))
    setCustomTitle('')
  }

  function exportLayout(): void {
    const blob = new Blob([JSON.stringify(props.layout.layout(), null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'lenscast-inspector-layout.json'
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
    pushToast('success', t('next.inspector.configurator.exported'))
  }

  async function importLayout(file: File): Promise<void> {
    if (readOnly()) return
    try {
      const raw: unknown = JSON.parse(await file.text())
      props.layout.replaceLayout(raw)
      setSelectedCustomId(null)
      setExpandedPlacementId(null)
      pushToast('success', t('next.inspector.configurator.imported'))
    } catch {
      pushToast('danger', t('next.inspector.configurator.importInvalid'))
    } finally {
      if (importInput) importInput.value = ''
    }
  }

  function savePreset(): void {
    if (readOnly() || !presetName().trim()) return
    props.layout.mutate((current) => saveCustomPreset(current, presetName()))
    setPresetName('')
  }

  return (
    <>
      <Drawer
        open={props.open}
        onClose={props.onClose}
        title={t('next.inspector.configurator.title')}
        class="nx-drawer-configurator"
      >
        <div class="nx-inspector-config">
          <Show when={props.layout.error()}>
            <div class="nx-inspector-config-error" role="alert">{props.layout.error()}</div>
          </Show>
          <Show when={props.layout.localOnly()}>
            <div class="nx-inspector-config-notice">{t('next.inspector.configurator.localOnly')}</div>
          </Show>
          <Show when={readOnly()}>
            <div class="nx-inspector-config-notice">{t('next.inspector.configurator.readOnly')}</div>
          </Show>

          <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-presets-title">
            <h3 id="nx-inspector-presets-title">{t('next.inspector.configurator.presets')}</h3>
            <div class="nx-inspector-config-row">
              <select
                class="nx-input nx-select"
                aria-label={t('next.inspector.configurator.presets')}
                value={props.layout.layout().activePresetId}
                disabled={readOnly()}
                onChange={(event) => props.layout.mutate((current) => applyPreset(current, event.currentTarget.value))}
              >
                <For each={BUILTIN_PRESETS}>
                  {(preset) => <option value={preset.id}>{builtinPresetName(preset.id)}</option>}
                </For>
                <Show when={customPresetMissing()}>
                  <option value={props.layout.layout().activePresetId}>{t('next.inspector.preset.custom')}</option>
                </Show>
                <For each={props.layout.layout().presets}>
                  {(preset) => <option value={preset.id}>{preset.name}</option>}
                </For>
              </select>
            </div>
            <Show when={!readOnly()}>
              <div class="nx-inspector-config-row">
                <input
                  class="nx-input"
                  type="text"
                  maxlength={INSPECTOR_LAYOUT_LIMITS.maxPresetNameLength}
                  placeholder={t('next.inspector.configurator.presetNamePlaceholder')}
                  aria-label={t('next.inspector.configurator.presetNamePlaceholder')}
                  value={presetName()}
                  onInput={(event) => setPresetName(event.currentTarget.value)}
                  onKeyDown={(event) => { if (event.key === 'Enter') savePreset() }}
                />
                <Button size="sm" onClick={savePreset} disabled={!presetName().trim()}>
                  {t('next.inspector.configurator.savePreset')}
                </Button>
              </div>
              <div class="nx-inspector-button-row">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={!activeCustomPreset()}
                  onClick={() => props.layout.mutate((current) => updateCustomPreset(current, current.activePresetId))}
                >
                  {t('next.inspector.configurator.updatePreset')}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={!activeCustomPreset()}
                  onClick={() => setDeletePreset(activeCustomPreset() ?? null)}
                >
                  {t('next.inspector.configurator.deletePreset')}
                </Button>
              </div>
            </Show>
          </section>

          <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-width-title">
            <h3 id="nx-inspector-width-title">{t('next.inspector.configurator.width')}</h3>
            <div class="nx-inspector-width-presets">
              <For each={[280, 320, 420]}>
                {(width) => (
                  <Button
                    size="sm"
                    variant={props.layout.layout().column.widthPx === width ? 'primary' : 'secondary'}
                    disabled={readOnly()}
                    onClick={() => props.layout.mutate((current) => setColumnWidth(current, width, width === 280 ? 'compact' : width === 320 ? 'default' : 'wide'))}
                  >
                    {width}px
                  </Button>
                )}
              </For>
            </div>
            <input
              class="nx-range nx-inspector-width-range"
              type="range"
              min={INSPECTOR_LAYOUT_LIMITS.columnWidthMinPx}
              max={widthMax()}
              step="20"
              value={props.layout.layout().column.widthPx}
              aria-label={t('next.inspector.configurator.width')}
              disabled={readOnly()}
              onInput={(event) => props.layout.mutate((current) => setColumnWidth(current, Number(event.currentTarget.value)))}
            />
            <div class="nx-inspector-range-labels">
              <span>{INSPECTOR_LAYOUT_LIMITS.columnWidthMinPx}px</span>
              <span>{props.layout.layout().column.widthPx}px</span>
              <span>{widthMax()}px</span>
            </div>
          </section>

          <div class="nx-inspector-config-columns">
            <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-order-title">
              <h3 id="nx-inspector-order-title">{t('next.inspector.configurator.order')}</h3>
              <p class="nx-inspector-config-hint">{t('next.inspector.configurator.reorderHint')}</p>
              <div class="nx-inspector-order-list">
                <For each={props.layout.layout().sections}>
                  {(section, index) => (
                    <div
                      class="nx-inspector-order-row"
                      classList={{
                        'nx-inspector-order-row-drag': dragOverId() === section.id,
                        'nx-inspector-order-row-grabbed': grabbedId() === section.id,
                        'nx-inspector-order-row-hidden': !section.visible,
                      }}
                      data-placement-id={section.id}
                      draggable={!readOnly()}
                      onDragStart={(event) => {
                        setDraggedId(section.id)
                        event.dataTransfer?.setData('text/plain', section.id)
                        if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
                      }}
                      onDragOver={(event) => {
                        event.preventDefault()
                        setDragOverId(section.id)
                        if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
                      }}
                      onDragLeave={() => setDragOverId((current) => current === section.id ? null : current)}
                      onDrop={(event) => {
                        event.preventDefault()
                        dropSection(section.id)
                      }}
                      onDragEnd={() => {
                        setDraggedId(null)
                        setDragOverId(null)
                      }}
                      onKeyDown={(event) => handleReorderKey(event, section)}
                    >
                      <button
                        type="button"
                        class="nx-inspector-drag-handle"
                        aria-label={t('next.inspector.configurator.grab', { title: sectionTitle(section) })}
                        aria-pressed={grabbedId() === section.id}
                        disabled={readOnly()}
                        onClick={() => setGrabbedId((current) => current === section.id ? null : section.id)}
                      >
                        <span aria-hidden="true">⠿</span>
                      </button>
                      <div class="nx-inspector-order-main">
                        <Show
                          when={section.kind === 'builtin'}
                          fallback={
                            <input
                              class="nx-input nx-inspector-title-input"
                              type="text"
                              maxlength={INSPECTOR_LAYOUT_LIMITS.maxTitleLength}
                              aria-label={t('next.inspector.configurator.customTitle')}
                              value={section.customTitle ?? ''}
                              disabled={readOnly()}
                              onInput={(event) => props.layout.mutate((current) => updateCustomSectionTitle(current, section.id, event.currentTarget.value))}
                            />
                          }
                        >
                          <span class="nx-inspector-order-title">{sectionTitle(section)}</span>
                        </Show>
                        <span class="nx-inspector-order-meta">
                          {section.kind === 'custom'
                            ? t('next.inspector.configurator.pinnedCount', { count: section.items.length })
                            : t(`next.inspector.configurator.category.${inspectorSection(section.sectionId)?.category ?? 'camera'}`)}
                        </span>
                      </div>
                      <div class="nx-inspector-order-actions">
                        <button
                          type="button"
                          class="nx-icon-text-button"
                          aria-label={t('next.inspector.configurator.moveUp', { title: sectionTitle(section) })}
                          disabled={readOnly() || index() === 0}
                          onClick={() => moveSection(section.id, -1)}
                        >
                          <span aria-hidden="true">↑</span>
                        </button>
                        <button
                          type="button"
                          class="nx-icon-text-button"
                          aria-label={t('next.inspector.configurator.moveDown', { title: sectionTitle(section) })}
                          disabled={readOnly() || index() === props.layout.layout().sections.length - 1}
                          onClick={() => moveSection(section.id, 1)}
                        >
                          <span aria-hidden="true">↓</span>
                        </button>
                        <label class="nx-inspector-check">
                          <input
                            type="checkbox"
                            checked={section.visible}
                            disabled={readOnly()}
                            onChange={() => props.layout.mutate((current) => toggleSectionVisibility(current, section.id))}
                          />
                          <span>{t('next.inspector.configurator.visible')}</span>
                        </label>
                        <label class="nx-inspector-check">
                          <input
                            type="checkbox"
                            checked={section.collapsed}
                            disabled={readOnly()}
                            onChange={() => props.layout.mutate((current) => toggleSectionCollapsed(current, section.id))}
                          />
                          <span>{t('next.inspector.configurator.collapsed')}</span>
                        </label>
                        <Button
                          size="sm"
                          variant="ghost"
                          ariaLabel={t('next.inspector.configurator.items', { title: sectionTitle(section) })}
                          ariaExpanded={expandedPlacementId() === section.id || selectedCustomId() === section.id}
                          onClick={() => {
                            if (section.kind === 'custom') {
                              setSelectedCustomId(section.id)
                              setExpandedPlacementId(null)
                            } else {
                              setExpandedPlacementId((current) => current === section.id ? null : section.id)
                              setSelectedCustomId(null)
                            }
                          }}
                        >
                          {section.kind === 'custom' ? t('next.inspector.configurator.configureItems') : t('next.inspector.configurator.items', { title: sectionTitle(section) })}
                        </Button>
                        <button
                          type="button"
                          class="nx-icon-text-button nx-icon-text-button-danger"
                          aria-label={t('next.inspector.configurator.remove', { title: sectionTitle(section) })}
                          disabled={readOnly()}
                          onClick={() => {
                            props.layout.mutate((current) => removeSection(current, section.id))
                            if (selectedCustomId() === section.id) setSelectedCustomId(null)
                            if (expandedPlacementId() === section.id) setExpandedPlacementId(null)
                          }}
                        >
                          <span aria-hidden="true">×</span>
                        </button>
                      </div>
                    </div>
                  )}
                </For>
              </div>
            </section>

            <div class="nx-inspector-config-catalog">
              <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-catalog-title">
                <h3 id="nx-inspector-catalog-title">{t('next.inspector.configurator.catalog')}</h3>
                <label class="nx-inspector-search">
                  <span>{t('next.inspector.configurator.searchSections')}</span>
                  <input
                    class="nx-input"
                    type="search"
                    value={sectionQuery()}
                    onInput={(event) => setSectionQuery(event.currentTarget.value)}
                  />
                </label>
                <select
                  class="nx-input nx-select"
                  aria-label={t('next.inspector.configurator.category')}
                  value={category()}
                  onChange={(event) => setCategory(event.currentTarget.value as CategoryFilter)}
                >
                  <option value="all">{t('next.inspector.configurator.category.all')}</option>
                  <For each={INSPECTOR_CATEGORIES}>
                    {(item) => <option value={item}>{t(`next.inspector.configurator.category.${item}`)}</option>}
                  </For>
                </select>
                <div class="nx-inspector-catalog-list">
                  <For each={filteredSections()}>
                    {(section) => {
                      const placement = () => props.layout.layout().sections.find(
                        (entry) => entry.kind === 'builtin' && entry.sectionId === section.id,
                      )
                      const added = () => placement()?.visible === true
                      return (
                        <button
                          type="button"
                          class="nx-inspector-catalog-item"
                          aria-label={`${t(section.titleKey)}: ${added() ? t('next.inspector.configurator.added') : t('next.inspector.configurator.add')}`}
                          disabled={readOnly() || added()}
                          onClick={() => props.layout.mutate((current) => addBuiltinSection(current, section.id))}
                        >
                          <span>{t(section.titleKey)}</span>
                          <span>{added() ? t('next.inspector.configurator.added') : t('next.inspector.configurator.add')}</span>
                        </button>
                      )
                    }}
                  </For>
                  <Show when={filteredSections().length === 0}>
                    <p class="nx-inspector-empty">{t('next.inspector.configurator.noResults')}</p>
                  </Show>
                </div>
              </section>

              <Show when={selectedCustom()}>
                {(custom) => (
                  <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-controls-title">
                    <h3 id="nx-inspector-controls-title">{custom().customTitle || t('next.inspector.configurator.pinnedControls')}</h3>
                    <label class="nx-inspector-search">
                      <span>{t('next.inspector.configurator.searchItems')}</span>
                      <input
                        class="nx-input"
                        type="search"
                        value={controlQuery()}
                        onInput={(event) => setControlQuery(event.currentTarget.value)}
                      />
                    </label>
                    <div class="nx-inspector-control-list">
                      <For each={filteredControls()}>
                        {(item) => (
                          <label class="nx-inspector-control-item">
                            <input
                              type="checkbox"
                              checked={custom().items.includes(item.id)}
                              disabled={readOnly() || custom().items.length >= INSPECTOR_LAYOUT_LIMITS.maxCustomItemsPerSection && !custom().items.includes(item.id)}
                              onChange={() => toggleItem(custom().id, item.id)}
                            />
                            <span>{t(item.labelKey)}</span>
                          </label>
                        )}
                      </For>
                      <Show when={filteredControls().length === 0}>
                        <p class="nx-inspector-empty">{t('next.inspector.configurator.noResults')}</p>
                      </Show>
                    </div>
                  </section>
                )}
              </Show>

              <Show when={!readOnly()}>
                <section class="nx-inspector-config-section" aria-labelledby="nx-inspector-custom-title">
                  <h3 id="nx-inspector-custom-title">{t('next.inspector.configurator.addCustom')}</h3>
                  <div class="nx-inspector-config-row">
                    <input
                      class="nx-input"
                      type="text"
                      maxlength={INSPECTOR_LAYOUT_LIMITS.maxTitleLength}
                      placeholder={t('next.inspector.configurator.customTitlePlaceholder')}
                      aria-label={t('next.inspector.configurator.customTitlePlaceholder')}
                      value={customTitle()}
                      onInput={(event) => setCustomTitle(event.currentTarget.value)}
                      onKeyDown={(event) => { if (event.key === 'Enter') createCustom() }}
                    />
                    <Button size="sm" onClick={createCustom} disabled={!customTitle().trim()}>
                      {t('next.inspector.configurator.add')}
                    </Button>
                  </div>
                </section>
              </Show>
            </div>
          </div>

          <Show when={expandedPlacementId()}>
            {(placementId) => {
              const section = () => props.layout.layout().sections.find((entry) => entry.id === placementId())
              return (
                <Show when={section()}>
                  {(placement) => (
                    <section class="nx-inspector-config-section nx-inspector-item-editor" aria-labelledby="nx-inspector-items-title">
                      <h3 id="nx-inspector-items-title">{sectionTitle(placement())}</h3>
                      <For each={inspectorSection(placement().sectionId)?.itemIds ?? []}>
                        {(itemId) => {
                          const item = controlItem(itemId)
                          return (
                            <Show when={item}>
                              <label class="nx-inspector-control-item">
                                <input
                                  type="checkbox"
                                  checked={!placement().hiddenItems.includes(itemId)}
                                  disabled={readOnly()}
                                  onChange={() => toggleItem(placement().id, itemId)}
                                />
                                <span>{t(item!.labelKey)}</span>
                              </label>
                            </Show>
                          )
                        }}
                      </For>
                    </section>
                  )}
                </Show>
              )
            }}
          </Show>

          <Show when={!readOnly()}>
            <section class="nx-inspector-config-section nx-inspector-portability" aria-labelledby="nx-inspector-portability-title">
              <h3 id="nx-inspector-portability-title">{t('next.inspector.configurator.portability')}</h3>
              <div class="nx-inspector-button-row">
                <Button size="sm" variant="secondary" onClick={exportLayout}>
                  {t('next.inspector.configurator.export')}
                </Button>
                <Button size="sm" variant="secondary" onClick={() => importInput?.click()}>
                  {t('next.inspector.configurator.import')}
                </Button>
                <Button size="sm" variant="danger" onClick={() => setConfirmReset(true)}>
                  {t('next.inspector.configurator.reset')}
                </Button>
              </div>
              <input
                ref={importInput}
                class="nx-visually-hidden"
                type="file"
                accept="application/json,.json"
                tabIndex={-1}
                onChange={(event) => {
                  const file = event.currentTarget.files?.[0]
                  if (file) void importLayout(file)
                }}
              />
            </section>
          </Show>
        </div>
      </Drawer>

      <ConfirmDialog
        open={confirmReset()}
        title={t('next.inspector.configurator.resetConfirmTitle')}
        message={t('next.inspector.configurator.resetConfirmMessage')}
        confirmLabel={t('next.inspector.configurator.reset')}
        danger
        onClose={() => setConfirmReset(false)}
        onConfirm={() => {
          setConfirmReset(false)
          void props.layout.reset()
        }}
      />
      <ConfirmDialog
        open={deletePreset() !== null}
        title={t('next.inspector.configurator.deletePresetConfirmTitle')}
        message={t('next.inspector.configurator.deletePresetConfirmMessage', { name: deletePreset()?.name ?? '' })}
        confirmLabel={t('common.delete')}
        danger
        onClose={() => setDeletePreset(null)}
        onConfirm={() => {
          if (deletePreset()) props.layout.mutate((current) => deleteCustomPreset(current, deletePreset()!.id))
          setDeletePreset(null)
        }}
      />
    </>
  )
}

function builtinPresetName(id: string): string {
  switch (id) {
    case 'camera-operator': return t('next.inspector.preset.cameraOperator')
    case 'security-monitor': return t('next.inspector.preset.securityMonitor')
    case 'streaming-links': return t('next.inspector.preset.streamingLinks')
    case 'minimal': return t('next.inspector.preset.minimal')
    default: return t('next.inspector.preset.default')
  }
}

function controlCategory(item: ControlItemDefinition): InspectorCategory {
  if (item.id === 'capture.snapshot') return 'capture'
  return inspectorSection(item.sectionId)?.category ?? 'camera'
}
