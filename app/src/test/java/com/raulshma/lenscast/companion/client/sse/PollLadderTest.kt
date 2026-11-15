package com.raulshma.lenscast.companion.client.sse

import com.raulshma.lenscast.companion.client.sse.PollLadderPolicy.Lane
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-tested pin of the tick ladder, mirroring web/src/hooks/pollLadder.test.ts
 * case for case (the fire tally becomes a lane-set assertion; ordering is
 * the set's iteration order, which the policy guarantees is declaration
 * order — the TS fire order).
 */
class PollLadderTest {

    /** Fast ticks that keep the tests table-sized where the periods matter. */
    private fun fastLadder(vararg periods: Pair<Lane, Int>): PollLadderPolicy =
        PollLadderPolicy(periods.toMap())

    // ── Boundary math ──

    @Test
    fun `fires a lane exactly on its period boundary, not before`() {
        val ladder = fastLadder(Lane.STATUS to 3)
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
    }

    @Test
    fun `fires lanes at their own rates over a shared tick source`() {
        val ladder = fastLadder(Lane.STATUS to 3, Lane.INTERVAL to 5)
        var statusFires = 0
        var intervalFires = 0
        repeat(30) {
            val fired = ladder.tick(sseHealthy = false, visible = true)
            if (Lane.STATUS in fired) statusFires++
            if (Lane.INTERVAL in fired) intervalFires++
        }
        assertEquals(10, statusFires) // TS: 30 ticks / everyTicks 3
        assertEquals(6, intervalFires) // TS: 30 ticks / everyTicks 5
    }

    @Test
    fun `fires in declaration order on a shared tick`() {
        // TS declaration order: STATUS before RECORDING. Both period-2 lanes
        // fire together on every even tick.
        val ladder = fastLadder(Lane.STATUS to 2, Lane.RECORDING to 2)
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(listOf(Lane.STATUS, Lane.RECORDING), ladder.tick(sseHealthy = false, visible = true).toList())
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(listOf(Lane.STATUS, Lane.RECORDING), ladder.tick(sseHealthy = false, visible = true).toList())
    }

    // ── Visibility gating (Android: caller-visible flag replaces document.hidden) ──

    @Test
    fun `freezes every counter while invisible and resumes where it left off`() {
        val ladder = fastLadder(Lane.STATUS to 3)
        repeat(10) { assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = false)) }
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        // The ten invisible ticks never counted: the fire lands on the third
        // visible tick, exactly as the TS visibility test pins.
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
    }

    // ── SSE-healthy suppression of the STATUS lane ──

    @Test
    fun `sse-healthy suppresses STATUS and the saturated counter fires on the first dead tick`() {
        val ladder = fastLadder(Lane.STATUS to 3)
        repeat(4) { assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = true, visible = true)) }
        // Gate closed the whole time: no fires, but the counter kept
        // accumulating (useAppState's "saturated counter" note) — the first
        // tick after the lane dies fires immediately.
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
        // Reset done; back to the normal 3-tick cadence.
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
    }

    // ── Lane-activity gates (the TS `enabled:` closures, as data) ──

    @Test
    fun `keeps counting through a closed gate and fires once when it opens`() {
        val ladder = fastLadder(Lane.LENSES to 3) // LENSES starts inactive
        repeat(5) { assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = true, visible = true)) }
        ladder.setLaneActive(mapOf(Lane.LENSES to true))
        // The gate opening pays out the accumulated counter immediately, then
        // the lane resets to its normal cadence: gate flapping below does not
        // fast-forward the fresh counter (ticks 7 and 8 stay silent).
        assertEquals(setOf(Lane.LENSES), ladder.tick(sseHealthy = true, visible = true))
        ladder.setLaneActive(mapOf(Lane.LENSES to false))
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = true, visible = true))
        ladder.setLaneActive(mapOf(Lane.LENSES to true))
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = true, visible = true))
        assertEquals(setOf(Lane.LENSES), ladder.tick(sseHealthy = true, visible = true))
    }

    @Test
    fun `gates each lane independently`() {
        // Mirror of the TS "gates each lane independently" case: STATUS
        // ungated, LENSES gated, both period 2.
        val ladder = fastLadder(Lane.STATUS to 2, Lane.LENSES to 2)
        ladder.tick(sseHealthy = false, visible = true)
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
        ladder.setLaneActive(mapOf(Lane.LENSES to true))
        // Tick 3: LENSES pays out its gate-closed accumulation (3 ticks ≥ 2)
        // while STATUS is mid-cycle after its tick-2 fire and reset. Tick 4:
        // STATUS's cadence; tick 5: LENSES's. Each lane keeps its own phase.
        assertEquals(setOf(Lane.LENSES), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(setOf(Lane.STATUS), ladder.tick(sseHealthy = false, visible = true))
        assertEquals(setOf(Lane.LENSES), ladder.tick(sseHealthy = false, visible = true))
    }

    // ── The production lane table ──

    @Test
    fun `settings fires always on the default activity map`() {
        val ladder = PollLadderPolicy() // production periods
        var settingsFires = 0
        var modelDownloadFires = 0
        var lensesFires = 0
        var statusFires = 0
        repeat(30) {
            val fired = ladder.tick(sseHealthy = true, visible = true) // SSE live: status suppressed
            if (Lane.SETTINGS in fired) settingsFires++
            if (Lane.MODEL_DOWNLOAD in fired) modelDownloadFires++
            if (Lane.LENSES in fired) lensesFires++
            if (Lane.STATUS in fired) statusFires++
        }
        assertEquals(1, settingsFires) // tick 30, despite the 30-period
        assertEquals(0, statusFires) // healthy SSE keeps the poll lane off
        assertEquals(0, modelDownloadFires) // no download running
        assertEquals(0, lensesFires) // streaming active
        // Tick 31 is silent everywhere: every counter reset after its fire.
        assertEquals(emptySet<Lane>(), ladder.tick(sseHealthy = true, visible = true))
    }

    @Test
    fun `a model download swaps the settings cadence for the download cadence`() {
        val ladder = PollLadderPolicy()
        ladder.setLaneActive(mapOf(Lane.SETTINGS to false, Lane.MODEL_DOWNLOAD to true))
        var settingsFires = 0
        var downloadFires = 0
        repeat(12) {
            val fired = ladder.tick(sseHealthy = true, visible = true)
            if (Lane.SETTINGS in fired) settingsFires++
            if (Lane.MODEL_DOWNLOAD in fired) downloadFires++
        }
        assertEquals(0, settingsFires) // TS: enabled: () => !modelDownloading()
        assertEquals(4, downloadFires) // every 3 ticks over 12
    }

    @Test
    fun `recording and interval lanes ignore every gate`() {
        val ladder = PollLadderPolicy()
        var recordingFires = 0
        var intervalFires = 0
        repeat(30) {
            val fired = ladder.tick(sseHealthy = true, visible = true)
            if (Lane.RECORDING in fired) recordingFires++
            if (Lane.INTERVAL in fired) intervalFires++
        }
        assertEquals(10, recordingFires) // every 3
        assertEquals(6, intervalFires) // every 5
    }

    @Test
    fun `lenses lane polls only while the device streams nothing`() {
        val ladder = PollLadderPolicy()
        ladder.setLaneActive(mapOf(Lane.LENSES to true)) // streamingInactive()
        var lensesFires = 0
        repeat(31) { if (Lane.LENSES in ladder.tick(sseHealthy = true, visible = true)) lensesFires++ }
        assertEquals(1, lensesFires) // tick 30
    }
}
