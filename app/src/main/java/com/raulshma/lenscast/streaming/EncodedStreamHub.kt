package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.streaming.hls.HlsVideoSink
import com.raulshma.lenscast.streaming.rtsp.AacEncoder
import com.raulshma.lenscast.streaming.rtsp.H264Encoder
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The parameter-set seam the RTSP server consumes for its SDP and PLAY
 * keyframe requests: the live encoder state of the shared encode pipeline.
 * [EncodedStreamHub] implements it in production; JVM tests substitute fakes.
 */
interface EncodedSource {
    val sps: ByteArray?

    val pps: ByteArray?

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
    fun feedVideo(nalUnits: List<H264Encoder.EncodedNalUnit>)

    fun feedAudio(aacData: ByteArray)
}

/**
 * The shared H.264/AAC encode pipeline, decoupled from the RTSP server:
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
 * and the dimension-change encoder reconfigure.
 */
internal class EncodedStreamHub(
    private val policyInputs: () -> EncodedStreamPolicy.Inputs,
    private val audio: RtspAudioSource,
    private val audioConfig: () -> AudioStreamingManager.Config,
    private val audioWanted: () -> Boolean,
    private val audioBitrateKbps: () -> Int,
    private val rtspSink: EncodedSink,
    private val hlsSink: HlsVideoSink,
    private val wsVideoSink: (List<H264Encoder.EncodedNalUnit>) -> Unit,
) : EncodedSource {

    private val encoder = H264Encoder()
    private val aacEncoder = AacEncoder()

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
    @Volatile private var lastRotation = 0

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
        encoder.onEncodedFrame = { nalUnits -> fanOutVideo(nalUnits) }
        aacEncoder.onEncodedFrame = { aacData, _ -> fanOutAudio(aacData) }
    }

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
            encoder.configure(videoWidth, videoHeight, videoBitrate, frameRate)
            encoder.setInputFormat(inputFormat)
            if (!encoder.start()) {
                Log.e(TAG, "H.264 encoder failed to start; encoded sinks stay idle")
                return
            }
            // Force CSD emission so a freshly started pipeline advertises
            // real SPS/PPS to DESCRIBE and mid-join WS clients immediately.
            encoder.submitBlackFrame()
            running.set(true)
            acceptedFrames.set(0)
            droppedFrames.set(0)
            Log.d(TAG, "Encoded stream started (rtsp/hls/ws sinks attached)")
        }
        ensureAudioLocked()
    }

    private fun stopLocked() {
        if (running.getAndSet(false)) {
            encoder.stop()
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

        if (encoder.isEncoderLagged()) {
            droppedFrames.incrementAndGet()
            return
        }

        val effectiveWidth: Int
        val effectiveHeight: Int
        val frameData: ByteArray

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

        if (effectiveWidth != videoWidth || effectiveHeight != videoHeight || rotation != lastRotation) {
            synchronized(stateLock) {
                lastRotation = rotation
                reconfigureEncoderLocked(effectiveWidth, effectiveHeight)
            }
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
            encoder.setBitrate(videoBitrate)
        }
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
     * The dimension/input-format reconfigure, ported from the RTSP server's
     * frame path: stop → configure → start, one retry, black frame for CSD.
     * A permanent failure leaves the encoder stopped; encodeFrame no-ops
     * safely until a later reconfigure succeeds.
     */
    private fun reconfigureEncoderLocked(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        encoder.stop()
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
        encoder.submitBlackFrame()
        Log.d(TAG, "Encoder reconfigured to ${width}x${height}")
    }

    // ── fan-out ──

    private fun fanOutVideo(nalUnits: List<H264Encoder.EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return
        fanOut("RTSP video") { rtspSink.feedVideo(nalUnits) }
        fanOut("HLS video") { hlsSink.feedVideo(nalUnits) }
        fanOut("WS video") { wsVideoSink(nalUnits) }
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

    override val sps: ByteArray?
        get() = encoder.sps

    override val pps: ByteArray?
        get() = encoder.pps

    override val audioSpecificConfig: ByteArray?
        get() = aacEncoder.audioSpecificConfig

    override fun requestKeyFrame() {
        encoder.requestKeyFrame()
    }

    companion object {
        private const val TAG = "EncodedStreamHub"

        /** Minimum spacing between frame-path policy evaluations. */
        private const val FRAME_PATH_REFRESH_INTERVAL_MS = 500L
    }
}
