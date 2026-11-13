import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, FocusMode } from '../../../../../types'
import { FOCUS_MODE_LABELS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { FOCUS_MODE_I18N_KEYS } from '../../../../../lib/cameraLabels'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Select, SliderField } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function FocusPanel(props: Props) {
  const s = () => props.settings()

  return (
    <Panel title={t('focus.title')}>
      <FieldRow label={t('focus.mode')}>
        <Select
          value={s()?.camera?.focusMode ?? API_DEFAULTS.cameraFocusMode}
          options={Object.keys(FOCUS_MODE_LABELS).map((k) => ({ value: k, label: t(FOCUS_MODE_I18N_KEYS[k as FocusMode]) }))}
          onChange={(v) => props.updateCamera({ focusMode: v as FocusMode })}
          ariaLabel={t('focus.mode')}
        />
      </FieldRow>

      <Show when={s()?.camera?.focusMode === 'MANUAL'}>
        <SliderField
          label={t('focus.distance')}
          display={(s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance).toFixed(1)}
          min={0}
          max={10}
          step={0.1}
          value={s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance}
          onChange={(v) => props.updateCamera({ focusDistance: v })}
        />
      </Show>
    </Panel>
  )
}
