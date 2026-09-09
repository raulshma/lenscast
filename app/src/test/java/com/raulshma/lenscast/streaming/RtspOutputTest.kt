package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfig
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class RtspOutputTest {

    // ── fakes ──

    /** Audio-capture fake: records starts/opens/stops, no device. */
    private class FakeAudio : RtspAudioSource {
        var running = false
        var startCalls = 0
        var lastConfig: AudioStreamingManager.Config? = null
        var openStreamCalls = 0
        var stopCalls = 0
        var declaredSampleRateHz = 44_100
        var declaredChannelCount = 2

        override fun isRunning(): Boolean = running

        override fun start(config: AudioStreamingManager.Config): Boolean {
            startCalls++
            lastConfig = config
            running = true
            return true
        }

        override fun openStream(): InputStream {
            openStreamCalls++
            return object : InputStream() {
                override fun read(): Int = -1
            }
        }

        override fun getSampleRateHz(): Int = declaredSampleRateHz

        override fun getChannelCount(): Int = declaredChannelCount

        override fun stop() {
            stopCalls++
            running = false
        }
    }

    /** Server fake: one per factory call, recording every call. */
    private class FakeServer : RtspServerHandle {
        val startCalls = mutableListOf<RtspConfig>()
        val applyCalls = mutableListOf<RtspConfig>()
        val fedVideo = mutableListOf<List<EncodedNalUnit>>()
        val fedAudio = mutableListOf<ByteArray>()
        var stopCalls = 0
        var startReturns = true
        var healthy = true

        override fun start(initial: RtspConfig): Boolean {
            startCalls += initial
            return startReturns
        }

        override fun stop() {
            stopCalls++
        }

        override fun apply(config: RtspConfig) {
            applyCalls += config
        }

        override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
            fedVideo += nalUnits
        }

        override fun feedAudio(aacData: ByteArray) {
            fedAudio += aacData
        }

        override fun health(): RtspHealth = RtspHealth(healthy = healthy)
    }

    private class Harness(
        val audio: FakeAudio = FakeAudio(),
        val authSpecs: MutableList<RtspAuthSpec?> = mutableListOf(),
    ) {
        val servers = mutableListOf<FakeServer>()
        val factoryPorts = mutableListOf<Int>()
        val releaseCalls = mutableListOf<Unit>()
        val states = mutableListOf<Pair<Boolean, String>>()
        val hubBitrateCalls = mutableListOf<Int>()
        var audioConfigCalls = 0

        val output = RtspOutput(
            audio = audio,
            audioConfig = {
                audioConfigCalls++
                AudioStreamingManager.Config()
            },
            authSpec = { authSpecs.lastOrNull() },
            releaseAudio = { releaseCalls += Unit },
            onVideoBitrateChanged = { hubBitrateCalls += it },
            onStateChanged = { running, url -> states += running to url },
            serverFactory = { port ->
                factoryPorts += port
                FakeServer().also { servers += it }
            },
        )

        /** Start() and give back the single server it created. */
        fun startEnabled(): FakeServer {
            output.setEnabled(true)
            output.start()
            return servers.last()
        }
    }

    // ── start: the audio-wanted / mic-arbitration decision ──

    @Test
    fun `start with audio wanted starts capture and attaches a stream`() {
        val h = Harness()
        val server = h.startEnabled()

        assertEquals(1, h.audio.startCalls)
        assertEquals(1, h.audio.openStreamCalls)
        assertEquals(1, server.startCalls.size)
        assertTrue(server.startCalls.single().audioEnabled)
    }

    @Test
    fun `start while recording claims the mic opens no audio track`() {
        val h = Harness()
        h.output.setEnabled(true)
        h.output.setRecordingCaptureActive(true)

        val server = h.startEnabled()

        assertEquals(0, h.audio.startCalls)
        assertEquals(0, h.audio.openStreamCalls)
        assertFalse(server.startCalls.single().audioEnabled)
    }

    @Test
    fun `start with stream audio off opens no audio track`() {
        val h = Harness()
        h.output.setEnabled(true)
        h.output.setAudioWanted(false)
        // setAudioWanted restarts nothing yet (not active); start proceeds audio-less.
        h.output.start()

        assertEquals(0, h.audio.startCalls)
        assertFalse(h.servers.single().startCalls.single().audioEnabled)
    }

    @Test
    fun `start does not restart capture that is already running`() {
        val h = Harness()
        h.audio.running = true

        h.startEnabled()

        assertEquals(0, h.audio.startCalls)
        assertEquals(1, h.audio.openStreamCalls)
    }

    // ── start: config assembly ──

    @Test
    fun `config assembly reads the capture format and the auth provider`() {
        val h = Harness()
        val spec = RtspAuthSpec("user", "hash", "HA1UPPER")
        h.authSpecs += spec

        val server = h.startEnabled()

        val config = server.startCalls.single()
        assertEquals(44_100, config.audioSampleRateHz)
        assertEquals(2, config.audioChannelCount)
        assertEquals(spec, config.auth)
        assertEquals("user", config.auth?.username)
        assertEquals("ha1upper", config.auth?.digestHa1)
    }

    @Test
    fun `the audio bitrate retained by setAudioBitrate is what start assembles`() {
        val h = Harness()
        h.output.setAudioBitrate(96)

        val config = h.startEnabled().startCalls.single()

        assertEquals(96, config.audioBitrateKbps)
        // The capture config is fetched only when audio is actually wanted.
        assertEquals(1, h.audioConfigCalls)
    }

    @Test
    fun `a failed start closes the opened audio stream and reports not running`() {
        val h = Harness()
        h.output.setEnabled(true)
        val opened = mutableListOf<InputStream>()
        val failingOutput = RtspOutput(
            audio = object : RtspAudioSource by h.audio {
                override fun openStream(): InputStream =
                    object : InputStream() {
                        override fun read(): Int = -1

                        override fun close() {
                            opened += this
                        }
                    }
            },
            audioConfig = { AudioStreamingManager.Config() },
            authSpec = { null },
            releaseAudio = {},
            onStateChanged = { _, _ -> },
            serverFactory = { FakeServer().also { it.startReturns = false } },
        )
        failingOutput.start()

        assertFalse(failingOutput.isRunning())
        assertEquals("", failingOutput.url())
        assertEquals(1, opened.size) // the stream opened for the attempt was closed
    }

    // ── the restart ladder ──

    @Test
    fun `audio bitrate change while live with audio wanted restarts the server`() {
        val h = Harness()
        h.startEnabled()
        val first = h.servers.single()
        assertTrue(first.stopCalls == 0)

        h.output.setAudioBitrate(96)

        assertEquals(2, h.servers.size) // a fresh server instance per start
        assertEquals(1, first.stopCalls)
        assertEquals(96, h.servers[1].startCalls.single().audioBitrateKbps)
        assertTrue(h.output.isRunning())
    }

    @Test
    fun `audio bitrate change while live without audio does not restart`() {
        val h = Harness()
        h.output.setEnabled(true)
        h.output.setAudioWanted(false)
        h.output.start()

        h.output.setAudioBitrate(96)

        assertEquals(1, h.servers.size)
        // Retained anyway — the next start picks it up.
        h.output.stop()
        h.output.start()
        assertEquals(96, h.servers[1].startCalls.single().audioBitrateKbps)
    }

    @Test
    fun `audio bitrate change while stopped is retained, never started`() {
        val h = Harness()

        h.output.setAudioBitrate(96)

        assertEquals(0, h.servers.size)
        assertEquals(96, h.startEnabled().startCalls.single().audioBitrateKbps)
    }

    @Test
    fun `channel and echo changes ride the same ladder as the bitrate`() {
        val h = Harness()
        h.startEnabled()

        h.output.restartForAudioConfigChange()

        assertEquals(2, h.servers.size)
        assertEquals(1, h.servers[0].stopCalls)
    }

    @Test
    fun `the stream-audio toggle restarts even when turning the track off`() {
        val h = Harness()
        h.startEnabled()

        h.output.setAudioWanted(false)

        assertEquals(2, h.servers.size)
        assertFalse(h.servers[1].startCalls.single().audioEnabled)
        assertEquals(1, h.audio.openStreamCalls) // no second open
    }

    // ── the coalesced audio entry ──

    @Test
    fun `setAudioConfig with wanted flip and bitrate change restarts exactly once`() {
        val h = Harness()
        h.startEnabled()

        h.output.setAudioConfig(false, 96)

        assertEquals(2, h.servers.size) // one restart, not one per field
        val config = h.servers[1].startCalls.single()
        assertFalse(config.audioEnabled)
        assertEquals(96, config.audioBitrateKbps)
        assertEquals(1, h.audio.openStreamCalls) // no second open when turning off
    }

    @Test
    fun `setAudioConfig with a bitrate-only change ladders like setAudioBitrate`() {
        val h = Harness()
        h.startEnabled()

        h.output.setAudioConfig(true, 96)

        assertEquals(2, h.servers.size)
        assertEquals(96, h.servers[1].startCalls.single().audioBitrateKbps)
    }

    @Test
    fun `setAudioConfig with identical values still ladders while live and wanted`() {
        // The output cannot see capture-side changes (channels/echo live
        // outside RtspConfig), so the ladder stays forced here — the
        // no-op detection lives one layer up, in the manager's snapshot.
        val h = Harness()
        val server = h.startEnabled()

        h.output.setAudioConfig(true, 128)

        assertEquals(2, h.servers.size)
        assertEquals(0, server.applyCalls.size)
    }

    @Test
    fun `setAudioConfig while stopped just retains - no server yet`() {
        val h = Harness()
        h.output.setEnabled(true)

        h.output.setAudioConfig(false, 96)

        assertEquals(0, h.servers.size)
        h.output.start()
        val config = h.servers.single().startCalls.single()
        assertFalse(config.audioEnabled)
        assertEquals(96, config.audioBitrateKbps)
    }

    @Test
    fun `isAudioWanted mirrors the toggle and the recording claim`() {
        val h = Harness()
        h.output.setEnabled(true)

        assertTrue(h.output.isAudioWanted())

        h.output.setAudioWanted(false)
        assertFalse(h.output.isAudioWanted())

        h.output.setAudioWanted(true)
        h.output.setRecordingCaptureActive(true)
        assertFalse(h.output.isAudioWanted())

        h.output.setRecordingCaptureActive(false)
        assertTrue(h.output.isAudioWanted())
    }

    @Test
    fun `recording start and stop never restart a live output`() {
        val h = Harness()
        h.startEnabled()

        h.output.setRecordingCaptureActive(true)
        h.output.setRecordingCaptureActive(false)

        assertEquals(1, h.servers.size)
    }

    // ── hot-swap setters ──

    @Test
    fun `frame rate input format and auth apply live without a restart`() {
        val h = Harness()
        val server = h.startEnabled()
        h.authSpecs += RtspAuthSpec("u", "p", "h")

        h.output.setFrameRate(30) // not the 24 fps default — a real diff
        h.output.setInputFormat(RtspInputFormat.I420)
        h.output.setAuth()

        assertEquals(1, h.servers.size) // no restart
        assertEquals(3, server.applyCalls.size)
        assertEquals(30, server.applyCalls[0].videoFrameRate)
        assertEquals(RtspInputFormat.I420, server.applyCalls[1].inputFormat)
        assertEquals("u", server.applyCalls[2].auth?.username)
    }

    @Test
    fun `hot-swap setters while stopped just retain - no server yet`() {
        val h = Harness()
        h.authSpecs += RtspAuthSpec("u", "p", "h")

        h.output.setFrameRate(30)
        h.output.setInputFormat(RtspInputFormat.NV21)
        h.output.setAuth()

        assertEquals(0, h.servers.size)
        val config = h.startEnabled().startCalls.single()
        assertEquals(30, config.videoFrameRate)
        assertEquals(RtspInputFormat.NV21, config.inputFormat)
        assertEquals("u", config.auth?.username)
    }

    // ── the update() diff routing ──

    @Test
    fun `a video-bitrate-only change hot-swaps while an audio-bitrate change ladders`() {
        val h = Harness()
        h.startEnabled()

        h.output.update { it.copy(videoBitrate = 4_000_000) }

        // Video bitrate is HOT_SWAP: the encoded-stream hub applies it live
        // (setVideoBitrate → MediaCodec setParameters), and the output only
        // refreshes the SDP's b=AS line through apply.
        assertEquals(1, h.servers.size)
        assertEquals(4_000_000, h.servers[0].applyCalls.single().videoBitrate)
        assertEquals(listOf(4_000_000), h.hubBitrateCalls)

        h.output.setAudioBitrate(96)

        // Audio bitrate is NEEDS_RESTART — the AAC encoder only
        // reads it at its next start.
        assertEquals(2, h.servers.size)
        assertEquals(96, h.servers[1].startCalls.single().audioBitrateKbps)
    }

    @Test
    fun `input-format and frame-rate changes ride the diff into apply, not the restart path`() {
        val h = Harness()
        val server = h.startEnabled()

        h.output.setInputFormat(RtspInputFormat.NV21)
        h.output.setFrameRate(30)

        assertEquals(1, h.servers.size)
        assertEquals(2, server.applyCalls.size)
        assertEquals(RtspInputFormat.NV21, server.applyCalls[0].inputFormat)
        assertEquals(30, server.applyCalls[1].videoFrameRate)
    }

    @Test
    fun `a change mixing hot-swap and restart fields takes the restart path only`() {
        val h = Harness()
        val server = h.startEnabled()

        h.output.update { it.copy(inputFormat = RtspInputFormat.I420, audioBitrateKbps = 64) }

        // Any NEEDS_RESTART field wins: restart with the new config, no live apply.
        assertEquals(2, h.servers.size)
        assertEquals(0, server.applyCalls.size)
        val config = h.servers[1].startCalls.single()
        assertEquals(64, config.audioBitrateKbps)
        assertEquals(RtspInputFormat.I420, config.inputFormat)
    }

    @Test
    fun `an update that changes nothing is a full no-op`() {
        val h = Harness()
        val server = h.startEnabled()

        h.output.update { it }

        assertEquals(1, h.servers.size)
        assertEquals(0, server.applyCalls.size)
    }

    // ── resolution ──

    @Test
    fun `a resolution change restarts a live output with both dimensions`() {
        val h = Harness()
        h.startEnabled()

        h.output.setResolution(RtspResolution.P1080)

        // A dimension change is NEEDS_RESTART in the diff — it must not
        // attempt a live apply.
        assertEquals(2, h.servers.size)
        val config = h.servers[1].startCalls.single()
        assertEquals(1920, config.videoWidth)
        assertEquals(1080, config.videoHeight)
    }

    @Test
    fun `a resolution change while stopped is retained for the next start`() {
        val h = Harness()
        h.output.setEnabled(true)

        h.output.setResolution(RtspResolution.P480)

        assertEquals(0, h.servers.size)
        val config = h.startEnabled().startCalls.single()
        assertEquals(640, config.videoWidth)
        assertEquals(480, config.videoHeight)
    }

    @Test
    fun `a same-resolution call is a full no-op`() {
        val h = Harness()
        val server = h.startEnabled()

        h.output.setResolution(RtspResolution.P720) // the config's default

        assertEquals(1, h.servers.size)
        assertEquals(0, server.applyCalls.size)
    }

    // ── port ──

    @Test
    fun `port change restarts a running enabled output`() {
        val h = Harness()
        h.startEnabled()

        h.output.setPort(9554)

        assertEquals(2, h.servers.size)
        assertEquals(9554, h.factoryPorts[1])
        assertTrue(h.output.url().endsWith(":9554/stream"))
    }

    @Test
    fun `port change on a stopped output just lands for the next start`() {
        val h = Harness()
        h.output.setEnabled(true)

        h.output.setPort(9554)

        assertEquals(0, h.servers.size)
        h.output.start()
        assertEquals(9554, h.factoryPorts.single())
    }

    @Test
    fun `same port is a no-op`() {
        val h = Harness()
        h.startEnabled()
        val before = h.servers.size

        h.output.setPort(h.factoryPorts.single())

        assertEquals(before, h.servers.size)
    }

    // ── lifecycle, state, and release ──

    @Test
    fun `encoded feeds reach the running server and no-op while stopped`() {
        val h = Harness()
        val server = h.startEnabled()

        h.output.feedEncodedVideo(emptyList())
        h.output.feedEncodedAudio(ByteArray(4))
        assertEquals(1, server.fedVideo.size)
        assertEquals(1, server.fedAudio.size)

        h.output.stop()
        h.output.feedEncodedVideo(emptyList())
        h.output.feedEncodedAudio(ByteArray(4))
        assertEquals(1, server.fedVideo.size)
        assertEquals(1, server.fedAudio.size)
    }

    @Test
    fun `healthSnapshot reports the live server verdict and not-running when stopped`() {
        val h = Harness()
        val server = h.startEnabled()

        assertTrue(h.output.healthSnapshot().healthy)

        server.healthy = false
        assertFalse(h.output.healthSnapshot().healthy)

        h.output.stop()
        assertFalse(h.output.healthSnapshot().healthy)
    }

    @Test
    fun `stop stops the server and fires the audio-release decision`() {
        val h = Harness()
        h.startEnabled()

        h.output.stop()

        assertEquals(1, h.servers[0].stopCalls)
        assertEquals(1, h.releaseCalls.size)
        assertFalse(h.output.isRunning())
        assertFalse(h.output.isActive())
        assertEquals("", h.output.url())
    }

    @Test
    fun `state transitions mirror start and restart`() {
        val h = Harness()
        h.startEnabled()

        h.output.stop()
        h.output.start()

        // start → (true, url); stop → (false, ""); start → (true, url)
        assertEquals(3, h.states.size)
        assertTrue(h.states[0].first)
        assertTrue(h.states[0].second.startsWith("rtsp://"))
        assertEquals(false to "", h.states[1])
        assertTrue(h.states[2].first)
    }

    @Test
    fun `restart notifies stopped-then-started, not just the final state`() {
        val h = Harness()
        h.startEnabled()

        h.output.setAudioBitrate(64)

        // The original stop → (false,"") followed by the fresh start → (true, url).
        assertEquals(listOf(false to "") + listOf(true to h.output.url()), h.states.drop(1))
    }

    @Test
    fun `enabled flag reports changes - gating the owner's start calls stays upstream`() {
        val h = Harness()

        assertTrue(h.output.setEnabled(true))
        assertFalse(h.output.setEnabled(true)) // unchanged
        assertTrue(h.output.setEnabled(false))

        // Enabled gates the owner's startStreaming/startRtspStreaming calls
        // (as today); a direct start() is unconditional.
        assertFalse(h.output.isActive())
        h.output.start()
        assertTrue(h.output.isActive())
    }

    @Test
    fun `start while already live is a no-op`() {
        val h = Harness()
        h.startEnabled()

        h.output.start()

        assertEquals(1, h.servers.size)
        assertEquals(1, h.states.size) // only the first start notified
    }
}
