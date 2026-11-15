package com.raulshma.lenscast.companion.client.sse

import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent

/**
 * The detection-event feed reducer, the port of eventStreamCore.ts's
 * `eventKey` + `mergeEvents` (web/src/hooks/eventStreamCore.ts) as driven by
 * web/src/hooks/useEventStream.ts: SSE arrival and periodic poll refreshes
 * merge into one deduped, newest-first, capped list.
 *
 * Port notes:
 *  - **Element type is [RemoteDetectionEvent]** (the TS `DetectionEvent`
 *    wire mirror) — concrete instead of a generic id+timestamp selector
 *    because this buffer exists for exactly one feed and CoreDtos already
 *    pins the wire shape.
 *  - **Identity ([eventKey])**: the TS `eventKey` — `id:<id>` when the id is
 *    present (non-blank), else `t:<timestampMs>`. The timestamp fallback
 *    covers pre-id server builds and keeps two same-millisecond id-less
 *    events collapsing into one (the TS behavior, kept).
 *  - **Merge order quirk, kept on purpose**: the TS merges
 *    `[...incoming, ...existing]` into a keyed map, so for a key present on
 *    both sides the EXISTING occurrence wins (incoming is written first,
 *    existing overwrites). A poll refresh that returns an older copy of an
 *    event already seen live therefore keeps... the poll copy, exactly as
 *    the TS does. Faithful over intuitive; the web's vitest pins this
 *    ("dedupes by id keeping the newest occurrence").
 *  - **Ordering**: newest-first by [RemoteDetectionEvent.timestampMs] (the
 *    web's feed renders newest at the top; the task brief's "newest-last"
 *    phrase is a slip — the TS source this ports is unambiguous:
 *    `b.timestampMs - a.timestampMs`). Ties break by the key's natural
 *    String order, the closest JVM analog of the TS `localeCompare`.
 *  - **Capacity**: default 200 ([DEFAULT_CAPACITY]) — the TS
 *    `MAX_EVENTS = 200` in useEventStream.ts; the oldest (lowest-timestamp)
 *    events fall off.
 *
 * Thread-safe: the SSE collector and the poll coroutine arrive on different
 * threads, so every read-modify-write locks (cheap — the work is a ~200
 * entry map rebuild per arrival).
 */
class EventStreamBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    private var snapshot: List<RemoteDetectionEvent> = emptyList()

    /** Merges one live SSE frame into the feed (useEventStream's `ingest`). */
    fun onSse(event: RemoteDetectionEvent) {
        applyMerge(incoming = listOf(event))
    }

    /**
     * Merges a poll page into the feed (useEventStream's `pollOnce` result):
     * same key map as live arrival, so replayed backlog and refreshed
     * history never duplicate.
     */
    fun onPollRefresh(events: List<RemoteDetectionEvent>) {
        applyMerge(incoming = events)
    }

    /** Empties the local feed (useEventStream's `clear`, after the server delete succeeded). */
    fun clear() {
        synchronized(lock) { snapshot = emptyList() }
    }

    /** The current feed, newest-first, at most [capacity] entries. */
    fun snapshot(): List<RemoteDetectionEvent> = synchronized(lock) { snapshot }

    /** The TS mergeEvents, transcribed (including the existing-wins quirk). */
    private fun applyMerge(incoming: List<RemoteDetectionEvent>) {
        synchronized(lock) {
            val byKey = LinkedHashMap<String, RemoteDetectionEvent>()
            for (event in incoming) byKey[eventKey(event)] = event
            for (event in snapshot) byKey[eventKey(event)] = event // existing occurrence wins, as in the TS
            val merged = byKey.values.toMutableList()
            // Newest-first; ties break by the key's natural String order,
            // the closest JVM analog of the TS `localeCompare`. (The
            // Comparator's own Long compareTo keeps the math overflow-free.)
            merged.sortWith { a, b ->
                val byTime = b.timestampMs.compareTo(a.timestampMs)
                if (byTime != 0) byTime else eventKey(a).compareTo(eventKey(b))
            }
            snapshot = if (merged.size > capacity) merged.take(capacity) else merged
        }
    }

    companion object {
        /** TS MAX_EVENTS in useEventStream.ts. */
        const val DEFAULT_CAPACITY = 200

        /**
         * TS `eventKey`: stable identity for dedupe — the server id when
         * present, else the timestamp.
         */
        fun eventKey(event: RemoteDetectionEvent): String =
            if (event.id.isNotEmpty()) "id:${event.id}" else "t:${event.timestampMs}"
    }
}
