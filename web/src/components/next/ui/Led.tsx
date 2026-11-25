export type LedTone = 'off' | 'ok' | 'warn' | 'rec' | 'accent' | 'danger'

interface Props {
  tone?: LedTone
  class?: string
}

/**
 * State LED: off = faint, ok = green (static), warn = amber outline
 * (static), rec = amber and breathing (the on-air state — a slow
 * opacity cycle, not a blink; the only Led that moves), accent =
 * amber (static), danger = static red fault. Amber = your picture
 * is going out; red = something is wrong. Color carries the meaning.
 */
export default function Led(props: Props) {
  const tone = () => props.tone ?? 'off'
  return (
    <span
      class={`nx-led${props.class ? ` ${props.class}` : ''}`}
      classList={{
        'nx-led-ok': tone() === 'ok',
        'nx-led-warn': tone() === 'warn',
        'nx-led-rec': tone() === 'rec',
        'nx-led-accent': tone() === 'accent',
        'nx-led-danger': tone() === 'danger',
      }}
      aria-hidden="true"
    />
  )
}
