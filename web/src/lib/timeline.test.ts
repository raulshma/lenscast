import { describe, expect, it } from 'vitest'
import type { DetectionEvent, RecordingSession } from '../types'
import {
  clampToDay,
  dayStartMs,
  eventMarkers,
  isAfterDay,
  laneCount,
  layoutTimeline,
  parseDayKey,
  parseSessions,
  percentOfDay,
  shiftDayClamped,
  shiftDayKey,
  todayKey,
} from './timeline'

// Fixed day under test: Wednesday 2026-09-09, local time.
const DAY = '2026-09-09'
const DAY_START = new Date(2026, 8, 9).getTime()
const at = (hour: number, minute = 0) => DAY_START + hour * 3_600_000 + minute * 60_000

function session(id: string, startMs: number, endMs: number, trigger: RecordingSession['trigger'] = 'motion', mediaId: string | null = null): RecordingSession {
  return { id, startMs, endMs, trigger, mediaId }
}

function event(id: string, timestampMs: number, type: DetectionEvent['type'] = 'motion'): DetectionEvent {
  return { id, type, source: 'camera', timestampMs, dispatchedActions: [], zones: [] }
}

describe('day keys', () => {
  it('parses well-formed keys and rejects malformed ones', () => {
    expect(parseDayKey('2026-09-09')).toEqual({ year: 2026, month: 9, day: 9 })
    expect(parseDayKey('2026-13-01')).toBeNull()
    expect(parseDayKey('not-a-day')).toBeNull()
    expect(parseDayKey('2026-9-9')).toBeNull()
  })

  it('dayStartMs is local midnight', () => {
    expect(dayStartMs(DAY)).toBe(new Date(2026, 8, 9, 0, 0, 0, 0).getTime())
    expect(dayStartMs('bogus')).toBeNull()
  })

  it('shiftDayKey crosses month and year boundaries', () => {
    expect(shiftDayKey('2026-09-09', -1)).toBe('2026-09-08')
    expect(shiftDayKey('2026-09-01', -1)).toBe('2026-08-31')
    expect(shiftDayKey('2026-12-31', 1)).toBe('2027-01-01')
    expect(shiftDayKey('2027-01-01', -1)).toBe('2026-12-31')
    // Feb 29 through a leap year (2028).
    expect(shiftDayKey('2028-02-28', 1)).toBe('2028-02-29')
    expect(shiftDayKey('oops', 1)).toBe('oops')
  })

  it('shiftDayClamped never steps past today', () => {
    expect(shiftDayClamped('2026-09-08', 1, '2026-09-09')).toBe('2026-09-09')
    expect(shiftDayClamped('2026-09-09', 1, '2026-09-09')).toBe('2026-09-09')
    expect(shiftDayClamped('2026-09-07', -1, '2026-09-09')).toBe('2026-09-06')
    // Going back is never clamped.
    expect(shiftDayClamped('2026-09-09', -30, '2026-09-09')).toBe('2026-08-10')
  })

  it('isAfterDay orders calendar days', () => {
    expect(isAfterDay('2026-09-10', '2026-09-09')).toBe(true)
    expect(isAfterDay('2026-09-09', '2026-09-10')).toBe(false)
    expect(isAfterDay('2026-09-09', '2026-09-09')).toBe(false)
  })

  it('todayKey derives from the injected clock', () => {
    expect(todayKey(new Date(2026, 8, 9, 23, 59).getTime())).toBe('2026-09-09')
  })
})

describe('percent mapping', () => {
  it('maps midnight→0, noon→50, next midnight→100', () => {
    expect(percentOfDay(at(0), DAY_START)).toBe(0)
    expect(percentOfDay(at(12), DAY_START)).toBeCloseTo(50)
    expect(percentOfDay(at(24), DAY_START)).toBe(100)
  })

  it('clamps out-of-day timestamps into the track', () => {
    expect(percentOfDay(at(-3), DAY_START)).toBe(0)
    expect(percentOfDay(at(30), DAY_START)).toBe(100)
  })
})

