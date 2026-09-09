package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfig
import com.raulshma.lenscast.streaming.rtsp.RtspConfigDiff
import com.raulshma.lenscast.streaming.rtsp.RtspField
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import com.raulshma.lenscast.streaming.rtsp.RtspUriPolicy
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
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
    fun start(initial: RtspConfig): Boolean

    fun stop()

    fun apply(config: RtspConfig)

    /** One encoded H.264 access unit from the encoded-stream hub. */
    fun feedVideo(nalUnits: List<EncodedNalUnit>)

    /** One AAC access unit from the encoded-stream hub. */
    fun feedAudio(aacData: ByteArray)

    /** The watchdog/dashboard health snapshot. */
    fun health(): RtspHealth
}

/**
 * How a settings change reaches a live output beyond what the diff verdict
 * decides. [FROM_DIFF] is the default routing: NEEDS_RESTART fields restart
 * through the audio ladder, HotSwap-only changes apply live, and a change
 * to nothing does nothing. [AUDIO_LADDER] forces the restart ladder for
 * changes no [RtspConfig] field carries or that must ladder even when the
 * value did not move (the capture-side channel/echo settings, the audio
 * bitrate). [WHILE_ACTIVE] restarts whenever the output is live — the
 * stream-audio toggle's track-existence change is real whether it turns
 * the track on or off.
 */
internal enum class RestartTrigger {
    FROM_DIFF,
    AUDIO_LADDER,
    WHILE_ACTIVE,
}

/** Public health snapshot for the watchdog + dashboard (stable API). */
data class RtspHealth(
    val playingClients: Int = 0,
    val totalClients: Int = 0,
    val acceptedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val healthy: Boolean = true,
)

