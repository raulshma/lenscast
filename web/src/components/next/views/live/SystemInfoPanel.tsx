import { createResource, For, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import type { SystemInfo } from '../../../../types'
import { getSystemInfo } from '../../../../api/client'
import { formatBytes } from '../../../../format'
import { t } from '../../../../lib/i18n'
import { Panel, FieldRow, Skeleton } from '../../ui'

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
 * Read-only diagnostics, ported from the classic SystemPanel: what build
 * is running, on what device, for how long, and how battery and storage
 * are holding up — the triage view for a headless phone (GET /api/system).
 * A failure renders an unavailable note instead of an error.
 */
export default function SystemInfoPanel() {
  const [info] = createResource<SystemInfo>(() => getSystemInfo())

  const rows = () => {
    const d = info()
    if (!d) return []
    const battery = (
      <span class="nx-info-value nx-info-battery">
        <span class="nx-num">{d.battery.level}%</span>
        <Show when={d.battery.isCharging}>
          <span class="nx-info-note">{t('next.vitals.charging')}</span>
        </Show>
        <span class="nx-num">{batteryTempLabel(d.battery.temperatureTenthsC)}</span>
      </span>
    )
    // One quiet stat per row; the third slot flags mono numerals (.nx-num).
    // Names (the device) stay in the UI face — measurements ride mono.
    return [
      [t('system.appVersion'), d.appVersion || '—', true],
      [t('system.device'), [d.deviceManufacturer, d.deviceModel].filter(Boolean).join(' ') || '—', false],
      [t('system.android'), `${d.androidVersion} (API ${d.sdkInt})`, true],
      [t('system.osUptime'), formatUptime(d.osUptimeMs), true],
      [t('system.processUptime'), formatUptime(d.processUptimeMs), true],
      [t('system.battery'), battery, false],
      [t('system.capturesOnDisk'), `${formatBytes(d.storage.usedBytes)} / ${formatBytes(d.storage.quotaBytes)}`, true],
      [t('system.volumeFree'), `${formatBytes(d.storage.freeBytes)} / ${formatBytes(d.storage.totalBytes)}`, true],
    ] as Array<[string, string | JSX.Element, boolean]>
  }

  return (
    <Panel title={t('system.title')}>
      <Show
        when={info()}
        fallback={
          <Show when={info.error} fallback={
            <>
              <Skeleton h="14px" />
              <Skeleton h="14px" w="70%" />
              <Skeleton h="14px" w="55%" />
            </>
          }>
            <div class="nx-banner nx-banner-neutral" role="note">
              <span>{t('system.unavailable')}</span>
            </div>
          </Show>
        }
      >
        <For each={rows()}>
          {([label, value, mono]) => (
            <FieldRow label={label}>
              <Show when={typeof value === 'string'} fallback={value}>
                <span class="nx-info-value" classList={{ 'nx-num': mono }}>{value as string}</span>
              </Show>
            </FieldRow>
          )}
        </For>
      </Show>
    </Panel>
  )
}
