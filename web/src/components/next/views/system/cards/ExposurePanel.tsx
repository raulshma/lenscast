import { Show } from 'solid-js'
import type { AllSettings, CameraSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ExposurePanel(props: Props) {
  const s = () => props.settings()

  return (
    <Panel title={t('exposure.title')}>
      {/* Exposure Compensation */}
      <SliderField
        label={t('exposure.compensation')}
        min={-12}
        max={12}
        step={1}
        value={s()?.camera?.exposureCompensation ?? API_DEFAULTS.cameraExposureCompensation}
        onChange={(v) => props.updateCamera({ exposureCompensation: v })}
      />

      {/* ISO */}
      <FieldRow label={t('exposure.iso')}>
        <div style={{ display: 'flex', gap: '6px', 'align-items': 'center' }}>
          <Select
            small
            value={s()?.camera?.iso == null ? 'auto' : 'manual'}
            options={[
              { value: 'auto', label: t('common.auto') },
              { value: 'manual', label: t('common.manual') },
            ]}
            onChange={(v) => {
              if (v === 'auto') {
                props.updateCamera({ iso: null })
              } else {
                props.updateCamera({ iso: s()?.camera?.iso ?? API_DEFAULTS.cameraIso })
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
              onChange={(e) => props.updateCamera({ iso: parseInt(e.currentTarget.value) || null })}
              aria-label={t('exposure.iso')}
            />
          )}
        </div>
      </FieldRow>

      {/* Exposure Time */}
      <FieldRow label={t('exposure.time')}>
        <div style={{ display: 'flex', gap: '6px', 'align-items': 'center' }}>
          <Select
            small
            value={s()?.camera?.exposureTime == null ? 'auto' : 'manual'}
            options={[
              { value: 'auto', label: t('common.auto') },
              { value: 'manual', label: t('common.manual') },
            ]}
            onChange={(v) => {
              if (v === 'auto') {
                props.updateCamera({ exposureTime: null })
              } else {
                props.updateCamera({ exposureTime: s()?.camera?.exposureTime ?? API_DEFAULTS.cameraExposureTime })
              }
            }}
            ariaLabel={t('exposure.time')}
          />
          {s()?.camera?.exposureTime != null && (
            <input
              id="exposure-time-input"
              type="number"
              class="nx-input nx-input-sm nx-mono nx-input-num"
              value={s()?.camera?.exposureTime ?? ''}
              min={100000}
              max={500000000}
              step={1000000}
              placeholder="ns"
              onChange={(e) => props.updateCamera({ exposureTime: parseInt(e.currentTarget.value) || null })}
              aria-label={t('exposure.time')}
            />
          )}
        </div>
      </FieldRow>
      <Show when={s()?.camera?.exposureTime != null}>
        <div class="nx-field-hint">
          {(s()!.camera.exposureTime! / 1_000_000).toFixed(1)}ms ({(s()!.camera.exposureTime! / 1_000_000_000).toFixed(3)}s)
        </div>
      </Show>
    </Panel>
  )
}
