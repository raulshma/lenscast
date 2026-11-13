import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

interface RowProps {
  label: string
  hint?: string
  children?: JSX.Element
}

/**
 * One settings row: label (+ hint) on the left, control on the right.
 * The console's default form vocabulary for compact card bodies.
 */
export default function FieldRow(props: RowProps) {
  return (
    <div class="nx-field-row">
      <div class="nx-field-main">
        <span class="nx-field-label">{props.label}</span>
        <Show when={props.hint}>
          <span class="nx-field-hint">{props.hint}</span>
        </Show>
      </div>
      {props.children}
    </div>
  )
}

interface FieldProps {
  label: string
  hint?: string
  children?: JSX.Element
}

/** Stacked field: label above a full-width control. */
export function Field(props: FieldProps) {
  return (
    <div class="nx-field">
      <span class="nx-field-label">{props.label}</span>
      {props.children}
      <Show when={props.hint}>
        <span class="nx-field-hint">{props.hint}</span>
      </Show>
    </div>
  )
}
