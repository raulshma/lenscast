import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { streamToggle } from '../../../../../lib/streamToggle'
import { Panel, Field, FieldRow, ToggleField, TextInput } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/** The broker port's valid range — min/max stop the spinners but not free-typed text, so clamp explicitly. */
function clampPort(raw: string): number | null {
  const v = parseInt(raw, 10)
  if (!Number.isFinite(v)) return null
  return Math.min(65535, Math.max(1, v))
}

/**
 * MQTT alert forwarding, ported from the classic MqttCard onto the kit:
 * motion/sound/tamper events publish to the user's broker with Home
 * Assistant discovery entities. mqttPassword is write-only — an empty value
 * keeps the stored secret — so like BackupCard's secrets it is bound to a
 * local draft, not the server value: responses always carry a blank
 * password, and binding to them would wipe the input mid-typing on the next
 * settings refresh.
 */
export default function MqttPanel(props: Props) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const save = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingAndSave(patch)
  }
  const saveDebounced = (patch: Partial<AllSettings['streaming']>) => {
    if (!readOnly()) props.updateStreamingDebounced(patch)
  }
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const mqttOn = () => stream()?.mqttEnabled ?? API_DEFAULTS.mqttEnabled
  const mqttTlsOn = () => stream()?.mqttTls ?? API_DEFAULTS.mqttTls
  // The plain telemetry toggle reads/saves through the one home for that
  // shape (lib/streamToggle) — the field named once.
  const mqttTelemetry = streamToggle(
    stream,
    'mqttTelemetryEnabled',
    (patch) => save(patch),
  )
  const [passwordDraft, setPasswordDraft] = createSignal('')

  return (
    <Panel title={t('mqtt.title')}>
      <SettingItem id="mqtt.enabled">
      <ToggleField
        disabled={readOnly()}
        label={t('mqtt.enable')}
        hint={t('mqtt.desc')}
        checked={mqttOn()}
        onChange={() => save({ mqttEnabled: !mqttOn() })}
      />
      </SettingItem>

      <SettingItem id="mqtt.connection">
      <Show when={mqttOn()}>
        <ToggleField
          disabled={readOnly()}
          label={t('mqtt.telemetry')}
          hint={t('mqtt.telemetryDesc')}
          checked={mqttTelemetry.checked()}
          onChange={mqttTelemetry.onToggle}
        />
        <Field label={t('mqtt.host')}>
          <TextInput
            disabled={readOnly()}
            mono
            type="text"
            placeholder={t('mqtt.hostPlaceholder')}
            value={stream()?.mqttBrokerHost ?? API_DEFAULTS.mqttBrokerHost}
            onInput={(v) => saveDebounced({ mqttBrokerHost: v })}
            ariaLabel={t('mqtt.host')}
          />
        </Field>

        <FieldRow label={t('mqtt.port')}>
          <TextInput
            disabled={readOnly()}
            class="nx-input-num"
            type="number"
            small
            mono
            value={String(stream()?.mqttBrokerPort ?? API_DEFAULTS.mqttBrokerPort)}
            onInput={(raw) => {
              const v = clampPort(raw)
              if (v !== null) saveDebounced({ mqttBrokerPort: v })
            }}
            ariaLabel={t('mqtt.port')}
          />
        </FieldRow>

        <ToggleField
          disabled={readOnly()}
          label={t('mqtt.tls')}
          checked={mqttTlsOn()}
          onChange={() => save({ mqttTls: !mqttTlsOn() })}
        />

        <Field label={t('common.username')}>
          <TextInput
            disabled={readOnly()}
            type="text"
            value={stream()?.mqttUsername ?? API_DEFAULTS.mqttUsername}
            onInput={(v) => saveDebounced({ mqttUsername: v })}
            ariaLabel={t('common.username')}
          />
        </Field>

        <Field label={t('common.password')}>
          <TextInput
            disabled={readOnly()}
            type="password"
            placeholder={t('common.unchanged')}
            value={passwordDraft()}
            onInput={(v) => {
              setPasswordDraft(v)
              saveDebounced({ mqttPassword: v })
            }}
            ariaLabel={t('common.password')}
          />
        </Field>

        <Field label={t('mqtt.prefix')}>
          <TextInput
            disabled={readOnly()}
            mono
            type="text"
            placeholder={t('mqtt.prefixPlaceholder')}
            value={stream()?.mqttDiscoveryPrefix ?? API_DEFAULTS.mqttDiscoveryPrefix}
            onInput={(v) => saveDebounced({ mqttDiscoveryPrefix: v })}
            ariaLabel={t('mqtt.prefix')}
          />
        </Field>
      </Show>
      </SettingItem>
    </Panel>
  )
}
