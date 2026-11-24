import { describe, expect, it } from 'vitest'
import { createPendingPatch } from './touchedPatch'

type Camera = { iso: number | null; exposureTime: number | null; exposureCompensation: number; stabilization: boolean }

describe('createPendingPatch', () => {
  it('stages each touched key with the value set at touch time, and the take hands exactly that over', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    pending.stage({ stabilization: false })
    expect(pending.take().values).toEqual({ iso: 800, stabilization: false })
  })

  it('unions keys staged across separate patches into one take', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    pending.stage({ exposureTime: 10_000 })
    pending.stage({ exposureCompensation: -1, stabilization: true })
    expect(pending.take().values).toEqual({
      iso: 800, exposureTime: 10_000, exposureCompensation: -1, stabilization: true,
    })
  })

  it('a key touched twice in one window carries the newest value', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    pending.stage({ iso: 1600, exposureTime: 10_000 })
    expect(pending.take().values).toEqual({ iso: 1600, exposureTime: 10_000 })
  })

  it('an explicit null is a value, not a missing key — the ISO / exposure-time reset', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: null, exposureTime: null })
    const take = pending.take()
    expect(take.values).toEqual({ iso: null, exposureTime: null })
    // …and survives the wire: the PUT body carries both resets.
    expect(JSON.stringify({ camera: take.values })).toBe('{"camera":{"iso":null,"exposureTime":null}}')
  })

  it('resets after take, so the next window starts empty', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    expect(pending.take().values).toEqual({ iso: 800 })
    expect(pending.take().values).toEqual({})
    pending.stage({ stabilization: false })
    expect(pending.take().values).toEqual({ stabilization: false })
  })

  it('an empty patch stages nothing', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({})
    expect(pending.take().values).toEqual({})
  })

  it('a key staged after the take starts the next patch instead of joining the one in flight', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    const inFlight = pending.take()
    pending.stage({ iso: 1600 })
    pending.stage({ exposureCompensation: 2 })
    // The take already handed its values over: the flush PUTs them unchanged.
    expect(inFlight.values).toEqual({ iso: 800 })
    expect(pending.take().values).toEqual({ iso: 1600, exposureCompensation: 2 })
  })

  it('clear discards the staged patch without a take', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800, stabilization: false })
    pending.clear()
    expect(pending.take().values).toEqual({})
    pending.stage({ iso: 400 })
    expect(pending.take().values).toEqual({ iso: 400 })
  })

  it('stages a snapshot: a take never aliases the patch the caller handed in', () => {
    const pending = createPendingPatch<Camera>()
    const edit = { iso: 800 }
    pending.stage(edit)
    edit.iso = 1600 // the caller mutates its object after the edit landed
    expect(pending.take().values).toEqual({ iso: 800 })
  })
})

describe('a take settling over the settings mirror', () => {
  // A settings GET replaced the mirror after the edit: iso is back to its
  // pre-edit value, and exposureCompensation came along from the device.
  const STALE_MIRROR: Camera = { iso: null, exposureTime: null, exposureCompensation: -1, stabilization: true }

  it('puts the taken values back over a mirror a stale read replaced', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800, stabilization: false })
    const take = pending.take()
    expect(take.settle(STALE_MIRROR)).toEqual({ ...STALE_MIRROR, iso: 800, stabilization: false })
  })

  it('leaves a key re-touched after the take to the newer edit', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800, stabilization: false })
    const take = pending.take()
    // The user moved iso on while the PUT was in flight: the mirror already
    // shows that edit and the next flush owns the key.
    pending.stage({ iso: 1600 })
    expect(take.settle({ ...STALE_MIRROR, iso: 1600 })).toEqual({ ...STALE_MIRROR, iso: 1600, stabilization: false })
  })

  it('leaves a key the newer edit already flushed alone too — an older PUT answering late must not win', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    const older = pending.take()
    pending.stage({ iso: 1600 })
    const newer = pending.take()
    // The newer PUT answered first; now the older one lands.
    expect(older.settle({ ...STALE_MIRROR, iso: 1600 })).toEqual({ ...STALE_MIRROR, iso: 1600 })
    expect(older.settle(STALE_MIRROR)).toEqual(STALE_MIRROR)
  })

  it('returns the same object when the mirror already agrees, so no consumer re-renders for nothing', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    const take = pending.take()
    const mirror: Camera = { ...STALE_MIRROR, iso: 800 }
    expect(take.settle(mirror)).toBe(mirror)
  })

  it('leaves the keys the flush never sent as the mirror holds them', () => {
    const pending = createPendingPatch<Camera>()
    pending.stage({ iso: 800 })
    const take = pending.take()
    const mirror: Camera = { iso: null, exposureTime: 30_000, exposureCompensation: 4, stabilization: false }
    expect(take.settle(mirror)).toEqual({ ...mirror, iso: 800 })
  })
})
