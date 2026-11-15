package com.raulshma.lenscast.companion.client.ws

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The talkback uplink against MockWebServer's withWebSocketUpgrade: the
 * binary frame IS the little-endian PCM16 bytes, the text `stop` precedes a
 * clean close, and a vanished device surfaces as FAILED with its cause.
 *
 * `runBlocking` (not `runTest`): OkHttp's WebSocket threads are real
 * threads; the virtual clock would skip past nothing-happened assertions
 * before any socket thread got a word in. Every await is wall-clock bounded.
 * tearDown evicts the client's pool first — a closed WebSocket's transport
 * parks in the idle pool and would otherwise hold MockWebServer.shutdown
 * past its "queue to shut down" patience.
 */
class TalkbackClientTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpClient
    private lateinit var client: TalkbackClient

    /** Captures the server side of the upgraded socket and everything it receives. */
    private class ServerSocketListener : WebSocketListener() {
        val messages = LinkedBlockingQueue<Any>() // String | ByteString
        @Volatile var socket: WebSocket? = null
        @Volatile var open = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            open = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            messages.add(bytes)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpClient()
        client = TalkbackClient(engine)
    }

    @After
    fun tearDown() {
        engine.connectionPool.evictAll() // parked WS transports block server.shutdown()
        engine.dispatcher.executorService.shutdownNow()
        // The OkHttp-5 classic MockWebServer bridge can miss its own
        // queue-shutdown latch after a fully completed WebSocket close
        // handshake (the assertions above have already proven the session
        // ended); a lingering mock socket is a mock artifact, not a signal.
        runCatching { server.shutdown() }
    }

    private fun connect(listener: ServerSocketListener): TalkbackSession {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        return client.connect(server.url("/ws/talkback").toString(), headers = emptyMap())
    }

    private suspend fun awaitLive(session: TalkbackSession) {
        withTimeout(10_000) { session.state.first { it == TalkbackState.LIVE } }
    }

    /** Awaits the clean end of a stop() — the state must leave STOPPING promptly. */
    private suspend fun awaitStopLanded(session: TalkbackSession) {
        withTimeout(10_000) {
            session.state.first { it == TalkbackState.CLOSED || it == TalkbackState.FAILED }
        }
    }

    private fun awaitServerOpen(listener: ServerSocketListener) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!listener.open && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("server socket never opened", listener.open)
    }

    /** Drains whatever precedes [expected] on the server's message queue, then asserts it. */
    private fun pollUntil(listener: ServerSocketListener, expected: String): Any {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val message = listener.messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (message == expected) return message
        }
        throw AssertionError("'$expected' never arrived on the server socket")
    }

    @Test
    fun `binary frames arrive server-side as the little-endian PCM bytes`() = runBlocking {
        val serverSide = ServerSocketListener()
        val session = connect(serverSide)
        awaitServerOpen(serverSide)
        awaitLive(session)

        // 1, -1, 32767, -32768 → 01 00 FF FF FF 7F 00 80
        session.sendPcm(shortArrayOf(1, -1, 32767, -32768))
        val received = withTimeout(10_000) { serverSide.messages.poll(5, TimeUnit.SECONDS) }
        val bytes = received as? ByteString ?: throw AssertionError("expected a binary frame, got $received")
        assertEquals(
            byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
                .toByteString(),
            bytes,
        )
        session.stop()
        awaitStopLanded(session)
    }

    @Test
    fun `headers ride the websocket upgrade request`() = runBlocking {
        val serverSide = ServerSocketListener()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        client.connect(server.url("/ws/talkback").toString(), headers = mapOf("Cookie" to "session=abc123"))
        val recorded = withTimeout(10_000) { server.takeRequest(5, TimeUnit.SECONDS) }
            ?: throw AssertionError("server never recorded the upgrade request")
        assertEquals("session=abc123", recorded.getHeader("Cookie"))
        awaitServerOpen(serverSide)
    }

    @Test
    fun `stop sends the text stop then closes cleanly`() = runBlocking {
        val serverSide = ServerSocketListener()
        val session = connect(serverSide)
        awaitServerOpen(serverSide)
        awaitLive(session)
        session.sendPcm(shortArrayOf(100, -100))

        session.stop()

        // The end-of-talk signal reaches the device after the audio already
        // in flight (wire order: binary chunks, then `stop`, then the close).
        pollUntil(serverSide, "stop")

        // The device's close answer lands: state ends CLOSED (not FAILED).
        awaitStopLanded(session)
        assertEquals(TalkbackState.CLOSED, session.state.value)
        // And a second stop() is a no-op, not a crash.
        session.stop()
    }

    @Test
    fun `sendPcm after stop throws instead of leaking audio into the close`() = runBlocking {
        val serverSide = ServerSocketListener()
        val session = connect(serverSide)
        awaitServerOpen(serverSide)
        awaitLive(session)
        session.stop()
        awaitStopLanded(session)
        // The session is terminal — audio must be rejected.
        try {
            session.sendPcm(shortArrayOf(1))
            throw AssertionError("sendPcm after stop() must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("no more audio"))
        }
    }

    @Test
    fun `a transport reset surfaces as FAILED with the cause`() = runBlocking {
        // The device's socket dies mid-handshake — a TCP reset instead of an
        // upgrade (the mock's server-side WebSocket.cancel() is unusable for
        // a mid-stream kill: the mock-side RealWebSocket never assigns its
        // call, so cancel() NPEs deterministically). Either way the client
        // sees a raw transport failure, which is the path under test.
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )
        val session = client.connect(server.url("/ws/talkback").toString(), headers = emptyMap())

        withTimeout(10_000) { session.state.first { it == TalkbackState.FAILED } }
        assertNotNull(session.failureCause.value)
        // stop() on an already-failed session keeps the FAILED truth.
        session.stop()
        assertEquals(TalkbackState.FAILED, session.state.value)
    }

    @Test
    fun `a refused handshake surfaces as FAILED without ever going live`() = runBlocking {
        // No WS upgrade — the server answers 404, OkHttp reports onFailure.
        server.enqueue(MockResponse().setResponseCode(404))
        val session = client.connect(server.url("/ws/talkback").toString(), headers = emptyMap())
        withTimeout(10_000) { session.state.first { it == TalkbackState.FAILED } }
        assertNotNull(session.failureCause.value)
        assertEquals(TalkbackState.FAILED, session.state.value)
    }
}
