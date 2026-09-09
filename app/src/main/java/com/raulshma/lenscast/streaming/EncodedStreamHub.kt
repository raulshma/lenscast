package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.streaming.hls.HlsVideoSink
import com.raulshma.lenscast.streaming.rtsp.AacEncoder
import com.raulshma.lenscast.streaming.rtsp.H264Encoder
import com.raulshma.lenscast.streaming.rtsp.H265Encoder
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.rtsp.VideoEncoder
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The parameter-set seam the RTSP server consumes for its SDP and PLAY
 * keyframe requests: the live encoder state of the shared encode pipeline,
 * made codec-aware — [videoCodec] names the active encoder, [sps]/[pps] are
 * that codec's SPS/PPS (both codecs carry them), and [vps] is the H.265-only
 * third parameter set (always null on H.264, so an H.264 SDP can never see a
 * stale VPS). [EncodedStreamHub] implements it in production; JVM tests
 * substitute fakes.
 */
interface EncodedSource {
    val sps: ByteArray?

    val pps: ByteArray?

    /** The active codec's H.265 VPS; always null under H.264. */
    val vps: ByteArray?

    val videoCodec: RtspVideoCodec

    val audioSpecificConfig: ByteArray?

    fun requestKeyFrame()
}

/**
 * The encoded-output seam: encoded H.264 and AAC access units as fanned out
 * by [EncodedStreamHub]. The RTSP output's forwarding sink answers it with
 * the RTP distribution path; the HLS ring and the WS video path receive the
 * same access units through their own sinks so a single encode serves every
 * consumer.
 */
internal interface EncodedSink {
    fun feedVideo(nalUnits: List<EncodedNalUnit>)

    fun feedAudio(aacData: ByteArray)
}

/**
 * The shared video/AAC encode pipeline, decoupled from the RTSP server:
 * camera YUV in, encoded access units out to every registered sink — the
 * RTSP server (RTP), the HLS ring, and the WS video path. Its start/stop
 * decision is the pure [EncodedStreamPolicy] verdict over sink activity, so
 * HLS and WS video keep a live encoded source even when the RTSP output is
 * off. The audio half taps the shared mic capture through a subscriber
 * stream and (re)attaches whenever the capture or the audio config moved —
 * the hub's version of the RTSP output's audio restart ladder.
 *
 * The frame path is what [com.raulshma.lenscast.streaming.rtsp.RtspServer]
 * used to do inline: interval throttle, encoder-lag gating, NV21 rotation,
 * and the aspect-fill fit onto the configured encode size — the frames are
 * scaled (never re-scaled dimensions) to [setVideoResolution]'s retained
 * size, so the RTSP output's resolution choice is authoritative regardless
 * of the camera's analysis resolution.
 *
 * The video codec ([RtspVideoCodec], persisted through the store and applied
 * by the Settings Applier) selects which encoder is instantiated, lazily and
 * one at a time; a codec change on a running pipeline reconfigures stop →
 * (new) encoder → start + black frame. On H.265 the fan-out feeds the RTSP
 * sink ONLY — the HLS TS muxer and the WS/WebCodecs video path are H.264-only and are gated
 * off, so HLS/WS stay dark until the codec returns to H.264.
 */
