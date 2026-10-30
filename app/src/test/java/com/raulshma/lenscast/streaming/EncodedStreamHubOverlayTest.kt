package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.streaming.hls.HlsVideoSink
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.rtsp.VideoEncoder
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's overlay insertion point: the compositor runs after the
 * rotate/scale and before [VideoEncoder.encodeFrame], for the main encode
 * and the 640×480 sub alike; the camera's own array is never mutated; and
 * the sub-stream scales the ROTATED frame (not the re-scaled main frame —
 * the stale source dims that corrupted /sub under a mismatched resolution).
 */
class EncodedStreamHubOverlayTest {

    private class FakeVideoEncoder : VideoEncoder {
        val encodedFrames = mutableListOf<ByteArray>()
        var configuredWidth = 0
        var configuredHeight = 0
        var started = false

        override fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int) {
            configuredWidth = width
            configuredHeight = height
        }

        override fun setInputFormat(format: RtspInputFormat) {}

        override fun start(): Boolean {
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

        override val sps: ByteArray? get() = null

        override val pps: ByteArray? get() = null

        override val vps: ByteArray? get() = null

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

    /** A hub with an active RTSP sink, audio off, and the given encoder/compositor fakes. */
    private fun hubWith(
        mainEncoder: FakeVideoEncoder,
        subEncoder: FakeVideoEncoder,
        overlayCompositor: EncodedOverlayCompositor,
    ): EncodedStreamHub {
        val handOut = mutableListOf<VideoEncoder>(mainEncoder, subEncoder)
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
            overlayCompositor = overlayCompositor,
        )
    }

    private class CountingRasterizer : EncodedOverlayCompositor.Rasterizer {
        var renderCount = 0

        override fun render(
            settings: OverlaySettings,
            targetWidth: Int,
            nowMs: Long,
        ): EncodedOverlayCompositor.YuvaStrip {
            renderCount++
            val strip = ByteArray(4 * 2 * 4)
            for (i in 0 until 4 * 2) {
                strip[i * 4] = 200.toByte()
                strip[i * 4 + 1] = 54
                strip[i * 4 + 2] = 242.toByte()
                strip[i * 4 + 3] = 0xFF.toByte()
            }
            return EncodedOverlayCompositor.YuvaStrip(strip, 4, 2)
        }
    }

    private fun overlaySettings(enabled: Boolean) = OverlaySettings(
        enabled = enabled,
        showTimestamp = true,
    )

    @Test
    fun `overlay stamps the main encode and never touches the camera array`() {
        val rasterizer = CountingRasterizer()
        val mainEncoder = FakeVideoEncoder()
        val subEncoder = FakeVideoEncoder()
        val hub = hubWith(mainEncoder, subEncoder, EncodedOverlayCompositor(rasterizer))
        hub.setOverlaySettings(overlaySettings(enabled = true))

        // 64×64 analysis frame at the default 1280×720 encode size: the main
        // frame is a fresh aspect-filled array, not the camera's.
        val cameraFrame = ByteArray(64 * 64 * 3 / 2)
        hub.pushFrame(cameraFrame, 64, 64, rotation = 0)

        assertEquals(1, mainEncoder.encodedFrames.size)
        val mainFrame = mainEncoder.encodedFrames[0]
        assertEquals(1280 * 720 * 3 / 2, mainFrame.size)
        // The overlay block lands at the corner margin.
        assertEquals(200, mainFrame[16 * 1280 + 16].toInt() and 0xFF)
        // The camera array comes through clean — the MJPEG/WHIP/WHEP taps read it.
        assertNotSame(cameraFrame, mainFrame)
        assertArrayEquals(ByteArray(cameraFrame.size), cameraFrame)
        assertEquals(1, rasterizer.renderCount)
    }

