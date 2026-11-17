import { createResource, Show } from 'solid-js'
import type { AllSettings, DeviceStatus, SystemInfo } from '../../../../../types'
import { getSystemInfo } from '../../../../../api/client'
import { API_DEFAULTS } from '../../../../../api/defaults'
import { formatTime, t } from '../../../../../lib/i18n'
import { Panel, ToggleField, SliderField, FieldRow, Badge, Led, type BadgeTone, type LedTone } from '../../../ui'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

const STATE_KEYS: Record<string, string> = {
  IDLE: 'watchdog.state.idle',
  MONITORING: 'watchdog.state.monitoring',
  RECOVERING: 'watchdog.state.recovering',
  FAILED: 'watchdog.state.failed',
  COOLDOWN: 'watchdog.state.cooldown',
}

const STATE_TONES: Record<string, BadgeTone> = {
  IDLE: 'neutral',
  MONITORING: 'success',
  RECOVERING: 'warning',
  FAILED: 'danger',
  COOLDOWN: 'warning',
}

const STATE_LEDS: Record<string, LedTone> = {
  IDLE: 'off',
  MONITORING: 'ok',
  RECOVERING: 'warn',
  FAILED: 'danger',
  COOLDOWN: 'warn',
}

/**
 * Stream watchdog, ported from the classic WatchdogCard onto the kit with
 * every binding unchanged: the enable toggle, max-retries and check-interval
 * sliders, and the live status readout (state, consecutive failures, total
 * recoveries, last recovery time, last failure reason, exhausted alert)
 * straight off the status snapshot's watchdog slice. Plus the out-of-process
 * reliability telemetry (spec item 7, fix D): the crash-relaunch guard's
 * count/state and the alarm heartbeat's armed/misses/tier ride GET
 * /api/system as one compact status line each — shown whenever wired,
 * independent of the watchdog toggle.
 */
