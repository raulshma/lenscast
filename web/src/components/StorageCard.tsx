import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * Capture and detection-event retention windows, in days, plus the storage
 * quota in MB. 0 keeps everything; items older than the window are deleted,
 * and media past the quota ages out oldest-first. Number inputs follow the
 * MQTT card's broker-port pattern: debounce-save while typing, only when the
 * text parses as an integer — the input's min/max stop the spinners but not
 * free-typed text, so each save clamps explicitly.
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
      title="Storage"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Keep Captures (days)</span>
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
          <span class="field-label">Keep Detection Events (days)</span>
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
          <span class="field-label">Storage Quota (MB)</span>
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

      <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
        <span class="status-banner-dot" aria-hidden="true" />
        <span>0 keeps everything; oldest items are deleted beyond the window. Past the quota, the oldest captures are removed automatically.</span>
      </div>
    </SettingsCard>
  )
}
