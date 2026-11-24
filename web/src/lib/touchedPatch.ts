/**
 * Touched-key tracking for the debounced camera save (issue #25): the
 * dashboard's camera edits PUT only the keys actually touched inside the
 * debounce window, with values read from the latest local mirror at flush
 * time. The old PUT carried the whole client mirror, so a stale iso/exposure
 * sitting in this tab overwrote whatever an automation rule or another
 * client had just written on the device — the "applies, then reverts a few
 * seconds later" repro. The server already merges per key
 * (SettingsWebHandler.put → mergeSection); this module is the client-side
 * half: what keys to send. Pure and store-free so it vitest-tests without a
 * Solid render.
 */

/** Accumulates the union of touched keys, cleared on take (the flush). */
export interface TouchedKeys<K extends string> {
  /** Adds every key of one patch; repeated keys across patches dedupe. */
  touch(keys: Iterable<K>): void
  /** The union accumulated since the last take, then resets the set. */
  take(): K[]
}

export function createTouchedKeys<K extends string>(): TouchedKeys<K> {
  const touched = new Set<K>()
  return {
    touch(keys: Iterable<K>) {
      for (const key of keys) touched.add(key)
    },
    take(): K[] {
      const all = [...touched]
      touched.clear()
      return all
    },
  }
}

/**
 * Builds the partial section a flush PUTs: only the touched keys, values
 * read from the source passed in — the caller passes its LATEST mirror, so
 * a key touched twice within one window carries its newest value, never the
 * one the first patch wrote. Keys absent from the source are skipped, so a
 * key that drifts out of the section shape can never reach the wire.
 */
export function pickKeys<T extends object>(source: T, keys: ReadonlyArray<string>): Partial<T> {
  const picked: Partial<T> = {}
  const sourceRecord = source as Record<string, unknown>
  const pickedRecord = picked as Record<string, unknown>
  for (const key of keys) {
    if (key in sourceRecord) pickedRecord[key] = sourceRecord[key]
  }
  return picked
}
