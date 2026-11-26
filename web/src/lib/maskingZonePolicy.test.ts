import { describe, expect, it, vi } from 'vitest'
import {
  MIN_DRAW_DRAG,
  RESIZE_HANDLES,
  ZONE_MIN_SIZE,
  clampPoint,
  clampZone,
  containRect,
  createLatestWinsQueue,
  dragBounds,
  moveZone,
  nudgeZone,
  resizeZone,
  roundZone,
  sameRect,
  sameZone,
  zoneFromDrag,
  zonesEqual,
  type ZonePoint,
  type ZoneRect,
} from './maskingZonePolicy'
import type { MaskingZone } from '../types'

// The visual masking editor's pure rules: where the picture sits in its
// container, what a drag turns into, and the frame rule every rectangle
// obeys — plus the latest-wins queue that saves the zones and the tolerant
// equality that reads them back. No render: everything here is numbers.

/** Four fields compared at 12 digits — float noise in the last ulps is not a finding. */
function expectRect(rect: ZoneRect, expected: ZoneRect): void {
  for (const field of ['x', 'y', 'width', 'height'] as const) {
    expect(rect[field], field).toBeCloseTo(expected[field], 12)
  }
}

/** The frame rule: inside the picture on all four sides, both edges >= the minimum. */
function expectInFrame(rect: ZoneRect): void {
  expect(rect.x).toBeGreaterThanOrEqual(0)
  expect(rect.y).toBeGreaterThanOrEqual(0)
  expect(rect.width).toBeGreaterThanOrEqual(ZONE_MIN_SIZE - 1e-12)
  expect(rect.height).toBeGreaterThanOrEqual(ZONE_MIN_SIZE - 1e-12)
  expect(rect.x + rect.width).toBeLessThanOrEqual(1 + 1e-12)
  expect(rect.y + rect.height).toBeLessThanOrEqual(1 + 1e-12)
}

function zone(overrides: Partial<MaskingZone> = {}): MaskingZone {
  return {
    id: 'a',
    label: 'Door',
    enabled: true,
    type: 'PIXELATE',
    x: 0.3,
    y: 0.2,
    width: 0.25,
    height: 0.4,
    pixelateSize: 16,
    blurRadius: 10,
    ...overrides,
  }
}

/** The device keeps the rectangle as 32-bit floats: this is what a save reads back as. */
const widened = (value: number): number => Math.fround(value)

