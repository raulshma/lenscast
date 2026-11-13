import { Show, For, createSignal } from 'solid-js'
import type { MaskingType, MaskingZone, StreamingSettings } from '../../../../../types'
import { MASKING_TYPE_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, Select, TextInput, SliderField, Button, Badge, Empty, Led } from '../../../ui'

/** Wire value → i18n key for the masking-type options. */
const MASK_KEYS: Record<MaskingType, string> = {
  BLACKOUT: 'option.mask.blackout',
  PIXELATE: 'option.mask.pixelate',
  BLUR: 'option.mask.blur',
}

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

export default function PrivacyMaskingPanel(props: Props) {
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
    <Panel
      title={t('privacy.title')}
      badge={<Led tone={s().maskingEnabled ? 'ok' : 'off'} />}
    >
      <ToggleField
        label={t('privacy.enable')}
        checked={s().maskingEnabled}
        onChange={(v) => props.onUpdate({ maskingEnabled: v })}
      />

      <Show when={s().maskingEnabled}>
        <FieldRow label={t('privacy.zones', { count: zones().length })}>
          <Button size="sm" variant="primary" onClick={addZone} ariaLabel={t('privacy.addZone')}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            {t('privacy.addZone')}
          </Button>
        </FieldRow>

        <For each={zones()}>
          {(zone) => (
            <>
              <div class="nx-list-row" style={{ cursor: 'pointer' }} onClick={() => toggleZoneExpanded(zone.id)}>
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  aria-hidden="true"
                  style={{
                    transform: expandedZoneId() === zone.id ? 'rotate(90deg)' : 'none',
                    transition: 'transform 0.15s',
                  }}
                >
                  <polyline points="9 18 15 12 9 6" />
                </svg>
                <span class="nx-field-label" style={{ flex: 1 }}>
                  {zone.label || t('privacy.zone', { n: zones().indexOf(zone) + 1 })}
                </span>
                <Badge tone={zone.type === 'BLACKOUT' ? 'neutral' : zone.type === 'PIXELATE' ? 'info' : 'accent'}>
                  {t(MASK_KEYS[zone.type])}
                </Badge>
                <Button
                  size="sm"
                  variant="danger-soft"
                  icon
                  onClick={(e) => {
                    e.stopPropagation()
                    removeZone(zone.id)
                  }}
                  title={t('privacy.removeZone')}
                  ariaLabel={t('privacy.removeZone')}
                >
                  ×
                </Button>
              </div>

              <Show when={expandedZoneId() === zone.id}>
                <div class="nx-list" style={{ 'padding-left': '24px' }}>
                  <FieldRow label={t('privacy.label')}>
                    <TextInput
                      small
                      value={zone.label}
                      placeholder={t('privacy.labelPlaceholder')}
                      onInput={(v) => updateZone(zone.id, { label: v })}
                      ariaLabel={t('privacy.label')}
                    />
                  </FieldRow>

                  <ToggleField
                    label={t('privacy.enabled')}
                    checked={zone.enabled}
                    onChange={(v) => updateZone(zone.id, { enabled: v })}
                  />

                  <FieldRow label={t('privacy.maskType')}>
                    <Select
                      small
                      value={zone.type}
                      options={Object.keys(MASKING_TYPE_LABELS).map((value) => ({ value, label: t(MASK_KEYS[value as MaskingType]) }))}
                      onChange={(v) => updateZone(zone.id, { type: v as MaskingType })}
                      ariaLabel={t('privacy.maskType')}
                    />
                  </FieldRow>

                  <SliderField
                    label={t('privacy.x')}
                    display={`${Math.round(zone.x * 100)}%`}
                    min={0}
                    max={100}
                    step={1}
                    value={Math.round(zone.x * 100)}
                    onChange={(v) => updateZone(zone.id, { x: v / 100 })}
                  />

                  <SliderField
                    label={t('privacy.y')}
                    display={`${Math.round(zone.y * 100)}%`}
                    min={0}
                    max={100}
                    step={1}
                    value={Math.round(zone.y * 100)}
                    onChange={(v) => updateZone(zone.id, { y: v / 100 })}
                  />

                  <SliderField
                    label={t('privacy.width')}
                    display={`${Math.round(zone.width * 100)}%`}
                    min={1}
                    max={100}
                    step={1}
                    value={Math.round(zone.width * 100)}
                    onChange={(v) => updateZone(zone.id, { width: v / 100 })}
                  />

                  <SliderField
                    label={t('privacy.height')}
                    display={`${Math.round(zone.height * 100)}%`}
                    min={1}
                    max={100}
                    step={1}
                    value={Math.round(zone.height * 100)}
                    onChange={(v) => updateZone(zone.id, { height: v / 100 })}
                  />

                  <Show when={zone.type === 'PIXELATE'}>
                    <SliderField
                      label={t('privacy.pixelSize')}
                      display={`${zone.pixelateSize}px`}
                      min={4}
                      max={64}
                      step={1}
                      value={zone.pixelateSize}
                      onChange={(v) => updateZone(zone.id, { pixelateSize: v })}
                    />
                  </Show>

                  <Show when={zone.type === 'BLUR'}>
                    <SliderField
                      label={t('privacy.blurRadius')}
                      display={String(Math.round(zone.blurRadius))}
                      min={1}
                      max={50}
                      step={1}
                      value={Math.round(zone.blurRadius)}
                      onChange={(v) => updateZone(zone.id, { blurRadius: v })}
                    />
                  </Show>
                </div>
              </Show>
            </>
          )}
        </For>

        <Show when={zones().length === 0}>
          <Empty title={t('privacy.empty')} />
        </Show>
      </Show>
    </Panel>
  )
}
