import type { LensInfo } from '../types'

interface Props {
  lenses: () => LensInfo[]
  handleSelectLens: (index: number) => void
}

function fmtFocalLength(fl: number): string {
  if (fl <= 0) return ''
  return Number.isInteger(fl) ? `${fl}mm` : `${fl.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}mm`
}

export default function LensSelector(props: Props) {
  return (
    <div class="lens-selector" id="lens-selector">
      {props.lenses().map((lens) => (
        <button
          class="lens-btn"
          classList={{ 'lens-btn-active': lens.selected }}
          onClick={() => props.handleSelectLens(lens.index)}
        >
          <span class="lens-btn-label">{lens.label}</span>
          <span class="lens-btn-focal">{fmtFocalLength(lens.focalLength)}</span>
        </button>
      ))}
    </div>
  )
}
