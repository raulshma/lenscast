package com.raulshma.lenscast.core

import android.os.PowerManager as AndroidPowerManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The wake-lock slots behind the app's awake posture, on Robolectric's
 * shadowed [android.os.PowerManager]: the streaming slot's timeout +
 * renew delegation (young locks untouched, an unheld slot re-acquired —
 * the ladder's thresholds and boundary live in [WakeLockRenewPolicyTest])
 * and the keep-awake slot's independence — a session end releases only
 * its own hold, never the always-on one.
 *
 * Assertions ride the wrapper's own slot fields (read reflectively), not
 * `ShadowPowerManager.getLatestWakeLock()`: the shadow's static tracks the
 * last-created lock JVM-wide and is not reset between test methods, so its
 * reads bleed across tests and gradle-daemon runs. Instance identity per
 * [PowerManager] is the deterministic ground truth; OS-level held/release
 * semantics are the platform `WakeLock` API the wrapper calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerManagerTest {

    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        powerManager = PowerManager(RuntimeEnvironment.getApplication())
    }

    // ── the streaming slot ──

    @Test
    fun `acquire holds the lock and a repeat release is safe`() {
        powerManager.acquireWakeLock()
        val lock = streamingSlot()
        assertNotNull(lock)
        assertTrue(lock!!.isHeld)
        // A second release is a no-op on the cleared flags, not a crash.
        powerManager.releaseWakeLock()
        assertNull(streamingSlot())
        powerManager.releaseWakeLock()
    }

    @Test
    fun `a second acquire while held is a no-op`() {
        powerManager.acquireWakeLock()
        val first = streamingSlot()
        powerManager.acquireWakeLock()
        // No new lock was minted behind the guard.
        assertSame(first, streamingSlot())
        assertNotNull(first)
    }

    @Test
    fun `renew leaves a young lock alone`() {
        powerManager.acquireWakeLock()
        val before = streamingSlot()
        powerManager.renewWakeLock()
        // Acquired microseconds ago, far inside the 30-minute renew point —
        // the delegation must not churn the real lock.
        assertSame(before, streamingSlot())
        assertTrue(before!!.isHeld)
    }

    @Test
    fun `renew re-acquires a lock that was never taken`() {
        powerManager.renewWakeLock()
        val first = streamingSlot()
        assertNotNull(first)
        assertTrue(first!!.isHeld)
    }

    // ── the keep-awake slot ──

    @Test
    fun `keep-awake acquires, is idempotent, and releases`() {
        powerManager.acquireKeepAwake()
        val lock = keepAwakeSlot()
        assertNotNull(lock)
        assertTrue(lock!!.isHeld)
        powerManager.acquireKeepAwake()
        assertSame("a second keep-awake acquire minted a new lock", lock, keepAwakeSlot())
        // A second release is a no-op, not a crash on the nulled reference.
        powerManager.releaseKeepAwake()
        assertNull(keepAwakeSlot())
        powerManager.releaseKeepAwake()
    }

    @Test
    fun `the streaming release never touches the keep-awake slot`() {
        powerManager.acquireWakeLock()
        powerManager.acquireKeepAwake()
        val keepAwake = keepAwakeSlot()

        powerManager.releaseWakeLock()
        // The keep-awake slot survived the session end untouched: the same
        // lock instance, still referenced, and a further acquire stays
        // idempotent — the wrapper's keep-awake flags were not cleared by
        // the streaming release.
        assertSame("the session end disturbed the always-on hold", keepAwake, keepAwakeSlot())
        powerManager.acquireKeepAwake()
        assertSame(keepAwake, keepAwakeSlot())
    }

    @Test
    fun `release drops both slots and the keep-awake flag`() {
        powerManager.acquireWakeLock()
        powerManager.acquireKeepAwake()
        val keepAwake = keepAwakeSlot()

        powerManager.release()

        // A release-acquire cycle after release() starts fresh, not wedged
        // on a stale flag: each slot mints a new lock.
        powerManager.acquireKeepAwake()
        val freshKeep = keepAwakeSlot()
        assertNotSame("the keep-awake flag outlived release()", keepAwake, freshKeep)
        assertTrue(freshKeep!!.isHeld)

        powerManager.acquireWakeLock()
        assertNotSame(freshKeep, streamingSlot())
    }

    // ── plumbing ──

    private fun streamingSlot(): AndroidPowerManager.WakeLock? = slotField("wakeLock")

    private fun keepAwakeSlot(): AndroidPowerManager.WakeLock? = slotField("keepAwakeLock")

    private fun slotField(name: String): AndroidPowerManager.WakeLock? {
        val field = PowerManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(powerManager) as AndroidPowerManager.WakeLock?
    }
}
