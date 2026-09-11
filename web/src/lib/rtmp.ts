// Pure RTMP-push presentation helpers for the RTMP Push settings card — the
// whip.ts pattern: the status snapshot's wire values and the push-target
// field map to display text over plain values, so vitest can pin every
// mapping without a browser.

/** The rtmpStatus wire names on the status snapshot (absent on pre-RTMP firmware). */
export type RtmpStatusWire = 'idle' | 'connecting' | 'connected' | 'error'

export interface RtmpStatusInput {
  /** status.streaming.rtmpStatus; undefined on older devices. */
  status: RtmpStatusWire | undefined
  /** status.streaming.rtmpActive — true while the push output runs. */
  active: boolean | undefined
  /** status.streaming.rtmpError — the readable failure reason. */
  error?: string | null
}

export interface RtmpStatusView {
  /** One-line state label for the status banner. */
  label: string
  /** The failure reason; empty outside the error state. */
  detail: string
  /** The status-banner variant the card renders. */
  variant: 'info' | 'success' | 'warning' | 'error'
}

/**
 * Maps the RTMP slice of the status snapshot onto the card's status line.
 * `connecting` outranks `active` (the connect handshake is still in flight),
 * while a bare `active` without a status reads as connected — pre-RTMP
 * firmware omits the fields entirely and lands on idle.
 */
export function rtmpStatusView(input: RtmpStatusInput): RtmpStatusView {
  if (input.status === 'error') {
    return { label: 'Error', detail: (input.error ?? '').trim() || 'RTMP push failed', variant: 'error' }
  }
  if (input.status === 'connecting') {
    return { label: 'Connecting…', detail: '', variant: 'warning' }
  }
  if (input.status === 'connected' || (input.active ?? false)) {
    return { label: 'Connected', detail: '', variant: 'success' }
  }
  return { label: 'Idle', detail: '', variant: 'info' }
}

export interface RtmpUrlFieldView {
  /** The field's hint line; a validation warning replaces the neutral hint. */
  hint: string
  /** True when the typed value carries a scheme or host the server would reject. */
  invalid: boolean
  /** False when empty or malformed — the Start button stays disabled. */
  valid: boolean
}

/** `rtmp://` or `rtmps://` — everything else (https:, plain host, rtsp:) is refused. */
const RTMP_SCHEME = /^rtmps?:\/\//i

/**
 * The push-target field's hint/validation state. Client-side the check is
 * intentionally shallow — scheme plus a non-empty host — because the
 * server's authoritative RtmpUrl.parse runs at start time; a blank value is
 * not flagged as an error: the field is write-only (the stream key is
 * embedded in the URL, so responses never carry it) and starts blank
 * whether or not the device holds a stored URL.
 */
export function rtmpUrlField(value: string): RtmpUrlFieldView {
  const trimmed = value.trim()
  if (!trimmed) {
    return {
      hint: 'Paste the push target from your streaming server, e.g. rtmp://ingest.example.com/live/stream-key — saved once, never echoed back.',
      invalid: false,
      valid: false,
    }
  }
  if (!RTMP_SCHEME.test(trimmed)) {
    return {
      hint: 'Start with rtmp:// or rtmps:// — the server refuses any other scheme.',
      invalid: true,
      valid: false,
    }
  }
  // Host = everything between the scheme (and any user:pass@ userinfo) and
  // the first path/query separator.
  const authority = trimmed.replace(RTMP_SCHEME, '').split(/[/?]/, 1)[0]
  const host = authority.includes('@') ? authority.slice(authority.lastIndexOf('@') + 1) : authority
  if (!host) {
    return {
      hint: 'The URL needs a host — rtmp:///path is missing where to publish.',
      invalid: true,
      valid: false,
    }
  }
  return {
    hint: 'Prefer rtmps:// when the server supports TLS — the stream key rides in the URL.',
    invalid: false,
    valid: true,
  }
}