describe('containRect', () => {
  it('fills the container when the aspect ratios match', () => {
    expect(containRect({ width: 640, height: 360 }, { width: 1920, height: 1080 })).toEqual({
      x: 0,
      y: 0,
      width: 640,
      height: 360,
    })
  })

  it('letterboxes a 16:9 picture in a 4:3 container — bars above and below', () => {
    expect(containRect({ width: 1000, height: 750 }, { width: 1920, height: 1080 })).toEqual({
      x: 0,
      y: 93.75,
      width: 1000,
      height: 562.5,
    })
  })

  it('pillarboxes a portrait picture in a landscape container — bars left and right', () => {
    expect(containRect({ width: 1000, height: 750 }, { width: 1080, height: 1920 })).toEqual({
      x: 289.0625,
      y: 0,
      width: 421.875,
      height: 750,
    })
  })

  it('letterboxes a landscape picture in a portrait container', () => {
    expect(containRect({ width: 600, height: 1000 }, { width: 1920, height: 1080 })).toEqual({
      x: 0,
      y: 331.25,
      width: 600,
      height: 337.5,
    })
  })

  it('centers a square picture in a wide container', () => {
    expect(containRect({ width: 800, height: 400 }, { width: 600, height: 600 })).toEqual({
      x: 200,
      y: 0,
      width: 400,
      height: 400,
    })
  })

  it('an unknown media size (0 × 0) means the whole container box', () => {
    expect(containRect({ width: 640, height: 480 }, { width: 0, height: 0 })).toEqual({
      x: 0,
      y: 0,
      width: 640,
      height: 480,
    })
  })

  it('a half-known media size has no aspect ratio and is unknown too', () => {
    expect(containRect({ width: 640, height: 480 }, { width: 1920, height: 0 })).toEqual({
      x: 0,
      y: 0,
      width: 640,
      height: 480,
    })
    expect(containRect({ width: 640, height: 480 }, { width: -1, height: 1080 })).toEqual({
      x: 0,
      y: 0,
      width: 640,
      height: 480,
    })
  })

  it('a zero-size container has no picture: a 0 × 0 rect at the origin', () => {
    const empty = { x: 0, y: 0, width: 0, height: 0 }
    expect(containRect({ width: 0, height: 0 }, { width: 1920, height: 1080 })).toEqual(empty)
    expect(containRect({ width: 0, height: 480 }, { width: 1920, height: 1080 })).toEqual(empty)
    expect(containRect({ width: 640, height: 0 }, { width: 0, height: 0 })).toEqual(empty)
  })

  it('a negative or non-finite container size is no size at all', () => {
    const empty = { x: 0, y: 0, width: 0, height: 0 }
    expect(containRect({ width: -300, height: 480 }, { width: 1920, height: 1080 })).toEqual(empty)
    expect(containRect({ width: 640, height: Number.NaN }, { width: 1920, height: 1080 })).toEqual(empty)
    expect(containRect({ width: Number.POSITIVE_INFINITY, height: 480 }, { width: 1920, height: 1080 })).toEqual(empty)
  })

  it('always centers the picture and keeps it inside the container', () => {
    const sizes = [1, 120, 375, 640, 1000, 1920, 3840]
    for (const cw of sizes) {
      for (const ch of sizes) {
        for (const mw of sizes) {
          for (const mh of sizes) {
            const rect = containRect({ width: cw, height: ch }, { width: mw, height: mh })
            expect(rect.width).toBeGreaterThanOrEqual(0)
            expect(rect.height).toBeGreaterThanOrEqual(0)
            expect(rect.width).toBeLessThanOrEqual(cw + 1e-9)
            expect(rect.height).toBeLessThanOrEqual(ch + 1e-9)
            expect(rect.x).toBeGreaterThanOrEqual(-1e-9)
            expect(rect.y).toBeGreaterThanOrEqual(-1e-9)
            // Centered on both axes.
            expect(rect.x).toBeCloseTo((cw - rect.width) / 2, 9)
            expect(rect.y).toBeCloseTo((ch - rect.height) / 2, 9)
            // Fills one axis exactly, and keeps the picture's own ratio.
            expect(Math.abs(cw - rect.width) < 1e-9 || Math.abs(ch - rect.height) < 1e-9).toBe(true)
            expect(rect.width * mh).toBeCloseTo(rect.height * mw, 6)
          }
        }
      }
    }
  })
})

describe('clampPoint', () => {
  it('leaves a point on the picture alone', () => {
    expect(clampPoint({ x: 0.25, y: 0.75 })).toEqual({ x: 0.25, y: 0.75 })
  })

  it('pins a pointer that left the picture to its edge', () => {
    expect(clampPoint({ x: -0.4, y: 1.7 })).toEqual({ x: 0, y: 1 })
  })

  it('refuses a point that carries no usable number', () => {
    expect(clampPoint({ x: Number.NaN, y: 0.5 })).toBeNull()
    expect(clampPoint({ x: 0.5, y: Number.POSITIVE_INFINITY })).toBeNull()
  })
})

describe('dragBounds', () => {
  it('spans the two points whichever way the drag went', () => {
    const rect = { x: 0.3, y: 0.2, width: 0.4, height: 0.25 }
    expectRect(dragBounds({ x: 0.3, y: 0.2 }, { x: 0.7, y: 0.45 }), rect)
    expectRect(dragBounds({ x: 0.7, y: 0.45 }, { x: 0.3, y: 0.2 }), rect)
    // Bottom-up and right-to-left one axis at a time.
    expectRect(dragBounds({ x: 0.7, y: 0.2 }, { x: 0.3, y: 0.45 }), rect)
    expectRect(dragBounds({ x: 0.3, y: 0.45 }, { x: 0.7, y: 0.2 }), rect)
  })

  it('has no size rule: a zero drag is a zero rect, not a refusal', () => {
    expect(dragBounds({ x: 0.5, y: 0.5 }, { x: 0.5, y: 0.5 })).toEqual({ x: 0.5, y: 0.5, width: 0, height: 0 })
  })
})

