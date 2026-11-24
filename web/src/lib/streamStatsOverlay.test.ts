import { describe, expect, it } from 'vitest'
import {
  createStreamStatsOverlay,
  nextStreamStatsOverlayMode,
  parseStreamStatsOverlayMode,
  streamStatsChipVisible,
  streamStatsOverlayVisible,
} from './streamStatsOverlay'

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
})

describe('streamStatsChipVisible', () => {
  it('off hides the chips whatever the value', () => {
    expect(streamStatsChipVisible('off', true, 1890)).toBe(false)
  })

  it('a null or zero measured value hides the chips in every mode', () => {
    expect(streamStatsChipVisible('auto', true, null)).toBe(false)
    expect(streamStatsChipVisible('on', true, 0)).toBe(false)
    expect(streamStatsChipVisible('off', true, 1890)).toBe(false)
  })

  it('auto keeps the legacy live-stream requirement', () => {
    expect(streamStatsChipVisible('auto', true, 1890)).toBe(true)
    expect(streamStatsChipVisible('auto', false, 1890)).toBe(false)
  })

  it('on shows measured data even while the stream is down', () => {
    expect(streamStatsChipVisible('on', false, 1890)).toBe(true)
  })
})
