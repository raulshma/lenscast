import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

interface Props {
  /** Header title — Golos 600, sentence case. No section codes. */
  title?: string
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
 * The instrument faceplate: plate surface, machined top bevel, hairline,
 * and a header whose Golos title carries the 2px cyan tick. The
 * console's section card.
 */
export default function Panel(props: Props) {
  return (
    <section class={`nx-panel${props.class ? ` ${props.class}` : ''}`}>
      <Show when={props.title || props.actions || props.badge}>
        <header class="nx-panel-header">
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
