import { createEffect, createMemo, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import type { DetectionEvent, RecordingSession, RecordingTrigger } from '../../../../../types'
import { getDetectionEvents, getRecordingSessions } from '../../../../../api/client'
import { dayLabel } from '../../../../../gallery/groupByDay'
import { openMedia } from '../../../../../lib/viewerStore'
import {
  dayStartMs,
  eventMarkers,
  laneCount,
  layoutTimeline,
  parseSessions,
  shiftDayClamped,
  todayKey,
  type TimelineSegment,
} from '../../../../../lib/timeline'
import { dayGroupLabel, formatTime, t, tCount } from '../../../../../lib/i18n'
import { Panel } from '../../../ui'

const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21, 24]

/** How often the now-line re-reads the clock (it only moves by minutes). */
const NOW_TICK_MS = 30_000

/** The day-scoped marker fetch budget — a full day of events, not one page. */
const EVENT_MARKERS_LIMIT = 200

const TRIGGER_LEGEND: { trigger: RecordingTrigger; label: () => string }[] = [
  { trigger: 'manual', label: () => t('timeline.legend.manual') },
  { trigger: 'motion', label: () => t('timeline.legend.motion') },
  { trigger: 'sound', label: () => t('timeline.legend.sound') },
  { trigger: 'continuous', label: () => t('timeline.legend.continuous') },
  { trigger: 'scheduled', label: () => t('timeline.legend.scheduled') },
  { trigger: 'interval', label: () => t('timeline.legend.interval') },
]

function timeLabelMs(ms: number): string {
  return formatTime(ms, { hour: '2-digit', minute: '2-digit' })
}

function segmentTitle(seg: TimelineSegment): string {
  const span = `${timeLabelMs(seg.startMs)}–${timeLabelMs(seg.endMs)}`
  const clip = seg.mediaId != null ? t('timeline.segment.openClip') : t('timeline.segment.noClip')
  return `${seg.trigger} — ${span} — ${clip}`
}

function segmentStyle(seg: TimelineSegment): JSX.CSSProperties {
  return {
    left: `${seg.startPct}%`,
    width: `${Math.max(seg.widthPct, 0.15)}%`,
    '--timeline-lane': `${seg.lane}`,
  }
}

function openSegment(seg: TimelineSegment) {
  if (seg.mediaId != null) openMedia(seg.mediaId, { type: 'VIDEO' })
}

/**
 * NVR day view, ported from the classic RecordingTimeline onto the kit:
 * one horizontal 24h track of recording sessions (GET
 * /api/recordings/sessions?day=YYYY-MM-DD — newer than some server builds,
 * so a failed fetch degrades to a "no session data" note instead of an
 * error), with detection-event markers for the same local day overlaid from
 * the existing /api/detection/events feed. The marker fetch sends
 * ?day=YYYY-MM-DD (newer servers scope it server-side; older builds that
 * reject or ignore the param fall back to the default page, where the
 * client-side day filter in eventMarkers still narrows the markers). All
 * the positioning math — percent mapping, midnight clamping, overlap lanes,
 * day bucketing — lives in the pure lib/timeline module; segments with a
 * linked mediaId open the shared in-dashboard media viewer, like the event
 * feed's clip buttons. The timeline bar DOM (day picker, legend, track,
 * markers, ticks) keeps its classic classes, restyled as a data
 * visualization in views/cards.css (hairline track, REC segments in the
 * signal magenta, cyan now-line, mono ticks); only the card wrapper and
 * the note banners moved to the kit.
 */
