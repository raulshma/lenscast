package com.raulshma.lenscast.companion.client.sse

/**
 * Multi-rate poll scheduler, the port of web/src/hooks/pollLadder.ts as
 * configured by web/src/hooks/useAppState.ts: one 1 s tick drives several
 * named lanes, each firing every N ticks while its gate is open. Pure
 * tick-count math — the caller owns the actual fetches, the visibility
 * source and the timing loop; [tick] returns THIS second's fire decisions
 * and the caller performs them.
 *
 * Port deviations from the TS (documented, semantics preserved):
 *
 *  - **Visibility replaces document.hidden.** The TS ladder reads
 *    `isVisible()` from a `visibilitychange` listener; Android has no
 *    document, so the caller folds its own visibility (lifecycle state,
 *    foreground check) into [tick]'s `visible` parameter. A false tick is a
 *    complete no-op that freezes every counter — exactly the TS
 *    `if (!options.isVisible()) return`.
 *  - **Fire callback → returned set.** The TS invokes `fire(key)` per lane
 *    in declaration order; the Kotlin policy instead returns this tick's
 *    lanes as a Set whose iteration order is the TS declaration order, so
 *    the caller's dispatch loop reproduces the same ordering guarantee.
 *  - **Gates become a caller-updated activity map.** The TS gates are
 *    closures over Solid signals (`enabled: () => !sseHealthChecker()`).
 *    Closures over Kotlin UI state would drag the session graph into a pure
 *    class, so per-lane activity is a map the caller updates between ticks
 *    ([setLaneActive]). The STATUS gate is the exception: it is the
 *    per-tick `sseHealthy` argument, evaluated fresh each tick exactly like
 *    the TS's `enabled: () => !sseHealthChecker()`.
 *
 * Lane table (periods from useAppState.ts's createPollLadder call):
 *
 * | Lane             | Period | Gate (TS origin)                        |
 * |------------------|--------|-----------------------------------------|
 * | [Lane.STATUS]    | 3      | `!sseHealthChecker()` → SSE dead        |
 * | [Lane.RECORDING] | 3      | none                                    |
 * | [Lane.INTERVAL]  | 5      | none                                    |
 * | [Lane.SETTINGS]  | 30     | `!modelDownloading()` → inactive while a model download runs |
 * | [Lane.MODEL_DOWNLOAD] | 3 | `modelDownloading()` → active only during a download |
 * | [Lane.LENSES]    | 30     | `streamingInactive()` → active only while the device is not streaming |
 *
 * A lane absent from the [periods] map is NOT scheduled — it never counts
 * and never fires — matching the TS, where a key missing from the lanes
 * config object simply does not exist (there is deliberately no default
 * period; the production [DEFAULT_PERIODS] schedules all six).
 */
class PollLadderPolicy(periods: Map<Lane, Int> = DEFAULT_PERIODS) {

    /** The poll lanes, in the TS declaration order (the fire order). */
    enum class Lane { STATUS, RECORDING, INTERVAL, SETTINGS, MODEL_DOWNLOAD, LENSES }

    private val periods: Map<Lane, Int>
    private val counters: MutableMap<Lane, Int>
    private val laneActive: MutableMap<Lane, Boolean>

    init {
        // Only lanes the caller SCHEDULED (present in the periods map) exist,
        // exactly like the TS where a lane missing from the config object
        // does not exist at all — there is no default period for an
        // unscheduled lane. Declaration order of the enum governs iteration
        // everywhere below (the TS fire order).
        this.periods = Lane.entries.filter { periods.containsKey(it) }.associateWith { periods.getValue(it) }
        this.counters = periods.keys.associateWithTo(mutableMapOf()) { 0 }
        this.laneActive = periods.keys.associateWithTo(mutableMapOf()) { DEFAULT_ACTIVE[it] ?: true }
    }

    /**
     * Updates the lane-activity gates the caller derives from live state:
     * pass e.g. `SETTINGS to false, MODEL_DOWNLOAD to true` while an ML
     * model download runs, or `LENSES to true` while the device is not
     * streaming. Keys absent from the map keep their previous activity.
     * (Maps the TS's `enabled:` closures onto plain data.)
     */
    fun setLaneActive(active: Map<Lane, Boolean>) {
        for ((lane, isActive) in active) laneActive[lane] = isActive
    }

    /**
     * Advances one tick (the caller's 1 s source drives this — the TS
     * `tickMs: 1000` interval lives outside). Returns the lanes that fire
     * this second, in declaration order; an empty set for an invisible tick.
     */
    fun tick(sseHealthy: Boolean, visible: Boolean): Set<Lane> {
        // The TS master gate: while invisible a tick is a no-op and every
        // counter freezes where it was.
        if (!visible) return emptySet()

        // All counters advance first, then lanes fire — fires are async
        // fetches on the web, so one tick's decisions stay independent.
        for (lane in periods.keys) counters[lane] = counters.getValue(lane) + 1

        val fired = ArrayDeque<Lane>()
        for (lane in periods.keys) {
            if (counters.getValue(lane) < periods.getValue(lane)) continue
            if (!gateOpen(lane, sseHealthy)) continue // closed gate: no fire, no reset (ticks keep accumulating)
            counters[lane] = 0
            fired.addLast(lane)
        }
        // LinkedHashSet preserving the declaration order the deque built.
        return LinkedHashSet(fired)
    }

    /** The lane's gate, ported per the table in the class KDoc. */
    private fun gateOpen(lane: Lane, sseHealthy: Boolean): Boolean = when (lane) {
        Lane.STATUS -> !sseHealthy // useAppState: enabled: () => !sseHealthChecker()
        Lane.RECORDING, Lane.INTERVAL -> true // no `enabled` in the TS config
        else -> laneActive.getValue(lane)
    }

    /** The lane periods, one tick each (useAppState.ts createPollLadder config). */
    companion object {
        const val PERIOD_STATUS = 3
        const val PERIOD_RECORDING = 3
        const val PERIOD_INTERVAL = 5
        const val PERIOD_SETTINGS = 30
        const val PERIOD_MODEL_DOWNLOAD = 3
        const val PERIOD_LENSES = 30

        val DEFAULT_PERIODS: Map<Lane, Int> = mapOf(
            Lane.STATUS to PERIOD_STATUS,
            Lane.RECORDING to PERIOD_RECORDING,
            Lane.INTERVAL to PERIOD_INTERVAL,
            Lane.SETTINGS to PERIOD_SETTINGS,
            Lane.MODEL_DOWNLOAD to PERIOD_MODEL_DOWNLOAD,
            Lane.LENSES to PERIOD_LENSES,
        )

        /**
         * The TS gates' INITIAL truth: settings active (modelDownloading()
         * starts false), modelDownload inactive, lenses inactive
         * (streaming.isActive starts false but the first status answer
         * arrives long before tick 30; the caller corrects via
         * [setLaneActive] — starting inactive matches the conservative side
         * of a 30 s lane).
         */
        private val DEFAULT_ACTIVE: Map<Lane, Boolean> = mapOf(
            Lane.SETTINGS to true,
            Lane.MODEL_DOWNLOAD to false,
            Lane.LENSES to false,
        )
    }
}
