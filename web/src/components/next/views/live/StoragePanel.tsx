import { createMemo, createResource, Show } from 'solid-js'
import type { AllSettings } from '../../../../types'
import { API_DEFAULTS } from '../../../../api/defaults'
import { getGallery, getSystemInfo } from '../../../../api/client'
import { forecastStorage, type StorageForecast } from '../../../../lib/storageForecast'
import { formatBytes } from '../../../../format'
import { t, tCount } from '../../../../lib/i18n'
import { Panel, FieldRow, TextInput } from '../../ui'

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
 * Retention and quota, ported from the classic StorageCard onto the kit
 * with bindings unchanged: number inputs debounce-save while typing, only
 * when the text parses as an integer, and clamp explicitly (min/max stop
 * the spinners but not free-typed text). The forecast line hides itself
 * when the data is too thin to be honest.
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

export default function StoragePanel(props: Props) {
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
    <Panel title={t('storage.title')}>
      <FieldRow label={t('storage.keepCaptures')}>
        <TextInput
          class="nx-input-num"
          type="number"
          small
          mono
          value={String(stream()?.captureRetentionDays ?? API_DEFAULTS.captureRetentionDays)}
          onInput={(raw) => {
            const v = clampRetentionDays(raw)
            if (v !== null) props.updateStreamingDebounced({ captureRetentionDays: v })
          }}
          ariaLabel={t('storage.keepCaptures')}
        />
      </FieldRow>

      <FieldRow label={t('storage.keepEvents')}>
        <TextInput
          class="nx-input-num"
          type="number"
          small
          mono
          value={String(stream()?.eventRetentionDays ?? API_DEFAULTS.eventRetentionDays)}
          onInput={(raw) => {
            const v = clampRetentionDays(raw)
            if (v !== null) props.updateStreamingDebounced({ eventRetentionDays: v })
          }}
          ariaLabel={t('storage.keepEvents')}
        />
      </FieldRow>

      <FieldRow label={t('storage.quota')}>
        <TextInput
          class="nx-input-num"
          type="number"
          small
          mono
          value={String(stream()?.storageQuotaMb ?? API_DEFAULTS.storageQuotaMb)}
          onInput={(raw) => {
            const v = clampQuotaMb(raw)
            if (v !== null) props.updateStreamingDebounced({ storageQuotaMb: v })
          }}
          ariaLabel={t('storage.quota')}
        />
      </FieldRow>

      <Show when={forecast()}>
        {(f) => (
          <div class="nx-banner nx-banner-info" role="note">
            <span>
              {tCount('storage.untilQuota', f().daysRemaining)} — {t('storage.forecastDesc', { perDay: formatBytes(f().bytesPerDay), days: tCount('storage.daysSpanned', Math.round(f().daysSpanned)) })}
            </span>
          </div>
        )}
      </Show>

      <div class="nx-banner nx-banner-neutral" role="note" aria-live="polite">
        <span>{t('storage.retentionDesc')}</span>
      </div>
    </Panel>
  )
}
