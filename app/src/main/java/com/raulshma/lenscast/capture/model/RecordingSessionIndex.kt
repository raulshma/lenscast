package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.DetectionEvent
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The pure grouping/classification behind GET /api/recordings/sessions: the
 * requested local day's video captures turned into NVR timeline sessions,
 * each with an inferred end and an attributed trigger.
 *
 * Recording-state history does not exist (the [com.raulshma.lenscast.capture.RecordingController]
 * publishes only the live state), so triggers are reconstructed from the
 * signals that do persist: a capture whose window overlaps a motion or sound
 * detection event is attributed to it, and everything else reads as
 * `manual`. A capture that was actually produced by the continuous loop or a
 * scheduled start is not recoverable historically — it reports `manual`
 * unless a detection event overlaps. Session ends ride the same honesty:
 * a known `durationMs` wins, else the next capture pins the end (gap
 * inference, capped), else a fixed one-minute fallback.
 *
 * All decisions are functions of the passed lists and stamps — caller-supplied
 * clock and zone — so the whole index is JVM-tested.
 */
object RecordingSessionIndex {

    /** Session length when neither a duration nor a next capture pins the end. */
    const val FALLBACK_SESSION_MS = 60_000L

    /**
     * The gap-inference cap: an unknown-length session never stretches past
     * this bound even when the next capture lands much later (a bounded bar
     * beats a day-spanning one on a timeline).
     */
    const val MAX_GAP_INFERENCE_MS = 60 * 60_000L

    /**
     * How far before a capture's start a detection event may sit and still
     * attribute: the coordinator logs the event and starts the clip in the
     * same choreography, so a small lead tolerance only absorbs stamp jitter.
     */
    const val TRIGGER_TOLERANCE_MS = 10_000L

    const val TRIGGER_MANUAL = "manual"
    const val TRIGGER_MOTION = "motion"
    const val TRIGGER_SOUND = "sound"

    private val DAY_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** One local calendar day as a half-open millisecond window. */
    data class DayWindow(val startMs: Long, val endExclusiveMs: Long)

    /** One timeline session: the capture's identity plus the inferred end and trigger. */
    data class Session(
        val id: String,
        val startMs: Long,
        val endMs: Long,
        val trigger: String,
        val mediaId: String?,
    )

    /**
     * The day window for a `yyyy-MM-dd` query value in [zone]. A missing,
     * malformed, or impossible date (February 30th) answers today's window —
     * the route degrades, it never errors.
     */
    fun dayWindow(day: String?, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): DayWindow {
        val parsed = day?.takeIf { DAY_PATTERN.matches(it) }?.let { value ->
            runCatching {
                val (year, month, dayOfMonth) = value.split('-').map { it.toInt() }
                windowFor(year, month, dayOfMonth, zone)
            }.getOrNull()
        }
        return parsed ?: run {
            val today = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
            windowFor(
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH),
                zone,
            )
        }
    }

    /**
     * The day's sessions: video captures starting inside [window], ends
     * inferred per capture, triggers attributed from the motion/sound events.
     * Ordered by start time; `captures` and `events` may arrive in any order.
     */
    fun buildSessions(
        captures: List<CaptureHistory>,
        events: List<DetectionEvent>,
        window: DayWindow,
    ): List<Session> {
        val videos = captures.filter { it.type == CaptureType.VIDEO }.sortedBy { it.timestamp }
        val triggerEvents = events.filter { it.type == TRIGGER_MOTION || it.type == TRIGGER_SOUND }
        return videos
            .filter { it.timestamp >= window.startMs && it.timestamp < window.endExclusiveMs }
            .map { capture ->
                val startMs = capture.timestamp
                val nextStart = videos.firstOrNull { it.timestamp > startMs }?.timestamp
                val endMs = inferEndMs(capture.durationMs, startMs, nextStart)
                Session(
                    id = capture.id,
                    startMs = startMs,
                    endMs = endMs,
                    trigger = attributeTrigger(startMs, endMs, triggerEvents),
                    mediaId = capture.id,
                )
            }
    }

    /**
     * End inference, in priority order: a persisted duration wins; else the
     * next capture pins the end while within [MAX_GAP_INFERENCE_MS]; else the
     * fixed [FALLBACK_SESSION_MS] bar. Always strictly greater than the start,
     * so a session never reads as empty.
     */
    fun inferEndMs(durationMs: Long, startMs: Long, nextCaptureStartMs: Long?): Long = when {
        durationMs > 0 -> startMs + durationMs
        nextCaptureStartMs != null && nextCaptureStartMs > startMs &&
            nextCaptureStartMs - startMs <= MAX_GAP_INFERENCE_MS -> nextCaptureStartMs
        else -> startMs + FALLBACK_SESSION_MS
    }

    /**
     * Trigger attribution: the nearest motion/sound event overlapping
     * `[start - tolerance, end]` names the trigger (`motion`/`sound`); a tie
     * on distance reads as motion; no overlapping event reads as [TRIGGER_MANUAL].
     */
    fun attributeTrigger(startMs: Long, endMs: Long, triggerEvents: List<DetectionEvent>): String {
        val overlapping = triggerEvents.filter { it.timestampMs >= startMs - TRIGGER_TOLERANCE_MS && it.timestampMs <= endMs }
        val best = overlapping.minWithOrNull(
            compareBy(
                { kotlin.math.abs(it.timestampMs - startMs) },
                { triggerPriority(it.type) },
            ),
        ) ?: return TRIGGER_MANUAL
        // Defensive: callers pre-filter to motion/sound, but an unexpected
        // event type must never leak onto the wire as a trigger — the
        // dashboard's trigger enum is closed.
        return if (best.type == TRIGGER_MOTION || best.type == TRIGGER_SOUND) best.type else TRIGGER_MANUAL
    }

    /** Tie-break order when a motion and a sound event sit equally near a start. */
    private fun triggerPriority(type: String): Int = when (type) {
        TRIGGER_MOTION -> 0
        else -> 1
    }

    /** Local midnight of the given calendar day through local midnight of the next. */
    private fun windowFor(year: Int, month: Int, dayOfMonth: Int, zone: TimeZone): DayWindow {
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, dayOfMonth, 0, 0, 0)
            isLenient = false
        }
        val startMs = calendar.timeInMillis // Throws on an impossible date (Feb 30) under the strict calendar.
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        return DayWindow(startMs, calendar.timeInMillis)
    }

    /** "yyyy-MM-dd" for a local millisecond stamp — the inverse of [dayWindow]'s parse. */
    fun dayKeyFor(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
