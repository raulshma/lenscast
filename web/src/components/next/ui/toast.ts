import { createSignal } from 'solid-js'

export type ToastTone = 'info' | 'success' | 'warning' | 'danger'

export interface Toast {
  id: number
  tone: ToastTone
  message: string
}

const [toasts, setToasts] = createSignal<Toast[]>([])

let nextId = 1

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

export function dismissToast(id: number): void {
  setToasts((list) => list.filter((t) => t.id !== id))
}

/** Test helper: drop the whole stack. */
export function clearToasts(): void {
  setToasts([])
}