describe('segment clamping', () => {
  it('keeps an inside-day session unchanged', () => {
    expect(clampToDay(session('a', at(10), at(11)), DAY_START)).toEqual({ startMs: at(10), endMs: at(11) })
  })

  it('clamps sessions crossing either midnight', () => {
    // Started yesterday 23:00, ran to 01:00 today.
    expect(clampToDay(session('a', at(-1), at(1)), DAY_START)).toEqual({ startMs: DAY_START, endMs: at(1) })
    // Ran 23:00 today into tomorrow.
    expect(clampToDay(session('b', at(23), at(25)), DAY_START)).toEqual({ startMs: at(23), endMs: DAY_START + 86_400_000 })
  })

  it('drops sessions entirely outside the day and inverted/empty ranges', () => {
    expect(clampToDay(session('early', at(-5), at(-1)), DAY_START)).toBeNull()
    expect(clampToDay(session('late', at(25), at(30)), DAY_START)).toBeNull()
    expect(clampToDay(session('inverted', at(10), at(10)), DAY_START)).toBeNull()
    expect(clampToDay(session('backwards', at(11), at(10)), DAY_START)).toBeNull()
  })
})

describe('timeline layout', () => {
  it('positions segments by percent of the day', () => {
    const segments = layoutTimeline([session('a', at(6), at(7, 30))], DAY_START)
    expect(segments).toHaveLength(1)
    expect(segments[0].startPct).toBeCloseTo(25)
    expect(segments[0].widthPct).toBeCloseTo(6.25)
    expect(segments[0].lane).toBe(0)
  })

  it('stacks overlapping sessions into separate lanes', () => {
    const segments = layoutTimeline([
      session('a', at(1), at(5)),
      session('b', at(2), at(3)), // inside a's span → new lane
      session('c', at(4), at(6)), // still overlaps a → lane with b free? b ended at 3 → reuse lane 1
      session('d', at(6), at(7)), // a ended at 5 → lane 0
    ], DAY_START)
    const byId = Object.fromEntries(segments.map((s) => [s.id, s.lane]))
    expect(byId).toEqual({ a: 0, b: 1, c: 1, d: 0 })
    expect(laneCount(segments)).toBe(2)
  })

  it('clamps cross-midnight sessions to the track edges', () => {
    const segments = layoutTimeline([session('cont', at(-2), at(3), 'continuous')], DAY_START)
    expect(segments[0].startPct).toBe(0)
    expect(segments[0].widthPct).toBeCloseTo(12.5)
  })

  it('drops invalid sessions and sorts the rest by start', () => {
    const segments = layoutTimeline([
      session('late', at(20), at(21)),
      session('bad', at(22), at(22)),
      session('early', at(8), at(9)),
    ], DAY_START)
    expect(segments.map((s) => s.id)).toEqual(['early', 'late'])
  })

  it('laneCount of an empty track is one row', () => {
    expect(laneCount([])).toBe(1)
  })
})

describe('event day bucketing', () => {
  it('keeps only events from the requested local day and positions them', () => {
    const markers = eventMarkers([
      event('in1', at(6)),
      event('in2', at(18)),
      event('yesterday', at(23) - 86_400_000),
      event('tomorrow', at(1) + 86_400_000),
    ], DAY)
    expect(markers.map((m) => m.id)).toEqual(['in1', 'in2'])
    expect(markers[0].pct).toBeCloseTo(25)
    expect(markers[1].pct).toBeCloseTo(75)
  })

  it('returns nothing for a malformed day key', () => {
    expect(eventMarkers([event('x', at(6))], 'bogus')).toEqual([])
  })
})

describe('payload validation', () => {
  it('parses a well-formed payload, sorting by start, defaulting mediaId to null', () => {
    const parsed = parseSessions({
      sessions: [
        { id: 'b', startMs: at(12), endMs: at(13), trigger: 'manual', mediaId: '42' },
        { id: 'a', startMs: at(8), endMs: at(9), trigger: 'continuous' },
      ],
    })
    expect(parsed.map((s) => s.id)).toEqual(['a', 'b'])
    expect(parsed[0].mediaId).toBeNull()
    expect(parsed[1].mediaId).toBe('42')
  })

  it('drops malformed rows and non-array bodies', () => {
    expect(parseSessions({ sessions: [
      { id: 'ok', startMs: at(1), endMs: at(2), trigger: 'scheduled' },
      { id: 7, startMs: at(1), endMs: at(2), trigger: 'manual' },
      { startMs: at(1), endMs: at(2), trigger: 'manual' },
      { id: 'no-range', startMs: at(2), endMs: at(1), trigger: 'manual' },
      { id: 'bad-trigger', startMs: at(1), endMs: at(2), trigger: 'vibration' },
      'junk',
      null,
    ] })).toEqual([expect.objectContaining({ id: 'ok' })])
    expect(parseSessions(null)).toEqual([])
    expect(parseSessions({})).toEqual([])
    expect(parseSessions('nope')).toEqual([])
  })
})
