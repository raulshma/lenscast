import { createResource, createSignal, For, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import type { SystemInfo } from '../../../../types'
import { getSystemInfo, getStatus, resetCamera, restartApp } from '../../../../api/client'
import { formatBytes } from '../../../../format'
import { reliabilityAdvisories, restartRefusalDisplay, resetCameraFailureDisplay } from '../../../../lib/reliabilityHints'
import { t } from '../../../../lib/i18n'
import { Button, ConfirmDialog, FieldRow, Panel, Skeleton } from '../../ui'
import { SettingItem, useInspectorSection } from '../../ui/SettingItem'

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
 * A failure renders an unavailable note instead of an error. The
 * reliability callouts (blueprint P0-B item 7) surface the grant/battery
 * states the 24/7 run depends on before they cost a night.
 *
 * Wave 4 (blueprint P1 items 9/10): the restart dialog gains the soft
 * restart — the in-process ladder that preserves the FGS's camera standing
 * (resolved tension 2) — beside the deep restart, and the camera-state row
 * carries the reset-camera button (the bounded, server-never-stops
 * recovery).
 */
export default function SystemInfoPanel() {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const [info] = createResource<SystemInfo>(() => getSystemInfo())
  // The camera state rides the live status snapshot (GET /api/status's
  // `camera` line): the reset button sits beside it, so the row is both the
  // diagnosis and the lever.
  const [status, statusActions] = createResource(() => getStatus())
  const [confirmOpen, setConfirmOpen] = createSignal(false)
  const [restarting, setRestarting] = createSignal(false)
  const [restartFailure, setRestartFailure] = createSignal('')
  const [resetting, setResetting] = createSignal(false)
  const [resetDone, setResetDone] = createSignal(false)
  const [resetFailure, setResetFailure] = createSignal('')

  // Issue #18's App-Restart button: the device schedules its own relaunch and
  // quits, so a dead connection here is the happy path — the note is shown
  // either way and the dashboard's status polling reconnects to the fresh
  // session. The typed refusal (blueprint item 8) is different: the
  // pre-check refused BEFORE the device quit — nothing restarted, so the
  // localized failure replaces the restarting note and the dialog closes.
  const onRestart = async (mode: 'soft' | 'process') => {
    if (readOnly()) return
    setConfirmOpen(false)
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
  // server never stops — the answer is typed, so a policy refusal surfaces
  // its reason instead of a silent shrug.
  const onResetCamera = async () => {
    if (readOnly()) return
    setResetting(true)
    setResetDone(false)
    setResetFailure('')
    try {
      const result = await resetCamera()
      if (result.success) {
        setResetDone(true)
        // Re-fetch the camera line so the row's state reflects the rebind.
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

  // The reliability callouts (blueprint item 7): the Show above is not
  // keyed, so the accessor guards the undefined case rows() also guards.
  const advisories = () => {
    const d = info()
    return d ? reliabilityAdvisories(d) : []
  }

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
    <Panel title={t('system.title')} class="nx-info-panel">
      <SettingItem id="system.diagnostics">
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
        <For each={advisories()}>
          {(advisory) => (
            <div
              class={`nx-banner ${advisory.level === 'warning' ? 'nx-banner-warning' : 'nx-banner-info'}`}
              role={advisory.level === 'warning' ? 'alert' : 'note'}
            >
              <span>{t(advisory.messageKey)}</span>
            </div>
          )}
        </For>
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
      </SettingItem>
      <Show when={info()}>
        <SettingItem id="system.reset-camera">
        <FieldRow label={t('system.camera')}>
          <span class="nx-info-value">
            {status()?.camera || '—'}
          </span>
          <Button
            variant="secondary"
            size="sm"
            disabled={resetting() || readOnly()}
            loading={resetting()}
            onClick={onResetCamera}
          >
            {t('system.resetCamera')}
          </Button>
        </FieldRow>
        <Show when={resetDone()}>
          <div class="nx-banner nx-banner-info" role="note">
            <span>{t('system.resetCameraDone')}</span>
          </div>
        </Show>
        <Show when={resetFailure()}>
          <div class="nx-banner nx-banner-warning" role="alert">
            <span>{resetFailure()}</span>
          </div>
        </Show>
        </SettingItem>
        <SettingItem id="system.restart">
        <FieldRow label={t('system.restart')}>
          <Show
            when={!restarting()}
            fallback={<span class="nx-info-value nx-info-note">{t('system.restarting')}</span>}
          >
            <Button
              variant="danger-soft"
              size="sm"
              disabled={readOnly()}
              onClick={() => { if (!readOnly()) setConfirmOpen(true) }}
            >
              {t('system.restartButton')}
            </Button>
          </Show>
        </FieldRow>
        <Show when={restartFailure()}>
          <div class="nx-banner nx-banner-warning" role="alert">
            <span>{restartFailure()}</span>
          </div>
        </Show>
        </SettingItem>
      </Show>
      <ConfirmDialog
        open={confirmOpen() && !readOnly()}
        title={t('system.restart')}
        message={t('system.restartConfirmOptions')}
        danger
        busy={restarting()}
        confirmLabel={t('system.restartButton')}
        alternativeLabel={t('system.softRestartButton')}
        onAlternative={() => onRestart('soft')}
        onConfirm={() => onRestart('process')}
        onClose={() => setConfirmOpen(false)}
      />
    </Panel>
  )
}
