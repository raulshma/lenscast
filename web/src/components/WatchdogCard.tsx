import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { AllSettings, DeviceStatus } from '../types'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  IDLE: { label: 'Idle', color: 'var(--lc-text-muted)' },
  MONITORING: { label: 'Monitoring', color: 'var(--lc-success)' },
  RECOVERING: { label: 'Recovering…', color: 'var(--lc-warning)' },
  FAILED: { label: 'Failed', color: 'var(--lc-danger)' },
  COOLDOWN: { label: 'Cooldown', color: 'var(--lc-warning)' },
}

export default function WatchdogCard(props: Props) {
  const s = () => props.settings()
  const wd = () => props.status()?.watchdog
  const enabled = () => s()?.streaming?.watchdogEnabled ?? false

  const statusInfo = () => {
    const st = wd()?.status ?? 'IDLE'
    return STATUS_LABELS[st] ?? STATUS_LABELS.IDLE
  }

  const formatTimestamp = (ts: number) => {
    if (!ts) return '—'
    const d = new Date(ts)
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z" />
          <path d="M12 6v6l4 2" />
        </svg>
      }
      title="Auto-Restart Watchdog"
    >
      {/* Enable Toggle */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Enable Watchdog</span>
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
          <span>Automatically detects camera or streaming failures and restarts the pipeline. Designed for 24/7 surveillance.</span>
        </div>
      </div>

      <Show when={enabled()}>
        {/* Max Retries */}
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Max Recovery Attempts</span>
            <span class="field-value">{s()?.streaming?.watchdogMaxRetries ?? 5}</span>
          </div>
          <input
            id="watchdog-max-retries-slider"
            type="range"
            class="custom-range"
            min={1}
            max={10}
            step={1}
            value={s()?.streaming?.watchdogMaxRetries ?? 5}
            onInput={(e) => {
              const v = parseInt(e.currentTarget.value)
              props.updateStreamingDebounced({ watchdogMaxRetries: v })
            }}
          />
        </div>

        {/* Check Interval */}
        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Health Check Interval</span>
            <span class="field-value">{s()?.streaming?.watchdogCheckIntervalSeconds ?? 5}s</span>
          </div>
          <input
            id="watchdog-check-interval-slider"
            type="range"
            class="custom-range"
            min={3}
            max={30}
            step={1}
            value={s()?.streaming?.watchdogCheckIntervalSeconds ?? 5}
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
              <span class="field-label">Status</span>
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
                  <span class="field-label">Consecutive Failures</span>
                  <span class="field-value" style={{ color: 'var(--lc-warning)' }}>
                    {wd()!.consecutiveFailures}
                  </span>
                </div>
              </Show>
              <div class="field-row">
                <span class="field-label">Total Recoveries</span>
                <span class="field-value">{wd()!.totalRecoveries}</span>
              </div>
              <Show when={wd()!.lastRecoveryTimestamp > 0}>
                <div class="field-row">
                  <span class="field-label">Last Recovery</span>
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
              <span>Watchdog exhausted all recovery attempts. Manual intervention required.</span>
            </div>
          </Show>
        </Show>
      </Show>
    </SettingsCard>
  )
}
