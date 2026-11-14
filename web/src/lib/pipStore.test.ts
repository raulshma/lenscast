import { describe, expect, it } from 'vitest'
import {
  PIP_EDGE,
  PIP_MOBILE_BOTTOM_INSET,
  clampPipPosition,
  defaultDockSize,
  defaultPipPosition,
  pipWanted,
  readStoredPipPosition,
} from './pipStore'

// The mini-player's pure verdicts: when the dock shows (truth table) and
// where it may rest (viewport clamping). The store's reactive half is
// exercised through StreamPipHost.test.tsx.

describe('pipWanted', () => {
  it('shows away from live while the stream plays and is not dismissed', () => {
    expect(pipWanted('media', true, true, false)).toBe(true)
    expect(pipWanted('detection', true, true, false)).toBe(true)
    expect(pipWanted('system', true, true, false)).toBe(true)
  })

  it('never shows on the live view — the stage hosts the player there', () => {
    expect(pipWanted('live', true, true, false)).toBe(false)
  })

  it('requires a playing stream (web-active AND preview-visible)', () => {
    expect(pipWanted('media', false, true, false)).toBe(false)
    expect(pipWanted('media', true, false, false)).toBe(false)
    expect(pipWanted('media', false, false, false)).toBe(false)
  })

  it('stays hidden for the session once dismissed', () => {
    expect(pipWanted('media', true, true, true)).toBe(false)
  })
})

describe('clampPipPosition', () => {
  const W = 320
  const H = 214 // 16:9 picture + 34px chrome

  it('leaves in-bounds positions alone', () => {
    expect(clampPipPosition(100, 90, W, H, 1280, 800)).toEqual({ x: 100, y: 90 })
  })

  it('clamps every edge, honoring the bottom inset', () => {
    const vw = 1000
    const vh = 800
    const inset = PIP_MOBILE_BOTTOM_INSET
    expect(clampPipPosition(-50, -50, W, H, vw, vh, inset)).toEqual({ x: PIP_EDGE, y: PIP_EDGE })
    expect(clampPipPosition(vw, vh, W, H, vw, vh, inset)).toEqual({
      x: vw - W - PIP_EDGE,
      y: vh - H - inset - PIP_EDGE,
    })
  })

  it('pins to the top-left edge when the viewport is smaller than the dock', () => {
    expect(clampPipPosition(200, 200, W, H, 200, 100)).toEqual({ x: PIP_EDGE, y: PIP_EDGE })
  })

  it('without an inset the dock may reach the bottom edge', () => {
    const vw = 1000
    const vh = 800
    expect(clampPipPosition(0, vh, W, H, vw, vh, 0)).toEqual({
      x: PIP_EDGE,
      y: vh - H - PIP_EDGE,
    })
  })
})

describe('defaultPipPosition', () => {
  it('rests bottom-right with a margin, above the mobile inset', () => {
    const vw = 1280
    const vh = 800
    const dockW = 320
    const dockH = 214
    expect(defaultPipPosition(vw, vh, dockW, dockH)).toEqual({
      x: vw - dockW - 16,
      y: vh - dockH - 16,
    })
    expect(defaultPipPosition(vw, vh, dockW, dockH, PIP_MOBILE_BOTTOM_INSET)).toEqual({
      x: vw - dockW - 16,
      y: vh - dockH - PIP_MOBILE_BOTTOM_INSET - 16,
    })
  })

  it('a tiny viewport still lands inside the clamp range', () => {
    const pos = defaultPipPosition(240, 320, 320, 214)
    expect(pos.x).toBeGreaterThanOrEqual(PIP_EDGE)
    expect(pos.y).toBeGreaterThanOrEqual(PIP_EDGE)
  })
})

describe('defaultDockSize', () => {
  it('desktop: min(320px, 44vw) wide, 16:9 slot + chrome + borders tall', () => {
    expect(defaultDockSize(1440)).toEqual({ w: 320, h: 216 })
    expect(defaultDockSize(800)).toEqual({ w: 320, h: 216 })
  })

  it('mobile (below the breakpoint): min(232px, 52vw) wide', () => {
    expect(defaultDockSize(767)).toEqual({ w: 232, h: 167 })
    expect(defaultDockSize(390)).toEqual({ w: 203, h: 150 })
    expect(defaultDockSize(200)).toEqual({ w: 104, h: 95 })
  })
})

describe('readStoredPipPosition', () => {
  it('returns null safely where localStorage does not exist (node env)', () => {
    expect(readStoredPipPosition()).toBeNull()
  })
})
