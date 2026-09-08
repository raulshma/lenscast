package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AacFormatTest {

    // ── literals ──

    @Test
    fun `samples per access unit is the AAC-LC 1024 and PCM is 16-bit`() {
        assertEquals(1024, AacFormat.SAMPLES_PER_ACCESS_UNIT)
        assertEquals(2, AacFormat.PCM_BYTES_PER_SAMPLE)
    }

    @Test
    fun `default sample rate defers to the shared stream default`() {
        assertEquals(StreamDefaults.AUDIO_SAMPLE_RATE_HZ, AacFormat.DEFAULT_SAMPLE_RATE_HZ)
    }

    @Test
    fun `contract - RtspServer advances its audio RTP clock by exactly one AU`() {
        assertEquals(
            AacFormat.SAMPLES_PER_ACCESS_UNIT.toLong(),
            RtspServer.AUDIO_TIMESTAMP_INCREMENT,
        )
    }

    // ── PCM frame math ──

    @Test
    fun `pcm frame size scales with channel count`() {
        assertEquals(1024 * 1 * 2, AacFormat.pcmFrameSizeBytes(channelCount = 1))
        assertEquals(1024 * 2 * 2, AacFormat.pcmFrameSizeBytes(channelCount = 2))
    }

    // ── alignToFrame ──

    @Test
    fun `aligned values pass through unchanged`() {
        assertEquals(1920, AacFormat.alignToFrame(1920, bytesPerFrame = 2))
        assertEquals(0, AacFormat.alignToFrame(0, bytesPerFrame = 2))
    }

    @Test
    fun `unaligned values round up to the next whole frame`() {
        assertEquals(1920, AacFormat.alignToFrame(1919, bytesPerFrame = 2))
        assertEquals(4, AacFormat.alignToFrame(3, bytesPerFrame = 4))
        assertEquals(8, AacFormat.alignToFrame(5, bytesPerFrame = 4))
    }

    @Test
    fun `degenerate frame width still yields at least one byte`() {
        assertEquals(5, AacFormat.alignToFrame(5, bytesPerFrame = 1))
        assertEquals(1, AacFormat.alignToFrame(0, bytesPerFrame = 1))
        assertEquals(1, AacFormat.alignToFrame(-3, bytesPerFrame = 1))
    }

    // ── resolveBuffers: the probe ladder ──

    /** A probe that answers for the rates in [supported] only. */
    private fun probe(vararg supported: Int): (Int, Int) -> Int {
        return { sampleRate, _ -> if (sampleRate in supported) 4096 else -1 }
    }

    @Test
    fun `first supported rate in the ladder wins`() {
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1, minBufferSize = probe(16_000, 32_000))
        assertEquals(32_000, resolved.sampleRateHz)
        assertEquals(1, resolved.channelCount)
    }

    @Test
    fun `an earlier rate wins over a later one`() {
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1, minBufferSize = probe(48_000, 44_100))
        assertEquals(48_000, resolved.sampleRateHz)
    }

    @Test
    fun `the default rate is probed first so the usual device resolves immediately`() {
        val probed = mutableListOf<Int>()
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1) { sampleRate, _ ->
            probed += sampleRate
            8192
        }
        assertEquals(AacFormat.DEFAULT_SAMPLE_RATE_HZ, probed.first())
        // A positive answer ends the probe — the usual device resolves on the first rate alone.
        assertEquals(listOf(AacFormat.DEFAULT_SAMPLE_RATE_HZ), probed)
        assertEquals(AacFormat.DEFAULT_SAMPLE_RATE_HZ, resolved.sampleRateHz)
    }

    @Test
    fun `unsupported head of the ladder falls through to the first supported rate`() {
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1, minBufferSize = probe(16_000))
        assertEquals(16_000, resolved.sampleRateHz)
    }

    @Test
    fun `no supported rate anywhere throws rather than inventing a config`() {
        assertThrows(IllegalStateException::class.java) {
            AacFormat.resolveBuffers(requestedChannelCount = 1, minBufferSize = { _, _ -> -2 })
        }
    }

    @Test
    fun `two requested channels resolve the stereo config`() {
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 2, minBufferSize = probe(48_000))
        assertEquals(2, resolved.channelCount)
        assertEquals(AacFormat.CHANNEL_IN_STEREO, resolved.channelConfig)

        val mono = AacFormat.resolveBuffers(requestedChannelCount = 1, minBufferSize = probe(48_000))
        assertEquals(1, mono.channelCount)
        assertEquals(AacFormat.CHANNEL_IN_MONO, mono.channelConfig)
    }

    // ── resolveBuffers: buffer sizing ──

    @Test
    fun `read chunk targets 20ms of PCM and buffers cover the minimum and three chunks`() {
        // 48 kHz mono 16-bit = 96000 bytes/s → 20ms = 1920 bytes (already frame-aligned).
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1) { _, _ -> 1024 }
        assertEquals(1920, resolved.readChunkBytes)
        // max(minBuffer=1024, 3 * 1920 = 5760), frame-aligned → 5760.
        assertEquals(5760, resolved.bufferSizeBytes)
    }

    @Test
    fun `a large minimum buffer inflates the read chunk to half of itself, frame-aligned`() {
        // minBuffer 8191 → half = 4095 → aligned to a 2-byte frame = 4096.
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 1) { _, _ -> 8191 }
        assertEquals(4096, resolved.readChunkBytes)
        // max(8191, 3 * 4096 = 12288) → 12288, already aligned.
        assertEquals(12288, resolved.bufferSizeBytes)
    }

    @Test
    fun `stereo sizes double per frame`() {
        // 48 kHz stereo 16-bit = 192000 bytes/s → 20ms = 3840.
        val resolved = AacFormat.resolveBuffers(requestedChannelCount = 2) { _, _ -> 1024 }
        assertEquals(3840, resolved.readChunkBytes)
        assertEquals(11520, resolved.bufferSizeBytes)
    }

    // ── AudioSpecificConfig + the SDP fallback ──

    @Test
    fun `audio specific config matches the AAC-LC bit layout`() {
        // AAC-LC (2), 48 kHz (index 3), mono (1) → 00010 0011 0001 … = 0x11 0x88.
        assertArrayEquals(byteArrayOf(0x11, 0x88.toByte()), AacFormat.audioSpecificConfigBytes(48_000, 1))
        // 44.1 kHz is index 4 (even → no high bit): 00010 0100 0001 … = 0x12 0x08.
        assertArrayEquals(byteArrayOf(0x12, 0x08.toByte()), AacFormat.audioSpecificConfigBytes(44_100, 1))
    }

    @Test
    fun `fallback asc hex derives from the actual rate and channel count`() {
        // 48 kHz mono (the capture default) — the old hardcoded "1190" advertised stereo here.
        assertEquals("1188", AacFormat.fallbackAscHex(48_000, 1))
        assertEquals("1190", AacFormat.fallbackAscHex(48_000, 2))
        // 44.1 kHz mono: index 4, no high bit → 0x12 0x08.
        assertEquals("1208", AacFormat.fallbackAscHex(44_100, 1))
    }

    @Test
    fun `bytesToHex renders unsigned lowercase pairs`() {
        assertEquals("1188", AacFormat.bytesToHex(byteArrayOf(0x11, 0x88.toByte())))
        assertEquals("00ff", AacFormat.bytesToHex(byteArrayOf(0x00, 0xFF.toByte())))
    }
}
