package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfig
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import com.raulshma.lenscast.streaming.rtsp.RtspUriPolicy
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The mic capture the RTSP output needs: start the shared recorder, read its
 * resolved format, and open a live PCM subscriber stream.
 * [AudioStreamingManager] implements it; JVM tests substitute a fake so the
 * audio-wanted and mic-arbitration decisions run without a device.
 */
internal interface RtspAudioSource {
    fun isRunning(): Boolean

    fun start(config: AudioStreamingManager.Config): Boolean

    fun openStream(): InputStream?

    fun getSampleRateHz(): Int

    fun getChannelCount(): Int

    fun stop()
}

/**
 * The live RTSP server as [RtspOutput] drives it. [RtspServer] implements
 * it; JVM tests substitute a fake server that records the calls.
 */
internal interface RtspServerHandle {
    fun start(initial: RtspConfig, audioStream: InputStream?): Boolean

    fun stop()

    fun apply(config: RtspConfig)

    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int)
}

/**
 * The RTSP output, pulled out of [StreamingManager]: the retained
 * [RtspConfig] (every setting lands here even while the output is stopped,
 * so the next start picks it all up), the server lifecycle with the
 * restart-vs-apply choice per change, the audio subscriber [InputStream]
 * handle, the stream URL, and the audio-wanted / mic-arbitration decision
 * (stream audio on and no recording capture claiming the microphone). The
 * manager keeps the public surface, the fan-out, and the web/mDNS concerns;
 * everything "the RTSP output" means sits behind this narrow interface.
 * Decisions are JVM-tested behind the [RtspAudioSource] / [RtspServerHandle]
 * seams.
 *
 * Semantics carried over unchanged: audio bitrate, channel count, and echo
 * cancellation are start-only encoder settings (see
 * [com.raulshma.lenscast.streaming.rtsp.RtspConfigDiff]) — a live output
 * enforces them by restart; frame rate, input format, and auth hot-swap via
 * [RtspServerHandle.apply]; a port change restarts; recording start/stop
 * never restarts a running output.
 */
