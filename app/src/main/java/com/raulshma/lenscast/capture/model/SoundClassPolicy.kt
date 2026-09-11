package com.raulshma.lenscast.capture.model

/**
 * The pure class gate behind YAMNet sound classification: which window labels
 * count and which labels ride a sound event. The RMS detector in
 * `capture/SoundDetectionPolicy.kt` still owns *whether* an event fires —
 * classification is annotate-only, so nothing here can suppress a sound
 * event; an empty verdict just means the event ships without labels.
 * JVM-tested; no Android types.
 *
 * [SECURITY_CLASSES] is the curated default allow-list — real YAMNet
 * (AudioSet) display names, security-relevant for a camera: distress and
 * speech (Speech, Shout, Screaming, Yell, Wail, moan), dogs (Dog, Bark),
 * entry-related sounds (Knock, Doorbell, Glass, Shatter), alarms and sirens
 * (Alarm, Smoke detector, Fire alarm, Siren), gunshots (Gunshot, Cap gun),
 * and a vehicle pulling up (Engine starting). The user-narrowed persisted
 * set is folded back to this default when it empties (the same convention as
 * the arm-schedule mask: the toggles narrow, never disarm silently).
 */
object SoundClassPolicy {

    /**
     * Curated default allow-list (~18 classes) in display order — exact
     * YAMNet spellings (see `capture/ml/YamnetLabels.kt`), so a label from
     * the model's output index always compares equal against this list.
     */
    val SECURITY_CLASSES: List<String> = listOf(
        "Speech",
        "Shout",
        "Screaming",
        "Yell",
        "Wail, moan",
        "Dog",
        "Bark",
        "Knock",
        "Doorbell",
        "Glass",
        "Shatter",
        "Alarm",
        "Smoke detector, smoke alarm",
        "Fire alarm",
        "Siren",
        "Gunshot, gunfire",
        "Cap gun",
        "Engine starting",
    )

    /** The default allow-list as the set the gate compares against. */
    val DEFAULT_ALLOWED_CLASSES: Set<String> = SECURITY_CLASSES.toSet()

    /**
     * The persisted allow-list made safe: unknown spellings drop out (a
     * restored backup or a renamed class must not silently widen the gate),
     * and an empty result folds back to the [DEFAULT_ALLOWED_CLASSES] — the
     * settings chips narrow the list, they never leave it empty.
     */
    fun normalizeAllowed(persisted: Set<String>): Set<String> {
        val known = persisted.filterTo(LinkedHashSet()) { it in DEFAULT_ALLOWED_CLASSES }
        return if (known.isEmpty()) DEFAULT_ALLOWED_CLASSES else known
    }

    /**
     * Whether one window's top-1 passes the gate: the label must be in the
     * allow-list and its confidence at or above the persisted percent. Returns
     * the canonical (allowed-list) spelling, or null for "this window's top
     * label does not count" — null is not a failure, it is the common case
     * (most windows are music, ambient, silence), and the caller just records
     * it without a label. A blank label never passes.
     */
    fun windowLabel(
        topLabel: String?,
        scorePercent: Float,
        minConfidencePercent: Int,
        allowedClasses: Set<String> = DEFAULT_ALLOWED_CLASSES,
    ): String? {
        if (topLabel.isNullOrBlank()) return null
        if (scorePercent < minConfidencePercent) return null
        return allowedClasses.firstOrNull { it == topLabel }
    }

    /** UI spelling of a YAMNet label: `Wail, moan` → `Wail, Moan`. */
    fun humanReadable(label: String): String =
        label.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}

/**
 * The label-stability half of the sound-class policy: turns a stream of
 * per-window qualified labels into the labels one sound event carries. The
 * engine emits a top-1 every 0.96 s window; this tracker keeps a short
 * bounded history and answers [winningLabels] at event time, so a chirping
 * smoke alarm retriggers sanely:
 *
 * 1. **Fresh and stable first** — a label qualified in at least
 *    [stabilityCount] of the last windows within [freshnessMs] wins (count,
 *    then recency). Two agreeing windows beat one, so a one-off misread of a
 *    passing truck never outranks a sounding alarm.
 * 2. **Fresh single** — with no stable label, the most recent fresh qualified
 *    label wins: a single short chirp still annotates its event.
 * 3. **Grace retrigger** — with no fresh qualified window at all, a label
 *    that won recently (within [labelCooldownMs]) re-wins. A low-battery
 *    smoke alarm chirps every ~40 s; each chirp fires the RMS event *before*
 *    its own window completes, so the chirp's label must survive the gap
 *    between freshness and the next event — that is the cooldown's whole job.
 *    Anything older than every horizon is gone for good: an ancient label
 *    never annotates a new event.
 *
 * A window without a qualified label (`onWindow(null, …)`) records nothing
 * but the passage of time — silence between chirps ages prior windows out;
 * it never erases them (erasure would unlabel the very next chirp).
 *
 * Pure state over caller-supplied clocks — JVM-tested; no Android types. The
 * caller serializes [onWindow] (single classifier worker) and
 * [winningLabels] (the sound event path); the tracker synchronizes anyway
 * because the two arrive from different threads.
 */
