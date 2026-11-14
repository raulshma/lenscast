import { Show } from 'solid-js'
import type { JSX } from 'solid-js'
import { t } from '../../../lib/i18n'
import { IconClose } from '../../icons'
import IconButton from './IconButton'

interface Props {
  open: boolean
  onClose: () => void
  title?: string
  children?: JSX.Element
}

/**
 * Right-edge slide-over for narrow viewports (the Live inspector on
 * tablets, detail panels on phones). Closes on scrim tap or the close
 * button.
 */
export default function Drawer(props: Props) {
  return (
    <Show when={props.open}>
      <div class="nx-drawer-scrim" onClick={props.onClose} />
      <aside class="nx-drawer" role="dialog" aria-modal="true" aria-label={props.title}>
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
