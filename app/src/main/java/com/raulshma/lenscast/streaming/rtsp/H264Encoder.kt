package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class H264Encoder {

    private var encoder: MediaCodec? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)

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
    // the pure assembler; this class keeps the MediaCodec lifecycle.
    private val streamAssembler = H264StreamAssembler()

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

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        encoder = null
        pendingFrames.set(0)
        droppedFrames = 0

        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val selected = EncoderFormatPolicy.choose(capabilities.colorFormats.toSet(), preferredInputFormat)
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
                        if (capabilities.isFeatureSupported(
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

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            outputThread = Thread({ drainOutput(codec) }, "H264EncoderOutput").apply {
                isDaemon = true
                start()
            }

            Log.d(
                TAG,
                "H264 encoder started: ${width}x${height} @ ${frameRate}fps, ${bitrate}bps, colorFormat=$inputColorFormat, requestedInput=$preferredInputFormat, activeInput=$activeInputFormat"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264 encoder", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        try {
            outputThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        outputThread = null

        try {
            encoder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        encoder = null
        pendingFrames.set(0)

        Log.d(TAG, "H264 encoder stopped (dropped $droppedFrames frames)")
    }

    fun isEncoderLagged(): Boolean {
        return pendingFrames.get() >= MAX_PENDING_FRAMES
    }

    fun setBitrate(newBitrate: Int) {
        bitrate = newBitrate.coerceIn(StreamDefaults.VIDEO_BITRATE_MIN, StreamDefaults.VIDEO_BITRATE_MAX)
        try {
            encoder?.let { codec ->
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
            val codec = encoder ?: return
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
        val codec = encoder ?: return
        if (!running.get()) return

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

    private fun drainOutput(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            streamAssembler.updateFromConfigBuffer(
                                outputBuffer,
                                bufferInfo.offset,
                                bufferInfo.size,
                            )
                        }

                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)

                            val isKeyFrame = isKeyFrame(bufferInfo.flags)
                            val nalUnits = extractNalUnits(data)

                            if (nalUnits.isEmpty()) {
                                codec.releaseOutputBuffer(outputBufferIndex, false)
                                pendingFrames.decrementAndGet()
                                continue
                            }

                            onEncodedFrame?.invoke(streamAssembler.assemble(nalUnits, isKeyFrame))
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        pendingFrames.decrementAndGet()
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
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
                }
            } catch (e: IllegalStateException) {
                if (running.get()) Log.e(TAG, "Encoder output error", e)
                break
            } catch (_: Exception) {
                break
            }
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
