package com.raulshma.lenscast.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mDNS registration verdicts: when an RTSP advert refresh is a no-op
 * versus a re-register, and when a listener must be unregistered. The
 * windows these pin are the ones the flag-gated manager got wrong — a stop
 * before the async registration confirmed skipped the unregister (ghost
 * advert until process death), and a late auth toggle never updated the
 * TXT record because [ServiceDiscoveryManager] early-returned on the
 * registered flag.
 */
class DiscoveryRegistrationPolicyTest {

    private fun advert(
        port: Int = 8554,
        path: String = "live",
        authRequired: Boolean = false,
    ) = ServiceDiscoveryManager.RtspAdvert(port = port, path = path, authRequired = authRequired)

    // ── shouldSkipRtspRegister ──

    @Test
    fun `a first registration never skips`() {
        assertFalse(DiscoveryRegistrationPolicy.shouldSkipRtspRegister(null, advert()))
    }

    @Test
    fun `identical params to the last attempt skip as a no-op`() {
        assertTrue(
            DiscoveryRegistrationPolicy.shouldSkipRtspRegister(
                existing = advert(authRequired = false),
                requested = advert(authRequired = false),
            )
        )
    }

    @Test
    fun `a late auth toggle re-registers`() {
        assertFalse(
            DiscoveryRegistrationPolicy.shouldSkipRtspRegister(
                existing = advert(authRequired = false),
                requested = advert(authRequired = true),
            )
        )
        assertFalse(
            DiscoveryRegistrationPolicy.shouldSkipRtspRegister(
                existing = advert(authRequired = true),
                requested = advert(authRequired = false),
            )
        )
    }

    @Test
    fun `a port or path change re-registers`() {
        assertFalse(DiscoveryRegistrationPolicy.shouldSkipRtspRegister(advert(), advert(port = 9000)))
        assertFalse(DiscoveryRegistrationPolicy.shouldSkipRtspRegister(advert(), advert(path = "stream")))
    }

    // ── shouldUnregister ──

    @Test
    fun `any present listener unregisters - even before the registration confirms`() {
        assertTrue(DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = true))
    }

    @Test
    fun `a missing listener has nothing to unregister`() {
        assertFalse(DiscoveryRegistrationPolicy.shouldUnregister(listenerPresent = false))
    }
}
