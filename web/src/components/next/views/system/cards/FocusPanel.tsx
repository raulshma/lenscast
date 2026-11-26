import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, FocusMode } from '../../../../../types'
import { FOCUS_MODE_LABELS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { FOCUS_MODE_I18N_KEYS } from '../../../../../lib/cameraLabels'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Select, SliderField } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function FocusPanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<CameraSettings>) => {
    if (!readOnly()) props.updateCamera(patch)
  }
  const s = () => props.settings()

  return (
    <Panel title={t('focus.title')}>
      <SettingItem id="focus.mode">
      <FieldRow label={t('focus.mode')}>
        <Select
          disabled={readOnly()}
          value={s()?.camera?.focusMode ?? API_DEFAULTS.cameraFocusMode}
          options={Object.keys(FOCUS_MODE_LABELS).map((k) => ({ value: k, label: t(FOCUS_MODE_I18N_KEYS[k as FocusMode]) }))}
          onChange={(v) => update({ focusMode: v as FocusMode })}
          ariaLabel={t('focus.mode')}
        />
      </FieldRow>
      </SettingItem>

      <SettingItem id="focus.distance">
      <Show when={s()?.camera?.focusMode === 'MANUAL'}>
        <SliderField
          disabled={readOnly()}
          label={t('focus.distance')}
          display={(s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance).toFixed(1)}
          min={0}
          max={10}
          step={0.1}
          value={s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance}
          onChange={(v) => update({ focusDistance: v })}
        />
      </Show>
      </SettingItem>
    </Panel>
  )
}
