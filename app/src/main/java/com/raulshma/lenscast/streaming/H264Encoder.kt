package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream

class H264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val bitRate: Int = 2_000_000,
    private val frameRate: Int = 30,
) {
    private var mediaCodec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isInitialized = false

    fun initialize(): Boolean {
        return try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            }
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            false
        }
    }

    fun encodeFrame(inputBuffer: ByteArray): ByteArray? {
        if (!isInitialized) return null
        val codec = mediaCodec ?: return null

        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            codec.getInputBuffer(inputIndex)?.put(inputBuffer)
            codec.queueInputBuffer(
                inputIndex, 0, inputBuffer.size,
                System.nanoTime() / 1000, 0
            )
        }

        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
        if (outputIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                val data = ByteArray(bufferInfo.size)
                outputBuffer.get(data)
                codec.releaseOutputBuffer(outputIndex, false)
                return data
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
        return null
    }

    fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "H264Encoder"
    }
}
