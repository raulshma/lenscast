interface Props {
  value: string | undefined
  onInput: (v: string) => void
  type?: string
  placeholder?: string
  disabled?: boolean
  /** Mono face for URLs, tokens, identifiers. */
  mono?: boolean
  small?: boolean
  class?: string
  ariaLabel?: string
}

/** Single-line text input, mono-capable for URLs and tokens. */
export default function TextInput(props: Props) {
  return (
    <input
      type={props.type ?? 'text'}
      class={`nx-input${props.class ? ` ${props.class}` : ''}`}
      classList={{
        'nx-mono': props.mono === true,
        'nx-input-sm': props.small === true,
      }}
      value={props.value}
      placeholder={props.placeholder}
      disabled={props.disabled}
      aria-label={props.ariaLabel}
      onInput={(e) => props.onInput(e.currentTarget.value)}
    />
  )
}
