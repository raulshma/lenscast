package com.raulshma.lenscast.wear

/**
 * The pure decision core of the watch-side detection alerts: which feed
 * entries are NEW events, what the alert surfaces should say, and how old
 * an event reads. No Android, no coroutines — every verdict is JVM-tested
 * (WearAlertPolicyTest); the controller only executes it.
 *
 * Why an id set and not a max id: the phone mints event ids as random
 * UUID strings (DetectionCoordinator), so "id greater than last seen" is
 * meaningless on the wire. New-event detection is therefore set-based —
 * a bounded ring of recently seen ids — with a timestamp floor that
 * absorbs old-but-unseen entries (a cleared/pruned phone-side log must
 * never replay as a watch-side alert burst).
 */
object WearAlertPolicy {

    /**
     * The persisted seen-state. [seenIds] is the bounded newest-first ring
     * (feed order — index 0 is the newest event the watch has laid eyes on);
     * [lastTimestampMs] is the newest event timestamp ever seen, the floor
     * below which unseen ids are absorbed silently.
     */
    data class SeenState(
        val lastTimestampMs: Long,
        val seenIds: List<String>,
    )

    /**
     * One poll's verdict: the new events to surface (chronological, oldest
     * first — the newest is `newEvents.last()`) and the state to persist.
     * [nextSeen] is null only while no event has ever been seen, so the very
     * first successful poll after install becomes a silent baseline.
     */
    data class Verdict(
        val newEvents: List<WearDetectionEvent>,
        val nextSeen: SeenState?,
    )

    /**
     * The new-event verdict over one newest-first feed page.
     *
     * - First contact ([seen] == null, non-empty feed): the newest page
     *   becomes the baseline and NOTHING alerts — a fresh install must not
     *   buzz the watch with the phone's event history.
     * - Thereafter: an entry is new when its id is outside the seen ring
     *   AND its timestamp is at or above the floor. Same-millisecond
     *   entries ride the `>=` floor and are deduped by id — the pair of
     *   rules closes both the burst race and the replay race.
     * - Freshness gate: a new event older than [MAX_ALERT_AGE_MS] (the
     *   watch was off/away for a while) updates the ring silently instead
     *   of alerting on stale history — the banner is for events that still
     *   matter now.
     */
    fun evaluate(feed: List<WearDetectionEvent>, seen: SeenState?, nowMs: Long): Verdict {
        if (feed.isEmpty()) return Verdict(emptyList(), seen)
        if (seen == null) {
            return Verdict(emptyList(), seenStateOf(feed))
        }
        val unseen = feed.filter { it.id !in seen.seenIds && it.timestampMs >= seen.lastTimestampMs }
        val alertable = unseen.filter { nowMs - it.timestampMs <= MAX_ALERT_AGE_MS }
        val ring = seenStateOf(feed, seen)
        return Verdict(
            newEvents = alertable.sortedBy { it.timestampMs },
            nextSeen = ring,
        )
    }

    /**
     * The next ring over one page: the feed's ids first (newest-first
     * already), then whatever the old ring still holds, deduped and cut to
     * [SEEN_RING_SIZE]. The floor is whichever end is newer — a phone-side
     * clock skew must never move it backwards.
     */
    private fun seenStateOf(feed: List<WearDetectionEvent>, previous: SeenState? = null): SeenState {
        val ids = (feed.map { it.id } + (previous?.seenIds ?: emptyList()))
            .distinct()
            .take(SEEN_RING_SIZE)
        val feedNewest = feed.maxOf { it.timestampMs }
        val floor = maxOf(feedNewest, previous?.lastTimestampMs ?: Long.MIN_VALUE)
        return SeenState(lastTimestampMs = floor, seenIds = ids)
    }

    /**
     * The banner's detail line: the ML/YAMNet class labels when present
     * ("person, dog"), else the motion zones ("Front door"), else an empty
     * string the UI collapses. One derivation so the banner and the
     * notification can never disagree.
     */
    fun detailLine(event: WearDetectionEvent): String = when {
        event.labels.isNotEmpty() -> event.labels.joinToString(", ")
        event.zones.isNotEmpty() -> event.zones.joinToString(", ")
        else -> ""
    }

    /**
     * How old an event reads, as a bucket the UI maps onto strings: the
     * pure half of the age ticker (the composables own only the templates).
     * Negative ages (a phone clock ahead of the watch) clamp to [AgeBucket.Now].
     */
    fun relativeAge(eventMs: Long, nowMs: Long): AgeBucket {
        val ageSeconds = ((nowMs - eventMs) / 1_000).coerceAtLeast(0)
        return when {
            ageSeconds < 10 -> AgeBucket.Now
            ageSeconds < 60 -> AgeBucket.Seconds(ageSeconds.toInt())
            ageSeconds < 3_600 -> AgeBucket.Minutes((ageSeconds / 60).toInt())
            else -> AgeBucket.Hours((ageSeconds / 3_600).toInt())
        }
    }

    /** The age ladder [relativeAge] returns; templates live in strings.xml. */
    sealed class AgeBucket {
        data object Now : AgeBucket()
        data class Seconds(val value: Int) : AgeBucket()
        data class Minutes(val value: Int) : AgeBucket()
        data class Hours(val value: Int) : AgeBucket()
    }

    /** The seen ring's size: 2x the feed window, so a burst never outruns it. */
    const val SEEN_RING_SIZE = 10

    /** Events older than this alert silently (ring-only): 10 minutes. */
    const val MAX_ALERT_AGE_MS = 10 * 60 * 1_000L
}
