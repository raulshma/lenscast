import type { StatusVariant } from '../../../../../lib/statusVariant'
import type { LedTone } from '../../../ui'

/**
 * The next-UI kit's rendering maps for a transport status variant, shared by
 * the RTMP and WHIP panels (which used to carry private identical copies):
 * the banner class and the LED tone. Connected is a healthy state, so it
 * reads as the kit's success banner + a green LED; only faults wear red.
 */

export const VARIANT_BANNER: Record<StatusVariant, string> = {
  info: 'nx-banner-info',
  success: 'nx-banner-success',
  warning: 'nx-banner-warning',
  error: 'nx-banner-danger',
}

export const VARIANT_LED: Record<StatusVariant, LedTone> = {
  info: 'off',
  success: 'ok',
  warning: 'warn',
  error: 'danger',
}