internal class RtspOutput(
    private val audio: RtspAudioSource,
    private val audioConfig: () -> AudioStreamingManager.Config,
    private val authSpec: () -> RtspAuthSpec?,
    /** Invoked when a stop releases the audio stream this output opened — the owner decides whether another consumer (web) still needs the capture and stops it. */
    private val releaseAudio: () -> Unit,
    /** The running/URL mirror after every server transition, so the owner's state flows follow the output. */
    private val onStateChanged: (running: Boolean, url: String) -> Unit,
    private val serverFactory: (port: Int) -> RtspServerHandle = { RtspServer(it) },
) {

    /** The output is switched on — start() while disabled refuses. */
    private val enabled = AtomicBoolean(false)

    /** The output is live: started and not yet stopped, even if the server failed to bind. */
    private val active = AtomicBoolean(false)

    // Cross-thread: the frame pump reads server/port while settings and
    // lifecycle threads start/stop/restart the output.
    @Volatile
    private var port: Int = RtspServer.DEFAULT_PORT

    @Volatile
    private var server: RtspServerHandle? = null

    @Volatile
    private var audioStream: InputStream? = null

    /** The running server's URL — built at start, blank while stopped. */
    @Volatile
    private var currentUrl = ""

    // One retained config for the output; settings land here even while
    // stopped, so the next start picks them all up.
    @Volatile
    private var config = RtspConfig()

    // The two audio-wanted inputs: the user's stream-audio toggle and the
    // recording pipeline's claim on the microphone.
    @Volatile
    private var streamAudioEnabled = true

    @Volatile
    private var recordingCaptureActive = false

    // ── flags & state reads ──

    /** Switches the output on/off; true when the flag actually changed. */
    fun setEnabled(on: Boolean): Boolean = enabled.getAndSet(on) != on

    fun isEnabled(): Boolean = enabled.get()

    /** True from [start] until [stop] — the owner's "RTSP output live" answer. */
    fun isActive(): Boolean = active.get()

    /** True while a server instance is actually serving. */
    fun isRunning(): Boolean = server != null

    /** The stream URL, or "" while stopped. */
    fun url(): String = currentUrl

    // ── lifecycle ──

    /** Starts the output (no-op when already live, matching the previous activity guard). */
    fun start() {
        if (active.getAndSet(true)) return
        startServer()
        notifyState()
    }

    /** Stops the server, releases the audio handle, and hands the capture release decision to the owner. */
    fun stop() {
        if (!active.getAndSet(false)) return
        stopServer()
        notifyState()
    }

    /** One camera frame onto the encoder path; no-op while no server is serving. */
    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        server?.pushFrame(yuvData, width, height, rotation)
    }

    // ── settings: the restart-vs-apply choice ──

    /** A new port lands immediately; a running, enabled output restarts on it. */
    fun setPort(newPort: Int) {
        if (newPort == port) return
        port = newPort
        if (enabled.get() && server != null) {
            restartServer()
        }
    }

    /** A new input format reconfigures the encoder inside the running server — hot-swap. */
    fun setInputFormat(format: RtspInputFormat) {
        if (format == config.inputFormat) return
        config = config.copy(inputFormat = format)
        server?.apply(config)
    }

    /** The frame rate reaches the RTP timestamp increment through the live config getter — hot-swap. */
    fun setFrameRate(fps: Int) {
        config = config.copy(videoFrameRate = fps)
        server?.apply(config)
    }

    /** The running server's authorizer reads the auth spec live — re-reads it here and hot-swaps. */
    fun setAuth() {
        config = config.copy(auth = authSpec())
        server?.apply(config)
    }

    /**
     * The audio bitrate: a NEEDS_RESTART change ([AacEncoder][com.raulshma.lenscast.streaming.rtsp.AacEncoder]
     * only reads it at its next start), so it is retained here and — while
     * the output is live with the audio track wanted — enforced by the
     * audio restart ladder below. Applying it live would silently no-op.
     */
    fun setAudioBitrate(bitrateKbps: Int) {
        config = config.copy(audioBitrateKbps = bitrateKbps)
        restartIfAudioTrackWanted()
    }

    /**
     * An audio-capture change the encoder only reads at start (channel
     * count, echo cancellation): the next start's capture config carries it,
     * and a live output enforces it through the same restart ladder.
     */
    fun restartForAudioConfigChange() {
        restartIfAudioTrackWanted()
    }

    /**
     * The stream-audio toggle — the one audio change that restarts even when
     * turning the track off, because the audio track changes either way.
     */
    fun setAudioWanted(wanted: Boolean) {
        streamAudioEnabled = wanted
        if (active.get()) {
            restartServer()
        }
    }

    /**
     * The recording pipeline's claim on the microphone: while set, a start
     * opens no audio track. Deliberately not a live trigger — recording
     * start/stop never restarts a running output.
     */
    fun setRecordingCaptureActive(recording: Boolean) {
        recordingCaptureActive = recording
    }

    /** The audio restart ladder: restart a live output only while the audio track matters to it. */
    private fun restartIfAudioTrackWanted() {
        if (active.get() && streamAudioEnabled) {
            restartServer()
        }
    }

    // ── server lifecycle ──

    /** The mic-arbitration decision at start: stream audio on and no recording capture claiming the mic. */
    private fun audioWanted(): Boolean = streamAudioEnabled && !recordingCaptureActive

    private fun restartServer() {
        stopServer()
        notifyState()
        startServer()
        notifyState()
    }

    private fun startServer() {
        if (server != null) return
        val newServer = serverFactory(port)

        var stream: InputStream? = null
        if (audioWanted()) {
            // Ensure audio capture is running
            if (!audio.isRunning()) {
                audio.start(audioConfig())
            }
            if (audio.isRunning()) {
                stream = audio.openStream()
                audioStream = stream
            }
        }

        // The audio fields resolve from the live capture (its sample rate
        // and channel count are whatever the probe ladder resolved); the
        // audio bitrate was retained by [setAudioBitrate] and stays put.
        config = config.copy(
            audioEnabled = stream != null,
            audioSampleRateHz = audio.getSampleRateHz(),
            audioChannelCount = audio.getChannelCount(),
            auth = authSpec(),
        )

        if (newServer.start(config, stream)) {
            server = newServer
            currentUrl = buildUrl()
            Log.d(TAG, "RTSP server started on port $port (audio=${stream != null})")
        } else {
            audioStream?.close()
            audioStream = null
            Log.e(TAG, "Failed to start RTSP server on port $port")
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        audioStream?.close()
        audioStream = null
        // The capture this output started may still be needed by the web
        // output — the owner decides.
        releaseAudio()
        currentUrl = ""
    }

    private fun notifyState() {
        onStateChanged(server != null, currentUrl)
    }

    private fun buildUrl(): String {
        val ip = NetworkUtils.getLocalIpAddress() ?: "localhost"
        return "rtsp://$ip:$port/${RtspUriPolicy.DEFAULT_STREAM_PATH}"
    }

    companion object {
        private const val TAG = "RtspOutput"
    }
}
