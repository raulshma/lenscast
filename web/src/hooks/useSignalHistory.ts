import { createSignal } from 'solid-js'

// Ring-buffer history for a numeric signal (bandwidth, battery level, ...):
// push samples over time, keep the last N, drop the oldest. The buffer math
// lives in the pure pushSample helper so vitest can cover it without Solid;
// the hook is only the thin signal wrapper.

export interface SignalHistoryOptions {
  /** How many samples to retain; the oldest are dropped first. Default 60. */
  capacity?: number
}

export interface SignalHistory {
  /** Oldest-first snapshot of the retained samples (reactive). */
  values: () => number[]
  push(sample: number): void
  reset(): void
}

const DEFAULT_CAPACITY = 60

/** Pure drop-oldest append: returns a new buffer, never mutates the input. */
export function pushSample(samples: number[], sample: number, capacity: number): number[] {
  const next = samples.length + 1 > capacity
    ? samples.slice(samples.length + 1 - capacity)
    : samples.slice()
  next.push(sample)
  return next
}

export function useSignalHistory(options: SignalHistoryOptions = {}): SignalHistory {
  const capacity = options.capacity ?? DEFAULT_CAPACITY
  const [values, setValues] = createSignal<number[]>([])
  return {
    values,
    push: (sample) => setValues((prev) => pushSample(prev, sample, capacity)),
    reset: () => setValues([]),
  }
}
