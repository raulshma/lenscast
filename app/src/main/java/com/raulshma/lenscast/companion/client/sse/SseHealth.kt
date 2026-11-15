package com.raulshma.lenscast.companion.client.sse

/**
 * The SSE push lane's freshness verdict, extracted pure (the port of
 * web/src/hooks/sseHealth.ts): the Android primitives — the connection, the
 * wall clock — stay at the call site (the companion session), and the
 * decision itself is unit-pinned without a socket.
 *
 * TS → Kotlin constant map (every number below has an origin in the TS):
 *
 * | TS (web/src/hooks/sseHealth.ts)                 | Kotlin                                  |
 * |-------------------------------------------------|-----------------------------------------|
 * | `SSE_STALE_MS = 5_000` (the only dead bar)      | [Companion.DEFAULT_GRACE_FACTOR] = 5.0 × the 1s status interval ([DEFAULT_EXPECTED_INTERVAL_MS]) — the ratio, not the milliseconds, so the policy survives an interval change |
 * | `nowMs - lastSseEventAtMs < SSE_STALE_MS`       | [SseHealthState.Healthy] boundary: `age < expectedIntervalMs * graceFactor` (= < 5000 ms at defaults) |
 * | `nowMs - lastSseEventAtMs >= SSE_STALE_MS`      | [SseHealthState.Stale]/[SseHealthState.Dead] territory from 5000 ms on |
 * | (no TS equivalent — the web has one bar)        | [Companion.DEFAULT_DEAD_FACTOR] = 2.0 × the stale point (= 10 s): the added Kotlin rung where reconnect wins over "keep waiting for events" |
 * | `open` + `sseAliveAtMs` inputs                   | folded into [evaluate]'s `lastEventAtMs`: the web's onopen handler stamps BOTH clocks (`sseAliveAt = lastSseEventAt = now`), so a single event clock reproduces the two-clock verdict exactly — documented in sseHealth.test.ts |
 *
 * The third state is the port's one semantic addition: the web's boolean
 * hands a binary "healthy / dead" to the banner and the poll ladder; the
 * companion session wants an intermediate rung where the reconnect has
 * started (Stale) but poll coverage is not yet declared authoritative
 * (Dead). The Healthy boundary itself is the TS one, unchanged.
 */
class SseHealth(
    private val graceFactor: Double = DEFAULT_GRACE_FACTOR,
    private val deadFactor: Double = DEFAULT_DEAD_FACTOR,
) {

    /** The freshness verdict for the push lane. */
    sealed interface SseHealthState {
        /** Events are arriving inside the grace window — trust the push lane. */
        data object Healthy : SseHealthState

        /**
         * Silent past the TS stale bar but not yet dead: a healthy device
         * streams a status event every ~1 s, so this long a gap means the
         * lane is failing — reconnect is warranted, but the feed may still
         * be a momentary hiccup away from recovering.
         */
        data object Stale : SseHealthState

        /** Silent past the dead bar — treat the lane as gone; fall back to polling. */
        data object Dead : SseHealthState
    }

    /**
     * The verdict for one evaluation instant. [lastEventAtMs] should be
     * stamped on open as well as on every event (the web's onopen behavior —
     * see the constant table above), so a freshly opened, not-yet-emitting
     * socket reads healthy at birth.
     */
    fun evaluate(nowMs: Long, lastEventAtMs: Long, expectedIntervalMs: Long = DEFAULT_EXPECTED_INTERVAL_MS): SseHealthState {
        val silenceMs = nowMs - lastEventAtMs
        val staleAfter = expectedIntervalMs * graceFactor
        val deadAfter = staleAfter * deadFactor
        return when {
            silenceMs < staleAfter -> SseHealthState.Healthy
            silenceMs < deadAfter -> SseHealthState.Stale
            else -> SseHealthState.Dead
        }
    }

    /**
     * The TS's exact boolean verdict (Healthy boundary == `sseLaneHealthy`
     * true): kept as its own function so the parity tests and the
     * poll-ladder's `sseHealthy` input share one definition with the web.
     */
    fun laneHealthy(nowMs: Long, lastEventAtMs: Long, expectedIntervalMs: Long = DEFAULT_EXPECTED_INTERVAL_MS): Boolean =
        nowMs - lastEventAtMs < expectedIntervalMs * graceFactor

    companion object {
        /**
         * TS `SSE_STALE_MS = 5_000` expressed as a multiple of the 1 s push
         * interval: 5 intervals of silence = stale (see the class table).
         */
        const val DEFAULT_GRACE_FACTOR = 5.0

        /** The added Kotlin dead bar: 2× the stale point (10 s at defaults). */
        const val DEFAULT_DEAD_FACTOR = 2.0

        /** The device's status push cadence (SseStatusStream.DEFAULT_INTERVAL_MS = 1000). */
        const val DEFAULT_EXPECTED_INTERVAL_MS = 1_000L

        /** The TS literal, kept for parity assertions. */
        const val TS_SSE_STALE_MS = 5_000L
    }
}
