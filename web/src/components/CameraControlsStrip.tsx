import { For, Show } from 'solid-js'
import { createCameraControls, type CameraControlsDeps } from '../hooks/useCameraControls'
import { zoomReadout } from '../lib/cameraControlsPolicy'
import { FOCUS_MODE_I18N_KEYS, STRIP_WB_PRESETS, WB_MODE_I18N_KEYS, lensLabel } from '../lib/cameraLabels'
import { FOCUS_MODE_LABELS, type FocusMode, type WhiteBalance } from '../types'
import { t } from '../lib/i18n'

/**
 * The classic skin of the floating camera controls strip, rendered inside the
 * preview container through StreamPreview's `stripOverlay` slot (LegacyShell
 * passes this in; the next UI passes its kit-based twin). All the behavior
 * lives in the shared useCameraControls factory — this file is markup only:
 * glass-pill styling from App.css, classic inline SVGs per house convention.
 *
 * Layout is five `.cam-strip-group` sections split by hairline dividers —
 * zoom, lens, exposure, picture (focus + WB), utility (torch + collapse) —
 * so the strip wraps at group boundaries on narrow previews instead of
 * orphaning single controls.
 */
export default function CameraControlsStrip(props: CameraControlsDeps) {
  // The props structurally ARE the hook's deps — no adapter needed.
  const cam = createCameraControls(props)
  // Every camera write is admin-only on the device (RoleGatePolicy): the
  // viewer pair sees the strip but each control is disabled with a hint,
  // instead of today's silent 403s.
  const locked = () => !cam.canControl()
  const lockTitle = () => (locked() ? t('cam.adminHint') : undefined)

  // Icon SVGs are inlined per usage site, NOT shared consts: a Solid JSX
  // value is a live DOM node, and a second mount point steals it from the
  // first (the zoom steppers once rendered as empty circles because the
  // exposure steppers moved their nodes).
  const minusSvg = () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  )
  const plusSvg = () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  )
  const sunSvg = (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <line x1="12" y1="2" x2="12" y2="5" />
      <line x1="12" y1="19" x2="12" y2="22" />
      <line x1="2" y1="12" x2="5" y2="12" />
      <line x1="19" y1="12" x2="22" y2="12" />
      <line x1="4.9" y1="4.9" x2="7" y2="7" />
      <line x1="17" y1="17" x2="19.1" y2="19.1" />
      <line x1="4.9" y1="19.1" x2="7" y2="17" />
      <line x1="17" y1="7" x2="19.1" y2="4.9" />
    </svg>
  )

  return (
    <>
      <Show
        when={!cam.collapsed()}
        fallback={
          <button
            type="button"
            class="cam-strip-handle"
            onClick={cam.toggleCollapsed}
            aria-expanded={false}
            title={t('cam.expand')}
          >
            <span class="cam-strip-readout">{zoomReadout(cam.zoomRatio())}</span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style={{ transform: 'rotate(180deg)' }}>
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
        }
      >
        <div class="cam-strip" role="group" aria-label={t('cam.stripLabel')}>
          {/* Zoom: steppers + slider + readout; the reset chip only joins
              once the device is actually zoomed in. */}
          <div class="cam-strip-group" role="group" aria-label={t('preview.remoteZoom')}>
            <button
              type="button"
              class="cam-strip-btn"
              onClick={cam.zoomOut}
              disabled={locked()}
              title={lockTitle() ?? t('cam.zoomOut')}
              aria-label={t('cam.zoomOut')}
            >
              {minusSvg()}
            </button>
            <input
              id="remote-zoom-slider"
              type="range"
              class="cam-strip-slider"
              aria-label={t('preview.remoteZoom')}
              min="1"
              max={String(cam.zoomMax())}
              step="0.5"
              value={cam.zoomRatio()}
              disabled={locked()}
              onInput={(e) => cam.setZoom(parseFloat(e.currentTarget.value))}
            />
            <button
              type="button"
              class="cam-strip-btn"
              onClick={cam.zoomIn}
              disabled={locked()}
              title={lockTitle() ?? t('cam.zoomIn')}
              aria-label={t('cam.zoomIn')}
            >
              {plusSvg()}
            </button>
            <span class="cam-strip-readout">{zoomReadout(cam.zoomRatio())}</span>
            <Show when={cam.zoomRatio() > 1}>
              <button
                type="button"
                class="cam-strip-chip"
                onClick={cam.resetWide}
                disabled={locked()}
                title={lockTitle() ?? t('cam.resetZoom')}
              >
                {t('cam.resetZoom')}
              </button>
            </Show>
          </div>

          {/* Lens picker — meaningful only when the device enumerates more than one. */}
          <Show when={cam.lensOptions().length > 1}>
            <div class="cam-strip-group cam-strip-lenses" role="group" aria-label={t('cam.lens')}>
              <For each={cam.lensOptions()}>
                {(lens) => (
                  <button
                    type="button"
                    class="cam-strip-chip"
                    classList={{ 'cam-strip-chip-active': lens.selected }}
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
          <div class="cam-strip-group" role="group" aria-label={t('cam.exposure')}>
            <span class="cam-strip-glyph">{sunSvg}</span>
            <button
              type="button"
              class="cam-strip-btn"
              onClick={() => cam.stepExposure(-1)}
              disabled={locked()}
              title={lockTitle() ?? t('cam.exposureLower')}
              aria-label={t('cam.exposureLower')}
            >
              {minusSvg()}
            </button>
            <span class="cam-strip-readout" title={t('exposure.compensation')}>
              {cam.exposure() > 0 ? `+${cam.exposure()}` : cam.exposure()}
            </span>
            <button
              type="button"
              class="cam-strip-btn"
              onClick={() => cam.stepExposure(1)}
              disabled={locked()}
              title={lockTitle() ?? t('cam.exposureRaise')}
              aria-label={t('cam.exposureRaise')}
            >
              {plusSvg()}
            </button>
          </div>

          {/* Focus / white balance quick-selects (MANUAL WB stays settings-only),
              each with a micro-label so the two "Auto" boxes tell themselves apart. */}
          <Show when={cam.focusMode() || cam.whiteBalance()}>
            <div class="cam-strip-group" role="group" aria-label={t('cam.picture')}>
              <Show when={cam.focusMode()}>
                <label class="cam-strip-field">
                  <span class="cam-strip-fieldlabel">{t('focus.title')}</span>
                  <select
                    class="cam-strip-select"
                    aria-label={t('focus.title')}
                    title={lockTitle() ?? t('focus.title')}
                    value={cam.focusMode()!}
                    disabled={locked()}
                    onChange={(e) => cam.setFocusMode(e.currentTarget.value as FocusMode)}
                  >
                    {Object.keys(FOCUS_MODE_LABELS).map((k) => (
                      <option value={k}>{t(FOCUS_MODE_I18N_KEYS[k as FocusMode])}</option>
                    ))}
                  </select>
                </label>
              </Show>
              <Show when={cam.whiteBalance()}>
                <label class="cam-strip-field">
                  <span class="cam-strip-fieldlabel">{t('cam.wbShort')}</span>
                  <select
                    class="cam-strip-select"
                    aria-label={t('wb.title')}
                    title={lockTitle() ?? t('wb.title')}
                    value={cam.whiteBalance()!}
                    disabled={locked()}
                    onChange={(e) => cam.setWhiteBalance(e.currentTarget.value as WhiteBalance)}
                  >
                    {STRIP_WB_PRESETS.map((wb) => (
                      <option value={wb}>{t(WB_MODE_I18N_KEYS[wb])}</option>
                    ))}
                  </select>
                </label>
              </Show>
            </div>
          </Show>

          {/* Torch: optimistic flip, the device snapshot settles the truth. */}
          <div class="cam-strip-group cam-strip-utility">
            <button
              type="button"
              class="cam-strip-btn"
              classList={{ 'cam-strip-btn-toggled': cam.torchOn() }}
              aria-pressed={cam.torchOn()}
              onClick={cam.toggleTorch}
              disabled={locked()}
              title={lockTitle() ?? t('preview.torchTitle')}
              aria-label={cam.torchOn() ? t('preview.torchOn') : t('preview.torch')}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M8 2h8l-1 7h3L10 22l2-9H7l1-11z" />
              </svg>
            </button>

            <button
              type="button"
              class="cam-strip-btn cam-strip-collapse"
              onClick={cam.toggleCollapsed}
              aria-expanded={true}
              title={t('cam.collapse')}
              aria-label={t('cam.collapse')}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="6 9 12 15 18 9" />
              </svg>
            </button>
          </div>
        </div>
      </Show>

      <Show when={cam.stripMsg()}>
        <div class="cam-strip-msg" role="status">{cam.stripMsg()}</div>
      </Show>
    </>
  )
}
