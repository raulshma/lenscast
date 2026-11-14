import type { JSX } from 'solid-js'

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'

interface Props {
  tone?: BadgeTone
  /** Mono face for status codes and timers. */
  mono?: boolean
  class?: string
  children: JSX.Element
}

/** Quiet tinted status chip. Compose a `<Led>`/dot inside for live states.
 *  On-air states keep tone="danger" markup and add class="nx-badge-signal" —
 *  the CSS re-points that red at signal magenta (magenta = your picture is
 *  going out; red = something is wrong). */
export default function Badge(props: Props) {
  const tone = () => props.tone ?? 'neutral'
  return (
    <span
      class={`nx-badge${props.class ? ` ${props.class}` : ''}`}
      classList={{
        'nx-badge-neutral': tone() === 'neutral',
        'nx-badge-accent': tone() === 'accent',
        'nx-badge-success': tone() === 'success',
        'nx-badge-warning': tone() === 'warning',
        'nx-badge-danger': tone() === 'danger',
        'nx-badge-info': tone() === 'info',
        'nx-mono': props.mono === true,
      }}
    >
      {props.children}
    </span>
  )
}
