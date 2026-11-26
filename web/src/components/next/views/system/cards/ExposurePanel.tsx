import { Show } from 'solid-js'
import type { AllSettings, CameraSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ExposurePanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<CameraSettings>) => {
    if (!readOnly()) props.updateCamera(patch)
  }
  const s = () => props.settings()

  return (
    <Panel title={t('exposure.title')}>
      {/* Exposure Compensation */}
      <SettingItem id="exposure.compensation">
      <SliderField
        disabled={readOnly()}
        label={t('exposure.compensation')}
        min={-12}
        max={12}
        step={1}
        value={s()?.camera?.exposureCompensation ?? API_DEFAULTS.cameraExposureCompensation}
        onChange={(v) => props.updateCamera({ exposureCompensation: v })}
      />
      </SettingItem>

      <SettingItem id="exposure.iso">
      {/* ISO */}
      <FieldRow label={t('exposure.iso')}>
        <div style={{ display: 'flex', gap: '6px', 'align-items': 'center' }}>
          <Select
            disabled={readOnly()}
            small
            value={s()?.camera?.iso == null ? 'auto' : 'manual'}
            options={[
              { value: 'auto', label: t('common.auto') },
              { value: 'manual', label: t('common.manual') },
            ]}
            onChange={(v) => {
              if (v === 'auto') {
                update({ iso: null })
              } else {
                update({ iso: s()?.camera?.iso ?? API_DEFAULTS.cameraIso })
              }
            }}
            ariaLabel={t('exposure.iso')}
          />
          {s()?.camera?.iso != null && (
            <input
              id="iso-value-input"
              type="number"
              class="nx-input nx-input-sm nx-mono nx-input-num"
              value={s()?.camera?.iso ?? ''}
              min={100}
              max={32000}
              disabled={readOnly()}
              onChange={(e) => update({ iso: parseInt(e.currentTarget.value) || null })}
              aria-label={t('exposure.iso')}
            />
          )}
        </div>
      </FieldRow>
      </SettingItem>

      <SettingItem id="exposure.time">
      <>
      {/* Exposure Time */}
      <FieldRow label={t('exposure.time')}>
        <div style={{ display: 'flex', gap: '6px', 'align-items': 'center' }}>
          <Select
            disabled={readOnly()}
            small
            value={s()?.camera?.exposureTime == null ? 'auto' : 'manual'}
            options={[
              { value: 'auto', label: t('common.auto') },
              { value: 'manual', label: t('common.manual') },
            ]}
            onChange={(v) => {
              if (v === 'auto') {
                update({ exposureTime: null })
              } else {
                update({ exposureTime: s()?.camera?.exposureTime ?? API_DEFAULTS.cameraExposureTime })
              }
            }}
            ariaLabel={t('exposure.time')}
          />
          {s()?.camera?.exposureTime != null && (
            <input
              id="exposure-time-input"
              type="number"
              class="nx-input nx-input-sm nx-mono nx-input-num"
              value={
                s()?.camera?.exposureTime == null
                  ? ''
                  : String(Number((s()!.camera.exposureTime! / 1_000_000).toFixed(3)))
              }
              min={0.1}
              max={500}
              step={0.1}
              placeholder="ms"
              disabled={readOnly()}
              onChange={(e) => {
                const ms = Number.parseFloat(e.currentTarget.value)
                if (Number.isFinite(ms) && ms > 0) {
                  update({ exposureTime: Math.round(ms * 1_000_000) })
                } else {
                  e.currentTarget.value = String(
                    Number(((s()?.camera?.exposureTime ?? API_DEFAULTS.cameraExposureTime) / 1_000_000).toFixed(3)),
                  )
                }
              }}
              aria-label={t('exposure.timeMs')}
            />
          )}
        </div>
      </FieldRow>
      <Show when={s()?.camera?.exposureTime != null}>
        <div class="nx-field-hint">
          {t('exposure.timeHint', {
            ms: (s()!.camera.exposureTime! / 1_000_000).toFixed(1),
            s: (s()!.camera.exposureTime! / 1_000_000_000).toFixed(3),
          })}
        </div>
      </Show>
      </>
      </SettingItem>
    </Panel>
  )
}
