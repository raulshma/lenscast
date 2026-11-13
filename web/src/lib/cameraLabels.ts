import { t } from './i18n'
import type { FocusMode, LensInfo, WhiteBalance } from '../types'

// The camera labels every camera-control surface shares: lens chip names
// (classic LensSelector, next LensPanel, the camera strip) and the focus/WB
// option maps (settings cards, the strip's quick-selects). One source so a
// renamed wire value or catalog key lands everywhere at once.

export function fmtFocalLength(fl: number): string {
  if (fl <= 0) return ''
  return Number.isInteger(fl) ? `${fl}mm` : `${fl.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}mm`
}

/**
 * "2x" for whole magnifications, one trimmed decimal otherwise ("2.3x").
 * Language-independent like the focal suffix, so no catalog entry is needed.
 */
export function fmtZoomRatio(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio <= 0) return ''
  const rounded = Math.round(ratio * 10) / 10
  const text = Number.isInteger(rounded) ? `${rounded}` : `${rounded.toFixed(1)}`
  return `${text}x`
}

/**
 * The chip label, localized from the lens's display role — the backend hands
 * down a kind plus zoom ratio and an English base label, and the dashboard
 * builds its own string so the chips follow the dashboard language like the
 * rest of the UI.
 */
export function lensLabel(lens: LensInfo): string {
  const frontVariant = (variant: string) => t('lens.frontVariant', { variant })
  const zoomText = (lens.zoomRatio != null ? fmtZoomRatio(lens.zoomRatio) : '') || fmtFocalLength(lens.focalLength)
  switch (lens.kind) {
    case 'FRONT':
      return t('lens.front')
    case 'BACK':
      return t('lens.back')
    case 'ULTRAWIDE':
      return lens.isFront ? t('lens.frontUltrawide') : t('lens.ultrawide')
    case 'WIDE':
      return t('lens.wide')
    case 'ZOOM':
      return lens.isFront ? frontVariant(zoomText) : zoomText
    case 'FOCAL_MM':
      return lens.isFront ? frontVariant(fmtFocalLength(lens.focalLength)) : fmtFocalLength(lens.focalLength)
    case 'CAMERA_ID':
      return t('lens.cameraId', { id: lens.id })
    default:
      // Older backend without kinds — the English base label.
      return lens.label
  }
}

/** Wire value → i18n key for the focus-mode options (labels live in the catalog). */
export const FOCUS_MODE_I18N_KEYS: Record<FocusMode, string> = {
  AUTO: 'common.auto',
  MANUAL: 'common.manual',
  MACRO: 'option.macro',
  CONTINUOUS_PICTURE: 'option.focus.contPhoto',
  CONTINUOUS_VIDEO: 'option.focus.contVideo',
}

/** Wire value → i18n key for the white-balance options (labels live in the catalog). */
export const WB_MODE_I18N_KEYS: Record<WhiteBalance, string> = {
  AUTO: 'common.auto',
  DAYLIGHT: 'option.wb.daylight',
  CLOUDY: 'option.wb.cloudy',
  INDOOR: 'option.wb.indoor',
  FLUORESCENT: 'option.wb.fluorescent',
  MANUAL: 'common.manual',
}

/**
 * The strip's white-balance presets: MANUAL needs the Kelvin slider, so it
 * stays a settings-only choice and the strip never offers it.
 */
export const STRIP_WB_PRESETS: readonly WhiteBalance[] = ['AUTO', 'DAYLIGHT', 'CLOUDY', 'INDOOR', 'FLUORESCENT']
