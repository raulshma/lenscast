/**
 * Touched-value tracking for the debounced camera save (issue #25): the
 * dashboard's camera edits PUT only the keys actually touched inside the
 * debounce window — each with the value the USER set, staged at touch time.
 * The server already merges per key (SettingsWebHandler.put → mergeSection);
 * this module is the client-side half: which keys go on the wire, with which
 * values. Pure and store-free so it vitest-tests without a Solid render.
 *
 * Two races made every other source of values the wrong one:
 *
 * - The whole client mirror (the original PUT body) let a stale iso/exposure
 *   sitting in one tab overwrite what an automation rule or another client
 *   had just written on the device — the "applies, then reverts a few
 *   seconds later" repro.
 * - Keys alone, re-read from the mirror at flush time, sent whatever the
 *   last settings GET had put there. Every GET replaces the mirror wholesale
 *   (the 30 s poll, the 3 s model-download lane, the visibility refresh), so
 *   a response landing inside the 400 ms window put the PRE-edit value back
 *   on the wire — or an empty body, where the server omits null keys.
 *
 * The staged patch is therefore the only source the flush reads; a take
 * never reads the mirror back. `settle` only ever writes INTO it.
 */

/** One section's staged patch: touched key → the value set when it was touched. */
export interface PendingPatch<T extends object> {
  /**
   * Stages one edit: every own key of `patch` with the value it carries now.
   * A later stage of the same key wins. An explicit `null` is a value (the
   * ISO / exposure-time reset); an `undefined` serializes away exactly as it
   * always did.
   */
  stage(patch: Partial<T>): void
  /**
   * Hands the staged patch to a flush and resets it. Edits staged after this
   * call start the NEXT patch — a key touched while the flush's PUT is in
   * flight is never swallowed by the PUT that already left.
   */
  take(): PatchTake<T>
  /**
   * Discards the staged patch without sending it: the session ended, or a
   * whole-document write (reset defaults) superseded it.
   */
  clear(): void
}

/** What one flush takes: exactly what its PUT sends, plus the mirror repair. */
export interface PatchTake<T extends object> {
  /** Exactly what the flush PUTs: the touched keys with their staged values. */
  values: Partial<T>
  /**
   * `camera` with this take's values put back, for every key no edit has
   * touched since the take. Called once the PUT succeeded: the sent values
   * are the device's truth for those keys now, so a settings GET that
   * replaced the mirror meanwhile must not leave the control showing its
   * pre-edit state until the next poll. A key a NEWER edit claimed — still
   * staged, or already sent by a newer flush — is left alone: the mirror
   * already shows that edit, and an older PUT answering late must not undo
   * it. Returns `camera` itself when it already agrees; the caller skips its
   * write on that, so nothing re-renders for an unchanged mirror.
   */
  settle(camera: T): T
}

export function createPendingPatch<T extends object>(): PendingPatch<T> {
  const staged = new Map<string, unknown>()
  // The edit clock. Every staged key takes the next tick, and a take
  // remembers the tick each of its keys carried — so `settle` can tell
  // whether an edit newer than the take has claimed a key since, whatever
  // became of that edit afterwards.
  let clock = 0
  const editedAt = new Map<string, number>()

  return {
    stage(patch) {
      for (const [key, value] of Object.entries(patch)) {
        staged.set(key, value)
        editedAt.set(key, ++clock)
      }
    },

    take() {
      // A copy, detached from the caller's patch object and from the next
      // window: the PUT body is fixed at this moment.
      const values = Object.fromEntries(staged) as Partial<T>
      staged.clear()
      const takenAt = new Map<string, number | undefined>()
      for (const key of Object.keys(values)) takenAt.set(key, editedAt.get(key))
      return {
        values,
        settle(camera) {
          const current = camera as Record<string, unknown>
          let settled: Record<string, unknown> | undefined
          for (const [key, value] of Object.entries(values)) {
            if (editedAt.get(key) !== takenAt.get(key)) continue
            if (Object.is(current[key], value)) continue
            settled ??= { ...current }
            settled[key] = value
          }
          return (settled ?? camera) as T
        },
      }
    },

    clear() {
      staged.clear()
    },
  }
}
