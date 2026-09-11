import type { DetectionEvent, DetectionEventType, RecordingSession, RecordingTrigger } from '../types'
import { dayKeyFor } from '../gallery/groupByDay'

// Pure math for the NVR RecordingTimeline — the pollLadder pattern: no
// fetches, signals or DOM here, only day keys, percent mapping and lane
// layout, so vitest can drive every edge (midnight clamp, overlap, day
// bucketing) without a browser. The Solid wiring lives in
// RecordingTimeline.tsx.

export const MS_PER_DAY = 86_400_000

/** Local calendar day keys the picker steps through, as `YYYY-MM-DD`. */
export function todayKey(now: number = Date.now()): string {
  return dayKeyFor(now)
}

/** Parse a `YYYY-MM-DD` key into local calendar parts, or null when malformed. */
export function parseDayKey(key: string): { year: number; month: number; day: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  return { year, month, day }
}

/** Local midnight of a day key in ms, or null when the key is malformed. */
export function dayStartMs(key: string): number | null {
  const parts = parseDayKey(key)
  if (!parts) return null
  return new Date(parts.year, parts.month - 1, parts.day).getTime()
}

/** The day key `deltaDays` away from `key`; a malformed key returns itself. */
export function shiftDayKey(key: string, deltaDays: number): string {
  const parts = parseDayKey(key)
  if (!parts) return key
  const shifted = new Date(parts.year, parts.month - 1, parts.day + deltaDays)
  return dayKeyFor(shifted.getTime())
}

/** The picker's forward step, clamped so it can never move past today. */
export function shiftDayClamped(key: string, deltaDays: number, today: string): string {
  const next = shiftDayKey(key, deltaDays)
  return isAfterDay(next, today) ? today : next
}

/** True when day `a` is strictly later than day `b` (both local calendar keys). */
export function isAfterDay(a: string, b: string): boolean {
  const startA = dayStartMs(a)
  const startB = dayStartMs(b)
  if (startA === null || startB === null) return false
  return startA > startB
}

/** A timestamp's position across the day, clamped to 0..100 percent. */
export function percentOfDay(timestampMs: number, dayStart: number): number {
  const pct = ((timestampMs - dayStart) / MS_PER_DAY) * 100
  if (!Number.isFinite(pct)) return 0
  return Math.min(100, Math.max(0, pct))
}

/** One session clamped to the 24h window starting at `dayStart`. */
export interface ClampedSpan {
  startMs: number
  endMs: number
}

/**
 * Clip a session's [startMs, endMs) to the day window; null when the session
 * does not overlap the day at all or carries an inverted/empty range.
 */
export function clampToDay(session: { startMs: number; endMs: number }, dayStart: number): ClampedSpan | null {
  if (!(session.endMs > session.startMs)) return null
  const start = Math.max(session.startMs, dayStart)
  const end = Math.min(session.endMs, dayStart + MS_PER_DAY)
  if (end <= start) return null
  return { startMs: start, endMs: end }
}

/** A session positioned on the 24h track; `lane` stacks overlapping bars. */
export interface TimelineSegment {
  id: string
  trigger: RecordingTrigger
  mediaId: string | null
  /** Clamped span actually shown on this day (for tooltips/labels). */
  startMs: number
  endMs: number
  /** Left edge, percent of the 24h track (0..100). */
  startPct: number
  /** Bar width, percent of the 24h track (>0). */
  widthPct: number
  /** Zero-based stacking row; overlapping sessions get distinct lanes. */
  lane: number
}

/**
 * Map sessions onto the 24h track: clamp to the day window, drop the parts
 * outside it, and lay overlapping sessions into greedily assigned lanes
 * (earliest-fit by start time, like a schedule view). Segments sort by
 * start, then width, so rendering order is stable.
 */
