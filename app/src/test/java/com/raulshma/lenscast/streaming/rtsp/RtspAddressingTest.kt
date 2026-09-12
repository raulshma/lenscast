package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure listener-addressing knowledge ([RtspAddressing]):
 * the dual-stack bind literal, the v4-mapped→IPv4 SDP advertisement
 * normalization, the address-family selection, and the URL bracket rule.
 */
class RtspAddressingTest {

    @Test
    fun `bind host is the IPv6 any-address`() {
        assertEquals("::", RtspAddressing.BIND_HOST)
        assertTrue(RtspAddressing.isIpv6(RtspAddressing.BIND_HOST))
    }

    @Test
    fun `v4-mapped literals reduce to plain IPv4`() {
        assertEquals("192.168.1.5", RtspAddressing.advertisedHost("::ffff:192.168.1.5"))
        assertEquals("10.0.0.1", RtspAddressing.advertisedHost("::FFFF:10.0.0.1"))
    }

    @Test
    fun `unspecified addresses answer null so the caller falls back`() {
        assertNull(RtspAddressing.advertisedHost("0.0.0.0"))
        assertNull(RtspAddressing.advertisedHost("::"))
        assertNull(RtspAddressing.advertisedHost(null))
        assertNull(RtspAddressing.advertisedHost(""))
    }

    @Test
    fun `concrete literal addresses pass through`() {
        assertEquals("192.168.1.5", RtspAddressing.advertisedHost("192.168.1.5"))
        assertEquals("fe80::1", RtspAddressing.advertisedHost(" fe80::1 "))
        assertTrue(RtspAddressing.advertisedHost("fe80::1")!!.contains(':'))
    }

    @Test
    fun `network type follows the host family`() {
        assertEquals("IP4", RtspAddressing.networkType("192.168.1.5"))
        assertEquals("IP6", RtspAddressing.networkType("fe80::1"))
        assertEquals("::", RtspAddressing.connectionAddress("IP6"))
        assertEquals("0.0.0.0", RtspAddressing.connectionAddress("IP4"))
    }

    @Test
    fun `url hosts bracket only IPv6`() {
        assertEquals("[fe80::1]", RtspAddressing.urlHost("fe80::1"))
        assertEquals("192.168.1.5", RtspAddressing.urlHost("192.168.1.5"))
    }

    @Test
    fun `hostile v4-mapped lookalikes pass through untouched`() {
        // The strict 4-octet regex must not rewrite partial junk — it keeps
        // its original (broken) spelling so callers can see it is unusable.
        assertEquals("::ffff:1.2.3", RtspAddressing.advertisedHost("::ffff:1.2.3"))
        assertEquals("::ffff:1.2.3.4.5", RtspAddressing.advertisedHost("::ffff:1.2.3.4.5"))
    }
}
