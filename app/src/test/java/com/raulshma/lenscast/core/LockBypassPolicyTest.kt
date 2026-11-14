package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LockBypassPolicyTest {

    private fun resolve(
        isKeyguardLocked: Boolean = true,
        postNotificationsGranted: Boolean = true,
        canUseFullScreenIntent: Boolean = true,
        nowMs: Long = 60_000L,
        lastAttemptAtMs: Long = 0L,
    ) = LockBypassPolicy.resolve(
        isKeyguardLocked, postNotificationsGranted, canUseFullScreenIntent, nowMs, lastAttemptAtMs,
    )

    // ── branch order: unlocked > no posting > no full-screen intent > throttle ──

    @Test
    fun `every gate passed posts the wake`() {
        val result = resolve()
        assertEquals(LockBypassPolicy.Verdict.POST, result)
    }

    @Test
    fun `unlocked skips before every other gate`() {
        val result = resolve(
            isKeyguardLocked = false,
            postNotificationsGranted = false,
            canUseFullScreenIntent = false,
        )
        assertEquals(LockBypassPolicy.Verdict.SKIP_UNLOCKED, result)
    }

    @Test
    fun `missing POST_NOTIFICATIONS skips`() {
        assertEquals(
            LockBypassPolicy.Verdict.SKIP_NO_POST_NOTIFICATIONS,
            resolve(postNotificationsGranted = false),
        )
    }

    @Test
    fun `missing full-screen intent skips`() {
        assertEquals(
            LockBypassPolicy.Verdict.SKIP_NO_FULL_SCREEN_INTENT,
            resolve(canUseFullScreenIntent = false),
        )
    }

    @Test
    fun `missing posting wins over missing full-screen intent`() {
        assertEquals(
            LockBypassPolicy.Verdict.SKIP_NO_POST_NOTIFICATIONS,
            resolve(postNotificationsGranted = false, canUseFullScreenIntent = false),
        )
    }

    // ── throttle floor ──

    @Test
    fun `no prior attempt never throttles the first wake`() {
        val result = resolve(nowMs = 5_000L, lastAttemptAtMs = -1L)
        assertEquals(LockBypassPolicy.Verdict.POST, result)
    }

    @Test
    fun `attempt inside the re-arm window is throttled`() {
        val result = resolve(nowMs = 60_000L, lastAttemptAtMs = 60_000L - LockBypassPolicy.MIN_ATTEMPT_INTERVAL_MS + 1)
        assertEquals(LockBypassPolicy.Verdict.SKIP_THROTTLED, result)
    }

    @Test
    fun `attempt at exactly the re-arm window posts`() {
        val result = resolve(nowMs = 60_000L, lastAttemptAtMs = 60_000L - LockBypassPolicy.MIN_ATTEMPT_INTERVAL_MS)
        assertEquals(LockBypassPolicy.Verdict.POST, result)
    }

    @Test
    fun `throttle skips even when permissions regressed too`() {
        assertEquals(
            LockBypassPolicy.Verdict.SKIP_NO_POST_NOTIFICATIONS,
            resolve(postNotificationsGranted = false, nowMs = 60_000L, lastAttemptAtMs = 59_999L),
        )
    }
}
