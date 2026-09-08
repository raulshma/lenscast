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

@Suppress("DEPRECATION")
class H264Encoder {

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
    var onEncodedFrame: ((List<EncodedNalUnit>) -> Unit)? = null

    data class EncodedNalUnit(val data: ByteArray, val isKeyFrame: Boolean)

    // The SPS/PPS state machine and the keyframe prepend decision live in
    // the pure assembler, and the MediaCodec lifecycle lives in the shared
    // harness. This class keeps the H.264 format knowledge and CSD
    // interpretation, plugged into the harness below.
    private val streamAssembler = H264StreamAssembler()

    private val harness = MediaCodecEncoderHarness(
        tag = TAG,
        threadName = "H264EncoderOutput",
        startedMessage = {
            "H264 encoder started: ${width}x${height} @ ${frameRate}fps, ${bitrate}bps, colorFormat=$inputColorFormat, requestedInput=$preferredInputFormat, activeInput=$activeInputFormat"
        },
        startFailureMessage = "Failed to start H264 encoder",
        outputErrorMessage = "Encoder output error",
        createCodec = {
            pendingFrames.set(0)
            droppedFrames = 0
            MediaCodecAdapter(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC))
        },
        configureCodec = { configureCodec(it) },
        onFormatChanged = { onFormatChanged(it) },
        onOutput = { outputBuffer, info -> onOutputBuffer(outputBuffer, info) },
    )

    val sps: ByteArray?
        get() = streamAssembler.sps

    val pps: ByteArray?
        get() = streamAssembler.pps

    fun configure(width: Int, height: Int, bitrate: Int, frameRate: Int) {
        this.width = width
        this.height = height
        this.bitrate = bitrate
        this.frameRate = frameRate
    }

    fun setInputFormat(format: RtspInputFormat) {
        preferredInputFormat = format
    }

    fun start(): Boolean = harness.start()

    fun stop() {
        if (!harness.stop()) return
        pendingFrames.set(0)

        Log.d(TAG, "H264 encoder stopped (dropped $droppedFrames frames)")
    }

    fun isEncoderLagged(): Boolean {
        return pendingFrames.get() >= MAX_PENDING_FRAMES
    }

    fun setBitrate(newBitrate: Int) {
        bitrate = newBitrate.coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        try {
            harness.activeCodec?.let { codec ->
                val params = android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                }
                codec.setParameters(params)
            }
            Log.d(TAG, "Bitrate adjusted to $bitrate")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust bitrate", e)
        }
    }

    fun requestKeyFrame() {
        try {
            val codec = harness.activeCodec ?: return
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request key frame", e)
        }
    }

    /**
     * Queues a black frame so a freshly started codec emits its CSD (SPS/PPS)
     * immediately. Lets the RTSP server advertise real sprop-parameter-sets in
     * the SDP before the first client reaches PLAY, instead of an empty fmtp.
     */
    fun submitBlackFrame() {
        val ySize = width * height
        val black = ByteArray(ySize * 3 / 2)
        java.util.Arrays.fill(black, 0, ySize, 16.toByte())
        java.util.Arrays.fill(black, ySize, black.size, 128.toByte())
        encodeFrame(black)
    }

    fun encodeFrame(nv21Data: ByteArray) {
        val codec = harness.activeCodec ?: return
        if (!harness.isRunning) return

        val pending = pendingFrames.getAndIncrement()
        if (pending >= MAX_PENDING_FRAMES) {
            pendingFrames.decrementAndGet()
            droppedFrames++
            if (droppedFrames % 30 == 0) {
                Log.w(TAG, "Dropped $droppedFrames total frames (encoder lagged)")
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
                Log.w(TAG, "Frame data (${frameData.size}) exceeds input buffer capacity (${inputBuffer.capacity()})")
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
            Log.e(TAG, "Encode frame failed", e)
        }
    }

    /**
     * The H.264 configuration between the harness's create and start steps:
     * capability-driven color-format selection via [EncoderFormatPolicy] and
     * the video MediaFormat, applied through the [CodecLike] seam.
     */
    private fun configureCodec(codec: CodecLike) {
        val selected = EncoderFormatPolicy.choose(
            codec.supportedColorFormats(MediaFormat.MIMETYPE_VIDEO_AVC),
            preferredInputFormat,
        )
        inputColorFormat = selected.colorFormat
        activeInputFormat = selected.effectiveInputFormat
        if (selected.fellBackToAuto) {
            Log.w(
                TAG,
                "Requested input format $preferredInputFormat is not supported by codec. Falling back to ${selected.effectiveInputFormat}."
            )
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, inputColorFormat)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (codec.isFeatureSupported(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                        )
                    ) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } else {
                        Log.d(TAG, "Encoder does not support low-latency; configuring without it")
                    }
                } catch (_: Exception) {
                    // Capability query failed: leave KEY_LOW_LATENCY unset rather than
                    // risk configure() rejecting the format (legacy OMX encoders return
                    // BAD_VALUE for unsupported keys instead of ignoring them).
                }
            }
            try {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            } catch (_: Exception) {
            }
        }

        codec.configureEncode(format)
    }

    /**
     * The H.264 half of the harness's output contract: codec-config buffers
     * feed the assembler's CSD state, encoded frames are split into NAL units
     * and assembled before the [onEncodedFrame] callback.
     */
    private fun onOutputBuffer(outputBuffer: ByteBuffer, info: MediaCodecEncoderHarness.OutputInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            streamAssembler.updateFromConfigBuffer(
                outputBuffer,
                info.offset,
                info.size,
            )
        }

        if (info.size > 0 &&
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        ) {
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data)

            val nalUnits = extractNalUnits(data)

            if (nalUnits.isEmpty()) {
                pendingFrames.decrementAndGet()
                return
            }

            onEncodedFrame?.invoke(streamAssembler.assemble(nalUnits, isKeyFrame(info.flags)))
        }

        pendingFrames.decrementAndGet()
    }

    private fun onFormatChanged(codec: CodecLike) {
        val format = codec.outputFormat()
        Log.d(TAG, "Encoder format changed: $format")
        try {
            streamAssembler.updateFromFormat(
                csd0 = format.getByteBuffer("csd-0"),
                csd1 = format.getByteBuffer("csd-1"),
            )
            Log.d(TAG, "SPS/PPS extracted from format: sps=${sps?.size} pps=${pps?.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SPS/PPS from format", e)
        }
    }

    private fun isKeyFrame(flags: Int): Boolean {
        return flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    }

    private fun extractNalUnits(data: ByteArray): List<ByteArray> = H264NalParser.extractNalUnits(data)

    private fun convertInputFrame(nv21: ByteArray, width: Int, height: Int): ByteArray {
        return when (activeInputFormat) {
            RtspInputFormat.NV21 -> nv21
            RtspInputFormat.NV12 -> YuvConverter.nv21ToNv12(nv21, width, height)
            RtspInputFormat.I420 -> YuvConverter.nv21ToI420(nv21, width, height)
            RtspInputFormat.AUTO -> YuvConverter.nv21ToNv12(nv21, width, height)
        }
    }

    companion object {
        private const val TAG = "H264Encoder"

        // Encoder output typically lags input by 1-2 buffers; 6 allows transient
        // hiccups (reconfigure, HW contention) without starving the stream to zero.
        private const val MAX_PENDING_FRAMES = 6
    }
}
