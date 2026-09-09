package com.raulshma.lenscast.capture

/**
 * Pure event-log knowledge for the [DetectionEventStore]: the newest-first
 * list order, the drop-oldest cap, the snapshot size gate, and the API list
 * limit. The store keeps the file, the lock, and the persistence; every
 * non-obvious decision over the in-memory log is pinned here by JVM tests.
 */
object DetectionEventLogPolicy {

    /** The log keeps the most recent [MAX_EVENTS] events, evicting the oldest. */
    const val MAX_EVENTS = 200

    /**
     * A snapshot rides the log and every webhook payload only when its base64
     * payload stays within this byte cap; bigger frames are skipped, never
     * truncated (a cut base64 string is a broken JPEG).
     */
    const val MAX_SNAPSHOT_BYTES = 40_000

    /** `/api/detection/events` default page size. */
    const val DEFAULT_LIST_LIMIT = 50

    /** Newest first: the new event leads, the log truncates at the cap. */
    fun append(existing: List<DetectionEvent>, event: DetectionEvent): List<DetectionEvent> =
        (listOf(event) + existing).take(MAX_EVENTS)

    /** Drop-oldest read: newest first, at most [limit] entries. */
    fun readNewestFirst(events: List<DetectionEvent>, limit: Int?): List<DetectionEvent> =
        events.take(listLimit(limit))

    /** The API limit clamps into 1..[MAX_EVENTS]; a missing limit means the default. */
    fun listLimit(limit: Int?): Int =
        (limit ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_EVENTS)

    /**
     * The snapshot decision: null/empty bytes and anything whose base64
     * encoding (4 bytes per 3, rounded up) would exceed [MAX_SNAPSHOT_BYTES]
     * are skipped — a snapshot must always be a complete JPEG, on disk and on
     * the wire.
     */
    fun acceptsSnapshot(frameBytes: ByteArray?): Boolean {
        if (frameBytes == null || frameBytes.isEmpty()) return false
        val base64Bytes = 4 * ((frameBytes.size + 2) / 3)
        return base64Bytes <= MAX_SNAPSHOT_BYTES
    }
}
