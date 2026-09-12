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

/** The display strings whipStatusView emits — overridden by the i18n caller. */
export interface WhipStatusLabelSet {
  idle: string
  connecting: string
  connected: string
  error: string
  pushFailed: string
}

export const WHIP_STATUS_LABELS_EN: WhipStatusLabelSet = {
  idle: 'Idle',
  connecting: 'Connecting…',
  connected: 'Connected',
  error: 'Error',
  pushFailed: 'WHIP push failed',
}

/**
 * Maps the WHIP slice of the status snapshot onto the card's status line.
 * `connecting` outranks `active` (the connect is still in flight), while a
 * bare `active` without a status reads as connected — pre-WHIP firmware
 * omits the fields entirely and lands on idle. The default labels keep the
 * pure-English behavior the tests pin; the card passes localized ones.
 */
export function whipStatusView(
  input: WhipStatusInput,
  labels: WhipStatusLabelSet = WHIP_STATUS_LABELS_EN,
): WhipStatusView {
  if (input.status === 'error') {
    return { label: labels.error, detail: (input.error ?? '').trim() || labels.pushFailed, variant: 'error' }
  }
  if (input.status === 'connecting') {
    return { label: labels.connecting, detail: '', variant: 'warning' }
  }
  if (input.status === 'connected' || (input.active ?? false)) {
    return { label: labels.connected, detail: '', variant: 'success' }
  }
  return { label: labels.idle, detail: '', variant: 'info' }
}

export interface WhipStunFieldView {
  /** The input's placeholder — the StreamDefaults default server. */
  placeholder: string
  /** The field's hint line; a validation warning replaces the neutral hint. */
  hint: string
  /** True when the typed value carries a scheme the server would reject. */
  invalid: boolean
}

/** The hint strings whipStunField emits — overridden by the i18n caller. */
export interface WhipStunHintSet {
  invalid: string
  present: string
  /** May carry a {default} placeholder interpolated with defaultStun. */
  empty: string
}

export const WHIP_STUN_HINTS_EN: WhipStunHintSet = {
  invalid: 'Enter a bare host[:port] — drop the scheme; the server assembles the stun: URI.',
  present: 'One STUN server for one-shot ICE gathering.',
  empty: 'Empty means host candidates only (LAN-only reachability); the default is {default}.',
}

/**
 * The STUN field's placeholder/hint/validation state. The server takes a
 * bare `host[:port]` (it assembles the `stun:` URI for iceServers itself),
 * so a pasted `stun:` or `https://` prefix is flagged before the save — and
 * a blank value is valid: it means no iceServers, i.e. host candidates only
 * (LAN-only reachability).
 */
export function whipStunField(value: string, defaultStun: string, hints: WhipStunHintSet = WHIP_STUN_HINTS_EN): WhipStunFieldView {
  const trimmed = value.trim()
  const invalid = /^(stun|stuns|http|https):/i.test(trimmed)
  return {
    placeholder: defaultStun,
    invalid,
    hint: invalid
      ? hints.invalid
      : trimmed
        ? hints.present
        : hints.empty.replace('{default}', defaultStun),
  }
}
