import { createResource, createSignal, For, Show } from 'solid-js'
import type { DailyCount } from '../types'
import { getDetectionStats } from '../api/client'
import { STATS_WINDOWS, sevenDaySeries, windowCounts, windowTotal, type StatsWindow } from '../lib/detectionStats'
import { todayKey } from '../lib/timeline'
import SettingsCard from './SettingsCard'

function weekdayLabel(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  if (!y || !m || !d) return day
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: 'narrow' })
}

/**
 * Aggregate detection statistics (GET /api/detection/stats — the counterpart
 * of EventFeed's live row feed): a 24h/7d/all-time window toggle over the
 * per-type counts, a seven-day per-day bar chart (zero-filled to a full week
 * by lib/detectionStats's sevenDaySeries), and the top motion zones / ML
 * labels. The payload carries all three windows at once, so the toggle never
 * refetches; the card loads once per mount like SystemPanel.
 */
export default function DetectionStatsCard() {
  const [stats] = createResource(() => getDetectionStats())
  const [window, setWindow] = createSignal<StatsWindow>('7d')

  const counts = () => (stats() ? windowCounts(stats()!, window()) : [])
  const bars = () => (stats() ? sevenDaySeries(stats()!.perDay, todayKey()) : [])
  const maxCount = () => Math.max(1, ...bars().map((d: DailyCount) => d.count))

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M3 21h18" />
          <rect x="5" y="12" width="3" height="6" rx="1" />
          <rect x="10.5" y="8" width="3" height="10" rx="1" />
          <rect x="16" y="4" width="3" height="14" rx="1" />
        </svg>
      }
      title="Detection Statistics"
    >
      <Show
        when={stats()}
        fallback={
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Detection statistics unavailable.</span>
          </div>
        }
      >
        <div class="field-group">
          <div class="field-row">
            <div class="gallery-filters" role="group" aria-label="Statistics window">
              <For each={STATS_WINDOWS}>
                {({ value, label }) => (
                  <button
                    type="button"
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': window() === value }}
                    onClick={() => setWindow(value)}
                  >
                    {label}
                  </button>
                )}
              </For>
            </div>
            <span class="field-value">{windowTotal(stats()!, window())} total</span>
          </div>

          <For each={counts()}>
            {(row) => (
              <div class="field-row">
                <span class={`event-badge event-badge-${row.type}`}>{row.type}</span>
                <span class="field-value">{row.count} event{row.count === 1 ? '' : 's'}</span>
              </div>
            )}
          </For>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">Last 7 days</span>
          </div>
          <div class="stats-bars" role="img" aria-label="Detections per day over the last seven days">
            <For each={bars()}>
              {(day) => (
                <div class="stats-bar-col" title={`${day.day}: ${day.count} event${day.count === 1 ? '' : 's'}`}>
                  <div class="stats-bar" style={{ height: `${Math.round((day.count / maxCount()) * 100)}%` }} />
                  <span class="stats-bar-count">
                    <Show when={day.count > 0}>{day.count}</Show>
                  </span>
                  <span class="stats-bar-label">{weekdayLabel(day.day)}</span>
                </div>
              )}
            </For>
          </div>
        </div>

        <Show when={stats()!.topZones.length > 0 || stats()!.topLabels.length > 0}>
          <div class="field-group stats-top-grid">
            <Show when={stats()!.topZones.length > 0}>
              <div class="stats-top-list">
                <span class="field-label">Top zones</span>
                <For each={stats()!.topZones}>
                  {(zone) => (
                    <div class="field-row">
                      <span class="event-zone-chip">{zone.label}</span>
                      <span class="field-value">{zone.count}</span>
                    </div>
                  )}
                </For>
              </div>
            </Show>
            <Show when={stats()!.topLabels.length > 0}>
              <div class="stats-top-list">
                <span class="field-label">Top ML labels</span>
                <For each={stats()!.topLabels}>
                  {(label) => (
                    <div class="field-row">
                      <span class="event-zone-chip event-label-chip">{label.label}</span>
                      <span class="field-value">{label.count}</span>
                    </div>
                  )}
                </For>
              </div>
            </Show>
          </div>
        </Show>
      </Show>
    </SettingsCard>
  )
}
