import { createEffect, onCleanup, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import { t } from '../../../lib/i18n'
import Button from './Button'

interface Props {
  open: boolean
  title: string
  message: JSX.Element
  /** Defaults to the localized common.confirm. */
  confirmLabel?: string
  /** Defaults to the localized common.cancel. */
  cancelLabel?: string
  /** Renders the confirm as the destructive action. */
  danger?: boolean
  /** True while the confirmed action runs: the confirm spins and Escape/scrim closes are blocked. */
  busy?: boolean
  onConfirm: () => void
  onClose: () => void
}

/**
 * The kit confirm: a small `.nx-modal` asking before a destructive action —
 * the themed, localized replacement for window.confirm (whose buttons render
 * in the browser's language, never the app's locale). Escape and the scrim
 * close it; while `busy` the dialog locks until the action settles.
 */
export default function ConfirmDialog(props: Props) {
  createEffect(() => {
    if (!props.open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !props.busy) props.onClose()
    }
    window.addEventListener('keydown', onKey)
    onCleanup(() => window.removeEventListener('keydown', onKey))
  })

  return (
    <Show when={props.open}>
      <div class="nx-modal-scrim" onClick={() => { if (!props.busy) props.onClose() }}>
        <div
          class="nx-modal nx-confirm"
          role="alertdialog"
          aria-modal="true"
          aria-label={props.title}
          onClick={(e) => e.stopPropagation()}
        >
          <div class="nx-confirm-body">
            <span class="nx-confirm-title">{props.title}</span>
            <span class="nx-confirm-message">{props.message}</span>
          </div>
          <div class="nx-confirm-actions">
            <Button variant="secondary" disabled={props.busy} onClick={props.onClose}>
              {props.cancelLabel ?? t('common.cancel')}
            </Button>
            <Button variant={props.danger ? 'danger' : 'primary'} loading={props.busy} onClick={props.onConfirm}>
              {props.confirmLabel ?? t('common.confirm')}
            </Button>
          </div>
        </div>
      </div>
    </Show>
  )
}
