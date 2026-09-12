import { createMemo, createResource, Show } from 'solid-js'
import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { getGallery, getSystemInfo } from '../api/client'
import { forecastStorage, type StorageForecast } from '../lib/storageForecast'
import { formatBytes } from '../format'
import SettingsCard from './SettingsCard'
import { t, tCount } from '../lib/i18n'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/** Page size for the forecast's gallery probes — matches Gallery's grid page. */
const FORECAST_PAGE_SIZE = 50

/** The oldest and newest retained capture timestamps, from the gallery's newest-first pages. */
interface CaptureEdges {
  oldestMs: number | null
  newestMs: number | null
}

async function fetchCaptureEdges(): Promise<CaptureEdges> {
  // Newest edge from page 0; the oldest needs the last page when one exists.
  const first = await getGallery(undefined, 0, FORECAST_PAGE_SIZE)
  const stamps = first.items.map((i) => i.timestamp)
  if (stamps.length === 0) return { oldestMs: null, newestMs: null }
  const newestMs = Math.max(...stamps)
  if (!first.hasMore) return { oldestMs: Math.min(...stamps), newestMs }
  const lastPage = Math.max(0, Math.floor((first.total - 1) / FORECAST_PAGE_SIZE))
  const last = lastPage === 0 ? first : await getGallery(undefined, lastPage, FORECAST_PAGE_SIZE)
  const lastStamps = last.items.map((i) => i.timestamp)
  return lastStamps.length === 0
    ? { oldestMs: Math.min(...stamps), newestMs }
    : { oldestMs: Math.min(...lastStamps), newestMs }
}

/**
 * Capture and detection-event retention windows, in days, plus the storage
 * quota in MB. 0 keeps everything; items older than the window are deleted,
 * and media past the quota ages out oldest-first. Number inputs follow the
 * MQTT card's broker-port pattern: debounce-save while typing, only when the
 * text parses as an integer — the input's min/max stop the spinners but not
 * free-typed text, so each save clamps explicitly.
 *
 * Below the inputs, a best-effort "days until quota" forecast averages the
 * daily growth (used bytes from /api/system spread over the oldest→newest
 * capture span from /api/gallery — pure math in lib/storageForecast, which
 * also decides when the data is too thin to show a number).
 */
function clampRetentionDays(raw: string): number | null {
  const v = parseInt(raw, 10)
  if (!Number.isFinite(v)) return null
  return Math.min(API_DEFAULTS.retentionMaxDays, Math.max(API_DEFAULTS.retentionMinDays, v))
}

function clampQuotaMb(raw: string): number | null {
  const v = parseInt(raw, 10)
  if (!Number.isFinite(v)) return null
  return Math.min(API_DEFAULTS.storageQuotaMaxMb, Math.max(API_DEFAULTS.storageQuotaMinMb, v))
}

export default function StorageCard(props: Props) {
  const s = () => props.settings()
  const stream = () => s()?.streaming

  const [system] = createResource(() => getSystemInfo())
  const [edges] = createResource(() => fetchCaptureEdges())

  // Null (any fetch failure, empty gallery, <2 days of history, zero usage)
  // simply hides the line — a made-up estimate is worse than none.
  const forecast = createMemo<StorageForecast | null>(() => {
    const sys = system()
    const cap = edges()
    if (!sys || !cap || cap.oldestMs === null || cap.newestMs === null) return null
    return forecastStorage({
      oldestCaptureMs: cap.oldestMs,
      newestCaptureMs: cap.newestMs,
      usedBytes: sys.storage.usedBytes,
      quotaBytes: sys.storage.quotaBytes,
    })
  })

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M22 12H2" />
          <path d="M5.45 5.11L2 12v6a2 2 0 002 2h16a2 2 0 002-2v-6l-3.45-6.89A2 2 0 0016.76 4H7.24a2 2 0 00-1.79 1.11z" />
          <line x1="6" y1="16" x2="6.01" y2="16" />
          <line x1="10" y1="16" x2="10.01" y2="16" />
        </svg>
      }
      title={t('storage.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('storage.keepCaptures')}</span>
          <span class="field-value">{stream()?.captureRetentionDays ?? API_DEFAULTS.captureRetentionDays}</span>
        </div>
        <input
          id="capture-retention-days"
          type="number"
          class="field-input field-input-full"
          min={API_DEFAULTS.retentionMinDays}
          max={API_DEFAULTS.retentionMaxDays}
          step={1}
          value={stream()?.captureRetentionDays ?? API_DEFAULTS.captureRetentionDays}
          onInput={(e) => {
            const v = clampRetentionDays(e.currentTarget.value)
            if (v !== null) props.updateStreamingDebounced({ captureRetentionDays: v })
          }}
        />
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('storage.keepEvents')}</span>
          <span class="field-value">{stream()?.eventRetentionDays ?? API_DEFAULTS.eventRetentionDays}</span>
        </div>
        <input
          id="event-retention-days"
          type="number"
          class="field-input field-input-full"
          min={API_DEFAULTS.retentionMinDays}
          max={API_DEFAULTS.retentionMaxDays}
          step={1}
          value={stream()?.eventRetentionDays ?? API_DEFAULTS.eventRetentionDays}
          onInput={(e) => {
            const v = clampRetentionDays(e.currentTarget.value)
            if (v !== null) props.updateStreamingDebounced({ eventRetentionDays: v })
          }}
        />
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('storage.quota')}</span>
          <span class="field-value">{stream()?.storageQuotaMb ?? API_DEFAULTS.storageQuotaMb}</span>
        </div>
        <input
          id="storage-quota-mb"
          type="number"
          class="field-input field-input-full"
          min={API_DEFAULTS.storageQuotaMinMb}
          max={API_DEFAULTS.storageQuotaMaxMb}
          step={100}
          value={stream()?.storageQuotaMb ?? API_DEFAULTS.storageQuotaMb}
          onInput={(e) => {
            const v = clampQuotaMb(e.currentTarget.value)
            if (v !== null) props.updateStreamingDebounced({ storageQuotaMb: v })
          }}
        />
      </div>

      <Show when={forecast()}>
        {(f) => (
          <div class="field-group">
            <div class="field-row">
              <span class="field-label" title={t('storage.forecastTitle')}>{t('storage.forecast')}</span>
              <span class="field-value">{tCount('storage.untilQuota', f().daysRemaining)}</span>
            </div>
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>
                {t('storage.forecastDesc', { perDay: formatBytes(f().bytesPerDay), days: tCount('storage.daysSpanned', Math.round(f().daysSpanned)) })}
              </span>
            </div>
          </div>
        )}
      </Show>

      <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
        <span class="status-banner-dot" aria-hidden="true" />
        <span>{t('storage.retentionDesc')}</span>
      </div>
    </SettingsCard>
  )
}
