import type { AllSettings, CameraSettings, HdrMode } from '../../../../../types'
import { SCENE_MODE_OPTIONS, HDR_LABELS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Select, ToggleField } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

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

export default function EffectsPanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<CameraSettings>) => {
    if (!readOnly()) props.updateCamera(patch)
  }
  const s = () => props.settings()

  return (
    <Panel title={t('effects.title')}>
      {/* Scene Mode */}
      <SettingItem id="effects.sceneMode">
      <FieldRow label={t('effects.scene')}>
        <Select
          disabled={readOnly()}
          value={s()?.camera?.sceneMode ?? API_DEFAULTS.cameraSceneMode}
          options={SCENE_MODE_OPTIONS.map((opt) => ({ value: opt.value, label: t(SCENE_KEYS[opt.value] ?? '') || opt.label }))}
          onChange={(v) => update({ sceneMode: v === '' ? null : v })}
          ariaLabel={t('effects.scene')}
        />
      </FieldRow>
      </SettingItem>

      {/* Stabilization */}
      <SettingItem id="effects.stabilization">
      <ToggleField
        disabled={readOnly()}
        label={t('effects.stabilization')}
        checked={s()?.camera?.stabilization ?? API_DEFAULTS.cameraStabilization}
        onChange={() => update({ stabilization: !(s()?.camera?.stabilization ?? API_DEFAULTS.cameraStabilization) })}
      />
      </SettingItem>

      {/* HDR */}
      <SettingItem id="effects.hdr">
      <FieldRow label={t('effects.hdr')}>
        <Select
          disabled={readOnly()}
          value={s()?.camera?.hdrMode ?? API_DEFAULTS.cameraHdrMode}
          options={Object.keys(HDR_LABELS).map((k) => ({ value: k, label: t(HDR_KEYS[k as HdrMode]) }))}
          onChange={(v) => update({ hdrMode: v as HdrMode })}
          ariaLabel={t('effects.hdr')}
        />
      </FieldRow>
      </SettingItem>
    </Panel>
  )
}
