import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, OutputRotation, Resolution } from '../types'
import { RESOLUTION_LABELS, FRAME_RATE_OPTIONS, OUTPUT_ROTATION_OPTIONS } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'

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
      title={t('zoomframe.title')}
    >
      {/* Zoom */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('zoomframe.zoom')}</span>
          <span class="field-value">{(s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio).toFixed(1)}x</span>
        </div>
        <input
          id="zoom-slider"
          type="range"
          class="custom-range"
          min={1}
          max={10}
          step={0.1}
          value={s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio}
          onInput={(e) => props.updateCamera({ zoomRatio: parseFloat(e.currentTarget.value) })}
        />
      </div>

      {/* Frame Rate */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('zoomframe.frameRate')}</span>
        </div>
        <select
          id="framerate-select"
          class="field-select field-select-full"
          value={s()?.camera?.frameRate ?? API_DEFAULTS.cameraFrameRate}
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
          <span class="field-label">{t('common.resolution')}</span>
        </div>
        <select
          id="resolution-select"
          class="field-select field-select-full"
          value={s()?.camera?.resolution ?? API_DEFAULTS.cameraResolution}
          onChange={(e) => props.updateCamera({ resolution: e.currentTarget.value as Resolution })}
        >
          {Object.entries(RESOLUTION_LABELS).map(([k, v]) => (
            <option value={k}>{v}</option>
          ))}
        </select>
      </div>

      {/* Output rotation — the mounted-phone correction (issue #6) */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('zoomframe.rotation')}</span>
        </div>
        <select
          id="rotation-select"
          class="field-select field-select-full"
          value={s()?.camera?.outputRotation ?? API_DEFAULTS.cameraOutputRotation}
          onChange={(e) => props.updateCamera({ outputRotation: parseInt(e.currentTarget.value) as OutputRotation })}
        >
          {OUTPUT_ROTATION_OPTIONS.map((r) => (
            <option value={r}>{r}°</option>
          ))}
        </select>
        <label class="field-row" style={{ cursor: 'pointer', 'margin-top': '6px' }}>
          <input
            id="orientation-locked-toggle"
            type="checkbox"
            checked={s()?.camera?.orientationLocked ?? API_DEFAULTS.cameraOrientationLocked}
            onChange={(e) => props.updateCamera({ orientationLocked: e.currentTarget.checked })}
          />
          <span class="field-label">{t('zoomframe.orientationLocked')}</span>
        </label>
      </div>
    </SettingsCard>
  )
}
