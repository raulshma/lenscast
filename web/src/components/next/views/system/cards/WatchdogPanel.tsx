import { Show } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../../../../../types'
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
 * straight off the status snapshot's watchdog slice.
 */
export default function WatchdogPanel(props: Props) {
  const s = () => props.settings()
  const wd = () => props.status()?.watchdog
  const enabled = () => s()?.streaming?.watchdogEnabled ?? API_DEFAULTS.watchdogEnabled

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
                <span class="nx-mono nx-info-value" style={{ color: 'var(--lc-warning)' }}>
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
