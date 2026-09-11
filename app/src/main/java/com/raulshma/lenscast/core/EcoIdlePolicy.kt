package com.raulshma.lenscast.core

/**
 * The pure decision core behind the eco idle-fps mode (battery-powered idle
 * sessions): when the toggle is on, the device is off charger, no stream
 * consumer is connected, and thermal is NORMAL, the frame rate drops to an
 * eco floor (and live-audio encoding pauses); the first consumer, charger,
 * or thermal event restores the full rate.
 *
 * Priority is explicit: eco only applies while thermal reads NORMAL — a
 * thermal escalation always hands the frame rate back to the thermal ladder
 * (RESTORE), never stacks on top of a dropped rate. Restore is immediate;
 * the anti-thrash guard sits on the drop side: after every restore, a
 * [RESTORE_COOLDOWN_MS] window must lapse before the mode may drop again,
 * and the idle conditions must hold continuously for [IDLE_STABLE_MS] first,
 * so a flapping client (connect → disconnect → connect) cannot churn the
 * encoder between rates.
 *
 * State is carried by the caller ([State]) and every verdict is a pure
 * function of the inputs, so the whole ladder is JVM-tested; the runtime
 * keeps only the poll loop and the applied side effects.
 */
object EcoIdlePolicy {

    /**
     * How long the caller waits between evaluations. Purely a cadence
     * constant — the verdicts themselves never depend on it.
     */
    const val EVALUATION_INTERVAL_MS = 5_000L

    /** After a restore, the mode may not drop again for this long. */
    const val RESTORE_COOLDOWN_MS = 30_000L

    /** The idle conditions (enabled, off charger, no consumers, thermal NORMAL) must hold this long before the first drop. */
    const val IDLE_STABLE_MS = 15_000L

    sealed interface Verdict {
        /** Apply the eco floor (frame rate down, live audio paused). */
        data object Drop : Verdict

        /** Return the frame rate to the user's setting (and resume live audio). */
        data object Restore : Verdict

        /** Keep whatever state is currently applied. */
        data object Hold : Verdict
    }

    /** The caller-carried bookkeeping between evaluations. */
    data class State(
        val active: Boolean = false,
        /** When the idle conditions were first observed to hold; null while they do not. */
        val idleSinceMs: Long? = null,
        /** Earliest moment the mode may drop again after a restore. */
        val restoreNotBeforeMs: Long = 0L,
    )

    data class Decision(val verdict: Verdict, val nextState: State)

    /**
     * The eco floor for a user frame rate: [StreamDefaults.ECO_IDLE_FPS]
     * clamped into `[StreamDefaults.ADAPTIVE_FPS_MIN, userFps]` — eco lowers
     * the rate, it never raises it above what the user configured.
     */
    fun floorFps(
        userFps: Int,
        ecoFps: Int = StreamDefaults.ECO_IDLE_FPS,
        minFps: Int = StreamDefaults.ADAPTIVE_FPS_MIN,
    ): Int = ecoFps.coerceIn(minFps, maxOf(minFps, userFps))

    fun evaluate(
        enabled: Boolean,
        charging: Boolean,
        hasConsumers: Boolean,
        thermalNormal: Boolean,
        nowMs: Long,
        state: State,
    ): Decision {
        val idleConditionsHold = enabled && !charging && !hasConsumers && thermalNormal
        if (!idleConditionsHold) {
            return if (state.active) {
                Decision(
                    verdict = Verdict.Restore,
                    nextState = State(
                        active = false,
                        idleSinceMs = null,
                        restoreNotBeforeMs = nowMs + RESTORE_COOLDOWN_MS,
                    ),
                )
            } else {
                Decision(
                    verdict = Verdict.Hold,
                    nextState = State(active = false, idleSinceMs = null, restoreNotBeforeMs = state.restoreNotBeforeMs),
                )
            }
        }

        // Idle conditions hold — but a mode already dropped just keeps holding.
        if (state.active) return Decision(Verdict.Hold, state)

        val idleSinceMs = state.idleSinceMs ?: nowMs
        val idleStable = nowMs - idleSinceMs >= IDLE_STABLE_MS
        val cooledDown = nowMs >= state.restoreNotBeforeMs
        return if (idleStable && cooledDown) {
            Decision(
                verdict = Verdict.Drop,
                nextState = State(active = true, idleSinceMs = idleSinceMs, restoreNotBeforeMs = state.restoreNotBeforeMs),
            )
        } else {
            Decision(
                verdict = Verdict.Hold,
                nextState = State(active = false, idleSinceMs = idleSinceMs, restoreNotBeforeMs = state.restoreNotBeforeMs),
            )
        }
    }
}
