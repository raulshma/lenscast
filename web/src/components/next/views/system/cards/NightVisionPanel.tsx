import type { NightVisionMode } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, Segmented, Badge } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface NightVisionPanelProps {
  value: NightVisionMode
  onChange: (mode: NightVisionMode) => void
}

/** Wire value → i18n key for the three night-vision buttons. */
const MODE_KEYS: Record<NightVisionMode, string> = {
  OFF: 'common.off',
  AUTO: 'common.auto',
  ON: 'option.irOn',
}

/**
 * Night vision / IR, ported from the classic NightVisionCard (which was a
 * named-export daisyUI card) onto the kit: the mode state moves to the
 * panel badge, the three buttons become a segmented control, and the
 * per-state description becomes the field hint underneath.
 */
export default function NightVisionPanel(props: NightVisionPanelProps) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const changeMode = (mode: NightVisionMode) => {
    if (!readOnly()) props.onChange(mode)
  }

  return (
    <SettingItem id="nightVision.mode">
      <Panel
        title={t('nightvision.title')}
        badge={
          <Badge tone={props.value === 'ON' ? 'accent' : props.value === 'AUTO' ? 'info' : 'neutral'}>
            {props.value === 'ON' ? t('common.active') : props.value === 'AUTO' ? t('common.auto') : t('common.off')}
          </Badge>
        }
      >
        <Segmented
          ariaLabel={t('nightvision.title')}
          value={props.value}
          options={(['OFF', 'AUTO', 'ON'] as NightVisionMode[]).map((mode) => ({ value: mode, label: t(MODE_KEYS[mode]) }))}
          onChange={(v) => changeMode(v as NightVisionMode)}
          disabled={readOnly()}
        />
        <div class="nx-field-hint">
          {props.value === 'ON'
            ? t('nightvision.descOn')
            : props.value === 'AUTO'
              ? t('nightvision.descAuto')
              : t('nightvision.descOff')}
        </div>
      </Panel>
    </SettingItem>
  )
}
