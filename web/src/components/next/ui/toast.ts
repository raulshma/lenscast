import { createSignal } from 'solid-js'

export type ToastTone = 'info' | 'success' | 'warning' | 'danger'

export interface Toast {
  id: number
  tone: ToastTone
  message: string
  /** Set while the exit animation plays; removal follows after the window. */
  exiting?: boolean
}

const [toasts, setToasts] = createSignal<Toast[]>([])

let nextId = 1

/** Exit window: the CSS exit animation is 180ms; removal lands just after. */
const EXIT_MS = 190

/** Current toast stack, oldest first. */
export function toastList(): Toast[] {
  return toasts()
}

/**
 * Push a transient message. Newest last, stack capped — an error storm
 * must not pile up forever. Auto-dismisses after `timeoutMs`.
 */
export function pushToast(tone: ToastTone, message: string, timeoutMs = 4200): void {
  if (!message) return
  const id = nextId++
  setToasts((list) => [...list, { id, tone, message }].slice(-4))
  setTimeout(() => dismissToast(id), timeoutMs)
}

/**
 * Two-phase dismissal: the toast flips to `exiting` and stays in the
 * array so layout holds while the exit animation plays, then drops out
 * once the window has elapsed — the timer, not animation events, is
 * the reliable removal path (jsdom fires none). Idempotent: an
 * already-exiting toast never double-schedules.
 */
export function dismissToast(id: number): void {
  const target = toasts().find((t) => t.id === id)
  if (!target || target.exiting) return
  setToasts((list) => list.map((t) => (t.id === id ? { ...t, exiting: true } : t)))
  setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), EXIT_MS)
}

/** Test helper: drop the whole stack. */
export function clearToasts(): void {
  setToasts([])
}
