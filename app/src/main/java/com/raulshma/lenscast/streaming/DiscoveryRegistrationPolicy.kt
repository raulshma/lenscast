package com.raulshma.lenscast.streaming

/**
 * The pure mDNS registration verdicts behind [ServiceDiscoveryManager] —
 * NsdManager has no JVM seam (the platform service is Android-only), so the
 * decisions a JVM test can pin live here: when an RTSP advert refresh is a
 * no-op versus a re-register, and when a listener must be torn down. The
 * manager keeps the NsdManager calls and the listeners.
 */
object DiscoveryRegistrationPolicy {

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