export default function WatchdogPanel(props: Props) {
  const s = () => props.settings()
  const wd = () => props.status()?.watchdog
  const enabled = () => s()?.streaming?.watchdogEnabled ?? API_DEFAULTS.watchdogEnabled
  const fsiResume = () => s()?.streaming?.fsiCameraResumeEnabled ?? API_DEFAULTS.fsiCameraResumeEnabled
  // The out-of-process reliability telemetry (GET /api/system, spec item 7's
  // "the dashboard can see every state"): the crash-relaunch loop guard's and
  // the alarm heartbeat's live states, one compact line each. Self-fetched —
  // the SystemInfoPanel precedent; absent fields stay hidden (an unwired
  // graph keeps them off the wire).
  const [sys] = createResource<SystemInfo>(() => getSystemInfo())
  const crashGuard = () => sys()?.crashRelaunch
  const heartbeat = () => sys()?.heartbeat

  const statusLabel = () => t(STATE_KEYS[wd()?.status ?? 'IDLE'] ?? STATE_KEYS.IDLE)

  const formatTimestamp = (ts: number) => {
    if (!ts) return '—'
    return formatTime(ts, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  }

  return (
    <Panel
      title={t('watchdog.title')}
      badge={
        <Show when={enabled()}>
          <Badge tone={STATE_TONES[wd()?.status ?? 'IDLE'] ?? 'neutral'} mono>
            <Led tone={STATE_LEDS[wd()?.status ?? 'IDLE'] ?? 'off'} />
            {statusLabel()}
          </Badge>
        </Show>
      }
    >
      {/* Enable Toggle */}
      <ToggleField
        label={t('watchdog.enable')}
        checked={enabled()}
        onChange={() => props.updateStreamingAndSave({ watchdogEnabled: !enabled() })}
      />
      <div class="nx-banner nx-banner-info" role="note" aria-live="polite">
        {t('watchdog.desc')}
      </div>

      {/* Camera Resume Prompt (opt-in, default off) */}
      <ToggleField
        label={t('watchdog.fsiResume')}
        checked={fsiResume()}
        onChange={() => props.updateStreamingAndSave({ fsiCameraResumeEnabled: !fsiResume() })}
      />
      <div class="nx-banner nx-banner-info" role="note" aria-live="polite">
        {t('watchdog.fsiResume.desc')}
      </div>

      {/* Crash guard + heartbeat telemetry (spec item 7): every state the
          overnight run relies on, dashboard-visible. Both run regardless of
          the watchdog toggle (the heartbeat keys on the server lifecycle,
          not the session), so these lines are not gated on `enabled()`.
          GIVE_UP warns — the handler has stopped arming and only the OS
          sticky restart is left. */}
      <Show when={crashGuard()}>
        <FieldRow label={t('watchdog.crashGuard')}>
          <span
            class="nx-mono nx-info-value"
            style={{ color: crashGuard()!.state === 'GIVE_UP' ? 'var(--nx-warning)' : undefined }}
          >
            {crashGuard()!.crashCount} · {crashGuard()!.state}
          </span>
        </FieldRow>
      </Show>
      <Show when={heartbeat()}>
        <FieldRow label={t('watchdog.heartbeat')}>
          <span class="nx-mono nx-info-value">
            {heartbeat()!.armed ? t('watchdog.heartbeat.armed') : t('watchdog.heartbeat.disarmed')}
            {' · '}{heartbeat()!.misses} {t('watchdog.heartbeat.misses')}
            <Show when={heartbeat()!.tier}>{' · '}{heartbeat()!.tier}</Show>
          </span>
        </FieldRow>
      </Show>

      <Show when={enabled()}>
        {/* Max Retries */}
        <SliderField
          label={t('watchdog.maxRetries')}
          display={`${s()?.streaming?.watchdogMaxRetries ?? API_DEFAULTS.watchdogMaxRetries}`}
          min={API_DEFAULTS.watchdogMaxRetriesMin}
          max={API_DEFAULTS.watchdogMaxRetriesMax}
          step={1}
          value={s()?.streaming?.watchdogMaxRetries ?? API_DEFAULTS.watchdogMaxRetries}
          onChange={(v) => props.updateStreamingDebounced({ watchdogMaxRetries: v })}
        />

        {/* Check Interval */}
        <SliderField
          label={t('watchdog.interval')}
          display={`${s()?.streaming?.watchdogCheckIntervalSeconds ?? API_DEFAULTS.watchdogCheckIntervalSeconds}s`}
          min={API_DEFAULTS.watchdogCheckIntervalMinSeconds}
          max={API_DEFAULTS.watchdogCheckIntervalMaxSeconds}
          step={1}
          value={s()?.streaming?.watchdogCheckIntervalSeconds ?? API_DEFAULTS.watchdogCheckIntervalSeconds}
          onChange={(v) => props.updateStreamingDebounced({ watchdogCheckIntervalSeconds: v })}
        />

        {/* Live Status */}
        <Show when={wd()}>
          <FieldRow label={t('common.status')}>
            <span class="nx-mono nx-info-value">
              <Led tone={STATE_LEDS[wd()!.status] ?? 'off'} /> {statusLabel()}
            </span>
          </FieldRow>

          <Show when={wd()!.totalRecoveries > 0 || wd()!.consecutiveFailures > 0}>
            <Show when={wd()!.consecutiveFailures > 0}>
              <FieldRow label={t('watchdog.consecutiveFailures')}>
                <span class="nx-mono nx-info-value" style={{ color: 'var(--nx-warning)' }}>
                  {wd()!.consecutiveFailures}
                </span>
              </FieldRow>
            </Show>
            <FieldRow label={t('watchdog.totalRecoveries')}>
              <span class="nx-mono nx-info-value">{wd()!.totalRecoveries}</span>
            </FieldRow>
            <Show when={wd()!.lastRecoveryTimestamp > 0}>
              <FieldRow label={t('watchdog.lastRecovery')}>
                <span class="nx-mono nx-info-value">{formatTimestamp(wd()!.lastRecoveryTimestamp)}</span>
              </FieldRow>
            </Show>
          </Show>

          <Show when={wd()!.lastFailureReason}>
            <div class="nx-banner nx-banner-warning" role="alert">
              {wd()!.lastFailureReason}
            </div>
          </Show>

          <Show when={wd()!.status === 'FAILED'}>
            <div class="nx-banner nx-banner-danger" role="alert">
              {t('watchdog.exhausted')}
            </div>
          </Show>
        </Show>
      </Show>
    </Panel>
  )
}
