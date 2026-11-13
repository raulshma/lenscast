import type { JSX } from 'solid-js'
import type { ButtonVariant, ButtonSize } from './Button'
import Button from './Button'

interface Props {
  /** Required: icon-only buttons must say what they do. */
  label: string
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  disabled?: boolean
  title?: string
  ariaPressed?: boolean
  ariaExpanded?: boolean
  onClick?: (e: MouseEvent) => void
  class?: string
  children?: JSX.Element
}

/** Icon-only button — a `Button` that refuses to render without a label. */
export default function IconButton(props: Props) {
  return (
    <Button
      variant={props.variant}
      size={props.size}
      icon={true}
      loading={props.loading}
      disabled={props.disabled}
      title={props.title ?? props.label}
      ariaLabel={props.label}
      ariaPressed={props.ariaPressed}
      ariaExpanded={props.ariaExpanded}
      onClick={props.onClick}
      class={props.class}
    >
      {props.children}
    </Button>
  )
}
