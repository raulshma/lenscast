import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, FocusMode } from '../types'
import { FOCUS_MODE_LABELS } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'

/** Wire value → i18n key for the focus-mode options (labels live in the catalog). */
const FOCUS_KEYS: Record<FocusMode, string> = {
  AUTO: 'common.auto',
  MANUAL: 'common.manual',
  MACRO: 'option.macro',
  CONTINUOUS_PICTURE: 'option.focus.contPhoto',
  CONTINUOUS_VIDEO: 'option.focus.contVideo',
}

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
      title={t('focus.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('focus.mode')}</span>
        </div>
        <select
          id="focus-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.focusMode ?? API_DEFAULTS.cameraFocusMode}
          onChange={(e) => props.updateCamera({ focusMode: e.currentTarget.value as FocusMode })}
        >
          {Object.keys(FOCUS_MODE_LABELS).map((k) => (
            <option value={k}>{t(FOCUS_KEYS[k as FocusMode])}</option>
          ))}
        </select>
      </div>

      {s()?.camera?.focusMode === 'MANUAL' && (
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('focus.distance')}</span>
            <span class="field-value">{(s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance).toFixed(1)}</span>
          </div>
          <input
            id="focus-distance-slider"
            type="range"
            class="custom-range"
            min={0}
            max={10}
            step={0.1}
            value={s()?.camera?.focusDistance ?? API_DEFAULTS.cameraFocusDistance}
            onInput={(e) => props.updateCamera({ focusDistance: parseFloat(e.currentTarget.value) })}
          />
        </div>
      )}
    </SettingsCard>
  )
}
