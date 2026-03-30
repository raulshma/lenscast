import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings } from '../types'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ExposureCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10" />
          <path d="M12 2v20M2 12h20" />
          <path d="M12 7v10M7 12h10" opacity="0.4" />
        </svg>
      }
      title="Exposure"
    >
      {/* Exposure Compensation */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Compensation</span>
          <span class="field-value">{s()?.camera?.exposureCompensation ?? 0}</span>
        </div>
        <input
          id="exposure-comp-slider"
          type="range"
          class="custom-range"
          min={-12}
          max={12}
          value={s()?.camera?.exposureCompensation ?? 0}
          onInput={(e) => props.updateCamera({ exposureCompensation: parseFloat(e.currentTarget.value) })}
        />
      </div>

      {/* ISO */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">ISO</span>
        </div>
        <div class="field-inline">
          <select
            id="iso-mode-select"
            class="field-select"
            value={s()?.camera?.iso == null ? 'auto' : 'manual'}
            onChange={(e) => {
              if (e.currentTarget.value === 'auto') {
                props.updateCamera({ iso: null })
              } else {
                props.updateCamera({ iso: s()?.camera?.iso ?? 800 })
              }
            }}
          >
            <option value="auto">Auto</option>
            <option value="manual">Manual</option>
          </select>
          {s()?.camera?.iso != null && (
            <input
              id="iso-value-input"
              type="number"
              class="field-input field-input-sm"
              value={s()?.camera?.iso ?? ''}
              min={100}
              max={32000}
              onChange={(e) => props.updateCamera({ iso: parseInt(e.currentTarget.value) || null })}
            />
          )}
        </div>
      </div>

      {/* Exposure Time */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Exposure Time</span>
        </div>
        <div class="field-inline">
          <select
            id="exposure-time-mode"
            class="field-select"
            value={s()?.camera?.exposureTime == null ? 'auto' : 'manual'}
            onChange={(e) => {
              if (e.currentTarget.value === 'auto') {
                props.updateCamera({ exposureTime: null })
              } else {
                props.updateCamera({ exposureTime: s()?.camera?.exposureTime ?? 10000000 })
              }
            }}
          >
            <option value="auto">Auto</option>
            <option value="manual">Manual</option>
          </select>
          {s()?.camera?.exposureTime != null && (
            <input
              id="exposure-time-input"
              type="number"
              class="field-input field-input-sm"
              value={s()?.camera?.exposureTime ?? ''}
              min={100000}
              max={500000000}
              step={1000000}
              placeholder="ns"
              onChange={(e) => props.updateCamera({ exposureTime: parseInt(e.currentTarget.value) || null })}
            />
          )}
        </div>
        {s()?.camera?.exposureTime != null && (
          <span class="field-hint">
            {(s()!.camera.exposureTime! / 1_000_000).toFixed(1)}ms ({(s()!.camera.exposureTime! / 1_000_000_000).toFixed(3)}s)
          </span>
        )}
      </div>
    </SettingsCard>
  )
}
