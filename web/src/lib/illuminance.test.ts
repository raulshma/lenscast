import { describe, expect, it } from 'vitest'
import { illuminanceVerdict } from './illuminance'

/**
 * The shared lux verdict (lib/illuminance.ts): one state machine for the
 * overlay card, the overlay panel and the rule editor's live row, pinned
 * here so the three surfaces cannot drift apart again.
 */
describe('illuminanceVerdict', () => {
  it('renders a sanitized reading whatever the verdict field says', () => {
    expect(illuminanceVerdict({ illuminanceLux: 320, lightSensorAvailable: true })).toEqual({ kind: 'value', lux: 320 })
    expect(illuminanceVerdict({ illuminanceLux: 0, lightSensorAvailable: true })).toEqual({ kind: 'value', lux: 0 })
    // A measured lux outranks even a nonsense "no sensor" beside it.
    expect(illuminanceVerdict({ illuminanceLux: 180, lightSensorAvailable: false })).toEqual({ kind: 'value', lux: 180 })
  })

  it('folds invalid readings into no reading', () => {
    for (const lux of [null, undefined, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(illuminanceVerdict({ illuminanceLux: lux, lightSensorAvailable: true }).kind).toBe('waiting')
    }
  })

  it('claims "no sensor" only on a known false verdict', () => {
    expect(illuminanceVerdict({ illuminanceLux: null, lightSensorAvailable: false })).toEqual({ kind: 'unavailable' })
    expect(illuminanceVerdict({ illuminanceLux: null, lightSensorAvailable: undefined }).kind).toBe('waiting')
    expect(illuminanceVerdict({ illuminanceLux: null, lightSensorAvailable: null }).kind).toBe('waiting')
  })

  it('waits on silence — no status, or a device too old for the pair', () => {
    expect(illuminanceVerdict(null)).toEqual({ kind: 'waiting' })
    expect(illuminanceVerdict(undefined)).toEqual({ kind: 'waiting' })
    expect(illuminanceVerdict({})).toEqual({ kind: 'waiting' })
    expect(illuminanceVerdict({ illuminanceLux: null, lightSensorAvailable: true }).kind).toBe('waiting')
  })
})
