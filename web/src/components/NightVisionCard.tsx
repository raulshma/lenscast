import type { NightVisionMode } from '../types'
import { t } from '../lib/i18n'

interface NightVisionCardProps {
  value: NightVisionMode
  onChange: (mode: NightVisionMode) => void
}

/** Wire value → i18n key for the three night-vision buttons. */
const MODE_KEYS: Record<NightVisionMode, string> = {
  OFF: 'common.off',
  AUTO: 'common.auto',
  ON: 'option.irOn',
}

export function NightVisionCard(props: NightVisionCardProps) {
  return (
    <div class="card bg-base-200 shadow-sm">
      <div class="card-body py-3 px-4">
        <div class="flex items-center justify-between mb-2">
          <h3 class="font-semibold text-sm">{t('nightvision.title')}</h3>
          <span class="text-xs text-base-content/60">
            {props.value === 'ON' ? t('common.active') : props.value === 'AUTO' ? t('common.auto') : t('common.off')}
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
              {t(MODE_KEYS[mode])}
            </button>
          ))}
        </div>
        <p class="text-xs text-base-content/50 mt-1">
          {props.value === 'ON'
            ? t('nightvision.descOn')
            : props.value === 'AUTO'
              ? t('nightvision.descAuto')
              : t('nightvision.descOff')}
        </p>
      </div>
    </div>
  )
}
