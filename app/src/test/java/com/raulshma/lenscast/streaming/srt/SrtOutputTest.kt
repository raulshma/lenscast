package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.PushOutput
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SRT transport-distinct pins over the push output: the media feed path,
 * the [SrtUrl] parse wiring through the thin class, and the wire-stats read
 * seam. The shared lifecycle — the validation ladder, start/stop,
 * disable-stops, restarts, the status mirror, and the settle epilogue — is
 * [com.raulshma.lenscast.streaming.PushOutputTest]'s, run against the SRT
 * configuration among the others; the [SrtUrl] decode and its
 * [SrtUrl.redacted] rendering are [SrtUrlTest]'s.
 */
class SrtOutputTest {

    // ── fakes ──

    /** Publisher fake: one per factory call, recording every handle call and the parsed target handed over. */
    private class FakePublisher(
        val onStatus: (SrtStatus) -> Unit,
    ) : SrtPublisherHandle {
        var startCalls = 0
        var stopCalls = 0
        val fedVideo = mutableListOf<List<EncodedNalUnit>>()
        val fedAudio = mutableListOf<ByteArray>()
        var parsedUrl: SrtUrl? = null

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
        val publishers = mutableListOf<FakePublisher>()

        val output = SrtOutput(
            source = source,
            onStatusChanged = {},
            publisherFactory = { url, onStatus ->
                FakePublisher(onStatus).also {
                    it.parsedUrl = url
                    publishers += it
                }
            },
            settle = {},
        )

        /** Arms, configures, and starts — the common passing-start ladder. */
        fun start(url: String = "srt://listener.local:9000"): PushOutput.StartResult {
            output.setEnabled(true)
            output.setUrl(url)
            return output.start()
        }
    }

    // ── the parse wiring ──

    @Test
    fun `a valid start hands the publisher factory a parsed SrtUrl with its stream id`() {
        val harness = Harness()

        harness.start("srt://listener.local:9000?streamid=abc")
        assertEquals(1, harness.publishers.size)
        val parsed = harness.publishers.single().parsedUrl
        assertEquals("listener.local", parsed?.host)
        assertEquals(9000, parsed?.port)
        assertEquals("abc", parsed?.streamId)
    }

    // ── the hub's sink ──

    @Test
    fun `media feeds reach the live publisher only`() {
        val harness = Harness()
        val units = listOf(EncodedNalUnit(byteArrayOf(0x65, 1), isKeyFrame = true))

        // Stopped: no publisher yet — the feed must be a safe no-op.
        harness.output.feedEncodedVideo(units)
        harness.output.feedEncodedAudio(byteArrayOf(1, 2))

        harness.start()
        harness.output.feedEncodedVideo(units)
        harness.output.feedEncodedAudio(byteArrayOf(3, 4))

        val publisher = harness.publishers.single()
        assertEquals(1, publisher.fedVideo.size)
        assertEquals(1, publisher.fedAudio.size)
    }

    // ── the wire-stats read seam ──

    @Test
    fun `stats read through to the live publisher and degrade while stopped`() {
        val harness = Harness()
        assertEquals(0.0, harness.output.stats().rttMs, 0.0)

        harness.start()
        assertEquals(42.5, harness.output.stats().rttMs, 0.0)

        harness.output.stop()
        assertEquals(0.0, harness.output.stats().rttMs, 0.0)
    }
}
