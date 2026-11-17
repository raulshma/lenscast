package com.raulshma.lenscast.core

/**
 * Pure decisions for the alarm heartbeat's escalation (blueprint P0 item 3,
 * self-healing §3's integration sketch): given the one-shot health verdict
 * the heartbeat collected, the consecutive-miss count, the miss budget, and
 * the journal's user-stopped flag, what — if anything — should this fire do?
 *
 * Why the policy exists: the in-process watchdog dies with the process and
 * wedges with it, so the heartbeat is the OUT-OF-PROCESS observer — but an
 * out-of-process trigger that always jumped straight to the relaunch tier
 * would tear down recoverable states and escalate user kills into
 * resurrection. The ladder keeps the process-preserving tiers first
 * (blueprint resolved tension 2's invariant: soft tiers that keep the
 * process alive are strictly stronger than death-and-relaunch tiers), and
 * the user-stopped flag keeps it from fighting an explicit stop (wave 1's
 * journal flag — the same single source of intent the boot resume honors).
 *
 * **The ladder, one escalation per consecutive miss** (the caller counts the
 * current miss in — the same incremented-before-asking convention
 * [WatchdogPolicy.nextTick] documents):
 * - miss 1 → [EscalationTier.SOFT] — in-process rebind
 *   (`StreamingSession.recover(SOFT)`): most transient stalls die here with
 *   the foreground-started FGS (and its while-in-use camera standing)
 *   untouched.
 * - miss 2 → [EscalationTier.MEDIUM] — server restart + rebind under the
 *   surviving FGS.
 * - miss 3 → [EscalationTier.SOFT_RESTART] — the full in-process soft-restart
 *   ladder (blueprint P1 item 9, [SoftRestartLadder]): the quit ladder minus
 *   process death — recording stop, server stop, camera rebind, server
 *   re-arm. The deepest tier that still preserves the FGS's while-in-use
 *   camera standing; used when the lighter tiers could not clear the wedge.
 * - miss 4 → [EscalationTier.RELAUNCH] — the death-and-relaunch tier: the
 *   quit ladder plus the exact-alarm relaunch (T4's mechanism — the only
 *   cure for a process-level wedge the in-process tiers cannot see).
 * - RELAUNCH **holds** for every miss up to [WatchdogAlarmPolicy.decide]'s
 *   `maxMisses` (the quit may still be settling; one re-fire at the same
 *   tier is the retry), then the verdict is STAND_DOWN — the relaunch has
 *   been spent repeatedly without the server coming back, and an alarm that
 *   re-kills a device forever is worse than a dark one.
 *
 * **The wave 4 insertion point** (blueprint P1 item 9, in-process
 * soft-restart): [EscalationTier] is an ORDERED enum and
 * [tierFor] maps miss n to the n-th entry — inserting `SOFT_RESTART`
 * between [EscalationTier.MEDIUM] and [EscalationTier.RELAUNCH] re-maps the
 * whole ladder with no decision-logic change (miss 3 became the
 * soft-restart, miss 4 the relaunch, the hold/stand-down rule untouched;
 * [DEFAULT_MAX_MISSES] grew by the one extra tier so the top tier keeps its
 * one-fire hold). The only invariant to preserve is that RELAUNCH stays the
 * LAST entry (pinned by test): "beyond the ladder" must keep meaning the
 * relaunch tier.
 *
 * **The server-only shift** (spec tension-2 honesty): the ladder's head
 * assumes a session — SOFT is `StreamingSession.recover(SOFT)`, a camera
 * rebind, and in the post-relaunch SERVER-ONLY state there is no session
 * (and no camera bound), so a SOFT verdict there would burn a full 15-minute
 * interval doing nothing. [decide]'s `serverOnlyMode` input (the health
 * snapshot's own mode, threaded through the heartbeat's one-shot read)
 * drops the session-only head: the first miss lands on the first tier that
 * does real work without a session — MEDIUM, whose session-less recover
 * restarts the server (StreamWatchdog's documented server-seam behavior) —
 * then SOFT_RESTART, then RELAUNCH last (the last-tier invariant holds in
 * both modes; pinned by test). RELAUNCH also stays the only tier the budget
 * hold repeats: [maxMisses] is unchanged, so the server-only ladder's hold
 * runs one fire longer than the session ladder's — bounded, and the honest
 * cost of starting the escalation one interval earlier.
 *
 * STAND_DOWN also answers a healthy snapshot (RESET action — the caller
 * zeroes its miss bookkeeping) and a user-stopped journal flag (the flag
 * takes precedence over everything: a swipe-kill recorded by
 * `onTaskRemoved` must never be resurrected by an alarm, even a healthy
 * one — same lifetime rule as the boot resume's).
 */
