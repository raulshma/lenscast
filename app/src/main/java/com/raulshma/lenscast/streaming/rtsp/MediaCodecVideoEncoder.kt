package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The codec-neutral video encoder surface the encoded-stream hub drives:
 * exactly the members the frame path, the settings, and the lifecycle need,
 * behind one type so the hub stays codec-blind. [MediaCodecVideoEncoder]
 * implements it for both codecs, so no per-codec adapter exists.
 */
internal interface VideoEncoder {
    fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int)

    fun setInputFormat(format: RtspInputFormat)

    fun start(): Boolean

    fun stop()

    fun submitBlackFrame()

    fun encodeFrame(nv21Data: ByteArray)

    fun isEncoderLagged(): Boolean

    fun setBitrate(newBitrate: Int)

    fun requestKeyFrame()

    val sps: ByteArray?

    val pps: ByteArray?

    /** H.265-only third parameter set; always null on the H.264 side. */
    val vps: ByteArray?

    var onEncodedFrame: ((List<EncodedNalUnit>) -> Unit)?
}

/**
 * The one MediaCodec video-encoder body behind both codec siblings
 * ([H264Encoder], [H265Encoder]): the retained config, the pending-frame lag
 * gate, the bitrate/key-frame `setParameters` calls, the black-frame CSD
 * kick, the NV21 input feeding, and the video MediaFormat construction (over
 * [EncoderFormatPolicy]) — all riding the shared [MediaCodecEncoderHarness].
 * A subclass supplies exactly the codec knowledge: the MIME type, the CSD
 * interpretation through its stream assembler, and the NAL-level keyframe
 * check.
 *
 * Shared invariants (KEY_I_FRAME_INTERVAL 2, no intra-refresh period, the
 * latency-keys retry, MAX_PENDING_FRAMES) live here once — an intra-refresh
 * encoder stops emitting full IDR keyframes, and a stream with no IDR can
 * never be joined mid-way; recovery rides the periodic IDRs plus PLAY's
 * request-sync-frame.
 */
