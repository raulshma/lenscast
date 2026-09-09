import { createSignal, For, Show } from 'solid-js'
import type { AllSettings, MotionZone } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
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
 * The webhook headers field is free text, but only a JSON object of
 * string→string reaches the wire as headers — anything else degrades to no
 * custom headers server-side. Returns the warning shown while the text does
 * not parse, so the silent drop never comes as a surprise.
 */
function webhookHeadersWarning(value: string | undefined): string {
  const text = value?.trim() ?? ''
  if (!text) return ''
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return 'Not a JSON object — these headers will not be sent'
    }
    const invalid = Object.values(parsed as Record<string, unknown>).some((v) => typeof v !== 'string')
    return invalid ? 'Header values must be strings — non-string values will not be sent' : ''
  } catch {
    return 'Invalid JSON — these headers will not be sent'
  }
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
  // One reader per toggled setting: the row's `checked`, its flip, and any
  // dependent <Show> all read the same server-or-default value.
  const motionOn = () => stream()?.motionDetectionEnabled ?? API_DEFAULTS.motionDetectionEnabled
  const motionRecordingOn = () => stream()?.motionRecordingEnabled ?? API_DEFAULTS.motionRecordingEnabled
  const armScheduleOn = () => stream()?.motionArmScheduleEnabled ?? API_DEFAULTS.motionArmScheduleEnabled
  const soundOn = () => stream()?.soundDetectionEnabled ?? API_DEFAULTS.soundDetectionEnabled
  const localAlertsOn = () => stream()?.detectionNotificationsEnabled ?? API_DEFAULTS.detectionNotificationsEnabled
  const tamperOn = () => stream()?.tamperDetectionEnabled ?? API_DEFAULTS.tamperDetectionEnabled
  const webhookOn = () => stream()?.webhookEnabled ?? API_DEFAULTS.webhookEnabled
  const autoSirenOn = () => stream()?.autoSiren ?? API_DEFAULTS.autoSiren
  const autoTorchOn = () => stream()?.autoTorch ?? API_DEFAULTS.autoTorch
  const mlOn = () => stream()?.mlDetectionEnabled ?? API_DEFAULTS.mlDetectionEnabled
  const continuousOn = () => stream()?.continuousRecording ?? API_DEFAULTS.continuousRecording

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
        <ToggleRow
          id="motion-toggle"
          label="Motion Detection"
          checked={motionOn()}
          onToggle={() => props.updateStreamingAndSave({ motionDetectionEnabled: !motionOn() })}
        />
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
          <ToggleRow
            id="motion-rec-toggle"
            label="Record on Motion"
            checked={motionRecordingOn()}
            onToggle={() => props.updateStreamingAndSave({ motionRecordingEnabled: !motionRecordingOn() })}
          />
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
          <ToggleRow
            id="motion-schedule-toggle"
            label="Arm on Schedule"
            checked={armScheduleOn()}
            onToggle={() => props.updateStreamingAndSave({ motionArmScheduleEnabled: !armScheduleOn() })}
          />
          <Show when={armScheduleOn()}>
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
        <ToggleRow
          id="sound-toggle"
          label="Sound Detection"
          checked={soundOn()}
          onToggle={() => props.updateStreamingAndSave({ soundDetectionEnabled: !soundOn() })}
        />
        <Show when={soundOn()}>
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

      {/* Object detection (ML) */}
      <div class="field-group">
        <ToggleRow
          id="ml-detection-toggle"
          label="Object Detection (ML)"
          checked={mlOn()}
          onToggle={() => props.updateStreamingAndSave({ mlDetectionEnabled: !mlOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>Verifies motion with an on-device model (person, pet, vehicle); events without a matching object are suppressed.</span>
        </div>
        <div class="field-row">
          <span class="field-label">Minimum Confidence</span>
          <span class="field-value">{stream()?.mlMinScorePercent ?? API_DEFAULTS.mlMinScorePercent}%</span>
        </div>
        <input
          id="ml-min-score-slider"
          type="range"
          class="custom-range"
          min={API_DEFAULTS.mlMinScoreMinPercent}
          max={API_DEFAULTS.mlMinScoreMaxPercent}
          step={5}
          disabled={!mlOn()}
          value={stream()?.mlMinScorePercent ?? API_DEFAULTS.mlMinScorePercent}
          onInput={(e) => props.updateStreamingDebounced({ mlMinScorePercent: parseInt(e.currentTarget.value) })}
        />
      </div>

      {/* Continuous recording */}
      <div class="field-group">
        <ToggleRow
          id="continuous-rec-toggle"
          label="Continuous Recording"
          checked={continuousOn()}
          onToggle={() => props.updateStreamingAndSave({ continuousRecording: !continuousOn() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>Records chained segments while the camera is idle; oldest segments age out with the capture retention window.</span>
        </div>
        <div class="field-row">
          <span class="field-label">Segment Length</span>
          <span class="field-value">{stream()?.continuousSegmentMinutes ?? API_DEFAULTS.continuousSegmentMinutes} min</span>
        </div>
        <input
          id="continuous-segment-slider"
          type="range"
          class="custom-range"
          min={API_DEFAULTS.continuousSegmentMinMinutes}
          max={API_DEFAULTS.continuousSegmentMaxMinutes}
          step={5}
          disabled={!continuousOn()}
          value={stream()?.continuousSegmentMinutes ?? API_DEFAULTS.continuousSegmentMinutes}
          onInput={(e) => props.updateStreamingDebounced({ continuousSegmentMinutes: parseInt(e.currentTarget.value) })}
        />
      </div>

      {/* On-device alerts and tamper */}
      <div class="field-group">
        <ToggleRow
          id="local-alerts-toggle"
          label="Local Alerts"
          checked={localAlertsOn()}
          onToggle={() => props.updateStreamingAndSave({ detectionNotificationsEnabled: !localAlertsOn() })}
        />
        <ToggleRow
          id="tamper-toggle"
          label="Tamper Detection"
          checked={tamperOn()}
          onToggle={() => props.updateStreamingAndSave({ tamperDetectionEnabled: !tamperOn() })}
        />
      </div>

      {/* Webhook alerts */}
      <div class="field-group">
        <ToggleRow
          id="webhook-toggle"
          label="Webhook Alerts"
          checked={webhookOn()}
          onToggle={() => props.updateStreamingAndSave({ webhookEnabled: !webhookOn() })}
        />
        <Show when={webhookOn()}>
          <input
            id="webhook-url"
            type="url"
            class="field-input field-input-full"
            placeholder="https://ntfy.sh/your-topic or any JSON endpoint"
            value={stream()?.webhookUrl ?? ''}
            onInput={(e) => props.updateStreamingDebounced({ webhookUrl: e.currentTarget.value })}
          />
          <input
            id="webhook-headers"
            type="text"
            class="field-input field-input-full"
            placeholder='Custom headers as JSON, e.g. {"Authorization": "Bearer token"}'
            value={stream()?.webhookHeaders ?? API_DEFAULTS.webhookHeaders}
            onInput={(e) => props.updateStreamingDebounced({ webhookHeaders: e.currentTarget.value })}
          />
          <Show when={webhookHeadersWarning(stream()?.webhookHeaders)}>
            {(warning) => (
              <span class="clients-cap-row" role="alert">
                {warning()}
              </span>
            )}
          </Show>
        </Show>
      </div>

      {/* Deterrence automation */}
      <div class="field-group">
        <ToggleRow
          id="auto-siren-toggle"
          label="Auto-Siren on Detection"
          checked={autoSirenOn()}
          onToggle={() => props.updateStreamingAndSave({ autoSiren: !autoSirenOn() })}
        />
        <Show when={autoSirenOn()}>
          <div class="field-row">
            <span class="field-label">Siren Duration</span>
            <span class="field-value">{stream()?.sirenDurationSeconds ?? API_DEFAULTS.sirenDurationSeconds}s</span>
          </div>
          <input
            id="siren-duration-slider"
            type="range"
            class="custom-range"
            min={API_DEFAULTS.sirenDurationMinSeconds}
            max={API_DEFAULTS.sirenDurationMaxSeconds}
            step={1}
            value={stream()?.sirenDurationSeconds ?? API_DEFAULTS.sirenDurationSeconds}
            onInput={(e) => props.updateStreamingDebounced({ sirenDurationSeconds: parseInt(e.currentTarget.value) })}
          />
        </Show>
        <ToggleRow
          id="auto-torch-toggle"
          label="Auto-Light on Detection"
          checked={autoTorchOn()}
          onToggle={() => props.updateStreamingAndSave({ autoTorch: !autoTorchOn() })}
        />
        <div class="field-row">
          <span class="field-label">Re-trigger Cooldown</span>
          <span class="field-value">{stream()?.autoDeterrenceCooldownSeconds ?? API_DEFAULTS.autoDeterrenceCooldownSeconds}s</span>
        </div>
        <input
          id="deterrence-cooldown-slider"
          type="range"
          class="custom-range"
          min={API_DEFAULTS.deterrenceCooldownMinSeconds}
          max={API_DEFAULTS.deterrenceCooldownMaxSeconds}
          step={5}
          value={stream()?.autoDeterrenceCooldownSeconds ?? API_DEFAULTS.autoDeterrenceCooldownSeconds}
          onInput={(e) => props.updateStreamingDebounced({ autoDeterrenceCooldownSeconds: parseInt(e.currentTarget.value) })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>When armed detection fires, the siren and light trigger automatically — at most once per cooldown window.</span>
        </div>
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
