import { Show, For, createSignal } from 'solid-js'
import type { MaskingType, MaskingZone, StreamingSettings } from '../../../../../types'
import { MASKING_TYPE_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { hashRouter } from '../../../../../lib/router'
import { startMaskEdit } from '../../../../../lib/maskEditStore'
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
  /**
   * True while the device's web stream is on air. A zone is placed on that
   * picture, so without it "Edit zones" and every zone row's open key stand
   * down and say why.
   */
  streamActive: () => boolean
}

/**
 * Privacy Masking, in the beta System view (Camera → Overlays): the master
 * switch, the zones, and the way into the visual editor — because where a
 * zone sits is drawn on the live picture, not typed. "Edit zones" opens the
 * editor with no zone selected; a zone row's key opens it on that zone.
 *
 * What stays in this panel is what the picture cannot say: the label, the
 * type and its strength, whether a zone is switched on, and removing it —
 * saved through `onUpdate`, like every other control here.
 *
 * Both ways in are one call sequence, in one handler: arm the edit
 * (maskEditStore), then route to Live, which owns the editor and answers
 * the arm. An arm that is not followed to Live would stay armed with the
 * dock's camera strip hidden and nothing on screen to finish it, so one
 * never happens without the other — and neither is offered without a web
 * stream, the picture zones are drawn on.
 */
export default function PrivacyMaskingPanel(props: Props) {
  const s = () => props.streaming()
  const zones = () => s().maskingZones ?? []
  const [expandedZoneId, setExpandedZoneId] = createSignal<string | null>(null)
  const router = hashRouter()

  /** The zone's own label, or its number in the list. */
  function zoneName(zone: MaskingZone): string {
    return zone.label || t('privacy.zone', { n: zones().indexOf(zone) + 1 })
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

  /** Open the visual editor over the Live stage — on `zoneId`, when a row names its zone. */
  function openEditor(zoneId?: string) {
    startMaskEdit(zoneId)
    router.navigate({ name: 'live' })
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
        {/* The reason stands on the row as text, not only in the button's
            tooltip: a disabled button is not one a keyboard or screen
            reader user can reach to hear it. */}
        <FieldRow
          label={t('privacy.zones', { count: zones().length })}
          hint={props.streamActive() ? undefined : t('privacy.streamOffHint')}
        >
          <Button
            size="sm"
            variant="primary"
            disabled={!props.streamActive()}
            title={props.streamActive() ? t('privacy.editZoneTitle') : t('privacy.streamOffHint')}
            ariaLabel={t('privacy.editZoneTitle')}
            onClick={() => openEditor()}
          >
            {t('privacy.editZones')}
          </Button>
        </FieldRow>

        <For each={zones()}>
          {(zone) => (
            <>
              {/* One zone per row. The row itself takes no click: its open
                  key is a real button, and the disclosure and the × beside
                  it are that button's siblings — a press on either is its
                  own, and never opens the editor. */}
              <div class="nx-list-row" data-zone-id={zone.id}>
                <Button
                  size="sm"
                  variant="ghost"
                  icon
                  ariaLabel={zoneName(zone)}
                  title={zoneName(zone)}
                  ariaExpanded={expandedZoneId() === zone.id}
                  onClick={() => toggleZoneExpanded(zone.id)}
                >
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
                      transition: 'transform var(--dur-micro) var(--ease-out)',
                    }}
                  >
                    <polyline points="9 18 15 12 9 6" />
                  </svg>
                </Button>
                {/* The key says which zone it opens before what it does:
                    the name is on screen inside it, and a list of keys all
                    called the same thing tells a screen reader nothing. */}
                <Button
                  class="nx-privacy-zone-open"
                  variant="ghost"
                  disabled={!props.streamActive()}
                  title={props.streamActive() ? t('privacy.editZoneTitle') : t('privacy.streamOffHint')}
                  ariaLabel={`${zoneName(zone)} · ${t('privacy.editZoneTitle')}`}
                  onClick={() => openEditor(zone.id)}
                >
                  <span class="nx-field-label nx-privacy-zone-name">{zoneName(zone)}</span>
                  {/* The zone's type is a reading, not a selection: quiet
                      neutral/cool tags, never the amber of a chosen chip. */}
                  <Badge tone={zone.type === 'BLACKOUT' ? 'neutral' : 'info'}>
                    {t(MASK_KEYS[zone.type])}
                  </Badge>
                </Button>
                <Button
                  size="sm"
                  variant="danger-soft"
                  icon
                  title={t('privacy.removeZone')}
                  ariaLabel={t('privacy.removeZone')}
                  onClick={() => removeZone(zone.id)}
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

        {/* The beta panel's own empty state: privacy.empty names "Add Zone",
            a button only the classic card has. */}
        <Show when={zones().length === 0}>
          <Empty title={t('privacy.editor.empty')} />
        </Show>
      </Show>
    </Panel>
  )
}
