import { onCleanup, onMount } from 'solid-js'

/**
 * One fixed-rate, visibility-gated interval poll — the single-lane sibling
 * of pollLadder for panels that poll at one rate: run `refresh` immediately,
 * then every `intervalMs`, skipping ticks while the tab is hidden. Owns the
 * mount/unmount wiring so components don't each hand-roll the interval and
 * the visibility gate.
 */
export function createVisiblePoll(refresh: () => void | Promise<void>, intervalMs: number): void {
  onMount(() => {
    void refresh()
    const timer = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      void refresh()
    }, intervalMs)
    onCleanup(() => clearInterval(timer))
  })
}
