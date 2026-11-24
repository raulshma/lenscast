package com.raulshma.lenscast.streaming.whep

/**
 * One viewer session's stats-poll protocol: when the WHEP endpoint may ask
 * libwebrtc for that session's next stats report. Pure and JVM-tested —
 * `WhepServer` only acts on the verdict.
 *
 * The rules, and why each exists (the bundled stream-webrtc-android 1.3.10:
 * `getStats` is a direct native call on the calling thread, libwebrtc wants
 * it on its signaling thread, the report comes back on that thread, and this
 * app's factory owns no handle to it — see the endpoint's KDoc):
 *
 *  - **One request owed at a time.** The endpoint issues the call from its
 *    own loop thread, so what it can keep safe is that the session's stats
 *    collector is never asked from two of the app's threads at once, nor
 *    while one of its reports is still being produced.
 *  - **A quiet gap after every report** ([QUIET_MS]). Upstream libwebrtc
 *    settles a request's own bookkeeping in its signaling thread around the
 *    moment it hands the report to the callback; a report landing right
 *    before a poll tick would otherwise let the loop push the next request
 *    into that bookkeeping — a race inside libwebrtc, with no lock of ours
 *    to take. The gap is far longer than that bookkeeping and far shorter
 *    than the endpoint's 2 s tick, so a healthy report never costs a tick.
 *  - **A lost report is not a dead session** ([STALE_MS]). A request whose
 *    report never arrives (libwebrtc drops it while a peer connection is
 *    closing) would leave the session unpolled forever; past the stale
 *    window it is declared lost and asked again.
 *
 * Thread-safe: the endpoint's loop arms, libwebrtc's callback thread marks
 * the delivery. The clock is the caller's, passed with every call.
 */
class WhepStatsPollGuard(
    private val quietMs: Long = QUIET_MS,
    private val staleMs: Long = STALE_MS,
) {

    init {
        require(quietMs >= 0) { "quietMs must not be negative: $quietMs" }
        require(staleMs > 0) { "staleMs must be positive: $staleMs" }
    }

    /** [arm]'s answer: [ISSUE] and [REISSUE] each grant exactly one request; [WAIT] leaves the session alone this tick. */
    enum class Verdict {
        /** No request is owed: issue one now. */
        ISSUE,

        /** The request issued earlier was declared lost: issue its replacement now. */
        REISSUE,

        /** A request is owed, or the last report's quiet gap has not passed. */
        WAIT,
    }

    private var pending = false
    private var requestedAtMs = 0L

    /** When the last report callback finished; null until the first one. */
    private var deliveredAtMs: Long? = null

    /**
     * The owner's once-per-tick ask for one session. A granted verdict comes
     * with the obligation to issue exactly one request; the report's
     * callback answers it with [onDelivered], a request that could not be
     * issued with [onFailed].
     */
    fun arm(nowMs: Long): Verdict = synchronized(this) {
        var verdict = Verdict.ISSUE
        if (pending) {
            if (nowMs - requestedAtMs < staleMs) return Verdict.WAIT
            verdict = Verdict.REISSUE
        }
        val deliveredAt = deliveredAtMs
        if (deliveredAt != null && nowMs - deliveredAt < quietMs) return Verdict.WAIT
        pending = true
        requestedAtMs = nowMs
        verdict
    }

    /** The granted request could not be issued (libwebrtc threw): nothing is owed, a later tick may ask again. */
    fun onFailed() = synchronized(this) {
        pending = false
    }

    /** The report callback returned at [nowMs] — nothing is owed from now on, and the quiet gap starts here. */
    fun onDelivered(nowMs: Long) = synchronized(this) {
        deliveredAtMs = nowMs
        pending = false
    }

    companion object {
        /** Far longer than the signaling thread's post-callback bookkeeping, far shorter than the endpoint's 2 s tick. */
        const val QUIET_MS = 250L

        /** Far beyond a healthy report's latency (tens of milliseconds) — three polls at the endpoint's 2 s cadence. */
        const val STALE_MS = 6_000L
    }
}
