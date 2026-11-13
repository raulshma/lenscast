import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../../../../../types'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { t } from '../../../../../lib/i18n'
import { streamToggle } from '../../../../../lib/streamToggle'
import { Panel, Field, FieldRow, ToggleField, TextInput } from '../../../ui'

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
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const mqttOn = () => stream()?.mqttEnabled ?? API_DEFAULTS.mqttEnabled
  const mqttTlsOn = () => stream()?.mqttTls ?? API_DEFAULTS.mqttTls
  // The plain telemetry toggle reads/saves through the one home for that
  // shape (lib/streamToggle) — the field named once.
  const mqttTelemetry = streamToggle(
    stream,
    'mqttTelemetryEnabled',
    (patch) => props.updateStreamingAndSave(patch),
  )
  const [passwordDraft, setPasswordDraft] = createSignal('')

  return (
    <Panel title={t('mqtt.title')}>
      <ToggleField
        label={t('mqtt.enable')}
        hint={t('mqtt.desc')}
        checked={mqttOn()}
        onChange={() => props.updateStreamingAndSave({ mqttEnabled: !mqttOn() })}
      />

      <Show when={mqttOn()}>
        <ToggleField
          label={t('mqtt.telemetry')}
          hint={t('mqtt.telemetryDesc')}
          checked={mqttTelemetry.checked()}
          onChange={mqttTelemetry.onToggle}
        />
        <Field label={t('mqtt.host')}>
          <TextInput
            mono
            type="text"
            placeholder={t('mqtt.hostPlaceholder')}
            value={stream()?.mqttBrokerHost ?? API_DEFAULTS.mqttBrokerHost}
            onInput={(v) => props.updateStreamingDebounced({ mqttBrokerHost: v })}
            ariaLabel={t('mqtt.host')}
          />
        </Field>

        <FieldRow label={t('mqtt.port')}>
          <TextInput
            class="nx-input-num"
            type="number"
            small
            mono
            value={String(stream()?.mqttBrokerPort ?? API_DEFAULTS.mqttBrokerPort)}
            onInput={(raw) => {
              const v = clampPort(raw)
              if (v !== null) props.updateStreamingDebounced({ mqttBrokerPort: v })
            }}
            ariaLabel={t('mqtt.port')}
          />
        </FieldRow>

        <ToggleField
          label={t('mqtt.tls')}
          checked={mqttTlsOn()}
          onChange={() => props.updateStreamingAndSave({ mqttTls: !mqttTlsOn() })}
        />

        <Field label={t('common.username')}>
          <TextInput
            type="text"
            value={stream()?.mqttUsername ?? API_DEFAULTS.mqttUsername}
            onInput={(v) => props.updateStreamingDebounced({ mqttUsername: v })}
            ariaLabel={t('common.username')}
          />
        </Field>

        <Field label={t('common.password')}>
          <TextInput
            type="password"
            placeholder={t('common.unchanged')}
            value={passwordDraft()}
            onInput={(v) => {
              setPasswordDraft(v)
              props.updateStreamingDebounced({ mqttPassword: v })
            }}
            ariaLabel={t('common.password')}
          />
        </Field>

        <Field label={t('mqtt.prefix')}>
          <TextInput
            mono
            type="text"
            placeholder={t('mqtt.prefixPlaceholder')}
            value={stream()?.mqttDiscoveryPrefix ?? API_DEFAULTS.mqttDiscoveryPrefix}
            onInput={(v) => props.updateStreamingDebounced({ mqttDiscoveryPrefix: v })}
            ariaLabel={t('mqtt.prefix')}
          />
        </Field>
      </Show>
    </Panel>
  )
}
