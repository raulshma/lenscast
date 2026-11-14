import { createSignal, createEffect, For, Show } from 'solid-js'
import type { AllSettings, DeviceStatus, MotionZone } from '../../../../types'
import { API_DEFAULTS } from '../../../../api/defaults'
import { isDayArmed, toggleArmDayMask } from '../../../../armDays'
import { downloadDetectionModel, sendTestAlert, setSiren, setTorch } from '../../../../api/client'
import { automationDayLabelKey, dayListLabel, isEveryDayMask } from '../../../../lib/automation'
import { t } from '../../../../lib/i18n'
import { Panel, ToggleField, SliderField, FieldRow, TextInput, Button, Empty } from '../../ui'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
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
      return t('security.headersNotObject')
    }
    const invalid = Object.values(parsed as Record<string, unknown>).some((v) => typeof v !== 'string')
    return invalid ? t('security.headersNotStrings') : ''
  } catch {
    return t('security.headersInvalid')
  }
}

/**
 * Security arming, ported from the classic SecurityCard onto the kit with
 * every binding unchanged: motion (zones + sensitivity + schedule + recording
 * trigger), sound, ML object detection, continuous recording, alerts and
 * tamper, webhook alerting, and the deterrence actions (siren + torch).
 */
export default function SecurityPanel(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming
  const [sirenOn, setSirenOn] = createSignal(false)
  const [sirenBusy, setSirenBusy] = createSignal(false)
  const [torchOn, setTorchOn] = createSignal(false)
  const [torchBusy, setTorchBusy] = createSignal(false)

  // Deterrence truth lives on the device: auto-deterrence (or another tab,
  // or the phone itself) can flip siren/torch without this card's involvement,
  // so every status snapshot re-syncs both mirrors unconditionally.
  createEffect(() => {
    const device = props.status()
    if (device) {
      setSirenOn(device.sirenActive)
      setTorchOn(device.torchOn)
    }
  })

  // The one busy-guard choreography every action button here shares: a
  // re-entrancy guard, the call, and a finally that always releases.
  function runGuarded(busy: () => boolean, setBusy: (v: boolean) => void, action: () => Promise<unknown>) {
    if (busy()) return
    setBusy(true)
    action()
      .catch(() => {})
      .finally(() => setBusy(false))
  }

  const zones = () => stream()?.motionZones ?? []
  // One reader per toggled setting: the row's `checked`, its flip, and any
  // dependent <Show> all read the same server-or-default value.
  const motionOn = () => stream()?.motionDetectionEnabled ?? API_DEFAULTS.motionDetectionEnabled
  const motionRecordingOn = () => stream()?.motionRecordingEnabled ?? API_DEFAULTS.motionRecordingEnabled
  const armScheduleOn = () => stream()?.motionArmScheduleEnabled ?? API_DEFAULTS.motionArmScheduleEnabled
  const soundOn = () => stream()?.soundDetectionEnabled ?? API_DEFAULTS.soundDetectionEnabled
  const localAlertsOn = () => stream()?.detectionNotificationsEnabled ?? API_DEFAULTS.detectionNotificationsEnabled
  const quietHoursOn = () => stream()?.alertQuietHoursEnabled ?? API_DEFAULTS.alertQuietHoursEnabled
  // Day-of-week arm mask helpers: bit 0 = Monday … bit 6 = Sunday.
  const armDaysMask = () => stream()?.motionArmDaysMask ?? API_DEFAULTS.motionArmDaysMask
  const armStartMinute = () => stream()?.motionArmStartMinute ?? API_DEFAULTS.motionArmStartMinute
  const armEndMinute = () => stream()?.motionArmEndMinute ?? API_DEFAULTS.motionArmEndMinute
  const armedDayCount = () => [0, 1, 2, 3, 4, 5, 6].filter((day) => isDayArmed(armDaysMask(), day)).length

  function toggleArmDay(isoDayIndex: number) {
    const next = toggleArmDayMask(armDaysMask(), isoDayIndex)
    if (next !== armDaysMask()) props.updateStreamingAndSave({ motionArmDaysMask: next })
  }
  const tamperOn = () => stream()?.tamperDetectionEnabled ?? API_DEFAULTS.tamperDetectionEnabled
  const webhookOn = () => stream()?.webhookEnabled ?? API_DEFAULTS.webhookEnabled
  const autoSirenOn = () => stream()?.autoSiren ?? API_DEFAULTS.autoSiren
  const autoTorchOn = () => stream()?.autoTorch ?? API_DEFAULTS.autoTorch
  const mlOn = () => stream()?.mlDetectionEnabled ?? API_DEFAULTS.mlDetectionEnabled
  const mlPersonOn = () => stream()?.mlIncludePerson ?? API_DEFAULTS.mlIncludePerson
  const mlPetsOn = () => stream()?.mlIncludePets ?? API_DEFAULTS.mlIncludePets
  const mlVehiclesOn = () => stream()?.mlIncludeVehicles ?? API_DEFAULTS.mlIncludeVehicles
  const soundRecordingOn = () => stream()?.soundRecordingEnabled ?? API_DEFAULTS.soundRecordingEnabled
  const continuousOn = () => stream()?.continuousRecording ?? API_DEFAULTS.continuousRecording
  // The detection model ships outside the APK; these read the server's
  // response-only model facts and the download button drives the POST route.
  const mlModelState = () => stream()?.mlModelState ?? API_DEFAULTS.mlModelState
  const mlModelProgress = () => stream()?.mlModelProgress ?? API_DEFAULTS.mlModelProgress
  const mlModelError = () => stream()?.mlModelError ?? API_DEFAULTS.mlModelError
  const [modelBusy, setModelBusy] = createSignal(false)
  const [testAlertBusy, setTestAlertBusy] = createSignal(false)
  const [testAlertResult, setTestAlertResult] = createSignal('')

  function sendTestAlertNow() {
    if (testAlertBusy()) return
    setTestAlertBusy(true)
    setTestAlertResult('')
    sendTestAlert()
      .then((result) => {
        setTestAlertResult(
          result.success
            ? result.dispatchedActions.length > 0
              ? t('security.testAlertVia', { channels: result.dispatchedActions.join(', ') })
              : t('security.testAlertNoChannel')
            : t('security.testAlertUnavailable'),
        )
      })
      .catch(() => setTestAlertResult(t('security.testAlertFailed')))
      .finally(() => setTestAlertBusy(false))
  }

  function downloadModel() {
    runGuarded(modelBusy, setModelBusy, () => downloadDetectionModel())
  }

  /**
   * One derived view of the model state: the status text, the download
   * button's label/availability, and whether the failure reason row shows all
   * discriminate the state once, here, so they can never disagree.
   */
  function mlModelView(): {
    status: string
    button: { label: string; disabled: boolean } | null
    showError: boolean
  } {
    if (mlModelState() === 'ready') return { status: t('security.modelReady'), button: null, showError: false }
    if (mlModelState() === 'downloading' || modelBusy()) {
      const p = mlModelProgress()
      return {
        status: p >= 0 ? t('security.modelDownloadingPct', { pct: Math.round(p * 100) }) : t('security.modelDownloading'),
        button: { label: t('security.modelDownloading'), disabled: true },
        showError: mlModelState() === 'failed',
      }
    }
    return mlModelState() === 'failed'
      ? { status: t('security.modelFailed'), button: { label: t('security.modelRetry'), disabled: false }, showError: true }
      : { status: t('security.modelNotDownloaded', { size: API_DEFAULTS.mlModelSizeLabel }), button: { label: t('security.modelDownload'), disabled: false }, showError: false }
  }

  function toggleSiren() {
    const next = !sirenOn()
    runGuarded(sirenBusy, setSirenBusy, () => setSiren(next).then(() => setSirenOn(next)))
  }

  function toggleTorch() {
    const next = !torchOn()
    runGuarded(torchBusy, setTorchBusy, () => setTorch(next).then(() => setTorchOn(next)))
  }

  function addZone() {
    const current = zones()
    const next: MotionZone = {
      id: crypto.randomUUID(),
      label: t('security.zone', { n: current.length + 1 }),
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
    <Panel title={t('security.title')}>
      {/* Motion */}
      <ToggleField
        label={t('security.motion')}
        checked={motionOn()}
        onChange={() => props.updateStreamingAndSave({ motionDetectionEnabled: !motionOn() })}
      />

      <Show when={motionOn()}>
        <SliderField
          label={t('security.sensitivity')}
          display={`${stream()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}%`}
          min={1}
          max={100}
          step={1}
          value={stream()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}
          onChange={(v) => props.updateStreamingDebounced({ motionSensitivityPercent: v })}
        />
        <SliderField
          label={t('security.cooldown')}
          display={`${stream()?.motionCooldownSeconds ?? API_DEFAULTS.motionCooldownSeconds}s`}
          min={API_DEFAULTS.detectionCooldownMinSeconds}
          max={API_DEFAULTS.detectionCooldownMaxSeconds}
          step={1}
          value={stream()?.motionCooldownSeconds ?? API_DEFAULTS.motionCooldownSeconds}
          onChange={(v) => props.updateStreamingDebounced({ motionCooldownSeconds: v })}
        />

        <ToggleField
          label={t('security.recordOnMotion')}
          checked={motionRecordingOn()}
          onChange={() => props.updateStreamingAndSave({ motionRecordingEnabled: !motionRecordingOn() })}
        />
        <Show when={motionRecordingOn()}>
          <SliderField
            label={t('security.postRoll')}
            display={`${stream()?.motionPostRollSeconds ?? API_DEFAULTS.motionPostRollSeconds}s`}
            min={0}
            max={120}
            step={5}
            value={stream()?.motionPostRollSeconds ?? API_DEFAULTS.motionPostRollSeconds}
            onChange={(v) => props.updateStreamingDebounced({ motionPostRollSeconds: v })}
          />
        </Show>

        {/* Arm schedule */}
        <ToggleField
          label={t('security.armSchedule')}
          checked={armScheduleOn()}
          onChange={() => props.updateStreamingAndSave({ motionArmScheduleEnabled: !armScheduleOn() })}
        />
        <Show when={armScheduleOn()}>
          <SliderField
            label={t('security.from')}
            display={minutesToLabel(stream()?.motionArmStartMinute ?? API_DEFAULTS.motionArmStartMinute)}
            min={0}
            max={MINUTES_PER_DAY - 1}
            step={15}
            value={stream()?.motionArmStartMinute ?? API_DEFAULTS.motionArmStartMinute}
            onChange={(v) => props.updateStreamingDebounced({ motionArmStartMinute: v })}
          />
          <SliderField
            label={t('security.until')}
            display={minutesToLabel(stream()?.motionArmEndMinute ?? API_DEFAULTS.motionArmEndMinute)}
            min={0}
            max={MINUTES_PER_DAY - 1}
            step={15}
            value={stream()?.motionArmEndMinute ?? API_DEFAULTS.motionArmEndMinute}
            onChange={(v) => props.updateStreamingDebounced({ motionArmEndMinute: v })}
          />
          <div class="nx-arm-days" role="group" aria-label={t('security.armDaysAria')}>
            <For each={[0, 1, 2, 3, 4, 5, 6]}>
              {(day) => (
                <button
                  type="button"
                  class="nx-daychip"
                  classList={{ 'nx-daychip-active': isDayArmed(armDaysMask(), day) }}
                  onClick={() => toggleArmDay(day)}
                >
                  {t(automationDayLabelKey(day))}
                </button>
              )}
            </For>
          </div>
          {/* The schedule restated as a plain sentence — the toggles stay
              compact, this line carries the reading: days, then the window.
              Equal bounds arm the whole day (isArmed's never-restricts
              verdict), so the interval reads as the all-day label instead
              of a nonsensical "14:00–14:00". */}
          <div class="nx-arm-summary">
            <span>
              {isEveryDayMask(armDaysMask())
                ? t('automation.daysEveryDay')
                : dayListLabel(armDaysMask(), t)}
            </span>
            <span class="nx-arm-summary-time nx-mono">
              {armStartMinute() === armEndMinute()
                ? t('security.allDay')
                : `${minutesToLabel(armStartMinute())}–${minutesToLabel(armEndMinute())}`}
            </span>
          </div>
        </Show>

        {/* Detection zones */}
        <FieldRow label={t('security.zones')}>
          <Button size="sm" variant="ghost" onClick={addZone} ariaLabel={t('security.addZone')}>
            {t('security.addZone')}
          </Button>
        </FieldRow>
        <Show when={zones().length === 0}>
          <Empty title={t('security.noZones')} />
        </Show>
        <For each={zones()}>
          {(zone) => (
            <div class="nx-zone-row">
              <span class="nx-mono nx-zone-rect" title={zone.label}>
                ({zone.x.toFixed(2)}, {zone.y.toFixed(2)}) {zone.width.toFixed(2)}×{zone.height.toFixed(2)}
              </span>
              <div class="nx-zone-actions">
                <label class="nx-switch" title={t('security.enableZone')}>
                  <input
                    type="checkbox"
                    checked={zone.enabled}
                    aria-label={t('security.enableZone')}
                    onChange={() => patchZone(zone.id, { enabled: !zone.enabled })}
                  />
                  <span class="nx-switch-track" aria-hidden="true" />
                  <span class="nx-switch-thumb" aria-hidden="true" />
                </label>
                <Button size="sm" variant="danger-soft" onClick={() => removeZone(zone.id)} ariaLabel={t('security.remove')}>
                  {t('security.remove')}
                </Button>
              </div>
            </div>
          )}
        </For>
      </Show>

      {/* Sound */}
      <ToggleField
        label={t('security.sound')}
        checked={soundOn()}
        onChange={() => props.updateStreamingAndSave({ soundDetectionEnabled: !soundOn() })}
      />
      <Show when={soundOn()}>
        <SliderField
          label={t('security.threshold')}
          display={`${stream()?.soundThresholdPercent ?? API_DEFAULTS.soundThresholdPercent}%`}
          min={1}
          max={100}
          step={1}
          value={stream()?.soundThresholdPercent ?? API_DEFAULTS.soundThresholdPercent}
          onChange={(v) => props.updateStreamingDebounced({ soundThresholdPercent: v })}
        />
        <ToggleField
          label={t('security.adaptiveFloor')}
          hint={stream()?.soundAdaptiveNoiseFloor ? t('security.adaptiveFloorDesc') : undefined}
          checked={stream()?.soundAdaptiveNoiseFloor ?? API_DEFAULTS.soundAdaptiveNoiseFloor}
          onChange={() =>
            props.updateStreamingAndSave({
              soundAdaptiveNoiseFloor: !(stream()?.soundAdaptiveNoiseFloor ?? API_DEFAULTS.soundAdaptiveNoiseFloor),
            })
          }
        />
        <SliderField
          label={t('security.cooldown')}
          display={`${stream()?.soundCooldownSeconds ?? API_DEFAULTS.soundCooldownSeconds}s`}
          min={API_DEFAULTS.detectionCooldownMinSeconds}
          max={API_DEFAULTS.detectionCooldownMaxSeconds}
          step={1}
          value={stream()?.soundCooldownSeconds ?? API_DEFAULTS.soundCooldownSeconds}
          onChange={(v) => props.updateStreamingDebounced({ soundCooldownSeconds: v })}
        />
        <ToggleField
          label={t('security.recordOnSound')}
          hint={soundRecordingOn() ? t('security.recordOnSoundDesc') : undefined}
          checked={soundRecordingOn()}
          onChange={() => props.updateStreamingAndSave({ soundRecordingEnabled: !soundRecordingOn() })}
        />
      </Show>

      {/* Object detection (ML) */}
      <ToggleField
        label={t('security.ml')}
        hint={t('security.mlDesc')}
        checked={mlOn()}
        onChange={() => props.updateStreamingAndSave({ mlDetectionEnabled: !mlOn() })}
      />
      <SliderField
        label={t('security.minConfidence')}
        display={`${stream()?.mlMinScorePercent ?? API_DEFAULTS.mlMinScorePercent}%`}
        min={API_DEFAULTS.mlMinScoreMinPercent}
        max={API_DEFAULTS.mlMinScoreMaxPercent}
        step={5}
        disabled={!mlOn()}
        value={stream()?.mlMinScorePercent ?? API_DEFAULTS.mlMinScorePercent}
        onChange={(v) => props.updateStreamingDebounced({ mlMinScorePercent: v })}
      />
      <span class="nx-label">{t('security.alertClasses')}</span>
      <ToggleField
        label={t('security.people')}
        checked={mlPersonOn()}
        disabled={!mlOn()}
        onChange={() => props.updateStreamingAndSave({ mlIncludePerson: !mlPersonOn() })}
      />
      <ToggleField
        label={t('security.animals')}
        checked={mlPetsOn()}
        disabled={!mlOn()}
        onChange={() => props.updateStreamingAndSave({ mlIncludePets: !mlPetsOn() })}
      />
      <ToggleField
        label={t('security.vehicles')}
        checked={mlVehiclesOn()}
        disabled={!mlOn()}
        onChange={() => props.updateStreamingAndSave({ mlIncludeVehicles: !mlVehiclesOn() })}
      />
      <FieldRow label={t('security.model')}>
        <span class="nx-mono nx-info-value">{mlModelView().status}</span>
      </FieldRow>
      <Show when={mlModelView().showError}>
        <div class="nx-banner nx-banner-danger" role="alert">{mlModelError()}</div>
      </Show>
      <Show when={mlModelView().button}>
        {(button) => (
          <Button
            size="sm"
            variant="ghost"
            disabled={button().disabled}
            onClick={downloadModel}
            ariaLabel={button().label}
          >
            {button().label}
          </Button>
        )}
      </Show>

      {/* Continuous recording */}
      <ToggleField
        label={t('security.continuous')}
        hint={t('security.continuousDesc')}
        checked={continuousOn()}
        onChange={() => props.updateStreamingAndSave({ continuousRecording: !continuousOn() })}
      />
      <SliderField
        label={t('security.segmentLength')}
        display={`${stream()?.continuousSegmentMinutes ?? API_DEFAULTS.continuousSegmentMinutes} min`}
        min={API_DEFAULTS.continuousSegmentMinMinutes}
        max={API_DEFAULTS.continuousSegmentMaxMinutes}
        step={5}
        disabled={!continuousOn()}
        value={stream()?.continuousSegmentMinutes ?? API_DEFAULTS.continuousSegmentMinutes}
        onChange={(v) => props.updateStreamingDebounced({ continuousSegmentMinutes: v })}
      />

      {/* On-device alerts and tamper */}
      <ToggleField
        label={t('security.localAlerts')}
        checked={localAlertsOn()}
        onChange={() => props.updateStreamingAndSave({ detectionNotificationsEnabled: !localAlertsOn() })}
      />
      <Show when={localAlertsOn()}>
        <ToggleField
          label={t('security.quietHours')}
          checked={quietHoursOn()}
          onChange={() => props.updateStreamingAndSave({ alertQuietHoursEnabled: !quietHoursOn() })}
        />
        <Show when={quietHoursOn()}>
          <SliderField
            label={t('security.quietFrom')}
            display={minutesToLabel(stream()?.alertQuietHoursStartMinute ?? API_DEFAULTS.alertQuietHoursStartMinute)}
            min={0}
            max={MINUTES_PER_DAY - 1}
            step={15}
            value={stream()?.alertQuietHoursStartMinute ?? API_DEFAULTS.alertQuietHoursStartMinute}
            onChange={(v) => props.updateStreamingDebounced({ alertQuietHoursStartMinute: v })}
          />
          <SliderField
            label={t('security.quietUntil')}
            display={minutesToLabel(stream()?.alertQuietHoursEndMinute ?? API_DEFAULTS.alertQuietHoursEndMinute)}
            min={0}
            max={MINUTES_PER_DAY - 1}
            step={15}
            value={stream()?.alertQuietHoursEndMinute ?? API_DEFAULTS.alertQuietHoursEndMinute}
            onChange={(v) => props.updateStreamingDebounced({ alertQuietHoursEndMinute: v })}
          />
        </Show>
      </Show>
      <ToggleField
        label={t('security.tamper')}
        checked={tamperOn()}
        onChange={() => props.updateStreamingAndSave({ tamperDetectionEnabled: !tamperOn() })}
      />

      {/* Webhook alerts */}
      <ToggleField
        label={t('security.webhook')}
        checked={webhookOn()}
        onChange={() => props.updateStreamingAndSave({ webhookEnabled: !webhookOn() })}
      />
      <Show when={webhookOn()}>
        <TextInput
          mono
          type="url"
          placeholder={t('security.webhookUrlPlaceholder')}
          value={stream()?.webhookUrl ?? ''}
          onInput={(v) => props.updateStreamingDebounced({ webhookUrl: v })}
          ariaLabel={t('security.webhook')}
        />
        <TextInput
          mono
          type="text"
          placeholder={t('security.webhookHeadersPlaceholder')}
          value={stream()?.webhookHeaders ?? API_DEFAULTS.webhookHeaders}
          onInput={(v) => props.updateStreamingDebounced({ webhookHeaders: v })}
          ariaLabel={t('security.webhookHeadersPlaceholder')}
        />
        <Show when={webhookHeadersWarning(stream()?.webhookHeaders)}>
          {(warning) => (
            <div class="nx-banner nx-banner-warning" role="alert">{warning()}</div>
          )}
        </Show>
      </Show>
      {/* The test alert fires every configured sink (webhook, MQTT, Web
          Push, local notification), not just the webhook — so it stays
          visible with the webhook off: a local-alerts-only user needs it
          to verify their chain too. The zero-sink verdict reads honestly
          in the result line below. */}
      <Button
        size="sm"
        variant="ghost"
        disabled={testAlertBusy()}
        onClick={sendTestAlertNow}
        ariaLabel={t('security.sendTestAlert')}
      >
        {testAlertBusy() ? t('security.sending') : t('security.sendTestAlert')}
      </Button>
      <Show when={testAlertResult()}>
        <div class="nx-banner nx-banner-neutral" role="status" aria-live="polite">{testAlertResult()}</div>
      </Show>

      {/* Deterrence automation */}
      <ToggleField
        label={t('security.autoSiren')}
        checked={autoSirenOn()}
        onChange={() => props.updateStreamingAndSave({ autoSiren: !autoSirenOn() })}
      />
      <Show when={autoSirenOn()}>
        <SliderField
          label={t('security.sirenDuration')}
          display={`${stream()?.sirenDurationSeconds ?? API_DEFAULTS.sirenDurationSeconds}s`}
          min={API_DEFAULTS.sirenDurationMinSeconds}
          max={API_DEFAULTS.sirenDurationMaxSeconds}
          step={1}
          value={stream()?.sirenDurationSeconds ?? API_DEFAULTS.sirenDurationSeconds}
          onChange={(v) => props.updateStreamingDebounced({ sirenDurationSeconds: v })}
        />
      </Show>
      <ToggleField
        label={t('security.autoTorch')}
        checked={autoTorchOn()}
        onChange={() => props.updateStreamingAndSave({ autoTorch: !autoTorchOn() })}
      />
      <SliderField
        label={t('security.retriggerCooldown')}
        display={`${stream()?.autoDeterrenceCooldownSeconds ?? API_DEFAULTS.autoDeterrenceCooldownSeconds}s`}
        min={API_DEFAULTS.deterrenceCooldownMinSeconds}
        max={API_DEFAULTS.deterrenceCooldownMaxSeconds}
        step={5}
        value={stream()?.autoDeterrenceCooldownSeconds ?? API_DEFAULTS.autoDeterrenceCooldownSeconds}
        onChange={(v) => props.updateStreamingDebounced({ autoDeterrenceCooldownSeconds: v })}
      />

      {/* Manual deterrence */}
      <span class="nx-label">{t('security.deterrence')}</span>
      <div class="nx-deterrence">
        <Button
          variant={sirenOn() ? 'danger' : 'secondary'}
          size="sm"
          disabled={sirenBusy()}
          onClick={toggleSiren}
          ariaLabel={sirenOn() ? t('security.sirenOff') : t('security.sirenOn')}
        >
          {sirenOn() ? t('security.sirenOff') : t('security.sirenOn')}
        </Button>
        <Button
          variant={torchOn() ? 'warning' : 'secondary'}
          size="sm"
          disabled={torchBusy()}
          onClick={toggleTorch}
          ariaLabel={torchOn() ? t('security.lightOff') : t('security.lightOn')}
        >
          {torchOn() ? t('security.lightOff') : t('security.lightOn')}
        </Button>
      </div>
    </Panel>
  )
}
