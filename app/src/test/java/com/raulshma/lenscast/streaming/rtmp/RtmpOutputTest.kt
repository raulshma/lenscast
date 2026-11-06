package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.PushOutput
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The RTMP transport-distinct pins over the push output: the media feed path
 * and the [RtmpUrl] parse wiring through the thin class. The shared
 * lifecycle — the validation ladder, start/stop, disable-stops, restarts,
 * the status mirror, and the settle epilogue — is
 * [com.raulshma.lenscast.streaming.PushOutputTest]'s, run against the RTMP
 * configuration among the others; the [RtmpUrl] decode rules themselves are
 * [RtmpUrlTest]'s.
 */
class RtmpOutputTest {

    // ── fakes ──

    /** Publisher fake: one per factory call, recording every handle call and the parsed target handed over. */
    private class FakePublisher(
        val onStatus: (RtmpStatus) -> Unit,
    ) : RtmpPublisherHandle {
        var startCalls = 0
        var stopCalls = 0
        var parsedUrl: RtmpUrl? = null
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

        override fun requestKeyFrame() = Unit
    }

    private class Harness(source: FakeSource = FakeSource()) {
        val publishers = mutableListOf<FakePublisher>()

        val output = RtmpOutput(
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
        fun start(url: String = "rtmp://example.com/live/key"): PushOutput.StartResult {
            output.setEnabled(true)
            output.setUrl(url)
            return output.start()
        }
    }

    private fun keyFrame(): List<EncodedNalUnit> = listOf(EncodedNalUnit(byteArrayOf(0x65, 1, 2), isKeyFrame = true))

    // ── the parse wiring ──

    @Test
    fun `a valid start hands the publisher factory a parsed RtmpUrl`() {
        val harness = Harness()

        harness.start("rtmp://publisher.example:1936/live/stream-key")
        assertEquals(1, harness.publishers.size)
        val parsed = harness.publishers.single().parsedUrl
        assertEquals("publisher.example", parsed?.host)
        assertEquals(1936, parsed?.port)
        assertEquals("live", parsed?.app)
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

        harness.start()
        harness.output.feedEncodedVideo(frames)
        harness.output.feedEncodedAudio(audio)

        val publisher = harness.publishers.single()
        assertEquals(listOf(frames), publisher.fedVideo)
        assertEquals(listOf(audio), publisher.fedAudio)
    }
}
