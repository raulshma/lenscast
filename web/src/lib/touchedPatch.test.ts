import { describe, expect, it } from 'vitest'
import { createTouchedKeys, pickKeys } from './touchedPatch'

describe('createTouchedKeys', () => {
  it('unions keys across separate patches', () => {
    const touched = createTouchedKeys()
    touched.touch(['iso'])
    touched.touch(['exposureTime'])
    touched.touch(['zoomRatio', 'frameRate'])
    expect(touched.take().sort()).toEqual(['exposureTime', 'frameRate', 'iso', 'zoomRatio'])
  })

  it('dedupes a key touched twice within one window', () => {
    const touched = createTouchedKeys()
    touched.touch(['iso'])
    touched.touch(['iso', 'focusDistance'])
    expect(touched.take().sort()).toEqual(['focusDistance', 'iso'])
  })

  it('resets after take, so the next window starts empty', () => {
    const touched = createTouchedKeys()
    touched.touch(['iso'])
    expect(touched.take()).toEqual(['iso'])
    expect(touched.take()).toEqual([])
    touched.touch(['whiteBalance'])
    expect(touched.take()).toEqual(['whiteBalance'])
  })

  it('an empty patch is a no-op', () => {
    const touched = createTouchedKeys()
    touched.touch([])
    expect(touched.take()).toEqual([])
  })
})

describe('pickKeys', () => {
  it('carries only the touched keys, values from the given source', () => {
    const mirror = { iso: 800, exposureTime: 10_000, zoomRatio: 2.5 }
    expect(pickKeys(mirror, ['iso', 'zoomRatio'])).toEqual({ iso: 800, zoomRatio: 2.5 })
  })

  it('a key touched twice carries the newest value at flush time', () => {
    // The tracker stores keys only; the flush reads values from the mirror
    // handed to pickKeys — here the second mirror, the latest one.
    const latest = { iso: 1600, zoomRatio: 1.0 }
    expect(pickKeys(latest, ['iso'])).toEqual({ iso: 1600 })
  })

  it('skips keys absent from the source', () => {
    const mirror = { iso: 800 }
    expect(pickKeys(mirror, ['iso', 'exposureTime'])).toEqual({ iso: 800 })
  })

  it('an empty touched set picks nothing', () => {
    expect(pickKeys({ iso: 800 }, [])).toEqual({})
  })
})
