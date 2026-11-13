import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

interface Props {
  /** Mono uppercase header title. */
  title?: string
  /** Running section code rendered before the title (01, 02…). */
  code?: string
  /** Right-side action slot (icon buttons, chips). */
  actions?: JSX.Element
  /** Status slot between title and actions (LED, badge). */
  badge?: JSX.Element
  /** No body padding — tables, grids, full-bleed content. */
  flush?: boolean
  class?: string
  children?: JSX.Element
}

/**
 * The instrument faceplate: a two-tone header (mono title + optional
 * section code) over a bordered body. The console's section card.
 */
export default function Panel(props: Props) {
  return (
    <section class={`nx-panel${props.class ? ` ${props.class}` : ''}`}>
      <Show when={props.title || props.code || props.actions || props.badge}>
        <header class="nx-panel-header">
          <Show when={props.code}>
            <span class="nx-panel-code">{props.code}</span>
          </Show>
          <Show when={props.title}>
            <span class="nx-panel-title">{props.title}</span>
          </Show>
          {props.badge}
          <Show when={props.actions}>
            <div class="nx-panel-actions">{props.actions}</div>
          </Show>
        </header>
      </Show>
      <div class="nx-panel-body" classList={{ 'nx-panel-body-flush': props.flush === true }}>
        {props.children}
      </div>
    </section>
  )
}