export default function RecordingTimelinePanel() {
  const [day, setDay] = createSignal(todayKey())
  const [sessions, setSessions] = createSignal<RecordingSession[] | null>(null)
  const [noSessionData, setNoSessionData] = createSignal(false)
  const [loading, setLoading] = createSignal(true)
  const [events, setEvents] = createSignal<DetectionEvent[]>([])

  async function loadSessions(forDay: string) {
    setLoading(true)
    try {
      const res = await getRecordingSessions(forDay)
      // A day flip during the fetch must not paint the stale day's sessions.
      if (day() !== forDay) return
      setSessions(parseSessions(res))
      setNoSessionData(false)
    } catch {
      if (day() !== forDay) return
      setSessions([])
      setNoSessionData(true)
    } finally {
      if (day() === forDay) setLoading(false)
    }
  }

  // Markers for the selected day: send ?day= so day-scoped servers return
  // the right window; anything else (older builds erroring on the param,
  // ignored params) degrades to the default page where the pure
  // client-side day filter still picks the visible markers.
  async function loadEvents(forDay: string) {
    try {
      const res = await getDetectionEvents(EVENT_MARKERS_LIMIT, undefined, forDay)
      if (day() !== forDay) return
      setEvents(res.events ?? [])
    } catch {
      try {
        const res = await getDetectionEvents(EVENT_MARKERS_LIMIT)
        if (day() !== forDay) return
        setEvents(res.events ?? [])
      } catch {
        if (day() === forDay) setEvents([])
      }
    }
  }

  onMount(() => {
    void loadSessions(day())
  })

  // The day signal drives both fetches, so ‹ › steps refresh everything.
  createEffect(() => {
    const current = day()
    void loadEvents(current)
  })

  function changeDay(deltaDays: number) {
    const next = shiftDayClamped(day(), deltaDays, todayKey())
    if (next === day()) return
    setDay(next)
    void loadSessions(next)
  }

  const trackStart = createMemo(() => dayStartMs(day()) ?? 0)
  const segments = createMemo(() => (sessions() ? layoutTimeline(sessions()!, trackStart()) : []))
  const markers = createMemo(() => eventMarkers(events(), day()))
  const lanes = createMemo(() => laneCount(segments()))
  // The wall clock, timer-backed: a bare Date.now() in the markup would
  // paint once and never move — a signal makes the now-line (and the
  // today check, which can flip at midnight) re-evaluate as time passes.
  const [nowMs, setNowMs] = createSignal(Date.now())
  onMount(() => {
    const nowTick = setInterval(() => setNowMs(Date.now()), NOW_TICK_MS)
    onCleanup(() => clearInterval(nowTick))
  })
  // The now-line's position as a percent of the 24h track, clamped to it.
  // Only meaningful for today — past days have no "now".
  const nowPct = () => {
    const pct = ((nowMs() - trackStart()) / 86_400_000) * 100
    return Math.min(100, Math.max(0, pct))
  }

  return (
    <Panel title={t('timeline.title')}>
      <div class="field-group">
        <div class="field-row">
          <div class="timeline-day-picker" role="group" aria-label={t('timeline.dayAria')}>
          <button
            type="button"
            class="timeline-day-btn"
            onClick={() => changeDay(-1)}
            title={t('timeline.prevDay')}
          >
            ‹
          </button>
          <span class="timeline-day-label">{dayGroupLabel(dayLabel(day(), nowMs()))}</span>
          <button
            type="button"
            class="timeline-day-btn"
            onClick={() => changeDay(1)}
            disabled={day() === todayKey()}
            title={t('timeline.nextDay')}
          >
            ›
          </button>
        </div>
        <span class="nx-mono">{tCount('timeline.sessions', sessions()?.length ?? 0)}</span>
        </div>
      </div>

      <div class="timeline-legend" aria-hidden="true">
        <For each={TRIGGER_LEGEND}>
          {({ trigger, label }) => (
            <span class={`timeline-legend-item timeline-trigger-${trigger}`}>
              <span class="timeline-legend-swatch" />
              {label()}
            </span>
          )}
        </For>
        <span class="timeline-legend-item">
          <span class="timeline-legend-swatch timeline-marker-swatch" />
          {t('timeline.detections')}
        </span>
      </div>

      <div class="timeline-wrap">
        <div class="timeline-track" style={{ height: `${lanes() * 14 + 8}px` }}>
          <Show when={day() === todayKey(nowMs())}>
            <div class="timeline-now" style={{ left: `${nowPct()}%` }} aria-hidden="true" />
          </Show>
          <For each={segments()}>
            {(seg) => (
              <Show
                when={seg.mediaId != null}
                fallback={
                  <span
                    class={`timeline-segment timeline-trigger-${seg.trigger}`}
                    style={segmentStyle(seg)}
                    title={segmentTitle(seg)}
                  />
                }
              >
                <button
                  type="button"
                  class={`timeline-segment timeline-trigger-${seg.trigger} timeline-segment-link`}
                  onClick={() => openSegment(seg)}
                  style={segmentStyle(seg)}
                  title={segmentTitle(seg)}
                />
              </Show>
            )}
          </For>
        </div>

        <div class="timeline-markers">
          <For each={markers()}>
            {(marker) => (
              <span
                class={`timeline-marker timeline-marker-${marker.type}`}
                style={{ left: `${marker.pct}%` }}
                title={`${marker.type} — ${timeLabelMs(marker.timestampMs)}`}
              />
            )}
          </For>
        </div>

        <div class="timeline-ticks">
          <For each={HOUR_TICKS}>
            {(hour) => (
              <span class="timeline-tick" style={{ left: `${(hour / 24) * 100}%` }}>
                {String(hour).padStart(2, '0')}
              </span>
            )}
          </For>
        </div>
      </div>

      <Show when={!loading()} fallback={
        <div class="nx-banner nx-banner-info" role="note">
          {t('timeline.loading')}
        </div>
      }>
        <Show when={!noSessionData()} fallback={
          <div class="nx-banner nx-banner-info" role="note">
            {t('timeline.noSessionData')}
          </div>
        }>
          <Show when={(sessions()?.length ?? 0) > 0} fallback={
            <div class="nx-banner nx-banner-info" role="note">
              {t('timeline.noRecordings')}
            </div>
          }>
            <div class="nx-banner nx-banner-info" role="note">
              {t('timeline.hint')}
            </div>
          </Show>
        </Show>
      </Show>
    </Panel>
  )
}