describe('zoneFromDrag', () => {
  /** zoneFromDrag's answer as a rectangle — the test fails here when the drag was refused. */
  function drawn(from: ZonePoint, to: ZonePoint): ZoneRect {
    const zone = zoneFromDrag(from, to)
    if (zone === null) throw new Error('expected a zone from this drag, got none')
    return zone
  }

  it('turns a drag into the rectangle it spans', () => {
    expectRect(drawn({ x: 0.3, y: 0.2 }, { x: 0.7, y: 0.45 }), { x: 0.3, y: 0.2, width: 0.4, height: 0.25 })
  })

  it('normalises right-to-left and bottom-up drags to the same zone', () => {
    const forward = drawn({ x: 0.3, y: 0.2 }, { x: 0.7, y: 0.45 })
    const backward = drawn({ x: 0.7, y: 0.45 }, { x: 0.3, y: 0.2 })
    expect(backward).toEqual(forward)
    expectRect(forward, { x: 0.3, y: 0.2, width: 0.4, height: 0.25 })
  })

  it('a drag of exactly 2% is a zone — including the one float error makes 0.0199…', () => {
    expect(MIN_DRAW_DRAG).toBe(0.02)
    // 0.7 − 0.68 is 0.01999999999999995 in doubles.
    expectRect(drawn({ x: 0.68, y: 0.5 }, { x: 0.7, y: 0.52 }), { x: 0.68, y: 0.5, width: 0.02, height: 0.02 })
  })

  it('ignores a drag under 2% in either dimension', () => {
    expect(zoneFromDrag({ x: 0.1, y: 0.1 }, { x: 0.4, y: 0.115 })).toBeNull()
    expect(zoneFromDrag({ x: 0.1, y: 0.1 }, { x: 0.115, y: 0.4 })).toBeNull()
    expect(zoneFromDrag({ x: 0.1, y: 0.1 }, { x: 0.1, y: 0.1 })).toBeNull()
  })

  it('clips a drag that starts or ends off the picture to the picture', () => {
    expect(zoneFromDrag({ x: -0.3, y: 0.1 }, { x: 0.4, y: 0.6 })).toEqual({ x: 0, y: 0.1, width: 0.4, height: 0.5 })
    expect(zoneFromDrag({ x: 0.5, y: 0.5 }, { x: 1.8, y: 1.2 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 })
  })

  it('a drag entirely off the picture has no size and is ignored', () => {
    expect(zoneFromDrag({ x: 1.2, y: 0.2 }, { x: 1.5, y: 0.6 })).toBeNull()
  })

  it('refuses a drag with no usable numbers', () => {
    expect(zoneFromDrag({ x: Number.NaN, y: 0.2 }, { x: 0.6, y: 0.6 })).toBeNull()
    expect(zoneFromDrag({ x: 0.2, y: 0.2 }, { x: 0.6, y: Number.NaN })).toBeNull()
  })

  it('never leaves the frame', () => {
    const ends = [-1, -0.01, 0, 0.25, 0.5, 0.7, 0.71, 0.99, 1, 1.4]
    for (const ax of ends) {
      for (const ay of ends) {
        for (const bx of ends) {
          for (const by of ends) {
            const rect = zoneFromDrag({ x: ax, y: ay }, { x: bx, y: by })
            if (rect) expectInFrame(rect)
          }
        }
      }
    }
  })
})

describe('clampZone', () => {
  it('leaves a zone that is already inside the frame exactly as it is', () => {
    const inside = { x: 0.3, y: 0.2, width: 0.25, height: 0.4 }
    expect(clampZone(inside)).toEqual(inside)
    const flush = { x: 0.75, y: 0.6, width: 0.25, height: 0.4 }
    expect(clampZone(flush)).toEqual(flush)
  })

  it('pulls a negative origin back to the top-left corner', () => {
    expect(clampZone({ x: -0.2, y: -0.1, width: 0.3, height: 0.3 })).toEqual({ x: 0, y: 0, width: 0.3, height: 0.3 })
  })

  it('brings the size into 1%…100%', () => {
    expect(clampZone({ x: 0.5, y: 0.5, width: 0, height: 0.003 })).toEqual({
      x: 0.5,
      y: 0.5,
      width: ZONE_MIN_SIZE,
      height: ZONE_MIN_SIZE,
    })
    expect(clampZone({ x: 0.5, y: 0.5, width: 3, height: 1.5 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 })
  })

  it('cuts the overhang off a far edge instead of sliding the zone back', () => {
    // x = 90%, width = 30% is what the panel's sliders can store: the device clips the last 20%.
    expectRect(clampZone({ x: 0.9, y: 0.2, width: 0.3, height: 0.3 }), { x: 0.9, y: 0.2, width: 0.1, height: 0.3 })
    expectRect(clampZone({ x: 0.2, y: 0.7, width: 0.3, height: 0.6 }), { x: 0.2, y: 0.7, width: 0.3, height: 0.3 })
  })

  it('slides back a zone left with less than 1% of room', () => {
    expect(clampZone({ x: 0.995, y: 0.2, width: 0.3, height: 0.3 })).toEqual({ x: 0.99, y: 0.2, width: 0.01, height: 0.3 })
    expect(clampZone({ x: 1, y: 0.2, width: 0.3, height: 0.3 })).toEqual({ x: 0.99, y: 0.2, width: 0.01, height: 0.3 })
  })

  it('lands NaN and Infinity on the same safe values as a missing field', () => {
    // No usable origin is the corner; no usable size is the minimum.
    expect(clampZone({ x: Number.NaN, y: Number.NEGATIVE_INFINITY, width: Number.NaN, height: Number.POSITIVE_INFINITY })).toEqual({
      x: 0,
      y: 0,
      width: ZONE_MIN_SIZE,
      height: ZONE_MIN_SIZE,
    })
  })

  it('always answers with a rectangle inside the frame', () => {
    const values = [-1, -0.01, 0, 0.005, 0.01, 0.3, 0.7, 0.9, 0.995, 1, 1.2, Number.NaN, Number.POSITIVE_INFINITY]
    for (const x of values) {
      for (const y of values) {
        for (const width of values) {
          for (const height of values) {
            expectInFrame(clampZone({ x, y, width, height }))
          }
        }
      }
    }
  })
})

describe('moveZone', () => {
  const start: ZoneRect = { x: 0.25, y: 0.5, width: 0.5, height: 0.25 }

  it('moves the zone by the delta without changing its size', () => {
    expect(moveZone(start, 0.125, -0.125)).toEqual({ x: 0.375, y: 0.375, width: 0.5, height: 0.25 })
  })

  it('stops at each edge of the frame', () => {
    expect(moveZone(start, 10, 10)).toEqual({ x: 0.5, y: 0.75, width: 0.5, height: 0.25 })
    expect(moveZone(start, -10, -10)).toEqual({ x: 0, y: 0, width: 0.5, height: 0.25 })
    expect(moveZone(start, 0.5, 0)).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.25 })
  })

  it('a zero delta leaves the zone where it is', () => {
    expect(moveZone(start, 0, 0)).toEqual(start)
  })

  it('brings an overhanging start zone into the frame first', () => {
    const overhang = { x: 0.9, y: 0.9, width: 0.3, height: 0.3 }
    expectRect(moveZone(overhang, 0, 0), { x: 0.9, y: 0.9, width: 0.1, height: 0.1 })
    expectRect(moveZone(overhang, -0.5, -0.5), { x: 0.4, y: 0.4, width: 0.1, height: 0.1 })
  })

  it('treats a delta that is not a number as no movement', () => {
    expect(moveZone(start, Number.NaN, 0.125)).toEqual({ x: 0.25, y: 0.625, width: 0.5, height: 0.25 })
    expect(moveZone(start, 0, Number.POSITIVE_INFINITY)).toEqual(start)
  })

  it('nudgeZone is the same move under its keyboard name', () => {
    expect(nudgeZone(start, 0.01, 0)).toEqual({ x: 0.26, y: 0.5, width: 0.5, height: 0.25 })
    expect(nudgeZone(start, -0.1, 0)).toEqual(moveZone(start, -0.1, 0))
    expect(nudgeZone(start, 0.1, 0)).toEqual(moveZone(start, 0.1, 0))
  })

  it('always answers with a rectangle inside the frame', () => {
    const values = [-1, 0, 0.2, 0.7, 1, 3, Number.NaN]
    for (const x of values) {
      for (const width of values) {
        for (const dx of values) {
          expectInFrame(moveZone({ x, y: x, width, height: width }, dx, dx))
        }
      }
    }
  })
})

