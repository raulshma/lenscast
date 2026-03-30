import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, WhiteBalance } from '../types'
import { WB_LABELS } from '../types'

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
      title="White Balance"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Mode</span>
        </div>
        <select
          id="wb-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.whiteBalance ?? 'AUTO'}
          onChange={(e) => props.updateCamera({ whiteBalance: e.currentTarget.value as WhiteBalance })}
        >
          {Object.entries(WB_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {s()?.camera?.whiteBalance === 'MANUAL' && (
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Color Temperature</span>
            <span class="field-value">{s()?.camera?.colorTemperature ?? 5500}K</span>
          </div>
          <input
            id="color-temp-slider"
            type="range"
            class="custom-range custom-range-warm"
            min={2000}
            max={9000}
            step={100}
            value={s()?.camera?.colorTemperature ?? 5500}
            onInput={(e) => props.updateCamera({ colorTemperature: parseFloat(e.currentTarget.value) })}
          />
        </div>
      )}
    </SettingsCard>
  )
}
