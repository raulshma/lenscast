package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamDefaults

/**
 * One home for the AAC stream's format literals and the pure capture-buffer
 * math, so the samples-per-AU, PCM width, and sample-rate ladder cannot
 * drift between [RtspServer] (RTP timestamp increment), [AacEncoder] (input
 * frame sizing), [SdpBuilder] (SDP fallback bytes), and
 * [com.raulshma.lenscast.streaming.AudioStreamingManager] (mic capture).
 * Android-free apart from mirrored platform constants, so the math is
 * JVM-tested.
 */
object AacFormat {

    /** AAC-LC encodes 1024 samples per access unit — also the RTP timestamp advance per audio AU. */
    const val SAMPLES_PER_ACCESS_UNIT = 1024

    /** PCM16 — two bytes per sample. */
    const val PCM_BYTES_PER_SAMPLE = 2

    /** The mic-capture probe ladder: the first rate with a positive minimum buffer size wins. */
    val PROBE_SAMPLE_RATES_HZ = intArrayOf(48_000, 44_100, 32_000, 24_000, 16_000)

    /** Default capture rate — the shared stream default. */
    const val DEFAULT_SAMPLE_RATE_HZ = StreamDefaults.AUDIO_SAMPLE_RATE_HZ

    /** The reader publishes a chunk about this often; the buffer math sizes it in whole PCM frames. */
    const val AUDIO_READ_CHUNK_MS = 20

    // Mirror of AudioFormat — stable platform constants (see EncoderFormatPolicy).
    const val CHANNEL_IN_MONO = 0x10
    const val CHANNEL_IN_STEREO = 0x0C

    /** One PCM access unit's byte size at [channelCount] channels. */
    fun pcmFrameSizeBytes(channelCount: Int): Int =
        SAMPLES_PER_ACCESS_UNIT * channelCount * PCM_BYTES_PER_SAMPLE

    /** Rounds [value] up to a whole multiple of [bytesPerFrame] (frame-aligned reads and buffers). */
    fun alignToFrame(value: Int, bytesPerFrame: Int): Int {
        if (bytesPerFrame <= 1) return value.coerceAtLeast(1)
        val remainder = value % bytesPerFrame
        return if (remainder == 0) value else value + (bytesPerFrame - remainder)
    }

    /**
     * The resolved capture configuration: what [com.raulshma.lenscast.streaming.AudioStreamingManager]
     * opens the [android.media.AudioRecord] with and drives its reader by.
     */
    data class ResolvedBuffers(
        val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        val channelCount: Int = 1,
        val channelConfig: Int = CHANNEL_IN_MONO,
        val bufferSizeBytes: Int = 8192,
        val readChunkBytes: Int = 2048,
    )

    /**
     * The pure capture-resolution decision: probe [PROBE_SAMPLE_RATES_HZ] in
     * order through [minBufferSize] (the platform's AudioRecord.getMinBufferSize)
     * and size the record buffer and read chunk in whole PCM frames for the
     * first rate that answers positive. Throws when no rate is supported —
     * there is no silent fallback.
     */
    fun resolveBuffers(
        requestedChannelCount: Int,
        minBufferSize: (sampleRateHz: Int, channelConfig: Int) -> Int,
    ): ResolvedBuffers {
        val channelConfig = if (requestedChannelCount >= 2) CHANNEL_IN_STEREO else CHANNEL_IN_MONO

        for (sampleRate in PROBE_SAMPLE_RATES_HZ) {
            val minBuffer = minBufferSize(sampleRate, channelConfig)
            if (minBuffer > 0) {
                val resolvedChannelCount = if (channelConfig == CHANNEL_IN_STEREO) 2 else 1
                val bytesPerFrame = resolvedChannelCount * PCM_BYTES_PER_SAMPLE
                val bytesPerSecond = sampleRate * bytesPerFrame
                val targetReadChunkBytes = alignToFrame(
                    value = (bytesPerSecond * AUDIO_READ_CHUNK_MS) / 1000,
                    bytesPerFrame = bytesPerFrame,
                )
                val readChunkBytes = alignToFrame(
                    value = maxOf(targetReadChunkBytes, minBuffer / 2),
                    bytesPerFrame = bytesPerFrame,
                )
                val recordBufferBytes = alignToFrame(
                    value = maxOf(minBuffer, readChunkBytes * 3),
                    bytesPerFrame = bytesPerFrame,
                )
                return ResolvedBuffers(
                    sampleRateHz = sampleRate,
                    channelCount = resolvedChannelCount,
                    channelConfig = channelConfig,
                    bufferSizeBytes = recordBufferBytes,
                    readChunkBytes = readChunkBytes,
                )
            }
        }

        throw IllegalStateException("No supported audio recording configuration found")
    }

    // ── AudioSpecificConfig ──

    /**
     * The two-byte AAC AudioSpecificConfig for [sampleRateHz] at [channelCount]
     * (AAC-LC, audio object type 2) — what the encoder advertises when the
     * codec never hands one over.
     */
    fun audioSpecificConfigBytes(sampleRateHz: Int, channelCount: Int): ByteArray {
        val audioObjectType = 2 // AAC-LC
        val samplingFreqIndex = samplingFrequencyIndex(sampleRateHz)
        val channelConfig = channelCount

        val byte0 = ((audioObjectType shl 3) or (samplingFreqIndex shr 1)) and 0xFF
        val byte1 = (((samplingFreqIndex and 0x1) shl 7) or (channelConfig shl 3)) and 0xFF
        return byteArrayOf(byte0.toByte(), byte1.toByte())
    }

    private fun samplingFrequencyIndex(sampleRateHz: Int): Int = when (sampleRateHz) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
        44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
        16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
        else -> 0xF // explicit frequency
    }

    /**
     * The SDP's fmtp `config=` fallback for when no live AudioSpecificConfig
     * exists: the bytes SdpBuilder has always advertised (AAC-LC at the
     * default rate, "1190") — derived here rather than re-typed, and pinned
     * by test so the SDP output stays byte-identical.
     */
    val SDP_FALLBACK_ASC_HEX: String = audioSpecificConfigBytes(DEFAULT_SAMPLE_RATE_HZ, 2).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
