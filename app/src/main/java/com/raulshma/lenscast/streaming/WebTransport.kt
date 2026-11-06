package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import javax.net.ssl.SSLServerSocketFactory

/**
 * How a (port, TLS) application landed. [UNCHANGED] means neither value
 * moved — the caller's no-op (a settings re-emission must not churn the
 * transport). The other three are the old `recreateServerIfRunning`
 * tri-state verbatim: [NOT_RUNNING] is the old `null` (the transport was
 * stopped, the swap landed, nothing restarted), [STARTED] the old `true`
 * (was serving, replacement started), [START_FAILED] the old `false` (was
 * serving, replacement failed to start — the stop-before-bind recovery
 * already released the port).
 */
internal enum class ReplaceOutcome {
    UNCHANGED,
    NOT_RUNNING,
    STARTED,
    START_FAILED,
}

/**
 * The web transport — the phone's HTTP/HTTPS listener (MJPEG, Web API, WS
 * sidecar, WHEP, HLS) and its replace cycle, pulled out of
 * [StreamingManager] as the last transport without an output module (the
 * [RtspOutput]/[PushOutput] pattern): the retained (port, TLS) config
 * (settings land here even while stopped, so the next start picks them all
 * up), the stop → swap → start-if-was-running replace cycle, the scheme
 * derivation, and the server state mirror. The manager keeps its public
 * surface (setPort/setTlsEnabled/ensureServerRunning, the running flow, the
 * URL builders) and its fan-out; everything "the web server's lifecycle"
 * means sits behind this narrow interface, JVM-tested over the
 * [WebServerHandle] seam (real server + test fake — two adapters justify
 * the seam).
 *
 * The replace cycle preserves, comment included, the bug-prevention
 * choreography the manager's web sections documented:
 *
 * - **Serialize everything.** The create/start/stop/replace lifecycle runs
 *   under one lock: the Settings Applier's port and TLS writers run on the
 *   shared app scope, so an unsynchronized create-then-start race could
 *   leave the owner holding an unstarted instance while the old socket is
 *   still bound — the state desync that made an HTTPS toggle fail with
 *   EADDRINUSE.
 * - **Resolve the replacement before announcing.** The TLS fallback-to-plain
 *   verdict lands before the scheme announcement, so an open dashboard is
 *   only ever pointed at the scheme the new listener will really speak.
 * - **Announce only on a scheme change.** The transport-switch notice goes
 *   to the live instance *before* it is torn down (the SSE frames must
 *   reach the socket first), and only when the scheme actually changed — a
 *   port-only apply stays silent.
 * - **Stop releases the port even after a failed start.** Every replace
 *   stops the old instance even when it never reported running (NanoHTTPD
 *   binds before a failed start reports), and the failed candidate is
 *   stopped too — so the next bind never answers EADDRINUSE.
 * - **TLS fallback-to-plain.** A requested TLS identity that cannot be
 *   built silently downgrades the retained flag to plain HTTP; the scheme,
 *   the announcement, and every advertised URL follow.
 *
 * The TLS identity itself is resolved by the owner (it needs the app's
 * TlsCertManager): the [update] entry receives the resolved
 * [SSLServerSocketFactory] per application through the injected resolver
 * lambda, invoked only when a TLS-speaking replacement is actually being
 * built.
 *
 * The WHEP viewer endpoint rides this transport: its sessions die with the
 * server on every stop of a serving transport (the WebSocket routes' rule;
 * after a failed start nothing armed, so that stop is silent there) and its
 * reap loop arms with the server on every successful start, through the
 * injected session hooks.
 */
