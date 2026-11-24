package com.raulshma.lenscast.streaming.whep

import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.SlidingRateWindow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The WHEP lane's measured-stream stats (issue #25) — the WebRTC preview
 * never crosses the encoded-stream hub (libwebrtc encodes each viewer's
 * stream itself), so this is where that lane's bitrate and frame rate come
 * from: libwebrtc's cumulative `getStats` counters, turned into the same
 * measured-rate vocabulary the MJPEG and encoded lanes read from
 * [SlidingRateWindow].
 *
 * **Counters, not frames.** A stats report carries CUMULATIVE totals per
 * session, so nothing can be measured until a second report arrives:
 * [onStats] differences each observation against the session's previous one.
 * The first observation of a session only stores that baseline. A delta that
 * did not advance (a stalled encoder) or went backwards (libwebrtc replaced
 * the outbound stream) is accounted as silence, and the new totals become the
 * baseline either way — a reset counter never reads as a negative sample, nor
 * inflates the next interval.
 *
 * **Why a delta is spread over cells.** The endpoint polls every
 * [pollIntervalMs] (2 s) while the window is [windowMs] (5 s). Recorded as
 * one sample per poll, stamped at the poll, the reading would swing with the
 * cadence: the whole 5 s at a poll, 3 s of it just before the next. Each
 * poll's delta is therefore spread over the fixed [subSampleMs] cells its
 * interval crossed, on ONE time lattice (cell `k` spans `[k·cell, (k+1)·cell)`
 * and is stamped at its centre), each cell carrying the poll's average rate
 * for its slice of the interval. The window span is a whole number of cells
 * (required), so a steady stream puts the same value in every cell.
 *
 * **The not-yet-polled tail is held at the last poll's rate.** Cells only
 * appear when a poll lands, but the window's old edge slides out every
 * millisecond: between two polls, up to one interval of the most recent cells
 * are elapsed and unobserved, and a plain sum would still fall from the true
 * rate to 60% of it and snap back at every poll. [snapshot] values those
 * cells — the ones after the session's last recorded cell, at most one poll
 * interval plus the cell the next poll lands in — at the last poll's
 * per-cell rate: a sample-and-hold over at most ~2 s, the price of polling
 * every 2 s. It decays honestly both ways: a stream that stops keeps being
 * polled, the next poll's delta is 0 and its cells are RECORDED as silence
 * (so the reading steps down as the real cells age out); polls that stop
 * arriving release the hold after one interval and the recorded cells age
 * out to 0 — never a frozen last value.
 *
 * **Sessions.** A session counts once its owner admits it
 * ([onSessionAdmitted]); rates are summed across the admitted sessions, and
 * the session currently moving the most bytes describes the lane (its
 * last-known codec and frame size — libwebrtc omits those members until
 * frames were encoded, so a report without them never erases what an earlier
 * one carried). Removal forgets a session outright, which is what makes a
 * report still in flight at teardown harmless: its id is unknown again.
 *
 * Pure and JVM-testable: no Context, no Log, and the only clock is the
 * injected [nowMs] — the owner stamps each observation with the same clock.
 * Thread-safe: the stats callbacks arrive on libwebrtc's threads, the owner
 * admits and removes from its own, and /api/status reads from another.
 */