internal class EncodedStreamHub(
    private val policyInputs: () -> EncodedStreamPolicy.Inputs,
    private val audio: RtspAudioSource,
    private val audioConfig: () -> AudioStreamingManager.Config,
    private val audioWanted: () -> Boolean,
    private val audioBitrateKbps: () -> Int,
    private val rtspSink: EncodedSink,
    private val hlsSink: HlsVideoSink,
    private val wsVideoSink: (List<EncodedNalUnit>) -> Unit,
) : EncodedSource {

    private val aacEncoder = AacEncoder()

    // The video encoders, one per codec, created lazily and only for the
    // active codec — an H.264-only deployment never constructs an H265Encoder
    // (and vice versa). [activeEncoder] is whichever instance the current
    // [videoCodec] selected; both holders survive so a codec flip back does
    // not rebuild what it already built.
    private var h264Encoder: VideoEncoder? = null
    private var h265Encoder: VideoEncoder? = null

    @Volatile
    private var activeEncoder: VideoEncoder? = null

    private val running = AtomicBoolean(false)
    private val stateLock = Any()

    // Frame-path policy evaluations are rate-limited: the verdict reads
    // settings flows and sink activity under the state lock, which a
    // per-frame evaluation would hammer at full camera fps. Lifecycle
    // callers use the unthrottled [refresh].
    @Volatile private var lastFramePathRefreshMs = 0L

    private val acceptedFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)

    // Retained encode config: settings land here even while the pipeline is
    // stopped, so the next start picks them all up (the RtspOutput pattern).
    @Volatile private var videoWidth = StreamDefaults.RTSP_VIDEO_WIDTH
    @Volatile private var videoHeight = StreamDefaults.RTSP_VIDEO_HEIGHT
    @Volatile private var videoBitrate = StreamDefaults.RTSP_VIDEO_BITRATE
    @Volatile private var frameRate = StreamDefaults.STREAM_FPS
    @Volatile private var inputFormat = RtspInputFormat.AUTO
    @Volatile private var activeVideoCodec = RtspVideoCodec.H264

    @Volatile private var audioStream: InputStream? = null
    @Volatile private var configuredSampleRateHz = -1
    @Volatile private var configuredChannelCount = -1
    @Volatile private var configuredAudioBitrateKbps = -1

    private val frameThrottle = FrameThrottle(
        intervalMs = { FrameTiming.frameIntervalMs(frameRate) },
        tolerance = FrameThrottle.TOLERANCE,
        updateClockOnReject = true,
    )

    init {
        aacEncoder.onEncodedFrame = { aacData, _ -> fanOutAudio(aacData) }
    }

    /**
     * The active codec's encoder, created on first use and cached — the only
     * place either MediaCodec video encoder is constructed. Must be called
     * under [stateLock] (creation and wiring race with start/stop otherwise).
     */
    private fun encoderForLocked(codec: RtspVideoCodec): VideoEncoder = when (codec) {
        RtspVideoCodec.H264 -> h264Encoder ?: H264Encoder().also {
            it.onEncodedFrame = { units -> fanOutVideo(RtspVideoCodec.H264, units) }
            h264Encoder = it
        }
        RtspVideoCodec.H265 -> h265Encoder ?: H265Encoder().also {
            it.onEncodedFrame = { units -> fanOutVideo(RtspVideoCodec.H265, units) }
            h265Encoder = it
        }
    } // [codec] is always the retained [activeVideoCodec]; the parameter keeps the helper honest.

    // ── lifecycle: the policy verdict, evaluated on demand ──

    /**
     * One policy evaluation: start the pipeline when any encoded sink is
     * active, stop it when none is. Idempotent both ways; called from the
     * frame path and from the owner's lifecycle transitions.
     */
    fun refresh() {
        synchronized(stateLock) {
            if (EncodedStreamPolicy.shouldRun(policyInputs())) {
                startLocked()
            } else {
                stopLocked()
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    /** Forced teardown for owner release paths; normal stops ride [refresh]. */
    fun stop() {
        synchronized(stateLock) { stopLocked() }
    }

    private fun startLocked() {
        if (!running.get()) {
            val encoder = encoderForLocked(activeVideoCodec)
            encoder.configure(videoWidth, videoHeight, videoBitrate, frameRate)
            encoder.setInputFormat(inputFormat)
            if (!encoder.start()) {
                Log.e(TAG, "${activeVideoCodec.wireName.uppercase()} encoder failed to start; encoded sinks stay idle")
                return
            }
            activeEncoder = encoder
            // Force CSD emission so a freshly started pipeline advertises
            // real parameter sets (SPS/PPS, or VPS/SPS/PPS on H.265) to
            // DESCRIBE and mid-join WS clients immediately.
            encoder.submitBlackFrame()
            running.set(true)
            acceptedFrames.set(0)
            droppedFrames.set(0)
            Log.d(TAG, "Encoded stream started (${activeVideoCodec.wireName}; rtsp/hls/ws sinks attached)")
        }
        ensureAudioLocked()
    }

    private fun stopLocked() {
        if (running.getAndSet(false)) {
            activeEncoder?.stop()
            Log.d(TAG, "Encoded stream stopped")
        }
        stopAudioLocked()
    }

    // ── audio: the (re)attach ladder ──

    /**
     * Keeps the AAC half matched to the shared capture: starts the capture
     * when it is wanted but down, and (re)attaches a subscriber pipe +
     * reconfigured encoder whenever the capture restarted or the audio
     * config drifted — the [EncodedStreamPolicy.shouldAttachAudio] verdict.
     */
    private fun ensureAudioLocked() {
        if (!audioWanted()) {
            stopAudioLocked()
            return
        }
        if (!audio.isRunning()) {
            audio.start(audioConfig())
        }
        val formatMatches = configuredSampleRateHz == audio.getSampleRateHz() &&
            configuredChannelCount == audio.getChannelCount() &&
            configuredAudioBitrateKbps == audioBitrateKbps()
        if (!EncodedStreamPolicy.shouldAttachAudio(
                aacRunning = aacEncoder.isRunning(),
                captureRunning = audio.isRunning(),
                formatMatches = formatMatches,
            )
        ) {
            return
        }
        stopAudioLocked()
        val stream = audio.openStream() ?: return
        audioStream = stream
        aacEncoder.setAudioStream(stream)
        configuredSampleRateHz = audio.getSampleRateHz()
        configuredChannelCount = audio.getChannelCount()
        configuredAudioBitrateKbps = audioBitrateKbps()
        aacEncoder.configure(configuredSampleRateHz, configuredChannelCount, configuredAudioBitrateKbps)
        if (!aacEncoder.start()) {
            Log.w(TAG, "AAC encoder failed to start; encoded audio stays idle")
            stopAudioLocked()
            return
        }
        Log.d(TAG, "AAC encoder attached: ${configuredSampleRateHz}Hz, ${configuredChannelCount}ch")
    }

    private fun stopAudioLocked() {
        aacEncoder.stop()
        audioStream?.close()
        audioStream = null
    }

    // ── frames: the encode path ──

    /**
     * One camera frame into the pipeline. Re-evaluates the policy at a
     * bounded rate, so a sink becoming active (a WS video client joining,
     * an output starting) spins the encoders up within
     * [FRAME_PATH_REFRESH_INTERVAL_MS] — and lets them wind down when the
     * last sink goes away.
     */
    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        val now = System.currentTimeMillis()
        if (now - lastFramePathRefreshMs >= FRAME_PATH_REFRESH_INTERVAL_MS) {
            lastFramePathRefreshMs = now
            refresh()
        }
        if (!running.get()) return

        if (!frameThrottle.accept(now)) {
            droppedFrames.incrementAndGet()
            return
        }

        val encoder = activeEncoder ?: return
        if (encoder.isEncoderLagged()) {
            droppedFrames.incrementAndGet()
            return
        }

        val effectiveWidth: Int
        val effectiveHeight: Int
        var frameData: ByteArray

        if (rotation == 90 || rotation == 270) {
            effectiveWidth = height
            effectiveHeight = width
            frameData = YuvConverter.rotateNv21(yuvData, width, height, rotation)
        } else if (rotation == 180) {
            effectiveWidth = width
            effectiveHeight = height
            frameData = YuvConverter.rotateNv21(yuvData, width, height, rotation)
        } else {
            effectiveWidth = width
            effectiveHeight = height
            frameData = yuvData
        }

        if (effectiveWidth != videoWidth || effectiveHeight != videoHeight) {
            // The hub encodes the CONFIGURED size, whatever the camera's
            // analysis resolution is: the frame is aspect-filled (scaled to
            // cover, center-cropped) onto videoWidth x videoHeight. A
            // resolution-setting change therefore lands on the very next
            // frame, and a preview-resolution change never rewrites the RTSP
            // output's own size — the reconfigure is reserved for input
            // format and codec changes.
            frameData = YuvConverter.scaleNv21(frameData, effectiveWidth, effectiveHeight, videoWidth, videoHeight)
        }

        acceptedFrames.incrementAndGet()
        encoder.encodeFrame(frameData)
    }

    /** Live frame-rate change: throttle interval now, encoder at its next (re)configure. */
    fun setFrameRate(fps: Int) {
        frameRate = FrameTiming.effectiveFps(fps)
    }

    /** Live bitrate change: MediaCodec `setParameters` now, retained for the next (re)configure. */
    fun setVideoBitrate(bitrate: Int) {
        videoBitrate = bitrate.coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        if (running.get()) {
            activeEncoder?.setBitrate(videoBitrate)
        }
    }

    /** The live encoder target bitrate — the adaptive controller's current value, for surfaces that advertise it (ONVIF profiles). */
    fun currentVideoBitrate(): Int = videoBitrate

    /**
     * The persisted RTSP resolution lands on the hub's retained dimensions —
     * the size every incoming frame is aspect-filled onto ([YuvConverter.scaleNv21])
     * and the encoder is configured at, independent of the camera's analysis
     * resolution. While stopped this is simply the next `configure`; while
     * running the very next pushed frame already carries the new size.
     * MediaCodec has no live `setParameters` for dimensions, which is why the
     * RTSP output still classifies a resolution change as NEEDS_RESTART.
     */
    fun setVideoResolution(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    /** A new input format reconfigures the running encoder — hot-swap with reconfigure. */
    fun setInputFormat(format: RtspInputFormat) {
        if (format == inputFormat) return
        inputFormat = format
        synchronized(stateLock) {
            if (running.get()) {
                reconfigureEncoderLocked(videoWidth, videoHeight)
            }
        }
    }

    /**
     * A codec change on a live pipeline reconfigures it: stop the current
     * encoder, select (lazily creating) the new codec's encoder, configure,
     * start, and kick it with a black frame for fresh CSD — the full
     * [RtspVideoCodec] swap, since nothing about the encode or wire path
     * hot-swaps across codecs. While stopped the value is simply retained
     * for the next start. A same-codec call is a no-op, so a settings
     * re-emission never churns a live pipeline.
     */
    fun setVideoCodec(codec: RtspVideoCodec) {
        if (codec == activeVideoCodec) return
        // The flip lands under the same lock as the reconfigure, so the old
        // encoder's stop and the field other readers see are ordered. An AU
        // drained mid-swap still carries its PRODUCING codec (the tagged
        // fan-out in [encoderForLocked]), never the live field's.
        synchronized(stateLock) {
            activeVideoCodec = codec
            if (running.get()) {
                reconfigureEncoderLocked(videoWidth, videoHeight)
            }
        }
    }

    /**
     * The dimension/input-format/codec reconfigure, ported from the RTSP
     * server's frame path: stop → configure → start, one retry, black frame
     * for CSD. Selects the encoder for the CURRENT codec, so a codec change
     * lands here too. A permanent failure leaves the encoder stopped;
     * encodeFrame no-ops safely until a later reconfigure succeeds.
     */
    private fun reconfigureEncoderLocked(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        activeEncoder?.stop()
        val encoder = encoderForLocked(activeVideoCodec)
        encoder.configure(width, height, videoBitrate, frameRate)
        encoder.setInputFormat(inputFormat)
        if (!encoder.start()) {
            Log.e(TAG, "Encoder restart at ${width}x${height} failed; retrying once")
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
            if (!encoder.start()) {
                Log.e(TAG, "Encoder restart at ${width}x${height} failed permanently; frames skipped until next reconfigure")
                return
            }
        }
        activeEncoder = encoder
        encoder.submitBlackFrame()
        Log.d(TAG, "Encoder reconfigured to ${width}x${height} (${activeVideoCodec.wireName})")
    }

    // ── fan-out ──

    /**
     * The video fan-out. [producingCodec] is the codec that ENCODED these AUs,
     * not the live configured codec — during a codec swap an in-flight drain
     * must take the route of the encoder that produced it, or HEVC AUs would
     * land in the H.264-only HLS muxer / WebCodecs path.
     */
    private fun fanOutVideo(producingCodec: RtspVideoCodec, nalUnits: List<EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return
        fanOut("RTSP video") { rtspSink.feedVideo(nalUnits) }
        // HLS stays H.264-only: the TS muxer has no HEVC mapping, so H.265 AUs would corrupt segments.
        // WS video stays H.264-only too: the WebCodecs decode path has no HEVC configuration yet.
        if (producingCodec != RtspVideoCodec.H265) {
            fanOut("HLS video") { hlsSink.feedVideo(nalUnits) }
            fanOut("WS video") { wsVideoSink(nalUnits) }
        }
    }

    private fun fanOutAudio(aacData: ByteArray) {
        if (aacData.isEmpty()) return
        fanOut("RTSP audio") { rtspSink.feedAudio(aacData) }
        fanOut("HLS audio") { hlsSink.feedAudio(aacData) }
    }

    /** One isolated sink delivery: a broken consumer must never starve the others. */
    private inline fun fanOut(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$label fan-out failed", e)
        }
    }

    // ── EncodedSource: the RTSP server's SDP/keyframe seam ──
    //
    // sps/pps are the ACTIVE codec's SPS/PPS; vps is non-null only under
    // H.265, so the H.264 SDP path can never observe a stale VPS. Before the
    // first start (no encoder instantiated) everything answers null and
    // requestKeyFrame no-ops — the same shape as a started-but-CSD-less
    // encoder.

    override val sps: ByteArray?
        get() = activeEncoder?.sps

    override val pps: ByteArray?
        get() = activeEncoder?.pps

    override val vps: ByteArray?
        get() = activeEncoder?.vps

    override val videoCodec: RtspVideoCodec
        get() = activeVideoCodec

    override val audioSpecificConfig: ByteArray?
        get() = aacEncoder.audioSpecificConfig

    override fun requestKeyFrame() {
        activeEncoder?.requestKeyFrame()
    }

    companion object {
        private const val TAG = "EncodedStreamHub"

        /** Minimum spacing between frame-path policy evaluations. */
        private const val FRAME_PATH_REFRESH_INTERVAL_MS = 500L
    }
}
