import { onCleanup } from 'solid-js'

/**
 * One self-clearing message slot: replaces the value now, cancels any still-
 * pending reset, and the reset only clears when the slot still shows this
 * value — so a newer flash is never wiped by an older timer. Must be called
 * under a component owner so the timer dies with the component.
 */
export function flashLater(set: (v: string | ((prev: string) => string)) => void, ms: number): (msg: string) => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  onCleanup(() => { if (timer) clearTimeout(timer) })
  return (msg: string) => {
    set(msg)
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => set((current) => (current === msg ? '' : current)), ms)
  }
}
