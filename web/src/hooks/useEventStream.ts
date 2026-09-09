import { createSignal, onCleanup, onMount } from 'solid-js'
import type { DetectionEvent } from '../types'
import { clearDetectionEvents, getDetectionEvents } from '../api/client'
import { createStreamFallback, mergeEvents, parseEventData } from './eventStreamCore'

const STREAM_PATH = '/api/detection/events/stream'
const POLL_INTERVAL_MS = 10_000
const SSE_RETRY_MS = 60_000
const MAX_EVENTS = 200

export type EventStreamMode = 'connecting' | 'live' | 'polling'

export interface EventStream {
  /** Newest-first, deduped, capped in memory. */
  events: () => DetectionEvent[]
  mode: () => EventStreamMode
  /** Deletes the whole persisted log server-side, then empties the local feed. */
  clear(): Promise<void>
}

/**
 * Live detection-event feed: an EventSource on the SSE replay/push channel
 * replaces the panel's polling entirely while it is open. On error the stream
 * is closed, the existing polling fetch takes over every 10 s, and SSE is
 * retried after 60 s. The parsing/dedupe/retry math lives in the pure
 * eventStreamCore module; this wrapper only owns the browser primitives.
 */
export function useEventStream(): EventStream {
  const [events, setEvents] = createSignal<DetectionEvent[]>([])
  const [mode, setMode] = createSignal<EventStreamMode>('connecting')
  const fallback = createStreamFallback({ retryAfterMs: SSE_RETRY_MS })

  let source: EventSource | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  function ingest(frameText: string) {
    const parsed = parseEventData(frameText)
    if (!parsed) return
    setEvents((prev) => mergeEvents(prev, [parsed], MAX_EVENTS))
  }

  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  async function pollOnce() {
    try {
      const result = await getDetectionEvents()
      setEvents((prev) => mergeEvents(prev, result.events ?? [], MAX_EVENTS))
    } catch {
      // Transient fetch errors just leave the current feed in place.
    }
  }

  function startPolling() {
    if (pollTimer !== null) return
    pollTimer = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      void pollOnce()
    }, POLL_INTERVAL_MS)
    void pollOnce()
  }

  function stopRetry() {
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  function scheduleRetry() {
    if (retryTimer !== null) return
    retryTimer = setTimeout(() => {
      retryTimer = null
      if (fallback.takeReconnect(Date.now())) connect()
    }, SSE_RETRY_MS)
  }

  function degrade() {
    if (source) {
      source.close()
      source = null
    }
    fallback.markError(Date.now())
    setMode('polling')
    startPolling()
    scheduleRetry()
  }

  function connect() {
    try {
      source = new EventSource(STREAM_PATH, { withCredentials: true })
    } catch {
      source = null
      degrade()
      return
    }
    source.onopen = () => {
      fallback.markOpen(Date.now())
      stopPolling()
      stopRetry()
      setMode('live')
    }
    source.onmessage = (ev) => ingest((ev as MessageEvent).data)
    source.onerror = () => degrade()
  }

  onMount(() => connect())

  onCleanup(() => {
    source?.close()
    stopPolling()
    stopRetry()
  })

  return {
    events,
    mode,
    // Only empty the local feed once the persisted log is actually gone —
    // the server's backlog replay would otherwise put every event right back.
    clear: async () => {
      try {
        await clearDetectionEvents()
        setEvents([])
      } catch {
        // Transient fetch errors leave the feed (and the log) untouched.
      }
    },
  }
}
