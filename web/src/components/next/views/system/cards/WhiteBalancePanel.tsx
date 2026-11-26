import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, WhiteBalance } from '../../../../../types'
import { WB_LABELS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { WB_MODE_I18N_KEYS } from '../../../../../lib/cameraLabels'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Select, SliderField } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function WhiteBalancePanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<CameraSettings>) => {
    if (!readOnly()) props.updateCamera(patch)
  }
  const s = () => props.settings()

  return (
    <Panel title={t('wb.title')}>
      <SettingItem id="whiteBalance.preset">
      <FieldRow label={t('common.mode')}>
        <Select
          disabled={readOnly()}
          value={s()?.camera?.whiteBalance ?? API_DEFAULTS.cameraWhiteBalance}
          options={Object.keys(WB_LABELS).map((k) => ({ value: k, label: t(WB_MODE_I18N_KEYS[k as WhiteBalance]) }))}
          onChange={(v) => update({ whiteBalance: v as WhiteBalance })}
          ariaLabel={t('common.mode')}
        />
      </FieldRow>
      </SettingItem>

      <SettingItem id="whiteBalance.temperature">
      <Show when={s()?.camera?.whiteBalance === 'MANUAL'}>
        <SliderField
          disabled={readOnly()}
          label={t('wb.temp')}
          display={`${s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}K`}
          min={2000}
          max={9000}
          step={100}
          value={s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}
          onChange={(v) => update({ colorTemperature: v })}
        />
      </Show>
      </SettingItem>
    </Panel>
  )
}
