interface Props {
  value: number
  min: number
  max: number
  step?: number
  disabled?: boolean
  onChange: (v: number) => void
  ariaLabel?: string
}

/** Range slider: cyan fill up to the value, bone thumb — the fill length
 *  rides a `--nx-slider-pct` custom property the kit CSS reads. */
export default function Slider(props: Props) {
  const fill = () => {
    const span = props.max - props.min || 1
    const pct = ((props.value - props.min) / span) * 100
    return `${Math.min(100, Math.max(0, pct))}%`
  }
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
      style={{ '--nx-slider-pct': fill() }}
      onInput={(e) => props.onChange(Number(e.currentTarget.value))}
    />
  )
}
