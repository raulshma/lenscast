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

/** Native select dressed as an inset console control: the field recipe
 *  on the select, a quiet chevron on the wrapper (inline svg — the kit
 *  carries no glyph font). */
export default function Select(props: Props) {
  const options = () => (typeof props.options === 'function' ? props.options() : props.options)
  return (
    <span class="nx-select-wrap">
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
      <svg
        class="nx-select-chevron"
        aria-hidden="true"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    </span>
  )
}
