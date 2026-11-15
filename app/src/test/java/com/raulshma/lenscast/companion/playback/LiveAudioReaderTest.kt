package com.raulshma.lenscast.companion.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

/**
 * Pins the /audio payload decode against the server contract
 * (MediaResponder.serveAudio: X-Audio-Format pcm_s16le, X-Audio-Sample-Rate,
 * X-Audio-Channels) and LiveAudioPlayer.ts's framing math
 * (createPcmFramer: whole 2×channels-byte sample frames, partial bytes
 * carried, defaults 48000 Hz / 1 channel).
 *
 * Plain runBlocking collectors — the reader is a linear decode with no
 * conflation or reconnect logic, so a list of chunks plus the terminal
 * failure asserts everything the flow contract offers.
 */
class LiveAudioReaderTest {

    private val reader = LiveAudioReader()

    private fun pcm16le(vararg samples: Short): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return bytes
    }

    /** 40 ms of mono 48 kHz: StreamDefaults.AUDIO_SAMPLE_RATE_HZ × CHUNK_DURATION_MS. */
    private val monoChunkSamples = 1_920

    /** Collects the flow to completion: chunks in order plus the failure (null on clean EOF). */
    private fun readAll(
        input: InputStream,
        headerSampleRate: Int? = null,
        headerChannels: Int? = null,
    ): Pair<List<PcmChunk>, Throwable?> {
        val chunks = mutableListOf<PcmChunk>()
        var failure: Throwable? = null
        try {
            runBlocking {
                reader.read(input, headerSampleRate, headerChannels).collect { chunks.add(it) }
            }
        } catch (t: Throwable) {
            failure = t
        }
        return chunks to failure
    }

    // ── cadence ──

    @Test
    fun `a large body is re-framed into 40ms emissions at the default rate`() {
        // 10 chunks' worth of mono PCM16 (3840 B per 40 ms chunk); sample n
        // carries (n mod 1000) so chunk boundaries are value-verified.
        val samples = ShortArray(monoChunkSamples * 10) { index -> (index % 1000).toShort() }
        val (chunks, failure) = readAll(ByteArrayInputStream(pcm16le(*samples)))
        assertNull(failure)
        assertEquals(10, chunks.size)
        chunks.forEachIndexed { chunkIndex, chunk ->
            assertEquals(monoChunkSamples, chunk.samples.size)
            assertEquals(LiveAudioReader.DEFAULT_SAMPLE_RATE_HZ, chunk.sampleRateHz)
            assertEquals(LiveAudioReader.DEFAULT_CHANNELS, chunk.channels)
            assertEquals(
                "chunk n must start at sample 1920n",
                ((chunkIndex * monoChunkSamples) % 1000).toShort(),
                chunk.samples.first(),
            )
        }
    }

    @Test
    fun `network arrival granularity does not change the framing`() {
        // The same samples delivered one byte per read must decode identically
        // to the single-big-body case (createPcmFramer's carry semantics).
        val samples = ShortArray(monoChunkSamples * 3) { index -> (index and 0x3F).toShort() }
        val body = pcm16le(*samples)
        val perByteStream: InputStream = SequenceInputStream(
            Collections.enumeration(body.map { ByteArrayInputStream(byteArrayOf(it)) })
        )

        val (byteByByte, byteFailure) = readAll(perByteStream)
        val (inOnePiece, oneFailure) = readAll(ByteArrayInputStream(body))
        assertNull(byteFailure)
        assertNull(oneFailure)
        assertEquals(inOnePiece.size, byteByByte.size)
        inOnePiece.zip(byteByByte).forEach { (one, byByte) -> assertArrayEquals(one.samples, byByte.samples) }
        assertEquals(samples.toList(), byteByByte.flatMap { it.samples.toList() })
    }

    // ── endianness ──

    @Test
    fun `little-endian decode of known sample values`() {
        val body = pcm16le(1, -1, 32767, -32768, 258)
        val (chunks, failure) = readAll(ByteArrayInputStream(body))
        assertNull(failure)
        assertEquals(1, chunks.size)
        assertArrayEquals(shortArrayOf(1, -1, 32767, -32768, 258), chunks[0].samples)
    }

    // ── EOF and partial samples ──

    @Test
    fun `partial final chunk is emitted as a short sample-aligned remainder`() {
        // 2.5 chunks' worth: two full emissions, then 960 samples.
        val body = ByteArray(monoChunkSamples * 2 * 2 + monoChunkSamples * 2 / 2) { index -> (index and 0x1F).toByte() }
        val (chunks, failure) = readAll(ByteArrayInputStream(body))
        assertNull(failure)
        assertEquals(3, chunks.size)
        assertEquals(monoChunkSamples, chunks[0].samples.size)
        assertEquals(monoChunkSamples, chunks[1].samples.size)
        assertEquals(monoChunkSamples / 2, chunks[2].samples.size)
    }

    @Test
    fun `a dangling sub-sample byte at eof is dropped`() {
        val body = pcm16le(10, 20) + byteArrayOf(0x55) // 3 bytes = 1.5 samples
        val (chunks, failure) = readAll(ByteArrayInputStream(body))
        assertNull(failure)
        assertEquals(1, chunks.size)
        assertArrayEquals(shortArrayOf(10, 20), chunks[0].samples)
    }

    @Test
    fun `an empty body completes without emissions`() {
        val (chunks, failure) = readAll(ByteArrayInputStream(ByteArray(0)))
        assertNull(failure)
        assertEquals(0, chunks.size)
    }

    // ── header hints vs defaults ──

    @Test
    fun `header hints change rate channels and therefore chunk sizing`() {
        // 16 kHz stereo: 40 ms = 640 frames = 1280 interleaved samples.
        val body = ByteArray(1280 * 2 * 3) { index -> (index and 0x0F).toByte() }
        val (chunks, failure) = readAll(
            ByteArrayInputStream(body),
            headerSampleRate = 16_000,
            headerChannels = 2,
        )
        assertNull(failure)
        assertEquals(3, chunks.size)
        chunks.forEach { chunk ->
            assertEquals(1_280, chunk.samples.size)
            assertEquals(16_000, chunk.sampleRateHz)
            assertEquals(2, chunk.channels)
        }
    }

    @Test
    fun `invalid header hints fall back to the server defaults`() {
        val body = ByteArray(monoChunkSamples * 2) { index -> (index and 0x07).toByte() }
        val (chunks, failure) = readAll(
            ByteArrayInputStream(body),
            headerSampleRate = 0,
            headerChannels = -1,
        )
        assertNull(failure)
        assertEquals(1, chunks.size)
        assertEquals(LiveAudioReader.DEFAULT_SAMPLE_RATE_HZ, chunks[0].sampleRateHz)
        assertEquals(LiveAudioReader.DEFAULT_CHANNELS, chunks[0].channels)
        assertEquals(monoChunkSamples, chunks[0].samples.size)
    }

    @Test
    fun `a mid-stream read failure surfaces instead of silently completing`() {
        // One full chunk arrives, then the underlying stream fails: the flow
        // must emit the chunk and then propagate the error (the caller owns
        // the reconnect decision).
        val input = object : ByteArrayInputStream(ByteArray(monoChunkSamples * 2)) {
            private var reads = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads += 1
                if (reads > 1) throw IOException("socket reset")
                return super.read(b, off, len)
            }
        }
        val (chunks, failure) = readAll(input)
        assertEquals(1, chunks.size)
        assertEquals(monoChunkSamples, chunks[0].samples.size)
        assertTrue("expected the mid-stream IOException, was: $failure", failure is IOException)
    }
}
