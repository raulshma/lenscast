import { createSignal, For, Show } from 'solid-js'
import type { DetectionEvent, DetectionEventType } from '../types'
import { useEventStream } from '../hooks/useEventStream'
import { detectionEventsExportUrl } from '../api/client'
import { collectLabels, filterEvents, type EventFilter } from '../hooks/eventStreamCore'
import SettingsCard from './SettingsCard'

const TYPE_FILTERS: { value: EventFilter['type']; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'motion', label: 'Motion' },
  { value: 'sound', label: 'Sound' },
  { value: 'tamper', label: 'Tamper' },
]

function hasClip(event: DetectionEvent): boolean {
  return event.clipMediaId != null
}

function clipFileName(event: DetectionEvent): string | null {
  return event.clipFileName ?? null
}

function eventLabels(event: DetectionEvent): string[] {
  return event.labels ?? []
}

function timeLabel(timestampMs: number): string {
  return new Date(timestampMs).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

function actionLabel(action: string): string {
  switch (action) {
    case 'recording': return 'Recorded'
    case 'photo': return 'Photo'
    case 'webhook': return 'Webhook'
    case 'siren': return 'Siren'
    case 'torch': return 'Light'
    case 'mqtt': return 'MQTT'
    case 'notify': return 'Alert'
    default: return action
  }
}

function modeLabel(mode: 'connecting' | 'live' | 'polling'): string {
  switch (mode) {
    case 'live': return 'Live'
    case 'polling': return 'Polling'
    default: return 'Connecting'
  }
}

/**
 * Recent detection events, pushed live over SSE (GET
 * /api/detection/events/stream) with automatic polling fallback — see
 * useEventStream. Each row shows time, type badge, the snapshot taken at
 * trigger time, dispatched actions, triggered zones / ML labels, and a link
 * to the recorded clip when one exists. A filter row (type chips + label
 * chips over the union of known ML labels, pure logic in eventStreamCore's
 * filterEvents) narrows the visible rows without dropping the buffer.
 * The gallery viewer lives inside the
 * Gallery component (not reachable from here), so clips open the media
 * route GET /api/media/{id} directly in a new tab.
 */
export default function EventFeed() {
  const { events, mode, clear } = useEventStream()
  const [typeFilter, setTypeFilter] = createSignal<EventFilter['type']>('all')
  const [labelFilter, setLabelFilter] = createSignal<string | null>(null)

  // The export route's `type` param mirrors the feed's type chips ('all' sends none).
  const exportFilter = (): DetectionEventType | undefined =>
    typeFilter() === 'all' ? undefined : (typeFilter() as DetectionEventType)

  const filteredEvents = () => filterEvents(events(), { type: typeFilter(), label: labelFilter() })
  const knownLabels = () => collectLabels(events())

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 8v4l3 3" />
          <circle cx="12" cy="12" r="9" />
        </svg>
      }
      title="Detection Events"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{events().length} event{events().length === 1 ? '' : 's'}</span>
          <span
            class="event-stream-pill"
            classList={{
              'event-stream-pill-live': mode() === 'live',
              'event-stream-pill-polling': mode() === 'polling',
              'event-stream-pill-connecting': mode() === 'connecting',
            }}
            title={mode() === 'live' ? 'Streaming events live' : 'Streaming unavailable — refreshing every 10 s'}
          >
            <span class="event-stream-dot" aria-hidden="true" />
            <span>{modeLabel(mode())}</span>
          </span>
          <a
            class="action-btn action-btn-ghost"
            href={detectionEventsExportUrl('csv', exportFilter())}
            download="lenscast-events.csv"
          >
            <span>CSV</span>
          </a>
          <a
            class="action-btn action-btn-ghost"
            href={detectionEventsExportUrl('json', exportFilter())}
            download="lenscast-events.json"
          >
            <span>JSON</span>
          </a>
          <button type="button" class="action-btn action-btn-ghost" disabled={events().length === 0} onClick={clear}>
            <span>Clear all</span>
          </button>
        </div>

        <Show when={events().length > 0}>
          <div class="event-feed-filters">
            <div class="gallery-filters" role="group" aria-label="Filter by event type">
              <For each={TYPE_FILTERS}>
                {({ value, label }) => (
                  <button
                    type="button"
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': typeFilter() === value }}
                    onClick={() => setTypeFilter(value)}
                  >
                    {label}
                  </button>
                )}
              </For>
            </div>
            <div class="gallery-filters" role="group" aria-label="Filter by label">
              <button
                type="button"
                class="gallery-filter-btn"
                classList={{ 'gallery-filter-active': labelFilter() === null }}
                onClick={() => setLabelFilter(null)}
              >
                All labels
              </button>
              <For each={knownLabels()}>
                {(label) => (
                  <button
                    type="button"
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': labelFilter() === label }}
                    onClick={() => setLabelFilter(label)}
                  >
                    {label}
                  </button>
                )}
              </For>
            </div>
          </div>
        </Show>
      </div>

      <Show
        when={filteredEvents().length > 0}
        fallback={
          <Show
            when={events().length > 0}
            fallback={
              <div class="status-banner status-banner-info stream-mode-hint" role="note">
                <span class="status-banner-dot" aria-hidden="true" />
                <span>No detection events yet — armed motion and sound triggers land here.</span>
              </div>
            }
          >
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>No events match the current filters.</span>
            </div>
          </Show>
        }
      >
        <For each={filteredEvents()}>
          {(event) => (
            <div class="event-feed-row">
              <Show when={event.snapshotJpegBase64} fallback={<div class="event-thumb event-thumb-empty" aria-hidden="true" />}>
                <img
                  class="event-thumb"
                  alt={`Snapshot for ${event.type} event`}
                  src={`data:image/jpeg;base64,${event.snapshotJpegBase64}`}
                />
              </Show>
              <div class="event-feed-body">
                <div class="event-feed-line">
                  <span class={`event-badge event-badge-${event.type}`}>{event.type}</span>
                  <span class="event-feed-time">{timeLabel(event.timestampMs)}</span>
                </div>
                <div class="event-feed-line">
                  <Show when={event.dispatchedActions.length > 0} fallback={<span class="event-feed-actions">No actions</span>}>
                    <span class="event-feed-actions">{event.dispatchedActions.map(actionLabel).join(' · ')}</span>
                  </Show>
                  <Show when={hasClip(event)}>
                    <a
                      class="event-clip-btn"
                      href={`/api/media/${event.clipMediaId}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      title={clipFileName(event) ?? 'Open the clip recorded for this event'}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polygon points="5 3 19 12 5 21 5 3" />
                      </svg>
                      <span>View clip</span>
                    </a>
                  </Show>
                </div>
                <Show when={event.zones.length > 0 || eventLabels(event).length > 0}>
                  <div class="event-feed-line">
                    <For each={event.zones}>
                      {(zone) => <span class="event-zone-chip">{zone}</span>}
                    </For>
                    <For each={eventLabels(event)}>
                      {(label) => <span class="event-zone-chip event-label-chip">{label}</span>}
                    </For>
                  </div>
                </Show>
              </div>
            </div>
          )}
        </For>
      </Show>
    </SettingsCard>
  )
}
