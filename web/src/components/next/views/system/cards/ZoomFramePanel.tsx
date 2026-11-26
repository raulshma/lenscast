import type { AllSettings, CameraSettings, OutputRotation, Resolution } from '../../../../../types'
import { RESOLUTION_LABELS, FRAME_RATE_OPTIONS, OUTPUT_ROTATION_OPTIONS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select, ToggleField } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ZoomFramePanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<CameraSettings>) => {
    if (!readOnly()) props.updateCamera(patch)
  }
  const s = () => props.settings()

  return (
    <Panel title={t('zoomframe.title')}>
      {/* Zoom */}
      <SettingItem id="zoomFrame.zoom">
      <SliderField
        disabled={readOnly()}
        label={t('zoomframe.zoom')}
        display={`${(s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio).toFixed(1)}x`}
        min={1}
        max={10}
        step={0.1}
        value={s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio}
        onChange={(v) => update({ zoomRatio: v })}
      />
      </SettingItem>

      {/* Frame Rate */}
      <SettingItem id="zoomFrame.frameRate">
      <FieldRow label={t('zoomframe.frameRate')}>
        <Select
          disabled={readOnly()}
          value={String(s()?.camera?.frameRate ?? API_DEFAULTS.cameraFrameRate)}
          options={FRAME_RATE_OPTIONS.map((r) => ({ value: String(r), label: `${r} fps` }))}
          onChange={(v) => update({ frameRate: parseInt(v) })}
          ariaLabel={t('zoomframe.frameRate')}
        />
      </FieldRow>
      </SettingItem>

      {/* Resolution */}
      <SettingItem id="zoomFrame.resolution">
      <FieldRow label={t('common.resolution')}>
        <Select
          disabled={readOnly()}
          value={s()?.camera?.resolution ?? API_DEFAULTS.cameraResolution}
          options={Object.entries(RESOLUTION_LABELS).map(([k, v]) => ({ value: k, label: v }))}
          onChange={(v) => update({ resolution: v as Resolution })}
          ariaLabel={t('common.resolution')}
        />
      </FieldRow>
      </SettingItem>

      <SettingItem id="zoomFrame.rotation">
      <FieldRow label={t('zoomframe.rotation')}>
        <Select
          disabled={readOnly()}
          value={String(s()?.camera?.outputRotation ?? API_DEFAULTS.cameraOutputRotation)}
          options={OUTPUT_ROTATION_OPTIONS.map((r) => ({ value: String(r), label: `${r}°` }))}
          onChange={(v) => update({ outputRotation: parseInt(v) as OutputRotation })}
          ariaLabel={t('zoomframe.rotation')}
        />
      </FieldRow>
      </SettingItem>
      <SettingItem id="zoomFrame.orientationLocked">
      <ToggleField
        disabled={readOnly()}
        label={t('zoomframe.orientationLocked')}
        checked={s()?.camera?.orientationLocked ?? API_DEFAULTS.cameraOrientationLocked}
        onChange={(v) => update({ orientationLocked: v })}
      />
      </SettingItem>
    </Panel>
  )
}
