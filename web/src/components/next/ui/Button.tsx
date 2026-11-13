import { Show } from 'solid-js'
import type { JSX } from 'solid-js'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-soft' | 'toggled' | 'warning'
export type ButtonSize = 'sm' | 'md' | 'lg'

interface Props {
  variant?: ButtonVariant
  size?: ButtonSize
  /** Icon-only square; pair with `title`/`ariaLabel` for accessibility. */
  icon?: boolean
  /** Shows a spinner and blocks the click while true. */
  loading?: boolean
  disabled?: boolean
  type?: 'button' | 'submit'
  title?: string
  ariaLabel?: string
  /** For on/off buttons (torch): lands as `aria-pressed`, undefined omits it. */
  ariaPressed?: boolean
  /** For disclosure buttons (strip collapse): lands as `aria-expanded`, undefined omits it. */
  ariaExpanded?: boolean
  onClick?: (e: MouseEvent) => void
  class?: string
  children?: JSX.Element
}

/**
 * The kit button: `.nx-btn` plus variant/size/state classes. One primary
 * action per zone — everything else is secondary, ghost, or danger.
 */
export default function Button(props: Props) {
  return (
    <button
      type={props.type ?? 'button'}
      class={`nx-btn${props.class ? ` ${props.class}` : ''}`}
      classList={{
        'nx-btn-secondary': props.variant === 'secondary' || props.variant === undefined,
        'nx-btn-primary': props.variant === 'primary',
        'nx-btn-ghost': props.variant === 'ghost',
        'nx-btn-danger': props.variant === 'danger',
        'nx-btn-danger-soft': props.variant === 'danger-soft',
        'nx-btn-toggled': props.variant === 'toggled',
        'nx-btn-warning': props.variant === 'warning',
        'nx-btn-sm': props.size === 'sm',
        'nx-btn-lg': props.size === 'lg',
        'nx-btn-icon': props.icon === true,
      }}
      disabled={props.disabled || props.loading}
      title={props.title}
      aria-label={props.ariaLabel}
      aria-pressed={props.ariaPressed}
      aria-expanded={props.ariaExpanded}
      onClick={(e) => { if (!props.loading) props.onClick?.(e) }}
    >
      <Show when={props.loading}>
        <span class="nx-spinner nx-spinner-sm" aria-hidden="true" />
      </Show>
      {props.children}
    </button>
  )
}
