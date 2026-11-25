import type { StatusVariant } from '../../../../../lib/statusVariant'
import type { LedTone } from '../../../ui'

/**
 * The next-UI kit's rendering maps for a transport status variant, shared by
 * the RTMP and WHIP panels (which used to carry private identical copies):
 * the banner class and the LED tone. The colour law decides each row:
 * connected is healthy (ok), connecting is the operator's wait (amber at
 * soft strength), a fault is bad — and idle is chrome (the quiet neutral
 * plate, the off LED), never a tinted banner.
 */

export const VARIANT_BANNER: Record<StatusVariant, string> = {
  info: 'nx-banner-neutral',
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
