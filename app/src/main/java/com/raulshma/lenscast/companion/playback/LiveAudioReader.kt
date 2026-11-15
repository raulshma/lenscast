package com.raulshma.lenscast.companion.playback

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.InputStream

/** One ~40ms block of interleaved PCM16, sized by [PcmChunk.sampleRateHz] and [PcmChunk.channels]. */
data class PcmChunk(val samples: ShortArray, val sampleRateHz: Int, val channels: Int)

/**
 * Pure decode of the companion device's `/audio` payload — the client-side
 * mirror of web/src/audio/LiveAudioPlayer.ts minus everything Web Audio:
 * the wire contract is a chunked HTTP body of raw little-endian int16 PCM
 * (`X-Audio-Format: pcm_s16le`), self-described per response by
 * `X-Audio-Sample-Rate` / `X-Audio-Channels` headers (MediaResponder.serveAudio).
 *
 * The TS engine re-frames each network chunk into whole sample frames
 * (createPcmFramer, `bytesPerFrame = 2 * channelCount`), carrying partial
 * bytes across pushes and defaulting the headers to 48000 Hz / 1 channel
 * when absent — both behaviors ported here. Differences, on purpose:
 *  - emission cadence: the TS engine schedules whatever arrived onto the Web
 *    Audio clock as soon as it is sample-aligned; there is no fixed chunk
 *    size in it. This reader emits ~[CHUNK_DURATION_MS] blocks instead so
 *    the wave-2 AudioTrack sink gets an amortized write size at a steady
 *    cadence (40 ms sits at the TS engine's own scheduling granularity —
 *    its 50 ms playback-clock lookahead — without adding perceptible
 *    latency);
 *  - no AudioContext/autoplay handling, no reconnect ladder: those live with
 *    the wave-2 player; this class only turns bytes into sample frames.
 *
 * EOF completes the flow (a trailing sample-aligned remainder is emitted; a
 * dangling sub-sample byte — a stream that never was PCM16-aligned — is
 * dropped, matching createPcmFramer's `% bytesPerFrame` remainder
 * semantics). A mid-stream IOException propagates: reconnect pacing is the
 * caller's decision, as with the MJPEG source. The reads are blocking —
 * collect on a background dispatcher (`flowOn(Dispatchers.IO)` at the wave-2
 * call site); cancellation lands between chunk emissions.
 */
class LiveAudioReader {

    /**
     * Reads the raw body stream and emits interleaved PCM16 chunks.
     * [headerSampleRate] / [headerChannels] are the response's
     * `X-Audio-Sample-Rate` / `X-Audio-Channels` values as parsed by the
     * caller (pass null when a header is absent or unparseable); absent or
     * non-positive values fall back to the server's configured defaults.
     */
    fun read(input: InputStream, headerSampleRate: Int? = null, headerChannels: Int? = null): Flow<PcmChunk> =
        flow {
            val sampleRateHz = headerSampleRate?.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE_HZ
            val channels = headerChannels?.takeIf { it > 0 } ?: DEFAULT_CHANNELS
            val bytesPerSampleFrame = 2 * channels // PCM16: two bytes per sample, interleaved
            // 40 ms of frames per emission; truncated at absurdly low rates to
            // one frame so the loop below can always make progress.
            val framesPerChunk = ((sampleRateHz.toLong() * CHUNK_DURATION_MS) / 1000L)
                .toInt()
                .coerceAtLeast(1)
            val chunkBytes = framesPerChunk * bytesPerSampleFrame

            val scratch = ByteArray(maxOf(chunkBytes, DEFAULT_READ_BYTES))
            var pending = ByteArray(0)
            while (true) {
                if (!currentCoroutineContext().isActive) return@flow
                val read = input.read(scratch)
                if (read < 0) break // EOF: server stopped the stream — complete like the TS reader.read() done arm
                if (read == 0) continue
                pending = pending + scratch.copyOf(read)

                var offset = 0
                while (pending.size - offset >= chunkBytes) {
                    emit(chunkFrom(pending, offset, chunkBytes, sampleRateHz, channels))
                    offset += chunkBytes
                }
                if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
            }

            // EOF: emit the trailing sample-aligned remainder (a shorter final
            // chunk — the TS framer would carry it into a next push that never
            // comes), and drop a dangling odd byte: it is not a sample.
            val alignedBytes = pending.size - (pending.size % bytesPerSampleFrame)
            if (alignedBytes > 0) {
                emit(chunkFrom(pending, 0, alignedBytes, sampleRateHz, channels))
            }
        }

    private fun chunkFrom(bytes: ByteArray, offset: Int, byteCount: Int, sampleRateHz: Int, channels: Int): PcmChunk {
        val samples = ShortArray(byteCount / 2)
        var cursor = offset
        for (index in samples.indices) {
            // Little-endian decode (pcm_s16le): low byte first.
            samples[index] =
                ((bytes[cursor].toInt() and 0xFF) or (bytes[cursor + 1].toInt() shl 8)).toShort()
            cursor += 2
        }
        return PcmChunk(samples, sampleRateHz, channels)
    }

    companion object {
        /**
         * The emission cadence of this port. LiveAudioPlayer.ts has no fixed
         * chunk size (it re-frames per network arrival); 40 ms is this
         * reader's contract, sized near the TS engine's 50 ms scheduling
         * lookahead so AudioTrack writes amortize without added latency.
         */
        const val CHUNK_DURATION_MS = 40L

        /**
         * X-Audio-Sample-Rate fallback. Three sources agree: the server's
         * configured default (StreamDefaults.AUDIO_SAMPLE_RATE_HZ = 48_000,
         * surfaced through AudioStreamingManager → MediaResponder's header),
         * and the web client's own `'48000'` fallback in
         * LiveAudioPlayer.ts. 48 kHz mono PCM16 → 1920 samples / 3840 bytes
         * per 40 ms chunk.
         */
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000

        /**
         * X-Audio-Channels fallback: StreamDefaults.AUDIO_CHANNELS = 1
         * (mono mic capture), matching LiveAudioPlayer.ts's `'1'` fallback.
         */
        const val DEFAULT_CHANNELS = 1

        private const val DEFAULT_READ_BYTES = 8 * 1024
    }
}