describe('resizeZone', () => {
  const start: ZoneRect = { x: 0.25, y: 0.5, width: 0.5, height: 0.25 }

  it('knows all eight handles', () => {
    expect([...RESIZE_HANDLES]).toEqual(['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw'])
  })

  it('moves exactly the edge or corner the handle names and nothing else', () => {
    const cases: Array<[string, number, number, ZoneRect]> = [
      ['n', 0, -0.125, { x: 0.25, y: 0.375, width: 0.5, height: 0.375 }],
      ['s', 0, 0.125, { x: 0.25, y: 0.5, width: 0.5, height: 0.375 }],
      ['w', -0.125, 0, { x: 0.125, y: 0.5, width: 0.625, height: 0.25 }],
      ['e', 0.125, 0, { x: 0.25, y: 0.5, width: 0.625, height: 0.25 }],
      ['ne', 0.125, -0.125, { x: 0.25, y: 0.375, width: 0.625, height: 0.375 }],
      ['nw', -0.125, -0.125, { x: 0.125, y: 0.375, width: 0.625, height: 0.375 }],
      ['se', 0.125, 0.125, { x: 0.25, y: 0.5, width: 0.625, height: 0.375 }],
      ['sw', -0.125, 0.125, { x: 0.125, y: 0.5, width: 0.625, height: 0.375 }],
    ]
    for (const [handle, dx, dy, expected] of cases) {
      expectRect(resizeZone(start, handle as never, dx, dy), expected)
    }
  })

  it('an edge dragged out of the picture stops at the frame', () => {
    expect(resizeZone(start, 'e', 1, 0)).toEqual({ x: 0.25, y: 0.5, width: 0.75, height: 0.25 })
    expect(resizeZone(start, 'w', -1, 0)).toEqual({ x: 0, y: 0.5, width: 0.75, height: 0.25 })
    expect(resizeZone(start, 'n', 0, -1)).toEqual({ x: 0.25, y: 0, width: 0.5, height: 0.75 })
    expect(resizeZone(start, 's', 0, 1)).toEqual({ x: 0.25, y: 0.5, width: 0.5, height: 0.5 })
  })

  it('an edge dragged past its partner stops 1% short of it: no flip, never under 1%', () => {
    const minimum = { x: 0.25, y: 0.5, width: ZONE_MIN_SIZE, height: ZONE_MIN_SIZE }
    expectRect(resizeZone(start, 'e', -1, 0), { ...start, width: ZONE_MIN_SIZE })
    expectRect(resizeZone(start, 'w', 1, 0), { x: 0.25 + 0.5 - ZONE_MIN_SIZE, y: 0.5, width: ZONE_MIN_SIZE, height: 0.25 })
    expectRect(resizeZone(start, 'n', 0, 1), { x: 0.25, y: 0.75 - ZONE_MIN_SIZE, width: 0.5, height: ZONE_MIN_SIZE })
    expectRect(resizeZone(start, 's', 0, -1), { x: 0.25, y: 0.5, width: 0.5, height: ZONE_MIN_SIZE })
    expectRect(resizeZone(start, 'se', -1, -1), minimum)
    // The opposite edge is pinned: the far corner dragged away takes only its own two edges.
    expectRect(resizeZone(start, 'nw', 1, 1), { x: 0.75 - ZONE_MIN_SIZE, y: 0.75 - ZONE_MIN_SIZE, width: ZONE_MIN_SIZE, height: ZONE_MIN_SIZE })
  })

  it('computes from the zone as the gesture began, not from the previous frame', () => {
    const once = resizeZone(start, 'w', 0.1, 0)
    expect(once).toEqual({ x: 0.35, y: 0.5, width: 0.4, height: 0.25 })
    // Feeding that result back in with "another 0.1" shrinks it again —
    // although the pointer only ever travelled 0.1 from where it went down.
    const cumulative = resizeZone(once, 'w', 0.1, 0)
    expect(cumulative.width).toBeCloseTo(0.3, 12)
    // The absolute form always answers from the start zone and the pointer's
    // total travel: the same travel is the same rectangle whenever it is
    // asked, twice the travel is twice the resize, and no travel at all is
    // the start rectangle.
    expect(resizeZone(start, 'w', 0.1, 0)).toEqual(once)
    expect(resizeZone(start, 'w', 0.2, 0)).toEqual({ x: 0.45, y: 0.5, width: 0.3, height: 0.25 })
    expect(resizeZone(start, 'w', 0, 0)).toEqual(start)
  })

  it('does not stick at a clamp the way a cumulative resize does', () => {
    // Out to the frame's edge and all the way back: a cumulative resize
    // restarts from the clamped rectangle and stays collapsed…
    const atTheEdge = resizeZone(start, 'e', 5, 0)
    expect(atTheEdge.width).toBe(0.75)
    expectRect(resizeZone(atTheEdge, 'e', -5, 0), { ...start, width: ZONE_MIN_SIZE })
    // …while the pointer's true travel (out 5, back 5) is nothing, and that
    // is what the absolute form sees.
    expect(resizeZone(start, 'e', 0, 0)).toEqual(start)
  })

  it('a drag that leads nowhere changes nothing', () => {
    expect(resizeZone(start, 'se', 0, 0)).toEqual(start)
    expect(resizeZone(start, 'se', Number.NaN, 0)).toEqual(start)
  })

  it('brings an overhanging start zone into the frame first', () => {
    expectRect(resizeZone({ x: 0.9, y: 0.2, width: 0.3, height: 0.3 }, 'e', 0, 0), { x: 0.9, y: 0.2, width: 0.1, height: 0.3 })
  })

  it('always answers with a rectangle inside the frame', () => {
    const starts: ZoneRect[] = [
      { x: 0, y: 0, width: 1, height: 1 },
      { x: 0.25, y: 0.5, width: 0.5, height: 0.25 },
      { x: 0.9, y: 0.9, width: 0.3, height: 0.3 },
      { x: 0.995, y: 0, width: 0.01, height: 0.01 },
    ]
    const deltas = [-3, -0.3, -0.01, 0, 0.01, 0.3, 3, Number.NaN]
    for (const from of starts) {
      for (const handle of RESIZE_HANDLES) {
        for (const dx of deltas) {
          for (const dy of deltas) {
            const rect = resizeZone(from, handle, dx, dy)
            expectInFrame(rect)
            // A handle without an `n` never moves the top edge; without `e`, the right edge stays.
            if (!handle.includes('n')) expect(rect.y).toBeCloseTo(clampZone(from).y, 12)
            if (!handle.includes('w')) expect(rect.x).toBeCloseTo(clampZone(from).x, 12)
            if (!handle.includes('s')) expect(rect.y + rect.height).toBeCloseTo(clampZone(from).y + clampZone(from).height, 12)
            if (!handle.includes('e')) expect(rect.x + rect.width).toBeCloseTo(clampZone(from).x + clampZone(from).width, 12)
          }
        }
      }
    }
  })
})

