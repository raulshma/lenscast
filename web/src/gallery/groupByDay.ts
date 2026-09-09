import type { GalleryItem } from '../types'

// Pure capture-day grouping for the gallery grid — inject `now` so vitest
// can pin Today/Yesterday without a clock. All keys/labels are local-time.

export interface GalleryDayGroup {
  /** Local `YYYY-MM-DD` capture day. */
  key: string
  /** "Today", "Yesterday", or a locale date label. */
  label: string
  items: GalleryItem[]
}

export interface GalleryDayOption {
  key: string
  label: string
}

/** Local calendar day of a millisecond timestamp, as `YYYY-MM-DD`. */
export function dayKeyFor(timestampMs: number): string {
  const d = new Date(timestampMs)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

function dayKeyOffset(now: number, offsetDays: number): string {
  const d = new Date(now)
  d.setDate(d.getDate() + offsetDays)
  return dayKeyFor(d.getTime())
}

/** Human label for a capture-day key relative to `now` (local time). */
export function dayLabel(key: string, now: number): string {
  if (key === dayKeyOffset(now, 0)) return 'Today'
  if (key === dayKeyOffset(now, -1)) return 'Yesterday'
  const [y, m, d] = key.split('-').map(Number)
  if (!y || !m || !d) return key
  return new Date(y, m - 1, d).toLocaleDateString(undefined, {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
  })
}

/**
 * Group items into capture-day sections. Group order follows first
 * appearance (the API returns newest-first, so today's group leads);
 * items keep their relative order inside a group.
 */
export function groupGalleryByDay(items: GalleryItem[], now: number): GalleryDayGroup[] {
  const groups = new Map<string, GalleryDayGroup>()
  for (const item of items) {
    const key = dayKeyFor(item.timestamp)
    let group = groups.get(key)
    if (!group) {
      group = { key, label: dayLabel(key, now), items: [] }
      groups.set(key, group)
    }
    group.items.push(item)
  }
  return [...groups.values()]
}

/** Distinct capture days in grid order, for the date-jump select. */
export function listCaptureDays(items: GalleryItem[], now: number): GalleryDayOption[] {
  return groupGalleryByDay(items, now).map(({ key, label }) => ({ key, label }))
}
