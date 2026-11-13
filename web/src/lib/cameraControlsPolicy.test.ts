import { describe, expect, it } from 'vitest'
import {
  CAM_STRIP_STORAGE_KEY,
  DEFAULT_EV_RANGE,
  EV_PRESS_WINDOW_MS,
  ZOOM_MIRROR_HOLD_MS,
  ZOOM_SEND_THROTTLE_MS,
  ZOOM_STEP,
  clampExposureIndex,
  clampRatio,
  evPressGuard,
  parseStripCollapsed,
  resetZoom,
  steppedZoom,
  stripCollapsedStorageValue,
  zoomFlushDelayMs,
  zoomReadout,
  zoomSendVerdict,
} from './cameraControlsPolicy'

describe('clampRatio', () => {
  it('floors at 1x — the camera never shrinks below the wide field of view', () => {
    expect(clampRatio(0.3, 10)).toBe(1)
    expect(clampRatio(1, 10)).toBe(1)
  })

  it('clamps to the device-published ceiling', () => {
    expect(clampRatio(8.4, 8)).toBe(8)
    expect(clampRatio(8, 8)).toBe(8)
  })
})

describe('steppedZoom', () => {
  it('moves one 0.5 tap per press and stays on the grid', () => {
    expect(steppedZoom(1, 1, 10)).toBe(1.5)
    expect(steppedZoom(1.5, 1, 10)).toBe(2)
    expect(steppedZoom(2, -1, 10)).toBe(1.5)
  })

  it('never rounds past the floor or the ceiling', () => {
    expect(steppedZoom(1, -1, 10)).toBe(1)
    expect(steppedZoom(9.8, 1, 10)).toBe(10)
  })

  it('absorbs float drift back onto the 0.5 grid', () => {
    expect(steppedZoom(1.4999999, 1, 10)).toBe(2)
  })
})

describe('resetZoom', () => {
  it('the 1x chip lands on the wide lens', () => {
    expect(resetZoom()).toBe(1)
  })
})

describe('zoomReadout', () => {
  it('renders one decimal with the x suffix', () => {
    expect(zoomReadout(1)).toBe('1.0x')
    expect(zoomReadout(2.5)).toBe('2.5x')
    expect(zoomReadout(8)).toBe('8.0x')
  })
})

describe('clampExposureIndex', () => {
  it('clamps into the device-published range', () => {
    expect(clampExposureIndex(5, { min: -3, max: 3 })).toBe(3)
    expect(clampExposureIndex(-9, { min: -3, max: 3 })).toBe(-3)
    expect(clampExposureIndex(2, { min: -3, max: 3 })).toBe(2)
  })

  it('falls back to the static -12..12 default when the device published no range', () => {
    expect(clampExposureIndex(99, null)).toBe(12)
    expect(clampExposureIndex(-99, undefined)).toBe(-12)
  })

  it('rounds fractional input back onto the integer index grid', () => {
    expect(clampExposureIndex(1.6, { min: -12, max: 12 })).toBe(2)
    expect(clampExposureIndex(-0.4, { min: -12, max: 12 })).toBe(0)
  })
})

describe('evPressGuard', () => {
  it('blocks the resync inside the press window and releases after it', () => {
    expect(evPressGuard(1_000, 1_000 + EV_PRESS_WINDOW_MS - 1)).toBe(true)
    expect(evPressGuard(1_000, 1_000 + EV_PRESS_WINDOW_MS)).toBe(false)
    expect(evPressGuard(1_000, 5_000)).toBe(false)
  })

  it('the same guard serves the zoom stepper hold at its shorter window', () => {
    expect(evPressGuard(1_000, 1_000 + ZOOM_MIRROR_HOLD_MS - 1, ZOOM_MIRROR_HOLD_MS)).toBe(true)
    expect(evPressGuard(1_000, 1_000 + ZOOM_MIRROR_HOLD_MS, ZOOM_MIRROR_HOLD_MS)).toBe(false)
    expect(ZOOM_MIRROR_HOLD_MS).toBeLessThan(EV_PRESS_WINDOW_MS)
  })
})

describe('zoom throttle', () => {
  it('sends on the leading call and defers inside the window', () => {
    expect(zoomSendVerdict(1_000, 0)).toBe('send')
    expect(zoomSendVerdict(1_050, 1_000)).toBe('defer')
    expect(zoomSendVerdict(1_000 + ZOOM_SEND_THROTTLE_MS, 1_000)).toBe('send')
  })

  it('arms the trailing flush for exactly the window remainder', () => {
    expect(zoomFlushDelayMs(1_050, 1_000)).toBe(ZOOM_SEND_THROTTLE_MS - 50)
    expect(zoomFlushDelayMs(1_100, 1_000)).toBe(50)
    expect(zoomFlushDelayMs(1_300, 1_000)).toBe(0)
  })
})

describe('strip collapse persistence', () => {
  it('parses only the exact collapsed token; anything else stays open', () => {
    expect(parseStripCollapsed('collapsed')).toBe(true)
    expect(parseStripCollapsed('open')).toBe(false)
    expect(parseStripCollapsed('garbage')).toBe(false)
    expect(parseStripCollapsed(null)).toBe(false)
  })

  it('round-trips through the storage value', () => {
    expect(stripCollapsedStorageValue(true)).toBe('collapsed')
    expect(stripCollapsedStorageValue(false)).toBe('open')
    expect(parseStripCollapsed(stripCollapsedStorageValue(true))).toBe(true)
    expect(parseStripCollapsed(stripCollapsedStorageValue(false))).toBe(false)
  })

  it('pins the storage key at its production value', () => {
    expect(CAM_STRIP_STORAGE_KEY).toBe('lenscast.camStrip')
  })
})
