package com.raulshma.lenscast.streaming.whep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHEP viewer cap and dead-session GC, pinned as pure verdicts (the
 * registry is the only JVM-testable half of the endpoint — the sessions
 * themselves are libwebrtc, device-only):
 *
 *  - the concurrent-viewer cap admits up to [StreamDefaults.WHEP_MAX_VIEWERS]
 *    and no more;
 *  - an offer that never reached CONNECTED is reaped past the connect window
 *    (a vanished viewer must not hold a hardware encoder slot);
 *  - a connected session that later went DISCONNECTED is reaped past the
 *    grace window; a connected session without a disconnect mark never is;
 *  - reapIds removes what it names, so a second pass is a no-op.
 */
class WhepSessionRegistryTest {

    private fun registry(
        connectReapMs: Long = WhepSessionRegistry.CONNECT_REAP_MS,
        disconnectedReapMs: Long = WhepSessionRegistry.DISCONNECTED_REAP_MS,
    ) = WhepSessionRegistry(connectReapMs, disconnectedReapMs)

    // ── the cap ──

    @Test
    fun `canAdmit holds the viewer cap`() {
        val reg = registry()
        val cap = com.raulshma.lenscast.core.StreamDefaults.WHEP_MAX_VIEWERS
        repeat(cap) { i ->
            assertTrue("admission $i under the cap", reg.canAdmit(cap))
            assertTrue(reg.admit("s$i", nowMs = 1_000L))
        }
        assertFalse("the cap refuses one more viewer", reg.canAdmit(cap))
        assertEquals(cap, reg.size())
    }

    @Test
    fun `a removal frees a viewer slot`() {
        val reg = registry()
        repeat(4) { i -> reg.admit("s$i", 1_000L) }
        assertFalse(reg.canAdmit(4))
        assertTrue(reg.remove("s2"))
        assertTrue(reg.canAdmit(4))
    }

    @Test
    fun `a duplicate id is never re-admitted`() {
        val reg = registry()
        assertTrue(reg.admit("dup", 1_000L))
        assertFalse(reg.admit("dup", 2_000L))
        assertEquals(1_000L, reg.get("dup")!!.createdAtMs)
        assertEquals(1, reg.size())
    }

    // ── never connected: the connect-reap window ──

    @Test
    fun `a session that never connected is reaped past the connect window`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("ghost", 0L)
        assertTrue(reg.reapIds(9_999L).isEmpty())
        assertEquals(listOf("ghost"), reg.reapIds(10_001L))
        assertEquals(0, reg.size())
    }

    @Test
    fun `the reap window boundary is exclusive`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("edge", 1_000L)
        assertTrue("exactly at the window is not yet dead", reg.reapIds(11_000L).isEmpty())
        assertEquals(listOf("edge"), reg.reapIds(11_001L))
    }

    // ── connected then (maybe) disconnected ──

    @Test
    fun `a connected session is never reaped by the connect window`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("live", 0L)
        reg.markConnected("live", 5_000L)
        assertTrue(reg.reapIds(600_000L).isEmpty())
    }

    @Test
    fun `a disconnected mark alone (without a connect) never reaps`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("shy", 0L)
        reg.markDisconnected("shy", 1_000L)
        assertTrue(reg.reapIds(5_000L).isEmpty()) // inside the connect window anyway
        assertEquals(listOf("shy"), reg.reapIds(10_001L)) // and past it, the connect verdict applies
    }

    @Test
    fun `a session disconnected past the grace window is reaped`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("dropped", 0L)
        reg.markConnected("dropped", 1_000L)
        reg.markDisconnected("dropped", 2_000L)
        assertTrue(reg.reapIds(31_999L).isEmpty())
        assertEquals(listOf("dropped"), reg.reapIds(32_001L))
    }

    @Test
    fun `a fresh disconnected mark restarts the grace window`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("flappy", 0L)
        reg.markConnected("flappy", 1_000L)
        reg.markDisconnected("flappy", 2_000L)
        reg.markConnected("flappy", 3_000L) // ICE recovered
        reg.markDisconnected("flappy", 40_000L) // and flapped again — the clock restarts here
        assertTrue(reg.reapIds(60_000L).isEmpty())
        assertEquals(listOf("flappy"), reg.reapIds(70_001L))
    }

    @Test
    fun `marks on unknown ids are ignored`() {
        val reg = registry()
        reg.markConnected("nobody", 1L)
        reg.markDisconnected("nobody", 2L)
        assertNull(reg.get("nobody"))
        assertTrue(reg.reapIds(1_000_000L).isEmpty())
    }

    // ── bookkeeping shape ──

    @Test
    fun `reapIds removes what it names so a second pass is a no-op`() {
        val reg = registry(connectReapMs = 10_000L, disconnectedReapMs = 30_000L)
        reg.admit("a", 0L)
        reg.admit("b", 0L)
        reg.admit("c", 0L)
        assertEquals(listOf("a", "b", "c").toSortedSet(), reg.reapIds(11_000L).toSortedSet())
        assertTrue(reg.reapIds(12_000L).isEmpty())
        assertTrue(reg.ids().isEmpty())
    }

    @Test
    fun `remove and clear empty the registry`() {
        val reg = registry()
        reg.admit("x", 0L)
        assertTrue(reg.remove("x"))
        assertFalse(reg.remove("x"))
        reg.admit("y", 0L)
        reg.clear()
        assertEquals(0, reg.size())
    }
}