/**
 * The RTSP output, pulled out of [StreamingManager]: the retained
 * [RtspConfig] (every setting lands here even while the output is stopped,
 * so the next start picks it all up), the server lifecycle with the
 * restart-vs-apply choice routed through one entry — [update] lands the
 * change and lets the [RtspConfigDiff] scope tags decide — the audio
 * subscriber [InputStream] handle, the stream URL, and the audio-wanted /
 * mic-arbitration decision (stream audio on and no recording capture
 * claiming the microphone). The manager keeps the public surface, the
 * fan-out, and the web/mDNS concerns; everything "the RTSP output" means
 * sits behind this narrow interface. Decisions are JVM-tested behind the
 * [RtspAudioSource] / [RtspServerHandle] seams.
 *
 * Semantics carried over unchanged: audio bitrate, channel count, and echo
 * cancellation are start-only encoder settings (see
 * [com.raulshma.lenscast.streaming.rtsp.RtspConfigDiff]) — a live output
 * enforces them by the audio restart ladder; frame rate, input format, and
 * auth hot-swap via [RtspServerHandle.apply], and a video-bitrate change
 * fans out to the encoded-stream hub (the encoder owner) through
 * [onVideoBitrateChanged]; a port change restarts; recording start/stop
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
    /** The encoded-stream hub's live bitrate seam: the H.264 encoder is the hub's, so a bitrate change lands there, not in any server instance. */
    private val onVideoBitrateChanged: (Int) -> Unit = {},
    private val serverFactory: (port: Int) -> RtspServerHandle,
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

    /** RTSP health snapshot for the watchdog: playing/total clients + encoder counters. */
    fun healthSnapshot(): RtspHealth = server?.health() ?: RtspHealth(healthy = false)

    /** The stream URL, or "" while stopped. */
    fun url(): String = currentUrl

    /** The configured port, retained even while stopped — the owner's mDNS/URL seam, no reflection. */
    fun port(): Int = port

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

    /**
     * The encoded-stream hub's video feed, forwarded to whatever server
     * instance is live — the RTP half of the fan-out. No-op while stopped.
     */
    fun feedEncodedVideo(nalUnits: List<EncodedNalUnit>) {
        server?.feedVideo(nalUnits)
    }

    /** The hub's AAC feed, forwarded like [feedEncodedVideo]. */
    fun feedEncodedAudio(aacData: ByteArray) {
        server?.feedAudio(aacData)
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
        update { it.copy(inputFormat = format) }
    }

    /** The frame rate reaches the RTP timestamp increment through the live config getter — hot-swap. */
    fun setFrameRate(fps: Int) {
        update { it.copy(videoFrameRate = fps) }
    }

    /**
     * A new RTSP resolution lands in the retained config (both video
     * dimensions move together — they are one setting), and a live output
     * restarts: an encoder dimension change is a NEEDS_RESTART verdict in
     * [RtspConfigDiff] (MediaCodec dims are fixed at `configure`), so a
     * hot-swap would silently keep the old size. While stopped the value is
     * simply retained for the next start. A same-size call is a no-op, so a
     * persisted-settings re-emission never churns a live output.
     */
    fun setResolution(resolution: RtspResolution) {
        val unchanged = config.videoWidth == resolution.width && config.videoHeight == resolution.height
        if (unchanged) return
        update(RestartTrigger.WHILE_ACTIVE) {
            it.copy(videoWidth = resolution.width, videoHeight = resolution.height)
        }
    }

    /**
     * The RTSP video codec, mirrored exactly on [setResolution]: the value
     * lands in the retained config and a live output restarts — a codec swap
     * is a NEEDS_RESTART verdict in [RtspConfigDiff] (the encode, the RTP
     * packetizer, and the SDP all move together), and the WHILE_ACTIVE
     * trigger restarts video-only outputs too, not just audio-wanted ones.
     * While stopped the value is simply retained for the next start. A
     * same-codec call is a no-op, so a settings re-emission never churns a
     * live output. The encoded-stream hub reconfigures its own encoder
     * separately (the manager fans the value out to both, like resolution).
     */
    fun setVideoCodec(codec: RtspVideoCodec) {
        val unchanged = config.videoCodec == codec
        if (unchanged) return
        update(RestartTrigger.WHILE_ACTIVE) {
            it.copy(videoCodec = codec)
        }
    }

    /** The running server's authorizer reads the auth spec live — re-reads it here and hot-swaps. */
    fun setAuth() {
        update { it.copy(auth = authSpec()) }
    }

    /**
     * The audio bitrate: a NEEDS_RESTART change ([AacEncoder][com.raulshma.lenscast.streaming.rtsp.AacEncoder]
     * only reads it at its next start), so it is retained here and — while
     * the output is live with the audio track wanted — enforced by the
     * audio restart ladder. Applying it live would silently no-op. The
     * ladder fires even when the value did not move, exactly like the
     * hand-coded ladder it replaced.
     */
    fun setAudioBitrate(bitrateKbps: Int) {
        update(RestartTrigger.AUDIO_LADDER) { it.copy(audioBitrateKbps = bitrateKbps) }
    }

    /**
     * An audio-capture change the encoder only reads at start (channel
     * count, echo cancellation): the next start's capture config carries it,
     * and a live output enforces it through the same restart ladder. No
     * [RtspConfig] field moves — the trigger forces the ladder.
     */
    fun restartForAudioConfigChange() {
        update(trigger = RestartTrigger.AUDIO_LADDER)
    }

    /**
     * The stream-audio toggle — the one audio change that restarts even when
     * turning the track off, because the audio track changes either way. The
     * flag is not a [RtspConfig] field (it resolves into the config at
     * start), so the trigger restarts whenever the output is live, diff or
     * not.
     */
    fun setAudioWanted(wanted: Boolean) {
        streamAudioEnabled = wanted
        update(trigger = RestartTrigger.WHILE_ACTIVE)
    }

    /**
     * The recording pipeline's claim on the microphone: while set, a start
     * opens no audio track. Deliberately not a live trigger — recording
     * start/stop never restarts a running output.
     */
    fun setRecordingCaptureActive(recording: Boolean) {
        recordingCaptureActive = recording
    }

    /**
     * The coalesced audio entry — the one settings write that can move the
     * wanted flag and the retained bitrate together. Lands both, then routes
     * exactly once through [update]: a wanted flip restarts whenever the
     * output is live (the audio track changes either way, like
     * [setAudioWanted]); otherwise the audio restart ladder decides (like
     * [setAudioBitrate] and [restartForAudioConfigChange] — a capture-side
     * change the diff cannot see still ladders while the track is wanted).
     * At most one restart per call, where the old per-setting sequence paid
     * one restart per setter and could wedge the native capture mid-storm.
     */
    fun setAudioConfig(wanted: Boolean, bitrateKbps: Int) {
        val wantedChanged = streamAudioEnabled != wanted
        streamAudioEnabled = wanted
        val trigger = if (wantedChanged) RestartTrigger.WHILE_ACTIVE else RestartTrigger.AUDIO_LADDER
        update(trigger) { it.copy(audioBitrateKbps = bitrateKbps) }
    }

    /**
     * The one settings entry: land the transform on the retained config,
     * then route by the [RtspConfigDiff] verdict — any NEEDS_RESTART field
     * takes the audio restart ladder, HotSwap-only changes apply live, and
     * an empty diff does nothing. The trigger overrides carry the two
     * changes no single config field expresses (see [RestartTrigger]).
     * [transform] sits last so setters can pass it as a trailing lambda.
     */
    internal fun update(
        trigger: RestartTrigger = RestartTrigger.FROM_DIFF,
        transform: (RtspConfig) -> RtspConfig = { it },
    ) {
        val old = config
        config = transform(old)
        val changed = RtspConfigDiff.of(old, config)
        when {
            trigger == RestartTrigger.WHILE_ACTIVE && active.get() -> restartServer()
            trigger == RestartTrigger.AUDIO_LADDER || RtspConfigDiff.needsRestart(changed) ->
                restartIfAudioTrackWanted()
            changed.isNotEmpty() -> server?.apply(config)
        }
        // The H.264 encoder belongs to the encoded-stream hub, not to any
        // server instance: every video-bitrate change lands there too — live
        // via setParameters while the hub runs, retained for its next start
        // otherwise. Any restart re-reads the retained config.
        if (RtspField.VIDEO_BITRATE in changed) {
            onVideoBitrateChanged(config.videoBitrate)
        }
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

    /**
     * The live answer to the mic-arbitration decision above, for the
     * foreground-service type derivation: the service must carry the
     * MICROPHONE type whenever this output wants the mic, or the OS
     * silences the capture (RTSP audio present but digitally silent).
     */
    fun isAudioWanted(): Boolean = audioWanted()

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

        if (newServer.start(config)) {
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
