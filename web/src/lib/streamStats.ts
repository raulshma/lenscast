import type { ConnectionQualityStatus, DeviceStatus } from '../types'
import type { PlayerMode } from '../video/playerLadder'

// Stream stats lane picking (issue #25): /api/status carries two measured
// lanes — the MJPEG lane (per-client windows in `connectionQuality`, the
// throughput/fps of actual frame production to web viewers) and the encoded
// lane (`encodedStream`, what the shared H264 encoder actually produced,
// null while no encoded stream runs). The dashboard's stats blocks follow
// the preview's actual lane: an MJPEG rung shows the MJPEG lane, a
// WHEP/H264/HLS rung shows the encoded lane. Pure verdicts over the status
// snapshot — the callers own the rendering, and absence is a typed null
// (dash/idle), never an invented number.

/** Which measured lane a preview rung consumes. */
export type StreamLaneKind = 'encoded' | 'mjpeg'

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

/** WHEP/H264/HLS all decode the encoded pipeline; MJPEG is its own lane. */
export function laneForMode(mode: PlayerMode | undefined): StreamLaneKind {
  return mode === 'mjpeg' ? 'mjpeg' : 'encoded'
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
 * The preview's lane: an MJPEG rung reads the MJPEG lane, every other rung
 * reads the encoded lane. Strict — an absent lane block picks null (the UI
 * hides/idles) rather than falling back to the other lane's numbers.
 */
export function pickPreviewLane(mode: PlayerMode | undefined, status: DeviceStatus | null | undefined): LanePick {
  if (!status) return null
  return laneForMode(mode) === 'mjpeg'
    ? mjpegLane(status.connectionQuality)
    : encodedLane(status.encodedStream)
}

export interface MeasuredRates {
  bitrateKbps: number | null
  fps: number | null
}

/**
 * The TX/FPS chips' read, lane-agnostic by priority: the encoded lane's
 * measured rates whenever the `encodedStream` block is present (presence IS
 * the running fact — the wire carries no flag), else the MJPEG lane's
 * measured rates. Nulls when neither lane has data — the chips render their
 * idle convention (hidden), never the adaptation ladders' congestion
 * estimate or a frozen last value.
 */
export function pickMeasuredRates(status: DeviceStatus | null | undefined): MeasuredRates {
  const encoded = status?.encodedStream
  if (encoded) {
    return { bitrateKbps: finiteOrNull(encoded.measuredBitrateKbps), fps: finiteOrNull(encoded.fps) }
  }
  const quality = status?.connectionQuality
  return {
    bitrateKbps: quality ? finiteOrNull(quality.avgThroughputKbps) : null,
    fps: quality ? finiteOrNull(quality.framesPerSecond) : null,
  }
}
