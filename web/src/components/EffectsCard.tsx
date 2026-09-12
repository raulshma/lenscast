import SettingsCard from './SettingsCard'
import type { AllSettings, CameraSettings, HdrMode } from '../types'
import { SCENE_MODE_OPTIONS, HDR_LABELS } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'

/** Wire value → i18n key for the scene-mode options ('' = Auto). */
const SCENE_KEYS: Record<string, string> = {
  '': 'common.auto',
  ACTION: 'option.scene.action',
  BARCODE: 'option.scene.barcode',
  BEACH: 'option.scene.beach',
  CANDLELIGHT: 'option.scene.candlelight',
  FACE_PRIORITY: 'option.scene.facePriority',
  FACE_PRIORITY_LOW_LIGHT: 'option.scene.facePriorityLowLight',
  FIREWORKS: 'option.scene.fireworks',
  HIGH_SPEED_VIDEO: 'option.scene.highSpeedVideo',
  LANDSCAPE: 'option.scene.landscape',
  NIGHT: 'option.scene.night',
  NIGHT_PORTRAIT: 'option.scene.nightPortrait',
  PARTY: 'option.scene.party',
  PORTRAIT: 'option.scene.portrait',
  SNOW: 'option.scene.snow',
  SPORTS: 'option.scene.sports',
  STEADYPHOTO: 'option.scene.steadyPhoto',
  SUNSET: 'option.scene.sunset',
  THEATRE: 'option.scene.theatre',
}

/** Wire value → i18n key for the HDR options. */
const HDR_KEYS: Record<HdrMode, string> = {
  OFF: 'common.off',
  ON: 'common.on',
  AUTO: 'common.auto',
}

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
      title={t('effects.title')}
    >
      {/* Scene Mode */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('effects.scene')}</span>
        </div>
        <select
          id="scene-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.sceneMode ?? API_DEFAULTS.cameraSceneMode}
          onChange={(e) => {
            const v = e.currentTarget.value
            props.updateCamera({ sceneMode: v === '' ? null : v })
          }}
        >
          {SCENE_MODE_OPTIONS.map((opt) => (
            <option value={opt.value}>{t(SCENE_KEYS[opt.value] ?? '') || opt.label}</option>
          ))}
        </select>
      </div>

      {/* Stabilization */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('effects.stabilization')}</span>
          <label class="toggle-switch" for="stabilization-toggle">
            <input
              id="stabilization-toggle"
              type="checkbox"
              checked={s()?.camera?.stabilization ?? API_DEFAULTS.cameraStabilization}
              onChange={() => props.updateCamera({ stabilization: !(s()?.camera?.stabilization ?? API_DEFAULTS.cameraStabilization) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      {/* HDR */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('effects.hdr')}</span>
        </div>
        <select
          id="hdr-mode-select"
          class="field-select field-select-full"
          value={s()?.camera?.hdrMode ?? API_DEFAULTS.cameraHdrMode}
          onChange={(e) => props.updateCamera({ hdrMode: e.currentTarget.value as HdrMode })}
        >
          {Object.keys(HDR_LABELS).map((k) => (
            <option value={k}>{t(HDR_KEYS[k as HdrMode])}</option>
          ))}
        </select>
      </div>
    </SettingsCard>
  )
}
