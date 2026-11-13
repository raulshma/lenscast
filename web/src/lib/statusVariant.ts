/**
 * The status-banner variant every transport status view resolves to
 * (`rtmpStatusView`, `whipStatusView` — the label/detail/variant triple),
 * plus the variant→tone map both shells' card headers read: four hand-rolled
 * switch cascades (RTMP/WHIP × classic/next) had already duplicated it once,
 * so the mapping lives here instead.
 */

export type StatusVariant = 'info' | 'success' | 'warning' | 'error'

export type StatusTone = 'success' | 'warning' | 'danger'

/**
 * The header-chip tone for a variant; null = the resting state (info) —
 * the chip is hidden entirely rather than rendered neutral, so anything
 * showing is worth seeing from across the page.
 */
export function statusTone(variant: StatusVariant): StatusTone | null {
  switch (variant) {
    case 'success': return 'success'
    case 'warning': return 'warning'
    case 'error': return 'danger'
    default: return null
  }
}
