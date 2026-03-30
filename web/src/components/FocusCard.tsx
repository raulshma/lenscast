import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, FocusMode } from '../types'
import { FOCUS_MODE_LABELS } from '../types'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function FocusCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="3" />
          <circle cx="12" cy="12" r="8" />
          <line x1="12" y1="1" x2="12" y2="4" />
          <line x1="12" y1="20" x2="12" y2="23" />
          <line x1="1" y1="12" x2="4" y2="12" />
          <line x1="20" y1="12" x2="23" y2="12" />
        </svg>
      }
      title="Focus"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Focus Mode</span>
        </div>
        <select
          id="focus-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.focusMode ?? 'AUTO'}
          onChange={(e) => props.updateCamera({ focusMode: e.currentTarget.value as FocusMode })}
        >
          {Object.entries(FOCUS_MODE_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {s()?.camera?.focusMode === 'MANUAL' && (
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Focus Distance</span>
            <span class="field-value">{(s()?.camera?.focusDistance ?? 0).toFixed(1)}</span>
          </div>
          <input
            id="focus-distance-slider"
            type="range"
            class="custom-range"
            min={0}
            max={10}
            step={0.1}
            value={s()?.camera?.focusDistance ?? 0}
            onInput={(e) => props.updateCamera({ focusDistance: parseFloat(e.currentTarget.value) })}
          />
        </div>
      )}
    </SettingsCard>
  )
}