    @Test
    fun `a same-size analysis frame is blended into a copy, not in place`() {
        val rasterizer = CountingRasterizer()
        val mainEncoder = FakeVideoEncoder()
        val hub = hubWith(mainEncoder, FakeVideoEncoder(), EncodedOverlayCompositor(rasterizer))
        hub.setVideoResolution(64, 64)
        hub.setOverlaySettings(overlaySettings(enabled = true))

        val cameraFrame = ByteArray(64 * 64 * 3 / 2)
        hub.pushFrame(cameraFrame, 64, 64, rotation = 0)

        val mainFrame = mainEncoder.encodedFrames[0]
        // rotation 0 + matching dims means the frame aliased the camera's
        // array — the blend must have copied it first.
        assertNotSame(cameraFrame, mainFrame)
        assertEquals(200, mainFrame[16 * 64 + 16].toInt() and 0xFF)
        assertArrayEquals(ByteArray(cameraFrame.size), cameraFrame)
    }

    @Test
    fun `overlay disabled passes the analysis array straight through`() {
        val mainEncoder = FakeVideoEncoder()
        val hub = hubWith(mainEncoder, FakeVideoEncoder(), EncodedOverlayCompositor(CountingRasterizer()))
        hub.setVideoResolution(64, 64)
        hub.setOverlaySettings(overlaySettings(enabled = false))

        val cameraFrame = ByteArray(64 * 64 * 3 / 2)
        hub.pushFrame(cameraFrame, 64, 64, rotation = 0)

        assertEquals(1, mainEncoder.encodedFrames.size)
        assertSame(cameraFrame, mainEncoder.encodedFrames[0])
    }

    @Test
    fun `sub-stream scales the rotated frame — not the re-scaled main frame`() {
        val mainEncoder = FakeVideoEncoder()
        val subEncoder = FakeVideoEncoder()
        val hub = hubWith(mainEncoder, subEncoder, EncodedOverlayCompositor(CountingRasterizer()))
        hub.setVideoResolution(1920, 1080)
        hub.setSubStreamEnabled(true)
        hub.setOverlaySettings(overlaySettings(enabled = false))

        // Analysis 1280×720, encode 1920×1080 — the mismatch that used to
        // feed scaleNv21 the stale source dims for the sub scale.
        val width = 1280
        val height = 720
        val cameraFrame = ByteArray(width * height * 3 / 2) { i ->
            if (i < width * height) (i % 251).toByte() else (i % 249).toByte()
        }
        hub.pushFrame(cameraFrame, width, height, rotation = 0)

        assertEquals(1920 * 1080 * 3 / 2, mainEncoder.encodedFrames[0].size)
        assertEquals(1, subEncoder.encodedFrames.size)
        // The sub frame derives from the ROTATED frame — byte-identical to
        // scaling the source directly.
        val expected = YuvConverter.scaleNv21(cameraFrame, width, height, 640, 480)
        assertArrayEquals(expected, subEncoder.encodedFrames[0])
    }

    @Test
    fun `overlay stamps the sub encode at its own size`() {
        val rasterizer = CountingRasterizer()
        val mainEncoder = FakeVideoEncoder()
        val subEncoder = FakeVideoEncoder()
        val hub = hubWith(mainEncoder, subEncoder, EncodedOverlayCompositor(rasterizer))
        hub.setVideoResolution(1920, 1080)
        hub.setSubStreamEnabled(true)
        hub.setOverlaySettings(overlaySettings(enabled = true))

        hub.pushFrame(ByteArray(1280 * 720 * 3 / 2), 1280, 720, rotation = 0)

        assertTrue(subEncoder.encodedFrames.isNotEmpty())
        val subFrame = subEncoder.encodedFrames.last()
        assertEquals(640 * 480 * 3 / 2, subFrame.size)
        assertEquals(200, subFrame[16 * 640 + 16].toInt() and 0xFF)
        // The main frame got the same treatment at the main encode size.
        assertEquals(200, mainEncoder.encodedFrames.last()[16 * 1920 + 16].toInt() and 0xFF)
    }
}