describe('roundZone', () => {
  it('rounds the four fields to 4 decimals', () => {
    expect(roundZone({ x: 0.123456, y: 0.487654321, width: 0.555555, height: 0.10006 })).toEqual({
      x: 0.1235,
      y: 0.4877,
      width: 0.5556,
      height: 0.1001,
    })
  })

  it('leaves a rectangle that is already on the grid alone', () => {
    const onGrid = { x: 0.1234, y: 0.5678, width: 0.2345, height: 0.3456 }
    expect(roundZone(onGrid)).toEqual(onGrid)
  })

  it('applies the frame rule before rounding', () => {
    expect(roundZone({ x: 0.9, y: 0.2, width: 0.33333, height: 0.3 })).toEqual({ x: 0.9, y: 0.2, width: 0.1, height: 0.3 })
    expect(roundZone({ x: Number.NaN, y: -1, width: 0, height: 5 })).toEqual({ x: 0, y: 0, width: ZONE_MIN_SIZE, height: 1 })
  })

  it('never lets rounding carry a far edge over the frame', () => {
    // Both edges sit on a half-step: rounding each on its own gives 0.3334 + 0.6667 = 1.0001.
    expect(roundZone({ x: 0.33335, y: 0.33335, width: 0.66665, height: 0.66665 })).toEqual({
      x: 0.3334,
      y: 0.3334,
      width: 0.6666,
      height: 0.6666,
    })
  })

  it('always answers with a grid rectangle inside the frame', () => {
    const values = [0, 0.00499, 0.005, 0.33335, 0.5, 0.66665, 0.90005, 0.99995, 1, 1.3]
    for (const x of values) {
      for (const width of values) {
        const rect = roundZone({ x, y: x, width, height: width })
        expectInFrame(rect)
        for (const field of [rect.x, rect.y, rect.width, rect.height]) {
          expect(Math.round(field * 10_000) / 10_000, String(field)).toBe(field)
        }
      }
    }
  })
})