internal abstract class MediaCodecVideoEncoder(
    /** Short display name in logs and thread names, e.g. "H264". */
    private val encoderName: String,
    /** The MediaCodec MIME type this encoder creates its codec for. */
    private val mimeType: String,
) : VideoEncoder {

    private var width = StreamDefaults.RTSP_VIDEO_WIDTH
    private var height = StreamDefaults.RTSP_VIDEO_HEIGHT
    private var bitrate = StreamDefaults.RTSP_VIDEO_BITRATE
    private var frameRate = StreamDefaults.STREAM_FPS
    private var preferredInputFormat = RtspInputFormat.AUTO
    private var activeInputFormat = RtspInputFormat.NV12
    private var inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private val pendingFrames = AtomicInteger(0)
    @Volatile
    private var droppedFrames = 0

    @Volatile
    override var onEncodedFrame: ((List<EncodedNalUnit>) -> Unit)? = null

    private val harness = MediaCodecEncoderHarness(
        tag = tag,
        threadName = "${encoderName}EncoderOutput",
        startedMessage = {
            "$encoderName encoder started: ${width}x${height} @ ${frameRate}fps, ${bitrate}bps, colorFormat=$inputColorFormat, requestedInput=$preferredInputFormat, activeInput=$activeInputFormat"
        },
        startFailureMessage = "Failed to start $encoderName encoder",
        outputErrorMessage = "Encoder output error",
        createCodec = {
            pendingFrames.set(0)
            droppedFrames = 0
            MediaCodecAdapter(MediaCodec.createEncoderByType(mimeType))
        },
        configureCodec = { configureCodec(it) },
        onFormatChanged = { learnCsdFromFormat(it.outputFormat()) },
        onOutput = { outputBuffer, info -> onOutputBuffer(outputBuffer, info) },
    )

    private val tag: String get() = "${encoderName}Encoder"

    /** The latest SPS as sent on the wire, from the subclass's assembler. */
    abstract override val sps: ByteArray?

    /** The latest PPS as sent on the wire, from the subclass's assembler. */
    abstract override val pps: ByteArray?

    /** HEVC-only; the base answer is null so the H.264 side never carries one. */
    override val vps: ByteArray?
        get() = null

    override fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int) {
        this.width = width
        this.height = height
        this.bitrate = bitrate
        this.frameRate = frameRate
    }

    override fun setInputFormat(format: RtspInputFormat) {
        preferredInputFormat = format
    }

    override fun start(): Boolean = harness.start()

    override fun stop() {
        if (!harness.stop()) return
        pendingFrames.set(0)

        Log.d(tag, "$encoderName encoder stopped (dropped $droppedFrames frames)")
    }

    override fun isEncoderLagged(): Boolean {
        return pendingFrames.get() >= MAX_PENDING_FRAMES
    }

    override fun setBitrate(newBitrate: Int) {
        bitrate = newBitrate.coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        try {
            harness.activeCodec?.let { codec ->
                val params = android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                }
                codec.setParameters(params)
            }
            Log.d(tag, "Bitrate adjusted to $bitrate")
        } catch (e: Exception) {
            Log.w(tag, "Failed to adjust bitrate", e)
        }
    }

    override fun requestKeyFrame() {
        try {
            val codec = harness.activeCodec ?: return
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: Exception) {
            Log.w(tag, "Failed to request key frame", e)
        }
    }

    /**
     * Queues a black frame so a freshly started codec emits its CSD
     * immediately — real parameter sets in the SDP before the first client
     * reaches PLAY, instead of an empty or missing fmtp line.
     */
    override fun submitBlackFrame() {
        val ySize = width * height
        val black = ByteArray(ySize * 3 / 2)
        java.util.Arrays.fill(black, 0, ySize, 16.toByte())
        java.util.Arrays.fill(black, ySize, black.size, 128.toByte())
        encodeFrame(black)
    }

    override fun encodeFrame(nv21Data: ByteArray) {
        val codec = harness.activeCodec ?: return
        if (!harness.isRunning) return

        val pending = pendingFrames.getAndIncrement()
        if (pending >= MAX_PENDING_FRAMES) {
            pendingFrames.decrementAndGet()
            droppedFrames++
            if (droppedFrames % 30 == 0) {
                Log.w(tag, "Dropped $droppedFrames total frames (encoder lagged)")
            }
            return
        }

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(5_000)
            if (inputBufferIndex < 0) {
                pendingFrames.decrementAndGet()
                return
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: run {
                pendingFrames.decrementAndGet()
                return
            }
            val frameData = convertInputFrame(nv21Data, width, height)
            if (frameData.size > inputBuffer.capacity()) {
                pendingFrames.decrementAndGet()
                Log.w(tag, "Frame data (${frameData.size}) exceeds input buffer capacity (${inputBuffer.capacity()})")
                return
            }
            inputBuffer.clear()
            inputBuffer.put(frameData)

            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                frameData.size,
                System.nanoTime() / 1000,
                0
            )
        } catch (e: Exception) {
            pendingFrames.decrementAndGet()
            Log.e(tag, "Encode frame failed", e)
        }
    }

    /**
     * The configuration between the harness's create and start steps:
     * capability-driven color-format selection via [EncoderFormatPolicy] and
     * the video MediaFormat, applied through the [CodecLike] seam.
     */
    private fun configureCodec(codec: CodecLike) {
        val selected = EncoderFormatPolicy.choose(
            codec.supportedColorFormats(mimeType),
            preferredInputFormat,
        )
        inputColorFormat = selected.colorFormat
        activeInputFormat = selected.effectiveInputFormat
        if (selected.fellBackToAuto) {
            Log.w(
                tag,
                "Requested input format $preferredInputFormat is not supported by codec. Falling back to ${selected.effectiveInputFormat}."
            )
        }

        // The optional latency keys are public (API 24) but a minority of
        // legacy encoders reject them outright at configure() — retry once
        // without them rather than losing the encoder.
        try {
            codec.configureEncode(buildEncodeFormat(codec, selected, includeLatencyKeys = true))
        } catch (e: Exception) {
            Log.w(tag, "Encoder rejected the latency keys; reconfiguring without them", e)
            codec.configureEncode(buildEncodeFormat(codec, selected, includeLatencyKeys = false))
        }
    }

    private fun buildEncodeFormat(
        codec: CodecLike,
        selected: EncoderFormatPolicy.SelectedFormat,
        includeLatencyKeys: Boolean,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            mimeType, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, selected.colorFormat)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (includeLatencyKeys) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        if (codec.isFeatureSupported(
                                mimeType,
                                MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                            )
                        ) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        } else {
                            Log.d(tag, "Encoder does not support low-latency; configuring without it")
                        }
                    } catch (_: Exception) {
                        // Capability query failed: leave KEY_LOW_LATENCY unset rather than
                        // risk configure() rejecting the format (legacy OMX encoders return
                        // BAD_VALUE for unsupported keys instead of ignoring them).
                    }
                }
                // Public keys (API 23/24, minSdk 26): bound the encoder's internal
                // reordering. KEY_INTRA_REFRESH_PERIOD must stay unset: an
                // intra-refresh encoder stops emitting full IDR keyframes (it
                // sweeps intra columns across the period instead), and a stream
                // with no IDR can never be joined mid-way — FFmpeg/VLC report
                // "SPS/PPS received but no video frames". Recovery must ride
                // the periodic IDRs from KEY_I_FRAME_INTERVAL + PLAY's
                // request-sync-frame, not intra refresh.
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                } catch (_: Exception) {
                }
            }
            try {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            } catch (_: Exception) {
            }
        }
        return format
    }

    /**
     * The output contract: codec-config buffers feed the assembler's CSD
     * state, encoded frames are split into NAL units and assembled before the
     * [onEncodedFrame] callback.
     */
    private fun onOutputBuffer(outputBuffer: ByteBuffer, info: MediaCodecEncoderHarness.OutputInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            updateCsdFromConfigBuffer(outputBuffer, info.offset, info.size)
        }

        if (info.size > 0 &&
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        ) {
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data)

            // The start-code/length-prefix framing scan is codec-agnostic —
            // Annex-B framing is identical in H.264 and H.265, and
            // H265NalParser re-exports these same scanners instead of
            // duplicating them.
            val nalUnits = H264NalParser.extractNalUnits(data)

            if (nalUnits.isEmpty()) {
                pendingFrames.decrementAndGet()
                return
            }

            onEncodedFrame?.invoke(
                assembleFrame(nalUnits, isKeyFrame(info.flags) || containsKeyframeNal(nalUnits))
            )
        }

        pendingFrames.decrementAndGet()
    }

    // BUFFER_FLAG_KEY_FRAME alone is not reliable: several vendor encoders
    // never set it on IDR access units. The NAL-level keyframe check in the
    // codec's parser is the authoritative signal; the flag is the fast path.
    private fun isKeyFrame(flags: Int): Boolean {
        return flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    private fun convertInputFrame(nv21: ByteArray, width: Int, height: Int): ByteArray {
        return when (activeInputFormat) {
            RtspInputFormat.NV21 -> nv21
            RtspInputFormat.NV12 -> YuvConverter.nv21ToNv12(nv21, width, height)
            RtspInputFormat.I420 -> YuvConverter.nv21ToI420(nv21, width, height)
            RtspInputFormat.AUTO -> YuvConverter.nv21ToNv12(nv21, width, height)
        }
    }

    /** Feeds a codec-config output buffer into the subclass assembler's CSD state. */
    protected abstract fun updateCsdFromConfigBuffer(buffer: ByteBuffer, offset: Int, size: Int)

    /** The wire NAL list for one encoded output buffer, via the subclass assembler. */
    protected abstract fun assembleFrame(nalUnits: List<ByteArray>, isKeyFrame: Boolean): List<EncodedNalUnit>

    /** Learns the CSD from an `INFO_OUTPUT_FORMAT_CHANGED` media format. */
    protected abstract fun learnCsdFromFormat(format: MediaFormat)

    /** The NAL-level keyframe verdict over one access unit's NAL units. */
    protected abstract fun containsKeyframeNal(nalUnits: List<ByteArray>): Boolean

    companion object {
        // Encoder output typically lags input by 1-2 buffers; 6 allows transient
        // hiccups (reconfigure, HW contention) without starving the stream to zero.
        private const val MAX_PENDING_FRAMES = 6
    }
}
