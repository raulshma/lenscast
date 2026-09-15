import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'

/** The keys of [T] whose values are booleans. */
export type BooleanKeys<T> = { [K in keyof T]: T[K] extends boolean ? K : never }[keyof T]

// API_DEFAULTS is a value literal that predates some StreamingSettings
// booleans, so it can't be indexed by the full key union unaided.
const DEFAULTS = API_DEFAULTS as unknown as Record<
  BooleanKeys<AllSettings['streaming']>,
  boolean
>

/**
 * The one props shape for a plain streaming toggle: the field name appears
 * once at the call site, and both the "live value or default" read and the
 * negated save patch derive from it — the triple repetition of the name
 * across `checked` and `onToggle` cannot drift apart. Returns accessors, so
 * spread/call them inside JSX (`checked={tg.checked()}`) to keep Solid's
 * reactivity.
 */
export function streamToggle(
  section: () => AllSettings['streaming'] | null | undefined,
  key: BooleanKeys<AllSettings['streaming']>,
  save: (patch: Partial<AllSettings['streaming']>) => void,
) {
  return {
    checked: () => section()?.[key] ?? DEFAULTS[key],
    onToggle: () =>
      save({ [key]: !(section()?.[key] ?? DEFAULTS[key]) } as Partial<AllSettings['streaming']>),
  }
}
