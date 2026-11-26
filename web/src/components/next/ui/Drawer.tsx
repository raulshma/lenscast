import { Show, createEffect, onCleanup } from 'solid-js'
import type { JSX } from 'solid-js'
import { t } from '../../../lib/i18n'
import { IconClose } from '../../icons'
import IconButton from './IconButton'

interface Props {
  open: boolean
  onClose: () => void
  title?: string
  class?: string
  children?: JSX.Element
}

/**
 * Right-edge slide-over for narrow viewports (the Live inspector on
 * tablets, detail panels on phones). Closes on scrim tap or the close
 * button.
 */
export default function Drawer(props: Props) {
  let panel: HTMLElement | undefined

  createEffect(() => {
    if (!props.open) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const focusable = () => Array.from(panel?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [])
    queueMicrotask(() => focusable()[0]?.focus())

    const onKeyDown = (event: KeyboardEvent) => {
      if (document.querySelector('.nx-modal-scrim')) return
      if (event.key === 'Escape') {
        event.preventDefault()
        props.onClose()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusable()
      const first = items[0]
      const last = items[items.length - 1]
      if (!first || !last) return
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    onCleanup(() => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    })
  })

  return (
    <Show when={props.open}>
      <div class="nx-drawer-scrim" onClick={props.onClose} />
      <aside
        ref={panel}
        class={`nx-drawer${props.class ? ` ${props.class}` : ''}`}
        role="dialog"
        aria-modal="true"
        aria-label={props.title}
      >
        <header class="nx-drawer-header">
          <span class="nx-panel-title">{props.title}</span>
          <IconButton label={t('common.close')} onClick={props.onClose}>
            <IconClose size={16} />
          </IconButton>
        </header>
        <div class="nx-drawer-body">{props.children}</div>
      </aside>
    </Show>
  )
}
