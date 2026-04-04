package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AacEncoder {

    private var encoder: MediaCodec? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private val running = AtomicBoolean(false)

    private var sampleRateHz = 48000
    private var channelCount = 1
    private var bitrate = 128_000

    @Volatile
    var audioSpecificConfig: ByteArray? = null
        private set

    @Volatile
    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    fun configure(sampleRateHz: Int, channelCount: Int, bitrateKbps: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount.coerceIn(1, 2)
        this.bitrate = (bitrateKbps * 1000).coerceIn(32_000, 320_000)
    }

    fun setAudioStream(stream: InputStream?) {
        audioStream = stream
    }

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        audioSpecificConfig = null

        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmFrameSizeBytes() * 2)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            // Extract AudioSpecificConfig from output format
            extractAudioSpecificConfig(codec)

            inputThread = Thread({ feedInput(codec) }, "AacEncoderInput").apply {
                isDaemon = true
                start()
            }

            outputThread = Thread({ drainOutput(codec) }, "AacEncoderOutput").apply {
                isDaemon = true
                start()
            }

            Log.d(TAG, "AAC encoder started: ${sampleRateHz}Hz, ${channelCount}ch, ${bitrate}bps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AAC encoder", e)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        audioStream = null

        try {
            inputThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        inputThread = null

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

        Log.d(TAG, "AAC encoder stopped")
    }

    fun isRunning(): Boolean = running.get()

    fun setBitrate(newBitrateKbps: Int) {
        bitrate = (newBitrateKbps * 1000).coerceIn(32_000, 320_000)
        // AAC encoder doesn't support dynamic bitrate changes via setParameters.
        // The new bitrate will take effect on next start().
        Log.d(TAG, "AAC bitrate set to ${bitrate}bps (effective on next start)")
    }

    private fun extractAudioSpecificConfig(codec: MediaCodec) {
        try {
            val format = codec.outputFormat
            val csd0 = format.getByteBuffer("csd-0")
            if (csd0 != null) {
                // csd-0 for AAC contains 2 bytes: AudioSpecificConfig
                val bytes = ByteArray(csd0.remaining())
                csd0.get(bytes)
                if (bytes.size >= 2) {
                    audioSpecificConfig = bytes.copyOfRange(bytes.size - 2, bytes.size)
                    Log.d(TAG, "AudioSpecificConfig from csd-0: ${bytes.toHexString()}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract AudioSpecificConfig from format, computing manually", e)
        }

        if (audioSpecificConfig == null) {
            audioSpecificConfig = computeAudioSpecificConfig()
            Log.d(TAG, "AudioSpecificConfig computed: ${audioSpecificConfig!!.toHexString()}")
        }
    }

    private fun computeAudioSpecificConfig(): ByteArray {
        val audioObjectType = 2 // AAC-LC
        val samplingFreqIndex = when (sampleRateHz) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 0xF // explicit frequency
        }
        val channelConfig = channelCount

        val byte0 = ((audioObjectType shl 3) or (samplingFreqIndex shr 1)) and 0xFF
        val byte1 = (((samplingFreqIndex and 0x1) shl 7) or (channelConfig shl 3)) and 0xFF
        return byteArrayOf(byte0.toByte(), byte1.toByte())
    }

    @Volatile
    private var audioStream: InputStream? = null

    private fun feedInput(codec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameSizeBytes = pcmFrameSizeBytes()
        val buffer = ByteArray(frameSizeBytes)

        while (running.get()) {
            val stream = audioStream ?: break

            try {
                var totalRead = 0
                while (totalRead < frameSizeBytes && running.get()) {
                    val read = stream.read(buffer, totalRead, frameSizeBytes - totalRead)
                    if (read < 0) {
                        // End of stream
                        running.set(false)
                        return
                    }
                    totalRead += read
                }

                if (totalRead < frameSizeBytes) continue

                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex < 0) continue

                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                inputBuffer.clear()
                inputBuffer.put(buffer, 0, frameSizeBytes)

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    frameSizeBytes,
                    System.nanoTime() / 1000,
                    0
                )
            } catch (e: InterruptedException) {
                break
            } catch (_: Exception) {
                if (running.get()) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get()) {
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputBufferIndex >= 0 -> {
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)

                            onEncodedFrame?.invoke(data, bufferInfo.presentationTimeUs)
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        extractAudioSpecificConfig(codec)
                    }
                }
            } catch (e: IllegalStateException) {
                if (running.get()) Log.e(TAG, "AAC encoder output error", e)
                break
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun pcmFrameSizeBytes(): Int {
        return AAC_SAMPLES_PER_ACCESS_UNIT * channelCount * PCM_BYTES_PER_SAMPLE
    }

    companion object {
        private const val TAG = "AacEncoder"
        // AAC-LC encodes 1024 samples per access unit.
        private const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024
        private const val PCM_BYTES_PER_SAMPLE = 2
    }
}
