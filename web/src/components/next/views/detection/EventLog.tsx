import { createSignal, For, Show } from 'solid-js'
import type { DetectionEvent, DetectionEventType } from '../../../../types'
import { useEventStream } from '../../../../hooks/useEventStream'
import { detectionEventsExportUrl } from '../../../../api/client'
import { collectLabels, filterEvents, type EventFilter } from '../../../../hooks/eventStreamCore'
import { openMedia } from '../../../../lib/viewerStore'
import { formatDateTime, t, tCount } from '../../../../lib/i18n'
import { Panel, Led, Badge, Segmented, Empty, Button } from '../../ui'

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

const MODE_LEDS = { live: 'ok', polling: 'warn', connecting: 'off' } as const

/**
 * The event log, ported from the classic EventFeed: rows pushed live over
 * SSE with polling fallback (useEventStream), type + label filters, CSV/JSON
 * export, and clips that open the shared media viewer. The log reads as a
 * console journal: mono timestamps, type tags, quiet action lines.
 */
export default function EventLog(props: { readOnly?: () => boolean } = {}) {
  const { events, mode, clear } = useEventStream()
  const [typeFilter, setTypeFilter] = createSignal<EventFilter['type']>('all')
  const [labelFilter, setLabelFilter] = createSignal<string | null>(null)

  // The export route's `type` param mirrors the feed's type chips ('all' sends none).
  const exportFilter = (): DetectionEventType | undefined =>
    typeFilter() === 'all' ? undefined : (typeFilter() as DetectionEventType)

  const filteredEvents = () => filterEvents(events(), { type: typeFilter(), label: labelFilter() })
  const knownLabels = () => collectLabels(events())

  return (
    <Panel
      title={t('events.title')}
      badge={
        <Badge tone="neutral" mono>
          <Led tone={MODE_LEDS[mode()]} />
          {tCount('events.count', events().length)}
        </Badge>
      }
      actions={
        <>
          <a class="nx-btn nx-btn-ghost nx-btn-sm" href={detectionEventsExportUrl('csv', exportFilter())} download="lenscast-events.csv">
            CSV
          </a>
          <a class="nx-btn nx-btn-ghost nx-btn-sm" href={detectionEventsExportUrl('json', exportFilter())} download="lenscast-events.json">
            JSON
          </a>
          <Show when={props.readOnly?.() !== true}>
            <Button size="sm" variant="ghost" disabled={events().length === 0} onClick={clear} ariaLabel={t('events.clearAll')}>
              {t('events.clearAll')}
            </Button>
          </Show>
        </>
      }
    >
      <Show when={events().length > 0}>
        <div class="nx-log-filters">
          <Segmented
            options={TYPE_FILTERS.map(({ value, label }) => ({ value, label: label() }))}
            value={typeFilter()}
            onChange={(v) => setTypeFilter(v as EventFilter['type'])}
            ariaLabel={t('events.filterTypeAria')}
          />
          <div class="nx-log-labels" role="group" aria-label={t('events.filterLabelAria')}>
            <button
              type="button"
              class="nx-chip"
              classList={{ 'nx-chip-active': labelFilter() === null }}
              onClick={() => setLabelFilter(null)}
            >
              {t('events.allLabels')}
            </button>
            <For each={knownLabels()}>
              {(label) => (
                <button
                  type="button"
                  class="nx-chip"
                  classList={{ 'nx-chip-active': labelFilter() === label }}
                  onClick={() => setLabelFilter(label)}
                >
                  {label}
                </button>
              )}
            </For>
          </div>
        </div>
      </Show>

      <Show
        when={filteredEvents().length > 0}
        fallback={
          <Show
            when={events().length > 0}
            fallback={<Empty title={t('events.empty')} />}
          >
            <Empty title={t('events.noMatch')} />
          </Show>
        }
      >
        <div class="nx-log">
          <For each={filteredEvents()}>
            {(event) => (
              <div class="nx-log-row" classList={{ 'nx-log-row-fault': event.type === 'tamper' }}>
                <Show when={event.snapshotJpegBase64} fallback={<div class="nx-log-thumb nx-log-thumb-empty" aria-hidden="true" />}>
                  <img
                    class="nx-log-thumb"
                    alt={t('events.snapshotAlt', { type: event.type })}
                    src={`data:image/jpeg;base64,${event.snapshotJpegBase64}`}
                  />
                </Show>
                <div class="nx-log-body">
                  <div class="nx-log-line">
                    <span class={`nx-tag nx-tag-${event.type}`}>{event.type}</span>
                    <span class="nx-log-time nx-mono">{timeLabel(event.timestampMs)}</span>
                    <Show when={hasClip(event)}>
                      <button
                        type="button"
                        class="nx-log-clip"
                        onClick={() => openClip(event)}
                        title={clipFileName(event) ?? t('events.playClipTitle')}
                      >
                        {t('events.viewClip')}
                      </button>
                    </Show>
                  </div>
                  <div class="nx-log-line nx-log-actions">
                    <Show when={event.dispatchedActions.length > 0} fallback={<span>{t('events.noActions')}</span>}>
                      <For each={event.dispatchedActions}>
                        {(action) => <span class="nx-log-action">{actionLabel(action)}</span>}
                      </For>
                    </Show>
                  </div>
                  <Show when={event.zones.length > 0 || eventLabels(event).length > 0}>
                    <div class="nx-log-line nx-log-chips">
                      <For each={event.zones}>
                        {(zone) => <span class="nx-chip">{zone}</span>}
                      </For>
                      <For each={eventLabels(event)}>
                        {(label) => <span class="nx-chip nx-chip-active">{label}</span>}
                      </For>
                    </div>
                  </Show>
                </div>
              </div>
            )}
          </For>
        </div>
      </Show>
    </Panel>
  )
}
