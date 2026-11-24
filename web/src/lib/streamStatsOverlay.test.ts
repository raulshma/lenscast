import { describe, expect, it } from 'vitest'
import type { ConnectionQualityStatus, DeviceStatus } from '../types'
import {
  createStreamStatsOverlay,
  nextStreamStatsOverlayMode,
  parseStreamStatsOverlayMode,
  pickStreamStatsChips,
  streamStatsOverlayMounted,
  streamStatsOverlayVisible,
} from './streamStatsOverlay'

// The three measured lanes at once, in the wire shapes of
// web/contract/status.json — every lane present, so a rung reading another
// lane's numbers cannot hide behind an absent block.
const allLanes = {
  connectionQuality: {
    qualityLevel: 'GOOD',
    estimatedBandwidthKbps: 4200,
    avgThroughputKbps: 3900,
    minThroughputKbps: 3500,
    worstLatencyMs: 12,
    avgFrameSizeBytes: 45000,
    totalBytesSent: 0,
    activeClients: 1,
    framesPerSecond: 23.5,
    clientDetails: {},
  } as ConnectionQualityStatus,
  encodedStream: {
    measuredBitrateKbps: 1890,
    fps: 23.5,
    targetBitrateKbps: 2000,
    codec: 'h264',
    width: 1280,
    height: 720,
  },
  whepStream: { measuredBitrateKbps: 1700, fps: 24, codec: 'vp8', width: 600, height: 800 },
} as DeviceStatus

type AdaptiveBitrate = NonNullable<DeviceStatus['adaptiveBitrate']>

const adaptive = (over: Partial<AdaptiveBitrate> = {}): AdaptiveBitrate => ({
  enabled: true,
  qualityLevel: 'GOOD',
  currentQuality: 1,
  targetQuality: 1,
  currentFps: 23.5,
  targetFps: 24,
  estimatedBandwidthKbps: 4200,
  minClientThroughputKbps: 3500,
  activeClients: 1,
  adjustmentCount: 0,
  ...over,
})

/** Adaptive Bitrate on, live with a positive congestion estimate (main's chip gate). */
const adaptiveOn = { adaptiveBitrate: adaptive() }

describe('parseStreamStatsOverlayMode', () => {
  it('defaults to auto for null, empty, and unknown values', () => {
    expect(parseStreamStatsOverlayMode(null)).toBe('auto')
    expect(parseStreamStatsOverlayMode('')).toBe('auto')
    expect(parseStreamStatsOverlayMode('garbage')).toBe('auto')
  })

  it('parses the stored on/off modes', () => {
    expect(parseStreamStatsOverlayMode('auto')).toBe('auto')
    expect(parseStreamStatsOverlayMode('on')).toBe('on')
    expect(parseStreamStatsOverlayMode('off')).toBe('off')
  })
})

describe('nextStreamStatsOverlayMode', () => {
  it('cycles auto → on → off → auto', () => {
    expect(nextStreamStatsOverlayMode('auto')).toBe('on')
    expect(nextStreamStatsOverlayMode('on')).toBe('off')
    expect(nextStreamStatsOverlayMode('off')).toBe('auto')
  })
})

describe('createStreamStatsOverlay', () => {
  it('reads the initial mode from the injected store', () => {
    const store = createStreamStatsOverlay({ get: () => 'off', set: () => {} })
    expect(store.mode()).toBe('off')
  })

  it('persists set() and updates the signal', () => {
    const written: string[] = []
    const store = createStreamStatsOverlay({ get: () => null, set: (v) => written.push(v) })
    store.set('on')
    expect(store.mode()).toBe('on')
    expect(written).toEqual(['on'])
    store.set('off')
    expect(store.mode()).toBe('off')
    expect(written).toEqual(['on', 'off'])
  })

  it('cycle advances the mode and persists it', () => {
    const written: string[] = []
    const store = createStreamStatsOverlay({ get: () => null, set: (v) => written.push(v) })
    store.cycle()
    expect(store.mode()).toBe('on')
    expect(written).toEqual(['on'])
    store.cycle()
    store.cycle()
    expect(store.mode()).toBe('auto')
    expect(written).toEqual(['on', 'off', 'auto'])
  })

  it('keeps the in-memory switch working when storage throws', () => {
    const store = createStreamStatsOverlay({
      get: () => null,
      set: () => {
        throw new Error('quota exceeded')
      },
    })
    store.set('on')
    expect(store.mode()).toBe('on')
  })
})

