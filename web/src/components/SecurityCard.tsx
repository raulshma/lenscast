import { createSignal, For, Show } from 'solid-js'
import type { AllSettings, MotionZone } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import { setSiren, setTorch } from '../api/client'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

const MINUTES_PER_DAY = 1440

function minutesToLabel(minutes: number): string {
  const clamped = Math.min(Math.max(minutes, 0), MINUTES_PER_DAY)
  const h = Math.floor(clamped / 60) % 24
  const m = clamped % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

/**
 * Security camera surface: motion detection (zones + sensitivity + schedule +
 * recording trigger), sound detection, webhook alerting, and the deterrence
 * actions (siren + torch). The zone editor mirrors the privacy-masking card's
 * normalized-rect model; zones are inclusive — motion inside them counts.
 */
export default function SecurityCard(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const [sirenOn, setSirenOn] = createSignal(false)
  const [sirenBusy, setSirenBusy] = createSignal(false)
  const [torchOn, setTorchOn] = createSignal(false)
  const [torchBusy, setTorchBusy] = createSignal(false)

  const zones = () => stream()?.motionZones ?? []
  const motionOn = () => stream()?.motionDetectionEnabled ?? API_DEFAULTS.motionDetectionEnabled

  function toggleSiren() {
    if (sirenBusy()) return
    setSirenBusy(true)
    const next = !sirenOn()
    setSiren(next)
      .then(() => setSirenOn(next))
      .catch(() => {})
      .finally(() => setSirenBusy(false))
  }

  function toggleTorch() {
    if (torchBusy()) return
    setTorchBusy(true)
    const next = !torchOn()
    setTorch(next)
      .then(() => setTorchOn(next))
      .catch(() => {})
      .finally(() => setTorchBusy(false))
  }

  function addZone() {
    const current = zones()
    const next: MotionZone = {
      id: crypto.randomUUID(),
      label: `Zone ${current.length + 1}`,
      enabled: true,
      x: 0.1 + 0.05 * (current.length % 4),
      y: 0.1 + 0.05 * (current.length % 3),
      width: 0.25,
      height: 0.25,
    }
    props.updateStreamingAndSave({ motionZones: [...current, next] })
  }

  function patchZone(id: string, patch: Partial<MotionZone>) {
    props.updateStreamingAndSave({
      motionZones: zones().map((z) => (z.id === id ? { ...z, ...patch } : z)),
    })
  }

  function removeZone(id: string) {
    props.updateStreamingAndSave({ motionZones: zones().filter((z) => z.id !== id) })
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        </svg>
      }
      title="Security & Detection"
    >
      {/* Motion */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Motion Detection</span>
          <label class="toggle-switch" for="motion-toggle">
            <input
              id="motion-toggle"
              type="checkbox"
              checked={motionOn()}
              onChange={() => props.updateStreamingAndSave({ motionDetectionEnabled: !motionOn() })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      <Show when={motionOn()}>
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Sensitivity</span>
            <span class="field-value">{stream()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}%</span>
          </div>
          <input
            id="motion-sensitivity-slider"
            type="range"
            class="custom-range"
            min={1}
            max={100}
            step={1}
            value={stream()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}
            onInput={(e) => props.updateStreamingDebounced({ motionSensitivityPercent: parseInt(e.currentTarget.value) })}
          />
        </div>

        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Record on Motion</span>
            <label class="toggle-switch" for="motion-rec-toggle">
              <input
                id="motion-rec-toggle"
                type="checkbox"
                checked={stream()?.motionRecordingEnabled ?? API_DEFAULTS.motionRecordingEnabled}
                onChange={() => props.updateStreamingAndSave({ motionRecordingEnabled: !(stream()?.motionRecordingEnabled ?? API_DEFAULTS.motionRecordingEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <div class="field-row">
            <span class="field-label">Post-roll</span>
            <span class="field-value">{stream()?.motionPostRollSeconds ?? API_DEFAULTS.motionPostRollSeconds}s</span>
          </div>
          <input
            id="motion-postroll-slider"
            type="range"
            class="custom-range"
            min={0}
            max={120}
            step={5}
            value={stream()?.motionPostRollSeconds ?? API_DEFAULTS.motionPostRollSeconds}
            onInput={(e) => props.updateStreamingDebounced({ motionPostRollSeconds: parseInt(e.currentTarget.value) })}
          />
        </div>

        {/* Arm schedule */}
        <div class="field-group">
          <div class="field-row field-row-toggle">
            <span class="field-label">Arm on Schedule</span>
            <label class="toggle-switch" for="motion-schedule-toggle">
              <input
                id="motion-schedule-toggle"
                type="checkbox"
                checked={stream()?.motionArmScheduleEnabled ?? API_DEFAULTS.motionArmScheduleEnabled}
                onChange={() => props.updateStreamingAndSave({ motionArmScheduleEnabled: !(stream()?.motionArmScheduleEnabled ?? API_DEFAULTS.motionArmScheduleEnabled) })}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <Show when={stream()?.motionArmScheduleEnabled ?? API_DEFAULTS.motionArmScheduleEnabled}>
            <div class="field-row">
              <span class="field-label">From</span>
              <span class="field-value">{minutesToLabel(stream()?.motionArmStartMinute ?? API_DEFAULTS.motionArmStartMinute)}</span>
            </div>
            <input
              id="motion-arm-start"
              type="range"
              class="custom-range"
              min={0}
              max={MINUTES_PER_DAY - 1}
              step={15}
              value={stream()?.motionArmStartMinute ?? API_DEFAULTS.motionArmStartMinute}
              onInput={(e) => props.updateStreamingDebounced({ motionArmStartMinute: parseInt(e.currentTarget.value) })}
            />
            <div class="field-row">
              <span class="field-label">Until</span>
              <span class="field-value">{minutesToLabel(stream()?.motionArmEndMinute ?? API_DEFAULTS.motionArmEndMinute)}</span>
            </div>
            <input
              id="motion-arm-end"
              type="range"
              class="custom-range"
              min={0}
              max={MINUTES_PER_DAY - 1}
              step={15}
              value={stream()?.motionArmEndMinute ?? API_DEFAULTS.motionArmEndMinute}
              onInput={(e) => props.updateStreamingDebounced({ motionArmEndMinute: parseInt(e.currentTarget.value) })}
            />
          </Show>
        </div>

        {/* Detection zones */}
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Detection Zones</span>
            <button type="button" class="action-btn action-btn-ghost" onClick={addZone}>
              <span>Add zone</span>
            </button>
          </div>
          <Show when={zones().length === 0}>
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>No zones — the whole frame is the detection area.</span>
            </div>
          </Show>
          <For each={zones()}>
            {(zone) => (
              <div class="client-row">
                <span class="client-id" title={zone.label}>
                  {zone.enabled ? '' : '· '}({zone.x.toFixed(2)}, {zone.y.toFixed(2)}) {zone.width.toFixed(2)}×{zone.height.toFixed(2)}
                </span>
                <div class="motion-zone-actions">
                  <label class="toggle-switch" title="Enable zone">
                    <input
                      type="checkbox"
                      checked={zone.enabled}
                      onChange={() => patchZone(zone.id, { enabled: !zone.enabled })}
                    />
                    <span class="toggle-slider" />
                  </label>
                  <button type="button" class="client-kick-btn" onClick={() => removeZone(zone.id)}>
                    Remove
                  </button>
                </div>
              </div>
            )}
          </For>
        </div>
      </Show>

      {/* Sound */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Sound Detection</span>
          <label class="toggle-switch" for="sound-toggle">
            <input
              id="sound-toggle"
              type="checkbox"
              checked={stream()?.soundDetectionEnabled ?? API_DEFAULTS.soundDetectionEnabled}
              onChange={() => props.updateStreamingAndSave({ soundDetectionEnabled: !(stream()?.soundDetectionEnabled ?? API_DEFAULTS.soundDetectionEnabled) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={stream()?.soundDetectionEnabled ?? API_DEFAULTS.soundDetectionEnabled}>
          <div class="field-row">
            <span class="field-label">Trigger Threshold</span>
            <span class="field-value">{stream()?.soundThresholdPercent ?? API_DEFAULTS.soundThresholdPercent}%</span>
          </div>
          <input
            id="sound-threshold-slider"
            type="range"
            class="custom-range"
            min={1}
            max={100}
            step={1}
            value={stream()?.soundThresholdPercent ?? API_DEFAULTS.soundThresholdPercent}
            onInput={(e) => props.updateStreamingDebounced({ soundThresholdPercent: parseInt(e.currentTarget.value) })}
          />
        </Show>
      </div>

      {/* Webhook alerts */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Webhook Alerts</span>
          <label class="toggle-switch" for="webhook-toggle">
            <input
              id="webhook-toggle"
              type="checkbox"
              checked={stream()?.webhookEnabled ?? API_DEFAULTS.webhookEnabled}
              onChange={() => props.updateStreamingAndSave({ webhookEnabled: !(stream()?.webhookEnabled ?? API_DEFAULTS.webhookEnabled) })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={stream()?.webhookEnabled ?? API_DEFAULTS.webhookEnabled}>
          <input
            id="webhook-url"
            type="url"
            class="field-input field-input-full"
            placeholder="https://ntfy.sh/your-topic or any JSON endpoint"
            value={stream()?.webhookUrl ?? ''}
            onInput={(e) => props.updateStreamingDebounced({ webhookUrl: e.currentTarget.value })}
          />
        </Show>
      </div>

      {/* Deterrence */}
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Deterrence</span>
        </div>
        <div class="deterrence-row">
          <button
            type="button"
            class={`action-btn ${sirenOn() ? 'action-btn-warning' : 'action-btn-success'}`}
            disabled={sirenBusy()}
            onClick={toggleSiren}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 5L6 9H2v6h4l5 4V5z" />
              <path d="M19.07 4.93a10 10 0 010 14.14" />
            </svg>
            <span>{sirenOn() ? 'Siren OFF' : 'Siren ON'}</span>
          </button>
          <button
            type="button"
            class={`action-btn ${torchOn() ? 'action-btn-warning' : 'action-btn-ghost'}`}
            disabled={torchBusy()}
            onClick={toggleTorch}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 18h6M10 22h4M12 2a7 7 0 00-4 12.7c.6.5 1 1.4 1 2.3h6c0-.9.4-1.8 1-2.3A7 7 0 0012 2z" />
            </svg>
            <span>{torchOn() ? 'Light OFF' : 'Light ON'}</span>
          </button>
        </div>
      </div>
    </SettingsCard>
  )
}
