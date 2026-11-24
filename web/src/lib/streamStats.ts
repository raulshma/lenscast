import type { ConnectionQualityStatus, DeviceStatus, WhepStreamStatus } from '../types'
import type { PlayerMode } from '../video/playerLadder'

// Stream stats lane picking (issue #25): /api/status carries three measured
// lanes — the MJPEG lane (per-client windows in `connectionQuality`, the
// throughput/fps of actual frame production to web viewers), the encoded
// lane (`encodedStream`, what the shared H264 encoder actually produced,
// null while no encoded stream runs) and the WHEP lane (`whepStream`, what
// libwebrtc sent to the WebRTC preview's viewers, absent until one has
// reported). The dashboard's stats blocks follow the preview's own lane
// and nothing else: an MJPEG rung shows the MJPEG lane, a WHEP rung the
// WHEP lane, an H264/HLS rung the encoded lane. Pure verdicts over the
// status snapshot — the callers own the rendering, and absence is a typed
// null (dash/idle), never an invented number and never another lane's
// measurement.

/** Which measured lane a preview rung consumes. */
export type StreamLaneKind = 'encoded' | 'mjpeg' | 'whep'

/**
 * One lane's normalized measured read: numbers the UI can render directly.
 * `null` fields mean the lane exists but has nothing measured yet (the
 * honest 0/absent case) — the UI renders its idle convention, never a
 * placeholder value from the other lane.
 */
export interface LaneStats {
  lane: StreamLaneKind
  /** Window-measured bitrate (kbps), or null when nothing is measured. */
  bitrateKbps: number | null
  /** Window-measured fps, or null when nothing is measured. */
  fps: number | null
  /** Encoded lane only: the configured target the adaptive ladder drives. */
  targetBitrateKbps?: number
  codec?: string
  width?: number
  height?: number
}

/** The lane pick: null = no lane data for this preview mode right now. */
export type LanePick = LaneStats | null

/**
 * The preview's own lane, per rung. WHEP is a lane of its own: libwebrtc
 * encodes the WebRTC preview per viewer, and those bytes never cross the
 * encoded-stream hub that `encodedStream` measures — only the H264/HLS
 * rungs decode that pipeline. MJPEG is its own lane. No rung (a non-preview
 * host) reads the encoded lane.
 */
export function laneForMode(mode: PlayerMode | undefined): StreamLaneKind {
  if (mode === 'mjpeg') return 'mjpeg'
  if (mode === 'whep') return 'whep'
  return 'encoded'
}

/** Finite numbers only — a wire anomaly renders as absence, never NaN.
 *  The one coerce shape: display layers compose it (?? 0), never re-roll
 *  their own. */
export function finiteOrNull(value: unknown): number | null {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

/** The MJPEG lane's measured read; null when the status has no quality block. */
export function mjpegLane(quality: ConnectionQualityStatus | undefined | null): LaneStats | null {
  if (!quality) return null
  return {
    lane: 'mjpeg',
    bitrateKbps: finiteOrNull(quality.avgThroughputKbps),
    fps: finiteOrNull(quality.framesPerSecond),
  }
}

/** The encoded lane's measured read; null while no encoded stream runs. */
export function encodedLane(encoded: DeviceStatus['encodedStream'] | undefined | null): LaneStats | null {
  if (!encoded) return null
  return {
    lane: 'encoded',
    bitrateKbps: finiteOrNull(encoded.measuredBitrateKbps),
    fps: finiteOrNull(encoded.fps),
    targetBitrateKbps: finiteOrNull(encoded.targetBitrateKbps) ?? undefined,
    codec: encoded.codec,
    width: finiteOrNull(encoded.width) ?? undefined,
    height: finiteOrNull(encoded.height) ?? undefined,
  }
}

/**
 * The WHEP lane's measured read; null until a viewer session has reported.
 * No target — libwebrtc negotiates this lane per viewer, so the wire block
 * carries none and nothing here invents one. A connected viewer that is
 * not encoding yet reads the honest 0s, not null.
 */
export function whepLane(whep: WhepStreamStatus | undefined | null): LaneStats | null {
  if (!whep) return null
  return {
    lane: 'whep',
    bitrateKbps: finiteOrNull(whep.measuredBitrateKbps),
    fps: finiteOrNull(whep.fps),
    codec: whep.codec,
    width: finiteOrNull(whep.width) ?? undefined,
    height: finiteOrNull(whep.height) ?? undefined,
  }
}

/**
 * The preview's lane: each rung reads its own lane. Strict — an absent lane
 * block picks null (the UI hides/idles) rather than falling back to another
 * lane's numbers.
 */
export function pickPreviewLane(mode: PlayerMode | undefined, status: DeviceStatus | null | undefined): LanePick {
  if (!status) return null
  const kind = laneForMode(mode)
  if (kind === 'mjpeg') return mjpegLane(status.connectionQuality)
  if (kind === 'whep') return whepLane(status.whepStream)
  return encodedLane(status.encodedStream)
}
