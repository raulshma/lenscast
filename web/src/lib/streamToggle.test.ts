import { describe, expect, it } from 'vitest'
import { streamToggle } from './streamToggle'
import type { BooleanKeys } from './streamToggle'
import { API_DEFAULTS } from '../api/defaults'
import type { AllSettings } from '../types'

const streaming = (overrides: Partial<AllSettings['streaming']> = {}): AllSettings['streaming'] =>
  ({ ...API_DEFAULTS, ...overrides }) as AllSettings['streaming']

// Contract pin for streamToggle's defaults lookup: streamToggle.ts casts
// API_DEFAULTS to `Record<BooleanKeys<…>, boolean>`, which defeats the
// compiler — a future StreamingSettings boolean missing from API_DEFAULTS
// would read `undefined` silently. Enforced by tsc (the constant only
// typechecks while the exclusion is `never`); surfaced as a test so the
// guarantee is visible next to the code that depends on it.
type MissingDefaults = Exclude<
  BooleanKeys<AllSettings['streaming']>,
  keyof typeof API_DEFAULTS
>
const everyStreamingBooleanHasADefault: [MissingDefaults] extends [never] ? true : false = true

describe('streamToggle', () => {
  it('has a default for every streaming boolean key', () => {
    expect(everyStreamingBooleanHasADefault).toBe(true)
  })


  it('reads the live value when the section is present', () => {
    const tg = streamToggle(() => streaming({ httpsEnabled: true }), 'httpsEnabled', () => {})
    expect(tg.checked()).toBe(true)
  })

  it('falls back to the default when the section is absent', () => {
    const tg = streamToggle(() => null, 'httpsEnabled', () => {})
    expect(tg.checked()).toBe(API_DEFAULTS.httpsEnabled)
  })

  it('saves the negated live value', () => {
    let saved: Partial<AllSettings['streaming']> | null = null
    const tg = streamToggle(() => streaming({ httpsEnabled: true }), 'httpsEnabled', (p) => { saved = p })
    tg.onToggle()
    expect(saved).toEqual({ httpsEnabled: false })
  })

  it('saves the negated default when the section is absent', () => {
    let saved: Partial<AllSettings['streaming']> | null = null
    const tg = streamToggle(() => undefined, 'httpsEnabled', (p) => { saved = p })
    tg.onToggle()
    expect(saved).toEqual({ httpsEnabled: !API_DEFAULTS.httpsEnabled })
  })
})
