import { createResource, createSignal, For, Show } from 'solid-js'
import type { DailyCount } from '../../../../types'
import { getDetectionStats } from '../../../../api/client'
import { STATS_WINDOWS, sevenDaySeries, windowCounts, windowTotal, type StatsWindow } from '../../../../lib/detectionStats'
import { todayKey } from '../../../../lib/timeline'
import { formatDate, t, tCount } from '../../../../lib/i18n'
import { Panel, Segmented, Skeleton, FieldRow, Empty } from '../../ui'

function weekdayLabel(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  if (!y || !m || !d) return day
  return formatDate(new Date(y, m - 1, d), { weekday: 'narrow' })
}

/** Golos label for a metric row, reusing the event-filter strings. */
function typeLabel(type: string): string {
  switch (type) {
    case 'motion': return t('events.filter.motion')
    case 'sound': return t('events.filter.sound')
    case 'tamper': return t('events.filter.tamper')
    default: return type
  }
}

/**
 * Aggregate detection statistics, ported from the classic DetectionStatsCard:
 * a 24h/7d/all-time window over the per-type counts, the zero-filled
 * seven-day bar chart, and the top zones / ML labels. The payload carries
 * all three windows at once, so the toggle never refetches.
 *
 * The composition varies by content rather than repeating a stat-card
 * grid: the lead metric speaks in one big tabular numeral, the remaining
 * types share a compact column row, the total runs quiet under a
 * hairline, and the seven-day sparkline sits beside them ≥900px.
 */
export default function StatsPanel() {
  const [stats] = createResource(() => getDetectionStats())
  const [window, setWindow] = createSignal<StatsWindow>('7d')

  const counts = () => (stats() ? windowCounts(stats()!, window()) : [])
  const bars = () => (stats() ? sevenDaySeries(stats()!.perDay, todayKey()) : [])
  const maxCount = () => Math.max(1, ...bars().map((d: DailyCount) => d.count))

  const windowOptions = () =>
    STATS_WINDOWS.map(({ value, label }) => ({
      value,
      label: label === 'All time' ? t('stats.window.all') : label,
    }))

  return (
    <Panel
      title={t('stats.title')}
      actions={
        <Show when={stats()}>
          <Segmented
            options={windowOptions()}
            value={window()}
            onChange={(v) => setWindow(v as StatsWindow)}
            ariaLabel={t('stats.windowAria')}
          />
        </Show>
      }
    >
      <Show
        when={stats()}
        fallback={
          <Show when={stats.error} fallback={
            <>
              <Skeleton h="52px" />
              <Skeleton h="72px" />
            </>
          }>
            <Empty title={t('stats.unavailable')} />
          </Show>
        }
      >
        <div class="nx-stats-rows">
          <div class="nx-stats-metrics">
            <For each={counts()}>
              {(row, index) => (
                <div class="nx-stats-row" classList={{ 'nx-stats-row-lead': index() === 0 }}>
                  <span class="nx-stats-count nx-mono">{row.count}</span>
                  <span class="nx-stat-label">{typeLabel(row.type)}</span>
                </div>
              )}
            </For>
            <div class="nx-stats-total nx-mono">{t('stats.total', { count: windowTotal(stats()!, window()) })}</div>
          </div>

          <div class="nx-bars-block">
            <span class="nx-label">{t('stats.last7')}</span>
            <div class="nx-bars" role="img" aria-label={t('stats.perDayAria')}>
              <For each={bars()}>
                {(day) => (
                  <div class="nx-bar-col" title={`${day.day}: ${tCount('stats.eventCount', day.count)}`}>
                    <div class="nx-bars-plot">
                      <div
                        class="nx-bar"
                        classList={{ 'nx-bar-zero': day.count === 0 }}
                        style={{ height: `${Math.round((day.count / maxCount()) * 100)}%` }}
                      />
                    </div>
                    <span class="nx-bar-count">
                      <Show when={day.count > 0}>{day.count}</Show>
                    </span>
                    <span class="nx-bar-label">{weekdayLabel(day.day)}</span>
                  </div>
                )}
              </For>
            </div>
          </div>
        </div>

        <Show when={stats()!.topZones.length > 0 || stats()!.topLabels.length > 0}>
          <div class="nx-stats-top">
            <Show when={stats()!.topZones.length > 0}>
              <div class="nx-stats-top-list">
                <span class="nx-label">{t('stats.topZones')}</span>
                <For each={stats()!.topZones}>
                  {(zone) => (
                    <FieldRow label={zone.label}>
                      <span class="nx-mono nx-info-value">{zone.count}</span>
                    </FieldRow>
                  )}
                </For>
              </div>
            </Show>
            <Show when={stats()!.topLabels.length > 0}>
              <div class="nx-stats-top-list">
                <span class="nx-label">{t('stats.topLabels')}</span>
                <For each={stats()!.topLabels}>
                  {(label) => (
                    <FieldRow label={label.label}>
                      <span class="nx-mono nx-info-value">{label.count}</span>
                    </FieldRow>
                  )}
                </For>
              </div>
            </Show>
          </div>
        </Show>
      </Show>
    </Panel>
  )
}
