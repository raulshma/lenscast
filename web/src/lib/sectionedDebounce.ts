/**
 * One debounce timer slot per settings section. The camera and app tabs
 * save disjoint sections, so a single shared timer would let the
 * last-edited tab silently replace the other tab's pending PUT — and the
 * 30 s status poll would then revert the dropped edit on screen. Each
 * section cancels only its own pending timer.
 */
export type SaveSection = 'camera' | 'streaming'

export function createSectionedDebouncer() {
  const timers: Partial<Record<SaveSection, ReturnType<typeof setTimeout>>> = {}
  return function debounceSave(section: SaveSection, fn: () => void, ms = 400) {
    const pending = timers[section]
    if (pending) clearTimeout(pending)
    timers[section] = setTimeout(fn, ms)
  }
}
