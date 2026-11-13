interface Props {
  value: number
  min: number
  max: number
  step?: number
  disabled?: boolean
  onChange: (v: number) => void
  ariaLabel?: string
}

/** Range slider; accent color comes from the native control token. */
export default function Slider(props: Props) {
  return (
    <input
      type="range"
      class="nx-slider"
      min={props.min}
      max={props.max}
      step={props.step ?? 1}
      value={props.value}
      disabled={props.disabled}
      aria-label={props.ariaLabel}
      onInput={(e) => props.onChange(Number(e.currentTarget.value))}
    />
  )
}
