import { Show } from 'solid-js'
import Slider from './Slider'

interface Props {
  label: string
  value: number
  min: number
  max: number
  step?: number
  /** Right-aligned current value; defaults to the raw number. */
  display?: string
  disabled?: boolean
  onChange: (v: number) => void
}

/**
 * Labelled slider: name + live mono value above, the range below —
 * the console's numeric vocabulary. One row per setting.
 */
export default function SliderField(props: Props) {
  return (
    <div class="nx-slider-field">
      <div class="nx-slider-field-head">
        <span class="nx-field-label">{props.label}</span>
        <span class="nx-slider-field-value nx-mono">
          <Show when={props.display !== undefined} fallback={String(props.value)}>
            {props.display}
          </Show>
        </span>
      </div>
      <Slider
        value={props.value}
        min={props.min}
        max={props.max}
        step={props.step}
        disabled={props.disabled}
        onChange={props.onChange}
        ariaLabel={props.label}
      />
    </div>
  )
}
