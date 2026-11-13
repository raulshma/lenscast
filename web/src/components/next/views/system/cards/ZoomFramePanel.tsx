import type { AllSettings, CameraSettings, OutputRotation, Resolution } from '../../../../../types'
import { RESOLUTION_LABELS, FRAME_RATE_OPTIONS, OUTPUT_ROTATION_OPTIONS } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { Panel, SliderField, FieldRow, Select, ToggleField } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  updateCamera: (patch: Partial<CameraSettings>) => void
}

export default function ZoomFramePanel(props: Props) {
  const s = () => props.settings()

  return (
    <Panel title={t('zoomframe.title')}>
      {/* Zoom */}
      <SliderField
        label={t('zoomframe.zoom')}
        display={`${(s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio).toFixed(1)}x`}
        min={1}
        max={10}
        step={0.1}
        value={s()?.camera?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio}
        onChange={(v) => props.updateCamera({ zoomRatio: v })}
      />

      {/* Frame Rate */}
      <FieldRow label={t('zoomframe.frameRate')}>
        <Select
          value={String(s()?.camera?.frameRate ?? API_DEFAULTS.cameraFrameRate)}
          options={FRAME_RATE_OPTIONS.map((r) => ({ value: String(r), label: `${r} fps` }))}
          onChange={(v) => props.updateCamera({ frameRate: parseInt(v) })}
          ariaLabel={t('zoomframe.frameRate')}
        />
      </FieldRow>

      {/* Resolution */}
      <FieldRow label={t('common.resolution')}>
        <Select
          value={s()?.camera?.resolution ?? API_DEFAULTS.cameraResolution}
          options={Object.entries(RESOLUTION_LABELS).map(([k, v]) => ({ value: k, label: v }))}
          onChange={(v) => props.updateCamera({ resolution: v as Resolution })}
          ariaLabel={t('common.resolution')}
        />
      </FieldRow>

      {/* Output rotation — the mounted-phone correction (issue #6) */}
      <FieldRow label={t('zoomframe.rotation')}>
        <Select
          value={String(s()?.camera?.outputRotation ?? API_DEFAULTS.cameraOutputRotation)}
          options={OUTPUT_ROTATION_OPTIONS.map((r) => ({ value: String(r), label: `${r}°` }))}
          onChange={(v) => props.updateCamera({ outputRotation: parseInt(v) as OutputRotation })}
          ariaLabel={t('zoomframe.rotation')}
        />
      </FieldRow>
      <ToggleField
        label={t('zoomframe.orientationLocked')}
        checked={s()?.camera?.orientationLocked ?? API_DEFAULTS.cameraOrientationLocked}
        onChange={(v) => props.updateCamera({ orientationLocked: v })}
      />
    </Panel>
  )
}
