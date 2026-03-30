import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, Resolution } from '../types'
import { RESOLUTION_LABELS, FRAME_RATE_OPTIONS } from '../types'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ZoomFrameCard(props: Props) {
  const s = () => props.settings()

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
          <line x1="11" y1="8" x2="11" y2="14" />
          <line x1="8" y1="11" x2="14" y2="11" />
        </svg>
      }
      title="Zoom & Frame"
    >
      {/* Zoom */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Zoom</span>
          <span class="field-value">{(s()?.camera?.zoomRatio ?? 1).toFixed(1)}x</span>
        </div>
        <input
          id="zoom-slider"
          type="range"
          class="custom-range"
          min={1}
          max={10}
          step={0.1}
          value={s()?.camera?.zoomRatio ?? 1}
          onInput={(e) => props.updateCamera({ zoomRatio: parseFloat(e.currentTarget.value) })}
        />
      </div>

      {/* Frame Rate */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Frame Rate</span>
        </div>
        <select
          id="framerate-select"
          class="field-select field-select-full"
          value={s()?.camera?.frameRate ?? 30}
          onChange={(e) => props.updateCamera({ frameRate: parseInt(e.currentTarget.value) })}
        >
          {FRAME_RATE_OPTIONS.map((r) => (
            <option value={r}>{r} fps</option>
          ))}
        </select>
      </div>

      {/* Resolution */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Resolution</span>
        </div>
        <select
          id="resolution-select"
          class="field-select field-select-full"
          value={s()?.camera?.resolution ?? 'FHD_1080P'}
          onChange={(e) => props.updateCamera({ resolution: e.currentTarget.value as Resolution })}
        >
          {Object.entries(RESOLUTION_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>
    </SettingsCard>
  )
}
