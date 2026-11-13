import type { JSX } from 'solid-js'

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral'

interface Props {
  tone: StatusTone
  label: string
  /** Animate the dot — only for genuinely live states (REC, LIVE). */
  pulse?: boolean
}

/**
 * Compact live-state chip pinned to a settings card header's right edge
 * (`settings-card-badge`). Mono uppercase label + dot, colored by tone.
 * The classic shell hides the badge entirely (App.css), so this component
 * only changes pixels under the next UI. Wrap in <Show> at the call site
 * when the chip should disappear in the resting state.
 */
export default function StatusChip(props: Props): JSX.Element {
  return (
    <span class="settings-card-badge" data-tone={props.tone} data-pulse={props.pulse ? 'true' : undefined}>
      <span class="settings-card-badge-dot" aria-hidden="true" />
      <span class="settings-card-badge-label">{props.label}</span>
    </span>
  )
}
