package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crash-loop guard's exact rule (blueprint P0 item 1, self-healing §4):
 * these pins are the contract the crash handler's KDoc cites.
 */
class CrashRelaunchPolicyTest {

    // ── nextCrashCount: the loop window decides whether a crash joins the series ──

    @Test
    fun `a crash after sustained clean uptime starts a fresh series`() {
        assertEquals(1, CrashRelaunchPolicy.nextCrashCount(previousCount = 0, uptimeMs = CrashRelaunchPolicy.CLEAN_UPTIME_WINDOW_MS))
        assertEquals(1, CrashRelaunchPolicy.nextCrashCount(previousCount = 3, uptimeMs = CrashRelaunchPolicy.CLEAN_UPTIME_WINDOW_MS + 1))
    }

    @Test
    fun `a crash soon after the process start joins the existing series`() {
        assertEquals(2, CrashRelaunchPolicy.nextCrashCount(previousCount = 1, uptimeMs = CrashRelaunchPolicy.CLEAN_UPTIME_WINDOW_MS - 1))
        assertEquals(4, CrashRelaunchPolicy.nextCrashCount(previousCount = 3, uptimeMs = 0))
    }

    @Test
    fun `the very first crash counts as one whatever the uptime`() {
        assertEquals(1, CrashRelaunchPolicy.nextCrashCount(previousCount = 0, uptimeMs = 0))
    }

    // ── decide: the verdict ladder ──

    @Test
    fun `a fresh crash arms the deterministic relaunch`() {
        assertEquals(CrashRelaunchPolicy.Verdict.ARM, CrashRelaunchPolicy.decide(crashCount = 1))
    }

    @Test
    fun `loop crashes under the max back off instead of arming blind`() {
        assertEquals(CrashRelaunchPolicy.Verdict.BACKOFF, CrashRelaunchPolicy.decide(crashCount = 2))
        assertEquals(CrashRelaunchPolicy.Verdict.BACKOFF, CrashRelaunchPolicy.decide(crashCount = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES))
    }

    @Test
    fun `past the max the verdict is give-up and it stays there`() {
        val over = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 1
        assertEquals(CrashRelaunchPolicy.Verdict.GIVE_UP_STICKY_ONLY, CrashRelaunchPolicy.decide(crashCount = over))
        assertEquals(CrashRelaunchPolicy.Verdict.GIVE_UP_STICKY_ONLY, CrashRelaunchPolicy.decide(crashCount = over + 10))
    }

    // ── the full loop: crash → relaunch → crash, until give-up, until clean uptime ──

    @Test
    fun `a tight crash loop arms, backs off twice, then gives up`() {
        var count = 0
        fun crash(uptimeMs: Long): CrashRelaunchPolicy.Verdict {
            count = CrashRelaunchPolicy.nextCrashCount(count, uptimeMs)
            return CrashRelaunchPolicy.decide(count)
        }

        assertEquals(CrashRelaunchPolicy.Verdict.ARM, crash(uptimeMs = 80_000)) // fresh crash, ran a while
        assertEquals(CrashRelaunchPolicy.Verdict.BACKOFF, crash(uptimeMs = 2_100)) // relaunch crashed soon
        assertEquals(CrashRelaunchPolicy.Verdict.BACKOFF, crash(uptimeMs = 2_100))
        assertEquals(CrashRelaunchPolicy.Verdict.GIVE_UP_STICKY_ONLY, crash(uptimeMs = 2_100))
        // Give-up is sticky-only — more loop crashes never re-arm, and the
        // counter keeps proving the loop is alive.
        assertEquals(CrashRelaunchPolicy.Verdict.GIVE_UP_STICKY_ONLY, crash(uptimeMs = 2_100))
    }

    @Test
    fun `sustained clean uptime between crashes re-arms`() {
        var count = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 5 // deep in give-up
        count = CrashRelaunchPolicy.nextCrashCount(count, uptimeMs = CrashRelaunchPolicy.CLEAN_UPTIME_WINDOW_MS)
        assertEquals(1, count)
        assertEquals(CrashRelaunchPolicy.Verdict.ARM, CrashRelaunchPolicy.decide(count))
    }

    // ── relaunchDelayMs: ARM uses the restart-alarm delay, BACKOFF paces itself ──

    @Test
    fun `arm keeps the restart alarm's two-second delay`() {
        assertEquals(AppRelaunch.RELAUNCH_DELAY_MS, CrashRelaunchPolicy.relaunchDelayMs(crashCount = 1))
    }

    @Test
    fun `backoff doubles per loop crash and never exceeds the watchdog cap`() {
        assertEquals(4_000L, CrashRelaunchPolicy.relaunchDelayMs(crashCount = 2))
        assertEquals(8_000L, CrashRelaunchPolicy.relaunchDelayMs(crashCount = 3))
    }

    @Test
    fun `give-up has no delay to speak of`() {
        assertEquals(0L, CrashRelaunchPolicy.relaunchDelayMs(crashCount = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 1))
    }

    // ── the marker state's give-up observation, for wave 3's telemetry ──

    @Test
    fun `gaveUp is derived from the persisted counter`() {
        assertEquals(false, CrashMarkerJournal.State(consecutiveCrashes = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES).gaveUp)
        assertEquals(true, CrashMarkerJournal.State(consecutiveCrashes = CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES + 1).gaveUp)
        assertEquals(true, CrashMarkerJournal.State(consecutiveCrashes = 99).gaveUp)
    }
}
