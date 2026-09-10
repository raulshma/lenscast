import { createResource, For, Show } from 'solid-js'
import type { SystemInfo } from '../types'
import { getSystemInfo } from '../api/client'
import { formatBytes } from '../format'
import SettingsCard from './SettingsCard'

function formatUptime(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '—'
  const minutes = Math.floor(ms / 60_000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const mins = minutes % 60
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${mins}m`
  return `${mins}m`
}

function batteryTempLabel(tenthsC: number | null | undefined): string {
  return tenthsC == null ? '—' : `${(tenthsC / 10).toFixed(1)}°C`
}

/**
 * The read-only diagnostics card: what build is running, on what device, for
 * how long, and how the battery and storage are holding up — the triage view
 * for a headless phone (GET /api/system). Fetched once per mount; a failure
 * renders the card with an unavailable note instead of an error.
 */
export default function SystemPanel() {
  const [info] = createResource<SystemInfo>(() => getSystemInfo())

  const rows = () => {
    const d = info()
    if (!d) return []
    return [
      ['App version', d.appVersion || '—'],
      ['Device', [d.deviceManufacturer, d.deviceModel].filter(Boolean).join(' ') || '—'],
      ['Android', `${d.androidVersion} (API ${d.sdkInt})`],
      ['OS uptime', formatUptime(d.osUptimeMs)],
      ['Process uptime', formatUptime(d.processUptimeMs)],
    ] as const
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="4" y="4" width="16" height="16" rx="2" />
          <path d="M9 9h6v6H9z" />
          <path d="M9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3" />
        </svg>
      }
      title="System"
    >
      <Show
        when={info()}
        fallback={
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>System information unavailable.</span>
          </div>
        }
      >
        {(d) => (
          <>
            <For each={rows()}>
              {([label, value]) => (
                <div class="field-row">
                  <span class="field-label">{label}</span>
                  <span class="field-value">{value}</span>
                </div>
              )}
            </For>
            <div class="field-row">
              <span class="field-label">Battery</span>
              <span class="field-value">
                {d().battery.level}%{d().battery.isCharging ? ' ⚡' : ''} · {batteryTempLabel(d().battery.temperatureTenthsC)}
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">Captures on disk</span>
              <span class="field-value">
                {formatBytes(d().storage.usedBytes)} / {formatBytes(d().storage.quotaBytes)}
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">Volume free</span>
              <span class="field-value">
                {formatBytes(d().storage.freeBytes)} / {formatBytes(d().storage.totalBytes)}
              </span>
            </div>
          </>
        )}
      </Show>
    </SettingsCard>
  )
}
