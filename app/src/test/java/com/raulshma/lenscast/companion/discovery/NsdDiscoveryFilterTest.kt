package com.raulshma.lenscast.companion.discovery

import com.raulshma.lenscast.streaming.DiscoveryRegistrationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN radar's pure verdicts: which discovered `_http._tcp` records are
 * LensCast devices (the `lenscast=1` TXT contract the camera app publishes),
 * and what the UI calls them once they pass. These pin both sides of the
 * contract — the filter's read AND the publisher's write-side constants —
 * so a renamed key fails here instead of silently emptying the pairing
 * sheet on a real LAN full of printers and AirPlay boxes.
 */
class NsdDiscoveryFilterTest {

    // ── isLensCastService ──

    @Test
    fun `a record carrying the lenscast TXT contract is a LensCast device`() {
        assertTrue(
            NsdDiscoveryFilter.isLensCastService(
                serviceName = "LensCast-Pixel8",
                txtRecords = mapOf(NsdDiscoveryFilter.LENSCAST_TXT_KEY to "1"),
            )
        )
    }

    @Test
    fun `a record without the TXT marker is not a LensCast device`() {
        // The LAN's other _http._tcp shouters: no marker, no entry.
        assertFalse(NsdDiscoveryFilter.isLensCastService("Office Printer", emptyMap()))
        assertFalse(
            NsdDiscoveryFilter.isLensCastService(
                "AirPlay",
                mapOf("model" to "AppleTV3", "features" to "0x5A7FFFF7"),
            )
        )
    }

    @Test
    fun `a wrong marker value is rejected - presence is not enough`() {
        assertFalse(
            NsdDiscoveryFilter.isLensCastService(
                "LensCast-Pixel8",
                mapOf(NsdDiscoveryFilter.LENSCAST_TXT_KEY to "0"),
            )
        )
    }

    @Test
    fun `the TXT key is matched case-insensitively`() {
        // NsdManager lowercases TXT keys on resolve; hand-built or older
        // stacks may not — the verdict must not hinge on it.
        assertTrue(
            NsdDiscoveryFilter.isLensCastService(
                "LensCast-Pixel8",
                mapOf("LensCast" to "1"),
            )
        )
    }

    @Test
    fun `the filter and the publisher spell the contract identically`() {
        // The shared vocabulary: the discovery side re-exports the policy's
        // constants, and the policy publishes exactly that record.
        assertEquals("lenscast", NsdDiscoveryFilter.LENSCAST_TXT_KEY)
        assertEquals("1", NsdDiscoveryFilter.LENSCAST_TXT_VALUE)
        assertEquals(
            mapOf(NsdDiscoveryFilter.LENSCAST_TXT_KEY to NsdDiscoveryFilter.LENSCAST_TXT_VALUE),
            DiscoveryRegistrationPolicy.httpTxtRecords(),
        )
    }

    // ── friendlyName ──

    @Test
    fun `the resolver's DN suffix is stripped from the display name`() {
        assertEquals(
            "LensCast-Pixel8",
            NsdDiscoveryFilter.friendlyName("LensCast-Pixel8._http._tcp."),
        )
        assertEquals(
            "LensCast-Pixel8",
            NsdDiscoveryFilter.friendlyName("LensCast-Pixel8._http._tcp"),
        )
    }

    @Test
    fun `a plain service name passes through untouched`() {
        assertEquals("LensCast-Pixel8", NsdDiscoveryFilter.friendlyName("LensCast-Pixel8"))
        // Only the mDNS suffix is stripped — an embedded mention stays.
        assertEquals(
            "LensCast._http._tcp Mirror",
            NsdDiscoveryFilter.friendlyName("LensCast._http._tcp Mirror"),
        )
    }

    @Test
    fun `blank names stay blank without crashing`() {
        assertEquals("", NsdDiscoveryFilter.friendlyName(""))
        assertEquals("", NsdDiscoveryFilter.friendlyName("   "))
    }

    @Test
    fun `prioritized candidate hint recognizes LensCast service names case-insensitively`() {
        assertTrue(NsdDiscoveryFilter.isPrioritizedCandidate("LensCast-Nokia-6-1-Plus"))
        assertTrue(NsdDiscoveryFilter.isPrioritizedCandidate("lenscast-pixel8._http._tcp."))
        assertFalse(NsdDiscoveryFilter.isPrioritizedCandidate("Brother HL-L2395DW"))
    }
}
