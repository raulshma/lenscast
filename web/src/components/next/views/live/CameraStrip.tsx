import { For, Show } from 'solid-js'
import type { NextState } from '../../NextState'
import { createCameraControls } from '../../../../hooks/useCameraControls'
import { zoomReadout } from '../../../../lib/cameraControlsPolicy'
import { FOCUS_MODE_I18N_KEYS, STRIP_WB_PRESETS, WB_MODE_I18N_KEYS, lensLabel } from '../../../../lib/cameraLabels'
import { FOCUS_MODE_LABELS, type FocusMode, type WhiteBalance } from '../../../../types'
import { t } from '../../../../lib/i18n'
import { IconButton, Select, Slider } from '../../ui'
import { IconChevronDown, IconMinus, IconPlus, IconTorch } from '../../../icons'

/**
 * The next skin of the floating camera controls strip — the kit twin of the
 * classic CameraControlsStrip, rendered inside the preview container through
 * StreamPreview's `stripOverlay` slot. All behavior lives in the shared
 * useCameraControls factory; this file is kit composition only (nx buttons,
 * the shared slider/select, and the same nx-chip lens row the system view's
 * LensPanel renders).
 */
export default function CameraStrip(props: { state: NextState }) {
  const state = props.state
  const cam = createCameraControls({
    status: state.status,
    lenses: state.lenses,
    settings: state.settings,
    isViewer: state.isViewer,
    updateCamera: state.updateCamera,
    handleSelectLens: state.handleSelectLens,
  })
  // Every camera write is admin-only on the device (RoleGatePolicy): the
  // viewer pair sees the strip but each control is disabled with a hint.
  const locked = () => !cam.canControl()
  const lockTitle = () => (locked() ? t('cam.adminHint') : undefined)

  return (
    <Show
      when={!cam.collapsed()}
      fallback={
        <button
          type="button"
          class="nx-cam-strip-handle"
          onClick={cam.toggleCollapsed}
          aria-expanded={false}
          title={t('cam.expand')}
        >
          <span class="nx-mono">{zoomReadout(cam.zoomRatio())}</span>
          <IconChevronDown size={14} class="nx-cam-strip-chevron-flipped" />
        </button>
      }
    >
      <div class="nx-cam-strip" role="group" aria-label={t('cam.stripLabel')}>
        {/* Zoom: steppers + slider + readout + wide reset. */}
        <div class="nx-cam-strip-group" role="group" aria-label={t('preview.remoteZoom')}>
          <IconButton
            label={t('cam.zoomOut')}
            variant="ghost"
            size="sm"
            disabled={locked()}
            title={lockTitle() ?? t('cam.zoomOut')}
            onClick={cam.zoomOut}
          >
            <IconMinus size={16} />
          </IconButton>
          <Slider
            value={cam.zoomRatio()}
            min={1}
            max={cam.zoomMax()}
            step={0.5}
            ariaLabel={t('preview.remoteZoom')}
            disabled={locked()}
            onChange={(v) => cam.setZoom(v)}
          />
          <IconButton
            label={t('cam.zoomIn')}
            variant="ghost"
            size="sm"
            disabled={locked()}
            title={lockTitle() ?? t('cam.zoomIn')}
            onClick={cam.zoomIn}
          >
            <IconPlus size={16} />
          </IconButton>
          <span class="nx-mono nx-cam-strip-readout">{zoomReadout(cam.zoomRatio())}</span>
          <button
            type="button"
            class="nx-chip"
            onClick={cam.resetWide}
            disabled={locked()}
            title={lockTitle() ?? t('cam.wide')}
          >
            {t('cam.wide')}
          </button>
        </div>

        {/* Lens picker — meaningful only when the device enumerates more than one. */}
        <Show when={cam.lensOptions().length > 1}>
          <div class="nx-cam-strip-group" role="group" aria-label={t('cam.lens')}>
            <For each={cam.lensOptions()}>
              {(lens) => (
                <button
                  type="button"
                  class="nx-chip"
                  classList={{ 'nx-chip-active': lens.selected }}
                  aria-pressed={lens.selected}
                  onClick={() => cam.selectLens(lens.index)}
                  disabled={locked()}
                  title={lockTitle() ?? lensLabel(lens)}
                >
                  {lensLabel(lens)}
                </button>
              )}
            </For>
          </div>
        </Show>

        {/* Exposure compensation: integer index steps through the camera settings. */}
        <div class="nx-cam-strip-group" role="group" aria-label={t('cam.exposure')}>
          <IconButton
            label={t('cam.exposureLower')}
            variant="ghost"
            size="sm"
            disabled={locked()}
            title={lockTitle() ?? t('cam.exposureLower')}
            onClick={() => cam.stepExposure(-1)}
          >
            <IconMinus size={16} />
          </IconButton>
          <span class="nx-mono nx-cam-strip-readout" title={t('exposure.compensation')}>
            {cam.exposure() > 0 ? `+${cam.exposure()}` : cam.exposure()}
          </span>
          <IconButton
            label={t('cam.exposureRaise')}
            variant="ghost"
            size="sm"
            disabled={locked()}
            title={lockTitle() ?? t('cam.exposureRaise')}
            onClick={() => cam.stepExposure(1)}
          >
            <IconPlus size={16} />
          </IconButton>
        </div>

        {/* Focus / white balance quick-selects (MANUAL WB stays settings-only). */}
        <Show when={cam.focusMode()}>
          <Select
            small
            ariaLabel={t('focus.title')}
            value={cam.focusMode()!}
            disabled={locked()}
            onChange={(v) => cam.setFocusMode(v as FocusMode)}
            options={() => Object.keys(FOCUS_MODE_LABELS).map((k) => ({ value: k, label: t(FOCUS_MODE_I18N_KEYS[k as FocusMode]) }))}
          />
        </Show>
        <Show when={cam.whiteBalance()}>
          <Select
            small
            ariaLabel={t('wb.title')}
            value={cam.whiteBalance()!}
            disabled={locked()}
            onChange={(v) => cam.setWhiteBalance(v as WhiteBalance)}
            options={() => STRIP_WB_PRESETS.map((wb) => ({ value: wb, label: t(WB_MODE_I18N_KEYS[wb]) }))}
          />
        </Show>

        {/* Torch: optimistic flip, the device snapshot settles the truth. */}
        <IconButton
          label={cam.torchOn() ? t('preview.torchOn') : t('preview.torch')}
          variant={cam.torchOn() ? 'toggled' : 'ghost'}
          size="sm"
          ariaPressed={cam.torchOn()}
          disabled={locked()}
          title={lockTitle() ?? t('preview.torchTitle')}
          onClick={cam.toggleTorch}
        >
          <IconTorch size={16} />
        </IconButton>

        <IconButton
          label={t('cam.collapse')}
          variant="ghost"
          size="sm"
          ariaExpanded={true}
          onClick={cam.toggleCollapsed}
        >
          <IconChevronDown size={14} />
        </IconButton>
      </div>

      <Show when={cam.stripMsg()}>
        <div class="nx-cam-strip-msg" role="status">{cam.stripMsg()}</div>
      </Show>
    </Show>
  )
}
