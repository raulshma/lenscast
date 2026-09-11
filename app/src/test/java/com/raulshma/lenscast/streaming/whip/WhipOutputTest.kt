package com.raulshma.lenscast.streaming.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHIP push output's lifecycle and status mirror, JVM-tested behind the
 * [WhipPublisherHandle] seam with a recording fake publisher (the
 * [com.raulshma.lenscast.streaming.rtmp.RtmpOutputTest] pattern). The real
 * [WhipPublisher] — PeerConnectionFactory, libwebrtc's offer/answer, the
 * dedicated AudioRecord — is native and therefore device-verify-only; these
 * tests pin everything the manager drives through the output.
 */
class WhipOutputTest {

    // ── fakes ──

    private class FakeFrame(val nv21: ByteArray, val width: Int, val height: Int, val rotation: Int)

    /** Publisher fake: one per factory call, recording every handle call. */
    private class FakePublisher(
        val onStatus: (WhipStatus) -> Unit,
    ) : WhipPublisherHandle {
        var startCalls = 0
        var stopCalls = 0
        var frameRate = -1
            private set
        val frames = mutableListOf<FakeFrame>()

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
            frames += FakeFrame(nv21, width, height, rotation)
        }

        override fun setFrameRate(fps: Int) {
            frameRate = fps
        }

