package com.raulshma.lenscast.streaming

/**
 * The pure mDNS registration verdicts behind [ServiceDiscoveryManager] —
 * NsdManager has no JVM seam (the platform service is Android-only), so the
 * decisions a JVM test can pin live here: when an RTSP advert refresh is a
 * no-op versus a re-register, when a listener must be torn down, and which
 * TXT records the HTTP advert publishes. The manager keeps the NsdManager
 * calls and the listeners.
 */
object DiscoveryRegistrationPolicy {

    /**
     * The TXT record that marks the HTTP advert as a LensCast device: the
     * camera side publishes it here and the companion app's discovery filter
     * (`companion/discovery/NsdDiscoveryFilter`) keys on it — the constants
     * are the single shared vocabulary, so the two spellings of the contract
     * cannot drift.
     */
    const val LENSCAST_TXT_KEY = "lenscast"
    const val LENSCAST_TXT_VALUE = "1"

    /**
     * The TXT records the HTTP (`_http._tcp`) advert publishes. A map, not a
     * setAttribute call, so the contract stays pure and JVM-testable — the
     * manager applies whatever this returns onto every HTTP service info it
     * builds (initial registration AND the collision retry, or a retried
     * advert would silently drop the marker).
     */
    fun httpTxtRecords(): Map<String, String> = mapOf(LENSCAST_TXT_KEY to LENSCAST_TXT_VALUE)

    /**
     * The HTTP advert refresh verdict: skip only when an HTTP advert is already
     * active AND its registered params (serviceName, port) match the request.
     * A port swap must re-register the HTTP advert on the new port.
     */
    fun shouldSkipHttpRegister(
        existing: ServiceDiscoveryManager.HttpAdvert?,
        requested: ServiceDiscoveryManager.HttpAdvert,
    ): Boolean = existing != null && existing == requested

    /**
     * The RTSP advert refresh verdict: skip only when an advert is already
     * registered AND the requested params (port/path/authRequired) equal the
     * last attempt. Anything else — a first registration, or ANY param drift
     * (the late auth toggle) — must (re-)register, or the TXT record would
     * keep advertising a stale shape (the old flag early-return did exactly
     * that: a re-invocation with auth flipped never landed).
     */
    fun shouldSkipRtspRegister(
        existing: ServiceDiscoveryManager.RtspAdvert?,
        requested: ServiceDiscoveryManager.RtspAdvert,
    ): Boolean = existing != null && existing == requested

    /**
     * The teardown verdict: unregister whenever a listener is present —
     * including one whose registration has not confirmed yet. Pre-confirmation
     * unregister is legal, and skipping it ghosts the advert until process
     * death (plus a duplicate advert on the next start).
     */
    fun shouldUnregister(listenerPresent: Boolean): Boolean = listenerPresent
}
