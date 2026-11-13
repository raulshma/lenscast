import { Show } from 'solid-js'
import type { AllSettings, CameraSettings, WhiteBalance } from '../../../../../types'
import { WB_LABELS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { WB_MODE_I18N_KEYS } from '../../../../../lib/cameraLabels'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Select, SliderField } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function WhiteBalancePanel(props: Props) {
  const s = () => props.settings()

  return (
    <Panel title={t('wb.title')}>
      <FieldRow label={t('common.mode')}>
        <Select
          value={s()?.camera?.whiteBalance ?? API_DEFAULTS.cameraWhiteBalance}
          options={Object.keys(WB_LABELS).map((k) => ({ value: k, label: t(WB_MODE_I18N_KEYS[k as WhiteBalance]) }))}
          onChange={(v) => props.updateCamera({ whiteBalance: v as WhiteBalance })}
          ariaLabel={t('common.mode')}
        />
      </FieldRow>

      <Show when={s()?.camera?.whiteBalance === 'MANUAL'}>
        <SliderField
          label={t('wb.temp')}
          display={`${s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}K`}
          min={2000}
          max={9000}
          step={100}
          value={s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}
          onChange={(v) => props.updateCamera({ colorTemperature: v })}
        />
      </Show>
    </Panel>
  )
}
