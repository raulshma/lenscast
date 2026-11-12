package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.hls.HlsVideoSink
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.rtsp.VideoEncoder
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's live reconfigure behaviour. Two concerns:
 *
 * The reconfigure-wedge recovery — a permanent encoder (re)start failure must
 * leave the hub able to heal, not wedged black while [isRunning] answers live.
 * A permanent reconfigure failure nulls the stopped encoder (so the
 * [EncodedSource] answers null for a dead stream), a later refresh rebuilds and
 * frames flow again, and a stop nulls the encoder too.
 *
 * The live resolution change — [EncodedStreamHub.setVideoResolution] must
 * reconfigure the running encoder to the new size (the frame path aspect-fills
 * onto it immediately), and a same-size apply must not churn the pipeline.
 */
class EncodedStreamHubReconfigureTest {

    /**
     * The overlay suite's fake, plus a `failStarts` fail-next-N flag: each
     * [start] call while the flag is positive fails (and consumes one), so
     * the reconfigure's one-retry ladder can be driven into its permanent
     * failure branch deterministically. [sps]/[pps] answer the real-looking
     * parameter sets whenever an instance is constructed, so a non-null
     * [EncodedSource.sps] reads exactly as "a live encoder sits in the slot".
     */
    private class FakeVideoEncoder : VideoEncoder {
        val encodedFrames = mutableListOf<ByteArray>()
        var configuredWidth = 0
        var configuredHeight = 0
        var configureCount = 0
        var started = false
        var failStarts = 0

        override fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int) {
            configuredWidth = width
            configuredHeight = height
            configureCount++
        }

        override fun setInputFormat(format: RtspInputFormat) {}

        override fun start(): Boolean {
            if (failStarts > 0) {
                failStarts--
                return false
            }
            started = true
            return true
        }

        override fun stop() {
            started = false
        }

        override fun submitBlackFrame() {}

        override fun encodeFrame(nv21Data: ByteArray) {
            encodedFrames.add(nv21Data)
        }

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

    private val noopRtspSink = object : EncodedSink {
        override fun feedVideo(nalUnits: List<EncodedNalUnit>) {}

        override fun feedAudio(aacData: ByteArray) {}
    }

    private val noopHlsSink = object : HlsVideoSink {
        override fun feedVideo(nalus: List<EncodedNalUnit>) {}

        override fun feedAudio(aacData: ByteArray) {}
    }

    /** A hub with an active RTSP sink and audio off, handing back the given encoder as the main one. */
    private fun hubWith(mainEncoder: FakeVideoEncoder): EncodedStreamHub {
        val handOut = mutableListOf<VideoEncoder>(mainEncoder)
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
            rtspSink = noopRtspSink,
            hlsSink = noopHlsSink,
            wsVideoSink = {},
            rtmpSink = noopRtspSink,
            encoderFactory = { handOut.removeAt(0) },
        )
    }

    @Test
    fun `a permanent reconfigure failure nulls the encoder so the source answers null`() {
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder)
        hub.refresh()
        assertTrue(hub.isRunning())
        assertTrue(hub.sps != null)

        // The reconfigure ladder (start + its one retry) both fail: permanent.
        encoder.failStarts = 2
        hub.setInputFormat(RtspInputFormat.NV21)

        // The wedge symptom, still true by design: the pipeline answers live.
        assertTrue(hub.isRunning())
        // The fix: the stopped encoder left the slot — no parameter sets for
        // a dead stream, and the frame path's null-encoder guard no-ops.
        assertFalse(encoder.started)
        assertNull(hub.sps)
        assertNull(hub.pps)
        assertNull(hub.vps)
    }

    @Test
    fun `a later refresh rebuilds after a permanent failure and frames flow again`() {
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder)
        hub.refresh()

        encoder.failStarts = 2
        hub.setInputFormat(RtspInputFormat.NV21)
        assertNull(hub.sps)

        // The heal: the next frame-path refresh (≤500ms cadence) rebuilds.
        encoder.failStarts = 0
        hub.pushFrame(ByteArray(64 * 64 * 3 / 2), 64, 64, rotation = 0)

        assertTrue(encoder.started)
        assertTrue(hub.sps != null)
        assertEquals(1, encoder.encodedFrames.size)
    }

    @Test
    fun `stop nulls the encoder so the source answers null after a clean stop`() {
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder)
        hub.refresh()
        assertTrue(hub.sps != null)

        hub.stop()

        assertFalse(hub.isRunning())
        assertFalse(encoder.started)
        assertNull(hub.sps)
        assertNull(hub.pps)
        assertNull(hub.vps)
    }

    @Test
    fun `a live resolution change reconfigures the running encoder to the new size`() {
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder)
        hub.refresh()
        assertTrue(hub.isRunning())
        assertEquals(1280, encoder.configuredWidth)
        assertEquals(720, encoder.configuredHeight)

        // The live 16:9 → 4:3 switch that used to leave the encoder at the old
        // dimensions while the frame path scaled to the new ones: every
        // NV21→NV12 conversion then ran off the end of the smaller buffer.
        hub.setVideoResolution(960, 720)

        assertEquals(960, encoder.configuredWidth)
        assertEquals(720, encoder.configuredHeight)
        assertTrue(encoder.started)
        // The next frame is aspect-filled onto the NEW size and encodes clean.
        hub.pushFrame(ByteArray(1280 * 720 * 3 / 2), 1280, 720, rotation = 0)
        assertEquals(960 * 720 * 3 / 2, encoder.encodedFrames.last().size)
    }

    @Test
    fun `a same-size resolution apply does not churn the live encoder`() {
        val encoder = FakeVideoEncoder()
        val hub = hubWith(encoder)
        hub.refresh()
        val configuresAfterStart = encoder.configureCount

        hub.setVideoResolution(1280, 720)

        assertEquals(configuresAfterStart, encoder.configureCount)
        assertTrue(encoder.started)
    }
}
