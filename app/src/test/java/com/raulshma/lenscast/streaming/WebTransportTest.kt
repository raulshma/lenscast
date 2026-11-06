package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLServerSocketFactory

/**
 * The web transport's replace cycle — run entirely against fake
 * [WebServerHandle]s (the real [StreamingServer] needs Android + a socket):
 *
 * - the stop → swap → start-if-was-running replace (old handle stopped,
 *   new started, order recorded),
 * - the replace while stopped (swap lands, nothing started, the intended
 *   instance is still released — the stop-before-bind discipline),
 * - the failed-start recovery (a failed start releases the port so a
 *   retry/replace binds clean — the EADDRINUSE class the seam exists to
 *   prevent) and the stop that clears state after a failed start,
 * - the TLS choreography: announce-on-scheme-change BEFORE teardown, the
 *   silent port-only apply, and the fallback-to-plain when the identity
 *   resolve yields null (no announcement, no TLS listener),
 * - the WHEP sessions dying with the transport on stop.
 *
 * The Settings Applier's "cannot change port while streaming" refusal is
 * manager-side ([StreamingManager.setPort]'s `isLiveStreaming()` guard — it
 * reads the manager's output flags, not the transport's) and is therefore
 * not reproducible here; what this suite pins is what the module itself
 * does for a port application: a changed port replaces a serving listener,
 * an unchanged (port, TLS) pair is a no-op that never even invokes the TLS
 * resolver.
 */
class WebTransportTest {

    // ── fakes ──

    /**
     * One web-server instance. Records every lifecycle call into the
     * fixture's shared event log so the ordering assertions read one flat
     * trace. The start outcome pops from [startOutcomes] (defaulting to
     * success) so a test can script a failed start — a fake's second
     * `startServer` on the same instance models the real server's retry on
     * a cleanly re-released socket.
     */
    private class FakeWebServer(
        private val id: String,
        private val events: MutableList<String>,
        private val startOutcomes: ArrayDeque<Boolean>,
    ) : WebServerHandle {
        val notifiedSchemes = mutableListOf<String>()
        var startCalls = 0
        var stopCalls = 0

        override fun startServer(): Boolean {
            events += "$id.start"
            startCalls++
            return startOutcomes.removeFirstOrNull() ?: true
        }

        override fun stopServer() {
            events += "$id.stop"
            stopCalls++
        }

        override fun notifyTransportSwitch(scheme: String): Boolean {
            events += "$id.notify($scheme)"
            notifiedSchemes += scheme
            return true
        }
    }

    /** The transport under test plus the seams its assertions read. */
    private class Fixture(startOutcomes: ArrayDeque<Boolean> = ArrayDeque()) {
        /** The one flat lifecycle trace every ordering assertion reads. */
        val events = mutableListOf<String>()

        /** The (port, tls?) spec each factory call built — last entry is the current instance's. */
        val specs = mutableListOf<String>()

        /** The running-state mirror calls, in order. */
        val runningMirror = mutableListOf<Boolean>()

        var readyCalls = 0
        var resolverCalls = 0

        private var counter = 0

        /** A non-null stand-in for a resolved TLS identity (no real handshake needed). */
        val tlsFactory: SSLServerSocketFactory = TlsIdentityStandIn()

        val transport = WebTransport(
            initialPort = 8080,
            onRunningChanged = { running ->
                runningMirror += running
                events += "mirror(${if (running) "up" else "down"})"
            },
            onReady = {
                readyCalls++
                events += "ready"
            },
            startWhepSessions = { events += "whep.start" },
            stopWhepSessions = { events += "whep.stop" },
            serverFactory = { port, sslFactory ->
                counter++
                specs += "$port/${if (sslFactory != null) "tls" else "plain"}"
                FakeWebServer("s$counter", events, startOutcomes)
            },
        )

        /** The resolver the tests hand to [WebTransport.update]: counts calls, yields the real factory. */
        fun resolvingTls(): SSLServerSocketFactory? {
            resolverCalls++
            return tlsFactory
        }
    }

    /**
     * A non-null [SSLServerSocketFactory] stand-in — the transport only
     * records whether the identity resolved (null vs. not), so no socket is
     * ever created through it.
     */
    private class TlsIdentityStandIn : SSLServerSocketFactory() {
        override fun createServerSocket(): java.net.ServerSocket =
            throw UnsupportedOperationException("no sockets in tests")

        override fun createServerSocket(port: Int): java.net.ServerSocket =
            throw UnsupportedOperationException("no sockets in tests")

        override fun createServerSocket(port: Int, backlog: Int): java.net.ServerSocket =
            throw UnsupportedOperationException("no sockets in tests")