        override fun status(): WhipStatus = WhipStatus.Idle
    }

    /** One factory-call record: the connect parameters the output handed over. */
    private class FactoryCall(
        val url: WhipUrl,
        val token: String?,
        val stunServer: String?,
        val audioAllowed: Boolean,
        val publisher: FakePublisher,
    )

    private class Harness(audioAllowed: Boolean = true) {
        val calls = mutableListOf<FactoryCall>()
        val statuses = mutableListOf<WhipStatus>()

        val output = WhipOutput(
            audioAllowed = { audioAllowed },
            onStatusChanged = { statuses += it },
            publisherFactory = { url, token, stunServer, audio, onStatus ->
                FakePublisher(onStatus).also { calls += FactoryCall(url, token, stunServer, audio, it) }
            },
        )

        /** Arms, configures, and starts — the common passing-start ladder. */
        fun start(
            url: String = "http://example.com:8000/whip",
            token: String = "",
            stunServer: String = "stun.l.google.com:19302",
        ): WhipOutput.StartResult {
            output.setEnabled(true)
            output.setUrl(url)
            output.setToken(token)
            output.setStunServer(stunServer)
            return output.start()
        }
    }

    // ── the validation ladder ──

    @Test
    fun `a disabled output refuses to start`() {
        val harness = Harness()
        harness.output.setUrl("http://example.com/whip")

        val result = harness.output.start()
        assertTrue(result is WhipOutput.StartResult.Rejected)
        assertEquals("WHIP push is disabled", (result as WhipOutput.StartResult.Rejected).reason)
        assertTrue(harness.calls.isEmpty())
        assertFalse(harness.output.isActive())
        assertEquals(WhipStatus.Error("WHIP push is disabled"), harness.output.status())
    }

    @Test
    fun `an unusable URL refuses with the readable shape hint`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")

        val result = harness.output.start()
        assertTrue(result is WhipOutput.StartResult.Rejected)
        assertTrue((result as WhipOutput.StartResult.Rejected).reason.contains("Invalid WHIP URL"))
        assertTrue(harness.calls.isEmpty())
    }

    @Test
    fun `a start under the H265 codec is not codec-gated`() {
        // No codec input exists on the output at all: libwebrtc encodes its
        // own H.264, so nothing here can refuse for the RTSP codec setting.
        val harness = Harness()
        val result = harness.start()
        assertEquals(WhipOutput.StartResult.Started, result)
        assertEquals(1, harness.calls.size)
    }

    // ── the start/stop lifecycle ──

    @Test
    fun `a valid start builds and starts the publisher with its connect parameters`() {
        val harness = Harness()
        val result = harness.start(token = "tok", stunServer = "stun.example.com:3478")

        assertEquals(WhipOutput.StartResult.Started, result)
        val call = harness.calls.single()
        assertEquals("example.com", call.url.host)
        assertEquals(8000, call.url.port)
        assertEquals("/whip", call.url.resourcePath)
        assertEquals("tok", call.token)
        assertEquals("stun.example.com:3478", call.stunServer)
        assertEquals(true, call.audioAllowed)
        assertEquals(1, call.publisher.startCalls)
        assertTrue(harness.output.isActive())
    }

    @Test
    fun `blank token and stun settings hand over nulls`() {
        val harness = Harness()
        harness.start(token = "  ", stunServer = "")

        val call = harness.calls.single()
        assertNull(call.token)
        assertNull(call.stunServer)
    }

    @Test
    fun `the mic-arbitration verdict rides the factory call`() {
        val allowed = Harness(audioAllowed = true)
        allowed.start()
        assertEquals(true, allowed.calls.single().audioAllowed)

        val denied = Harness(audioAllowed = false)
        denied.start()
        assertEquals(false, denied.calls.single().audioAllowed)
    }

    @Test
    fun `start is a no-op while already live`() {
        val harness = Harness()
        harness.start()

        assertEquals(WhipOutput.StartResult.Started, harness.output.start())
        assertEquals(1, harness.calls.single().publisher.startCalls)
    }

    @Test
    fun `stop stops the publisher and goes idle`() {
        val harness = Harness()
        harness.start()
        val publisher = harness.calls.single().publisher
        publisher.onStatus(WhipStatus.Connecting)

        harness.output.stop()
        assertEquals(1, publisher.stopCalls)
        assertFalse(harness.output.isActive())
        assertEquals(WhipStatus.Idle, harness.output.status())
        // A second stop is a no-op.
        harness.output.stop()
        assertEquals(1, publisher.stopCalls)
    }

    @Test
    fun `disabling stops a live output and arming alone never starts`() {
        val harness = Harness()
        harness.start()
        val publisher = harness.calls.single().publisher

        assertTrue(harness.output.setEnabled(false))
        assertEquals(1, publisher.stopCalls)
        assertFalse(harness.output.isActive())

        // Re-arming does not start by itself — the start is a user/API action.
        assertTrue(harness.output.setEnabled(true))
        assertFalse(harness.output.isActive())
        assertEquals(1, harness.calls.size)
    }

    @Test
    fun `arming is idempotent`() {
        val harness = Harness()
        assertTrue(harness.output.setEnabled(true))
        assertFalse(harness.output.setEnabled(true))
    }

    // ── retained config & restart-on-change ──

    @Test
    fun `the url is retained while stopped for the next start`() {
        val harness = Harness()
        harness.output.setUrl("http://example.com/whip")
        assertEquals("http://example.com/whip", harness.output.configuredUrl())
        assertTrue(harness.calls.isEmpty())
    }

    @Test
    fun `a url change restarts a live output on the new target`() {
        val harness = Harness()
        harness.start()
        val first = harness.calls.single()

        harness.output.setUrl("http://elsewhere:9000/whip-endpoint")
        assertEquals(1, first.publisher.stopCalls)
        val second = harness.calls[1]
        assertEquals("elsewhere", second.url.host)
        assertEquals(9000, second.url.port)
        assertEquals("/whip-endpoint", second.url.resourcePath)
        assertEquals(1, second.publisher.startCalls)
    }

    @Test
    fun `a same-values set is a no-op for a live output`() {
        val harness = Harness()
        harness.start()

        harness.output.setUrl("http://example.com:8000/whip") // trimmed-equal
        harness.output.setUrl("  http://example.com:8000/whip  ")
        harness.output.setToken("")
        harness.output.setStunServer("stun.l.google.com:19302")
        harness.output.setStunServer("  stun.l.google.com:19302  ")
        assertEquals(1, harness.calls.size)
        assertEquals(0, harness.calls.single().publisher.stopCalls)
    }

    @Test
    fun `a token change restarts a live output with the new credential`() {
        val harness = Harness()
        harness.start(token = "old")

        harness.output.setToken("new")
        assertEquals(2, harness.calls.size)
        assertEquals("new", harness.calls[1].token)
        assertEquals(1, harness.calls[1].publisher.startCalls)
    }

    @Test
    fun `a stun change restarts a live output`() {
        val harness = Harness()
        harness.start()

        harness.output.setStunServer("stun.other.example:3478")
        assertEquals(2, harness.calls.size)
        assertEquals("stun.other.example:3478", harness.calls[1].stunServer)
    }

    @Test
    fun `a mic-verdict change restarts a live output but not a stopped one`() {
        val harness = Harness()
        harness.start()
        harness.output.onMicVerdictChanged()
        assertEquals(2, harness.calls.size)

        harness.output.stop()
        harness.output.onMicVerdictChanged()
        assertEquals(2, harness.calls.size)
        assertFalse(harness.output.isActive())
    }

    // ── the analysis tap's frame feed ──

    @Test
    fun `frames reach the live publisher with the tap's dimensions and vanish while stopped`() {
        val harness = Harness()
        val nv21 = byteArrayOf(1, 2, 3, 4)

        // Stopped: no publisher yet — the feed must be a safe no-op.
        harness.output.feedVideoFrame(nv21, 2, 2, 0)

        harness.start()
        val publisher = harness.calls.single().publisher
        harness.output.feedVideoFrame(nv21, 2, 2, 90)

        assertEquals(1, publisher.frames.size)
        val frame = publisher.frames.single()
        assertTrue(frame.nv21.contentEquals(nv21))
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(90, frame.rotation)
    }

    @Test
    fun `frames stop at the publisher after a stop`() {
        val harness = Harness()
        harness.start()
        val publisher = harness.calls.single().publisher
        harness.output.stop()

        harness.output.feedVideoFrame(byteArrayOf(1), 1, 1, 0)
        assertEquals(0, publisher.frames.size)
    }

    // ── the fps fan-out ──

    @Test
    fun `the target fps reaches a live publisher and a later start`() {
        val harness = Harness()
        harness.start()
        val publisher = harness.calls.single().publisher

        harness.output.setFrameRate(15)
        assertEquals(15, publisher.frameRate)

        // A restart builds the next publisher with the current rate in hand.
        harness.output.setUrl("http://other/whip")
        assertEquals(15, harness.calls[1].publisher.frameRate)
    }

    // ── the status mirror ──

    @Test
    fun `publisher status transitions mirror through onStatusChanged`() {
        val harness = Harness()
        harness.start()

        val publisher = harness.calls.single().publisher
        publisher.onStatus(WhipStatus.Connecting)
        publisher.onStatus(WhipStatus.Connected)
        publisher.onStatus(WhipStatus.Error("WHIP server answered 401 to the offer"))

        assertEquals(WhipStatus.Connected, harness.statuses[1])
        assertEquals(
            WhipStatus.Error("WHIP server answered 401 to the offer"),
            harness.statuses[2],
        )
        assertEquals(
            WhipStatus.Error("WHIP server answered 401 to the offer"),
            harness.output.status(),
        )
    }

    @Test
    fun `a stopped output publishes idle after an error state`() {
        val harness = Harness()
        harness.start()
        harness.calls.single().publisher.onStatus(WhipStatus.Error("boom"))

        harness.output.stop()
        assertEquals(WhipStatus.Idle, harness.output.status())
        assertTrue(harness.statuses.last() is WhipStatus.Idle)
    }

    @Test
    fun `a fresh output is idle`() {
        val harness = Harness()
        assertEquals(WhipStatus.Idle, harness.output.status())
        assertFalse(harness.output.isEnabled())
        assertFalse(harness.output.isActive())
        assertEquals("", harness.output.configuredUrl())
    }
}