class SoundLabelTracker(
    /** How many recent qualified windows the history keeps. */
    private val maxHistory: Int = DEFAULT_MAX_HISTORY,
    /** A qualified window labels an event only within this age. */
    private val freshnessMs: Long = DEFAULT_FRESHNESS_MS,
    /** Windows agreeing on one label within freshness before it is "stable". */
    private val stabilityCount: Int = DEFAULT_STABILITY_COUNT,
    /** How long a label that already won keeps re-winning without fresh evidence. */
    private val labelCooldownMs: Long = DEFAULT_LABEL_COOLDOWN_MS,
    /** Upper bound of labels one event carries (the wire `labels` array). */
    private val maxEventLabels: Int = DEFAULT_MAX_EVENT_LABELS,
) {
    private class Entry(val label: String, val timestampMs: Long)

    private val lock = Any()
    private val history = ArrayDeque<Entry>()

    /** Per-label last-win stamps — the grace-retrigger clock. */
    private val lastWinMs = HashMap<String, Long>()

    /** Records one window's qualified label (or a label-less window). */
    fun onWindow(qualifiedLabel: String?, nowMs: Long) {
        if (qualifiedLabel == null) return
        synchronized(lock) {
            history.addLast(Entry(qualifiedLabel, nowMs))
            while (history.size > maxHistory) history.removeFirst()
        }
    }

    /**
     * The labels a sound event at [nowMs] carries, under the ladder in the
     * class KDoc — possibly empty (annotate-only: the event fires regardless).
     * Each returned label's grace clock is re-stamped to [nowMs], so a label
     * keeps winning only while events (or fresh windows) keep confirming it.
     */
    fun winningLabels(nowMs: Long): List<String> {
        synchronized(lock) {
            // Age out stale windows first: the fresh set is what everything
            // below reads, and the history stays bounded by passage of time,
            // not just by maxHistory.
            while (history.isNotEmpty() && nowMs - history.first().timestampMs > freshnessMs) {
                history.removeFirst()
            }
            val fresh = history.toList()
            val winners = stableWinners(fresh)
                ?: singleFreshWinner(fresh)
                ?: graceWinners(nowMs)
            val claimed = winners.take(maxEventLabels)
            claimed.forEach { lastWinMs[it] = nowMs }
            return claimed
        }
    }

    /** Ladder 1: labels with at least [stabilityCount] fresh windows, count then recency. */
    private fun stableWinners(fresh: List<Entry>): List<String>? {
        val byLabel = fresh.groupBy { it.label }
        val stable = byLabel.entries
            .filter { it.value.size >= stabilityCount }
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Entry>>> { it.value.size }
                    .thenByDescending { it.value.maxOf { e -> e.timestampMs } },
            )
            .map { it.key }
        return stable.ifEmpty { null }
    }

    /** Ladder 2: the most recent fresh qualified label. */
    private fun singleFreshWinner(fresh: List<Entry>): List<String>? =
        fresh.maxByOrNull { it.timestampMs }?.let { listOf(it.label) }

    /** Ladder 3: labels that won within [labelCooldownMs] — the chirping-alarm retrigger. */
    private fun graceWinners(nowMs: Long): List<String> =
        lastWinMs.entries
            .filter { nowMs - it.value < labelCooldownMs }
            .sortedByDescending { it.value }
            .map { it.key }

    /** Clears all history and grace stamps (a settings flip or detector re-enable). */
    fun reset() {
        synchronized(lock) {
            history.clear()
            lastWinMs.clear()
        }
    }

    companion object {
        /** ~5.8 s of YAMNet windows — the fresh horizon comfortably spans the event-to-window lag. */
        const val DEFAULT_MAX_HISTORY = 6

        /** Freshness horizon: only windows this young label an event. */
        const val DEFAULT_FRESHNESS_MS = 5_000L

        /** Two agreeing fresh windows make a label stable. */
        const val DEFAULT_STABILITY_COUNT = 2

        /**
         * Grace horizon: a winning label re-triggers this long without fresh
         * evidence — longer than a low-battery smoke alarm's chirp interval
         * (~40 s), shorter than anything a stale label could misattribute.
         */
        const val DEFAULT_LABEL_COOLDOWN_MS = 120_000L

        /** The wire `labels` payload cap for one sound event. */
        const val DEFAULT_MAX_EVENT_LABELS = 2
    }
}
