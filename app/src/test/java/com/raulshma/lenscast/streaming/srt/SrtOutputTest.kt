package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SRT push output's lifecycle, JVM-tested behind the [SrtPublisherHandle]
 * seam with a recording fake publisher and a canned [EncodedSource] — the
 * [com.raulshma.lenscast.streaming.rtmp.RtmpOutputTest] pattern.
 */
class SrtOutputTest {

    // ── fakes ──

    /** Publisher fake: one per factory call, recording every handle call. */
    private class FakePublisher(
        val onStatus: (SrtStatus) -> Unit,
    ) : SrtPublisherHandle {
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

        override fun status(): SrtStatus = SrtStatus.Idle

        override fun stats(): SrtStats = SrtStats(rttMs = 42.5)
    }

    private class FakeSource(
        override val videoCodec: RtspVideoCodec = RtspVideoCodec.H264,
    ) : EncodedSource {
        override val sps: ByteArray = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        override val pps: ByteArray = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte())
        override val vps: ByteArray? = null
        override val audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x10)

        override fun requestKeyFrame() = Unit
    }

    private class Harness(source: FakeSource = FakeSource()) {
        val source: FakeSource = source
        val publishers = mutableListOf<FakePublisher>()
        val statuses = mutableListOf<SrtStatus>()

        val output = SrtOutput(
            source = source,
            onStatusChanged = { statuses += it },
            publisherFactory = { _, onStatus ->
                FakePublisher(onStatus).also { publishers += it }
            },
        )
    }

    // ── the validation ladder ──

    @Test
    fun `a disabled output refuses to start with a readable error`() {
        val harness = Harness()
        val result = harness.output.start()
        assertTrue(result is SrtOutput.StartResult.Rejected)
        assertEquals("SRT push is disabled", (result as SrtOutput.StartResult.Rejected).reason)
        assertTrue(harness.output.status() is SrtStatus.Error)
    }

    @Test
    fun `an unusable URL is refused with a readable error`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("not-an-srt-url")
        val result = harness.output.start()
        assertTrue(result is SrtOutput.StartResult.Rejected)
        assertTrue((result as SrtOutput.StartResult.Rejected).reason.contains("srt://"))
    }

    @Test
    fun `an H265 codec is refused before a publisher is built`() {
        val harness = Harness(FakeSource(RtspVideoCodec.H265))
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local:9000")
        val result = harness.output.start()
        assertTrue(result is SrtOutput.StartResult.Rejected)
        assertTrue((result as SrtOutput.StartResult.Rejected).reason.contains("H.264"))
        assertEquals(0, harness.publishers.size)
    }

    @Test
    fun `a passing ladder starts the publisher and reports active`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local:9000?streamid=abc")
        assertEquals(SrtOutput.StartResult.Started, harness.output.start())
        assertTrue(harness.output.isActive())
        assertEquals(1, harness.publishers.single().startCalls)
    }

    @Test
    fun `start is idempotent while live`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        harness.output.start()
        assertEquals(1, harness.publishers.size)
    }

    @Test
    fun `stop tears the publisher down and reports idle`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        harness.output.stop()
        assertFalse(harness.output.isActive())
        assertEquals(1, harness.publishers.single().stopCalls)
        assertEquals(SrtStatus.Idle, harness.output.status())
    }

    @Test
    fun `disabling stops a live output`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        harness.output.setEnabled(false)
        assertEquals(1, harness.publishers.single().stopCalls)
    }

    @Test
    fun `a URL change restarts a live output and is retained while stopped`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local:9000")
        harness.output.start()
        // The restart goes through the output's own stop → start path, so a
        // fresh publisher handle is built with the new target.
        harness.output.setUrl("srt://elsewhere.local:9710")
        assertEquals(2, harness.publishers.size)
        assertEquals(1, harness.publishers[0].stopCalls)
        assertEquals(1, harness.publishers[1].startCalls)
        assertTrue(harness.output.isActive())
        // The URL is retained even while stopped, for the next start.
        harness.output.stop()
        harness.output.setUrl("srt://third.local")
        assertEquals("srt://third.local", harness.output.configuredUrl())
        assertEquals(2, harness.publishers.size)
        harness.output.start()
        assertEquals(3, harness.publishers.size)
    }

    @Test
    fun `a same-URL write never churns a live output`() {
        val harness = Harness()
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        harness.output.setUrl(" srt://listener.local ")
        assertEquals(0, harness.publishers.single().stopCalls)
    }

    @Test
    fun `media feeds reach the live publisher only`() {
        val harness = Harness()
        val units = listOf(EncodedNalUnit(byteArrayOf(0x65, 1), isKeyFrame = true))
        harness.output.feedEncodedVideo(units)
        harness.output.feedEncodedAudio(byteArrayOf(1, 2))
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        harness.output.feedEncodedVideo(units)
        harness.output.feedEncodedAudio(byteArrayOf(3, 4))
        val publisher = harness.publishers.single()
        assertEquals(1, publisher.fedVideo.size)
        assertEquals(1, publisher.fedAudio.size)
    }

    @Test
    fun `stats read through to the live publisher and degrade while stopped`() {
        val harness = Harness()
        assertEquals(0.0, harness.output.stats().rttMs, 0.0)
        harness.output.setEnabled(true)
        harness.output.setUrl("srt://listener.local")
        harness.output.start()
        assertEquals(42.5, harness.output.stats().rttMs, 0.0)
    }
}
