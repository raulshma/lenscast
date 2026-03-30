import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, HdrMode } from '../types'
import { SCENE_MODE_OPTIONS, HDR_LABELS } from '../types'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function EffectsCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 2L2 7l10 5 10-5-10-5z" />
          <path d="M2 17l10 5 10-5" />
          <path d="M2 12l10 5 10-5" />
        </svg>
      }
      title="Effects"
    >
      {/* Scene Mode */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Scene Mode</span>
        </div>
        <select
          id="scene-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.sceneMode ?? ''}
          onChange={(e) => {
            const v = e.currentTarget.value
            props.updateCamera({ sceneMode: v === '' ? null : v })
          }}
        >
          {SCENE_MODE_OPTIONS.map((opt) => (
            <option value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </div>

      {/* Stabilization */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Stabilization</span>
          <label class="toggle-switch" for="stabilization-toggle">
            <input
              id="stabilization-toggle"
              type="checkbox"
              checked={s()?.camera?.stabilization ?? true}
              onChange={() => props.updateCamera({ stabilization: !(s()?.camera?.stabilization ?? true) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* HDR */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">HDR</span>
        </div>
        <select
          id="hdr-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.hdrMode ?? 'OFF'}
          onChange={(e) => props.updateCamera({ hdrMode: e.currentTarget.value as HdrMode })}
        >
          {Object.entries(HDR_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>
    </SettingsCard>
  )
}
