package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import java.io.InputStream
import java.nio.ByteBuffer

class AacEncoder {

    private var inputThread: Thread? = null

    private var sampleRateHz = AacFormat.DEFAULT_SAMPLE_RATE_HZ
    private var channelCount = 1
    private var bitrate = 128_000

    @Volatile
    var audioSpecificConfig: ByteArray? = null
        private set

    @Volatile
    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    // The MediaCodec lifecycle (running guard, start ladder, output-drain
    // thread, teardown) lives in the shared harness. This class keeps the AAC
    // format construction, the AudioSpecificConfig interpretation, and the
    // PCM input feed, plugged into the harness below.
    private val harness = MediaCodecEncoderHarness(
        tag = TAG,
        threadName = "AacEncoderOutput",
        startedMessage = { "AAC encoder started: ${sampleRateHz}Hz, ${channelCount}ch, ${bitrate}bps" },
        startFailureMessage = "Failed to start AAC encoder",
        outputErrorMessage = "AAC encoder output error",
        createCodec = {
            audioSpecificConfig = null
            MediaCodecAdapter(MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC))
        },
        configureCodec = { configureCodec(it) },
        onFormatChanged = { extractAudioSpecificConfig(it) },
        onOutput = { outputBuffer, info -> onOutputBuffer(outputBuffer, info) },
    )

    fun configure(sampleRateHz: Int, channelCount: Int, bitrateKbps: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount.coerceIn(StreamDefaults.AUDIO_CHANNELS_MIN, StreamDefaults.AUDIO_CHANNELS_MAX)
        this.bitrate = (bitrateKbps * 1000).coerceIn(
            StreamDefaults.AUDIO_BITRATE_MIN_KBPS * 1000,
            StreamDefaults.AUDIO_BITRATE_MAX_KBPS * 1000,
        )
    }

    fun setAudioStream(stream: InputStream?) {
        audioStream = stream
    }

    fun start(): Boolean {
        if (!harness.start()) return false

        harness.activeCodec?.let { codec ->
            // Extract AudioSpecificConfig from output format
            extractAudioSpecificConfig(codec)

            inputThread = Thread({ feedInput(codec) }, "AacEncoderInput").apply {
                isDaemon = true
                start()
            }
        }

        return true
    }

    fun stop() {
        if (!harness.stop()) return

        audioStream = null

        try {
            inputThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        inputThread = null

        Log.d(TAG, "AAC encoder stopped")
    }

    fun setBitrate(newBitrateKbps: Int) {
        bitrate = (newBitrateKbps * 1000).coerceIn(
            StreamDefaults.AUDIO_BITRATE_MIN_KBPS * 1000,
            StreamDefaults.AUDIO_BITRATE_MAX_KBPS * 1000,
        )
        // AAC encoder doesn't support dynamic bitrate changes via setParameters.
        // The new bitrate will take effect on next start().
        Log.d(TAG, "AAC bitrate set to ${bitrate}bps (effective on next start)")
    }

    private fun extractAudioSpecificConfig(codec: CodecLike) {
        try {
            val format = codec.outputFormat()
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
            audioSpecificConfig = AacFormat.audioSpecificConfigBytes(sampleRateHz, channelCount)
            Log.d(TAG, "AudioSpecificConfig computed: ${audioSpecificConfig!!.toHexString()}")
        }
    }

    @Volatile
    private var audioStream: InputStream? = null

    private fun feedInput(codec: CodecLike) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameSizeBytes = pcmFrameSizeBytes()
        val buffer = ByteArray(frameSizeBytes)

        while (harness.isRunning) {
            val stream = audioStream ?: break

            try {
                var totalRead = 0
                while (totalRead < frameSizeBytes && harness.isRunning) {
                    val read = stream.read(buffer, totalRead, frameSizeBytes - totalRead)
                    if (read < 0) {
                        // End of stream
                        harness.requestStop()
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
                if (harness.isRunning) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /**
     * The AAC half of the harness's output contract: every non-config buffer
     * with payload is one AAC access unit delivered to [onEncodedFrame].
     */
    private fun onOutputBuffer(outputBuffer: ByteBuffer, info: MediaCodecEncoderHarness.OutputInfo) {
        if (info.size > 0 &&
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        ) {
            val data = ByteArray(info.size)
            outputBuffer.position(info.offset)
            outputBuffer.get(data)

            onEncodedFrame?.invoke(data, info.presentationTimeUs)
        }
    }

    /**
     * The AAC configuration between the harness's create and start steps:
     * the audio MediaFormat, applied through the [CodecLike] seam.
     */
    private fun configureCodec(codec: CodecLike) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmFrameSizeBytes() * 2)
        }

        codec.configureEncode(format)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun pcmFrameSizeBytes(): Int {
        return AacFormat.pcmFrameSizeBytes(channelCount)
    }

    companion object {
        private const val TAG = "AacEncoder"
    }
}
