import { describe, expect, it } from 'vitest'
import type {
  ConnectionQualityStatus,
  DeviceStatus,
  EncodedStreamStatus,
  WhepStreamStatus,
} from '../types'
import {
  encodedLane,
  laneForMode,
  mjpegLane,
  pickPreviewLane,
  whepLane,
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

const whep = (over: Partial<WhepStreamStatus> = {}): WhepStreamStatus => ({
  measuredBitrateKbps: 1700,
  fps: 24,
  codec: 'vp8',
  width: 600,
  height: 800,
  ...over,
})

describe('laneForMode', () => {
  it('maps each rung to its own lane: MJPEG, WHEP, and H264/HLS to the encoded lane', () => {
    expect(laneForMode('mjpeg')).toBe('mjpeg')
    expect(laneForMode('whep')).toBe('whep')
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

describe('whepLane', () => {
  it('reads the measured bitrate, fps, codec and geometry', () => {
    expect(whepLane(whep())).toEqual({
      lane: 'whep',
      bitrateKbps: 1700,
      fps: 24,
      codec: 'vp8',
      width: 600,
      height: 800,
    })
  })

  it('carries no target — the WHEP lane has none, so none is invented', () => {
    expect(whepLane(whep())?.targetBitrateKbps).toBeUndefined()
  })

  it('keeps the honest 0s of a viewer that is connected but not encoding yet', () => {
    expect(whepLane(whep({ measuredBitrateKbps: 0, fps: 0 }))).toEqual({
      lane: 'whep',
      bitrateKbps: 0,
      fps: 0,
      codec: 'vp8',
      width: 600,
      height: 800,
    })
  })

  it('leaves codec and geometry absent until the viewer session reports them', () => {
    const lane = whepLane(whep({ codec: undefined, width: undefined, height: undefined }))
    expect(lane?.codec).toBeUndefined()
    expect(lane?.width).toBeUndefined()
    expect(lane?.height).toBeUndefined()
    expect(lane?.bitrateKbps).toBe(1700)
  })

  it('is null until a viewer session has reported', () => {
    expect(whepLane(undefined)).toBeNull()
    expect(whepLane(null)).toBeNull()
  })

  it('renders a non-finite wire value as absence', () => {
    const lane = whepLane(whep({ measuredBitrateKbps: Number.NaN }))
    expect(lane?.bitrateKbps).toBeNull()
    expect(lane?.fps).toBe(24)
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
    const status = { encodedStream: encoded(), whepStream: whep() } as DeviceStatus
    expect(pickPreviewLane('mjpeg', status)).toBeNull()
  })

  it('a WHEP preview reads the WHEP lane', () => {
    const status = { connectionQuality: quality(), encodedStream: encoded(), whepStream: whep() } as DeviceStatus
    expect(pickPreviewLane('whep', status)).toEqual({
      lane: 'whep',
      bitrateKbps: 1700,
      fps: 24,
      codec: 'vp8',
      width: 600,
      height: 800,
    })
  })

  it('a WHEP preview picks null while no viewer has reported — never the encoded lane it never crosses', () => {
    // The old rule, inverted: libwebrtc encodes the WHEP preview itself, so
    // the encoded block beside it measures a lane this preview does not use.
    const status = { connectionQuality: quality(), encodedStream: encoded() } as DeviceStatus
    expect(pickPreviewLane('whep', status)).toBeNull()
  })

  it('an H264/HLS preview reads the encoded lane', () => {
    const status = { connectionQuality: quality(), encodedStream: encoded(), whepStream: whep() } as DeviceStatus
    for (const mode of ['h264', 'hls'] as const) {
      expect(pickPreviewLane(mode, status)).toEqual({
        lane: 'encoded',
        bitrateKbps: 1890,
        fps: 23.5,
        targetBitrateKbps: 2000,
        codec: 'h264',
        width: 1280,
        height: 720,
      })
    }
  })

  it('an encoded preview while not encoding picks null instead of the MJPEG numbers', () => {
    const status = { connectionQuality: quality(), encodedStream: undefined } as DeviceStatus
    expect(pickPreviewLane('h264', status)).toBeNull()
  })

  it('never throws on a status missing every block', () => {
    for (const mode of ['mjpeg', 'whep', 'h264', 'hls'] as const) {
      expect(pickPreviewLane(mode, {} as DeviceStatus)).toBeNull()
    }
  })
})
