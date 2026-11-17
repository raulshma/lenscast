package com.raulshma.lenscast.core

/**
 * Pure decisions for the crash-triggered relaunch (blueprint P0 item 1,
 * self-healing §4): how many crashes in a row the process has suffered and
 * how long it ran cleanly before the latest one decide whether the crash
 * handler arms [AppRelaunch], paces itself, or hands the night back to the
 * OS sticky restart. The counter survives process death — it persists in
 * [CrashMarkerJournal] beside the crash marker — because a loop guard whose
 * state dies with the crashing process guards nothing.
 *
 * **The exact crash-loop rule.** A crash that lands after
 * [CLEAN_UPTIME_WINDOW_MS] of continuous process uptime starts a fresh
 * series (`nextCrashCount` → 1): the previous series proved recoverable, so
 * the deterministic ~2 s relaunch stays justified. A crash that lands
 * *within* the window of its process's start is a loop crash: it increments
 * the persisted counter. The verdict ladder over that counter:
 * - 1 → [Verdict.ARM] — a fresh crash relaunches at [AppRelaunch.RELAUNCH_DELAY_MS].
 * - 2..[MAX_CONSECUTIVE_CRASHES] → [Verdict.BACKOFF] — still arming (the
 *   deterministic tier stays), but at [relaunchDelayMs]'s doubled pace, the
 *   same escalation the in-process watchdog uses, so a fast loop does not
 *   spin at exact-alarm cadence.
 * - >[MAX_CONSECUTIVE_CRASHES] → [Verdict.GIVE_UP_STICKY_ONLY] — the handler
 *   stops arming; `START_STICKY`'s OS restart (escalating backoff, blueprint
 *   resolved tension 1) is the only remaining tier. Give-up is *derived*
 *   from the persisted counter (`CrashMarkerJournal.State.gaveUp`), so it
 *   survives process death and needs no second persisted flag.
 *
 * Give-up clears by exactly two paths: a crash observed after sustained
 * clean uptime (counter resets to 1 — the first rule), or the app-start
 * reset (a process start that survives [CLEAN_UPTIME_WINDOW_MS] zeroes the
 * counter — the shell schedules this once per start; see
 * MainApplication's crash wiring). A manual reset is the journal's
 * [CrashMarkerJournal.reset], exposed for the telemetry surface (wave 3).
 */
object CrashRelaunchPolicy {

    /**
     * How many consecutive within-window crashes the deterministic tier may
     * cause before the handler stops arming: three loop crashes mean the
     * relaunch itself is not fixing the bug — each re-entry crashes within
     * seconds — and continuing would convert one bug into an exact-alarm
     * boot loop (self-healing §4's mandatory guard).
     */
    const val MAX_CONSECUTIVE_CRASHES = 3

    /**
     * The clean-uptime window: process uptime at or above this counts as a
     * sustained clean run, so the next crash starts a fresh series. Long
     * enough that no real loop crash (which lands within seconds of the
     * relaunch) can ever look clean, short enough that a nightly one-off
     * crash clears itself before the next night.
     */
    const val CLEAN_UPTIME_WINDOW_MS = 5 * 60_000L

    /** What the crash handler should do about the crash just observed. */
    enum class Verdict {
        /** Arm [AppRelaunch] at the restart delay — the deterministic relaunch. */
        ARM,

        /** Still arm, but at [relaunchDelayMs]'s backoff pace — a loop is forming. */
        BACKOFF,

        /** Do not arm: the OS sticky restart (tension 1) is the only tier left. */
        GIVE_UP_STICKY_ONLY,
    }

    /**
     * The counter after the observed crash: a crash after sustained clean
     * uptime starts a fresh series; a crash within the window joins the
     * existing one. [uptimeMs] is the crashed process's own uptime (elapsed
     * real time since its start), the input that separates a one-off crash
     * from a loop link.
     */
    fun nextCrashCount(previousCount: Int, uptimeMs: Long): Int =
        if (uptimeMs >= CLEAN_UPTIME_WINDOW_MS) 1 else previousCount + 1

    /** The verdict for the counter as it stands after the observed crash. */
    fun decide(crashCount: Int): Verdict = when {
        crashCount > MAX_CONSECUTIVE_CRASHES -> Verdict.GIVE_UP_STICKY_ONLY
        crashCount <= 1 -> Verdict.ARM
        else -> Verdict.BACKOFF
    }

    /**
     * The delay the relaunch should carry for this crash count: ARM keeps
     * [AppRelaunch.RELAUNCH_DELAY_MS] (the dashboard-restart pacing — the
     * response flush + teardown grace), BACKOFF doubles per loop crash off
     * the same base and cap as [WatchdogPolicy.backoffMs] (the in-process
     * ladder's pacing, so both recovery paths share one rhythm), give-up
     * has none.
     */
    fun relaunchDelayMs(crashCount: Int): Long = when (decide(crashCount)) {
        Verdict.ARM -> AppRelaunch.RELAUNCH_DELAY_MS
        Verdict.BACKOFF -> WatchdogPolicy.backoffMs(crashCount)
        Verdict.GIVE_UP_STICKY_ONLY -> 0L
    }
}