internal class WebTransport(
    /** The port the first (pre-settings) instance serves — the StreamDefaults default. */
    initialPort: Int,
    /**
     * Builds the one handle per replace cycle from the new spec — the real
     * [StreamingServer] at the composition root, a recording fake in tests.
     * Everything the server construction needs besides port and TLS factory
     * (the Web API stack, the auth gate, the WHEP endpoint) is fixed by the
     * owner's closure.
     */
    private val serverFactory: (port: Int, sslFactory: SSLServerSocketFactory?) -> WebServerHandle,
    /** The running-state mirror after every transition, so the owner's flow follows the transport. */
    private val onRunningChanged: (running: Boolean) -> Unit,
    /**
     * Runs after a successful start — the owner refreshes its advertised
     * stream URL from the new scheme there (the old startServerLocked tail).
     */
    private val onReady: () -> Unit = {},
    /** Arms the WHEP endpoint's reap loop with a started server. */
    private val startWhepSessions: () -> Unit = {},
    /** Kills every WHEP session when the transport stops (viewer peer connections die with it). */
    private val stopWhepSessions: () -> Unit = {},
) {

    // The server lifecycle (create / start / stop / replace) is serialized:
    // the Settings Applier's port and TLS writers run on the shared app
    // scope, so an unsynchronized create-then-start race could leave the
    // owner holding an unstarted instance while the old socket is still
    // bound — the state desync that made an HTTPS toggle fail with
    // EADDRINUSE.
    private val lock = Any()

    // The intended next instance, which may be newer than the one serving if
    // a replacement is being applied. Built once up front (the manager's
    // historical field initializer), so the media/client forwards below have
    // a receiver even while stopped — they no-op inside the unstarted server.
    @Volatile
    private var server: WebServerHandle = serverFactory(initialPort, null)

    // The instance that actually owns the listening socket: null while
    // stopped.
    @Volatile
    private var started: WebServerHandle? = null

    // The failed-start quiescence latch: set when a start attempt failed
    // while nothing was serving (the candidate already released, the mirror
    // already down, WHEP never armed) and cleared when a candidate lands or
    // a start succeeds — the only transitions out of that state. [stopLocked]
    // reads it to skip the WHEP teardown when a stop is merely that
    // cleanup. Touched only under [lock].
    private var failedStart = false

    // The retained (port, TLS) config: settings land here even while
    // stopped, so the next start picks them all up.
    @Volatile
    private var port: Int = initialPort

    // TLS mode: when on, the server socket is secure (self-signed cert owned
    // by the app's TlsCertManager) and every URL the app hands out uses
    // https. Falls back to plain HTTP when the identity cannot be built.
    @Volatile
    private var tlsEnabled = false

    // ── state reads ──

    /** The advertised scheme — http/https per the retained TLS flag (fallback included). */
    fun scheme(): String = if (tlsEnabled) "https" else "http"

    /** True while HTTPS mode is retained on (identity fallback clears it). */
    fun tlsEnabled(): Boolean = tlsEnabled

    /** The configured port, retained even while stopped — the owner's mDNS/URL seam. */
    fun port(): Int = port

    /** True while an instance is actually serving. */
    fun isRunning(): Boolean = started != null

    /**
     * The instance actually serving — the started one when live, else the
     * intended instance (which answers zero/no clients while stopped). The
     * live-count and client-ids reads prefer it: the intended instance may
     * be an unbound replacement that has not taken the socket yet.
     */
    private fun serving(): WebServerHandle = started ?: server

    /** MJPEG client count as the serving instance reports it. */
    fun liveClientCount(): Int = serving().getClientCount()

    /** MJPEG client ids as the serving instance reports them. */
    fun liveHttpClientIds(): List<String> = serving().httpClientIds()

    // ── fan-in forwards (the manager's frame/client seams) ──

    /** One rendered M-JPEG frame to the intended instance (no-op while no viewer). */
    fun updateFrame(jpeg: ByteArray) = server.updateFrame(jpeg)

    /** The encoded hub's WS video fan-out to the intended instance (carries across a replace). */
    fun feedVideo(nalUnits: List<EncodedNalUnit>) = server.feedVideo(nalUnits)

    /** Live `/ws/video` client count — the encoded-stream policy's WS input. */
    fun videoClientCount(): Int = server.videoClientCount()

    /** MJPEG client count as the intended instance reports it (the frame path's gate). */
    fun clientCount(): Int = server.getClientCount()

    /** True kick of one MJPEG client on the intended instance. */
    fun kickHttpClient(clientId: String): Boolean = server.kickHttpClient(clientId)

    /** The web-stream toggle lands on the intended instance's MJPEG pump. */
    fun setWebStreamingEnabled(enabled: Boolean) = server.setWebStreamingEnabled(enabled)

    // ── lifecycle ──

    /**
     * The one settings entry, in the [RtspOutput.update] style: land the
     * (port, TLS) change, then run the replace cycle. Either half moving is
     * a full replace — a port change rebuilds the listener (and, with TLS
     * retained on, re-resolves its identity, whose failure downgrades to
     * plain exactly like a TLS toggle would), and a TLS change rebuilds it
     * under the new scheme. Neither moving is [ReplaceOutcome.UNCHANGED] —
     * a settings re-emission must not churn the transport, and the resolver
     * lambda is not invoked (its side effects — the fingerprint refresh, the
     * fallback log — stay change-scoped like the old createServer calls).
     *
     * Ordering inside the cycle (all under the lock):
     * 1. Retain the requested TLS, resolve the identity through [resolveTls]
     *    when a TLS-speaking replacement is wanted — before anything is
     *    announced or stopped, so the fallback-to-plain verdict and the
     *    announced scheme are the replacement's real ones.
     * 2. Build the replacement candidate.
     * 3. Announce the new scheme to the live instance when it changed —
     *    before teardown, so the SSE notice reaches the sockets first; a
     *    port-only apply (scheme unchanged) stays silent.
     * 4. Stop the old instance (always — the stop-before-bind recovery, see
     *    [stopLocked]), land the swap, start when the transport was serving.
     */
    fun update(
        port: Int = this.port,
        tlsRequested: Boolean = this.tlsEnabled,
        resolveTls: () -> SSLServerSocketFactory?,
    ): ReplaceOutcome = synchronized(lock) {
        if (port == this.port && tlsRequested == this.tlsEnabled) {
            return@synchronized ReplaceOutcome.UNCHANGED
        }
        val schemeBefore = scheme()
        tlsEnabled = tlsRequested
        // Build the replacement (and resolve any TLS fallback) before the
        // old listener is stopped, so the scheme announced below is the one
        // the replacement will really speak.
        val sslFactory = if (tlsRequested) resolveTls() else null
        if (tlsRequested && sslFactory == null) {
            // TLS identity unavailable — serve plain HTTP: the scheme below
            // (and every advertised URL) follows the real listener.
            tlsEnabled = false
        }
        val candidate = serverFactory(port, if (tlsEnabled) sslFactory else null)
        val schemeAfter = scheme()
        if (schemeAfter != schemeBefore) {
            // An open dashboard's SSE connection cannot survive the restart
            // under a different scheme — announce the destination first,
            // then let the notice reach the socket before the old listener
            // is torn down. The wait is bounded and observes the flush; no
            // fixed sleep holds the lifecycle lock.
            started?.notifyTransportSwitch(schemeAfter)
        }
        val wasRunning = started != null
        // Release the old instance even when it never reported running: a
        // failed start can still hold its socket (NanoHTTPD binds before it
        // reports the failure), and the replacement's bind would answer
        // EADDRINUSE. stopServer is a no-op for a cleanly-stopped instance.
        stopLocked()
        this.port = port
        // The swap lands a fresh candidate: any failed-start quiescence of
        // the outgoing instance does not carry over.
        server = candidate
        failedStart = false
        if (!wasRunning) {
            ReplaceOutcome.NOT_RUNNING
        } else if (startLocked()) {
            ReplaceOutcome.STARTED
        } else {
            ReplaceOutcome.START_FAILED
        }
    }

    /**
     * Starts the transport when stopped; true when it ends up serving (a
     * no-op answer on an already-running transport). Mirrors the old
     * ensureServerRunning: the owner refreshes its blank stream URL on the
     * true answer.
     */
    fun ensureRunning(): Boolean = synchronized(lock) {
        if (started != null) return@synchronized true
        startLocked()
    }

    /**
     * The one lever for "the transport stops": the HTTP server carrying the
     * HTTP and /ws WebSocket routes alike, plus every WHEP session. Safe to
     * call from any state (and after a failed start — see [stopLocked]).
     */
    fun stop() = synchronized(lock) {
        stopLocked()
    }

    // ── internals ──

    /**
     * Starts [server] and records it as the live instance. The caller holds
     * [lock]; the two entry points are [ensureRunning] and the replace
     * cycle, so a socket can never be bound by an instance the module does
     * not remember.
     */
    private fun startLocked(): Boolean {
        val candidate = server
        val didStart = candidate.startServer()
        if (!didStart) {
            // Release whatever the failed attempt grabbed so the next
            // ensureRunning retries on a clean socket. No WHEP teardown here:
            // the reap loop only arms with a serving transport, so after a
            // failed start there is nothing to kill — the latch below just
            // records that quiescence for a following stop (the mirror still
            // drops, so the UI never shows a running transport).
            candidate.stopServer()
            onRunningChanged(false)
            failedStart = true
            return false
        }
        started = candidate
        failedStart = false
        // The WHEP endpoint rides the web transport: its reap loop arms with
        // the server (sessions are created on demand by viewers).
        runCatching { startWhepSessions() }
        onRunningChanged(true)
        onReady()
        return true
    }

    /**
     * Stops the instance that owns the socket. The intended instance is
     * normally the same object; if a failed replacement left a different one
     * behind, close it too so the port is always released before the next
     * bind (the EADDRINUSE class this seam exists to prevent).
     */
    private fun stopLocked() {
        val live = started
        started = null
        if (live != null) {
            live.stopServer()
        }
        if (server !== live) {
            server.stopServer()
        }
        onRunningChanged(false)
        // WHEP sessions live on the web transport: every viewer's peer
        // connection dies with it (the WebSocket routes' rule). The one
        // exception is the cleanup after a failed start — WHEP only arms
        // with a serving transport, so there is nothing to kill (the
        // candidate release and the mirror drop above still run).
        if (!failedStart) {
            runCatching { stopWhepSessions() }
        }
    }
}
