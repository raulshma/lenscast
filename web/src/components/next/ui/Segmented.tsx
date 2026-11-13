import { For } from 'solid-js'

export interface SegmentOption {
  value: string
  label: string
}

interface Props {
  options: readonly SegmentOption[] | (() => readonly SegmentOption[])
  value: string | undefined
  onChange: (v: string) => void
  ariaLabel?: string
}

/** Segmented control for exclusive small option sets (filters, modes). */
export default function Segmented(props: Props) {
  const options = () => (typeof props.options === 'function' ? props.options() : props.options)
  return (
    <div class="nx-segment" role="group" aria-label={props.ariaLabel}>
      <For each={options()}>
        {(option) => (
          <button
            type="button"
            class="nx-segment-btn"
            classList={{ 'nx-segment-btn-active': props.value === option.value }}
            onClick={() => props.onChange(option.value)}
          >
            {option.label}
          </button>
        )}
      </For>
    </div>
  )
}