describe('zonesEqual', () => {
  it('equal lists are equal whatever their object identities', () => {
    expect(zonesEqual([zone()], [zone()])).toBe(true)
    expect(zonesEqual([zone({ id: 'a' }), zone({ id: 'b' })], [zone({ id: 'a' }), zone({ id: 'b' })])).toBe(true)
    expect(zonesEqual([], [])).toBe(true)
  })

  it("tolerates the device's float-widened read-back", () => {
    const stored = zone({ x: 0.3, y: 0.7, width: 0.25, height: 0.1, blurRadius: 12 })
    const readBack = zone({
      x: widened(0.3),
      y: widened(0.7),
      width: widened(0.25),
      height: widened(0.1),
      blurRadius: widened(12),
    })
    // The widening is real — 0.3 is not what comes back…
    expect(readBack.x).not.toBe(stored.x)
    expect(readBack.x).toBe(0.30000001192092896)
    // …and still the same zone.
    expect(zonesEqual([stored], [readBack])).toBe(true)
    expect(sameZone(stored, readBack)).toBe(true)
    expect(sameRect(stored, readBack)).toBe(true)
  })

  it('a difference past the tolerance is a different zone', () => {
    expect(zonesEqual([zone()], [zone({ x: 0.3002 })])).toBe(false)
    expect(zonesEqual([zone()], [zone({ pixelateSize: 15 })])).toBe(false)
    expect(zonesEqual([zone()], [zone({ blurRadius: 10.5 })])).toBe(false)
  })

  it('compares the other fields exactly', () => {
    expect(zonesEqual([zone()], [zone({ id: 'other' })])).toBe(false)
    expect(zonesEqual([zone()], [zone({ label: 'Window' })])).toBe(false)
    expect(zonesEqual([zone()], [zone({ enabled: false })])).toBe(false)
    expect(zonesEqual([zone()], [zone({ type: 'BLUR' })])).toBe(false)
    // The type is a string compare: casing is not forgiven.
    expect(zonesEqual([zone()], [zone({ type: 'pixelate' as MaskingZone['type'] })])).toBe(false)
  })

  it('length and order are part of the content', () => {
    const a = zone({ id: 'a' })
    const b = zone({ id: 'b' })
    expect(zonesEqual([a], [])).toBe(false)
    expect(zonesEqual([], [a])).toBe(false)
    expect(zonesEqual([a, b], [b, a])).toBe(false)
    expect(zonesEqual([a, b], [a, b])).toBe(true)
  })

  it('a number that is not a number is never the same', () => {
    expect(zonesEqual([zone()], [zone({ x: Number.NaN })])).toBe(false)
    expect(zonesEqual([zone({ x: Number.NaN })], [zone({ x: Number.NaN })])).toBe(false)
  })
})

