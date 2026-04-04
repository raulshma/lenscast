import { Show, For, createSignal } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { MaskingType, MaskingZone, StreamingSettings } from '../types'
import { MASKING_TYPE_LABELS } from '../types'

interface Props {
  streaming: () => StreamingSettings
  onUpdate: (patch: Partial<StreamingSettings>) => void
}

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function createDefaultZone(): MaskingZone {
  return {
    id: generateId(),
    label: '',
    enabled: true,
    type: 'BLACKOUT',
    x: 0.1,
    y: 0.1,
    width: 0.3,
    height: 0.3,
    pixelateSize: 16,
    blurRadius: 10,
  }
}

export default function PrivacyMaskingCard(props: Props) {
  const s = () => props.streaming()
  const zones = () => s().maskingZones ?? []
  const [expandedZoneId, setExpandedZoneId] = createSignal<string | null>(null)

  function addZone() {
    const newZone = createDefaultZone()
    props.onUpdate({ maskingZones: [...zones(), newZone] })
    setExpandedZoneId(newZone.id)
  }

  function removeZone(id: string) {
    props.onUpdate({ maskingZones: zones().filter((z) => z.id !== id) })
    if (expandedZoneId() === id) setExpandedZoneId(null)
  }

  function updateZone(id: string, patch: Partial<MaskingZone>) {
    props.onUpdate({
      maskingZones: zones().map((z) => (z.id === id ? { ...z, ...patch } : z)),
    })
  }

  function toggleZoneExpanded(id: string) {
    setExpandedZoneId((prev) => (prev === id ? null : id))
  }

  return (
    <SettingsCard
      icon={
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="3" width="7" height="7" fill="currentColor" opacity="0.3" />
          <rect x="14" y="14" width="7" height="7" fill="currentColor" opacity="0.3" />
          <rect x="3" y="14" width="7" height="7" />
          <rect x="14" y="3" width="7" height="7" />
        </svg>
      }
      title="Privacy Masking"
    >
      <div class="settings-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Enable Privacy Masking</span>
          <label class="toggle-switch" for="masking-enabled-toggle">
            <input
              id="masking-enabled-toggle"
              type="checkbox"
              checked={s().maskingEnabled}
              onChange={(e) => props.onUpdate({ maskingEnabled: e.currentTarget.checked })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      <Show when={s().maskingEnabled}>
        <div class="settings-group">
          <div class="setting-row" style={{ 'justify-content': 'space-between', 'align-items': 'center' }}>
            <span class="setting-label">Masking Zones ({zones().length})</span>
            <button
              class="card-btn card-btn-primary"
              style={{ 'font-size': '12px', padding: '4px 10px' }}
              onClick={addZone}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
              Add Zone
            </button>
          </div>
        </div>

        <For each={zones()}>
          {(zone) => (
            <div class="settings-group" style={{ 'margin-top': '4px' }}>
              <div
                class="setting-row"
                style={{ cursor: 'pointer', 'flex-wrap': 'wrap', gap: '4px' }}
                onClick={() => toggleZoneExpanded(zone.id)}
              >
                <div style={{ display: 'flex', 'align-items': 'center', gap: '8px', flex: 1 }}>
                  <svg
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    style={{
                      transform: expandedZoneId() === zone.id ? 'rotate(90deg)' : 'none',
                      transition: 'transform 0.15s',
                    }}
                  >
                    <polyline points="9 18 15 12 9 6" />
                  </svg>
                  <span class="setting-label" style={{ flex: 1 }}>
                    {zone.label || `Zone ${zones().indexOf(zone) + 1}`}
                  </span>
                  <span
                    style={{
                      'font-size': '11px',
                      padding: '2px 6px',
                      'border-radius': '4px',
                      background: zone.type === 'BLACKOUT' ? '#374151' : zone.type === 'PIXELATE' ? '#1e3a5f' : '#3b1f5e',
                      color: '#9ca3af',
                    }}
                  >
                    {MASKING_TYPE_LABELS[zone.type]}
                  </span>
                </div>
                <button
                  class="card-btn"
                  style={{
                    'font-size': '16px',
                    padding: '2px 6px',
                    color: '#ef4444',
                    background: 'transparent',
                    border: 'none',
                    cursor: 'pointer',
                    'line-height': 1,
                  }}
                  onClick={(e) => {
                    e.stopPropagation()
                    removeZone(zone.id)
                  }}
                  title="Remove zone"
                >
                  ×
                </button>
              </div>

              <Show when={expandedZoneId() === zone.id}>
                <div style={{ 'margin-top': '8px', 'padding-left': '24px' }}>
                  <label class="setting-row">
                    <span class="setting-label">Label</span>
                    <input
                      type="text"
                      class="text-input text-input-sm"
                      value={zone.label}
                      placeholder="e.g. Front door, Monitor"
                      onChange={(e) => updateZone(zone.id, { label: e.currentTarget.value })}
                    />
                  </label>

                  <div class="field-row field-row-toggle">
                    <span class="field-label">Enabled</span>
                    <label class="toggle-switch" for={`mask-zone-enabled-${zone.id}`}>
                      <input
                        id={`mask-zone-enabled-${zone.id}`}
                        type="checkbox"
                        checked={zone.enabled}
                        onChange={(e) => updateZone(zone.id, { enabled: e.currentTarget.checked })}
                      />
                      <span class="toggle-slider" />
                    </label>
                  </div>

                  <div class="setting-row">
                    <span class="setting-label">Mask Type</span>
                    <select
                      class="select-input select-input-sm"
                      value={zone.type}
                      onChange={(e) => updateZone(zone.id, { type: e.currentTarget.value as MaskingType })}
                    >
                      {Object.entries(MASKING_TYPE_LABELS).map(([value, label]) => (
                        <option value={value}>{label}</option>
                      ))}
                    </select>
                  </div>

                  <div class="setting-row">
                    <span class="setting-label">X Position</span>
                    <input
                      type="range"
                      class="range-input"
                      min="0"
                      max="100"
                      value={Math.round(zone.x * 100)}
                      onInput={(e) => updateZone(zone.id, { x: parseInt(e.currentTarget.value) / 100 })}
                    />
                    <span class="range-value">{Math.round(zone.x * 100)}%</span>
                  </div>

                  <div class="setting-row">
                    <span class="setting-label">Y Position</span>
                    <input
                      type="range"
                      class="range-input"
                      min="0"
                      max="100"
                      value={Math.round(zone.y * 100)}
                      onInput={(e) => updateZone(zone.id, { y: parseInt(e.currentTarget.value) / 100 })}
                    />
                    <span class="range-value">{Math.round(zone.y * 100)}%</span>
                  </div>

                  <div class="setting-row">
                    <span class="setting-label">Width</span>
                    <input
                      type="range"
                      class="range-input"
                      min="1"
                      max="100"
                      value={Math.round(zone.width * 100)}
                      onInput={(e) => updateZone(zone.id, { width: parseInt(e.currentTarget.value) / 100 })}
                    />
                    <span class="range-value">{Math.round(zone.width * 100)}%</span>
                  </div>

                  <div class="setting-row">
                    <span class="setting-label">Height</span>
                    <input
                      type="range"
                      class="range-input"
                      min="1"
                      max="100"
                      value={Math.round(zone.height * 100)}
                      onInput={(e) => updateZone(zone.id, { height: parseInt(e.currentTarget.value) / 100 })}
                    />
                    <span class="range-value">{Math.round(zone.height * 100)}%</span>
                  </div>

                  <Show when={zone.type === 'PIXELATE'}>
                    <div class="setting-row">
                      <span class="setting-label">Pixel Size</span>
                      <input
                        type="range"
                        class="range-input"
                        min="4"
                        max="64"
                        value={zone.pixelateSize}
                        onInput={(e) => updateZone(zone.id, { pixelateSize: parseInt(e.currentTarget.value) })}
                      />
                      <span class="range-value">{zone.pixelateSize}px</span>
                    </div>
                  </Show>

                  <Show when={zone.type === 'BLUR'}>
                    <div class="setting-row">
                      <span class="setting-label">Blur Radius</span>
                      <input
                        type="range"
                        class="range-input"
                        min="1"
                        max="50"
                        value={Math.round(zone.blurRadius)}
                        onInput={(e) => updateZone(zone.id, { blurRadius: parseFloat(e.currentTarget.value) })}
                      />
                      <span class="range-value">{Math.round(zone.blurRadius)}</span>
                    </div>
                  </Show>
                </div>
              </Show>
            </div>
          )}
        </For>

        <Show when={zones().length === 0}>
          <div style={{ 'text-align': 'center', padding: '16px 0', color: '#6b7280', 'font-size': '13px' }}>
            No masking zones defined. Click "Add Zone" to create one.
          </div>
        </Show>
      </Show>
    </SettingsCard>
  )
}
