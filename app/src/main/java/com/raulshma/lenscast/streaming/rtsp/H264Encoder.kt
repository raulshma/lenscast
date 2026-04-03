package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class H264Encoder {

    private var encoder: MediaCodec? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var width = 1280
    private var height = 720
    private var bitrate = 2_000_000
    private var frameRate = 24
    private var preferredInputFormat = RtspInputFormat.AUTO
    private var activeInputFormat = RtspInputFormat.NV12
    private var inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private val pendingFrames = AtomicInteger(0)
    @Volatile
    private var droppedFrames = 0

    @Volatile
    var onEncodedFrame: ((List<EncodedNalUnit>) -> Unit)? = null

    data class EncodedNalUnit(val data: ByteArray, val isKeyFrame: Boolean)

    @Volatile
    var sps: ByteArray? = null
        private set

    @Volatile
    var pps: ByteArray? = null
        private set

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
        pendingFrames.set(0)
        droppedFrames = 0

        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val selected = chooseInputColorFormat(capabilities, preferredInputFormat)
            inputColorFormat = selected.first
            activeInputFormat = selected.second

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, inputColorFormat)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
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

    fun isRunning(): Boolean = running.get()

    fun isEncoderLagged(): Boolean {
        return pendingFrames.get() >= MAX_PENDING_FRAMES
    }

    fun setBitrate(newBitrate: Int) {
        bitrate = newBitrate.coerceIn(500_000, 8_000_000)
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
                            extractSpsAndPps(outputBuffer, bufferInfo)
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

                            if (isKeyFrame) {
                                val spsData = sps
                                val ppsData = pps
                                if (spsData != null && ppsData != null) {
                                    val allNals = mutableListOf<EncodedNalUnit>(
                                        EncodedNalUnit(spsData, false),
                                        EncodedNalUnit(ppsData, false)
                                    )
                                    allNals.addAll(nalUnits.map { EncodedNalUnit(it, true) })
                                    onEncodedFrame?.invoke(allNals)
                                } else {
                                    onEncodedFrame?.invoke(nalUnits.map { EncodedNalUnit(it, true) })
                                }
                            } else {
                                onEncodedFrame?.invoke(nalUnits.map { EncodedNalUnit(it, false) })
                            }
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        pendingFrames.decrementAndGet()
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        Log.d(TAG, "Encoder format changed: $format")
                        try {
                            format.getByteBuffer("csd-0")?.let { buf ->
                                sps = extractNalFromCsd(buf)
                            }
                            format.getByteBuffer("csd-1")?.let { buf ->
                                pps = extractNalFromCsd(buf)
                            }
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

    private fun extractNalUnits(data: ByteArray): List<ByteArray> {
        val annexB = extractAnnexBNalUnits(data)
        if (annexB.isNotEmpty()) return annexB

        val avcc = extractAvccNalUnits(data)
        if (avcc.isNotEmpty()) return avcc

        return emptyList()
    }

    private fun extractSpsAndPps(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data)

        val nalUnits = extractNalUnits(data)
        for (nalUnit in nalUnits) {
            if (nalUnit.isEmpty()) continue
            val nalType = nalUnit[0].toInt() and 0x1F
            when (nalType) {
                7 -> sps = nalUnit
                8 -> pps = nalUnit
            }
        }
    }

    private fun extractAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < data.size) {
            val startCodeLen = when {
                offset + 3 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 0.toByte() &&
                    data[offset + 3] == 1.toByte() -> 4

                offset + 2 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 1.toByte() -> 3

                else -> break
            }

            val nalStart = offset + startCodeLen
            var nalEnd = data.size
            var scan = nalStart
            while (scan + 2 < data.size) {
                if (data[scan] == 0.toByte() && data[scan + 1] == 0.toByte()) {
                    if (data[scan + 2] == 1.toByte()) {
                        nalEnd = scan
                        break
                    }
                    if (scan + 3 < data.size && data[scan + 2] == 0.toByte() && data[scan + 3] == 1.toByte()) {
                        nalEnd = scan
                        break
                    }
                }
                scan++
            }

            if (nalStart < nalEnd) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }

            offset = nalEnd
        }

        return result
    }

    private fun extractAvccNalUnits(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + 4 <= data.size) {
            val nalSize =
                ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            offset += 4

            if (nalSize <= 0 || offset + nalSize > data.size) {
                return emptyList()
            }

            result.add(data.copyOfRange(offset, offset + nalSize))
            offset += nalSize
        }

        return if (offset == data.size) result else emptyList()
    }

    private fun extractNalFromCsd(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var start = 0
        while (start < bytes.size && bytes[start] == 0.toByte()) start++
        if (start < bytes.size && bytes[start] == 1.toByte()) start++
        return if (start < bytes.size) bytes.copyOfRange(start, bytes.size) else bytes
    }

    private fun chooseInputColorFormat(
        capabilities: MediaCodecInfo.CodecCapabilities,
        requestedInputFormat: RtspInputFormat,
    ): Pair<Int, RtspInputFormat> {
        val supported = capabilities.colorFormats.toSet()
        fun autoChoice(): Pair<Int, RtspInputFormat>? {
            val preferred = listOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                // Keep packed semi-planar as last resort. On some devices this format is
                // ambiguously implemented and may behave like NV12/NV21 inconsistently.
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
            )
            val color = preferred.firstOrNull { supported.contains(it) } ?: return null
            return color to mapColorFormatToInputFormat(color)
        }

        val requested = when (requestedInputFormat) {
            RtspInputFormat.AUTO -> autoChoice()
            RtspInputFormat.NV21 -> {
                val color = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
                if (supported.contains(color)) color to RtspInputFormat.NV21 else null
            }
            RtspInputFormat.NV12 -> {
                val color = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                if (supported.contains(color)) color to RtspInputFormat.NV12 else null
            }
            RtspInputFormat.I420 -> {
                val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                when {
                    supported.contains(planar) -> planar to RtspInputFormat.I420
                    supported.contains(flexible) -> flexible to RtspInputFormat.I420
                    else -> null
                }
            }
        }

        val selected = requested ?: autoChoice()
        if (selected != null) {
            if (requested == null && requestedInputFormat != RtspInputFormat.AUTO) {
                Log.w(
                    TAG,
                    "Requested input format $requestedInputFormat is not supported by codec. Falling back to ${selected.second}."
                )
            }
            return selected
        }

        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar to RtspInputFormat.NV12
    }

    private fun mapColorFormatToInputFormat(colorFormat: Int): RtspInputFormat {
        return when (colorFormat) {
            // Treat packed semi-planar as NV12 for compatibility. In practice this avoids
            // frequent magenta/green tint issues seen when assuming strict NV21 ordering.
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> RtspInputFormat.NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> RtspInputFormat.NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> RtspInputFormat.I420
            else -> RtspInputFormat.NV12
        }
    }

    private fun convertInputFrame(nv21: ByteArray, width: Int, height: Int): ByteArray {
        return when (activeInputFormat) {
            RtspInputFormat.NV21 -> nv21
            RtspInputFormat.NV12 -> nv21ToNv12(nv21, width, height)
            RtspInputFormat.I420 -> nv21ToI420(nv21, width, height)
            RtspInputFormat.AUTO -> nv21ToNv12(nv21, width, height)
        }
    }

    private fun nv21ToNv12(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val nv12 = nv21.copyOf()

        val uvStart = ySize
        for (i in 0 until width * height / 2 step 2) {
            val v = nv21[uvStart + i]
            val u = nv21[uvStart + i + 1]
            nv12[uvStart + i] = u
            nv12[uvStart + i + 1] = v
        }

        return nv12
    }

    private fun nv21ToI420(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvPlaneSize = ySize / 4
        val i420 = ByteArray(ySize + uvPlaneSize * 2)

        // Y plane
        System.arraycopy(nv21, 0, i420, 0, ySize)

        // U and V planar
        var src = ySize
        var uDst = ySize
        var vDst = ySize + uvPlaneSize
        while (src + 1 < nv21.size) {
            val v = nv21[src]
            val u = nv21[src + 1]
            i420[uDst++] = u
            i420[vDst++] = v
            src += 2
        }

        return i420
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val MAX_PENDING_FRAMES = 2
    }
}