object WatchdogAlarmPolicy {

    /**
     * The default miss budget: the ladder's four tiers plus one hold. One
     * heartbeat interval (15 min) per miss means the whole escalation from
     * first miss to give-up spans about 75 minutes — bounded, and slow enough
     * that a flapping recovery has room to land between tiers. (Wave 4's
     * SOFT_RESTART insertion grew this by one: the top tier's one-fire hold
     * semantics are preserved, not compressed.)
     */
    const val DEFAULT_MAX_MISSES = 5

    /**
     * The ordered escalation ladder. The ORDER is the decision: the mapping
     * is strictly ordinal ([tierFor]), and wave 4's soft-restart tier sits
     * between MEDIUM and RELAUNCH — see the class KDoc before touching this
     * enum.
     */
    enum class EscalationTier {
        /** In-process rebind — `StreamingSession.recover(SOFT)`; the process survives. */
        SOFT,

        /** Server restart + rebind — `StreamingSession.recover(MEDIUM)`; the process survives. */
        MEDIUM,

        /**
         * The full in-process soft-restart ladder ([SoftRestartLadder],
         * blueprint P1 item 9) — the quit ladder minus process death; the
         * process and its FGS camera standing survive.
         */
        SOFT_RESTART,

        /** Death-and-relaunch — the quit ladder plus [AppRelaunch]; the top tier. */
        RELAUNCH,
    }

    /** What the heartbeat's shell should do with one fired alarm. */
    enum class Action {
        /** Run the decision's [tier]'s mechanics. */
        ESCALATE,

        /** The snapshot is healthy: zero the miss bookkeeping, touch nothing. */
        RESET,

        /** Do nothing — the user-stopped flag is set, or the miss budget is spent. */
        STAND_DOWN,
    }

    /** The verdict for one fired alarm: an action plus its tier when [Action.ESCALATE]. */
    data class Decision(val action: Action, val tier: EscalationTier? = null)

    /**
     * The tier for the miss count: strictly ordinal, miss 1 = the first
     * entry. In `serverOnlyMode` the session-only head ([EscalationTier.SOFT]
     * — a camera rebind with no session to rebind) is dropped, so miss 1
     * lands on MEDIUM, the first tier that does real work without a session
     * (see the class KDoc's server-only shift). null = beyond the ladder
     * (the caller then consults the miss budget — see [decide]).
     */
    fun tierFor(consecutiveMisses: Int, serverOnlyMode: Boolean = false): EscalationTier? {
        val ladder =
            if (serverOnlyMode) EscalationTier.entries.drop(1) else EscalationTier.entries.toList()
        return ladder.getOrNull(consecutiveMisses - 1)
    }

    /**
     * The verdict for one fired alarm. [consecutiveMisses] includes the
     * current miss (incremented by the caller before asking); a healthy
     * snapshot's count is conventionally 0. [userStopped] is the
     * StreamStateJournal's swipe-kill flag, read at fire time.
     * [serverOnlyMode] is the same health read's mode (one read, so the
     * verdict and the mode cannot disagree): true shifts the ladder's first
     * real-work tier down one, because the SOFT tier is a session's camera
     * rebind and server-only sessions do not exist.
     */
    fun decide(
        healthy: Boolean,
        consecutiveMisses: Int,
        maxMisses: Int,
        userStopped: Boolean,
        serverOnlyMode: Boolean = false,
    ): Decision = when {
        userStopped -> Decision(Action.STAND_DOWN)
        healthy -> Decision(Action.RESET)
        else -> {
            val tier = tierFor(consecutiveMisses, serverOnlyMode)
            when {
                tier != null -> Decision(Action.ESCALATE, tier)
                // Beyond the ladder the top tier holds (the quit may still
                // be settling) until the budget is spent.
                consecutiveMisses <= maxMisses -> Decision(Action.ESCALATE, EscalationTier.RELAUNCH)
                else -> Decision(Action.STAND_DOWN)
            }
        }
    }
}