        override fun createServerSocket(port: Int, backlog: Int, ifAddress: java.net.InetAddress?): java.net.ServerSocket =
            throw UnsupportedOperationException("no sockets in tests")

        override fun getDefaultCipherSuites(): Array<String> = arrayOf()

        override fun getSupportedCipherSuites(): Array<String> = arrayOf()
    }

    private fun fixtureWithFailedFirstStart(): Fixture {
        val outcomes = ArrayDeque<Boolean>()
        outcomes += false // the first start attempt fails (EADDRINUSE stand-in)
        return Fixture(outcomes)
    }

    // ── replace cycle ──

    @Test
    fun `replace while serving stops the old handle then starts the new one`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        f.events.clear()

        val outcome = f.transport.update(port = 9090) { null }

        assertEquals(ReplaceOutcome.STARTED, outcome)
        // Order: the old listener is released (running mirror dropped, its
        // WHEP sessions killed) before the replacement binds.
        assertEquals(
            listOf("s1.stop", "mirror(down)", "whep.stop", "s2.start", "whep.start", "mirror(up)", "ready"),
            f.events,
        )
        assertEquals(9090, f.transport.port())
        assertTrue(f.transport.isRunning())
        assertEquals("9090/plain", f.specs.last())
        assertTrue("the port-only apply must stay silent", f.events.none { it.contains("notify") })
    }

    @Test
    fun `replace while stopped lands the swap without starting and still releases the intended instance`() {
        val f = Fixture()

        val outcome = f.transport.update(port = 9090) { null }

        assertEquals(ReplaceOutcome.NOT_RUNNING, outcome)
        // The stop-before-bind discipline: the intended instance is released
        // even though it never reported running, and no start follows.
        assertEquals(listOf("s1.stop", "mirror(down)", "whep.stop"), f.events)
        assertFalse(f.transport.isRunning())
        assertEquals(9090, f.transport.port())
        assertTrue(f.runningMirror.none { it })
    }

    @Test
    fun `unchanged port and tls are a no-op that never invokes the tls resolver`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        f.events.clear()

        val outcome = f.transport.update(port = 8080) { f.resolvingTls() }

        assertEquals(ReplaceOutcome.UNCHANGED, outcome)
        assertEquals("a settings re-emission must not churn the transport", emptyList<String>(), f.events)
        assertEquals("the resolver's side effects stay change-scoped", 0, f.resolverCalls)
    }

    // ── failed start: the EADDRINUSE recovery path ──

    @Test
    fun `failed start releases the port and a retry on the clean socket binds`() {
        val f = fixtureWithFailedFirstStart()

        assertFalse(f.transport.ensureRunning())
        assertFalse(f.transport.isRunning())
        // The failed attempt's port is released immediately (stop after the
        // failed start), the WHEP loop never armed, the mirror is down.
        assertEquals(listOf("s1.start", "s1.stop", "mirror(down)"), f.events)
        assertTrue(f.events.none { it == "whep.start" })

        // The retry (a subsequent ensureServerRunning) starts on a clean socket.
        assertTrue(f.transport.ensureRunning())
        assertEquals(
            listOf("s1.start", "s1.stop", "mirror(down)", "s1.start", "whep.start", "mirror(up)", "ready"),
            f.events,
        )
        assertTrue(f.transport.isRunning())
    }

    @Test
    fun `a replace after a failed start works and ends serving on the replacement`() {
        val f = fixtureWithFailedFirstStart()
        assertFalse(f.transport.ensureRunning())

        val outcome = f.transport.update(port = 9090) { null }

        assertEquals(ReplaceOutcome.NOT_RUNNING, outcome)
        assertTrue("the failed candidate must be released too", f.events.count { it == "s1.stop" } >= 2)
        assertTrue(f.transport.ensureRunning())
        assertTrue(f.transport.isRunning())
        assertEquals(9090, f.transport.port())
        assertEquals("s2.start", f.events.last { it.endsWith(".start") && !it.startsWith("whep") })
    }

    @Test
    fun `stop clears the running state even after a failed start`() {
        val f = fixtureWithFailedFirstStart()
        assertFalse(f.transport.ensureRunning())

        f.transport.stop()

        assertFalse(f.transport.isRunning())
        assertFalse("the mirror ends down", f.runningMirror.last())
        // The intended (failed-candidate) instance is released once more —
        // the always-release discipline the port lease depends on.
        assertTrue(f.events.contains("s1.stop"))
        assertEquals("mirror(down)", f.events.last())
    }

    // ── TLS choreography ──

    @Test
    fun `tls on-off-on flips announce each new scheme to the live instance before tearing it down`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        f.events.clear()

        // HTTP → HTTPS: the announcement reaches the live SSE connection
        // before the old listener is torn down.
        assertEquals(ReplaceOutcome.STARTED, f.transport.update(tlsRequested = true) { f.resolvingTls() })
        assertEquals("https", f.transport.scheme())
        assertTrue(f.transport.tlsEnabled())
        assertEquals("8080/tls", f.specs.last())
        val notifyHttps = f.events.indexOf("s1.notify(https)")
        val stopS1 = f.events.indexOf("s1.stop")
        val startS2 = f.events.indexOf("s2.start")
        assertTrue(notifyHttps in 0 until stopS1)
        assertTrue(stopS1 < startS2)
        assertEquals(listOf("https"), f.events.filter { it.contains(".notify(") }.map { it.substringAfter("(").substringBefore(")") })

        f.events.clear()
        // HTTPS → HTTP: the same notify-before-teardown ordering.
        assertEquals(ReplaceOutcome.STARTED, f.transport.update(tlsRequested = false) { f.resolvingTls() })
        assertEquals("http", f.transport.scheme())
        assertFalse(f.transport.tlsEnabled())
        assertEquals("8080/plain", f.specs.last())
        val notifyHttp = f.events.indexOf("s2.notify(http)")
        val stopS2 = f.events.indexOf("s2.stop")
        assertTrue(notifyHttp in 0 until stopS2)

        f.events.clear()
        // HTTP → HTTPS again: the cycle repeats on the fresh instances.
        assertEquals(ReplaceOutcome.STARTED, f.transport.update(tlsRequested = true) { f.resolvingTls() })
        assertEquals("https", f.transport.scheme())
        assertTrue(f.events.indexOf("s3.notify(https)") < f.events.indexOf("s3.stop"))
    }

    @Test
    fun `tls identity failure falls back to plain without announcing`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        f.events.clear()

        // A null factory (identity build failed) means the replacement
        // speaks plain HTTP: scheme unchanged → no reload announced.
        assertEquals(ReplaceOutcome.STARTED, f.transport.update(tlsRequested = true) { null })

        assertEquals("the retained TLS flag cleared with the identity", false, f.transport.tlsEnabled())
        assertEquals("http", f.transport.scheme())
        assertEquals("the listener really is plain", "8080/plain", f.specs.last())
        assertTrue(
            "a fallback leaves the scheme unchanged, so no reload is announced",
            f.events.none { it.contains("notify") },
        )
        assertTrue(f.transport.isRunning())
    }

    @Test
    fun `port-only apply on a tls transport re-resolves the identity and stays silent when the scheme holds`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        // Land TLS first so the retained flag is on.
        assertEquals(ReplaceOutcome.STARTED, f.transport.update(tlsRequested = true) { f.resolvingTls() })
        val resolverCallsBefore = f.resolverCalls
        f.events.clear()

        val outcome = f.transport.update(port = 9090) { f.resolvingTls() }

        assertEquals(ReplaceOutcome.STARTED, outcome)
        assertEquals(
            "a port change with TLS on rebuilds the identity like the old createServer did",
            1,
            f.resolverCalls - resolverCallsBefore,
        )
        assertEquals("9090/tls", f.specs.last())
        assertTrue(
            "scheme unchanged → silent",
            f.events.none { it.contains("notify") },
        )
        assertEquals("https", f.transport.scheme())
    }

    // ── WHEP egress + lifecycle stops ──

    @Test
    fun `whep sessions die with the transport on stop and rearm on the next start`() {
        val f = Fixture()
        assertTrue(f.transport.ensureRunning())
        assertEquals(listOf("whep.start"), f.events.filter { it.startsWith("whep") })

        f.transport.stop()

        assertEquals("whep.stop", f.events.last { it.startsWith("whep") })
        assertFalse(f.transport.isRunning())

        assertTrue(f.transport.ensureRunning())
        assertTrue("the reap loop arms with the new server", f.events.lastIndexOf("whep.start") > f.events.lastIndexOf("whep.stop"))
    }

    @Test
    fun `state reads and fan-in forwards route to the retained instances`() {
        val f = Fixture()
        assertEquals(8080, f.transport.port())
        assertEquals("http", f.transport.scheme())
        assertFalse(f.transport.isRunning())

        assertTrue(f.transport.ensureRunning())
        assertTrue(f.runningMirror.last())
        assertEquals("a fresh start reports ready once", 1, f.readyCalls)
        assertTrue(f.transport.ensureRunning())
        assertEquals("an already-running ensure is a no-op true", 1, f.readyCalls)
    }
}
