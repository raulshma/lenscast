package com.raulshma.lenscast.capture

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Interval → video: sorted JPEGs in [inputDir] muxed to an H.264 MP4 at [fps].
 * Frame ordering ([orderedFrames]) is pure and JVM-tested; [assemble] does the
 * MediaCodec + MediaMuxer work on the caller's worker thread.
 */
object TimelapseAssembler {
    private const val TAG = "TimelapseAssembler"
    const val MIN_SOURCES = 10
    const val MIN_SELECT = 10
    const val MAX_SELECT = 500

    fun orderedFrames(files: List<File>): List<File> =
        files.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg") }
            .sortedBy { it.name }

    /**
     * The timelapse source verdict: newest-first photo history → oldest-first
     * assembly order, clamped to [MIN_SELECT]..[MAX_SELECT]. Pure so the
     * capture ViewModel renders the selection without re-rolling the ladder.
     */
    fun selectSources(
        history: List<com.raulshma.lenscast.capture.model.CaptureHistory>,
        lastN: Int,
    ): List<com.raulshma.lenscast.capture.model.CaptureHistory> =
        history
            .filter { it.type == com.raulshma.lenscast.capture.model.CaptureType.PHOTO }
            .sortedByDescending { it.timestamp }
            .take(lastN.coerceIn(MIN_SELECT, MAX_SELECT))
            .sortedBy { it.timestamp }

    fun assemble(inputDir: File, outputFile: File, fps: Int = 30, width: Int = 1280, height: Int = 720): Boolean {
        val frames = orderedFrames(inputDir.listFiles()?.toList() ?: emptyList())
        if (frames.isEmpty()) {
            Log.w(TAG, "No frames in $inputDir")
            return false
        }
        return try {
            encodeFrames(frames, outputFile, fps.coerceIn(1, 60), width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Timelapse assembly failed", e)
            false
        }
    }

    private fun encodeFrames(frames: List<File>, output: File, fps: Int, width: Int, height: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var muxer: MediaMuxer? = null
        var track = -1
        var started = false
        val frameDurationUs = 1_000_000L / fps
        try {
            var ptsUs = 0L
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            for (file in frames) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, width, height, true)
                if (scaled != bmp) bmp.recycle()
                val nv21 = bitmapToNv21(scaled, width, height)
                scaled.recycle()
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    // COLOR_FormatYUV420Flexible input layout varies; feed NV21 bytes directly —
                    // encoders accepting flexible YUV take the planar bytes in order.
                    buf.put(nv21)
                    codec.queueInputBuffer(inIdx, 0, nv21.size, ptsUs, 0)
                    ptsUs += frameDurationUs
                }
                drain(codec, bufferInfo, { muxer }, { m, idx ->
                    if (!started) {
                        track = m.addTrack(codec.outputFormat)
                        m.start()
                        started = true
                    }
                    m.writeSampleData(track, codec.getOutputBuffer(idx)!!, bufferInfo)
                }, lazyMuxer = {
                    muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer!!
                })
            }
            // EOS
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var eos = false
            var guard = 0
            while (!eos && guard++ < 100) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (!started) {
                            if (muxer == null) muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            track = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            started = true
                        }
                        if (bufferInfo.size > 0) muxer!!.writeSampleData(track, codec.getOutputBuffer(outIdx)!!, bufferInfo)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            if (muxer == null) muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            track = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            started = true
                        }
                    }
                    else -> Unit
                }
            }
            return started
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun drain(
        codec: MediaCodec,
        info: android.media.MediaCodec.BufferInfo,
        muxer: () -> MediaMuxer?,
        write: (MediaMuxer, Int) -> Unit,
        lazyMuxer: () -> MediaMuxer,
    ) {
        var idx = codec.dequeueOutputBuffer(info, 0)
        while (idx >= 0) {
            val m = muxer() ?: lazyMuxer()
            write(m, idx)
            codec.releaseOutputBuffer(idx, false)
            idx = codec.dequeueOutputBuffer(info, 0)
        }
    }

    internal fun bitmapToNv21(bmp: android.graphics.Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bmp.getPixels(argb, 0, width, 0, 0, width, height)
        val y = ByteArray(width * height)
        val vu = ByteArray(width * height / 2)
        var yIdx = 0
        var vuIdx = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                y[yIdx++] = yVal.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    vu[vuIdx++] = v.coerceIn(0, 255).toByte()
                    vu[vuIdx++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return y + vu
    }
}
