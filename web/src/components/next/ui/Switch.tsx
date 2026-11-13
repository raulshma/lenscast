interface Props {
  checked: boolean | undefined
  onChange: (v: boolean) => void
  disabled?: boolean
  ariaLabel?: string
}

/** Accessible toggle: hidden checkbox drives track + thumb via CSS. */
export default function Switch(props: Props) {
  return (
    <label class="nx-switch">
      <input
        type="checkbox"
        checked={props.checked}
        disabled={props.disabled}
        aria-label={props.ariaLabel}
        onChange={(e) => props.onChange(e.currentTarget.checked)}
      />
      <span class="nx-switch-track" aria-hidden="true" />
      <span class="nx-switch-thumb" aria-hidden="true" />
    </label>
  )
}
