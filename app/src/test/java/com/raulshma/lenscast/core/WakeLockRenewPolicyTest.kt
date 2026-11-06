package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streaming wake lock's renew ladder at its boundaries: an unheld lock
 * re-acquires, a young lock is left alone, and the halfway mark re-arms —
 * the exact boundary pair is pinned so a threshold edit is a reviewed
 * decision, not an accident.
 */
class WakeLockRenewPolicyTest {

    @Test
    fun `an unheld lock is re-acquired whatever the elapsed time reads`() {
        assertEquals(WakeLockRenewPolicy.Action.ACQUIRE, WakeLockRenewPolicy.resolve(held = false, elapsedSinceAcquireMs = 0L))
        assertEquals(
            WakeLockRenewPolicy.Action.ACQUIRE,
            WakeLockRenewPolicy.resolve(held = false, elapsedSinceAcquireMs = WakeLockRenewPolicy.TIMEOUT_MS * 2),
        )
    }

    @Test
    fun `a young lock is left alone`() {
        assertEquals(WakeLockRenewPolicy.Action.KEEP, WakeLockRenewPolicy.resolve(held = true, elapsedSinceAcquireMs = 0L))
        assertEquals(
            WakeLockRenewPolicy.Action.KEEP,
            WakeLockRenewPolicy.resolve(held = true, elapsedSinceAcquireMs = WakeLockRenewPolicy.RENEW_AFTER_MS - 1),
        )
    }

    @Test
    fun `a lock at the halfway mark re-arms`() {
        assertEquals(
            WakeLockRenewPolicy.Action.REARM,
            WakeLockRenewPolicy.resolve(held = true, elapsedSinceAcquireMs = WakeLockRenewPolicy.RENEW_AFTER_MS),
        )
        assertEquals(
            WakeLockRenewPolicy.Action.REARM,
            WakeLockRenewPolicy.resolve(held = true, elapsedSinceAcquireMs = WakeLockRenewPolicy.TIMEOUT_MS),
        )
    }

    @Test
    fun `the renew point is halfway to the timeout`() {
        assertEquals(WakeLockRenewPolicy.TIMEOUT_MS / 2, WakeLockRenewPolicy.RENEW_AFTER_MS)
    }
}
