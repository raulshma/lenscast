/**
 * The one verdict for "what does the ambient-light readout say right now",
 * shared by the overlay card, the overlay panel and the rule editor's live
 * lux row — the web twin of the app's AutomationViewModel.illuminanceReadout,
 * so all three surfaces (and both platforms) speak the same states for the
 * same payload:
 *
 * - a sanitized reading (`{ kind: 'value' }`) wins whenever one is present —
 *   a measured lux is real however odd the verdict field beside it looks;
 * - `{ kind: 'unavailable' }` only on a KNOWN `lightSensorAvailable: false`;
 * - `{ kind: 'waiting' }` for every silence — a sensor still debouncing its
 *   first reading, a status that has not landed yet, a device too old to
 *   publish the pair. Silence is never rendered as "no sensor": claiming
 *   absence from absence is the one lie this verdict exists to prevent.
 *
 * Pure and i18n-free: callers map the kind onto their own strings and roles.
 */

export type IlluminanceVerdict =
  | { kind: 'value'; lux: number }
  | { kind: 'unavailable' }
  | { kind: 'waiting' }

/** The reading + verdict fields a status document (or the editor's poll) carries. */
export type IlluminanceSource = {
  illuminanceLux?: number | null
  lightSensorAvailable?: boolean | null
} | null | undefined

/** The device's own invalid-reading sentinels, folded into "no reading". */
function sanitizedLux(lux: number | null | undefined): number | null {
  return typeof lux === 'number' && Number.isFinite(lux) && lux >= 0 ? lux : null
}

export function illuminanceVerdict(status: IlluminanceSource): IlluminanceVerdict {
  const lux = sanitizedLux(status?.illuminanceLux)
  if (lux != null) return { kind: 'value', lux }
  if (status?.lightSensorAvailable === false) return { kind: 'unavailable' }
  return { kind: 'waiting' }
}

/**
 * The verdict's two render-facing projections — the sanitized reading (null
 * when there is not one) and the known-absence flag — so a `Show` branch can
 * read plain accessors instead of narrowing a discriminated union through a
 * reactive call.
 */
export function illuminanceLuxValue(status: IlluminanceSource): number | null {
  const verdict = illuminanceVerdict(status)
  return verdict.kind === 'value' ? verdict.lux : null
}

export function illuminanceUnavailable(status: IlluminanceSource): boolean {
  return illuminanceVerdict(status).kind === 'unavailable'
}
