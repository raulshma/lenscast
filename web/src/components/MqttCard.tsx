import { Show, createSignal } from 'solid-js'
import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * MQTT alert forwarding: motion/sound/tamper events publish to the user's
 * broker with Home Assistant discovery entities. mqttPassword is write-only —
 * an empty value keeps the stored secret — so like BackupCard's secrets it is
 * bound to a local draft, not the server value: responses always carry a
 * blank password, and binding to them would wipe the input mid-typing on the
 * next settings refresh.
 */
export default function MqttCard(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const mqttOn = () => stream()?.mqttEnabled ?? API_DEFAULTS.mqttEnabled
  const mqttTlsOn = () => stream()?.mqttTls ?? API_DEFAULTS.mqttTls
  const [passwordDraft, setPasswordDraft] = createSignal('')

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="2" />
          <path d="M16.24 7.76a6 6 0 010 8.49" />
          <path d="M7.76 16.24a6 6 0 010-8.49" />
          <path d="M19.07 4.93a10 10 0 010 14.14" />
          <path d="M4.93 19.07a10 10 0 010-14.14" />
        </svg>
      }
      title="MQTT"
    >
      <div class="field-group">
        <ToggleRow
          id="mqtt-toggle"
          label="Enable MQTT"
          checked={mqttOn()}
          onToggle={() => props.updateStreamingAndSave({ mqttEnabled: !mqttOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>Publishes motion/sound/tamper alerts to an MQTT broker with Home Assistant discovery entities.</span>
        </div>
      </div>

      <Show when={mqttOn()}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Broker Host</span>
          </div>
          <input
            id="mqtt-broker-host"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            placeholder="e.g. 192.168.1.10 or broker.example.com"
            value={stream()?.mqttBrokerHost ?? API_DEFAULTS.mqttBrokerHost}
            onInput={(e) => props.updateStreamingDebounced({ mqttBrokerHost: e.currentTarget.value })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Broker Port</span>
          </div>
          <input
            id="mqtt-broker-port"
            type="number"
            class="field-input field-input-full"
            min={1}
            max={65535}
            value={stream()?.mqttBrokerPort ?? API_DEFAULTS.mqttBrokerPort}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value, 10)
              if (Number.isFinite(v)) props.updateStreamingDebounced({ mqttBrokerPort: v })
            }}
          />
        </div>

        <div class="field-group">
          <ToggleRow
            id="mqtt-tls-toggle"
            label="Use TLS (port 8883 typical)"
            checked={mqttTlsOn()}
            onToggle={() => props.updateStreamingAndSave({ mqttTls: !mqttTlsOn() })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Username</span>
          </div>
          <input
            id="mqtt-username"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            value={stream()?.mqttUsername ?? API_DEFAULTS.mqttUsername}
            onInput={(e) => props.updateStreamingDebounced({ mqttUsername: e.currentTarget.value })}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Password</span>
          </div>
          <input
            id="mqtt-password"
            type="password"
            class="field-input field-input-full"
            autocomplete="new-password"
            placeholder="(unchanged)"
            value={passwordDraft()}
            onInput={(e) => {
              setPasswordDraft(e.currentTarget.value)
              props.updateStreamingDebounced({ mqttPassword: e.currentTarget.value })
            }}
          />
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">HA Discovery Prefix</span>
          </div>
          <input
            id="mqtt-discovery-prefix"
            type="text"
            class="field-input field-input-full"
            autocomplete="off"
            placeholder="homeassistant"
            value={stream()?.mqttDiscoveryPrefix ?? API_DEFAULTS.mqttDiscoveryPrefix}
            onInput={(e) => props.updateStreamingDebounced({ mqttDiscoveryPrefix: e.currentTarget.value })}
          />
        </div>
      </Show>
    </SettingsCard>
  )
}
