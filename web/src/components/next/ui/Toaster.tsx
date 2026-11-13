import { For } from 'solid-js'
import { toastList, dismissToast, type ToastTone } from './toast'

const TONE_CLASSES: Record<ToastTone, string> = {
  info: 'nx-toast-info',
  success: 'nx-toast-success',
  warning: 'nx-toast-warning',
  danger: 'nx-toast-danger',
}

/** Fixed bottom-right toast stack; tap a toast to dismiss it early. */
export default function Toaster() {
  return (
    <div class="nx-toasts" aria-live="polite">
      <For each={toastList()}>
        {(toast) => (
          <div
            class={`nx-toast ${TONE_CLASSES[toast.tone]}`}
            role="status"
            onClick={() => dismissToast(toast.id)}
          >
            <span>{toast.message}</span>
          </div>
        )}
      </For>
    </div>
  )
}
