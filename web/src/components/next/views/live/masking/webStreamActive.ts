import { API_DEFAULTS } from '../../../../../api/defaults'
import type { NextState } from '../../../NextState'

/** The one member of NextState the gate reads. */
type StreamStatus = Pick<NextState, 'status'>

/**
 * True while the device's web stream is on air — the predicate StreamPreview
 * gates its live picture on. Masking zones are painted on that picture, so
 * the editor's two gates (the Live view's answer to an armed edit, and the
 * inspector's "Edit zones" button) ask exactly what the stage asks, and can
 * never disagree with it. Absent on a status that has not loaded: the DTO
 * default, which is off.
 */
export function webStreamActive(state: StreamStatus): boolean {
  return state.status()?.streaming?.webStreamingActive ?? API_DEFAULTS.webStreamingActive
}
