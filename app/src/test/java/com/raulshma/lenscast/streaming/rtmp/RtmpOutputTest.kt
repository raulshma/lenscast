package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RTMP push output's lifecycle, JVM-tested behind the [RtmpPublisherHandle]
 * seam with a recording fake publisher and a canned [EncodedSource] — the
 * [com.raulshma.lenscast.streaming.RtspOutputTest] pattern.
 */
class RtmpOutputTest {

    // ── fakes ──

    /** Publisher fake: one per factory call, recording every handle call. */
    private class FakePublisher(
        val onStatus: (RtmpStatus) -> Unit,
    ) : RtmpPublisherHandle {
        var startCalls = 0
        var stopCalls = 0
        val fedVideo = mutableListOf<List<EncodedNalUnit>>()
        val fedAudio = mutableListOf<ByteArray>()

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
            fedVideo += nalUnits
        }

        override fun feedAudio(aacData: ByteArray) {
            fedAudio += aacData
        }

        override fun status(): RtmpStatus = RtmpStatus.Idle
    }

    private class FakeSource(
        override val videoCodec: RtspVideoCodec = RtspVideoCodec.H264,
    ) : EncodedSource {
        override val sps: ByteArray = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        override val pps: ByteArray = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte())
        override val vps: ByteArray? = null
        override val audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x10)
        var keyFrameRequests = 0

        override fun requestKeyFrame() {
            keyFrameRequests++
        }
    }

    private class Harness(source: FakeSource = FakeSource()) {
        val source: FakeSource = source
        val publishers = mutableListOf<FakePublisher>()
        val statuses = mutableListOf<RtmpStatus>()

        val output = RtmpOutput(
            source = source,
            onStatusChanged = { statuses += it },
            publisherFactory = { _, onStatus ->
                FakePublisher(onStatus).also { publishers += it }
            },
        )
    }

    private fun keyFrame(): List<EncodedNalUnit> = listOf(EncodedNalUnit(byteArrayOf(0x65, 1, 2), isKeyFrame = true))

    // ── the validation ladder ──

    @Test
    fun `a disabled output refuses to start`() {
        val harness = Harness()
        val result = harness.output.start()
        assertTrue(result is RtmpOutput.StartResult.Rejected)
        assertEquals("RTMP push is disabled", (result as RtmpOutput.StartResult.Rejected).reason)
        assertTrue(harness.publishers.isEmpty())
        assertFalse(harness.output.isActive())
        assertEquals(RtmpStatus.Error("RTMP push is disabled"), harness.output.status())
    }

    @Test
    fun `an unusable URL refuses with the readable shape hint`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("http://example.com/live/key")

        val result = harness.output.start()
        assertTrue(result is RtmpOutput.StartResult.Rejected)
        assertTrue((result as RtmpOutput.StartResult.Rejected).reason.contains("Invalid RTMP URL"))
        assertTrue(harness.publishers.isEmpty())
    }

    @Test
    fun `an H265 source refuses with the codec hint`() {
        val harness = Harness(FakeSource(videoCodec = RtspVideoCodec.H265))
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")

        val result = harness.output.start()
        assertTrue(result is RtmpOutput.StartResult.Rejected)
        assertTrue((result as RtmpOutput.StartResult.Rejected).reason.contains("H.264"))
        assertTrue(harness.publishers.isEmpty())
    }

    // ── the start/stop lifecycle ──

    @Test
    fun `a valid start builds starts and reports the publisher`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")

        assertEquals(RtmpOutput.StartResult.Started, harness.output.start())
        val publisher = harness.publishers.single()
        assertEquals(1, publisher.startCalls)
        assertTrue(harness.output.isActive())
    }

    @Test
    fun `start is a no-op while already live`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()

        assertEquals(RtmpOutput.StartResult.Started, harness.output.start())
        assertEquals(1, harness.publishers.single().startCalls)
    }

    @Test
    fun `stop stops the publisher and goes idle`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()
        val publisher = harness.publishers.single()
        publisher.onStatus(RtmpStatus.Connecting)

        harness.output.stop()
        assertEquals(1, publisher.stopCalls)
        assertFalse(harness.output.isActive())
        assertEquals(RtmpStatus.Idle, harness.output.status())
        // A second stop is a no-op.
        harness.output.stop()
        assertEquals(1, publisher.stopCalls)
    }

    @Test
    fun `disabling stops a live output and arming alone never starts`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()
        val publisher = harness.publishers.single()

        assertTrue(harness.output.setEnabled(false))
        assertEquals(1, publisher.stopCalls)
        assertFalse(harness.output.isActive())

        // Re-arming does not start by itself — the start is a user/API action.
        assertTrue(harness.output.setEnabled(true))
        assertFalse(harness.output.isActive())
        assertTrue(harness.publishers.size == 1)
    }

    // ── URL handling ──

    @Test
    fun `the URL is retained while stopped for the next start`() {
        val harness = Harness()
        harness.output.setUrl("rtmp://example.com/live/key")
        assertEquals("rtmp://example.com/live/key", harness.output.configuredUrl())
    }

    @Test
    fun `a URL change restarts a live output on the new target`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()
        val first = harness.publishers.single()

        harness.output.setUrl("rtmp://elsewhere:1936/live/key")
        assertEquals(1, first.stopCalls)
        val second = harness.publishers.last()
        assertTrue(second !== first)
        assertEquals(1, second.startCalls)
    }

    @Test
    fun `a same-URL set is a no-op for a live output`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()

        harness.output.setUrl("rtmp://example.com/live/key") // trimmed-equal
        harness.output.setUrl("  rtmp://example.com/live/key  ") // trimmed-equal
        assertEquals(1, harness.publishers.size)
        assertEquals(0, harness.publishers.single().stopCalls)
    }

    // ── the hub's sink ──

    @Test
    fun `media feeds reach the live publisher and vanish while stopped`() {
        val harness = Harness()
        val audio = byteArrayOf(1, 2, 3)
        val frames = keyFrame()

        // Stopped: no publisher yet — the feed must be a safe no-op.
        harness.output.feedEncodedVideo(frames)
        harness.output.feedEncodedAudio(audio)

        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()
        harness.output.feedEncodedVideo(frames)
        harness.output.feedEncodedAudio(audio)

        val publisher = harness.publishers.single()
        assertEquals(listOf(frames), publisher.fedVideo)
        assertEquals(listOf(audio), publisher.fedAudio)
    }

    // ── the status mirror ──

    @Test
    fun `publisher status transitions mirror through onStatusChanged`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("rtmp://example.com/live/key")
        harness.output.start()

        val publisher = harness.publishers.single()
        publisher.onStatus(RtmpStatus.Connecting)
        publisher.onStatus(RtmpStatus.Connected)
        publisher.onStatus(RtmpStatus.Error("RTMP connect rejected: nope"))

        assertEquals(RtmpStatus.Connected, harness.statuses[1])
        assertEquals(
            RtmpStatus.Error("RTMP connect rejected: nope"),
            harness.statuses[2],
        )
        assertEquals(
            RtmpStatus.Error("RTMP connect rejected: nope"),
            harness.output.status(),
        )
    }
}
