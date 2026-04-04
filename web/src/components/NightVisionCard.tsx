import type { NightVisionMode } from '../types'
import { NIGHT_VISION_LABELS } from '../types'

interface NightVisionCardProps {
  value: NightVisionMode
  onChange: (mode: NightVisionMode) => void
}

export function NightVisionCard(props: NightVisionCardProps) {
  return (
    <div class="card bg-base-200 shadow-sm">
      <div class="card-body py-3 px-4">
        <div class="flex items-center justify-between mb-2">
          <h3 class="font-semibold text-sm">Night Vision / IR</h3>
          <span class="text-xs text-base-content/60">
            {props.value === 'ON' ? 'Active' : props.value === 'AUTO' ? 'Auto' : 'Off'}
          </span>
        </div>
        <div class="flex gap-2">
          {(['OFF', 'AUTO', 'ON'] as NightVisionMode[]).map((mode) => (
            <button
              type="button"
              class={`btn btn-sm flex-1 ${
                props.value === mode
                  ? mode === 'ON'
                    ? 'btn-primary'
                    : 'btn-secondary'
                  : 'btn-ghost'
              }`}
              onClick={() => props.onChange(mode)}
            >
              {NIGHT_VISION_LABELS[mode]}
            </button>
          ))}
        </div>
        <p class="text-xs text-base-content/50 mt-1">
          {props.value === 'ON'
            ? 'Forces night scene mode with maximum exposure for best low-light performance.'
            : props.value === 'AUTO'
              ? 'Automatically adapts to lighting conditions with auto flash.'
              : 'Standard camera behavior without low-light enhancements.'}
        </p>
      </div>
    </div>
  )
}
