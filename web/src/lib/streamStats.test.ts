import { describe, expect, it } from 'vitest'
import type { ConnectionQualityStatus, DeviceStatus, EncodedStreamStatus } from '../types'
import {
  encodedLane,
  laneForMode,
  mjpegLane,
  pickMeasuredRates,
  pickPreviewLane,
  type LaneStats,
} from './streamStats'

// Status fixtures in the Unit-3 wire shapes (web/contract/status.json).
const quality = (over: Partial<ConnectionQualityStatus> = {}): ConnectionQualityStatus => ({
  qualityLevel: 'GOOD',
  estimatedBandwidthKbps: 4200,
  avgThroughputKbps: 3900,
  minThroughputKbps: 3500,
  worstLatencyMs: 120,
  avgFrameSizeBytes: 45000,
  totalBytesSent: 104857600,
  activeClients: 2,
  framesPerSecond: 23.5,
  clientDetails: {},
  ...over,
})

const encoded = (over: Partial<EncodedStreamStatus> = {}): EncodedStreamStatus => ({
  measuredBitrateKbps: 1890,
  fps: 23.5,
  targetBitrateKbps: 2000,
  codec: 'h264',
  width: 1280,
  height: 720,
  ...over,
})

describe('laneForMode', () => {
  it('maps the MJPEG rung to the MJPEG lane and every other rung to the encoded lane', () => {
    expect(laneForMode('mjpeg')).toBe('mjpeg')
    expect(laneForMode('whep')).toBe('encoded')
    expect(laneForMode('h264')).toBe('encoded')
    expect(laneForMode('hls')).toBe('encoded')
    expect(laneForMode(undefined)).toBe('encoded')
  })
})

describe('mjpegLane', () => {
  it('reads the measured throughput and fps', () => {
    const lane: LaneStats | null = mjpegLane(quality())
    expect(lane).toEqual({ lane: 'mjpeg', bitrateKbps: 3900, fps: 23.5 })
  })

  it('keeps the honest 0s (measured-zero stays 0, not null)', () => {
    const lane = mjpegLane(quality({ avgThroughputKbps: 0, framesPerSecond: 0 }))
    expect(lane).toEqual({ lane: 'mjpeg', bitrateKbps: 0, fps: 0 })
  })

  it('is null when the status has no quality block', () => {
    expect(mjpegLane(undefined)).toBeNull()
    expect(mjpegLane(null)).toBeNull()
  })

  it('renders a non-finite wire value as absence', () => {
    const lane = mjpegLane(quality({ avgThroughputKbps: Number.NaN }))
    expect(lane?.bitrateKbps).toBeNull()
    expect(lane?.fps).toBe(23.5)
  })
})

describe('encodedLane', () => {
  it('reads measured vs target, fps, codec and geometry', () => {
    expect(encodedLane(encoded())).toEqual({
      lane: 'encoded',
      bitrateKbps: 1890,
      fps: 23.5,
      targetBitrateKbps: 2000,
      codec: 'h264',
      width: 1280,
      height: 720,
    })
  })

  it('is null while no encoded stream runs', () => {
    expect(encodedLane(undefined)).toBeNull()
    expect(encodedLane(null)).toBeNull()
  })
})

describe('pickPreviewLane', () => {
  it('returns null for an absent status, whatever the mode', () => {
    expect(pickPreviewLane('mjpeg', null)).toBeNull()
    expect(pickPreviewLane('mjpeg', undefined)).toBeNull()
    expect(pickPreviewLane('whep', null)).toBeNull()
  })

  it('an MJPEG preview reads the MJPEG lane', () => {
    const status = { connectionQuality: quality() } as DeviceStatus
    expect(pickPreviewLane('mjpeg', status)).toEqual({ lane: 'mjpeg', bitrateKbps: 3900, fps: 23.5 })
  })

  it('an MJPEG preview with no clients (no quality block) picks null — no invented numbers', () => {
    const status = { encodedStream: encoded() } as DeviceStatus
    expect(pickPreviewLane('mjpeg', status)).toBeNull()
  })

  it('a WHEP/H264/HLS preview reads the encoded lane', () => {
    const status = { encodedStream: encoded() } as DeviceStatus
    for (const mode of ['whep', 'h264', 'hls'] as const) {
      expect(pickPreviewLane(mode, status)?.lane).toBe('encoded')
    }
  })

  it('an encoded preview while not encoding picks null instead of the MJPEG numbers', () => {
    const status = { connectionQuality: quality(), encodedStream: undefined } as DeviceStatus
    expect(pickPreviewLane('h264', status)).toBeNull()
  })

  it('never throws on a status missing both blocks', () => {
    expect(pickPreviewLane('mjpeg', {} as DeviceStatus)).toBeNull()
    expect(pickPreviewLane('hls', {} as DeviceStatus)).toBeNull()
  })
})

describe('pickMeasuredRates', () => {
  it('prefers the encoded lane when its block is present (presence is the running fact)', () => {
    const status = { connectionQuality: quality(), encodedStream: encoded() } as DeviceStatus
    expect(pickMeasuredRates(status)).toEqual({ bitrateKbps: 1890, fps: 23.5 })
  })

  it('falls back to the MJPEG lane when the encoded block is absent', () => {
    const status = { connectionQuality: quality() } as DeviceStatus
    expect(pickMeasuredRates(status)).toEqual({ bitrateKbps: 3900, fps: 23.5 })
    expect(pickMeasuredRates({ connectionQuality: quality(), encodedStream: undefined } as DeviceStatus))
      .toEqual({ bitrateKbps: 3900, fps: 23.5 })
  })

  it('returns nulls when neither lane has data', () => {
    expect(pickMeasuredRates(null)).toEqual({ bitrateKbps: null, fps: null })
    expect(pickMeasuredRates({} as DeviceStatus)).toEqual({ bitrateKbps: null, fps: null })
    expect(pickMeasuredRates({ connectionQuality: quality({ avgThroughputKbps: 0, framesPerSecond: 0 }) } as DeviceStatus))
      .toEqual({ bitrateKbps: 0, fps: 0 })
  })
})
