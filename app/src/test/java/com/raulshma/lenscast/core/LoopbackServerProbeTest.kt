package com.raulshma.lenscast.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The loopback probe against a real (JDK-built-in) HTTP server on an
 * ephemeral loopback port — the seam's contract is honest HTTP, so the JVM
 * test runs real sockets; the watchdog itself only ever sees the lambda.
 */
class LoopbackServerProbeTest {

    private fun server(handler: () -> Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status = handler()
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    private fun probe(server: HttpServer, connectMs: Int = 2_000, readMs: Int = 2_000) =
        LoopbackServerProbe(
            urlProvider = { "http://127.0.0.1:${server.address.port}/api/system" },
            connectTimeoutMs = connectMs,
            readTimeoutMs = readMs,
        )

    @Test
    fun `an answering server probes true`() {
        val server = server { 200 }
        try {
            assertTrue(probe(server).invoke())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an auth rejection is still a living server`() {
        // 401 with auth on answers the only question the probe asks.
        val server = server { 401 }
        try {
            assertTrue(probe(server).invoke())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 5xx answer probes false`() {
        val server = server { 500 }
        try {
            assertFalse(probe(server).invoke())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a dead port probes false without throwing`() {
        val server = server { 200 }
        val port = server.address.port
        server.stop(0)
        val probe = LoopbackServerProbe(urlProvider = { "http://127.0.0.1:$port/api/system" })
        assertFalse(probe.invoke())
    }

    @Test
    fun `a wedged handler times out to false on the probe's own clock`() {
        // The failure class the flags cannot see: the socket accepts, nothing
        // ever answers. The probe's own read timeout (not the server's) must
        // bound the wait.
        val server = server {
            Thread.sleep(5_000)
            200
        }
        try {
            assertFalse(probe(server, connectMs = 1_000, readMs = 300).invoke())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unreachable host is false, never a raise`() {
        val probe = LoopbackServerProbe(
            urlProvider = { "http://127.0.0.1:1/api/system" },
            connectTimeoutMs = 300,
        )
        assertFalse(probe.invoke())
    }
}
