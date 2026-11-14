import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

interface Props {
  title: string
  hint?: string
  icon?: JSX.Element
  /** The first action (e.g. a Button) offered under the hint. */
  action?: JSX.Element
}

/** Quiet empty state: it names the object, then offers the first action. */
export default function Empty(props: Props) {
  return (
    <div class="nx-empty">
      <Show when={props.icon}>{props.icon}</Show>
      <div class="nx-empty-title">{props.title}</div>
      <Show when={props.hint}>
        <div class="nx-empty-hint">{props.hint}</div>
      </Show>
      <Show when={props.action}>
        <div class="nx-empty-actions">{props.action}</div>
      </Show>
    </div>
  )
}
