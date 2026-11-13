import { For } from 'solid-js'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

interface Props {
  value: string | undefined
  options: readonly SelectOption[] | (() => readonly SelectOption[])
  onChange: (v: string) => void
  disabled?: boolean
  small?: boolean
  ariaLabel?: string
}

/** Native select dressed as an inset console control. */
export default function Select(props: Props) {
  const options = () => (typeof props.options === 'function' ? props.options() : props.options)
  return (
    <select
      class="nx-select"
      classList={{ 'nx-input-sm': props.small === true }}
      value={props.value}
      disabled={props.disabled}
      aria-label={props.ariaLabel}
      onChange={(e) => props.onChange(e.currentTarget.value)}
    >
      <For each={options()}>
        {(option) => (
          <option value={option.value} disabled={option.disabled} selected={props.value === option.value}>
            {option.label}
          </option>
        )}
      </For>
    </select>
  )
}
