import { createSignal, For, Show } from 'solid-js'
import type { DetectionEvent } from '../types'
import { clearDetectionEvents, getDetectionEvents } from '../api/client'
import { createVisiblePoll } from '../hooks/visiblePoll'
import SettingsCard from './SettingsCard'

const POLL_INTERVAL_MS = 10_000

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
    default: return action
  }
}

/**
 * Recent detection events from GET /api/detection/events — newest first,
 * each with time, type badge, the snapshot taken at trigger time, and the
 * actions that were dispatched. Simple 10 s polling while the panel is
 * visible; the clear-all button deletes the whole log.
 */
export default function EventFeed() {
  const [events, setEvents] = createSignal<DetectionEvent[]>([])
  const [total, setTotal] = createSignal(0)
  const [busy, setBusy] = createSignal(false)

  async function refresh() {
    try {
      const result = await getDetectionEvents()
      setEvents(result.events ?? [])
      setTotal(result.total ?? 0)
    } catch {
      // Transient fetch errors just leave the current feed in place.
    }
  }

  async function clearAll() {
    if (busy()) return
    setBusy(true)
    try {
      await clearDetectionEvents()
      setEvents([])
      setTotal(0)
    } catch {
      // Keep the feed as-is on failure.
    } finally {
      setBusy(false)
    }
  }

  createVisiblePoll(refresh, POLL_INTERVAL_MS)

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
          <span class="field-label">{total()} event{total() === 1 ? '' : 's'}</span>
          <button type="button" class="action-btn action-btn-ghost" disabled={busy() || events().length === 0} onClick={clearAll}>
            <span>Clear all</span>
          </button>
        </div>
      </div>

      <Show
        when={events().length > 0}
        fallback={
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>No detection events yet — armed motion and sound triggers land here.</span>
          </div>
        }
      >
        <For each={events()}>
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
                </div>
              </div>
            </div>
          )}
        </For>
      </Show>
    </SettingsCard>
  )
}
