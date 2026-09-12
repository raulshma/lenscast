import { createSignal, For, Show } from 'solid-js'
import type { DetectionEvent, DetectionEventType } from '../types'
import { useEventStream } from '../hooks/useEventStream'
import { detectionEventsExportUrl } from '../api/client'
import { collectLabels, filterEvents, type EventFilter } from '../hooks/eventStreamCore'
import { openMedia } from '../lib/viewerStore'
import SettingsCard from './SettingsCard'
import { formatDateTime, t, tCount } from '../lib/i18n'

const TYPE_FILTERS: { value: EventFilter['type']; label: () => string }[] = [
  { value: 'all', label: () => t('events.filter.all') },
  { value: 'motion', label: () => t('events.filter.motion') },
  { value: 'sound', label: () => t('events.filter.sound') },
  { value: 'tamper', label: () => t('events.filter.tamper') },
]

function hasClip(event: DetectionEvent): boolean {
  return event.clipMediaId != null
}

function clipFileName(event: DetectionEvent): string | null {
  return event.clipFileName ?? null
}

/**
 * Open the event's clip in the shared in-dashboard viewer (the overlay the
 * gallery and the timeline use) instead of a raw new tab — the clip plays
 * right here, with download/copy-link in the viewer bar. clipMediaId is the
 * server's numeric media id (a different id space than the gallery's
 * filename ids), so it is stringified and the viewer renders it directly
 * from the /api/media route.
 */
function openClip(event: DetectionEvent) {
  if (event.clipMediaId == null) return
  openMedia(String(event.clipMediaId), { type: 'VIDEO', fileName: clipFileName(event) })
}

function eventLabels(event: DetectionEvent): string[] {
  return event.labels ?? []
}

function timeLabel(timestampMs: number): string {
  return formatDateTime(timestampMs, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

function actionLabel(action: string): string {
  switch (action) {
    case 'recording': return t('events.action.recording')
    case 'photo': return t('events.action.photo')
    case 'webhook': return t('events.action.webhook')
    case 'siren': return t('events.action.siren')
    case 'torch': return t('events.action.torch')
    case 'mqtt': return t('events.action.mqtt')
    case 'notify': return t('events.action.notify')
    default: return action
  }
}

function modeLabel(mode: 'connecting' | 'live' | 'polling'): string {
  switch (mode) {
    case 'live': return t('events.mode.live')
    case 'polling': return t('events.mode.polling')
    default: return t('events.mode.connecting')
  }
}

/**
 * Recent detection events, pushed live over SSE (GET
 * /api/detection/events/stream) with automatic polling fallback — see
 * useEventStream. Each row shows time, type badge, the snapshot taken at
 * trigger time, dispatched actions, triggered zones / ML labels, and a link
 * to the recorded clip when one exists. A filter row (type chips + label
 * chips over the union of known ML labels, pure logic in eventStreamCore's
 * filterEvents) narrows the visible rows without dropping the buffer. Clips
 * open the shared in-dashboard media viewer (lib/viewerStore) — the same
 * overlay the gallery and the recording timeline use.
 */
export default function EventFeed(props: { readOnly?: () => boolean } = {}) {
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
      title={t('events.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{tCount('events.count', events().length)}</span>
          <span
            class="event-stream-pill"
            classList={{
              'event-stream-pill-live': mode() === 'live',
              'event-stream-pill-polling': mode() === 'polling',
              'event-stream-pill-connecting': mode() === 'connecting',
            }}
            title={mode() === 'live' ? t('events.streamLiveTitle') : t('events.streamPollTitle')}
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
          <Show when={props.readOnly?.() !== true}>
            <button type="button" class="action-btn action-btn-ghost" disabled={events().length === 0} onClick={clear}>
              <span>{t('events.clearAll')}</span>
            </button>
          </Show>
        </div>

        <Show when={events().length > 0}>
          <div class="event-feed-filters">
            <div class="gallery-filters" role="group" aria-label={t('events.filterTypeAria')}>
              <For each={TYPE_FILTERS}>
                {({ value, label }) => (
                  <button
                    type="button"
                    class="gallery-filter-btn"
                    classList={{ 'gallery-filter-active': typeFilter() === value }}
                    onClick={() => setTypeFilter(value)}
                  >
                    {label()}
                  </button>
                )}
              </For>
            </div>
            <div class="gallery-filters" role="group" aria-label={t('events.filterLabelAria')}>
              <button
                type="button"
                class="gallery-filter-btn"
                classList={{ 'gallery-filter-active': labelFilter() === null }}
                onClick={() => setLabelFilter(null)}
              >
                {t('events.allLabels')}
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
                <span>{t('events.empty')}</span>
              </div>
            }
          >
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{t('events.noMatch')}</span>
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
                  alt={t('events.snapshotAlt', { type: event.type })}
                  src={`data:image/jpeg;base64,${event.snapshotJpegBase64}`}
                />
              </Show>
              <div class="event-feed-body">
                <div class="event-feed-line">
                  <span class={`event-badge event-badge-${event.type}`}>{event.type}</span>
                  <span class="event-feed-time">{timeLabel(event.timestampMs)}</span>
                </div>
                <div class="event-feed-line">
                  <Show when={event.dispatchedActions.length > 0} fallback={<span class="event-feed-actions">{t('events.noActions')}</span>}>
                    <span class="event-feed-actions">{event.dispatchedActions.map(actionLabel).join(' · ')}</span>
                  </Show>
                  <Show when={hasClip(event)}>
                    <button
                      type="button"
                      class="event-clip-btn"
                      onClick={() => openClip(event)}
                      title={clipFileName(event) ?? t('events.playClipTitle')}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polygon points="5 3 19 12 5 21 5 3" />
                      </svg>
                      <span>{t('events.viewClip')}</span>
                    </button>
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