describe('streamStatsOverlayVisible', () => {
  const live = { previewLive: true, adaptiveEnabled: true, laneAvailable: true }

  it('off never shows, whatever else holds', () => {
    expect(streamStatsOverlayVisible('off', live)).toBe(false)
  })

  it('on shows whenever the preview is live with lane data — adaptive not required', () => {
    expect(streamStatsOverlayVisible('on', live)).toBe(true)
    expect(streamStatsOverlayVisible('on', { ...live, adaptiveEnabled: false })).toBe(true)
  })

  it('auto preserves today\'s gate: adaptive enabled is required', () => {
    expect(streamStatsOverlayVisible('auto', live)).toBe(true)
    expect(streamStatsOverlayVisible('auto', { ...live, adaptiveEnabled: false })).toBe(false)
  })

  it('no mode shows without a live preview or lane data', () => {
    expect(streamStatsOverlayVisible('on', { ...live, previewLive: false })).toBe(false)
    expect(streamStatsOverlayVisible('on', { ...live, laneAvailable: false })).toBe(false)
    expect(streamStatsOverlayVisible('auto', { ...live, laneAvailable: false })).toBe(false)
  })

  it('is the mount verdict plus the lane\'s data — nothing more', () => {
    for (const mode of ['auto', 'on', 'off'] as const) {
      for (const previewLive of [true, false]) {
        for (const adaptiveEnabled of [true, false]) {
          const gate = { previewLive, adaptiveEnabled, laneAvailable: true }
          expect(streamStatsOverlayVisible(mode, gate))
            .toBe(streamStatsOverlayMounted(mode, gate))
          expect(streamStatsOverlayVisible(mode, { ...gate, laneAvailable: false })).toBe(false)
        }
      }
    }
  })
})

describe('streamStatsOverlayMounted', () => {
  const live = { previewLive: true, adaptiveEnabled: true, laneAvailable: true }

  it('off never mounts, whatever else holds', () => {
    expect(streamStatsOverlayMounted('off', live)).toBe(false)
  })

  it('on mounts whenever the preview is live — adaptive not required', () => {
    expect(streamStatsOverlayMounted('on', live)).toBe(true)
    expect(streamStatsOverlayMounted('on', { ...live, adaptiveEnabled: false })).toBe(true)
  })

  it('auto keeps the legacy adaptive requirement', () => {
    expect(streamStatsOverlayMounted('auto', live)).toBe(true)
    expect(streamStatsOverlayMounted('auto', { ...live, adaptiveEnabled: false })).toBe(false)
  })

  it('no mode mounts without a live preview', () => {
    expect(streamStatsOverlayMounted('on', { ...live, previewLive: false })).toBe(false)
    expect(streamStatsOverlayMounted('auto', { ...live, previewLive: false })).toBe(false)
  })

  // The mount/visibility split: a lane switch lands here as "the new lane
  // has no data yet" — mounting must not care, or the indicator would be
  // torn down (expanded panel, sparkline history and all) on every switch.
  it('is blind to the lane\'s data — that decides visibility, never mount', () => {
    expect(streamStatsOverlayMounted('on', { ...live, laneAvailable: false })).toBe(true)
    expect(streamStatsOverlayMounted('auto', { ...live, laneAvailable: false })).toBe(true)
  })
})

