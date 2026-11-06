package com.raulshma.lenscast.streaming.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHIP transport-distinct pins over the push output: the token/STUN
 * blank→null handoff, the mic-arbitration verdict riding the factory call
 * and the verdict-change restart (the audio decision is a
 * publisher-construction input, not a hot-swap), the fps fan-out, and the
 * NV21 analysis-tap frame feed. The shared lifecycle — the validation
 * ladder, start/stop, disable-stops, URL restarts, the status mirror, and
 * the settle epilogue — is [com.raulshma.lenscast.streaming.PushOutputTest]'s,
 * run against the WHIP configuration among the others; the [WhipUrl] decode
 * rules are [WhipUrlTest]'s. The real [WhipPublisher] — PeerConnectionFactory,
 * libwebrtc's offer/answer, the dedicated AudioRecord — is native and
 * therefore device-verify-only; these tests pin everything the manager
 * drives through the output.
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
            settle = {},
        )

        /** Arms, configures, and starts — the common passing-start ladder; verdicts are read off [calls]. */
        fun start(
            url: String = "http://example.com:8000/whip",
            token: String = "",
            stunServer: String = "stun.l.google.com:19302",
        ) {
            output.setEnabled(true)
            output.setUrl(url)
            output.setToken(token)
            output.setStunServer(stunServer)
            output.start()
        }
    }

    // ── the connect-parameter handoff ──

    @Test
    fun `a valid start builds and starts the publisher with its connect parameters`() {
        val harness = Harness()
        harness.start(token = "tok", stunServer = "stun.example.com:3478")

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

    // ── restart-on-config-change beyond the URL ──

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
    fun `a same-values set is a no-op for a live output`() {
        val harness = Harness()
        harness.start()

        harness.output.setToken("  ") // blank-equal to the default blank
        harness.output.setStunServer("stun.l.google.com:19302")
        harness.output.setStunServer("  stun.l.google.com:19302  ")
        assertEquals(1, harness.calls.size)
        assertEquals(0, harness.calls.single().publisher.stopCalls)
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
}
