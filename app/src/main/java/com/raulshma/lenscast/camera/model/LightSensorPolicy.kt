package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.core.StreamDefaults
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure smoothing and hysteresis for ambient-light samples. */
object LightSensorPolicy {

    const val SMOOTHING_ALPHA = 0.25
    const val DEBOUNCE_MS = 400L
    const val SETTLE_INTERVAL_MS = 200L
    const val SETTLE_EPSILON_LUX = 0.5
    const val MIN_CHANGE_LUX = 1
    const val RELATIVE_HYSTERESIS = 0.01

    /**
     * How long one served plain status GET keeps the dashboard demand gate
     * open. A poller holds no SSE session, so nothing else would register the
     * sensor for it; each served status re-arms this window, and a poll
     * cadence faster than the TTL reads live lux while one slower than it
     * reads null between windows (the monitor resets the reading when the
     * last gate closes — a stale lux value never lingers).
     */
    const val STATUS_PULSE_TTL_MS = 15_000L

    /**
     * The dashboard demand gate's verdict: an SSE dashboard client is
     * connected, or a plain /api/status GET was served within [pulseTtlMs].
     * Pure so the wiring (one collector owning the monitor's dashboard flag)
     * stays a composition of tested parts.
     */
    fun dashboardDemand(
        sseClients: Int,
        lastStatusPollMs: Long,
        nowMs: Long,
        pulseTtlMs: Long = STATUS_PULSE_TTL_MS,
    ): Boolean {
        if (sseClients > 0) return true
        return lastStatusPollMs > 0 && nowMs - lastStatusPollMs <= pulseTtlMs
    }

    data class State(
        val smoothedLux: Double? = null,
        val publishedLux: Int? = null,
        val candidateLux: Int? = null,
        val candidateSinceMs: Long? = null,
        val lastRawLux: Double? = null,
    )

    data class Sample(val state: State, val lux: Int?)

    /**
     * Fold one raw sample into the state. The very first sample after a
     * registration never publishes on its own — it only seeds a candidate
     * (see [establish]) — so a single sample, however far off the real light,
     * reaches neither the overlay, the status API, nor an automation trigger.
     * Once a reading is live, a new level publishes only after it has held
     * outside the published value's hysteresis band for [DEBOUNCE_MS].
     */
    fun sample(state: State, rawLux: Double, nowMs: Long): Sample {
        if (!rawLux.isFinite()) return Sample(state, state.publishedLux)
        val bounded = rawLux.coerceIn(
            StreamDefaults.LIGHT_SENSOR_MIN_LUX.toDouble(),
            StreamDefaults.LIGHT_SENSOR_MAX_LUX.toDouble(),
        )
        val smoothed = state.smoothedLux?.let { it + (bounded - it) * SMOOTHING_ALPHA } ?: bounded
        val rounded = smoothed.roundToInt()
        // This sample's smoothing and raw level ride along with every verdict below.
        val settled = state.copy(smoothedLux = smoothed, lastRawLux = bounded)
        val published = state.publishedLux ?: return establish(settled, smoothed, rounded, bounded, nowMs)
        if (abs(rounded - published) < hysteresisAround(published)) {
            return Sample(settled.copy(candidateLux = null, candidateSinceMs = null), published)
        }
        val previousCandidate = state.candidateLux
        val sameDirection = previousCandidate != null &&
            (previousCandidate > published) == (rounded > published)
        val candidateSince = if (sameDirection) state.candidateSinceMs ?: nowMs else nowMs
        val next = settled.copy(
            candidateLux = rounded,
            candidateSinceMs = candidateSince,
        )
        return if (nowMs - candidateSince >= DEBOUNCE_MS) {
            publish(smoothed, rounded, bounded)
        } else {
            Sample(next, published)
        }
    }

    /**
     * Advance an on-change sensor (`Sensor.TYPE_LIGHT`) when no new hardware
     * event arrived within [SETTLE_INTERVAL_MS]: Android only emits on change,
     * so steady light after the initial event or a step drop would otherwise
     * stall [establish] or mid-EMA convergence forever. Re-applying [State.lastRawLux]
     * lets the debounce window and EMA converge deterministically.
     */
    fun settle(state: State, nowMs: Long): Sample {
        val lastRaw = state.lastRawLux ?: return Sample(state, state.publishedLux)
        return sample(state, lastRaw, nowMs)
    }

    /**
     * Whether [settle] still has pending work: establishing the first reading,
     * finishing an active debounce candidate, or converging the EMA toward a
     * [State.lastRawLux] outside the published hysteresis band. Guaranteed to
     * terminate because each tick shrinks `|lastRawLux - smoothedLux|` by
     * `1 - SMOOTHING_ALPHA` below [SETTLE_EPSILON_LUX].
     */
    fun needsSettle(state: State): Boolean {
        val lastRaw = state.lastRawLux ?: return false
        val published = state.publishedLux ?: return true
        if (state.candidateLux != null) return true
        val smoothed = state.smoothedLux ?: return true
        return abs(lastRaw - smoothed) >= SETTLE_EPSILON_LUX &&
            abs(lastRaw.roundToInt() - published) >= hysteresisAround(published)
    }

    /**
     * The band a smoothed value must leave before it counts as a change from
     * [reference]: a whole lux, or 1% above 100 lux.
     */
    private fun hysteresisAround(reference: Int): Double =
        max(MIN_CHANGE_LUX.toDouble(), reference * RELATIVE_HYSTERESIS)

    /**
     * Establish the first reading after a registration: [rounded] is the
     * smoothed level to confirm, not a value to publish. The smoothed value
     * must stay inside the candidate's hysteresis band for [DEBOUNCE_MS]
     * before it goes live — a level that moves out of the band restarts the
     * candidate — so the reading the overlay and the automation triggers see
     * is always a debounced one, never the sensor's first (raw) sample.
     */
    private fun establish(
        settled: State,
        smoothed: Double,
        rounded: Int,
        lastRaw: Double,
        nowMs: Long,
    ): Sample {
        val candidate = settled.candidateLux
        if (candidate == null || abs(rounded - candidate) >= hysteresisAround(candidate)) {
            return Sample(settled.copy(candidateLux = rounded, candidateSinceMs = nowMs), null)
        }
        val sinceMs = settled.candidateSinceMs ?: nowMs
        return if (nowMs - sinceMs >= DEBOUNCE_MS) publish(smoothed, rounded, lastRaw) else Sample(settled, null)
    }

    /** A published verdict: the new level is live and no candidate is pending. */
    private fun publish(smoothed: Double, lux: Int, lastRaw: Double): Sample =
        Sample(State(smoothedLux = smoothed, publishedLux = lux, lastRawLux = lastRaw), lux)
}