export function layoutTimeline(sessions: RecordingSession[], dayStart: number): TimelineSegment[] {
  const spans = sessions
    .map((session) => ({ session, span: clampToDay(session, dayStart) }))
    .filter((entry): entry is { session: RecordingSession; span: ClampedSpan } => entry.span !== null)

  spans.sort((a, b) => {
    if (a.span.startMs !== b.span.startMs) return a.span.startMs - b.span.startMs
    return (b.span.endMs - b.span.startMs) - (a.span.endMs - a.span.startMs)
  })

  // Greedy earliest-fit lanes: one occupied-until stamp per lane.
  const laneEnds: number[] = []
  const segments: TimelineSegment[] = spans.map(({ session, span }) => {
    let lane = laneEnds.findIndex((end) => span.startMs >= end)
    if (lane === -1) {
      lane = laneEnds.length
      laneEnds.push(span.endMs)
    } else {
      laneEnds[lane] = span.endMs
    }
    return {
      id: session.id,
      trigger: session.trigger,
      mediaId: session.mediaId,
      startMs: span.startMs,
      endMs: span.endMs,
      startPct: percentOfDay(span.startMs, dayStart),
      widthPct: ((span.endMs - span.startMs) / MS_PER_DAY) * 100,
      lane,
    }
  })
  return segments
}

/** Rows the track needs so no two overlapping bars share a lane. */
export function laneCount(segments: TimelineSegment[]): number {
  return segments.reduce((max, s) => Math.max(max, s.lane + 1), 1)
}

/** A detection event positioned on the 24h track for the marker overlay. */
export interface EventMarker {
  id: string
  type: DetectionEventType
  timestampMs: number
  /** Marker position, percent of the 24h track (0..100). */
  pct: number
}

/**
 * Bucket events to the local calendar day (client-side filter — the events
 * endpoint has no day param) and map each to its percent position.
 */
export function eventMarkers(events: DetectionEvent[], dayKey: string): EventMarker[] {
  const start = dayStartMs(dayKey)
  if (start === null) return []
  return events
    .filter((event) => dayKeyFor(event.timestampMs) === dayKey)
    .map((event) => ({
      id: event.id,
      type: event.type,
      timestampMs: event.timestampMs,
      pct: percentOfDay(event.timestampMs, start),
    }))
}

const KNOWN_TRIGGERS: readonly RecordingTrigger[] = ['manual', 'motion', 'sound', 'continuous', 'scheduled']

function isTrigger(value: unknown): value is RecordingTrigger {
  return typeof value === 'string' && (KNOWN_TRIGGERS as readonly string[]).includes(value)
}

/**
 * Validate a raw /api/recordings/sessions payload into well-formed sessions:
 * keeps rows with a string id, a properly increasing ms range and a known
 * trigger (mediaId tolerated as string or null, absent → null), drops
 * everything else, sorts by start. The endpoint is newer than some servers,
 * so a malformed body must degrade to an empty day instead of breaking the
 * card. (layoutTimeline still re-guards the range — defense in depth for
 * direct callers.)
 */
export function parseSessions(raw: unknown): RecordingSession[] {
  if (raw === null || typeof raw !== 'object') return []
  const rows = (raw as { sessions?: unknown }).sessions
  if (!Array.isArray(rows)) return []
  const sessions: RecordingSession[] = []
  for (const row of rows) {
    if (row === null || typeof row !== 'object') continue
    const r = row as Record<string, unknown>
    if (typeof r.id !== 'string') continue
    if (typeof r.startMs !== 'number' || typeof r.endMs !== 'number') continue
    if (!(r.endMs > r.startMs)) continue
    if (!isTrigger(r.trigger)) continue
    const mediaId = typeof r.mediaId === 'string' ? r.mediaId : null
    sessions.push({ id: r.id, startMs: r.startMs, endMs: r.endMs, trigger: r.trigger, mediaId })
  }
  sessions.sort((a, b) => a.startMs - b.startMs)
  return sessions
}
