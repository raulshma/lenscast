package com.raulshma.lenscast.companion.client.sse

import app.cash.turbine.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * The SSE front door over a real MockWebServer: event-name/multi-line-data
 * decoding in wire order, the no-silent-completion contract (failures lane
 * emits on an abrupt server close while events never completes), and the
 * idempotent cancel that neither fires failures nor leaks the read loop.
 *
 * These tests use `runBlocking` (not `runTest`) on purpose: OkHttp's reader
 * threads are real threads, and runTest's virtual clock would advance past
 * "nothing more arrives" assertions before any real thread got a say — the
 * exact false-positive class virtual time exists to create. Turbine's
 * wallclock awaits are widened to 30 s so a slow CI box fails on assertion,
 * never on the library's own 3 s default. The fixture body ends with a
 * double newline — an SSE event without its terminating blank line is never
 * dispatched by the server-sent-event reader (learned the hard way: a body
 * ending `data: ...\n` silently carries one fewer event than it lists).
 */
class SseEventSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: SseEventSource

    /** No read timeout: these are endless chunked streams, like the device's. */
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = SseEventSource(client)
    }

    @After
    fun tearDown() {
        client.connectionPool.evictAll() // idle keep-alive sockets block server.shutdown()
        server.shutdown()
    }

    /** The server's wire shape (SseStatusStream + SseDetectionEventStream). */
    private fun sseBody(): String = listOf(
        "event: status",
        "data: {\"battery\":87}",
        "",
        "data: {\"id\":\"e1\",\"timestampMs\":100}", // data-only: the detection stream's shape
        "",
        "event: transport",
        "data: {\"scheme\":\"https\",",
        "data: \"port\":8443}", // multi-line data payload
        "",
        ": ping", // keep-alive comment: no event
        "",
        "data: {\"id\":\"e2\",\"timestampMs\":200}",
        "", // dispatches the last event...
        "", // ...and terminates the response body per the SSE spec
    ).joinToString("\n")

    private fun textResponse(body: String = sseBody()): MockResponse =
        MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream")

    private fun assertRecorded(request: RecordedRequest?): RecordedRequest =
        request ?: throw AssertionError("server never recorded the SSE request")

    // ── Wire decoding ──

    @Test
    fun `named events and multi-line data decode in wire order, comments skipped`() = runBlocking {
        server.enqueue(textResponse())
        val stream = source.open(server.url("/api/events").toString())

        stream.events.test(30.seconds) {
            val decoded = ArrayList<SseEvent>(4)
            repeat(4) { decoded.add(awaitItem()) }
            assertEquals(
                listOf(
                    SseEvent("status", "{\"battery\":87}"),
                    // data-only frames are the spec's "message" default → name = null
                    SseEvent(null, "{\"id\":\"e1\",\"timestampMs\":100}"),
                    // multi-line data joined with \n per the SSE spec
                    SseEvent("transport", "{\"scheme\":\"https\",\n\"port\":8443}"),
                    SseEvent(null, "{\"id\":\"e2\",\"timestampMs\":200}"),
                ),
                decoded,
            )
            cancelAndIgnoreRemainingEvents()
        }
        stream.cancel()
    }

    @Test
    fun `headers ride the upgrade request, Accept added when absent`() = runBlocking {
        server.enqueue(textResponse())
        val stream = source.open(
            server.url("/api/detection/events/stream").toString(),
            headers = mapOf("X-Api-Token" to "tok-123"),
        )
        stream.events.test(30.seconds) {
            awaitItem()
            val recorded = assertRecorded(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("tok-123", recorded.getHeader("X-Api-Token"))
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
            cancelAndIgnoreRemainingEvents()
        }
        stream.cancel()
    }

    @Test
    fun `explicit Accept header is not overwritten`() = runBlocking {
        server.enqueue(textResponse())
        val stream = source.open(
            server.url("/api/events").toString(),
            headers = mapOf("Accept" to "text/event-stream; charset=utf-8"),
        )
        stream.events.test(30.seconds) {
            awaitItem()
            val recorded = assertRecorded(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("text/event-stream; charset=utf-8", recorded.getHeader("Accept"))
            cancelAndIgnoreRemainingEvents()
        }
        stream.cancel()
    }

    // ── The no-silent-completion contract ──

    @Test
    fun `abrupt server close emits into failures, events flow does not complete silently`() = runBlocking {
        val bigBody = buildString {
            repeat(500) { i ->
                append("data: {\"id\":\"e$i\",\"timestampMs\":$i}\n\n")
            }
        }
        server.enqueue(
            MockResponse()
                .setChunkedBody(bigBody, 64)
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val stream = source.open(server.url("/api/detection/events/stream").toString())

        // The failures lane is subscribed FIRST, so the disconnect can never
        // outrun a collector; the events lane then lands via the replay window.
        stream.failures.test(30.seconds) {
            val failureLane = this
            stream.events.test(30.seconds) {
                val eventLane = this
                // At least one event landed before the disconnect.
                eventLane.awaitItem()
                // The abrupt close surfaces as a failure — never as a silent
                // completion of the events flow.
                val failure = failureLane.awaitItem()
                assertTrue("expected a transport failure, got $failure", failure is java.io.IOException)
                // The events flow is still alive and incomplete: one full
                // second of wall clock passes with no completion event
                // (awaitComplete throws the timeout TurbineAssertionError —
                // which is exactly the passing outcome here).
                var completed = false
                runCatching { withTimeout(1_000) { eventLane.awaitComplete() } }
                    .onSuccess { completed = true }
                assertFalse("events flow must not complete after a failure", completed)
                eventLane.cancelAndIgnoreRemainingEvents()
            }
            failureLane.cancelAndIgnoreRemainingEvents()
        }
        stream.cancel()
    }

    // ── Cancel ──

    @Test
    fun `cancel is idempotent, fires no failure, and stops the event flow`() = runBlocking {
        // Headers arrive immediately; the body is delayed, so the stream is
        // open but silent while we cancel — no race with the body ending.
        server.enqueue(
            textResponse().setBodyDelay(3, TimeUnit.SECONDS),
        )
        val stream = source.open(server.url("/api/events").toString())

        stream.failures.test(30.seconds) {
            val failureLane = this
            stream.events.test(30.seconds) {
                val eventLane = this
                // The request reached the server before we tear down.
                withTimeout(10.seconds) {
                    while (server.requestCount == 0) delay(10)
                }

                stream.cancel()
                stream.cancel() // idempotent: second call must be a no-op, not a crash

                // A cancelled stream reports no failure: the caller planned
                // this ending, so the failures lane stays silent through a
                // full second of wall clock.
                delay(1_000)
                failureLane.expectNoEvents()
                // And the (delayed) body never surfaces as an event after cancel.
                eventLane.expectNoEvents()
                eventLane.cancelAndIgnoreRemainingEvents()
            }
            failureLane.cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Multi-lane + replay ──

    @Test
    fun `replay covers the open-to-subscribe gap for late collectors`() = runBlocking {
        server.enqueue(textResponse())
        val stream = source.open(server.url("/api/events").toString())
        // Drain the whole burst on a throwaway collector so the wire is done.
        stream.events.test(30.seconds) {
            repeat(4) { awaitItem() }
            cancelAndIgnoreRemainingEvents()
        }

        // Subscribe late — everything already flew by; the small replay
        // window is what this collector still sees (all 4 events fit).
        stream.events.test(30.seconds) {
            val first = awaitItem()
            assertEquals(SseEvent("status", "{\"battery\":87}"), first)
            cancelAndIgnoreRemainingEvents()
        }
        stream.cancel()
    }

    @Test
    fun `multiple collectors tap one connection`() = runBlocking {
        server.enqueue(textResponse())
        val stream = source.open(server.url("/api/events").toString())

        val laneA = ArrayList<SseEvent>()
        val laneB = ArrayList<SseEvent>()
        val jobs = listOf(
            launch { stream.events.collect { if (laneA.size < 4) laneA.add(it) } },
            launch { stream.events.collect { if (laneB.size < 4) laneB.add(it) } },
        )
        withTimeout(30.seconds) {
            while (laneA.size < 4 || laneB.size < 4) delay(10)
        }
        assertEquals(laneA, laneB)
        jobs.forEach { it.cancel() }
        stream.cancel()
        // Hot SharedFlow, not a state holder: it carries no "latest value".
        assertFalse(stream.events is kotlinx.coroutines.flow.StateFlow)
    }
}
