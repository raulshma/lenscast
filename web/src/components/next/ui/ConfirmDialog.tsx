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
  /**
   * An optional gentler alternative action (the restart dialog's "soft
   * restart" beside the deep restart): rendered as a secondary button beside
   * the confirm so one dialog offers both intensities.
   */
  alternativeLabel?: string
  onAlternative?: () => void
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
  let dialog: HTMLDivElement | undefined

  createEffect(() => {
    if (!props.open) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [])
    queueMicrotask(() => focusable()[0]?.focus())

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !props.busy) {
        props.onClose()
        return
      }
      if (e.key !== 'Tab') return
      const items = focusable()
      const first = items[0]
      const last = items[items.length - 1]
      if (!first || !last) return
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    onCleanup(() => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    })
  })

  return (
    <Show when={props.open}>
      <div class="nx-modal-scrim" onClick={() => { if (!props.busy) props.onClose() }}>
        <div
          ref={dialog}
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
            <Show when={props.alternativeLabel && props.onAlternative}>
              <Button variant="secondary" disabled={props.busy} onClick={props.onAlternative}>
                {props.alternativeLabel}
              </Button>
            </Show>
            <Button variant={props.danger ? 'danger' : 'primary'} loading={props.busy} onClick={props.onConfirm}>
              {props.confirmLabel ?? t('common.confirm')}
            </Button>
          </div>
        </div>
      </div>
    </Show>
  )
}
