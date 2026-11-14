export type LedTone = 'off' | 'ok' | 'warn' | 'rec' | 'accent' | 'danger'

interface Props {
  tone?: LedTone
  class?: string
}

/**
 * State LED: off = faint, ok = green + glow, warn = amber pulse,
 * rec = signal-magenta pulse (the on-air state), accent = cyan,
 * danger = static red fault. Magenta = your picture is going out;
 * red = something is wrong. Color carries the meaning.
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