class WhepStreamStats(
    /** The owner's poll cadence — how long the not-yet-polled tail is held at the last poll's rate. */
    pollIntervalMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val subSampleMs: Long = StreamDefaults.STATS_SUB_SAMPLE_MS,
    private val windowMs: Long = StreamDefaults.STATS_WINDOW_MS,
) {

    init {
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive: $pollIntervalMs" }
        require(windowMs > 0) { "windowMs must be positive: $windowMs" }
        require(subSampleMs > 0) { "subSampleMs must be positive: $subSampleMs" }
        require(windowMs % subSampleMs == 0L) {
            "windowMs must be a whole number of sub-samples: $windowMs ms is not a multiple of $subSampleMs ms"
        }
    }

    /** The WHEP lane's measured status, as /api/status publishes it. */
    data class Snapshot(
        val measuredBitrateKbps: Int,
        val fps: Double,
        /** The stream's codec (`video/VP8` → `vp8`); null until a report resolved it. */
        val codec: String?,
        /** The encoded frame size; null until a report carried it. */
        val width: Int?,
        val height: Int?,
    )

    /**
     * What one observation accounted: the interval it closed and the counter
     * deltas it spread over that interval's cells (both 0 for a stalled or
     * reset counter). The owner logs it; nothing else reads it.
     */
    data class Accounted(val intervalMs: Long, val bytes: Long, val frames: Long)

    private val windowCells: Long = windowMs / subSampleMs

    /** How many cells behind a session's last poll the hold reaches: one interval, plus the cell the next poll lands in. */
    private val carryCells: Long = pollIntervalMs / subSampleMs + 1

    private val lock = Any()
    private val sessions = HashMap<String, Session>()

    /** One admitted viewer session: its window of cells, its last baseline, and the facts its reports established. */
    private class Session(var window: SlidingRateWindow) {
        /** The totals and cell of the last observation that was accounted for; null until the first one. */
        var baseline: Baseline? = null

        /** The last accounted poll's per-cell rate — what the not-yet-polled tail is held at. */
        var cellBytes = 0.0
        var cellFrames = 0.0

        /** The newest lattice cell this session recorded; null until a poll was spread (nothing to hold before that). */
        var lastCell: Long? = null

        var codec: String? = null
        var width: Int? = null
        var height: Int? = null

        /** The most recent observation's time — the tie-break between equally busy sessions. */
        var lastObservedAtMs: Long = Long.MIN_VALUE

        /** Drops every measurement (the descriptive facts stay): the cells are stamped on a time that no longer means anything. */
        fun forgetMeasurements(window: SlidingRateWindow) {
            this.window = window
            cellBytes = 0.0
            cellFrames = 0.0
            lastCell = null
        }
    }

    private class Baseline(val atMs: Long, val bytesSent: Long, val framesEncoded: Long, val cell: Long)

    /** One session's completed reading: its recorded cells plus the held tail. */
    private class Reading(val bytes: Double, val frames: Double) {
        val kbps: Double get() = bytes * 8.0 / 1000.0
    }

    /** Admits a viewer session. An id that is admitted again starts clean. */
    fun onSessionAdmitted(sessionId: String) {
        synchronized(lock) { sessions[sessionId] = Session(newWindow()) }
    }

    /**
     * One stats report for [sessionId], stamped [atMs] on the injected clock:
     * the session's cumulative [bytesSent] and [framesEncoded], plus the
     * codec and encoded frame size the report carried (null = absent — the
     * last known values stand). A session that was not admitted, or was
     * removed, is ignored. Answers what the observation accounted, or null
     * when it measured nothing (a baseline, or an observation still inside
     * the baseline's cell).
     */
    fun onStats(
        sessionId: String,
        atMs: Long,
        bytesSent: Long,
        framesEncoded: Long,
        codec: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): Accounted? = synchronized(lock) {
        val session = sessions[sessionId] ?: return@synchronized null
        session.lastObservedAtMs = atMs
        codec?.let { session.codec = it }
        width?.takeIf { it > 0 }?.let { session.width = it }
        height?.takeIf { it > 0 }?.let { session.height = it }

        val cell = cellAt(atMs)
        val bytes = bytesSent.coerceAtLeast(0L)
        val frames = framesEncoded.coerceAtLeast(0L)
        val base = session.baseline
        if (base == null) {
            // The first totals have nothing to be differenced against.
            session.baseline = Baseline(atMs, bytes, frames, cell)
            return@synchronized null
        }
        // The cells whose end falls inside (baseline, now] — the interval this delta covers.
        val cells = cell - base.cell
        if (cells < 0) {
            // The clock stepped backwards past the baseline: the session's
            // cells are stamped on a time that no longer means anything (as
            // "future" samples they would over-count until time caught up),
            // so its accounting restarts at this report. The owner reads a
            // monotonic clock, so this is the defensive path, not the plan.
            session.forgetMeasurements(newWindow())
            session.baseline = Baseline(atMs, bytes, frames, cell)
            return@synchronized null
        }
        if (cells == 0L) {
            // Still inside the baseline's cell: leave the baseline where it
            // is, so the next observation carries this delta too instead of
            // two samples double-booking one cell.
            return@synchronized null
        }
        val elapsedMs = atMs - base.atMs
        val deltaBytes = max(0L, bytes - base.bytesSent)
        val deltaFrames = max(0L, frames - base.framesEncoded)

        // A clock jump forward can cross more cells than one window holds;
        // only the newest window's worth can still be inside it, and the loop
        // below never walks a cell per skipped millisecond.
        val skipped = max(0L, cells - windowCells)
        // One cell's share of the poll interval. A steady stream puts the
        // same value in every cell whatever the interval's length or phase.
        val share = subSampleMs.toDouble() / elapsedMs
        var bytesSoFar = (deltaBytes * share * skipped).roundToLong()
        var framesSoFar = deltaFrames * share * skipped
        for (index in skipped until cells) {
            val bytesUpTo = (deltaBytes * share * (index + 1)).roundToLong()
            val framesUpTo = deltaFrames * share * (index + 1)
            // The running totals are rounded, the differences are not: the
            // cells of one poll sum to exactly that poll's rescaled delta.
            session.window.record(
                stampOf(base.cell + index),
                bytesUpTo - bytesSoFar,
                framesUpTo - framesSoFar,
            )
            bytesSoFar = bytesUpTo
            framesSoFar = framesUpTo
        }
        // The tail is held at THIS poll's per-cell rate — 0 for a stalled or
        // reset counter, so a stopped stream lets go of its old rate at once.
        session.cellBytes = deltaBytes * share
        session.cellFrames = deltaFrames * share
        session.lastCell = base.cell + cells - 1
        session.baseline = Baseline(atMs, bytes, frames, cell)
        Accounted(intervalMs = elapsedMs, bytes = deltaBytes, frames = deltaFrames)
    }

    /** Forgets a session — its samples, its baseline and its facts leave the lane at once. */
    fun onSessionRemoved(sessionId: String) {
        synchronized(lock) { sessions.remove(sessionId) }
    }

    /**
     * The lane's measured status: the sessions' rates summed, described by
     * the session currently moving the most bytes (ties break to the most
     * recently observed one). Null while no admitted session has reported —
     * the caller omits the wire block then.
     */
    fun snapshot(): Snapshot? = synchronized(lock) {
        val observed = sessions.entries.filter { it.value.baseline != null }.sortedBy { it.key }
        if (observed.isEmpty()) return@synchronized null
        val now = windowNowMs()
        var bytes = 0.0
        var frames = 0.0
        var dominant: Session? = null
        var dominantKbps = Double.NEGATIVE_INFINITY
        for ((_, session) in observed) {
            val reading = readingOf(session, now)
            bytes += reading.bytes
            frames += reading.frames
            // Sessions are visited in id order, so an all-way tie (a silent
            // lane) still answers deterministically.
            if (reading.kbps > dominantKbps ||
                (reading.kbps == dominantKbps && session.lastObservedAtMs > dominant!!.lastObservedAtMs)
            ) {
                dominant = session
                dominantKbps = reading.kbps
            }
        }
        val windowSeconds = windowMs / 1000.0
        Snapshot(
            // Rounded to nearest: the cell arithmetic moves whole bytes, and
            // a 1699.99 reading is 1700.
            measuredBitrateKbps = (bytes * 8.0 / 1000.0 / windowSeconds).roundToLong().toInt(),
            fps = frames / windowSeconds,
            codec = dominant!!.codec,
            width = dominant.width,
            height = dominant.height,
        )
    }

    /** The session's recorded cells, plus its not-yet-polled tail held at the last poll's rate. */
    private fun readingOf(session: Session, now: Long): Reading {
        val totals = session.window.totals()
        val lastCell = session.lastCell ?: return Reading(totals.bytes.toDouble(), totals.frames)
        val heldFrom = max(lastCell + 1, firstCellInWindow(now))
        val heldTo = min(lastCell + carryCells, newestElapsedCell(now))
        val held = max(0L, heldTo - heldFrom + 1)
        return Reading(
            bytes = totals.bytes + held * session.cellBytes,
            frames = totals.frames + held * session.cellFrames,
        )
    }

    /** The lattice cell holding [atMs]: cell `k` spans `[k·sub, (k+1)·sub)`. */
    private fun cellAt(atMs: Long): Long = Math.floorDiv(atMs, subSampleMs)

    private fun newWindow(): SlidingRateWindow = SlidingRateWindow(windowMs = windowMs, nowMs = ::windowNowMs)

    /**
     * The clock the session windows see. [SlidingRateWindow] keeps a sample
     * exactly at its edge; on a lattice that makes the one millisecond per
     * cell where `now − window` IS a cell's stamp hold 21 cells (a +1/20
     * reading), so an instant that sits on a cell centre is read one
     * millisecond later — the next instant's answer, 1 ms early, instead of a
     * spike that is gone the millisecond after.
     */
    private fun windowNowMs(): Long {
        val now = nowMs()
        return if ((now - subSampleMs / 2) % subSampleMs == 0L) now + 1 else now
    }

    /** The stamp [SlidingRateWindow] holds cell [index] at: its centre, so a cell is never stamped in the future. */
    private fun stampOf(index: Long): Long = index * subSampleMs + subSampleMs / 2

    /** The oldest cell still inside a window read at [nowMs]: the first whose centre is at or past `now − window`. */
    private fun firstCellInWindow(nowMs: Long): Long = Math.floorDiv(nowMs - windowMs - subSampleMs / 2 + subSampleMs - 1, subSampleMs)

    /** The newest cell whose centre has passed by [nowMs] — the last cell a poll could have described by then. */
    private fun newestElapsedCell(nowMs: Long): Long = Math.floorDiv(nowMs - subSampleMs / 2, subSampleMs)
}
