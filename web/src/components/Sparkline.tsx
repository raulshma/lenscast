import { createMemo, Show } from 'solid-js'

interface Props {
  /** Oldest-first samples; the line is drawn over whatever is provided. */
  samples: number[]
  /** ViewBox width; also the rendered width. Default 120. */
  width?: number
  /** ViewBox height; also the rendered height. Default 28. */
  height?: number
  /** Accessible description surfaced as the SVG label + title. */
  title?: string
}

const DEFAULT_WIDTH = 120
const DEFAULT_HEIGHT = 28
/** Keep the stroke inside the viewBox on the extremes. */
const PAD = 2

function round1(value: number): number {
  return Math.round(value * 10) / 10
}

/**
 * Dependency-free sparkline: a polyline over the sample buffer, normalized
 * to the min..max range of the data. Flat data collapses to a single
 * centered line instead of dividing by zero. Stroke is currentColor so the
 * parent picks the theme color.
 */
export default function Sparkline(props: Props) {
  const width = () => props.width ?? DEFAULT_WIDTH
  const height = () => props.height ?? DEFAULT_HEIGHT

  const points = createMemo(() => {
    const samples = props.samples
    if (samples.length < 2) return null
    let min = Infinity
    let max = -Infinity
    for (const sample of samples) {
      if (sample < min) min = sample
      if (sample > max) max = sample
    }
    const span = max - min
    const stepX = (width() - PAD * 2) / (samples.length - 1)
    return samples
      .map((sample, i) => {
        const x = PAD + i * stepX
        const y = span === 0
          ? height() / 2
          : PAD + (1 - (sample - min) / span) * (height() - PAD * 2)
        return `${round1(x)},${round1(y)}`
      })
      .join(' ')
  })

  return (
    <Show
      when={points()}
      fallback={<span style={{ display: 'inline-block', width: `${width()}px`, height: `${height()}px` }} aria-hidden="true" />}
    >
      <svg
        viewBox={`0 0 ${width()} ${height()}`}
        width={width()}
        height={height()}
        preserveAspectRatio="none"
        role="img"
        aria-label={props.title}
      >
        <Show when={props.title}>
          <title>{props.title}</title>
        </Show>
        <polyline
          points={points()!}
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </Show>
  )
}
