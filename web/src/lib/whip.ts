// Pure WHIP-push presentation helpers for the WHIP Push settings card — the
// storageForecast pattern: the status snapshot's wire values and the STUN
// field map to display text over plain values, so vitest can pin every
// mapping without a browser.

/** The whipStatus wire names on the status snapshot (absent on pre-WHIP firmware). */
export type WhipStatusWire = 'idle' | 'connecting' | 'connected' | 'error'

export interface WhipStatusInput {
  /** status.streaming.whipStatus; undefined on older devices. */
  status: WhipStatusWire | undefined
  /** status.streaming.whipActive — true while the push output runs. */
  active: boolean | undefined
  /** status.streaming.whipError — the readable failure reason. */
  error?: string | null
}

export interface WhipStatusView {
  /** One-line state label for the status banner. */
  label: string
  /** The failure reason; empty outside the error state. */
  detail: string
  /** The status-banner variant the card renders. */
  variant: 'info' | 'success' | 'warning' | 'error'
}

/**
 * Maps the WHIP slice of the status snapshot onto the card's status line.
 * `connecting` outranks `active` (the connect is still in flight), while a
 * bare `active` without a status reads as connected — pre-WHIP firmware
 * omits the fields entirely and lands on idle.
 */
export function whipStatusView(input: WhipStatusInput): WhipStatusView {
  if (input.status === 'error') {
    return { label: 'Error', detail: (input.error ?? '').trim() || 'WHIP push failed', variant: 'error' }
  }
  if (input.status === 'connecting') {
    return { label: 'Connecting…', detail: '', variant: 'warning' }
  }
  if (input.status === 'connected' || (input.active ?? false)) {
    return { label: 'Connected', detail: '', variant: 'success' }
  }
  return { label: 'Idle', detail: '', variant: 'info' }
}

export interface WhipStunFieldView {
  /** The input's placeholder — the StreamDefaults default server. */
  placeholder: string
  /** The field's hint line; a validation warning replaces the neutral hint. */
  hint: string
  /** True when the typed value carries a scheme the server would reject. */
  invalid: boolean
}

/**
 * The STUN field's placeholder/hint/validation state. The server takes a
 * bare `host[:port]` (it assembles the `stun:` URI for iceServers itself),
 * so a pasted `stun:` or `https://` prefix is flagged before the save — and
 * a blank value is valid: it means no iceServers, i.e. host candidates only
 * (LAN-only reachability).
 */
export function whipStunField(value: string, defaultStun: string): WhipStunFieldView {
  const trimmed = value.trim()
  const invalid = /^(stun|stuns|http|https):/i.test(trimmed)
  return {
    placeholder: defaultStun,
    invalid,
    hint: invalid
      ? 'Enter a bare host[:port] — drop the scheme; the server assembles the stun: URI.'
      : trimmed
        ? 'One STUN server for one-shot ICE gathering.'
        : `Empty means host candidates only (LAN-only reachability); the default is ${defaultStun}.`,
  }
}
