import { For, Show } from 'solid-js'
import type { LensInfo } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { fmtFocalLength, lensLabel } from '../../../../../lib/cameraLabels'
import { Panel } from '../../../ui'

interface Props {
  lenses: () => LensInfo[]
  handleSelectLens: (index: number) => void
}

/**
 * The lens picker, ported from the classic LensSelector onto the kit: the
 * same per-kind localized chip labels and focal readouts, rendered as a
 * wrap row of kit chips (active chip = selected lens).
 */
export default function LensPanel(props: Props) {
  return (
    <Panel title={t('system.section.lens')}>
      <div role="group" aria-label={t('system.section.lens')} style={{ display: 'flex', 'flex-wrap': 'wrap', gap: '6px' }}>
        <For each={props.lenses()}>
          {(lens) => (
            <button
              type="button"
              class="nx-chip nx-lens-chip"
              classList={{ 'nx-chip-active': lens.selected }}
              onClick={() => props.handleSelectLens(lens.index)}
            >
              <span>{lensLabel(lens)}</span>
              <Show when={fmtFocalLength(lens.focalLength)}>
                <span class="nx-mono">{fmtFocalLength(lens.focalLength)}</span>
              </Show>
            </button>
          )}
        </For>
      </div>
    </Panel>
  )
}
