import { Show } from 'solid-js'
import type { NextState } from '../../../NextState'
import { t } from '../../../../../lib/i18n'
import { maskEditActive, startMaskEdit } from '../../../../../lib/maskEditStore'
import { Button, FieldRow, Led, Panel } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'
import { webStreamActive } from './webStreamActive'

export interface MaskingQuickPanelProps {
  /** The app's shared state: the zones and their master switch, and whether the web stream is on air. */
  state: NextState
}

/**
 * Privacy masking at a glance, in the normal Live inspector: is the rig
 * masking anything right now, how many zones there are, and the way into the
 * visual editor — "Edit zones" arms it (maskEditStore) and the Live view
 * answers by opening it over the stage.
 *
 * The LED is the truth the picture shows, not the setting: lit only when the
 * master switch is on AND at least one zone is enabled. A disabled master
 * with five zones masks nothing, and the light says so.
 *
 * Zones are drawn on the live web stream, so without it the button stands
 * down and says why. And while the editor is open this panel is not on
 * screen at all: its button would only re-arm an edit that is already
 * running, and the stage has the inspector.
 */
export default function MaskingQuickPanel(props: MaskingQuickPanelProps) {
  const inspector = useInspectorSection()
  const readOnly = () => props.state.isViewer?.() === true || inspector?.readOnly() === true
  const streaming = () => props.state.settings()?.streaming
  const zones = () => streaming()?.maskingZones ?? []
  const masking = () => streaming()?.maskingEnabled === true && zones().some((zone) => zone.enabled)
  const streamOn = () => webStreamActive(props.state)

  return (
    <Show when={!maskEditActive()}>
      <Panel
        title={t('privacy.title')}
        badge={<SettingItem id="masking.enabled"><Led tone={masking() ? 'ok' : 'off'} /></SettingItem>}
      >
        <SettingItem id="masking.edit">
          <FieldRow
            label={t('privacy.zones', { count: zones().length })}
            hint={streamOn() ? undefined : t('privacy.streamOffHint')}
          >
            <Button
              size="sm"
              variant="primary"
              disabled={!streamOn() || readOnly()}
              tooltip={streamOn() ? t('privacy.editZoneTitle') : t('privacy.streamOffHint')}
              ariaLabel={t('privacy.editZoneTitle')}
              onClick={() => { if (!readOnly()) startMaskEdit() }}
            >
              {t('privacy.editZones')}
            </Button>
          </FieldRow>
        </SettingItem>
      </Panel>
    </Show>
  )
}
