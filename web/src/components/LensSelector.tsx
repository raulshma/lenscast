import type { LensInfo } from '../types'
import { fmtFocalLength, lensLabel } from '../lib/cameraLabels'

interface Props {
  lenses: () => LensInfo[]
  handleSelectLens: (index: number) => void
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
          <span class="lens-btn-label">{lensLabel(lens)}</span>
          <span class="lens-btn-focal">{fmtFocalLength(lens.focalLength)}</span>
        </button>
      ))}
    </div>
  )
}
