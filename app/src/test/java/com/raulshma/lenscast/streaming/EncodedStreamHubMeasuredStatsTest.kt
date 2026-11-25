package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.hls.HlsVideoSink
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.VideoEncoder
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoded lane's measured-stream status (issue #25): the encoder-output
 * fan-out feeds a sliding window, and [EncodedStreamHub.encodedStreamStatus]
 * reads the encoder's REAL bitrate/fps from it — never a drain-rate artifact,
 * never an invented default — next to the configured target and encode
 * geometry. The injected clock makes every cadence deterministic; the tests
 * drive the exact production seam (the encoder's onEncodedFrame callback).
 */
class EncodedStreamHubMeasuredStatsTest {

    /** The reconfigure suite's fake, emitting the AUs it is handed back through [onEncodedFrame]. */
    private class FakeVideoEncoder : VideoEncoder {
        var started = false
        override fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int) {}

        override fun setInputFormat(format: RtspInputFormat) {}

        override fun start(): Boolean {
            started = true
            return true
        }

        override fun stop() {
            started = false
        }

        override fun submitBlackFrame() {}

        override fun encodeFrame(nv21Data: ByteArray) {}

        override fun isEncoderLagged(): Boolean = false

        override fun setBitrate(newBitrate: Int) {}

        override fun requestKeyFrame() {}

        override val sps: ByteArray get() = byteArrayOf(0x67, 0x64, 0x00, 0x1F)

        override val pps: ByteArray get() = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte())

        override val vps: ByteArray? = null

        override var onEncodedFrame: ((List<EncodedNalUnit>) -> Unit)? = null
    }

    private class NoAudio : RtspAudioSource {
        override fun isRunning(): Boolean = false

        override fun start(config: AudioStreamingManager.Config): Boolean = false

        override fun openStream(): InputStream? = null

        override fun getSampleRateHz(): Int = 48_000

        override fun getChannelCount(): Int = 1

        override fun stop() {}
    }

    private class FakeClock {
        var nowMs: Long = 1_000_000L
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private fun hubWith(encoder: FakeVideoEncoder, clock: FakeClock): EncodedStreamHub {
        val handOut = mutableListOf<VideoEncoder>(encoder)
        val noopSink = object : EncodedSink {
            override fun feedVideo(nalUnits: List<EncodedNalUnit>) {}

            override fun feedAudio(aacData: ByteArray) {}
        }
        val noopHls = object : HlsVideoSink {
            override fun feedVideo(nalus: List<EncodedNalUnit>) {}

            override fun feedAudio(aacData: ByteArray) {}
        }
        return EncodedStreamHub(
            policyInputs = {
                EncodedStreamPolicy.Inputs(
                    webActive = false,
                    rtspActive = true,
                    hlsRequested = false,
                    wsVideoClients = 0,
                    rtmpActive = false,
                )
            },
            audio = NoAudio(),
            audioConfig = { AudioStreamingManager.Config() },
            audioWanted = { false },
            audioBitrateKbps = { 128 },
            rtspSink = noopSink,
            hlsSink = noopHls,
            wsVideoSink = {},
            rtmpSink = noopSink,
            encoderFactory = { handOut.removeAt(0) },
            nowMs = { clock.nowMs },
        )
    }

    /** One AU of [bytes] through the exact production seam: the encoder's output callback. */
    private fun emitAu(encoder: FakeVideoEncoder, bytes: Int) {
        encoder.onEncodedFrame!!.invoke(listOf(EncodedNalUnit(ByteArray(bytes), isKeyFrame = false)))
    }

    @Test
    fun `a fresh hub answers not running with zeroed measurements`() {
        val clock = FakeClock()
        val hub = hubWith(FakeVideoEncoder(), clock)
        val status = hub.encodedStreamStatus()
        assertFalse(status.running)
        assertEquals(0, status.measuredBitrateKbps)
        assertEquals(0.0, status.fps, 0.0)
    }

    @Test
    fun `fan-out frames fill the window into a known bitrate and fps`() {
        val clock = FakeClock()
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder, clock)
        hub.refresh()
        assertTrue(hub.isRunning())

        // 50 AUs of 12_500 bytes at 100 ms spacing = 625_000 bytes inside the
        // 5 s window → 1000 kbps measured against the 2000 kbps target, 10 fps.
        repeat(50) {
            emitAu(encoder, 12_500)
            clock.advance(100)
        }
        val status = hub.encodedStreamStatus()
        assertTrue(status.running)
        assertEquals(1000, status.measuredBitrateKbps)
        assertEquals(10.0, status.fps, 0.001)
        // The configured encode facts ride alongside the measurements.
        assertEquals(2000, status.targetBitrateKbps)
        assertEquals("h264", status.codec)
        assertEquals(1280, status.width)
        assertEquals(720, status.height)
    }

    @Test
    fun `silence decays the measurements while the encoder stays up`() {
        val clock = FakeClock()
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder, clock)
        hub.refresh()
        repeat(50) {
            emitAu(encoder, 12_500)
            clock.advance(100)
        }
        assertEquals(1000, hub.encodedStreamStatus().measuredBitrateKbps)

        clock.advance(5_001)
        val status = hub.encodedStreamStatus()
        // Honest decay, not a frozen last value: still running, measured 0.
        assertTrue(status.running)
        assertEquals(0, status.measuredBitrateKbps)
        assertEquals(0.0, status.fps, 0.0)
    }

    @Test
    fun `a stopped hub reports not running`() {
        val clock = FakeClock()
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder, clock)
        hub.refresh()
        emitAu(encoder, 12_500)
        assertTrue(hub.encodedStreamStatus().running)

        hub.stop()
        assertFalse(hub.encodedStreamStatus().running)
    }
}
