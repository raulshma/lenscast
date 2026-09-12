import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { AllSettings, DeviceStatus } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { formatTime, t } from '../lib/i18n'

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

const STATE_COLORS: Record<string, string> = {
  IDLE: 'var(--lc-text-muted)',
  MONITORING: 'var(--lc-success)',
  RECOVERING: 'var(--lc-warning)',
  FAILED: 'var(--lc-danger)',
  COOLDOWN: 'var(--lc-warning)',
}

export default function WatchdogCard(props: Props) {
  const s = () => props.settings()
  const wd = () => props.status()?.watchdog
  const enabled = () => s()?.streaming?.watchdogEnabled ?? API_DEFAULTS.watchdogEnabled

  const statusInfo = () => {
    const st = wd()?.status ?? 'IDLE'
    return { label: t(STATE_KEYS[st] ?? STATE_KEYS.IDLE), color: STATE_COLORS[st] ?? STATE_COLORS.IDLE }
  }

  const formatTimestamp = (ts: number) => {
    if (!ts) return '—'
    return formatTime(ts, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z" />
          <path d="M12 6v6l4 2" />
        </svg>
      }
      title={t('watchdog.title')}
    >
      {/* Enable Toggle */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('watchdog.enable')}</span>
          <label class="toggle-switch" for="watchdog-enable-toggle">
            <input
              id="watchdog-enable-toggle"
              type="checkbox"
              checked={enabled()}
              onChange={() => props.updateStreamingAndSave({ watchdogEnabled: !enabled() })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('watchdog.desc')}</span>
        </div>
      </div>

      <Show when={enabled()}>
        {/* Max Retries */}
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('watchdog.maxRetries')}</span>
            <span class="field-value">{s()?.streaming?.watchdogMaxRetries ?? API_DEFAULTS.watchdogMaxRetries}</span>
          </div>
          <input
            id="watchdog-max-retries-slider"
            type="range"
            class="custom-range"
            min={API_DEFAULTS.watchdogMaxRetriesMin}
            max={API_DEFAULTS.watchdogMaxRetriesMax}
            step={1}
            value={s()?.streaming?.watchdogMaxRetries ?? API_DEFAULTS.watchdogMaxRetries}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ watchdogMaxRetries: v })
            }}
          />
        </div>

        {/* Check Interval */}
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">{t('watchdog.interval')}</span>
            <span class="field-value">{s()?.streaming?.watchdogCheckIntervalSeconds ?? API_DEFAULTS.watchdogCheckIntervalSeconds}s</span>
          </div>
          <input
            id="watchdog-check-interval-slider"
            type="range"
            class="custom-range"
            min={API_DEFAULTS.watchdogCheckIntervalMinSeconds}
            max={API_DEFAULTS.watchdogCheckIntervalMaxSeconds}
            step={1}
            value={s()?.streaming?.watchdogCheckIntervalSeconds ?? API_DEFAULTS.watchdogCheckIntervalSeconds}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ watchdogCheckIntervalSeconds: v })
            }}
          />
        </div>

        {/* Live Status */}
        <Show when={wd()}>
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('common.status')}</span>
              <span
                class="field-value watchdog-status-badge"
                style={{ color: statusInfo().color }}
              >
                <span
                  class="watchdog-status-dot"
                  style={{ background: statusInfo().color }}
                />
                {statusInfo().label}
              </span>
            </div>
          </div>

          <Show when={wd()!.totalRecoveries > 0 || wd()!.consecutiveFailures > 0}>
            <div class="field-group watchdog-stats-grid">
              <Show when={wd()!.consecutiveFailures > 0}>
                <div class="field-row">
                  <span class="field-label">{t('watchdog.consecutiveFailures')}</span>
                  <span class="field-value" style={{ color: 'var(--lc-warning)' }}>
                    {wd()!.consecutiveFailures}
                  </span>
                </div>
              </Show>
              <div class="field-row">
                <span class="field-label">{t('watchdog.totalRecoveries')}</span>
                <span class="field-value">{wd()!.totalRecoveries}</span>
              </div>
              <Show when={wd()!.lastRecoveryTimestamp > 0}>
                <div class="field-row">
                  <span class="field-label">{t('watchdog.lastRecovery')}</span>
                  <span class="field-value">{formatTimestamp(wd()!.lastRecoveryTimestamp)}</span>
                </div>
              </Show>
            </div>
          </Show>

          <Show when={wd()!.lastFailureReason}>
            <div class="status-banner status-banner-warning" role="alert">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{wd()!.lastFailureReason}</span>
            </div>
          </Show>

          <Show when={wd()!.status === 'FAILED'}>
            <div class="status-banner status-banner-error" role="alert">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{t('watchdog.exhausted')}</span>
            </div>
          </Show>
        </Show>
      </Show>
    </SettingsCard>
  )
}