describe('pickStreamStatsChips', () => {
  const hidden = { bitrateKbps: null, fps: null }

  it('off hides both chips whatever the lane measured', () => {
    expect(pickStreamStatsChips('off', { ...allLanes, ...adaptiveOn }, 'whep', true)).toEqual(hidden)
  })

  it("follows the preview rung's own lane — MJPEG, WHEP and H264/HLS each read theirs", () => {
    const status = { ...allLanes, ...adaptiveOn } as DeviceStatus
    expect(pickStreamStatsChips('auto', status, 'mjpeg', true)).toEqual({ bitrateKbps: 3900, fps: 23.5 })
    // The default preview reads its own lane — never the encoded block's.
    expect(pickStreamStatsChips('auto', status, 'whep', true)).toEqual({ bitrateKbps: 1700, fps: 24 })
    for (const mode of ['h264', 'hls'] as const) {
      expect(pickStreamStatsChips('auto', status, mode, true)).toEqual({ bitrateKbps: 1890, fps: 23.5 })
    }
    expect(pickStreamStatsChips('auto', status, undefined, true)).toEqual({ bitrateKbps: 1890, fps: 23.5 })
  })

  it("never borrows: a rung whose lane has no block reads nulls in every mode", () => {
    // The old rule, inverted — the encoded block used to win whenever
    // present, whatever the preview was showing.
    const noWhep = { ...allLanes, ...adaptiveOn, whepStream: undefined } as DeviceStatus
    expect(pickStreamStatsChips('on', noWhep, 'whep', true)).toEqual(hidden)
    expect(pickStreamStatsChips('auto', noWhep, 'whep', true)).toEqual(hidden)
    const noEncoded = { ...allLanes, ...adaptiveOn, encodedStream: undefined } as DeviceStatus
    expect(pickStreamStatsChips('on', noEncoded, 'h264', true)).toEqual(hidden)
    const noQuality = { ...allLanes, ...adaptiveOn, connectionQuality: undefined } as DeviceStatus
    expect(pickStreamStatsChips('on', noQuality, 'mjpeg', true)).toEqual(hidden)
    for (const mode of ['on', 'auto'] as const) {
      expect(pickStreamStatsChips(mode, null, 'whep', true)).toEqual(hidden)
      expect(pickStreamStatsChips(mode, {} as DeviceStatus, 'mjpeg', true)).toEqual(hidden)
    }
  })

  it('a null or zero measured value hides its chip in every mode', () => {
    const status = { ...allLanes, ...adaptiveOn, whepStream: { measuredBitrateKbps: 0, fps: 0 } } as DeviceStatus
    expect(pickStreamStatsChips('auto', status, 'whep', true)).toEqual(hidden)
    expect(pickStreamStatsChips('on', status, 'whep', true)).toEqual(hidden)
    expect(pickStreamStatsChips('off', status, 'whep', true)).toEqual(hidden)
  })

  it('each chip stands on its own value', () => {
    const status = { ...allLanes, ...adaptiveOn, whepStream: { measuredBitrateKbps: 1700, fps: 0 } } as DeviceStatus
    expect(pickStreamStatsChips('on', status, 'whep', true)).toEqual({ bitrateKbps: 1700, fps: null })
  })

  it("on shows the lane's measured data — adaptive not required, and even while the stream is down", () => {
    expect(pickStreamStatsChips('on', allLanes, 'whep', true)).toEqual({ bitrateKbps: 1700, fps: 24 })
    expect(pickStreamStatsChips('on', allLanes, 'whep', false)).toEqual({ bitrateKbps: 1700, fps: 24 })
  })

  it('auto requires Adaptive Bitrate on — the chips follow the feature, not just its numbers', () => {
    const off = { ...allLanes, adaptiveBitrate: adaptive({ enabled: false }) } as DeviceStatus
    expect(pickStreamStatsChips('auto', off, 'whep', true)).toEqual(hidden)
    expect(pickStreamStatsChips('auto', { ...allLanes }, 'whep', true)).toEqual(hidden)
  })

  it('auto requires a positive congestion estimate (main\'s exact condition)', () => {
    for (const estimatedBandwidthKbps of [0, -1, Number.NaN]) {
      const status = { ...allLanes, adaptiveBitrate: adaptive({ estimatedBandwidthKbps }) } as DeviceStatus
      expect(pickStreamStatsChips('auto', status, 'whep', true)).toEqual(hidden)
    }
  })

  it('auto requires the live stream; its gate is not a substitute for the lane\'s data', () => {
    const status = { ...allLanes, ...adaptiveOn } as DeviceStatus
    expect(pickStreamStatsChips('auto', status, 'whep', false)).toEqual(hidden)
    // The legacy gate holds, but the preview's lane measured nothing.
    const idle = { ...status, whepStream: { measuredBitrateKbps: 0, fps: 0 } } as DeviceStatus
    expect(pickStreamStatsChips('auto', idle, 'whep', true)).toEqual(hidden)
  })
})