describe('createLatestWinsQueue', () => {
  function deferred() {
    let resolve!: () => void
    let reject!: (error: unknown) => void
    const promise = new Promise<void>((res, rej) => {
      resolve = res
      reject = rej
    })
    return { promise, resolve, reject }
  }

  /** Lets every microtask (and the queue's continuation) run. */
  const settle = () => new Promise<void>((done) => setTimeout(done, 0))

  function harness() {
    const gates: Array<ReturnType<typeof deferred>> = []
    const commit = vi.fn((_value: string) => {
      const gate = deferred()
      gates.push(gate)
      return gate.promise
    })
    const onError = vi.fn()
    const queue = createLatestWinsQueue<string>(commit, onError)
    return { gates, commit, onError, queue }
  }

  it('sends an idle queue a value immediately', async () => {
    const { commit, queue } = harness()
    queue.push('one')
    expect(commit).toHaveBeenCalledTimes(1)
    expect(commit).toHaveBeenCalledWith('one')
    expect(queue.busy()).toBe(true)
  })

  it('holds a value that arrives mid-commit and sends it once the first settles', async () => {
    const { gates, commit, queue } = harness()
    queue.push('one')
    queue.push('two')
    expect(commit).toHaveBeenCalledTimes(1)
    await settle()
    expect(commit).toHaveBeenCalledTimes(1)
    gates[0].resolve()
    await settle()
    expect(commit).toHaveBeenCalledTimes(2)
    expect(commit).toHaveBeenLastCalledWith('two')
  })

  it('collapses everything that arrives mid-commit into one trailing commit with the latest value', async () => {
    const { gates, commit, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    queue.push('v3')
    queue.push('v4')
    expect(commit).toHaveBeenCalledTimes(1)
    expect(queue.busy()).toBe(true)
    gates[0].resolve()
    await settle()
    expect(commit.mock.calls.map(([value]) => value)).toEqual(['v1', 'v4'])
  })

  it('never has more than one commit in flight', async () => {
    const { gates, commit, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    queue.push('v3')
    gates[0].resolve()
    await settle()
    // v3 took the slot the moment v1 settled; nothing else went out with it.
    expect(commit).toHaveBeenCalledTimes(2)
    expect(queue.busy()).toBe(true)
    gates[1].resolve()
    await settle()
    expect(commit).toHaveBeenCalledTimes(2)
    expect(queue.busy()).toBe(false)
  })

  it('keeps the chain going through several commits', async () => {
    const { gates, commit, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    gates[0].resolve()
    await settle()
    queue.push('v3')
    gates[1].resolve()
    await settle()
    expect(commit.mock.calls.map(([value]) => value)).toEqual(['v1', 'v2', 'v3'])
    gates[2].resolve()
    await settle()
    expect(queue.busy()).toBe(false)
  })

  it('reports a failed commit and still sends the commit held behind it', async () => {
    const { gates, commit, onError, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    const failure = new Error('device said no')
    gates[0].reject(failure)
    await settle()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith(failure)
    // The failure is not reported twice, and did not block v2.
    expect(commit.mock.calls.map(([value]) => value)).toEqual(['v1', 'v2'])
    gates[1].resolve()
    await settle()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(queue.busy()).toBe(false)
  })

  it('a failed commit with nothing behind it leaves the queue idle and usable', async () => {
    const { gates, commit, onError, queue } = harness()
    queue.push('v1')
    gates[0].reject(new Error('offline'))
    await settle()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(queue.busy()).toBe(false)
    queue.push('v2')
    expect(commit).toHaveBeenLastCalledWith('v2')
    gates[1].resolve()
    await settle()
    expect(queue.busy()).toBe(false)
  })

  it('a commit function that throws instead of returning a promise is a failed commit', async () => {
    const failure = new Error('sync throw')
    const commit = vi.fn(() => {
      throw failure
    })
    const onError = vi.fn()
    const queue = createLatestWinsQueue<string>(commit as () => Promise<void>, onError)
    queue.push('v1')
    await settle()
    expect(onError).toHaveBeenCalledWith(failure)
    expect(queue.busy()).toBe(false)
  })

  it('survives an error reporter that throws', async () => {
    const { gates, commit } = harness()
    const onError = vi.fn(() => {
      throw new Error('toast exploded')
    })
    const fragile = createLatestWinsQueue<string>(commit, onError)
    fragile.push('v1')
    fragile.push('v2')
    gates[0].reject(new Error('device said no'))
    await settle()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(commit.mock.calls.map(([value]) => value)).toEqual(['v1', 'v2'])
    gates[1].resolve()
    await settle()
    expect(fragile.busy()).toBe(false)
  })

  it('works without an error reporter', async () => {
    const { gates, commit } = harness()
    const queue = createLatestWinsQueue<string>(commit)
    queue.push('v1')
    gates[0].reject(new Error('device said no'))
    await expect(queue.idle()).resolves.toBeUndefined()
    expect(queue.busy()).toBe(false)
  })

  it('idle() resolves at once when nothing is pending', async () => {
    const { queue } = harness()
    let done = false
    void queue.idle().then(() => {
      done = true
    })
    await settle()
    expect(done).toBe(true)
  })

  it('idle() resolves only once the in-flight commit settled', async () => {
    const { gates, queue } = harness()
    queue.push('v1')
    let done = false
    void queue.idle().then(() => {
      done = true
    })
    await settle()
    expect(done).toBe(false)
    gates[0].resolve()
    await settle()
    expect(done).toBe(true)
  })

  it('idle() waits for the trailing commit too, and for every waiter', async () => {
    const { gates, commit, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    let first = false
    let second = false
    void queue.idle().then(() => {
      first = true
    })
    queue.push('v3')
    void queue.idle().then(() => {
      second = true
    })
    gates[0].resolve()
    await settle()
    // v3 is on the wire now (v2 was superseded before it left).
    expect(commit.mock.calls.map(([value]) => value)).toEqual(['v1', 'v3'])
    expect(queue.busy()).toBe(true)
    expect(first).toBe(false)
    expect(second).toBe(false)
    gates[1].resolve()
    await settle()
    expect(first).toBe(true)
    expect(second).toBe(true)
  })

  it('idle() also resolves when the last commit fails', async () => {
    const { gates, queue } = harness()
    queue.push('v1')
    gates[0].reject(new Error('device said no'))
    await expect(queue.idle()).resolves.toBeUndefined()
  })

  it('drop() discards the held commit; the one on the wire still lands', async () => {
    const { gates, commit, queue } = harness()
    queue.push('v1')
    queue.push('v2')
    queue.drop()
    gates[0].resolve()
    await settle()
    expect(commit).toHaveBeenCalledTimes(1)
    expect(queue.busy()).toBe(false)
    // Dropping an idle queue is harmless.
    queue.drop()
    queue.push('v3')
    expect(commit).toHaveBeenLastCalledWith('v3')
    gates[1].resolve()
    await settle()
  })
})
