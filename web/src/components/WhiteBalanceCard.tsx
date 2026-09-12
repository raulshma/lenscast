import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, WhiteBalance } from '../types'
import { WB_LABELS } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'

/** Wire value → i18n key for the white-balance options (labels live in the catalog). */
const WB_KEYS: Record<WhiteBalance, string> = {
  AUTO: 'common.auto',
  DAYLIGHT: 'option.wb.daylight',
  CLOUDY: 'option.wb.cloudy',
  INDOOR: 'option.wb.indoor',
  FLUORESCENT: 'option.wb.fluorescent',
  MANUAL: 'common.manual',
}

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function WhiteBalanceCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="5" />
          <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
        </svg>
      }
      title={t('wb.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('common.mode')}</span>
        </div>
        <select
          id="wb-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.whiteBalance ?? API_DEFAULTS.cameraWhiteBalance}
          onChange={(e) => props.updateCamera({ whiteBalance: e.currentTarget.value as WhiteBalance })}
        >
          {Object.keys(WB_LABELS).map((k) => (
            <option value={k}>{t(WB_KEYS[k as WhiteBalance])}</option>
          ))}
        </select>
      </div>

      {s()?.camera?.whiteBalance === 'MANUAL' && (
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('wb.temp')}</span>
            <span class="field-value">{s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}K</span>
          </div>
          <input
            id="color-temp-slider"
            type="range"
            class="custom-range custom-range-warm"
            min={2000}
            max={9000}
            step={100}
            value={s()?.camera?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}
            onInput={(e) => props.updateCamera({ colorTemperature: parseFloat(e.currentTarget.value) })}
          />
        </div>
      )}
    </SettingsCard>
  )
}
