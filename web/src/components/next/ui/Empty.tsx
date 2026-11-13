import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

interface Props {
  title: string
  hint?: string
  icon?: JSX.Element
}

/** Quiet empty state for lists and grids with nothing to show yet. */
export default function Empty(props: Props) {
  return (
    <div class="nx-empty">
      <Show when={props.icon}>{props.icon}</Show>
      <div class="nx-empty-title">{props.title}</div>
      <Show when={props.hint}>
        <div class="nx-empty-hint">{props.hint}</div>
      </Show>
    </div>
  )
}
