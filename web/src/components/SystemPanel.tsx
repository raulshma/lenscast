import { createResource, createSignal, For, Show } from 'solid-js'
import type { SystemInfo } from '../types'
import { getSystemInfo, getStatus, resetCamera, restartApp } from '../api/client'
import { formatBytes } from '../format'
import SettingsCard from './SettingsCard'
import { reliabilityAdvisories, restartRefusalDisplay, resetCameraFailureDisplay } from '../lib/reliabilityHints'
import { t } from '../lib/i18n'

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
 * renders the card with an unavailable note instead of an error. The
 * reliability callouts (blueprint P0-B item 7) surface the grant/battery
 * states the 24/7 run depends on before they cost a night.
 *
 * Wave 4 (blueprint P1 items 9/10): the restart row gains the soft restart —
 * the in-process ladder that preserves the FGS's camera standing (resolved
 * tension 2) beside the deep restart — and the camera-state row carries the
 * reset-camera button (the bounded, server-never-stops recovery).
 */
export default function SystemPanel() {
  const [info] = createResource<SystemInfo>(() => getSystemInfo())
  // The camera state rides the live status snapshot (GET /api/status's
  // `camera` line): the reset button sits beside it, so the row is both the
  // diagnosis and the lever.
  const [status, statusActions] = createResource(() => getStatus())
  const [restarting, setRestarting] = createSignal(false)
  const [restartFailure, setRestartFailure] = createSignal('')
  const [resetting, setResetting] = createSignal(false)
  const [resetDone, setResetDone] = createSignal(false)
  const [resetFailure, setResetFailure] = createSignal('')

  // Issue #18's App-Restart button: the device schedules its own relaunch and
  // quits, so the request failing with a dead connection is the happy path —
  // the note is shown either way and the dashboard's status polling
  // reconnects to the fresh session. The typed refusal (blueprint item 8) is
  // different: the pre-check refused BEFORE the device quit — nothing
  // restarted, no reconnect will follow, so the localized failure replaces
  // the restarting note. The soft mode (blueprint item 9) has no quit at all:
  // its dropped response is chased by the client's own /api/system poll.
  const onRestart = async (mode: 'soft' | 'process') => {
    if (restarting()) return
    const confirmKey = mode === 'soft' ? 'system.softRestartConfirm' : 'system.restartConfirm'
    if (!window.confirm(t(confirmKey))) return
    setRestarting(true)
    setRestartFailure('')
    try {
      const result = await restartApp(mode)
      if (result.success === false) {
        const display = restartRefusalDisplay(result)
        setRestartFailure('messageKey' in display ? t(display.messageKey) : display.deviceHint)
        setRestarting(false)
      }
    } catch {
      // Expected: the teardown can kill the connection before the OK lands.
    }
  }

  // The reset-camera lever (blueprint item 10): one bounded rebind, the
  // server never stops — the typed answer surfaces a policy refusal's reason
  // instead of a silent shrug.
  const onResetCamera = async () => {
    setResetting(true)
    setResetDone(false)
    setResetFailure('')
    try {
      const result = await resetCamera()
      if (result.success) {
        setResetDone(true)
        statusActions.refetch()
      } else {
        const display = resetCameraFailureDisplay(result)
        setResetFailure('messageKey' in display ? t(display.messageKey) : display.deviceHint)
      }
    } catch {
      setResetFailure(t('system.resetCameraDenied.generic'))
    }
    setResetting(false)
  }

  const rows = () => {
    const d = info()
    if (!d) return []
    return [
      [t('system.appVersion'), d.appVersion || '—'],
      [t('system.device'), [d.deviceManufacturer, d.deviceModel].filter(Boolean).join(' ') || '—'],
      [t('system.android'), `${d.androidVersion} (API ${d.sdkInt})`],
      [t('system.osUptime'), formatUptime(d.osUptimeMs)],
      [t('system.processUptime'), formatUptime(d.processUptimeMs)],
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
      title={t('system.title')}
    >
      <Show
        when={info()}
        fallback={
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('system.unavailable')}</span>
          </div>
        }
      >
        {(d) => (
          <>
            <For each={reliabilityAdvisories(d())}>
              {(advisory) => (
                <div
                  class={`status-banner ${advisory.level === 'warning' ? 'status-banner-warning' : 'status-banner-info'}`}
                  role={advisory.level === 'warning' ? 'alert' : 'note'}
                >
                  <span class="status-banner-dot" aria-hidden="true" />
                  <span>{t(advisory.messageKey)}</span>
                </div>
              )}
            </For>
            <For each={rows()}>
              {([label, value]) => (
                <div class="field-row">
                  <span class="field-label">{label}</span>
                  <span class="field-value">{value}</span>
                </div>
              )}
            </For>
            <div class="field-row">
              <span class="field-label">{t('system.battery')}</span>
              <span class="field-value">
                {d().battery.level}%{d().battery.isCharging ? ' ⚡' : ''} · {batteryTempLabel(d().battery.temperatureTenthsC)}
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">{t('system.camera')}</span>
              <div class="motion-zone-actions">
                <span class="field-value">{status()?.camera || '—'}</span>
                <button
                  type="button"
                  class="client-kick-btn"
                  disabled={resetting()}
                  onClick={onResetCamera}
                >
                  {t('system.resetCamera')}
                </button>
              </div>
            </div>
            <div class="field-row">
              <span class="field-label">{t('system.capturesOnDisk')}</span>
              <span class="field-value">
                {formatBytes(d().storage.usedBytes)} / {formatBytes(d().storage.quotaBytes)}
              </span>
            </div>
            <div class="field-row">
              <span class="field-label">{t('system.volumeFree')}</span>
              <span class="field-value">
                {formatBytes(d().storage.freeBytes)} / {formatBytes(d().storage.totalBytes)}
              </span>
            </div>
            <Show when={resetDone()}>
              <div class="status-banner status-banner-info stream-mode-hint" role="note">
                <span class="status-banner-dot" aria-hidden="true" />
                <span>{t('system.resetCameraDone')}</span>
              </div>
            </Show>
            <Show when={resetFailure()}>
              <div class="status-banner status-banner-warning" role="alert">
                <span class="status-banner-dot" aria-hidden="true" />
                <span>{resetFailure()}</span>
              </div>
            </Show>
            <div class="field-row">
              <span class="field-label">{t('system.restart')}</span>
              <div class="motion-zone-actions">
                <Show
                  when={!restarting()}
                  fallback={<span class="field-value">{t('system.restarting')}</span>}
                >
                  <button type="button" class="client-kick-btn" onClick={() => onRestart('soft')}>
                    {t('system.softRestartButton')}
                  </button>
                  <button type="button" class="client-kick-btn" onClick={() => onRestart('process')}>
                    {t('system.restartButton')}
                  </button>
                </Show>
              </div>
            </div>
            <Show when={restartFailure()}>
              <div class="status-banner status-banner-warning" role="alert">
                <span class="status-banner-dot" aria-hidden="true" />
                <span>{restartFailure()}</span>
              </div>
            </Show>
          </>
        )}
      </Show>
    </SettingsCard>
  )
}
