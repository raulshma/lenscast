import { createMemo, createSignal, For, onMount, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import type { DetectionEvent, RecordingSession, RecordingTrigger } from '../types'
import { getDetectionEvents, getRecordingSessions } from '../api/client'
import { dayLabel } from '../gallery/groupByDay'
import {
  dayStartMs,
  eventMarkers,
  laneCount,
  layoutTimeline,
  parseSessions,
  shiftDayClamped,
  todayKey,
  type TimelineSegment,
} from '../lib/timeline'
import SettingsCard from './SettingsCard'

const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21, 24]

const TRIGGER_LEGEND: { trigger: RecordingTrigger; label: string }[] = [
  { trigger: 'manual', label: 'Manual' },
  { trigger: 'motion', label: 'Motion' },
  { trigger: 'sound', label: 'Sound' },
  { trigger: 'continuous', label: 'Continuous' },
  { trigger: 'scheduled', label: 'Scheduled' },
]

function timeLabel(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function segmentTitle(seg: TimelineSegment): string {
  const span = `${timeLabel(seg.startMs)}–${timeLabel(seg.endMs)}`
  const clip = seg.mediaId != null ? ' — click to open the clip' : ' — no clip linked'
  return `${seg.trigger} · ${span}${clip}`
}

function segmentStyle(seg: TimelineSegment): JSX.CSSProperties {
  return {
    left: `${seg.startPct}%`,
    width: `${Math.max(seg.widthPct, 0.15)}%`,
    '--timeline-lane': `${seg.lane}`,
  }
}

/**
 * NVR day view: one horizontal 24h track of recording sessions (GET
 * /api/recordings/sessions?day=YYYY-MM-DD — newer than some server builds,
 * so a failed fetch degrades to a "no session data" note instead of an
 * error), with detection-event markers for the same local day overlaid from
 * the existing /api/detection/events feed (filtered client-side — that
 * endpoint has no day param). All the positioning math — percent mapping,
 * midnight clamping, overlap lanes, day bucketing — lives in the pure
 * lib/timeline module; segments with a linked mediaId open GET /api/media/{id}
 * in a new tab like EventFeed's clip links.
 */
export default function RecordingTimeline() {
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

  onMount(() => {
    void loadSessions(day())
    // Best-effort overlay: the server's default event page is plenty for a
    // single day; a failure just leaves the markers off.
    void getDetectionEvents().then((r) => setEvents(r.events ?? [])).catch(() => { })
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

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="M3 10h18" />
          <path d="M8 5v14" />
          <path d="M16 5v14" />
          <path d="M3 15h18" />
        </svg>
      }
      title="Recording Timeline"
    >
      <div class="field-group">
        <div class="field-row">
          <div class="timeline-day-picker" role="group" aria-label="Timeline day">
            <button
              type="button"
              class="timeline-day-btn"
              onClick={() => changeDay(-1)}
              title="Previous day"
            >
              ‹
            </button>
            <span class="timeline-day-label">{dayLabel(day(), Date.now())}</span>
            <button
              type="button"
              class="timeline-day-btn"
              onClick={() => changeDay(1)}
              disabled={day() === todayKey()}
              title="Next day"
            >
              ›
            </button>
          </div>
          <span class="field-value">{sessions()?.length ?? 0} session{(sessions()?.length ?? 0) === 1 ? '' : 's'}</span>
        </div>

        <div class="timeline-legend" aria-hidden="true">
          <For each={TRIGGER_LEGEND}>
            {({ trigger, label }) => (
              <span class={`timeline-legend-item timeline-trigger-${trigger}`}>
                <span class="timeline-legend-swatch" />
                {label}
              </span>
            )}
          </For>
          <span class="timeline-legend-item">
            <span class="timeline-legend-swatch timeline-marker-swatch" />
            Detections
          </span>
        </div>
      </div>

      <div class="timeline-wrap">
        <div class="timeline-track" style={{ height: `${lanes() * 14 + 8}px` }}>
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
                <a
                  class={`timeline-segment timeline-trigger-${seg.trigger} timeline-segment-link`}
                  href={`/api/media/${seg.mediaId}`}
                  target="_blank"
                  rel="noopener noreferrer"
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
                title={`${marker.type} · ${timeLabel(marker.timestampMs)}`}
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
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>Loading recording sessions…</span>
        </div>
      }>
        <Show when={!noSessionData()} fallback={
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>No session data — recording-session history needs a device build with the sessions endpoint.</span>
          </div>
        }>
          <Show when={(sessions()?.length ?? 0) > 0} fallback={
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>No recordings on this day.</span>
            </div>
          }>
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>Bars show recording sessions by trigger; markers show detection events. Click a bar with a linked clip to open it.</span>
            </div>
          </Show>
        </Show>
      </Show>
    </SettingsCard>
  )
}
