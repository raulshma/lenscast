import type { DailyCount, DetectionEventType, DetectionStats } from '../types'

// Pure view-model mapping for DetectionStatsCard — the pollLadder pattern:
// no fetches or signals, just reshaping GET /api/detection/stats for the
// window chips, the seven-day bar chart and the top lists, so vitest can
// pin every mapping without a browser.

export type StatsWindow = '24h' | '7d' | 'all-time'

export const STATS_WINDOWS: { value: StatsWindow; label: string }[] = [
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: 'all-time', label: 'All time' },
]

const KNOWN_TYPES: readonly DetectionEventType[] = ['motion', 'sound', 'tamper']

function countsForWindow(stats: DetectionStats, window: StatsWindow): Record<string, number> {
  if (window === '24h') return stats.last24h ?? {}
  if (window === '7d') return stats.last7d ?? {}
  return stats.allTime ?? {}
}

/** One per-type count row for the selected window. */
export interface TypeCount {
  type: string
  count: number
}

/**
 * Per-type counts for a window: the known detection types in fixed order
 * (zeros kept so the rows never reshuffle when a type goes quiet), then any
 * extra server-side keys alphabetically so unknown types still surface.
 */
export function windowCounts(stats: DetectionStats, window: StatsWindow): TypeCount[] {
  const counts = countsForWindow(stats, window)
  const rows: TypeCount[] = KNOWN_TYPES.map((type) => ({ type, count: counts[type] ?? 0 }))
  const extras = Object.keys(counts)
    .filter((key) => !(KNOWN_TYPES as readonly string[]).includes(key))
    .sort((a, b) => a.localeCompare(b))
  for (const key of extras) rows.push({ type: key, count: counts[key] ?? 0 })
  return rows
}

/** Sum of a window's per-type counts. */
export function windowTotal(stats: DetectionStats, window: StatsWindow): number {
  return windowCounts(stats, window).reduce((sum, row) => sum + row.count, 0)
}

function parseKey(key: string): { year: number; month: number; day: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key)
  if (!match) return null
  return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) }
}

/**
 * Zero-fill the server's per-day series to exactly seven consecutive local
 * days ending at `todayKey` (oldest first). The stats payload only carries
 * days that had events, but the chart must draw every column; days beyond
 * the seven-day window are dropped.
 */
export function sevenDaySeries(perDay: DailyCount[] | undefined, todayKey: string): DailyCount[] {
  const byDay = new Map<string, number>()
  for (const entry of perDay ?? []) {
    if (parseKey(entry.day)) byDay.set(entry.day, entry.count)
  }
  const today = parseKey(todayKey)
  if (!today) return []
  const series: DailyCount[] = []
  for (let offset = -6; offset <= 0; offset++) {
    const d = new Date(today.year, today.month - 1, today.day + offset)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    series.push({ day: key, count: byDay.get(key) ?? 0 })
  }
  return series
}
